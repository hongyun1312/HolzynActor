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
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.AfterCommit;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryLineage;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryLineageRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨层巩固蒸馏服务。
 * <p>职责：L1→L2→L3 的<b>语义蒸馏与谱系追踪</b>——每次巩固伴随信息量缩减
 * （L1"昨天在集市遇到了谁，聊了天气"→L2"与某NPC在集市有日常社交接触"→L3"与某NPC关系维持中"），
 * 谱系 {@link ActorMemoryLineage} 可追溯（调试「记忆谱系」视图数据源）。</p>
 * <ul>
 * <li><b>L1→L2 触发</b>（任一满足）：fuzz_level≥0.6（模糊到"记不清但记得大概"的固化时点）/
 * retrieval_count≥3（反复被检索=重要）/ importance_score≥0.7（高重要快车道）；
 * 蒸馏产物写 actor_memory（type=semantic，source=consolidation，
 * evidence_ids=原 L1 id）；fuzz_state status=distilled + distilled_memory_id 回指；</li>
 * <li><b>L2→L3 触发</b>（更严格）：7 游戏日内被检索 ≥5 次（access_count + last_access_at
 * 游戏时间差）/ 与活跃目标强相关（进行中计划文本与记忆内容 Dice ≥ 阈值）；
 * 巩固产物=L3 扩展语义（actor_memory_core block_key=consolidated:{memoryId}，零新表，
 * 人格核心 kind=core 不动；仅 HIGH 档注入）；</li>
 * <li><b>滞回防振荡</b>（FadeMem 参考）：L2 记忆巩固进 L3 后保留在 L2（不删除），
 * 层间不做反复横跳；L1 蒸馏后 status=distilled 不再参与模糊扫描；</li>
 * <li><b>AI 档位联动</b>：max/full=AI 蒸馏（scene=memory_distill）；light/programmatic=
 * 规则蒸馏（截断 60% + 主语保留 + "（细节记不清）"）；AI 结果缩减率 &lt;
 * min-reduction-ratio（0.2）时拒绝回退规则（防信息量丢失）；</li>
 * <li><b>谱系</b>：每跳落 actor_memory_lineage（源/目标层级与 id、游戏秒、缩减率 token 比、
 * prompt 版本）。</li>
 * </ul>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§4 +
 * （L3 扩展=actor_memory_core 零新表）。</p>
 */
@Slf4j
@Service
public class MemoryDistillService {

    /** AI 场景：跨层蒸馏 */
    public static final String SCENE_MEMORY_DISTILL = "memory_distill";

    /** 谱系层级常量 */
    public static final String TIER_L1 = "L1";
    public static final String TIER_L2 = "L2";
    public static final String TIER_L3 = "L3";

    /** 规则降级 prompt 标记 */
    private static final String RULES_TAG = "rules";

    // ==================== 配置（application.yml holzyn.actor.memory.distill.*） ====================

    /** 蒸馏总开关（false=不蒸馏不谱系，睡眠清洗仅模糊/合并） */
    @Value("${holzyn.actor.memory.distill.enabled:true}")
    private boolean enabled;

    /** L1→L2 触发：fuzz_level 阈值（默认 0.6） */
    @Value("${holzyn.actor.memory.distill.fuzz-threshold:0.6}")
    private double fuzzThreshold;

    /** L1→L2 触发：检索次数阈值（默认 3） */
    @Value("${holzyn.actor.memory.distill.retrieval-threshold:3}")
    private int retrievalThreshold;

    /** L1→L2 触发：重要度阈值（0~1，默认 0.7 快车道） */
    @Value("${holzyn.actor.memory.distill.importance-threshold:0.7}")
    private double importanceThreshold;

    /** AI 蒸馏结果最低缩减率（<0.2 拒绝回退规则——防信息量丢失） */
    @Value("${holzyn.actor.memory.distill.min-reduction-ratio:0.2}")
    private double minReductionRatio;

    /** L2→L3 触发：7 游戏日内检索次数阈值（默认 5） */
    @Value("${holzyn.actor.memory.distill.l3-retrieval-7d:5}")
    private int l3Retrieval7d;

