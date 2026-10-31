/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 模糊引擎。
 * <p>职责：L1 short_term 短期事件记忆的完整「自然遗忘」生命周期——写入（先进 L1）、
 * <b>清醒期艾宾浩斯渐进模糊</b>（三维降级：细节粒度/确定性/检索难度-embedding 噪声）、
 * <b>睡眠清洗</b>（用户 语义：L1 有效期=一段清醒时间，隔夜按重要度分档——
 * 琐事合并压缩重存 / 重要+临睡前模糊跳升 / 重复或高重要蒸馏进 L2）、超清醒兜底、维护清理。</p>
 * <ul>
 * <li><b>写入</b>：{@link #writeShortTerm}——episodic 类抽取/事件/行动经历统一先落
 * actor_memory_working(item_type=short_term) + 1:1 fuzz_state 行 + 原始内容向量化
 * （开关 {@code write-short-term-first}，关闭=回直进 L2 现状）；</li>
 * <li><b>模糊节奏</b>：艾宾浩斯先快后慢——初始间隔 1800 游戏秒（30 游戏分钟，高重要记忆
 * ×(1+importance) 拉长首间隔），每次触发 ×1.8 指数增长，封顶 1 游戏日；
 * fuzz_rate=base×(1−importance×0.7)（0.02 封底）——高重要模糊极慢；</li>
 * <li><b>三维降级</b>：① 细节粒度（AI scene=memory_fuzz / 规则正则剥离时间·数字·对话原文）
 * ② 确定性（限定词按 fuzz_level 档位：0.3~0.7「可能」/&gt;0.7「好像·记不太清」）
 * ③ 检索难度（embedding 向量高斯噪声 σ=fuzz_level×max-noise，确定性种子可复现）；</li>
 * <li><b>睡眠清洗</b>：{@link #onSleepAction}（NpcStateService 大睡关键词挂钩 + 12 游戏时
 * 冷却）→ 三档处理：琐事（重要度≤2）AI/规则合并为一条概括记忆重存 L1；
 * 重要度 3 或临睡前窗口内 → fuzz_level +0.3（封顶 0.7）；重要度≥4 或检索≥3 →
 * MemoryDistillService 蒸馏进 L2（谱系可追溯）；</li>
 * <li><b>超时兜底</b>：{@link #enforceAwakeFallback}——角色清醒超 24 游戏时无睡眠动作则
 * 强制睡眠清洗（维护循环每 30 分钟 + 批量巩固时点双触发）。</li>
 * </ul>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§3 +
 * 用户新增语义（睡眠清洗/琐事合并/临睡前窗口， 逐题确认）。</p>
 */
@Slf4j
@Service
public class L1FuzzificationService {

    /** L1 短期事件记忆 item_type（actor_memory_working.item_type 扩展值） */
    public static final String ITEM_SHORT_TERM = "short_term";

    /** fuzz_state 状态：进行中 */
    public static final String FUZZ_STATUS_ACTIVE = "active";
    /** fuzz_state 状态：已蒸馏进 L2 */
    public static final String FUZZ_STATUS_DISTILLED = "distilled";
    /** fuzz_state 状态：已归档（睡眠清洗琐事合并后） */
    public static final String FUZZ_STATUS_ARCHIVED = "archived";

    /** AI 场景：模糊化 */
    public static final String SCENE_MEMORY_FUZZ = "memory_fuzz";
    /** AI 场景：睡眠琐事合并概括 */
    public static final String SCENE_MEMORY_SLEEP_MERGE = "memory_sleep_merge";

    /** 规则降级 prompt 标记（fuzz_history_json 记录来源） */
    private static final String RULES_PROMPT_TAG = "rules";

    /** 睡眠清洗模糊跳升 prompt 标记 */
    private static final String SLEEP_JUMP_TAG = "sleep-jump";

    // ==================== 配置（application.yml holzyn.actor.memory.fuzz.*） ====================

    /** L1 模糊引擎总开关（false=不写 short_term/不模糊/不睡眠清洗，行为回到直进 L2 现状） */
    @Value("${holzyn.actor.memory.fuzz.enabled:true}")
    private boolean enabled;

    /** 写入路径开关：episodic 类先进 L1 short_term（false=回直进 L2 现状） */
    @Value("${holzyn.actor.memory.fuzz.write-short-term-first:true}")
    private boolean writeShortTermFirst;

    /** 艾宾浩斯初始间隔（游戏秒，默认 1800=30 游戏分钟） */
    @Value("${holzyn.actor.memory.fuzz.initial-interval-seconds:1800}")
    private long initialIntervalSeconds;

    /** 艾宾浩斯指数增长因子（默认 1.8） */
    @Value("${holzyn.actor.memory.fuzz.growth-factor:1.8}")
    private double growthFactor;

    /** 艾宾浩斯间隔封顶（游戏秒，默认 86400=1 游戏日） */
    @Value("${holzyn.actor.memory.fuzz.max-interval-seconds:86400}")
    private long maxIntervalSeconds;

    /** 每次触发模糊增量基准（0~1，默认 0.15） */
    @Value("${holzyn.actor.memory.fuzz.base-rate:0.15}")
    private double baseRate;

    /** 重要度阻尼系数（默认 0.7：rate=base×(1−importance×0.7)） */
    @Value("${holzyn.actor.memory.fuzz.dampening-factor:0.7}")
    private double dampeningFactor;

    /** 模糊增量封底（默认 0.02：高重要记忆仍极慢模糊，不为 0） */
    @Value("${holzyn.actor.memory.fuzz.rate-floor:0.02}")
    private double rateFloor;

    /** embedding 最大噪声幅度（默认 0.12：σ=fuzz_level×max-noise） */
    @Value("${holzyn.actor.memory.fuzz.max-noise:0.12}")
    private double maxNoise;

    /** 睡眠清洗开关（false=不清洗，仅清醒期渐进模糊） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.enabled:true}")
    private boolean sleepCleanupEnabled;

    /** 大睡关键词（命中才触发隔夜清洗；小憩类词排除在外） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.big-sleep-keywords:睡觉,就寝,卧床,歇息,眠,睡}")
    private List<String> bigSleepKeywords;

    /** 睡眠清洗冷却（游戏秒，默认 43200=12 游戏时：同一"夜"只洗一次） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.min-interval-game-seconds:43200}")
    private long sleepMinIntervalGameSeconds;

    /** 超清醒兜底阈值（游戏秒，默认 86400=24 游戏时：超时无睡眠动作强制清洗） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.awake-max-game-seconds:86400}")
    private long awakeMaxGameSeconds;

    /** 临睡前窗口（游戏秒，默认 10800=3 游戏时：窗口内写入的记忆计入"临睡前"） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.pre-sleep-window-seconds:10800}")
    private long preSleepWindowSeconds;

    /** 睡眠模糊跳升量（默认 +0.3） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.fuzz-jump:0.3}")
    private double fuzzJump;

    /** 睡眠模糊跳升封顶（默认 0.7） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.fuzz-jump-cap:0.7}")
    private double fuzzJumpCap;

    /** 琐事判定阈值（重要度 ≤ 该值=琐事，默认 2） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.trivial-importance:2}")
    private int trivialImportance;

    /** 睡眠清洗蒸馏触发：重要度 ≥ 该值（1~5，默认 4；对应 importance_score≥0.7 快车道） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.distill-importance:4}")
    private int distillImportance;

    /** 睡眠清洗蒸馏触发：检索 ≥ 该值（默认 3：反复被检索=重要，强化保留） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.distill-retrieval:3}")
    private int distillRetrieval;

    /** 琐事合并行起点模糊度（默认 0.5：概括记忆"记不太清"观感） */
    @Value("${holzyn.actor.memory.fuzz.sleep-cleanup.merged-fuzz-start:0.5}")
    private double mergedFuzzStart;

    /** 维护循环开关（超清醒兜底 + 孤儿清理） */
    @Value("${holzyn.actor.memory.fuzz.maintenance-enabled:true}")
    private boolean maintenanceEnabled;

    // ==================== 依赖 ====================

    private final ActorMemoryWorkingRepository workingRepository;
    private final ActorMemoryFuzzStateRepository fuzzRepository;
    private final WorldClockService worldClockService;
    private final MemoryVectorService memoryVectorService;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /** 蒸馏服务 */
    @Autowired(required = false)
    private MemoryDistillService distillService;

    /** AI 强度闸门（四档联动：programmatic 纯规则 / light 低频 AI / full·max 全 AI） */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 当前用户（AI 凭据归属） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 项目仓库（超清醒兜底按项目遍历） */
    @Autowired(required = false)
    private ActorProjectRepository projectRepository;

    /**
     * 世界运行时门控（用户需求：启动全静止，进入沙盘才运行；null=未装配[单测]不过滤——
     * 未运行世界跳过超清醒兜底/孤儿清理等维护，世界静止=数据静止）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService runtimeGate;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（memory_fuzz / memory_sleep_merge 属批4 记忆链·角色视角）：AI 请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）——模糊/合并判断知道「这是谁的记忆、世界与处境如何」；
     * null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 每角色最近一次睡眠清洗游戏秒（进程内存态；重启丢失=冷却从 0 起，无副作用） */
    private final Map<String, Long> lastSleepCleanupByRef = new ConcurrentHashMap<>();

    public L1FuzzificationService(ActorMemoryWorkingRepository workingRepository,
                                  ActorMemoryFuzzStateRepository fuzzRepository,
                                  WorldClockService worldClockService,
                                  MemoryVectorService memoryVectorService,
                                  AiSpringChatGateway aiChatGateway,
                                  PromptTemplateService promptTemplateService,
                                  UsageLogService usageLogService,
                                  ObjectMapper objectMapper) {
        this.workingRepository = workingRepository;
        this.fuzzRepository = fuzzRepository;
        this.worldClockService = worldClockService;
        this.memoryVectorService = memoryVectorService;
        this.aiChatGateway = aiChatGateway;
        this.promptTemplateService = promptTemplateService;
        this.usageLogService = usageLogService;
        this.objectMapper = objectMapper;
    }

    // ==================== 写入 ====================

    /** 写入路径开关（调用方判定用）。 */
    public boolean isWriteShortTermFirst() {
        return enabled && writeShortTermFirst;
    }

    /**
     * 写一条 L1 短期事件记忆（working 行 item_type=short_term + 1:1 fuzz_state 行 +
     * 原始内容向量化）。
     * <p>与同角色 active short_term 既有记忆做 Dice 去重（复用既有去重阈值）；
     * gameSecond 缺失时按世界时钟当前引擎位置兜底（仍缺失=不写，零阻断）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（special:{id}/crowd:{id}）
     *
     * @param content 经历内容
     *
     * @param importance 重要度 1~5
     *
     * @param gameSecond 记忆发生时游戏秒（可空=按时钟兜底）
     *
     * @return true=已写入 L1
     */
    @Transactional
    public boolean writeShortTerm(Long projectId, String characterRef, String content,
                                  int importance, Long gameSecond) {
        if (!isWriteShortTermFirst() || projectId == null || characterRef == null
                || characterRef.isBlank() || content == null || content.isBlank()) {
            return false;
        }
        String ref = characterRef.trim();
        int imp = clampImportance(importance);
        // 与同角色 active short_term 既有记忆去重（防同经历重复落 L1）
        List<ActorMemoryWorking> existing = workingRepository
                .findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, ref, ITEM_SHORT_TERM, "active", 0);
        if (!existing.isEmpty()) {
            List<String> texts = existing.stream().map(ActorMemoryWorking::getContent).toList();
            if (MemoryDedup.isDuplicate(content, texts, 0.6)) {
                log.debug("[L1 模糊] short_term 去重命中，跳过：project={} ref={}", projectId, ref);
                return false;
            }
        }
        Long gs = gameSecond;
        if (gs == null && worldClockService != null) {
            try {
                gs = worldClockService.currentEngineGameSecond(projectId);
            } catch (Exception ignored) {
                gs = null;
            }
        }
        if (gs == null) {
            return false;
        }
        // working 行
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setProjectId(projectId);
        w.setCharacterRef(ref);
        w.setSessionKey("auto");
        w.setItemType(ITEM_SHORT_TERM);
        w.setContent(content);
        w.setSource(ActorMemoryTypes.SOURCE_ACTION);
        w.setStatus("active");
        w.setImportanceHint(imp);
        w.setImportanceScore(imp / 5.0);
        w.setGameSecond(gs);
        workingRepository.save(w);
        // fuzz_state 行（1:1）
        ActorMemoryFuzzState fs = new ActorMemoryFuzzState();
        fs.setProjectId(projectId);
        fs.setMemoryWorkingId(w.getId());
        fs.setCharacterRef(ref);
        fs.setOriginalContent(content);
        fs.setCurrentContent(content);
        fs.setFuzzLevel(0.0);
        fs.setImportanceScore(imp / 5.0);
        fs.setLastFuzzGameSecond(null);
        fs.setNextFuzzGameSecond(gs + initialInterval(imp / 5.0));
        fs.setFuzzCount(0);
        fs.setFuzzHistoryJson("[]");
        fs.setRetrievalCount(0);
        fs.setEmbeddingNoise(0.0);
        fs.setStatus(FUZZ_STATUS_ACTIVE);
        fuzzRepository.save(fs);
        // 原始内容向量化（检索难度基准；失败零阻断）
        // ⚠️ 世界运行抓修：向量化 = 一次 embedding HTTP 调用，绝不能留在事务内——原实现
        // 写在 @Transactional 方法体内（且常被 AgentActionService.execute 等更大的事务嵌套，那些事务
        // 前面已改写 actor_state/actor_character），等于「一个 HTTP 往返期间行锁一直被按住」。
        // 实测（rate=48、20 角色并行决策）在两个游戏小时边界集中出现
        // "Timeout trying to lock table actor_state" 31 次、4 次情绪 AI 写回重试 3 次后彻底丢失。
        // 改为提交后执行（无事务时立即执行）；w.getId() 在保存时已生成，提交后行必然存在。
        final Long workingId = w.getId();
        com.holzyn.actor.common.AfterCommit.runAfterCommit("L1 写入向量化",
                () -> memoryVectorService.addVector(currentUserId(), projectId, workingId, content));
        log.debug("[L1 模糊] 写入 short_term：project={} ref={} 重要度={} 游戏秒={} id={}",
                projectId, ref, imp, gs, w.getId());
        return true;
    }

    /** 当前用户 id（null 回退 1）。 */
    private Long currentUserId() {
        try {
            return currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        } catch (Exception e) {
            return 1L;
        }
    }

    // ==================== 清醒期艾宾浩斯渐进模糊（每日钩子 + 手动触发） ====================

    /**
     * 模糊扫描（WorldEngine 游戏日钩子/批量巩固时点调用）：扫描全部到点的 active fuzz_state，
     * 逐条执行一次模糊（单条失败隔离不阻断）。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}（模糊含 AI
     * scene=memory_fuzz，旧实现整轮扫描一个事务会跨条目持锁；现 AI 在事务外，写阶段由
     * {@link #fuzzOne} 内的仓库 save 短事务承担）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param nowGameSecond 当前游戏秒
     *
     * @return 实际模糊条数
     */
    public int scanDueFuzz(Long projectId, Long nowGameSecond) {
        if (!enabled || projectId == null || nowGameSecond == null) {
            return 0;
        }
        List<ActorMemoryFuzzState> due = fuzzRepository
                .findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(projectId, FUZZ_STATUS_ACTIVE);
        int done = 0;
        for (ActorMemoryFuzzState fs : due) {
            if (fs.getNextFuzzGameSecond() == null || fs.getNextFuzzGameSecond() > nowGameSecond) {
                continue;
            }
            try {
                if (fuzzOne(projectId, fs, nowGameSecond)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("[L1 模糊] 单条模糊失败（隔离继续）: project={} state={} : {}",
                        projectId, fs.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[L1 模糊] 扫描完成：project={} 模糊 {} 条（到点 {} 条）", projectId, done, due.size());
        }
        return done;
    }

    /**
     * 单条模糊一步：fuzz_rate 增量（importance 约束）→ 三维降级（AI scene=memory_fuzz /
     * 规则降级，四档联动）→ embedding 噪声（σ=fuzz_level×max-noise）→ 历史/下次触发更新。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——AI 模糊
     * （scene=memory_fuzz，秒级）在事务外执行，写阶段（历史追加 + fuzz_state 更新）由
     * fuzzRepository.save 毫秒级短事务承担；噪声向量化 HTTP 保持 {@code AfterCommit} 提交后执行。</p>
     *
     * @return true=本次执行了模糊（fuzz_level 有变化）
     */
    public boolean fuzzOne(Long projectId, ActorMemoryFuzzState fs, Long nowGameSecond) {
        if (fs == null || !FUZZ_STATUS_ACTIVE.equals(fs.getStatus())) {
            return false;
        }
        double imp = fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore();
        double cur = fs.getFuzzLevel() == null ? 0.0 : fs.getFuzzLevel();
        // fuzz_rate = base×(1−importance×0.7)，0.02 封底——高重要记忆模糊极慢
        double rate = Math.max(rateFloor, baseRate * (1.0 - imp * dampeningFactor));
        double target = Math.min(1.0, cur + rate);
        // 三维降级（AI 档位联动：max/full 每次 AI；light fuzz_count%2==0 用 AI；programmatic 纯规则）
        boolean useAi = aiAllowed(projectId) && !(isLight(projectId) && fs.getFuzzCount() % 2 != 0);
        String before = fs.getCurrentContent() == null ? "" : fs.getCurrentContent();
        FuzzResult fr = useAi ? aiFuzz(projectId, fs.getCharacterRef(), nowGameSecond, before, cur, target)
                : rulesFuzz(before, target);
        String after = fr.content();
        if (after == null || after.isBlank()) {
            after = rulesFuzz(before, target).content();
        }
        // 记录历史（幂等可追溯）
        appendHistory(fs, nowGameSecond, cur, target, useAi, fr.prompt());
        fs.setCurrentContent(after);
        fs.setFuzzLevel(target);
        fs.setLastFuzzGameSecond(nowGameSecond);
        fs.setFuzzCount((fs.getFuzzCount() == null ? 0 : fs.getFuzzCount()) + 1);
        fs.setNextFuzzGameSecond(nextInterval(nowGameSecond, imp, fs.getFuzzCount()));
        // 检索难度：embedding 噪声重向量化（σ=fuzz_level×max-noise；失败保留旧向量零阻断）
        double sigma = target * maxNoise;
        fs.setEmbeddingNoise(sigma);
        // 抓修：噪声向量化同样是 embedding HTTP 调用 → 挪到事务提交后（见 writeShortTerm 说明）
        final Long fuzzWorkingId = fs.getMemoryWorkingId();
        final String fuzzAfter = after;
        com.holzyn.actor.common.AfterCommit.runAfterCommit("L1 模糊噪声向量化",
                () -> memoryVectorService.applyNoiseAndStore(currentUserId(), projectId, fuzzWorkingId,
                        fuzzAfter, sigma, fuzzWorkingId * 31L + nowGameSecond));
        fuzzRepository.save(fs);
        log.debug("[L1 模糊] 单条模糊完成：state={} {} → {}（importance={} ai={}）",
                fs.getId(), round2(cur), round2(target), round2(imp), useAi);
        return true;
    }

    /**
     * 手动触发一次模糊（调试/冒烟 API）。
     * <p><b></b>：不再带 {@code @Transactional}（委托的 {@link #fuzzOne}
     * 含 AI，事务边界随其拆法）。</p>
     *
     * @return true=已模糊
     */
    public boolean triggerFuzz(Long projectId, Long memoryWorkingId, Long nowGameSecond) {
        if (!enabled || projectId == null || memoryWorkingId == null) {
            return false;
        }
        Long gs = nowGameSecond;
        if (gs == null && worldClockService != null) {
            try {
                gs = worldClockService.currentEngineGameSecond(projectId);
            } catch (Exception ignored) {
                gs = null;
            }
        }
        if (gs == null) {
            return false;
        }
        ActorMemoryFuzzState fs = fuzzRepository.findByMemoryWorkingId(memoryWorkingId).orElse(null);
        if (fs == null) {
            return false;
        }
        // 手动触发无视艾宾浩斯间隔，直接到点
        fs.setNextFuzzGameSecond(0L);
        return fuzzOne(projectId, fs, gs);
    }

    /** 下次触发间隔（艾宾浩斯：initial×(1+importance) 起步，×growth^count，封顶 max）。 */
    private long nextInterval(Long nowGameSecond, double importance, int count) {
        double interval = initialInterval(importance);
        for (int i = 0; i < count; i++) {
            interval = Math.min(maxIntervalSeconds, interval * growthFactor);
        }
        return nowGameSecond + (long) Math.max(60, interval);
    }

    /** 初始间隔（高重要记忆起步间隔拉长：×(1+importance)）。 */
    private long initialInterval(double importance) {
        double base = Math.max(60, initialIntervalSeconds);
        return (long) (base * (1.0 + clamp01(importance)));
    }

    // ==================== 睡眠清洗（用户新增语义：L1 有效期=一段清醒时间） ====================

    /**
     * 睡眠动作触发入口（NpcStateService.onNpcAction 大睡关键词命中时挂钩）：
     * 冷却判定（距上次清洗 ≥ 12 游戏时）→ 执行该角色睡眠清洗。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——清洗链含 AI
     * （蒸馏/琐事合并，秒级），旧实现连 {@code NpcStateService.onNpcAction} 的事务一起把
     * actor_state/actor_memory 行锁持有整个清洗时长。现清洗在事务外执行（{@link #sleepCleanup}
     * 内部写阶段为仓库 save 短事务）；{@code onNpcAction} 侧已改经 {@code AfterCommit} 延迟到
     * 行动事务提交后再调本方法（见 NpcStateService.onNpcAction 注释）。</p>
     *
     * @return true=本次执行了清洗
     */
    public boolean onSleepAction(Long projectId, String characterRef, Long gameSecond) {
        if (!enabled || !sleepCleanupEnabled || projectId == null
                || characterRef == null || characterRef.isBlank()) {
            return false;
        }
        String ref = characterRef.trim();
        Long gs = gameSecond;
        if (gs == null && worldClockService != null) {
            try {
                gs = worldClockService.currentEngineGameSecond(projectId);
            } catch (Exception ignored) {
                gs = null;
            }
        }
        if (gs == null) {
            return false;
        }
        Long last = lastSleepCleanupByRef.get(projectId + ":" + ref);
        if (last != null && gs - last < sleepMinIntervalGameSeconds) {
            return false; // 同一"夜"只洗一次（小憩/重复触发防抖）
        }
        if (last != null && gs < last) {
            return false; // 时钟回拨保护
        }
        int cleaned = sleepCleanup(projectId, ref, gs);
        if (cleaned >= 0) {
            lastSleepCleanupByRef.put(projectId + ":" + ref, gs);
        }
        return cleaned > 0;
    }

    /**
     * 大睡关键词判定（供 NpcStateService 挂钩使用；"小憩/打盹/午休"等不算）。
     */
    public boolean isBigSleepAction(String actionText) {
        if (actionText == null || actionText.isBlank()) {
            return false;
        }
        for (String kw : bigSleepKeywords) {
            if (kw != null && !kw.isBlank() && actionText.contains(kw.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 睡眠清洗核心：对该角色全部 active short_term 记忆按重要度分档处理——
     * ① 蒸馏档（重要度≥distill-importance 或检索≥distill-retrieval）→ MemoryDistillService 蒸馏进 L2；
     * ② 琐事档（重要度≤trivial-importance）→ 合并为一条概括记忆重存 L1（AI scene=memory_sleep_merge /
     * 规则模板，信息量大减），原琐事行清理；
     * ③ 模糊档（重要度=3 或临睡前窗口内写入）→ fuzz_level 跳升 +0.3（封顶 0.7）。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——清洗链含多处 AI
     * （蒸馏约 80s/条、琐事合并约数十秒），旧实现整场清洗一个事务，前一条的写锁会在后一条的
     * AI 期间持续持有，连锁撞 {@code LOCK_TIMEOUT=10000}（实测 79 次 actor_memory 锁超时即此链
     * 与反思链共同导致）。现在逐条 AI 在事务外，写阶段由各仓库 save 毫秒级短事务承担。</p>
     *
     * @return 处理条数（蒸馏+合并+模糊合计）
     */
    public int sleepCleanup(Long projectId, String characterRef, Long nowGameSecond) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return 0;
        }
        String ref = characterRef.trim();
        List<ActorMemoryFuzzState> states = fuzzRepository
                .findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(projectId, ref, FUZZ_STATUS_ACTIVE);
        if (states.isEmpty()) {
            return 0;
        }
        List<ActorMemoryFuzzState> distill = new ArrayList<>();
        List<ActorMemoryFuzzState> trivial = new ArrayList<>();
        List<ActorMemoryFuzzState> fuzzJump = new ArrayList<>();
        for (ActorMemoryFuzzState fs : states) {
            double imp = fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore();
            int imp1to5 = (int) Math.round(imp * 5.0);
            int retrieval = fs.getRetrievalCount() == null ? 0 : fs.getRetrievalCount();
            if (imp1to5 >= distillImportance || retrieval >= distillRetrieval) {
                distill.add(fs);            // ① 高重要/反复检索 → 蒸馏 L2
            } else if (imp1to5 <= trivialImportance) {
                trivial.add(fs);            // ② 琐事 → 合并压缩重存
            } else {
                fuzzJump.add(fs);           // ③ 重要一点/临睡前 → 模糊跳升
            }
        }
        // 临睡前窗口内写入的记忆（无论档位已处理剩余）并入模糊档：窗口判定=写入游戏秒在清洗前
        // preSleepWindow 内且未被蒸馏/合并（蒸馏档已按重要性优先处理）
        for (ActorMemoryFuzzState fs : states) {
            if (fuzzJump.contains(fs)) {
                continue;
            }
            Long writeGs = fs.getLastFuzzGameSecond() == null
                    ? workingGameSecond(fs.getMemoryWorkingId(), null)
                    : fs.getLastFuzzGameSecond();
            if (writeGs != null && nowGameSecond - writeGs <= preSleepWindowSeconds) {
                // 临睡前刚写入的 3 档记忆已蒸馏/合并，此处仅兜底模糊档（理论上无重叠）
                fuzzJump.add(fs);
            }
        }
        int handled = 0;
        // ① 蒸馏（最高优先：重要信息必进 L2）
        if (distillService != null) {
            for (ActorMemoryFuzzState fs : distill) {
                try {
                    if (distillService.distillL1toL2(projectId, fs, nowGameSecond)) {
                        handled++;
                    }
                } catch (Exception e) {
                    log.warn("[L1 模糊] 睡眠蒸馏失败（隔离继续）: project={} state={} : {}",
                            projectId, fs.getId(), e.getMessage());
                }
            }
        } else {
            handled += distill.size(); // 未装配（单测）视为已处理
        }
        // ③ 模糊跳升
        for (ActorMemoryFuzzState fs : fuzzJump) {
            try {
                if (fuzzJumpOne(fs, nowGameSecond)) {
                    handled++;
                }
            } catch (Exception e) {
                log.warn("[L1 模糊] 睡眠模糊跳升失败（隔离继续）: project={} state={} : {}",
                        projectId, fs.getId(), e.getMessage());
            }
        }
        // ② 琐事合并（最后：清理可能改变集合）
        if (!trivial.isEmpty()) {
            try {
                if (mergeTrivia(projectId, ref, trivial, nowGameSecond)) {
                    handled += trivial.size();
                }
            } catch (Exception e) {
                log.warn("[L1 模糊] 琐事合并失败（隔离继续）: project={} ref={} : {}",
                        projectId, ref, e.getMessage());
            }
        }
        log.info("[L1 模糊] 睡眠清洗完成：project={} ref={} 处理 {} 条（蒸馏 {} / 模糊 {} / 琐事合并 {}）",
                projectId, ref, handled, distill.size(), fuzzJump.size(), trivial.size());
        return handled;
    }

    /** working 行的 game_second（fuzz_state 未冗余时回退读 working 行）。 */
    private Long workingGameSecond(Long memoryWorkingId, Long fallback) {
        if (memoryWorkingId == null) {
            return fallback;
        }
        try {
            ActorMemoryWorking w = workingRepository.findById(memoryWorkingId).orElse(null);
            return w == null ? fallback : (w.getGameSecond() == null ? fallback : w.getGameSecond());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 睡眠模糊跳升：fuzz_level +fuzzJump（封顶 fuzzJumpCap），同步重向量化噪声 + 历史记录。
     */
    private boolean fuzzJumpOne(ActorMemoryFuzzState fs, Long nowGameSecond) {
        double cur = fs.getFuzzLevel() == null ? 0.0 : fs.getFuzzLevel();
        double target = Math.min(fuzzJumpCap, cur + fuzzJump);
        if (target <= cur) {
            return false;
        }
        // 跳升同时做一次规则级内容模糊（跳过 AI——睡眠清洗是批量操作，规则降级确定性）
        FuzzResult fr = rulesFuzz(fs.getCurrentContent(), target);
        appendHistory(fs, nowGameSecond, cur, target, false, SLEEP_JUMP_TAG);
        fs.setCurrentContent(fr.content());
        fs.setFuzzLevel(target);
        fs.setLastFuzzGameSecond(nowGameSecond);
        fs.setFuzzCount((fs.getFuzzCount() == null ? 0 : fs.getFuzzCount()) + 1);
        fs.setNextFuzzGameSecond(nextInterval(nowGameSecond,
                fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore(), fs.getFuzzCount()));
        double sigma = target * maxNoise;
        fs.setEmbeddingNoise(sigma);
        // 抓修：同上——跳升噪声向量化挪到事务提交后
        final Long jumpWorkingId = fs.getMemoryWorkingId();
        final String jumpContent = fr.content();
        com.holzyn.actor.common.AfterCommit.runAfterCommit("L1 跳升噪声向量化",
                () -> memoryVectorService.applyNoiseAndStore(currentUserId(), fs.getProjectId(), jumpWorkingId,
                        jumpContent, sigma, jumpWorkingId * 31L + nowGameSecond));
        fuzzRepository.save(fs);
        return true;
    }

    /**
     * 琐事合并：把一批重要度≤2 的琐碎记忆合并为一条概括记忆重存 L1（用户语义：
     * "不重要的繁琐小事总结整理后作为一条单独的数据重新存入 L1，信息量大大减少"）。
     * <p>概括来源：full/max 档 AI（scene=memory_sleep_merge，≤40 字自然概括）；
     * light/programmatic/AI 失败 → 规则模板（前两条主干 + "等琐事（细节记不清）"）。
     * 合并行 fuzz 起点 mergedFuzzStart（0.5），重要度=原琐事最高值（仍 ≤2），继续走模糊生命周期；
     * 原琐事行 + fuzz_state + 向量清理（status=archived 保留历史可追溯）。</p>
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——AI 概括
     * （scene=memory_sleep_merge）在事务外执行，写阶段由各仓库 save 毫秒级短事务承担；
     * 原琐事行的向量移除（HTTP）改经 {@code AfterCommit} 提交后执行（沿 writeShortTerm 先例）。</p>
     *
     * @return true=合并完成
     */
    public boolean mergeTrivia(Long projectId, String characterRef, List<ActorMemoryFuzzState> trivia,
                               Long nowGameSecond) {
        if (trivia == null || trivia.isEmpty()) {
            return false;
        }
        String ref = characterRef.trim();
        boolean useAi = aiAllowed(projectId);
        String summary = useAi ? aiMergeTrivia(projectId, characterRef, nowGameSecond, trivia) : null;
        if (summary == null || summary.isBlank()) {
            summary = rulesMergeTrivia(trivia);
        }
        if (summary.isBlank()) {
            summary = "近日有些琐碎日常（细节记不清）";
        }
        double maxImp = 0.2;
        for (ActorMemoryFuzzState fs : trivia) {
            if (fs.getImportanceScore() != null && fs.getImportanceScore() > maxImp) {
                maxImp = fs.getImportanceScore();
            }
        }
        int imp1to5 = Math.max(1, Math.min(trivialImportance, (int) Math.round(maxImp * 5.0)));
        // 合并行 = 新 short_term（original=current=概括文本；起点模糊度 mergedFuzzStart）
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setProjectId(projectId);
        w.setCharacterRef(ref);
        w.setSessionKey("auto");
        w.setItemType(ITEM_SHORT_TERM);
        w.setContent(summary);
        w.setSource(ActorMemoryTypes.SOURCE_CONSOLIDATION);
        w.setStatus("active");
        w.setImportanceHint(imp1to5);
        w.setImportanceScore(imp1to5 / 5.0);
        w.setGameSecond(nowGameSecond);
        workingRepository.save(w);
        ActorMemoryFuzzState merged = new ActorMemoryFuzzState();
        merged.setProjectId(projectId);
        merged.setMemoryWorkingId(w.getId());
        merged.setCharacterRef(ref);
        merged.setOriginalContent(summary);
        merged.setCurrentContent(summary);
        merged.setFuzzLevel(mergedFuzzStart);
        merged.setImportanceScore(imp1to5 / 5.0);
        merged.setLastFuzzGameSecond(nowGameSecond);
        merged.setNextFuzzGameSecond(nextInterval(nowGameSecond, imp1to5 / 5.0, 1));
        merged.setFuzzCount(1);
        merged.setFuzzHistoryJson("[]");
        merged.setRetrievalCount(0);
        merged.setEmbeddingNoise(mergedFuzzStart * maxNoise);
        merged.setStatus(FUZZ_STATUS_ACTIVE);
        fuzzRepository.save(merged);
        // 抓修：合并行向量化同样挪到事务提交后（见 writeShortTerm 说明）
        final Long mergedWorkingId = w.getId();
        final String mergedSummary = summary;
        final double mergedSigma = mergedFuzzStart * maxNoise;
        com.holzyn.actor.common.AfterCommit.runAfterCommit("L1 合并行向量化",
                () -> memoryVectorService.applyNoiseAndStore(currentUserId(), projectId, mergedWorkingId,
                        mergedSummary, mergedSigma, mergedWorkingId * 31L + nowGameSecond));
        // 原琐事行清理（status=archived 保留历史 + working 行软删 + 向量移除）
        for (ActorMemoryFuzzState fs : trivia) {
            fs.setStatus(FUZZ_STATUS_ARCHIVED);
            fuzzRepository.save(fs);
            try {
                ActorMemoryWorking ow = workingRepository.findById(fs.getMemoryWorkingId()).orElse(null);
                if (ow != null) {
                    ow.setStatus("expired");
                    workingRepository.save(ow);
                }
            } catch (Exception e) {
                log.warn("[L1 模糊] 琐事行清理失败（隔离继续）: {}", e.getMessage());
            }
            // ：琐事向量移除（embedding HTTP）挪到写阶段提交后执行（沿 writeShortTerm 先例）
            try {
                final Long vectorWorkingId = fs.getMemoryWorkingId();
                com.holzyn.actor.common.AfterCommit.runAfterCommit("L1 琐事向量移除",
                        () -> memoryVectorService.removeVector(projectId, vectorWorkingId));
            } catch (Exception e) {
                log.warn("[L1 模糊] 琐事向量清理失败（隔离继续）: {}", e.getMessage());
            }
        }
        log.info("[L1 模糊] 琐事合并完成：project={} ref={} {} 条 → 概括「{}」（ai={}）",
                projectId, ref, trivia.size(), truncate(summary, 30), useAi);
        return true;
    }

    // ==================== 超清醒兜底 + 维护（角色从不睡觉时强制清洗） ====================

    /**
     * 超清醒兜底：遍历项目 → 角色，若其最新 short_term 记忆写入时间距当前已超
     * awake-max-game-seconds（24 游戏时）且距上次清洗也超冷却，则强制睡眠清洗。
     *
     * @return 强制清洗的角色数
     */
    public int enforceAwakeFallback() {
        if (!enabled || !sleepCleanupEnabled || projectRepository == null) {
            return 0;
        }
        int forced = 0;
        List<ActorProject> projects;
        try {
            projects = projectRepository.findAll();
        } catch (Exception e) {
            log.warn("[L1 模糊] 超清醒兜底遍历项目失败（跳过本轮）: {}", e.getMessage());
            return 0;
        }
        for (ActorProject p : projects) {
            Long pid = p.getId();
            if (pid == null) {
                continue;
            }
            // 世界运行时门控：未运行世界跳过超清醒兜底（世界静止=数据静止）
            if (runtimeGate != null && !runtimeGate.isActive(pid)) {
                continue;
            }
            Long nowGs = null;
            try {
                nowGs = worldClockService == null ? null : worldClockService.currentEngineGameSecond(pid);
            } catch (Exception ignored) {
                nowGs = null;
            }
            if (nowGs == null) {
                continue;
            }
            try {
                List<ActorMemoryWorking> shorts = workingRepository
                        .findByProjectIdAndItemTypeAndStatusAndDeleted(pid, ITEM_SHORT_TERM, "active", 0);
                // 按角色聚合最新写入时刻
                Map<String, Long> newestByRef = new LinkedHashMap<>();
                for (ActorMemoryWorking w : shorts) {
                    if (w.getCharacterRef() == null || w.getGameSecond() == null) {
                        continue;
                    }
                    newestByRef.merge(w.getCharacterRef(), w.getGameSecond(), Math::max);
                }
                for (Map.Entry<String, Long> e : newestByRef.entrySet()) {
                    if (nowGs - e.getValue() > awakeMaxGameSeconds) {
                        try {
                            int n = sleepCleanup(pid, e.getKey(), nowGs);
                            if (n > 0) {
                                forced++;
                                log.info("[L1 模糊] 超清醒兜底强制睡眠清洗：project={} ref={} 处理 {} 条",
                                        pid, e.getKey(), n);
                            }
                        } catch (Exception ex) {
                            log.warn("[L1 模糊] 超清醒兜底单角色失败（隔离继续）: project={} ref={} : {}",
                                    pid, e.getKey(), ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[L1 模糊] 超清醒兜底单项目失败（隔离继续）: project={} : {}", pid, e.getMessage());
            }
        }
        return forced;
    }

    /**
     * 维护循环（独立调度 30 分钟）：超清醒兜底 + 孤儿 fuzz_state 清理（working 行已删的悬空行）。
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.memory.fuzz.maintenance-interval-ms:1800000}")
    public void maintenance() {
        if (!maintenanceEnabled) {
            return;
        }
        gated("L1FuzzificationService.maintenance", this::maintainNow);
    }

    /** 维护体（经世界闸门之后才允许扫库/写库） */
    private void maintainNow() {
        try {
            enforceAwakeFallback();
        } catch (Exception e) {
            log.warn("[L1 模糊] 维护-超清醒兜底失败（隔离继续）: {}", e.getMessage());
        }
        try {
            cleanupOrphans();
        } catch (Exception e) {
            log.warn("[L1 模糊] 维护-孤儿清理失败（隔离继续）: {}", e.getMessage());
        }
    }

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：世界未挂载时维护任务<b>零 DB 写入、零 AI 调用</b>（AC12）。
     * 未装配（单测直接 {@code new}）时退化为直接执行；字段注入不改构造器（AGENTS §8.1.2）。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /** 经世界闸门执行；未装配闸门（单测）时直接执行 */
    private void gated(String name, Runnable body) {
        if (worldGate == null) {
            body.run();
        } else {
            worldGate.tryMaintenance(name, body);
        }
    }

    /** 孤儿清理：fuzz_state 关联的 working 行不存在/已删 → 清理 fuzz_state（防悬空行膨胀）。 */
    public int cleanupOrphans() {
        if (projectRepository == null) {
            return 0;
        }
        int cleaned = 0;
        for (ActorProject p : projectRepository.findAll()) {
            Long pid = p.getId();
            if (pid == null) {
                continue;
            }
            // 世界运行时门控：未运行世界跳过孤儿清理（世界静止=数据静止）
            if (runtimeGate != null && !runtimeGate.isActive(pid)) {
                continue;
            }
            List<ActorMemoryFuzzState> states;
            try {
                states = fuzzRepository.findByProjectIdOrderByIdAsc(pid);
            } catch (Exception e) {
                continue;
            }
            for (ActorMemoryFuzzState fs : states) {
                if (fs.getMemoryWorkingId() == null) {
                    continue;
                }
                boolean alive;
                try {
                    alive = workingRepository.existsById(fs.getMemoryWorkingId());
                } catch (Exception e) {
                    alive = true;
                }
                if (!alive) {
                    fuzzRepository.delete(fs);
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            log.info("[L1 模糊] 孤儿 fuzz_state 清理完成：{} 条", cleaned);
        }
        return cleaned;
    }

    // ==================== 检索辅助 ====================

    /**
     * 检索命中标记：retrieval_count +1（注入路由命中 L1 记忆时调用——反复被检索=重要，
     * 蒸馏触发依据）。
     */
    @Transactional
    public void markRetrieved(Long memoryWorkingId) {
        if (memoryWorkingId == null) {
            return;
        }
        try {
            ActorMemoryFuzzState fs = fuzzRepository.findByMemoryWorkingId(memoryWorkingId).orElse(null);
            if (fs != null && FUZZ_STATUS_ACTIVE.equals(fs.getStatus())) {
                fs.setRetrievalCount((fs.getRetrievalCount() == null ? 0 : fs.getRetrievalCount()) + 1);
                fuzzRepository.save(fs);
            }
        } catch (Exception e) {
            log.warn("[L1 模糊] 检索标记失败（隔离继续）: {}", e.getMessage());
        }
    }

    // ==================== AI 路径（scene=memory_fuzz / memory_sleep_merge） ====================

    /** 档位判定：是否允许 AI。 */
    private boolean aiAllowed(Long projectId) {
        return aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
    }

    /** 档位判定：是否 light（低频 AI）。 */
    private boolean isLight(Long projectId) {
        return aiIntensityGate != null && aiIntensityGate.isLight(projectId);
    }

    /**
     * AI 模糊化（scene=memory_fuzz，模板 §5.1 草案）：输出 {"content":...,"keptTypes":[],"dropped":[]}；
     * 失败/非法返回 null（调用方规则兜底）。
     */
    private FuzzResult aiFuzz(Long projectId, String characterRef, Long gameSecond,
                              String content, double curLevel, double targetLevel) {
        try {
            String json = aiJson(projectId, characterRef, gameSecond,
                    SCENE_MEMORY_FUZZ, PromptTemplateService.CODE_MEMORY_FUZZ, Map.of(
                            "fuzz_level", round2Str(curLevel),
                            "target_level", round2Str(targetLevel),
                            "content", content == null ? "" : content));
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String c = root.path("content").asText(null);
            if (c == null || c.isBlank()) {
                return null;
            }
            return new FuzzResult(c.trim(), truncate(json, 120));
        } catch (Exception e) {
            log.warn("[L1 模糊] AI 模糊解析失败（回退规则）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 琐事合并概括（scene=memory_sleep_merge）：输出 {"content":"≤40字","keptTypes":[]}；
     * 失败返回 null（规则兜底）。
     */
    private String aiMergeTrivia(Long projectId, String characterRef, Long gameSecond,
                                 List<ActorMemoryFuzzState> trivia) {
        StringBuilder sb = new StringBuilder();
        for (ActorMemoryFuzzState fs : trivia) {
            sb.append("- ").append(fs.getCurrentContent() == null ? "" : fs.getCurrentContent()).append("\n");
        }
        try {
            String json = aiJson(projectId, characterRef, gameSecond,
                    SCENE_MEMORY_SLEEP_MERGE,
                    PromptTemplateService.CODE_MEMORY_SLEEP_MERGE,
                    Map.of("trivia_list", sb.toString().trim()));
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String c = root.path("content").asText(null);
            return (c == null || c.isBlank()) ? null : c.trim();
        } catch (Exception e) {
            log.warn("[L1 模糊] AI 琐事合并解析失败（回退规则）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 调用统一入口（scene 透传；programmatic 档跳过；失败返回 null 零阻断）。
     * <p>批 6 收口：带角色引用+游戏时刻调用——AI 请求前置记忆链段位
     * （段1A+1C+2D+2E，{@code memoryChainContext}）；未装配/无角色 → 原样（fail-open）。</p>
     */
    private String aiJson(Long projectId, String characterRef, Long gameSecond,
                          String scene, String templateCode, Map<String, Object> placeholders) {
        try {
            Long uid = currentUserId();
            String prompt = promptTemplateService.render(uid, projectId, templateCode, placeholders);
            // 批6 收口：带角色引用+游戏时刻——前置 段1A+1C+2D+2E（memoryChainContext；fail-open）
            final String finalPrompt;
            if (aiContextInjector != null && characterRef != null && !characterRef.isBlank()) {
                String ctx = aiContextInjector.memoryChainContext(projectId, characterRef, gameSecond);
                finalPrompt = (ctx == null || ctx.isBlank()) ? prompt : ctx + "\n\n" + prompt;
            } else {
                finalPrompt = prompt;
            }
            String sys = promptTemplateService.systemMessage(uid, projectId, templateCode, null);
            if (sys == null || sys.isBlank()) {
                sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            final String sysFinal = sys;
            AiChatResult result = AiCallContext.run(scene, null, () ->
                    aiChatGateway.chatCompletion(uid, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", sysFinal),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    0.3, 256, true)));
            usageLogService.record(uid, projectId, null, result.providerId(), result.model(), scene,
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return JsonUtil.extractJson(result.content());
        } catch (Exception e) {
            log.warn("[L1 模糊] AI 调用失败（{}，回退规则降级）: {}", scene, e.getMessage());
            return null;
        }
    }

    // ==================== 规则降级（零 AI 兜底，确定性可单测） ====================

    /**
     * 规则模糊化：① 细节剥离（正则删时间词/数字/引号对话原文）② 确定性限定词（按 fuzz_level
     * 档位：0.3~0.7「可能」/&gt;0.7「好像·记不太清」）③ 长度截断 1/(1+fuzz_level)。
     */
    static FuzzResult rulesFuzz(String content, double targetLevel) {
        if (content == null || content.isBlank()) {
            return new FuzzResult(content == null ? "" : content, RULES_PROMPT_TAG);
        }
        String stripped = stripDetails(content);
        // 长度截断：1/(1+level)
        int maxLen = Math.max(8, (int) Math.round(stripped.length() / (1.0 + targetLevel)));
        String cut = stripped.length() <= maxLen ? stripped : stripped.substring(0, maxLen) + "…";
        // 确定性限定词
        if (targetLevel > 0.7) {
            cut = "好像" + cut + "（记不太清）";
        } else if (targetLevel >= 0.3) {
            cut = "可能" + cut;
        }
        return new FuzzResult(cut, RULES_PROMPT_TAG);
    }

    /**
     * 细节剥离正则：时间词（昨/今/明/上午/下午/晚上/凌晨/时分刻/数字日期）+ 数字 + 引号对话原文
     * ，保留事件主干。
     */
    static String stripDetails(String content) {
        String s = content == null ? "" : content;
        s = s.replaceAll("[「『“\"]([^」』”\"])*[」』”\"]", "");            // 引号对话原文
        s = s.replaceAll("(昨天|今日|今天|明日|明天|昨日|前天|后天|上午|下午|中午|晚上|傍晚|清晨|早晨|凌晨|夜里|深夜|白天|夜晚|午间|午后)", "");
        s = s.replaceAll("\\d+\\s*月\\s*\\d+\\s*日", "");
        s = s.replaceAll("\\d+\\s*[日号天点时刻分]", "");
        s = s.replaceAll("\\d+", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** 规则琐事合并：前两条主干 + 概括后缀。 */
    static String rulesMergeTrivia(List<ActorMemoryFuzzState> trivia) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ActorMemoryFuzzState fs : trivia) {
            if (n >= 2) {
                break;
            }
            String c = fs.getCurrentContent() == null ? "" : fs.getCurrentContent().trim();
            if (c.isBlank()) {
                continue;
            }
            String main = stripDetails(c);
            if (main.length() > 14) {
                main = main.substring(0, 14) + "…";
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(main);
            n++;
        }
        if (sb.length() == 0) {
            return "近日有些琐碎日常";
        }
        return sb + "等琐事（细节记不清）";
    }

    // ==================== 读接口（调试/主前端模糊状态视图） ====================

    /**
     * 模糊状态列表（调试前端「记忆模糊面板」/主前端记忆模糊入口数据源）。
     *
     * @param characterRef 可空=项目全部；非空=该角色
     *
     * @param status 可空=全部（active/distilled/archived）
     */
    public List<FuzzStateVO> listFuzz(Long projectId, String characterRef, String status) {
        List<ActorMemoryFuzzState> rows;
        if (characterRef != null && !characterRef.isBlank()) {
            rows = fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(
                    projectId, characterRef.trim(), status == null || status.isBlank() ? "active" : status);
        } else if (status != null && !status.isBlank()) {
            rows = fuzzRepository.findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(projectId, status);
        } else {
            rows = fuzzRepository.findByProjectIdOrderByIdAsc(projectId);
        }
        List<FuzzStateVO> out = new ArrayList<>();
        for (ActorMemoryFuzzState fs : rows) {
            out.add(FuzzStateVO.of(fs));
        }
        return out;
    }

    /**
     * 单条模糊详情（fuzz_history 展开）。
     */
    public FuzzStateVO fuzzDetail(Long projectId, Long workingId) {
        ActorMemoryFuzzState fs = fuzzRepository.findByMemoryWorkingId(workingId).orElse(null);
        if (fs == null || (projectId != null && !projectId.equals(fs.getProjectId()))) {
            return null;
        }
        return FuzzStateVO.of(fs);
    }

    /** 模糊状态 VO（原始→当前→历史全量可追溯）。 */
    public record FuzzStateVO(Long id, Long memoryWorkingId, String characterRef,
                              String originalContent, String currentContent, double fuzzLevel,
                              Long lastFuzzGameSecond, Long nextFuzzGameSecond, int fuzzCount,
                              List<Map<String, Object>> fuzzHistory, int retrievalCount,
                              double importanceScore, double embeddingNoise, String status,
                              Long distilledMemoryId) {
        static FuzzStateVO of(ActorMemoryFuzzState fs) {
            List<Map<String, Object>> history = new ArrayList<>();
            if (fs.getFuzzHistoryJson() != null && !fs.getFuzzHistoryJson().isBlank()) {
                try {
                    JsonNode node = new ObjectMapper().readTree(fs.getFuzzHistoryJson());
                    if (node.isArray()) {
                        for (JsonNode e : node) {
                            history.add(new ObjectMapper().convertValue(e, Map.class));
                        }
                    }
                } catch (Exception ignored) {
                    // 历史解析失败返回空
                }
            }
            return new FuzzStateVO(fs.getId(), fs.getMemoryWorkingId(), fs.getCharacterRef(),
                    fs.getOriginalContent(), fs.getCurrentContent(),
                    fs.getFuzzLevel() == null ? 0.0 : fs.getFuzzLevel(),
                    fs.getLastFuzzGameSecond(), fs.getNextFuzzGameSecond(),
                    fs.getFuzzCount() == null ? 0 : fs.getFuzzCount(), history,
                    fs.getRetrievalCount() == null ? 0 : fs.getRetrievalCount(),
                    fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore(),
                    fs.getEmbeddingNoise() == null ? 0.0 : fs.getEmbeddingNoise(),
                    fs.getStatus() == null ? FUZZ_STATUS_ACTIVE : fs.getStatus(),
                    fs.getDistilledMemoryId());
        }
    }

    // ==================== 历史记录 / 工具 ====================

    /** 模糊历史追加一条（幂等可追溯）。 */
    private void appendHistory(ActorMemoryFuzzState fs, Long gameSecond, double fromLevel,
                               double toLevel, boolean ai, String prompt) {
        try {
            ArrayNode arr;
            String old = fs.getFuzzHistoryJson();
            if (old == null || old.isBlank()) {
                arr = objectMapper.createArrayNode();
            } else {
                JsonNode node = objectMapper.readTree(old);
                arr = node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
            }
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("gameSecond", gameSecond == null ? 0L : gameSecond);
            entry.put("fromLevel", round2(fromLevel));
            entry.put("toLevel", round2(toLevel));
            entry.put("ai", ai);
            entry.put("prompt", prompt == null ? "" : truncate(prompt, 60));
            arr.add(entry);
            // 历史有界（保留最近 50 条）
            while (arr.size() > 50) {
                arr.remove(0);
            }
            fs.setFuzzHistoryJson(objectMapper.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("[L1 模糊] 历史记录失败（隔离继续）: {}", e.getMessage());
        }
    }

    /** 钳制 1~5。 */
    private static int clampImportance(Integer importance) {
        if (importance == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, importance));
    }

    /** 钳制 0~1。 */
    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 两位小数（数值展示/历史）。 */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String round2Str(double v) {
        return String.valueOf(round2(v));
    }

    /** 文本截断（中文按字符）。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** 规则/AI 模糊结果（内容 + 来源 prompt 片段）。 */
    public record FuzzResult(String content, String prompt) {
    }
}