    /** L2→L3 触发：活跃目标强相关 Dice 阈值（默认 0.5） */
    @Value("${holzyn.actor.memory.distill.l3-plan-relevance-threshold:0.5}")
    private double l3PlanRelevanceThreshold;

    /** L3 巩固块内容上限（字，默认 400 对齐核心块约束） */
    @Value("${holzyn.actor.memory.distill.l3-max-chars:400}")
    private int l3MaxChars;

    /** prompt 版本（lineage 记录） */
    @Value("${holzyn.actor.memory.distill.prompt-version:v1}")
    private String promptVersion;

    // ==================== 依赖 ====================

    private final ActorMemoryFuzzStateRepository fuzzRepository;
    private final ActorMemoryWorkingRepository workingRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorCoreMemoryRepository coreRepository;
    private final ActorMemoryLineageRepository lineageRepository;
    private final ActorActionPlanRepository planRepository;
    private final WorldClockService worldClockService;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /** AI 强度闸门（四档联动） */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 当前用户（AI 凭据归属） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 记忆向量服务（蒸馏后 L1 向量清理） */
    @Autowired(required = false)
    private MemoryVectorService memoryVectorService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（memory_distill 属批4 记忆链·角色视角）：AI 蒸馏请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    public MemoryDistillService(ActorMemoryFuzzStateRepository fuzzRepository,
                                ActorMemoryWorkingRepository workingRepository,
                                ActorMemoryRepository memoryRepository,
                                ActorCoreMemoryRepository coreRepository,
                                ActorMemoryLineageRepository lineageRepository,
                                ActorActionPlanRepository planRepository,
                                WorldClockService worldClockService,
                                AiSpringChatGateway aiChatGateway,
                                PromptTemplateService promptTemplateService,
                                UsageLogService usageLogService,
                                ObjectMapper objectMapper) {
        this.fuzzRepository = fuzzRepository;
        this.workingRepository = workingRepository;
        this.memoryRepository = memoryRepository;
        this.coreRepository = coreRepository;
        this.lineageRepository = lineageRepository;
        this.planRepository = planRepository;
        this.worldClockService = worldClockService;
        this.aiChatGateway = aiChatGateway;
        this.promptTemplateService = promptTemplateService;
        this.usageLogService = usageLogService;
        this.objectMapper = objectMapper;
    }

    // ==================== L1→L2 蒸馏 ====================

    /**
     * L1→L2 每日扫描（MemoryConsolidationService 批量巩固时点调用）：对满足触发条件
     * （fuzz≥阈值 / 检索≥阈值 / 重要度≥阈值）的 active fuzz_state 逐条蒸馏（失败隔离）。
     * <p><b>记忆写并发治理</b>：本方法与 {@link #distillL1toL2} <b>不再带
     * {@code @Transactional}</b>——旧实现把整轮扫描包进一个事务，条目 1 的写锁会在条目 2 的
     * AI 蒸馏（约 80s）期间继续持有，连锁撞 {@code LOCK_TIMEOUT=10000}。现在 AI 全部在事务外，
     * 每条目的写阶段由各仓库 save 的毫秒级短事务承担（配 {@link #distillL1toL2} 的 evidence 幂等
     * 守卫：中途失败重跑不会产生重复 L2 行）。</p>
     *
     * @return 蒸馏条数
     */
    public int scanL1toL2(Long projectId, Long nowGameSecond) {
        if (!enabled || projectId == null) {
            return 0;
        }
        List<ActorMemoryFuzzState> states = fuzzRepository
                .findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(projectId,
                        L1FuzzificationService.FUZZ_STATUS_ACTIVE);
        int done = 0;
        for (ActorMemoryFuzzState fs : states) {
            double fuzz = fs.getFuzzLevel() == null ? 0.0 : fs.getFuzzLevel();
            int retrieval = fs.getRetrievalCount() == null ? 0 : fs.getRetrievalCount();
            double imp = fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore();
            if (fuzz < fuzzThreshold && retrieval < retrievalThreshold && imp < importanceThreshold) {
                continue;
            }
            try {
                if (distillL1toL2(projectId, fs, nowGameSecond)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("[记忆蒸馏] L1→L2 蒸馏失败（隔离继续）: project={} state={} : {}",
                        projectId, fs.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[记忆蒸馏] L1→L2 扫描完成：project={} 蒸馏 {} 条", projectId, done);
        }
        return done;
    }

    /**
     * 单条 L1→L2 蒸馏：AI（scene=memory_distill，target=L2）或规则截断 → 写 L2 semantic
     * （source=consolidation，evidence_ids=L1 id）→ 谱系行 → fuzz_state/working 状态更新
     * → L1 向量清理。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}（AI 蒸馏在事务外，
     * 写阶段由各仓库 save 短事务承担）。<b>evidence 幂等守卫</b>：已存在 evidence_ids=本 L1 id
     * 的 L2 行则跳过新建、只把 fuzz_state 标记 distilled（旧单事务拆开后「L2 已写、状态未标」的
     * 半截重跑不会产生重复行）。向量清理（HTTP 调用）经 {@link AfterCommit} 延迟到写阶段提交后。</p>
     *
     * @return true=蒸馏成功
     */
    public boolean distillL1toL2(Long projectId, ActorMemoryFuzzState fs, Long nowGameSecond) {
        if (!enabled || fs == null || projectId == null) {
            return false;
        }
        if (!L1FuzzificationService.FUZZ_STATUS_ACTIVE.equals(fs.getStatus())) {
            return false;
        }
        String sourceContent = fs.getCurrentContent() == null ? "" : fs.getCurrentContent();
        if (sourceContent.isBlank()) {
            sourceContent = fs.getOriginalContent() == null ? "" : fs.getOriginalContent();
        }
        if (sourceContent.isBlank()) {
            return false;
        }
        // ============ AI（事务外；重试/失败回退规则均在锁外） ============
        // 蒸馏语义（AI 档位联动；缩减率校验防信息丢失）
        String distilled = aiAllowed(projectId)
                ? aiDistill(projectId, fs.getCharacterRef(), nowGameSecond, sourceContent, TIER_L2) : null;
        if (distilled == null || distilled.isBlank()) {
            distilled = rulesDistill(sourceContent, TIER_L2);
        }
        double imp = fs.getImportanceScore() == null ? 0.2 : fs.getImportanceScore();
        int imp1to5 = Math.max(1, Math.min(5, (int) Math.round(imp * 5.0)));
        // ============ 写阶段 ============
        // 幂等守卫：evidence_ids=本 L1 id 的 L2 行已存在（上次写成功但状态未标/半截重跑）→
        // 不重复新建，直接复用其 id 标记 fuzz_state（避免「拆事务后半截失败重跑产生重复行」）
        Long existingL2 = findDistilledL2(projectId, fs.getMemoryWorkingId());
        Long l2Id;
        if (existingL2 != null) {
            l2Id = existingL2;
            log.info("[记忆蒸馏] L1→L2 幂等命中（evidence_ids 已存在），复用 L2={} 仅标记状态: project={} state={}",
                    l2Id, projectId, fs.getId());
        } else {
            // 写 L2（type=semantic 语义记忆，source=consolidation；evidence_ids=原 L1 id 谱系锚点）
            ActorMemory m = new ActorMemory();
            m.setProjectId(projectId);
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            m.setCharacterId(NpcRefs.idOf(fs.getCharacterRef()));
            m.setCharacterRef(fs.getCharacterRef());
            m.setType(ActorMemoryTypes.TYPE_SEMANTIC);
            m.setContent(distilled);
            m.setImportance(imp1to5);
            m.setSource(ActorMemoryTypes.SOURCE_CONSOLIDATION);
            m.setEvidenceIds(String.valueOf(fs.getMemoryWorkingId()));
            l2Id = memoryRepository.save(m).getId();
            // 谱系（缩减率=目标/源 token 比）
            int srcTokens = TokenEstimator.estimateTokens(sourceContent);
            int dstTokens = TokenEstimator.estimateTokens(distilled);
            recordLineage(projectId, fs.getMemoryWorkingId(), TIER_L1, l2Id, TIER_L2,
                    "L1->L2", nowGameSecond, srcTokens, dstTokens);
        }
        // fuzz_state / working 状态更新（复用 l2Id；各自短事务）
        fs.setStatus(L1FuzzificationService.FUZZ_STATUS_DISTILLED);
        fs.setDistilledMemoryId(l2Id);
        fuzzRepository.save(fs);
        try {
            ActorMemoryWorking w = workingRepository.findById(fs.getMemoryWorkingId()).orElse(null);
            if (w != null) {
                w.setStatus("consolidated");
                w.setConsolidatedAt(java.time.LocalDateTime.now());
                workingRepository.save(w);
            }
        } catch (Exception e) {
            log.warn("[记忆蒸馏] L1 working 状态更新失败（隔离继续）: {}", e.getMessage());
        }
        // ：L1 向量清理（embedding HTTP）挪到写阶段提交后执行（沿 writeShortTerm 先例）
        if (memoryVectorService != null) {
            final Long vectorWorkingId = fs.getMemoryWorkingId();
            AfterCommit.runAfterCommit("L1 蒸馏向量清理", () -> {
                try {
                    memoryVectorService.removeVector(projectId, vectorWorkingId);
                } catch (Exception e) {
                    log.warn("[记忆蒸馏] L1 向量清理失败（隔离继续）: {}", e.getMessage());
                }
            });
        }
        log.info("[记忆蒸馏] L1→L2 完成：project={} state={} L2={} 幂等={}",
                projectId, fs.getId(), l2Id, existingL2 != null);
        return true;
    }

    /**
     * L1 蒸馏幂等查询：该 L1 working id 是否已产出过 L2 行（evidence_ids 锚点精确匹配）。
     *
     * @return 已存在的 L2 行 id；无则 null
     */
    private Long findDistilledL2(Long projectId, Long memoryWorkingId) {
        if (memoryWorkingId == null) {
            return null;
        }
        try {
            List<ActorMemory> hits = memoryRepository
                    .findConsolidatedL2ByEvidenceId(projectId, String.valueOf(memoryWorkingId));
            return hits.isEmpty() ? null : hits.get(0).getId();
        } catch (Exception e) {
            log.warn("[记忆蒸馏] 幂等查询失败（按无命中处理）: {}", e.getMessage());
            return null;
        }
    }

    // ==================== L2→L3 巩固 ====================

    /**
     * L2→L3 每日扫描：L2 记忆满足「7 游戏日内检索 ≥ 阈值」或「与活跃目标强相关」
     * （进行中计划文本与内容 Dice ≥ 阈值）→ 巩固为 L3 扩展语义（actor_memory_core
     * block_key=consolidated:{id}，零新表；人格核心不动）。
     * <p><b>记忆写并发治理</b>：与 {@link #consolidateL2toL3} 同拆——不再带
     * {@code @Transactional}，AI 蒸馏在事务外，写阶段由各仓库 save 毫秒级短事务承担
     * （已天然幂等：block_key=consolidated:{id} 去重）。</p>
     *
     * @return 巩固条数
     */
    public int scanL2toL3(Long projectId, Long nowGameSecond) {
        if (!enabled || projectId == null || nowGameSecond == null) {
            return 0;
        }
        // 角色级 L2 语义/情节记忆全量（巩固候选；project 级世界大事记不参与角色 L3 巩固）
        List<ActorMemory> all = memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0);
        int done = 0;
        for (ActorMemory m : all) {
            if (m.getCharacterRef() == null || m.getCharacterRef().isBlank()
                    || m.getCharacterId() == null) {
                continue; // 普通 NPC/项目级不巩固（L3 核心属特殊 NPC）
            }
            if (!ActorMemoryTypes.TYPE_SEMANTIC.equals(m.getType())
                    && !ActorMemoryTypes.TYPE_EPISODIC.equals(m.getType())) {
                continue;
            }
            try {
                if (consolidateL2toL3(projectId, m, nowGameSecond)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("[记忆蒸馏] L2→L3 巩固失败（隔离继续）: project={} memory={} : {}",
                        projectId, m.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[记忆蒸馏] L2→L3 扫描完成：project={} 巩固 {} 条", projectId, done);
        }
        return done;
    }

    /**
     * 单条 L2→L3 巩固判定与执行：7 游戏日检索 ≥ 阈值 或 活跃计划强相关 →
     * AI/规则蒸馏为 L3 抽象（关系/倾向/信念级）→ 写 actor_memory_core
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——AI 蒸馏在
     * 事务外，写阶段由 core/lineage 仓库 save 毫秒级短事务承担；block_key 去重保证重跑幂等。</p>
     */
    public boolean consolidateL2toL3(Long projectId, ActorMemory m, Long nowGameSecond) {
        if (!enabled || m == null || m.getId() == null || m.getCharacterRef() == null
                || m.getCharacterId() == null) {
            return false;
        }
        // 触发判定①：7 游戏日内检索 ≥ 阈值（last_access_at 游戏时间差）
        boolean freqHit = false;
        if (m.getAccessCount() != null && m.getAccessCount() >= l3Retrieval7d && m.getLastAccessAt() != null) {
            Long lastAccessHour = null;
            try {
                lastAccessHour = worldClockService == null ? null
                        : worldClockService.gameHourAt(projectId, m.getLastAccessAt());
            } catch (Exception ignored) {
                lastAccessHour = null;
            }
            if (lastAccessHour != null) {
                long nowDay = Math.floorDiv(nowGameSecond, 86400L);
                long accessDay = Math.floorDiv(lastAccessHour * 3600L, 86400L);
                freqHit = nowDay - accessDay <= 7;
            }
        }
        // 触发判定②：活跃目标强相关（进行中计划文本 Dice ≥ 阈值）
        boolean planHit = planRelevant(projectId, m);
        if (!freqHit && !planHit) {
            return false;
        }
        // 已巩固去重（block_key=consolidated:{id} 存在即跳过）
        String blockKey = "consolidated:" + m.getId();
        List<ActorCoreMemory> existing = coreRepository
                .findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(projectId, m.getCharacterRef(), blockKey, 0);
        if (!existing.isEmpty()) {
            return false;
        }
        // L3 蒸馏（比 L2 更抽象：关系/倾向/信念级）
        String source = m.getContent() == null ? "" : m.getContent();
        String distilled = aiAllowed(projectId)
                ? aiDistill(projectId, m.getCharacterRef(), nowGameSecond, source, TIER_L3) : null;
        if (distilled == null || distilled.isBlank()) {
            distilled = rulesDistill(source, TIER_L3);
        }
        if (distilled.length() > l3MaxChars) {
            distilled = distilled.substring(0, l3MaxChars);
        }
        // 写 L3 扩展语义块（零新表；人格核心 kind=core 不动；巩固块只读不可 agent 自编辑）
        ActorCoreMemory core = new ActorCoreMemory();
        core.setProjectId(projectId);
        core.setCharacterRef(m.getCharacterRef());
        core.setBlockKey(blockKey);
        core.setBlockName("巩固记忆");
        core.setContent(distilled);
        core.setImportance(Math.max(1, Math.min(5, m.getImportance() == null ? 3 : m.getImportance())));
        core.setEditableByAgent(0);
        coreRepository.save(core);
        // 谱系（源=L2 memory id，目标=L3 core id）
        int srcTokens = TokenEstimator.estimateTokens(source);
        int dstTokens = TokenEstimator.estimateTokens(distilled);
        recordLineage(projectId, m.getId(), TIER_L2, core.getId(), TIER_L3,
                "L2->L3", nowGameSecond, srcTokens, dstTokens);
        log.info("[记忆蒸馏] L2→L3 巩固完成：project={} memory={} core={} 触发={}",
                projectId, m.getId(), core.getId(), freqHit ? "7日5检" : "计划强相关");
        return true;
    }

    /** 活跃目标强相关：该角色进行中计划（planned/executing）文本与记忆内容 Dice ≥ 阈值。 */
    private boolean planRelevant(Long projectId, ActorMemory m) {
        try {
            List<ActorActionPlan> plans = planRepository
                    .findByCharacterIdAndStatusOrderByIdDesc(m.getCharacterId(), "planned");
            List<ActorActionPlan> executing = planRepository
                    .findByCharacterIdAndStatusOrderByIdDesc(m.getCharacterId(), "executing");
            List<ActorActionPlan> all = new ArrayList<>(plans);
            all.addAll(executing);
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.isBlank()) {
                return false;
            }
            for (ActorActionPlan p : all) {
                String json = p.getActionJson() == null ? "" : p.getActionJson();
                if (MemoryDedup.overlapScore(content, json) >= l3PlanRelevanceThreshold) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[记忆蒸馏] 计划相关性判定失败（按不相关处理）: {}", e.getMessage());
            return false;
        }
    }

    // ==================== AI 蒸馏 / 规则蒸馏 ====================

    /**
     * AI 蒸馏（scene=memory_distill）：输出 {"content":...,"importanceScore":0~1,...}；
     * 缩减率 < minReductionRatio 时拒绝（防信息量丢失，回退规则）；失败返回 null。
     */
    private String aiDistill(Long projectId, String characterRef, Long gameSecond,
                             String sourceContent, String targetTier) {
        try {
            String json = aiJson(projectId, characterRef, gameSecond,
                    SCENE_MEMORY_DISTILL, PromptTemplateService.CODE_MEMORY_DISTILL,
                    Map.of("source_tier", TIER_L1.equals(targetTier) ? TIER_L1 : TIER_L2,
                            "target_tier", targetTier,
                            "source_content", sourceContent));
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String c = root.path("content").asText(null);
            if (c == null || c.isBlank()) {
                return null;
            }
            String content = c.trim();
            // 缩减率校验（源 token 比目标多不了多少 = 没蒸馏 → 拒绝）
            int src = TokenEstimator.estimateTokens(sourceContent);
            int dst = TokenEstimator.estimateTokens(content);
            double ratio = src <= 0 ? 1.0 : dst * 1.0 / src;
            if (ratio > 1.0 - minReductionRatio && src > 8) {
                log.debug("[记忆蒸馏] AI 结果缩减不足（ratio={}），回退规则", round2(ratio));
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warn("[记忆蒸馏] AI 蒸馏解析失败（回退规则）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 规则蒸馏（零 AI）：原文截断（保留前 60%）+ 主语/参与者保留 + 源模糊则追加
     * "（细节记不清）"；L3 再抽象一层（追加"长期来看"前缀语义——关系/倾向级）。
     */
    static String rulesDistill(String sourceContent, String targetTier) {
        if (sourceContent == null || sourceContent.isBlank()) {
            return "";
        }
        String s = sourceContent.trim();
        int keep = Math.max(12, (int) Math.round(s.length() * 0.6));
        String cut = s.length() <= keep ? s : s.substring(0, keep) + "…";
        String out = TIER_L3.equals(targetTier) ? "长期来看，" + cut : cut;
        // 源已带模糊限定词/或为模糊版本时，保留"记不清"语义
        if (s.contains("记不太清") || s.contains("好像") || s.contains("可能")) {
            out = out + "（细节记不清）";
        }
        return out;
    }

    /**
     * AI 调用统一入口（scene 透传；programmatic 档跳过；失败返回 null）。
     * <p>批 6 收口：带角色引用+游戏时刻调用——AI 请求前置记忆链段位
     * （段1A+1C+2D+2E，{@code memoryChainContext}）；未装配/无角色 → 原样（fail-open）。</p>
     */
    private String aiJson(Long projectId, String characterRef, Long gameSecond,
                          String scene, String templateCode, Map<String, Object> placeholders) {
        try {
            Long uid = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
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
            log.warn("[记忆蒸馏] AI 调用失败（{}，回退规则蒸馏）: {}", scene, e.getMessage());
            return null;
        }
    }

    /** 档位判定：是否允许 AI。 */
    private boolean aiAllowed(Long projectId) {
        return aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
    }

    // ==================== 谱系 ====================

    /** 谱系行落库（缩减率=目标/源 token 比）。 */
    private void recordLineage(Long projectId, Long sourceId, String sourceTier,
                               Long targetId, String targetTier, String consolidateType,
                               Long gameSecond, int sourceTokens, int targetTokens) {
        try {
            ActorMemoryLineage ln = new ActorMemoryLineage();
            ln.setProjectId(projectId);
            ln.setSourceMemoryId(sourceId);
            ln.setSourceTier(sourceTier);
            ln.setTargetMemoryId(targetId);
            ln.setTargetTier(targetTier);
            ln.setConsolidateType(consolidateType);
            ln.setGameSecond(gameSecond == null ? 0L : gameSecond);
            ln.setReductionRatio(sourceTokens <= 0 ? 1.0 : targetTokens * 1.0 / sourceTokens);
            ln.setSourceTokens(sourceTokens);
            ln.setTargetTokens(targetTokens);
            ln.setPromptVersion(promptVersion);
            lineageRepository.save(ln);
        } catch (Exception e) {
            log.warn("[记忆蒸馏] 谱系记录失败（隔离继续）: {}", e.getMessage());
        }
    }

    /**
     * 记忆谱系（反向链）：以任一 L2/L3 记忆为根，逐层向上展开来源（目标→直接来源→再上层）。
     *
     * @param projectId 项目 ID
     *
     * @param targetMemoryId 根记忆 id（L2 actor_memory.id 或 L3 actor_memory_core.id）
     *
     * @return 谱系 VO 链（自根向源展开；每跳含来源层级/巩固类型/游戏秒/缩减率/内容快照）
     */
    public List<LineageVO> lineageOf(Long projectId, Long targetMemoryId) {
        List<LineageVO> out = new ArrayList<>();
        Long cursor = targetMemoryId;
        String cursorTier = TIER_L2;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            List<ActorMemoryLineage> hops = lineageRepository
                    .findByProjectIdAndTargetMemoryIdOrderByIdAsc(projectId, cursor);
            if (hops.isEmpty()) {
                break;
            }
            for (ActorMemoryLineage hop : hops) {
                String sourceContent = resolveContent(projectId, hop);
                out.add(new LineageVO(hop.getSourceMemoryId(), hop.getSourceTier(),
                        hop.getTargetMemoryId(), hop.getTargetTier(), hop.getConsolidateType(),
                        hop.getGameSecond(), hop.getReductionRatio(),
                        hop.getSourceTokens(), hop.getTargetTokens(), sourceContent));
                // 向上溯源（源若是 L1/L2 继续展开）
                if (TIER_L1.equals(hop.getSourceTier()) || TIER_L2.equals(hop.getSourceTier())) {
                    cursor = hop.getSourceMemoryId();
                    cursorTier = hop.getSourceTier();
                    break;
                }
            }
            // 全部目标相同 → 避免死循环
            if (hops.size() == 1 && TIER_L3.equals(cursorTier)) {
                break;
            }
        }
        return out;
    }

    /** 谱系源内容快照（L1 working / L2 memory / L3 core 原文）。 */
    private String resolveContent(Long projectId, ActorMemoryLineage hop) {
        try {
            if (TIER_L1.equals(hop.getSourceTier())) {
                ActorMemoryWorking w = workingRepository.findById(hop.getSourceMemoryId()).orElse(null);
                return w == null ? null : truncate(w.getContent(), 200);
            }
            if (TIER_L2.equals(hop.getSourceTier())) {
                ActorMemory m = memoryRepository.findById(hop.getSourceMemoryId()).orElse(null);
                return m == null ? null : truncate(m.getContent(), 200);
            }
            ActorCoreMemory c = coreRepository.findById(hop.getSourceMemoryId()).orElse(null);
            return c == null ? null : truncate(c.getContent(), 200);
        } catch (Exception e) {
            return null;
        }
    }

    /** 谱系 VO。 */
    public record LineageVO(Long sourceMemoryId, String sourceTier, Long targetMemoryId, String targetTier,
                            String consolidateType, Long gameSecond, Double reductionRatio,
                            Integer sourceTokens, Integer targetTokens, String sourceContent) {
    }

    // ==================== 工具 ====================

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
