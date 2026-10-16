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

package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.memory.service.MemoryExtractParse;
import com.holzyn.actor.domain.memory.service.MemoryExtractReviewParse;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.memory.service.MemoryArchiveService;
import com.holzyn.actor.domain.memory.service.L1FuzzificationService;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import com.holzyn.actor.domain.memory.vo.MemoryWorkingVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆组件（B4 记忆沉淀 + 阶段 C MemoryService 收编，domain/agent/memory）。
 * <p>定位：把「对话日志式记忆」升级为
 * <b>记忆流</b>——多类记忆（observation/fact/summary，reflection/plan 留 D 阶段）、检索评分、
 * 非对话经历沉淀、项目级大事记写入方、<b>对话抽取/注入/管理（收编 MemoryService）</b>。</p>
 * <p><b>收编内容</b>（原 {@code MemoryService} 的对话侧能力，行为不变）：</p>
 * <ul>
 * <li><b>抽取</b>：{@link #extractAfterRound}——对话后 AI 全自动抽取关键事实与会话摘要
 * （服务端文本重叠二次去重 + 寒暄门控 + 摘要间隔 + 超预算滚动淘汰）；</li>
 * <li><b>注入</b>：{@link #memoryContext}/{@link #memoryContextByRef}——角色级 fact+observation
 * top-K + 项目级大事记【世界大事记】top-K；{@link #conversationSummaryContext} 超窗压缩注入；</li>
 * <li><b>管理</b>：{@link #list}/{@link #delete}（归属校验 + 分页 + character_ref 双轨）。</li>
 * </ul>
 * <p>依赖注入约定：对话侧依赖（ConversationService/仓库/AI 路由等）用<b>字段注入</b>（非 final），
 * 保持构造器仅 {@code memoryRepository}——B4 存量单测（AgentMemoryServiceTest 单参构造）零改动；
 * 纯静态工具（rankForInjection/evictToBudget/isDuplicate/门控判定）<b>起迁入本组件</b>
 * （旧 MemoryService 已物理删除）。</p>
 * <p>character_ref 双轨：特殊型 → 双写
 * character_id + character_ref；普通型 → 仅 character_ref。类型不再由前缀编码，改查
 */
@Slf4j
@Service
public class AgentMemoryService {

    /** 每角色/项目记忆条数预算（继承既有记忆预算，超限按「重要度×新旧」滚动淘汰） */
    @Value("${holzyn.actor.memory.budget:50}")
    private int budget;

    /** 去重阈值：新记忆与已有记忆文本重叠度（Dice 二元组 0~1）高于该值则判定重复丢弃 */
    @Value("${holzyn.actor.memory.dedup-threshold:0.6}")
    private double dedupThreshold;

    /** 检索默认 top-K（未显式传参时使用） */
    @Value("${holzyn.actor.memory.retrieval-top-k:8}")
    private int retrievalTopK;

    /** recency 指数衰减半程（游戏/真实小时，默认 24：24 小时前的记忆衰减到 e^-1≈0.37） */
    @Value("${holzyn.actor.memory.recency-decay-hours:24}")
    private int recencyDecayHours;

    /** R3 staleness：高重要事实标记「可能过时」的重要度阈值（默认 3，重要度≥该值才参与 staleness 判定） */
    @Value("${holzyn.actor.memory.stale-importance-threshold:3}")
    private int staleImportanceThreshold;

    /** R3 staleness：超期判定（游戏日，默认 3：距上次复核超 3 游戏日的高重要事实标记「可能过时」） */
    @Value("${holzyn.actor.memory.stale-after-game-days:3}")
    private int staleAfterGameDays;

    /** 注入去重阈值：记忆与「历史窗口文本」重叠度（Dice 二元组 0~1）≥该值则从注入清单剔除（复用 dedupThreshold） */

    // ==================== 收编 MemoryService 对话侧配置与依赖（字段注入，不改构造器） ====================

    /** 长期记忆总开关（收编，false 时抽取/注入全部跳过，行为与旧版一致） */
    @Value("${holzyn.actor.memory.enabled:true}")
    private boolean memoryEnabled;

    /** 每轮注入的角色级事实记忆条数（重要度 desc + 较新优先） */
    @Value("${holzyn.actor.memory.top-k:8}")
    private int topK;

    /** 每轮注入的项目级记忆条数（世界大事记） */
    @Value("${holzyn.actor.memory.project-top-k:4}")
    private int projectTopK;

    /** 会话摘要生成间隔（每 N 轮生成一条 summary 记忆） */
    @Value("${holzyn.actor.memory.summary-interval:10}")
    private int summaryInterval;

    /** 记忆门控总开关（true=寒暄/无实质信息的轮次跳过提取，连 AI 都不调用） */
    @Value("${holzyn.actor.memory.gate.enabled:true}")
    private boolean gateEnabled;

    /** 单聊寒暄判定最大字符数 */
    @Value("${holzyn.actor.memory.gate.greeting-max-len:20}")
    private int greetingMaxLen;

    /** 群聊寒暄判定最大字符数 */
    @Value("${holzyn.actor.memory.gate.group-greeting-max-len:8}")
    private int groupGreetingMaxLen;

    /** AI 复核开关（默认 false） */
    @Value("${holzyn.actor.memory.gate.ai-review:false}")
    private boolean gateAiReview;

    /** 记忆系统重构：三重机制写入门槛——重要度≤drop-below 直接丢弃（默认 1） */
    @Value("${holzyn.actor.memory.extract-gate.drop-below:1}")
    private int extractDropBelow;

    /** 记忆系统重构：重要度<draft-below 进 L1 草稿待巩固复核（默认 3：2 进草稿） */
    @Value("${holzyn.actor.memory.extract-gate.draft-below:3}")
    private int extractDraftBelow;

    /** 记忆系统重构：按需 AI 复核阈值（候选重要度≥该值或带 update/invalidate 操作才复核，默认 3） */
    @Value("${holzyn.actor.memory.extract-gate.review-threshold:3}")
    private int extractReviewThreshold;

    /** 记忆系统重构：按需 AI 复核总开关（默认 true；false=候选直接按初判执行零复核调用） */
    @Value("${holzyn.actor.memory.extract-gate.review-enabled:true}")
    private boolean extractReviewEnabled;

    /** 记忆系统重构：update/invalidate 旧条匹配阈值（Dice 二元组，默认 0.4；无匹配退化为 add/丢弃） */
    @Value("${holzyn.actor.memory.supersede-match-threshold:0.4}")
    private double supersedeMatchThreshold;

    /** 会话结束懒检查阈值（真实分钟，默认 30） */
    @Value("${holzyn.actor.memory.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /** 抽取/摘要时纳入的最近消息条数（控制输入 token 成本） */
    private static final int RECENT_DIALOG_LIMIT = 20;

    /** 会话摘要的固定重要度 */
    private static final int SUMMARY_IMPORTANCE = 4;

    /** AI 抽取温度 */
    private static final double EXTRACT_TEMPERATURE = 0.3;

    /** AI 抽取最大输出 token */
    private static final int EXTRACT_MAX_TOKENS = 1024;

    /** 虚拟线程执行器：异步抽取不阻塞对话主流程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 记忆仓库（构造器唯一依赖，B4 存量单测兼容） */
    private final ActorMemoryRepository memoryRepository;

    // 对话侧依赖（收编 MemoryService，字段注入保持构造器不变）
    @Autowired
    private ActorMessageRepository messageRepository;
    @Autowired
    private ActorConversationMemberRepository memberRepository;
    @Autowired
    private ActorCharacterRepository characterRepository;
    @Autowired
    private ActorCharacterRepository npcRepository;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码类型——「是否普通型」决定记忆写/读走
     * character_ref 还是 character_id，必须查 actor_profile.type；字段注入保持构造器单参不变，
     * 纯单测直构时为 null → 按特殊型处理[与 NpcTypeResolver 解析失败回退 special 同口径]）
     */
    @Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;
    @Autowired
    private ActorProjectRepository projectRepository;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private PromptTemplateService promptTemplateService;
    @Autowired
    private AiSpringChatGateway aiChatGateway;
    @Autowired
    private UsageLogService usageLogService;
    @Autowired
    private CurrentUserProvider currentUserProvider;

    /** 世界时钟（R3 staleness 游戏时间换算，字段注入保持构造器单参不变） */
    @Autowired
    private WorldClockService worldClockService;

    /**
     * L1 工作记忆服务（双缓冲：逐轮抽取写 draft 草稿 + 会话热缓冲注入 + 会话结束巩固编排；
     * 字段注入保持构造器单参不变）
     */
    @Autowired
    private MemoryWorkingService workingService;

    /**
     * L1 模糊引擎（episodic 类经历记忆统一先进 L1 short_term——写入路径改造，
     * 3~4 游戏时渐进模糊 + 睡眠清洗 + 蒸馏；字段注入保持构造器单参不变；
     * null=未装配/开关关回直进 L2 现状）
     */
    @Autowired(required = false)
    private L1FuzzificationService fuzzService;

    /**
     * L3 核心记忆服务（：静态基线组合渲染 + 核心块 CRUD + 恒在上下文注入 + agent 自编辑；
     * 对话记忆段首部注入【核心记忆】+ 逐轮抽取后触发自编辑；字段注入保持构造器单参不变）
     */
    @Autowired
    private CoreMemoryService coreService;

    /** 归档服务 */
    @Autowired(required = false)
    private MemoryArchiveService archiveService;

    /**
     * 记忆注入器（：分场景注入统一入口——分层权重评分 + token 预算 + 结构化预览；
     * 字段注入保持构造器单参不变；不可用时回退旧 rankForInjection 路径）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryInjectorService injectorService;

    /**
     * 项目记忆服务（记忆系统重构：双通道路由 target=world/both → 编年史/实体状态；
     * 字段注入保持构造器单参不变；null=未装配跳过路由不阻断抽取）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldMemoryService worldMemoryService;

    /**
     * AI 强度闸门（微层接线， 记忆抽取按项目档位生效——
     * programmatic=零 AI（抽取整体跳过，对话不沉淀记忆）/ light=保留抽取、跳过「按需 AI 复核」
     * （候选按初判执行，省一半抽取链 AI 成本）/ full=标准 / max=全 AI 必调；
     * 字段注入保持构造器单参不变）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.AiIntensityGate aiIntensityGate;

    /**
     * 统一 AI 上下文注入器（记忆操作也属「角色视角」——AI 以该角色视角判断「什么值得记」/
     * 生成会话摘要时，前置世界观简报 + 角色核心卡；字段注入保持构造器单参不变；
     * null=未装配跳过注入，不影响原调用）
     */
    @Autowired(required = false)
    private AiContextInjector aiContextInjector;

    /**
     * 构造器（保持单参，B4 存量单测零改动；对话侧依赖经字段注入）。
     */
    public AgentMemoryService(ActorMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    // ==================== 非对话经历沉淀（，B4） ====================

    /**
     * 为当事人写一条经历记忆（kind=observation，）。
     * <p>由行动组件（AgentActionService.execute 重要度≥阈值）与事件订阅者
     * （{@link AgentMemoryEventSubscriber} 在场当事人经历）调用（统一身份 {@code npc:{id}}，特殊型双写
     * character_id + character_ref，普通型仅写 character_ref）；写入前对同 ref 已有
     * fact+observation 记忆做文本重叠去重（复用 MemoryService.isDuplicate），写入后预算淘汰。</p>
     */
    public void recordObservation(Long projectId, String characterRef, String content, Integer importance) {
        recordObservation(projectId, characterRef, content, importance, null);
    }

    /**
     * 为当事人写一条经历记忆（kind=observation，），带决策来源（P0-03 决策源可追溯）。
     * <p>由行动组件（AgentActionService.execute 重要度≥阈值）与事件订阅者
     * （{@link AgentMemoryEventSubscriber} 在场当事人经历）调用（统一身份 {@code npc:{id}}，特殊型双写
     * character_id + character_ref，普通型仅写 character_ref）；写入前对同 ref 已有
     * fact+observation 记忆做文本重叠去重（复用 MemoryService.isDuplicate），写入后预算淘汰。</p>
     *
     * @param source 决策来源（AI/程序化兜底/预算降级；可空=旧路径视为 AI）。兜底/降级产出
     *               打标后进学习管线被过滤（不吸收兜底数据学习，见 {@code DecisionSources#isAbsorbableByLearning}）
     */
    public void recordObservation(Long projectId, String characterRef, String content, Integer importance,
                                  com.holzyn.actor.domain.agent.runtime.DecisionSource source) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        String ref = characterRef.trim();
        int imp = clampImportance(importance);
        List<String> existing = loadMemories(projectId, ref).stream()
                .map(ActorMemory::getContent).toList();
        if (isDuplicate(content, existing, dedupThreshold)) {
            log.debug("[记忆沉淀] observation 去重命中，跳过：project={} ref={}", projectId, ref);
            return;
        }
        // ：经历类记忆先进 L1 short_term（3~4 游戏时渐进模糊 + 睡眠清洗 +
        // 蒸馏生命周期；开关 write-short-term-first 关闭/未装配 → 回直进 L2 现状）
        if (fuzzService != null && fuzzService.isWriteShortTermFirst()) {
            try {
                Long gs = worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
                if (fuzzService.writeShortTerm(projectId, ref, content, imp, gs)) {
                    log.debug("[记忆沉淀] observation 先进 L1 short_term：project={} ref={} 重要度={}", projectId, ref, imp);
                    evictOverBudget(projectId, ref);
                    return;
                }
            } catch (Exception e) {
                log.warn("[记忆沉淀] observation 落 L1 失败（回退直写 L2）: project={} : {}", projectId, e.getMessage());
            }
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        m.setCharacterId(NpcRefs.idOf(ref));
        m.setCharacterRef(ref);
        m.setType(ActorMemoryTypes.TYPE_EPISODIC);
        m.setContent(content);
        m.setImportance(imp);
        // P0-03 决策源可追溯：经历记忆打标来源（兜底/预算降级产出进学习管线被过滤）
        if (source != null) {
            m.setDecisionSource(source.name());
        }
        memoryRepository.save(m);
        log.debug("[记忆沉淀] 写入 observation：project={} ref={} 重要度={}", projectId, ref, imp);
        evictOverBudget(projectId, ref);
    }

    /**
     * 写一条项目级记忆（世界大事记写入方，修）。
     * <p>重大世界事件（source 语义 world/evolution，重要度≥阈值，见 {@link AgentMemoryEventSubscriber}）
     * 由订阅者调用；characterId=null + character_ref=null（所有角色可见，经既有 memoryContext
     * 项目级注入【世界大事记】出现）；写入前去重 + 项目级预算淘汰。</p>
     */
    public void recordProjectMemory(Long projectId, String content, Integer importance) {
        if (projectId == null || content == null || content.isBlank()) {
            return;
        }
        List<String> existing = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0)
                .stream().map(ActorMemory::getContent).toList();
        if (isDuplicate(content, existing, dedupThreshold)) {
            log.debug("[记忆沉淀] 项目级大事记去重命中，跳过：project={}", projectId);
            return;
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        m.setCharacterId(null);
        m.setCharacterRef(null);
        m.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        m.setSource(ActorMemoryTypes.SOURCE_EVENT);
        m.setContent(content);
        m.setImportance(clampImportance(importance));
        memoryRepository.save(m);
        log.debug("[记忆沉淀] 写入项目级大事记：project={} 重要度={}", projectId, m.getImportance());
        List<ActorMemory> proj = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        softDelete(evictToBudget(proj, budget));
    }

    /**
     * 写一条计划记忆（，kind=plan）——日计划/行动序列（对齐 Smallville 层级规划）。
     * <p>由 {@code PlanningService} 生成日计划时写入；content=计划 JSON（date_game_day + items[{hour,action,target,location,state}]）；
     * 按 ref 双轨（特殊型双写 character_id+ref / 普通型仅 ref）；写入前淘汰超预算。</p>
     * <p>⚠️ jstack 抓修：本方法补上 {@code @Transactional}，作为「日计划生成」的**写阶段**
     * 短事务。上游 {@code PlanningService.planDay/replanOnDisruption} 已去事务（事务绝不跨 AI 存活），
     * AI 调用在事务外完成后，计划记忆 + L1 task 关注点的落库在这里以毫秒级事务一次性提交——既不再
     * 长期持锁，也不会出现「记忆写了、L1 没写」的半截状态（本方法内无任何 AI/网络调用，可安全入事务）。</p>
     */
    @Transactional
    public void recordPlan(Long projectId, String characterRef, String content, Integer importance) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        m.setCharacterId(NpcRefs.idOf(characterRef.trim()));
        m.setCharacterRef(characterRef.trim());
        m.setType(ActorMemoryTypes.TYPE_PLAN);
        m.setSource(ActorMemoryTypes.SOURCE_PLANNING);
        m.setContent(content);
        m.setImportance(clampImportance(importance));
        memoryRepository.save(m);
        log.debug("[记忆] 写入日计划 type=plan：project={} ref={} 内容={}", projectId, characterRef,
                content.length() > 60 ? content.substring(0, 60) + "…" : content);
        evictOverBudget(projectId, characterRef.trim());
        // L1 工作记忆：日计划生成时同步写一条 task 关注点（来源 planning，供会话热缓冲注入）
        recordWorkingTask(projectId, characterRef.trim(), "今日计划：" + compactPlan(content), 3);
    }

    /**
     * 压缩日计划 JSON 为一行摘要（L1 task 内容用；解析失败回退原文）。
     * <p>2026-10-10 决策：不设字数硬边界，日计划全量保留（不再截断 80 字——
     * 此前截断会让 L1 工作记忆里的今日计划残缺，影响对话/决策感知）。</p>
     */
    private static String compactPlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return "";
        }
        return planJson.replaceAll("\\s+", " ").trim();
    }

    /**
     * 写一条反思洞察记忆（，kind=reflection）——洞察 + 证据指针。
     * <p>由 {@code ReflectionService} 反思时写入；evidenceIds=本次反思依据的记忆 id 列表（逗号分隔，
     * 写入 evidence_ids 列作证据指针）；按 ref 双轨；写入前淘汰超预算。</p>
     * <p><b>记忆写并发治理</b>：补 {@code @Transactional} 作为「反思写阶段」
     * 短事务——INSERT + 预算淘汰一次毫秒级提交，作为下游短事务被事务外的反思编排调用，
     * 绝不跨 AI 存活（反射链的 AI 已全部移出事务，见 ReflectionService.reflect 类注释）。</p>
     */
    @Transactional
    public void recordReflection(Long projectId, String characterRef, String content, Integer importance,
                                 List<Long> evidenceIds) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        m.setCharacterId(NpcRefs.idOf(characterRef.trim()));
        m.setCharacterRef(characterRef.trim());
        m.setType(ActorMemoryTypes.TYPE_REFLECTION);
        m.setSource(ActorMemoryTypes.SOURCE_REFLECTION);
        m.setContent(content);
        m.setImportance(clampImportance(importance));
        if (evidenceIds != null && !evidenceIds.isEmpty()) {
            m.setEvidenceIds(evidenceIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null));
        }
        memoryRepository.save(m);
        log.debug("[记忆] 写入反思洞察 type=reflection：project={} ref={} 证据={}",
                projectId, characterRef, evidenceIds == null ? 0 : evidenceIds.size());
        evictOverBudget(projectId, characterRef.trim());
    }

    /**
     * 写一条 L1 进行中任务/关注点。
     * <p>委托 {@link MemoryWorkingService#writeTask}（每角色 active task 有界防膨胀）；
     * 由 {@code AgentActionService}（行动执行）与 {@link #recordPlan}（日计划生成）调用。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（统一身份 {@code npc:{actor_profile.id}}）
     *
     * @param content 任务内容（如「当前行动：…」）
     *
     * @param importanceHint 巩固重要度建议（1~5）
     */
    public void recordWorkingTask(Long projectId, String characterRef, String content, Integer importanceHint) {
        if (projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        if (workingService == null) {
            return;
        }
        try {
            workingService.writeTask(projectId, characterRef.trim(), content, importanceHint);
        } catch (Exception e) {
            log.warn("[L1 工作记忆] task 写入失败（不阻断主流程）: {}", e.getMessage());
        }
    }

    /**
     * R3 staleness 复核：反思优先复核被标记记忆后，刷新其最近访问时间（视为已复核、清除「可能过时」标记）。
     * <p><b>记忆写并发治理</b>：补 {@code @Transactional} 作为「反思写阶段」
     * 短事务——证据行 UPDATE 循环一次毫秒级提交，被事务外的反思编排调用，不跨 AI 存活。</p>
     *
     * @param projectId 项目 ID
     *
     * @param memoryIds 本次反思依据的记忆 id 列表（复核对象）
     */
    @Transactional
    public void recheckMemories(Long projectId, List<Long> memoryIds) {
        if (projectId == null || memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long id : memoryIds) {
            if (id == null) {
                continue;
            }
            memoryRepository.findById(id).filter(m -> projectId.equals(m.getProjectId()))
                    .ifPresent(m -> {
                        m.setLastAccessAt(now);
                        m.setAccessCount(m.getAccessCount() == null ? 1 : m.getAccessCount() + 1);
                        memoryRepository.save(m);
                    });
        }
    }

    // ==================== 检索（Smallville 评分：重要度 + recency + relevance，B4） ====================

    /**
     * 检索某角色的记忆流（fact + observation），按「重要度 + recency 指数衰减 + relevance」评分取 top-K。
     * <p>recency 基准：最近检索命中时间 last_access_at（无则创建时间），指数衰减
     * {@code e^(-age/decayHours)}；relevance 无 embedding 时用文本重叠（Dice 二元组）降级，
     * embedding 余弦留 / RAG 接入。检索命中更新 last_access_at/access_count（遗忘工程依据）。</p>
     */
    public List<ActorMemory> retrieve(Long projectId, String characterRef, String query, int topK) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return List.of();
        }
        int k = topK <= 0 ? retrievalTopK : topK;
        LocalDateTime now = LocalDateTime.now();
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, now);
        List<ActorMemory> candidates = loadMemories(projectId, characterRef.trim()).stream()
                .filter(m -> ActorMemoryTypes.TYPE_SEMANTIC.equals(m.getType())
                        || ActorMemoryTypes.TYPE_EPISODIC.equals(m.getType()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (ActorMemory m : candidates) {
            LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
            double recency = recencyScore(base, now, recencyDecayHours);
            double relevance = (query == null || query.isBlank())
                    ? 0 : relevanceScore(query, m.getContent());
            double score = retrievalScore(m.getImportance() == null ? 1 : m.getImportance(), recency, relevance);
            m.setStale(staleFlag(projectId, m, nowGameHour));
            scored.add(new Scored(m, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<ActorMemory> result = scored.stream().limit(k).map(Scored::memory).toList();
        touch(result, now);
        return result;
    }

    /**
     * R3 staleness 判定（实例级）：重要度≥阈值 且 距上次复核（last_access_at 优先/created_at 兜底）
     * 超过 {@code staleAfterGameDays} 个游戏日的 fact/observation 高重要事实 → 标记「可能过时」。
     * <p>时间基准=游戏时间（WorldClockService.gameHourAt 换算，统一口径）；复核=反思优先复核后
     * 刷新 last_access_at（{@link #recheckMemories}），标记自然清除。</p>
     *
     * @param projectId 项目 ID（游戏时间换算基准）
     *
     * @param m 记忆实体
     *
     * @param nowGameHour 当前游戏小时（可空=不判定）
     *
     * @return true 表示「可能过时」
     */
    private boolean staleFlag(Long projectId, ActorMemory m, Long nowGameHour) {
        if (projectId == null || m == null || nowGameHour == null || worldClockService == null) {
            return false;
        }
        if (m.getImportance() == null || m.getImportance() < staleImportanceThreshold) {
            return false;
        }
        String type = m.getType();
        if (!ActorMemoryTypes.TYPE_SEMANTIC.equals(type) && !ActorMemoryTypes.TYPE_EPISODIC.equals(type)) {
            return false;
        }
        LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
        if (base == null) {
            return false;
        }
        Long baseGameHour = worldClockService.gameHourAt(projectId, base);
        if (baseGameHour == null) {
            return false;
        }
        long nowDay = Math.floorDiv(nowGameHour, 24);
        long baseDay = Math.floorDiv(baseGameHour, 24);
        return staleByGameDays(baseDay, nowDay, staleAfterGameDays);
    }

    /**
     * R3 staleness 纯逻辑（静态可测）：是否超 {@code afterDays} 个游戏日未复核。
     *
     * @param baseGameDay 上次复核所在游戏日编号（floorDiv(gameHour,24)）
     *
     * @param nowGameDay 当前游戏日编号
     *
     * @param afterDays 超期阈值（游戏日）
     *
     * @return true 表示超期
     */
    public static boolean staleByGameDays(long baseGameDay, long nowGameDay, int afterDays) {
        return nowGameDay - baseGameDay >= afterDays;
    }

    /**
     * 批量刷新 staleness 标记（注入渲染前调用）：对 top-K 排序后的记忆批量设置 transient stale。
     *
     * @param projectId 项目 ID
     *
     * @param memories 记忆列表（就地修改 transient 标记）
     *
     * @param nowGameHour 当前游戏小时（可空）
     */
    private void markStaleAll(Long projectId, List<ActorMemory> memories, Long nowGameHour) {
        if (memories == null) {
            return;
        }
        for (ActorMemory m : memories) {
            m.setStale(staleFlag(projectId, m, nowGameHour));
        }
    }

    /**
     * recency 指数衰减分（0~1）：距基准时刻越近越高，衰减半程 decayHours 小时。静态可测。
     */
    static double recencyScore(LocalDateTime base, LocalDateTime now, int decayHours) {
        if (base == null || now == null) {
            return 0;
        }
        long ageSeconds = Duration.between(base, now).getSeconds();
        if (ageSeconds <= 0) {
            return 1;
        }
        double decaySeconds = Math.max(1, decayHours) * 3600.0;
        return Math.exp(-ageSeconds / decaySeconds);
    }

    /**
     * 文本相关性分（0~1）：无 embedding 时的降级实现——Dice 二元组重叠（复用 MemoryService.overlapScore）。
     */
    static double relevanceScore(String query, String content) {
        return overlapScore(query, content);
    }

    /**
     * Smallville 检索总评分 = 2×重要度 + 3×recency + 2×relevance。静态可测。
     */
    static double retrievalScore(int importance, double recency, double relevance) {
        return 2.0 * importance + 3.0 * recency + 2.0 * relevance;
    }

    // ==================== 收编：对话抽取（MemoryService.extractAfterRound 系） ====================

    /**
     * 异步抽取入口：对话每轮回复完成后调用，不阻塞 SSE 主流程。
     * 记忆开关关闭时直接返回（与旧行为一致）。
     *
     * @param conversationId 会话主键
     *
     * @param userId 归属用户 ID（已从请求线程捕获，不依赖虚拟线程 SecurityContext）
     */
    public void extractAfterRoundAsync(Long conversationId, Long userId) {
        if (!memoryEnabled) {
            return;
        }
        executor.execute(() -> {
            try {
                extractAfterRound(conversationId, userId);
            } catch (Exception e) {
                log.warn("[记忆抽取] 异步抽取失败: 会话={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 会话结束兜底：用户向会话发送新消息时调用。
     * <p>若该会话距上一条消息已超过 {@code sessionTimeoutMinutes}（默认 30 分钟），视为上次会话已结束——
     * 在本轮对话开始前异步<b>巩固上次会话</b>（L1 草稿复核提升进 L2 + 强制会话摘要直写 L2；
     * 对齐设计 §5.2「会话结束/超时触发巩固」双缓冲语义，替代旧的「完整重提」）。
     * 若窗口内均为寒暄则门控跳过、不落库。天然防抖：巩固后新对话会刷新 lastMessageAt，
     * 下次需再空闲超时才触发。</p>
     *
     * @param conv 会话实体（须在更新 lastMessageAt 之前传入）
     *
     * @param userId 归属用户 ID（请求线程捕获）
     */
    public void maybeExtractAfterIdleAsync(ActorConversation conv, Long userId) {
        if (!memoryEnabled) {
            return;
        }
        LocalDateTime last = conv.getLastMessageAt();
        if (last == null) {
            return;
        }
        if (last.plusMinutes(sessionTimeoutMinutes).isAfter(LocalDateTime.now())) {
            return;
        }
        log.info("[记忆懒检查] 会话={} 距上条消息已超 {} 分钟，异步巩固上次会话（L1 草稿复核提升 + 强制摘要）",
                conv.getId(), sessionTimeoutMinutes);
        executor.execute(() -> {
            try {
                consolidateByConversation(conv.getId(), userId, true);
            } catch (Exception e) {
                log.warn("[记忆懒检查] 巩固失败: 会话={} : {}", conv.getId(), e.getMessage());
            }
        });
    }

    /**
     * 会话结束巩固：把某会话的 L1 工作项（草稿/任务/上下文/感知）
     * 复核提升进 L2（source=consolidation，经 {@link MemoryWorkingService#consolidateSession}），
     * 并按需强制生成会话摘要直写 L2（保持既有会话结束补摘要语义）。
     *
     * @param conversationId 会话主键（=L1 的 session_key）
     *
     * @param userId 归属用户 ID
     *
     * @param forceSummary true=强制补会话摘要（对话超时懒检查）；false=仅巩固工作项（归档收尾场景）
     */
    public void consolidateByConversation(Long conversationId, Long userId, boolean forceSummary) {
        if (!memoryEnabled) {
            return;
        }
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        // ① L1 → L2 双缓冲巩固（草稿/任务/上下文/感知 按规则提升或过期）
        if (workingService != null) {
            try {
                workingService.consolidateSession(String.valueOf(conversationId));
            } catch (Exception e) {
                log.warn("[记忆巩固] 会话={} L1 巩固失败（隔离）: {}", conversationId, e.getMessage());
            }
        }
        if (!forceSummary) {
            return;
        }
        // ② 强制会话摘要直写 L2
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        if (history.isEmpty()) {
            return;
        }
        Long characterId = resolveMemoryCharacter(conv, history);
        String characterRef = resolveMemoryCharacterRef(conv, history);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用能否解析成 actor_profile 行（special/common 都成立）
        boolean hasOwnerRef = NpcRefs.isNpcRef(characterRef);
        if (characterId == null && !hasOwnerRef) {
            log.info("[记忆巩固] 会话={} 无归属角色，跳过强制摘要", conversationId);
            return;
        }
        boolean group = "group".equals(conv.getMode());
        if (gateEnabled && !segmentHasSubstance(history, group, greetingMaxLen, groupGreetingMaxLen)) {
            log.info("[记忆巩固] 会话={} 最近对话均为寒暄/无实质信息，跳过会话结束补摘要", conversationId);
            return;
        }
        String dialogText = buildDialogText(recentMessages(history));
        if (dialogText.isBlank()) {
            return;
        }
        String summary = summarizeDialog(userId, conv.getProjectId(), characterRef, dialogText);
        if (summary != null) {
            saveMemoryFor(conv.getProjectId(), characterId, characterRef,
                    ActorMemoryTypes.TYPE_SEMANTIC, summary, SUMMARY_IMPORTANCE,
                    ActorMemoryTypes.CONTEXT_SUMMARY_JSON);
            log.info("[记忆巩固] 会话={} 角色={} 强制会话摘要已落 L2", conversationId, characterId);
        }
    }

    /**
     * 同步抽取核心（默认模式：逐轮门控 + 按 summary-interval 生成摘要）。
     */
    public void extractAfterRound(Long conversationId, Long userId) {
        extractAfterRound(conversationId, userId, false);
    }

    /**
     * 同步抽取核心（收编，行为与原 MemoryService.extractAfterRound 一致）：对会话最近一轮对话
     * 抽取新事实（去重后写入），并按需生成会话摘要、淘汰超预算记忆。归属角色：单聊=会话首个成员角色；
     * 群聊=最近一条 assistant 消息的归属角色。普通型 NPC 归属 {@code npc:{id}}（character_ref 双轨）。
     *
     * @param conversationId 会话主键
     *
     * @param userId 归属用户 ID（异步调用方须从请求线程捕获传入）
     *
     * @param forceSummary 会话结束兜底：true=窗口有实质即强制补摘要；false=按 summary-interval
     */
    public void extractAfterRound(Long conversationId, Long userId, boolean forceSummary) {
        if (!memoryEnabled) {
            return;
        }
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        // 微层接线：programmatic 档零 AI——抽取整体跳过（记忆不沉淀，机制兜底=不抽取；
        // 对话主流程不受影响，行为与 memory.enabled=false 同语义）
        if (aiIntensityGate != null && !aiIntensityGate.allowsAi(conv.getProjectId())) {
            log.debug("[记忆抽取] AI 强度档位 programmatic，跳过抽取（零 AI）: conversation={}", conversationId);
            return;
        }
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        if (history.isEmpty()) {
            return;
        }
        Long characterId = resolveMemoryCharacter(conv, history);
        String characterRef = resolveMemoryCharacterRef(conv, history);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用能否解析成 actor_profile 行（special/common 都成立）
        boolean hasOwnerRef = NpcRefs.isNpcRef(characterRef);
        if (characterId == null && !hasOwnerRef) {
            log.info("[记忆抽取] 会话={} 无归属角色，跳过抽取", conversationId);
            return;
        }
        boolean group = "group".equals(conv.getMode());
        if (gateEnabled) {
            if (forceSummary) {
                if (!segmentHasSubstance(history, group, greetingMaxLen, groupGreetingMaxLen)) {
                    log.info("[记忆抽取] 会话={} 角色={} 最近对话均为寒暄/无实质信息，跳过会话结束补提", conversationId, characterId);
                    return;
                }
            } else if (!lastRoundHasSubstance(history, group, greetingMaxLen, groupGreetingMaxLen)) {
                log.info("[记忆抽取] 会话={} 角色={} 本轮为寒暄/无实质信息，跳过提取", conversationId, characterId);
                return;
            }
            if (gateAiReview && !aiReviewHasSubstance(conv, history, userId)) {
                log.info("[记忆抽取] 会话={} 角色={} AI 复核判定无值得记忆内容，跳过提取", conversationId, characterId);
                return;
            }
        }
        String dialogText = buildDialogText(recentMessages(history));
        if (dialogText.isBlank()) {
            return;
        }
        List<ActorMemory> existingMemories = loadExistingForExtract(conv.getProjectId(), characterId, characterRef);
        List<String> existing = new ArrayList<>(existingMemories.stream().map(ActorMemory::getContent).toList());

        // ① AI 抽取新事实（记忆系统重构三重机制：模板「什么值得记」准则 + target/operation 契约 +
        // 重要度分级——抽取输出=含 target 的条目数组，单次抽取双通道路由）
        List<MemoryExtractParse.ExtractedMemory> extracted =
                extractFacts(userId, conv.getProjectId(), characterRef, dialogText, existing);

        // ①-a 双通道路由（设计 §4.2）：target=world/both 的世界信息 → 项目记忆
        // （事件类 kind=event → 编年史；状态类 kind=entity → 实体状态表失效更新；
        // target=both 角色侧个人经历由 ①-b 落 episodic）
        routeWorldCandidates(userId, conv.getProjectId(), extracted);

        // ①-b 角色侧三重机制（设计 §3.2）：按需 AI 复核（候选=重要度≥review-threshold 或
        // update/invalidate）→ 重要度分级（≤drop-below 直接丢弃 / <draft-below 进 L1 草稿
        // 待巩固复核 / ≥draft-below 直进 L2）→ 操作判定（add 新增 / update 版本链 supersedes /
        // invalidate 旧条软删归档不写新条）
        List<MemoryExtractParse.ExtractedMemory> charItems = extracted.stream()
                .filter(MemoryExtractParse.ExtractedMemory::isCharacterTargeted)
                .toList();
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> review =
                // 微层接线：light 档跳过「按需 AI 复核」（候选按初判执行，省一半抽取链 AI 成本）
                aiIntensityGate != null && aiIntensityGate.isLight(conv.getProjectId())
                        ? Map.of()
                        : reviewCandidates(userId, conv.getProjectId(), characterId, characterRef, charItems, existing);
        int added = 0, dropped = 0, updated = 0, invalidated = 0;
        for (int i = 0; i < charItems.size(); i++) {
            MemoryExtractParse.ExtractedMemory em = charItems.get(i);
            // 复核调整（keep 默认按初判 / drop 丢弃 / modify 替换内容与重要度后仍按原操作执行；
            // 复核失败静默回退=keep，不阻断——设计 §3.2「复核失败静默回退」）
            MemoryExtractReviewParse.ReviewDecision d = review.get(i);
            if (d != null && d.isDrop()) {
                dropped++;
                continue;
            }
            if (d != null && (d.content() != null || d.importance() != null)) {
                em = new MemoryExtractParse.ExtractedMemory(em.kind(),
                        d.content() != null ? d.content() : em.content(),
                        d.importance() != null ? d.importance() : em.importance(),
                        em.target(), em.operation());
            }
            // 操作判定（Mem0 式 add/update/invalidate，设计 §3.2③）——update/invalidate 先于去重：
            // 其内容与旧条天然重叠（演进/推翻同一事实），去重只拦截 add 新事实
            String op = em.operation() == null ? MemoryExtractParse.OPERATION_ADD : em.operation();
            if (MemoryExtractParse.OPERATION_INVALIDATE.equals(op)) {
                // invalidate：旧事实已失效（角色明确推翻/情况变化）→ 匹配旧条软删+归档，不写新条
                ActorMemory target = findSupersedeTarget(em.content(), existingMemories);
                if (target != null) {
                    invalidateMemory(target);
                    invalidated++;
                } else {
                    dropped++; // 无匹配旧条：丢弃（不写新条）
                }
                continue;
            }
            if (MemoryExtractParse.OPERATION_UPDATE.equals(op)) {
                // update：与已有记忆矛盾或演进 → 写新条 supersedes 旧条 + 旧条归档（版本链）
                ActorMemory target = findSupersedeTarget(em.content(), existingMemories);
                if (target != null) {
                    saveMemorySuperseding(conv.getProjectId(), characterId, characterRef,
                            typeOf(em.kind()), em.content(), em.importance(), target);
                    existing.add(em.content());
                    updated++;
                    continue;
                }
                // 无匹配旧条：退化为 add（走下方重要度分级，含去重）
            }
            // add：去重（与已有记忆文本重叠过滤）→ 重要度分级 ②③
            // （<draft-below 进 L1 草稿待巩固复核；≥draft-below 直进 L2——设计 §3.2「3+ 直进 L2」）
            if (isDuplicate(em.content(), existing, dedupThreshold)) {
                continue;
            }
            // 重要度分级门槛①：importance≤drop-below 直接丢弃（设计 §3.2「1 丢」——无长期价值，真人不会记）
            if (em.importance() <= extractDropBelow) {
                dropped++;
                continue;
            }
            // 重要度分级 ②③：<draft-below 进 L1 草稿待巩固复核；≥draft-below 直进 L2
            // 写入路径改造：episodic 类（个人经历）统一先进 L1 short_term
            // （3~4 游戏时渐进模糊 + 睡眠清洗 + 蒸馏；高重要 4-5 靠 快车道次日蒸馏回 L2，
            // L1 清晰段注入保障期间可用）；semantic 类保持现状分级不变
            String addType = typeOf(em.kind());
            if (ActorMemoryTypes.TYPE_EPISODIC.equals(addType)
                    && fuzzService != null && fuzzService.isWriteShortTermFirst()) {
                try {
                    Long gs = worldClockService == null ? null
                            : worldClockService.currentEngineGameSecond(conv.getProjectId());
                    if (fuzzService.writeShortTerm(conv.getProjectId(), characterRef,
                            em.content(), em.importance(), gs)) {
                        existing.add(em.content());
                        added++;
                    }
                } catch (Exception e) {
                    log.warn("[记忆抽取] episodic 落 L1 short_term 失败（回退既有分级）: {}", e.getMessage());
                    saveMemoryFor(conv.getProjectId(), characterId, characterRef,
                            addType, em.content(), em.importance(), null);
                    existing.add(em.content());
                    added++;
                }
            } else if (em.importance() < extractDraftBelow) {
                // 双缓冲：写 L1 draft（session_key=会话 id），会话结束由 consolidateByConversation
                // 经 MemoryWorkingService 复核提升为 L2 semantic（source=consolidation）
                if (workingService != null
                        && workingService.writeDraft(conv.getProjectId(), characterRef,
                        String.valueOf(conversationId), em.content(), em.importance())) {
                    existing.add(em.content());
                    added++;
                }
            } else {
                saveMemoryFor(conv.getProjectId(), characterId, characterRef,
                        typeOf(em.kind()), em.content(), em.importance(), null);
                existing.add(em.content());
                added++;
            }
        }
        if (dropped > 0 || updated > 0 || invalidated > 0) {
            log.info("[记忆抽取] 会话={} 角色={} 三重机制：写入 {} 条（含草稿），丢弃 {} 条，更新 {} 条，失效 {} 条",
                    conversationId, characterId, added, dropped, updated, invalidated);
        }
        // L3 对话挂钩（双挂钩之一）：本轮抽取新增了草稿（有实质新信息）才触发核心块自编辑评估——
        // 把当前核心块 + 最近对话交给 AI 判断身份级信息是否需要更新（对齐 Letta「对话中 agent 自编辑」）；
        // 异步线程内同步执行（失败静默，不阻断抽取/对话主流程）
        if (added > 0 && coreService != null) {
            coreService.maybeSelfEditFromDialog(userId, conv.getProjectId(), characterRef, dialogText);
        }

        // ② 生成会话摘要（决策：摘要保持直写 L2——type=semantic + context_json subtype=summary，
        // 保证对话中【过往对话摘要】立即可用，不走 L1 双缓冲）
        long completedAssistant = history.stream()
                .filter(m -> "assistant".equals(m.getRole()) && "done".equals(m.getStatus()))
                .count();
        if (forceSummary || shouldGenerateSummary(completedAssistant, summaryInterval)) {
            String summary = summarizeDialog(userId, conv.getProjectId(), characterRef, dialogText);
            if (summary != null) {
                // 会话摘要 → L2 semantic + context_json subtype=summary（与事实同 type、子类型区分）
                saveMemoryFor(conv.getProjectId(), characterId, characterRef,
                        ActorMemoryTypes.TYPE_SEMANTIC, summary, SUMMARY_IMPORTANCE,
                        ActorMemoryTypes.CONTEXT_SUMMARY_JSON);
            }
        }

        // ③ 超预算滚动淘汰（角色级 + 项目级；普通型 NPC 按 character_ref 双轨）
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「是否普通型」改查 actor_profile.type
        if (isCommonNpc(conv.getProjectId(), characterRef)) {
            evictOverBudget(conv.getProjectId(), characterRef);
        } else {
            evictOverBudget(conv.getProjectId(), characterId);
        }
        log.info("[记忆抽取] 会话={} 角色={} 抽取新增 {} 条草稿（L1，会话结束巩固后升 L2）",
                conversationId, characterId, added);
    }

    /**
     * 手动触发抽取（调试/补抽）：对指定角色参与的最近一个会话执行一次抽取。
     */
    public int extractForCharacter(Long characterId) {
        if (!memoryEnabled) {
            return 0;
        }
        Long userId = currentUserProvider.currentUserId();
        ActorCharacter ch = characterRepository.findById(characterId)
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
        requireProject(ch.getProjectId(), userId);
        List<ActorConversationMember> members = memberRepository.findByCharacterId(characterId);
        if (members.isEmpty()) {
            log.info("[记忆补抽] 角色={} 无参与会话，跳过", characterId);
            return 0;
        }
        ActorConversation target = null;
        LocalDateTime latest = null;
        for (ActorConversationMember m : members) {
            ActorConversation c = conversationService.requireOwned(m.getConversationId());
            if (latest == null || (c.getLastMessageAt() != null && c.getLastMessageAt().isAfter(latest))) {
                latest = c.getLastMessageAt();
                target = c;
            }
        }
        if (target == null) {
            return 0;
        }
        int before = countFacts(target.getProjectId(), characterId);
        // 双缓冲：先补抽（写 L1 草稿），再立即巩固该会话（草稿复核提升进 L2），保证手动补抽即时可见
        extractAfterRound(target.getId(), userId);
        consolidateByConversation(target.getId(), userId, false);
        return countFacts(target.getProjectId(), characterId) - before;
    }

    // ==================== 收编：注入（MemoryService.memoryContext 系） ====================

    /**
     * 生成「长期记忆」注入片段（character_id 路径）：角色级 fact+observation top-K + 项目级大事记 top-K。
     * <p>注入前对 top-K 结果做「与历史窗口文本重叠过滤」（{@link #filterAgainstTexts}），
     * 剔除与刚说过内容重叠的记忆，避免上下文冗余/token 浪费； R3：注入渲染带「（可能过时）」标记。</p>
     */
    public String memoryContext(Long userId, Long projectId, Long characterId) {
        return memoryContext(userId, projectId, characterId, null, null);
    }

    /**
     * 生成「长期记忆」注入片段（character_id 路径， 去重版：excludeTexts=最近会话历史窗口文本列表）。
     */
    public String memoryContext(Long userId, Long projectId, Long characterId, List<String> excludeTexts) {
        return memoryContext(userId, projectId, characterId, null, excludeTexts);
    }

    /**
     * 生成「长期记忆」注入片段（character_id 路径， 带检索 query 版）。
     *
     * @param query 检索 query（对话话题/最近用户消息等情境文本——tier 判定（scene=memory_tier）与
     *              L2 语义检索共用；可空=旧行为「（无明确查询）」）
     *
     * @param excludeTexts 最近会话历史窗口文本列表（可空）
     */
    public String memoryContext(Long userId, Long projectId, Long characterId, String query, List<String> excludeTexts) {
        if (!memoryEnabled) {
            return "";
        }
        // 分场景注入：对话场景走统一注入器（L3 核心 + L1 热缓冲 + L2 评分 top-K + 世界大事记 + 过往摘要，
        // token 预算控制）；注入器不可用时回退旧路径（rankForInjection + 追加段）保持行为
        if (injectorService != null) {
            try {
                var result = injectorService.build(projectId, com.holzyn.actor.common.NpcRefs.of(characterId),
                        com.holzyn.actor.domain.memory.service.MemoryInjectorService.SCENE_DIALOG,
                        query, excludeTexts);
                String rendered = injectorService.render(result);
                if (rendered != null && !rendered.isBlank()) {
                    return rendered;
                }
            } catch (Exception e) {
                log.warn("[对话注入] M5 注入器失败，回退旧路径: {}", e.getMessage());
            }
        }
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, LocalDateTime.now());
        StringBuilder sb = new StringBuilder();
        if (characterId != null) {
            // L3 恒在注入（设计 §9.1：【核心记忆】位于注入组合首位，在【当前情境】L1 之前）
            appendCoreSegment(sb, projectId, com.holzyn.actor.common.NpcRefs.of(characterId));
            // L1 热缓冲注入（设计 §9.1：【当前情境】本会话热缓冲 ≤5 工作项；在【长期记忆】之前）
            appendWorkingSegment(sb, projectId, com.holzyn.actor.common.NpcRefs.of(characterId));
            List<ActorMemory> charMemories = memoryRepository
                    .findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                            projectId, characterId, List.of(ActorMemoryTypes.TYPE_SEMANTIC, ActorMemoryTypes.TYPE_EPISODIC), 0);
            List<ActorMemory> ranked = rankForInjection(charMemories, topK);
            markStaleAll(projectId, ranked, nowGameHour);
            appendCharSection(sb, filterAgainstTexts(ranked, excludeTexts, dedupThreshold));
        }
        appendProjectSection(sb, projectId, projectTopK);
        return sb.toString().trim();
    }

    /**
     * 生成「长期记忆」注入片段（character_ref 版，B5 普通 NPC 进会话用； /R3 同上）。
     */
    public String memoryContextByRef(Long userId, Long projectId, String characterRef) {
        return memoryContextByRef(userId, projectId, characterRef, null, null);
    }

    /**
     * 生成「长期记忆」注入片段（character_ref 版， 去重版）。
     */
    public String memoryContextByRef(Long userId, Long projectId, String characterRef, List<String> excludeTexts) {
        return memoryContextByRef(userId, projectId, characterRef, null, excludeTexts);
    }

    /**
     * 生成「长期记忆」注入片段（character_ref 版， 带检索 query 版——语义同 {@link #memoryContext(Long, Long, Long, String, List)}）。
     */
    public String memoryContextByRef(Long userId, Long projectId, String characterRef, String query, List<String> excludeTexts) {
        if (!memoryEnabled || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        String ref = characterRef.trim();
        // 分场景注入：对话场景走统一注入器（普通型 NPC 同路径，npc:{id} 轻量基线 + 核心块）
        if (injectorService != null) {
            try {
                var result = injectorService.build(projectId, ref,
                        com.holzyn.actor.domain.memory.service.MemoryInjectorService.SCENE_DIALOG,
                        query, excludeTexts);
                String rendered = injectorService.render(result);
                if (rendered != null && !rendered.isBlank()) {
                    return rendered;
                }
            } catch (Exception e) {
                log.warn("[对话注入] M5 注入器失败（ref），回退旧路径: {}", e.getMessage());
            }
        }
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, LocalDateTime.now());
        StringBuilder sb = new StringBuilder();
        // L3 恒在注入（普通型 NPC 走 npc:{id} 轻量基线 + 手动核心块；设计 §7.3/§9.1）
        appendCoreSegment(sb, projectId, ref);
        // L1 热缓冲注入（普通型 NPC 也走工作记忆：npc:{id}）
        appendWorkingSegment(sb, projectId, ref);
        List<ActorMemory> refMemories = memoryRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(projectId, ref, 0);
        List<ActorMemory> typed = refMemories.stream()
                .filter(m -> ActorMemoryTypes.TYPE_SEMANTIC.equals(m.getType())
                        || ActorMemoryTypes.TYPE_EPISODIC.equals(m.getType()))
                .toList();
        List<ActorMemory> ranked = rankForInjection(typed, topK);
        markStaleAll(projectId, ranked, nowGameHour);
        appendCharSection(sb, filterAgainstTexts(ranked, excludeTexts, dedupThreshold));
        appendProjectSection(sb, projectId, projectTopK);
        return sb.toString().trim();
    }

    /**
     * L3 恒在上下文注入：把某角色的【核心记忆】段（静态基线 + 动态核心块）追加到注入缓冲首部
     * （设计 §9.1 注入组合首位，位于【当前情境】L1 之前）；无内容/开关关/服务不可用时不追加，保持旧行为。
     */
    private void appendCoreSegment(StringBuilder sb, Long projectId, String characterRef) {
        if (coreService == null) {
            return;
        }
        String core;
        try {
            core = coreService.renderCoreSegment(projectId, characterRef);
        } catch (Exception e) {
            log.warn("[对话注入] L3 核心记忆注入失败（跳过）: {}", e.getMessage());
            return;
        }
        if (core == null || core.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(core);
    }

    /**
     * L1 热缓冲注入：把某角色的 active 工作项以【当前情境】段追加到注入缓冲（在设计 §9.1 中
     * 位于【长期记忆】之前；无内容/开关关/服务不可用时不追加，保持旧行为）。
     */
    private void appendWorkingSegment(StringBuilder sb, Long projectId, String characterRef) {
        if (workingService == null) {
            return;
        }
        String working;
        try {
            working = workingService.injectWorkingSegment(projectId, characterRef);
        } catch (Exception e) {
            log.warn("[对话注入] L1 工作记忆注入失败（跳过）: {}", e.getMessage());
            return;
        }
        if (working == null || working.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(working);
    }

    /**
     * 注入去重（静态可测）：把 top-K 记忆里与「历史窗口文本」重叠度 ≥ 阈值的条目剔除，
     * 避免「刚说过的事实又被记忆注入」造成上下文冗余（复用 Dice 二元组 overlapScore）。
     *
     * @param memories 排序后的记忆列表（可空）
     *
     * @param excludeTexts 历史窗口文本列表（可空/空=不去重）
     *
     * @param threshold 重叠阈值（0~1）
     *
     * @return 过滤后的记忆列表
     */
    static List<ActorMemory> filterAgainstTexts(List<ActorMemory> memories, List<String> excludeTexts, double threshold) {
        if (memories == null || memories.isEmpty() || excludeTexts == null || excludeTexts.isEmpty()) {
            return memories;
        }
        List<String> texts = excludeTexts.stream().filter(t -> t != null && !t.isBlank()).toList();
        if (texts.isEmpty()) {
            return memories;
        }
        List<ActorMemory> out = new ArrayList<>();
        for (ActorMemory m : memories) {
            boolean dup = false;
            for (String t : texts) {
                if (overlapScore(m.getContent(), t) >= threshold) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 生成「过往对话摘要」注入片段（S2 窗口压缩承接）：取该角色最近一条会话摘要。
     */
    public String conversationSummaryContext(Long projectId, Long characterId) {
        if (!memoryEnabled || characterId == null) {
            return "";
        }
        return memoryRepository
                .findSummariesByProjectIdAndCharacterId(projectId, characterId)
                .stream().findFirst()
                .map(ActorMemory::getContent)
                .orElse("");
    }

    /**
     * 分场景注入预览（公开）：按场景组装注入组合并返回结构化结果（各段 + 命中记忆 + 预算），
     * 供「注入预览」API（调试前端注入预览页 + 主前端角色页预览弹窗）使用。
     * 注入器不可用时返回空结果（调用方提示不可用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{actor_profile.id}}）
     *
     * @param scenario 场景（dialog/decision/perception/reflection）
     *
     * @param query 检索 query（可空）
     *
     * @param excludeTexts 去重文本（可空）
     *
     * @return 注入结果（空场景 = 不可用）
     */
    public com.holzyn.actor.domain.memory.service.MemoryInjectorService.InjectionResult buildInjection(
            Long projectId, String characterRef, String scenario, String query, List<String> excludeTexts) {
        if (injectorService == null) {
            return com.holzyn.actor.domain.memory.service.MemoryInjectorService.InjectionResult.empty(scenario);
        }
        try {
            return injectorService.build(projectId, characterRef, scenario, query, excludeTexts);
        } catch (Exception e) {
            log.warn("[注入预览] 注入器组装失败: {}", e.getMessage());
            return com.holzyn.actor.domain.memory.service.MemoryInjectorService.InjectionResult.empty(scenario);
        }
    }

    // ==================== 收编：管理（MemoryService.list/delete） ====================

    /**
     * 记忆列表（归属校验）。characterId/characterRef 为空返回项目全部记忆，非空返回该成员记忆。
     */
    public PageResult<MemoryVO> list(Long projectId, Long characterId, String characterRef, int page, int size) {
        requireProject(projectId, currentUserProvider.currentUserId());
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<ActorMemory> p;
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用能解析成 actor_profile 行（special/common
        // 都成立，故不再只认 crowd:），校验档案存在后按 character_ref 列查询
        if (NpcRefs.isNpcRef(characterRef)) {
            Long npcId = NpcRefs.idOf(characterRef);
            if (npcId == null || npcRepository.findByIdAndProjectId(npcId, projectId).isEmpty()) {
                throw new BizException(404, "普通 NPC 不存在或无权访问");
            }
            p = memoryRepository.findByProjectIdAndCharacterRefAndDeleted(
                    projectId, characterRef, 0, PageRequest.of(safePage, safeSize));
        } else {
            if (characterId != null) {
                requireCharacterInProject(characterId, projectId);
            }
            p = characterId == null
                    ? memoryRepository.findByProjectIdAndDeleted(projectId, 0, PageRequest.of(safePage, safeSize))
                    : memoryRepository.findByProjectIdAndCharacterIdAndDeleted(
                            projectId, characterId, 0, PageRequest.of(safePage, safeSize));
        }
        List<MemoryVO> vos = p.getContent().stream().map(MemoryVO::of).toList();
        return PageResult.of(vos, p.getTotalElements(), page, safeSize);
    }

    /**
     * 工作记忆（L1）列表：characterRef 为空返回项目级工作项；npc ref 原样传；
     * characterId 非空时按 {@code npc:{id}} 解析。委托 {@link MemoryWorkingService#list}。
     */
    public PageResult<MemoryWorkingVO> listWorking(Long projectId, Long characterId, String characterRef,
                                                   String status, int page, int size) {
        requireProject(projectId, currentUserProvider.currentUserId());
        String ref;
        if (characterRef != null && !characterRef.isBlank()) {
            ref = characterRef.trim();
        } else if (characterId != null) {
            requireCharacterInProject(characterId, projectId);
            ref = com.holzyn.actor.common.NpcRefs.of(characterId);
        } else {
            ref = null; // 项目级工作项
        }
        return workingService == null
                ? PageResult.of(List.of(), 0, page, size)
                : workingService.list(projectId, ref, status, page, size);
    }

    /**
     * 删除记忆（软删，归属校验）。
     */
    public void delete(Long memoryId) {
        ActorMemory m = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new BizException(404, "记忆不存在"));
        if (m.getProjectId() == null) {
            throw new BizException(404, "记忆不存在或无权访问");
        }
        requireProject(m.getProjectId(), currentUserProvider.currentUserId());
        m.setDeleted(1);
        memoryRepository.save(m);
    }

    // ==================== 私有辅助（B4 检索/沉淀） ====================

    /**
     * 加载某角色 ref 的全部记忆（去重/检索/淘汰共用）：
     * ① 按 character_ref 查询（B4 新写双轨统一走 ref）；② ref 能解析出 actor_profile.id 时额外并入
     * 仅 character_id 的存量旧记忆。
     */
    private List<ActorMemory> loadMemories(Long projectId, String characterRef) {
        List<ActorMemory> out = new ArrayList<>(memoryRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(projectId, characterRef, 0));
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long charId = NpcRefs.idOf(characterRef);
        if (charId != null) {
            List<ActorMemory> legacy = memoryRepository
                    .findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                            projectId, charId, List.of(ActorMemoryTypes.TYPE_SEMANTIC, ActorMemoryTypes.TYPE_EPISODIC), 0);
            Set<Long> seen = new HashSet<>();
            for (ActorMemory m : out) {
                if (m.getId() != null) {
                    seen.add(m.getId());
                }
            }
            for (ActorMemory m : legacy) {
                if (m.getId() == null || !seen.contains(m.getId())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * 超预算滚动淘汰（character_ref 版，B4）：按「重要度低+旧」软删超预算部分（复用 MemoryService.evictToBudget）。
     */
    private void evictOverBudget(Long projectId, String characterRef) {
        List<ActorMemory> all = loadMemories(projectId, characterRef);
        softDelete(evictToBudget(all, budget));
    }

    /**
     * 超预算滚动淘汰（character_id 版， 收编）：角色级与项目级分别按预算滚动软删低价值记忆。
     */
    private void evictOverBudget(Long projectId, Long characterId) {
        List<ActorMemory> charMem = memoryRepository
                .findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(projectId, characterId, 0);
        softDelete(evictToBudget(charMem, budget));
        List<ActorMemory> projMem = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        softDelete(evictToBudget(projMem, budget));
    }

    /**
     * 软删记忆列表（管理删除语义一致，不物理删除保留审计）。
     * <p>预算淘汰归档钩子：{@code holzyn.actor.memory.archive.evict-enabled=true} 时，
     * 本次被淘汰（预算滚动）的记忆在软删前先写归档（reason=evicted）；默认关——淘汰仍走软删，行为不变。</p>
     */
    private void softDelete(List<ActorMemory> toDelete) {
        if (toDelete == null || toDelete.isEmpty()) {
            return;
        }
        if (archiveService != null) {
            try {
                archiveService.archiveEvicted(toDelete);
            } catch (Exception e) {
                log.warn("[记忆淘汰] 淘汰归档失败（不阻断软删）: {}", e.getMessage());
            }
        }
        for (ActorMemory m : toDelete) {
            m.setDeleted(1);
            memoryRepository.save(m);
        }
    }

    /**
     * 检索命中：更新 last_access_at/access_count（recency 衰减依据）。
     */
    private void touch(List<ActorMemory> memories, LocalDateTime now) {
        for (ActorMemory m : memories) {
            m.setLastAccessAt(now);
            m.setAccessCount(m.getAccessCount() == null ? 1 : m.getAccessCount() + 1);
            memoryRepository.save(m);
        }
    }

    /**
     * npc:{id} 不再编码类型——「是否普通型」决定记忆写/读走 character_ref 还是 character_id，
     * 此处按意图 B 处理（普通型=仅 character_ref 双轨），改查 {@code actor_profile.type}。
     * <p>解析器未装配（纯单测直构无 Spring）时按特殊型处理——与 {@link NpcTypeResolver} 解析失败
     * 回退 {@code special} 同口径，保守走 character_id 路径不误判普通型。</p>
     */
    private boolean isCommonNpc(Long projectId, String characterRef) {
        return npcTypeResolver != null && npcTypeResolver.isCommon(projectId, characterRef);
    }

    /**
     * 重要度钳制到 1~5（null 回退 1）。
     */
    private int clampImportance(Integer importance) {
        if (importance == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, importance));
    }

    /** 检索评分记录（记忆 + 得分） */
    private record Scored(ActorMemory memory, double score) {
    }

    // ==================== 私有辅助（收编 MemoryService 对话侧） ====================

    /**
     * 角色级记忆注入段（【长期记忆】； R3：staleness 标记的记忆渲染带「（可能过时）」提示）。
     */
    private void appendCharSection(StringBuilder sb, List<ActorMemory> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return;
        }
        sb.append("【长期记忆】以下是该角色过往经历中值得记住的事实，回答时自然引用，不得遗忘或前后矛盾：\n");
        for (ActorMemory m : ranked) {
            String stale = m.isStale() ? "（可能过时，需以最新情况为准）" : "";
            sb.append("- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append(stale).append("\n");
        }
    }

    /**
     * 项目级记忆注入段（【世界大事记】，所有角色可见）。
     */
    private void appendProjectSection(StringBuilder sb, Long projectId, int topK) {
        List<ActorMemory> projMemories = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        List<ActorMemory> projRanked = rankForInjection(projMemories, topK);
        if (projRanked.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append("【世界大事记】以下是本项目近期发生的重大事件，所有角色都知晓：\n");
        for (ActorMemory m : projRanked) {
            sb.append("- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append("\n");
        }
    }

    /**
     * 解析记忆归属角色：群聊取最近一条 assistant 消息的归属角色，否则取会话首个成员角色。
     */
    private Long resolveMemoryCharacter(ActorConversation conv, List<ActorMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if ("assistant".equals(m.getRole()) && m.getCharacterId() != null) {
                return m.getCharacterId();
            }
        }
        return memberRepository.findByConversationId(conv.getId()).stream()
                .findFirst().map(ActorConversationMember::getCharacterId).orElse(null);
    }

    /**
     * 解析记忆归属成员的统一角色引用。
     */
    private String resolveMemoryCharacterRef(ActorConversation conv, List<ActorMessage> history) {
        Long memberId = resolveMemoryCharacter(conv, history);
        if (memberId == null) {
            return null;
        }
        // ：npc:{id} 不再编码类型（原按 member_type 选 crowd:/special: 前缀的分支已无意义），
        // 统一身份与类型解耦后引用拼装只看 id——原 member_type 读取成为死代码，一并移除
        return NpcRefs.of(memberId);
    }

    /**
     * 抽取去重用已有记忆清单（B5）：普通型 NPC 按 character_ref 查（类型查 actor_profile.type），
     * 否则按 character_id。
     */
    private List<ActorMemory> loadExistingForExtract(Long projectId, Long characterId, String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「是否普通型」改查 actor_profile.type
        if (isCommonNpc(projectId, characterRef)) {
            return memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                    projectId, characterRef, 0).stream()
                    .filter(m -> ActorMemoryTypes.TYPE_SEMANTIC.equals(m.getType())
                            || ActorMemoryTypes.TYPE_EPISODIC.equals(m.getType()))
                    .toList();
        }
        return memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                projectId, characterId, List.of(ActorMemoryTypes.TYPE_SEMANTIC, ActorMemoryTypes.TYPE_EPISODIC), 0);
    }

    /**
     * 写入抽取记忆：普通型 NPC 写 character_ref（无 character_id，类型查
     * actor_profile.type），否则写 character_id。
     */
    private void saveMemoryFor(Long projectId, Long characterId, String characterRef,
                               String type, String content, Integer importance, String contextJson) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「是否普通型」改查 actor_profile.type
        if (isCommonNpc(projectId, characterRef)) {
            saveMemoryByRef(projectId, characterRef, type, content, importance, contextJson);
            return;
        }
        saveMemory(projectId, characterId, type, content, importance, contextJson);
    }

    /**
     * 写入一条记忆。
     */
    private void saveMemory(Long projectId, Long characterId, String type, String content,
                            Integer importance, String contextJson) {
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        m.setCharacterId(characterId);
        m.setType(type);
        m.setContent(content);
        m.setImportance(importance == null ? 1 : importance);
        m.setContextJson(contextJson);
        memoryRepository.save(m);
    }

    /**
     * 写入一条 character_ref 记忆（普通型 NPC 对话抽取记忆归属 {@code npc:{id}}，双轨无 character_id）。
     */
    private void saveMemoryByRef(Long projectId, String characterRef, String type, String content,
                                 Integer importance, String contextJson) {
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        m.setCharacterId(null);
        m.setCharacterRef(characterRef);
        m.setType(type);
        m.setContent(content);
        m.setImportance(importance == null ? 1 : importance);
        m.setContextJson(contextJson);
        memoryRepository.save(m);
    }

    // ==================== 记忆系统重构：双通道路由 + 按需复核 + 操作判定（版本链） ====================

    /**
     * 双通道路由（设计 §4.2）：抽取结果中 target=world/both 的条目 → 项目记忆
     * （WorldMemoryService.routeFromExtraction：事件类→编年史 / 状态类→实体状态表）。
     * 失败静默不阻断角色侧抽取。
     */
    private void routeWorldCandidates(Long userId, Long projectId,
                                      List<MemoryExtractParse.ExtractedMemory> extracted) {
        if (worldMemoryService == null || extracted == null || extracted.isEmpty()) {
            return;
        }
        List<com.holzyn.actor.domain.world.service.WorldMemoryService.RouteCandidate> world = new ArrayList<>();
        for (MemoryExtractParse.ExtractedMemory em : extracted) {
            if (em.isWorldTargeted() && em.content() != null && !em.content().isBlank()) {
                world.add(new com.holzyn.actor.domain.world.service.WorldMemoryService.RouteCandidate(
                        em.kind(), em.content(), em.importance()));
            }
        }
        if (world.isEmpty()) {
            return;
        }
        try {
            Long gameDay = null;
            if (worldClockService != null && projectId != null) {
                try {
                    Long sec = worldClockService.currentEngineGameSecond(projectId);
                    if (sec != null) {
                        gameDay = Math.floorDiv(sec, 86400L);
                    }
                } catch (Exception ignored) {
                    // 时钟不可用则 gameDay 传 null，由项目记忆服务回退解析
                }
            }
            worldMemoryService.routeFromExtraction(userId, projectId, gameDay, world);
        } catch (Exception e) {
            log.warn("[记忆抽取] 世界信息路由失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 按需 AI 复核（设计 §3.2 三重机制③）：候选=重要度≥review-threshold 或带 update/invalidate 操作；
     * 合并为一次批量 AI 调用（memory_extract_review，返回 charItems 下标 → 判定映射）；
     * 无候选/开关关/调用失败 → 返回空 Map（调用方按初判执行，静默回退不阻断）。
     */
    private Map<Integer, MemoryExtractReviewParse.ReviewDecision> reviewCandidates(
            Long userId, Long projectId, Long characterId, String characterRef,
            List<MemoryExtractParse.ExtractedMemory> charItems, List<String> existing) {
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> out = new java.util.HashMap<>();
        if (!extractReviewEnabled || charItems == null || charItems.isEmpty()) {
            return out;
        }
        List<Integer> candidateIdx = new ArrayList<>();
        for (int i = 0; i < charItems.size(); i++) {
            MemoryExtractParse.ExtractedMemory em = charItems.get(i);
            boolean needsReview = em.importance() != null && em.importance() >= extractReviewThreshold
                    || !MemoryExtractParse.OPERATION_ADD.equals(em.operation());
            if (needsReview) {
                candidateIdx.add(i);
            }
        }
        if (candidateIdx.isEmpty()) {
            return out;
        }
        try {
            StringBuilder cand = new StringBuilder();
            for (int idx : candidateIdx) {
                MemoryExtractParse.ExtractedMemory em = charItems.get(idx);
                cand.append(idx).append(". [操作=").append(em.operation())
                        .append(" 重要度=").append(em.importance())
                        .append("] ").append(em.content()).append("\n");
            }
            String existingText = existing == null || existing.isEmpty() ? "（暂无）"
                    : "- " + String.join("\n- ", existing);
            String prompt = promptTemplateService.render(userId, projectId,
                    PromptTemplateService.CODE_MEMORY_EXTRACT_REVIEW,
                    Map.of("review_candidates", cand.toString().trim(),
                            "existing_memories", existingText));
            // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：按需复核属「角色视角」记忆操作——
            // AI 以该角色视角判断候选记忆是否值得长期记住/重要度/操作，前置段 1A 世界观 +
            // 段 1C 角色核心卡 + 段 2D 当前世界动态 + 段 2E 当前状态（零阻断）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.memoryChainContext(projectId, characterRef, currentGameSecond(projectId));
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            AiChatResult result = AiCallContext.run("memory", characterId, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    EXTRACT_TEMPERATURE, EXTRACT_MAX_TOKENS, true)));
            usageLogService.record(userId, projectId, characterId, result.providerId(), result.model(), "memory",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return MemoryExtractReviewParse.parseReview(result.content());
        } catch (Exception e) {
            log.warn("[记忆抽取] 按需复核调用失败，按初判执行（静默回退）: {}", e.getMessage());
            return out;
        }
    }

    /**
     * 抽取条目类型映射（kind → L2 type）：episodic→episodic；其余（fact/event/entity 等）→semantic。
     */
    private static String typeOf(String kind) {
        return MemoryExtractParse.KIND_EPISODIC.equals(kind)
                ? ActorMemoryTypes.TYPE_EPISODIC : ActorMemoryTypes.TYPE_SEMANTIC;
    }

    /**
     * 旧条匹配（update/invalidate 的目标定位）：与既有 L2 记忆做 Dice 重叠，
     * 取最高分且 ≥ supersede-match-threshold 的条目；无匹配返回 null。
     */
    private ActorMemory findSupersedeTarget(String content, List<ActorMemory> existingMemories) {
        if (content == null || existingMemories == null || existingMemories.isEmpty()) {
            return null;
        }
        ActorMemory best = null;
        double bestScore = supersedeMatchThreshold;
        for (ActorMemory m : existingMemories) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            double score = MemoryDedup.overlapScore(content, m.getContent());
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /**
     * invalidate 落库：旧条软删（deleted=1）+ 归档（reason=superseded，原文保留可追溯），不写新条。
     */
    private void invalidateMemory(ActorMemory target) {
        target.setDeleted(1);
        target.setUpdatedAt(LocalDateTime.now());
        memoryRepository.save(target);
        if (archiveService != null) {
            try {
                archiveService.archiveL2Memory(target, "superseded");
            } catch (Exception e) {
                log.warn("[记忆抽取] invalidate 归档失败（隔离）: memory={} : {}", target.getId(), e.getMessage());
            }
        }
    }

    /**
     * update 落库：写新条（supersedes_id=旧条 id、version=旧条.version+1，版本链）
     * + 旧条软删归档（reason=superseded）。
     */
    private void saveMemorySuperseding(Long projectId, Long characterId, String characterRef,
                                       String type, String content, Integer importance, ActorMemory target) {
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「是否普通型」改查 actor_profile.type
        if (isCommonNpc(projectId, characterRef)) {
            m.setCharacterId(null);
            m.setCharacterRef(characterRef);
        } else {
            m.setCharacterId(characterId);
            if (characterRef != null) {
                m.setCharacterRef(characterRef);
            }
        }
        m.setType(type);
        m.setContent(content);
        m.setImportance(importance == null ? 1 : importance);
        m.setSupersedesId(target.getId());
        m.setVersion((target.getVersion() == null ? 1 : target.getVersion()) + 1);
        m.setSource(ActorMemoryTypes.SOURCE_DIALOG);
        memoryRepository.save(m);
        target.setDeleted(1);
        target.setUpdatedAt(LocalDateTime.now());
        memoryRepository.save(target);
        if (archiveService != null) {
            try {
                archiveService.archiveL2Memory(target, "superseded");
            } catch (Exception e) {
                log.warn("[记忆抽取] update 旧条归档失败（隔离）: memory={} : {}", target.getId(), e.getMessage());
            }
        }
    }

    /**
     * 取最近消息子列表。
     */
    private List<ActorMessage> recentMessages(List<ActorMessage> history) {
        int from = Math.max(0, history.size() - RECENT_DIALOG_LIMIT);
        return new ArrayList<>(history.subList(from, history.size()));
    }

    /**
     * 组装对话文本（抽取/摘要输入）：user 标「玩家：」，assistant 标角色名，事件标「【世界事件】」。
     */
    private String buildDialogText(List<ActorMessage> recent) {
        StringBuilder sb = new StringBuilder();
        for (ActorMessage m : recent) {
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("event".equals(m.getType())) {
                sb.append("【世界事件】").append(content).append("\n");
            } else if ("user".equals(m.getRole())) {
                sb.append("玩家：").append(content).append("\n");
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                sb.append(characterName(m.getCharacterId())).append("：").append(content).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 角色姓名解析（用于对话文本标注；缺失返回「角色」）。
     */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return "角色";
        }
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse("角色");
    }

    /**
     * AI 抽取新事实：渲染 memory_extract 模板 → json_object 调用 → 解析为事实数组。失败返回空列表。
     */
    private List<MemoryExtractParse.ExtractedMemory> extractFacts(Long userId, Long projectId,
                                                                  String characterRef, String dialogText,
                                                                  List<String> existing) {
        try {
            String existingText = existing.isEmpty() ? "（暂无）"
                    : "- " + String.join("\n- ", existing);
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_MEMORY_EXTRACT,
                    Map.of("recent_dialog", dialogText, "existing_memories", existingText));
            // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：记忆抽取属「角色视角」操作——AI 以
            // 该角色视角判断「什么值得记」，前置段 1A 世界观 + 段 1C 角色核心卡 + 段 2D 当前
            // 世界动态 + 段 2E 当前状态（未装配/组装失败零阻断，不影响原调用）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.memoryChainContext(projectId, characterRef, currentGameSecond(projectId));
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            AiChatResult result = AiCallContext.run("memory", null, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    EXTRACT_TEMPERATURE, EXTRACT_MAX_TOKENS, true)));
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "memory",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return MemoryExtractParse.parseExtract(result.content());
        } catch (Exception e) {
            log.warn("[记忆抽取] AI 调用失败，跳过本轮抽取: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * AI 生成会话摘要：渲染 memory_summarize 模板 → json_object 调用 → 解析摘要文本。
     */
    private String summarizeDialog(Long userId, Long projectId, String characterRef, String dialogText) {
        try {
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_MEMORY_SUMMARIZE,
                    Map.of("recent_dialog", dialogText));
            // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：会话摘要属「角色视角」操作——AI 以
            // 该角色视角归纳「这段时间发生了什么」，前置段 1A 世界观 + 段 1C 角色核心卡 +
            // 段 2D 当前世界动态 + 段 2E 当前状态（未装配/组装失败零阻断，不影响原调用）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.memoryChainContext(projectId, characterRef, currentGameSecond(projectId));
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            // 调用方已不再显式传 characterId——从统一引用解析（special 型无 id=null，不影响调用/记账）
            Long characterId = NpcRefs.idOf(characterRef);
            AiChatResult result = AiCallContext.run("memory", characterId, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    EXTRACT_TEMPERATURE, EXTRACT_MAX_TOKENS, true)));
            usageLogService.record(userId, projectId, characterId, result.providerId(), result.model(), "memory",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return MemoryExtractParse.parseSummarize(result.content());
        } catch (Exception e) {
            log.warn("[记忆摘要] AI 调用失败，跳过本轮摘要: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 复核（可选，默认关闭）：让 AI 判断最近对话是否有值得长期记住的事实。失败回退为允许提取。
     */
    private boolean aiReviewHasSubstance(ActorConversation conv, List<ActorMessage> history, Long userId) {
        try {
            String dialogText = buildDialogText(recentMessages(history));
            if (dialogText.isBlank()) {
                return false;
            }
            String prompt = "请判断下面这段玩家与 NPC 的对话中，是否存在值得长期记住的信息"
                    + "（新事实、约定、关键事件、玩家偏好等）。若只是寒暄/闲聊/无实质信息，输出 false。\n\n"
                    + "只输出 JSON，格式：{\"has_memorable\": true 或 false}\n\n"
                    + "对话：\n" + dialogText;
            AiChatResult result = AiCallContext.run("memory", null, () ->
                    aiChatGateway.chatCompletion(userId, conv.getProjectId(), null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", prompt)),
                                    EXTRACT_TEMPERATURE, 32, true)));
            String content = result.content() == null ? "" : result.content();
            return content.contains("\"has_memorable\"") && (content.contains("true")
                    || content.contains("True") || content.contains("TRUE"));
        } catch (Exception e) {
            log.warn("[记忆AI复核] 调用失败，回退为允许提取: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     */
    private void requireProject(Long projectId, Long userId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 校验角色属于该项目（越权抛 404）。
     */
    private void requireCharacterInProject(Long characterId, Long projectId) {
        characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
    }

    /**
     * 当前引擎游戏时刻（秒，可空——记忆链上下文注入用；时钟未装配/异常返回 null）。
     */
    private Long currentGameSecond(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        try {
            return worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统计某角色事实记忆条数（补抽返回用，type=semantic）。
     */
    private int countFacts(Long projectId, Long characterId) {
        return memoryRepository
                .findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                        projectId, characterId, ActorMemoryTypes.TYPE_SEMANTIC, 0)
                .size();
    }

    // ==================== 收编 MemoryService 静态工具（纯逻辑，原样迁入；旧 MemoryService 已删） ====================

    /** 常见寒暄/客套词（用于启发式门控；命中且短消息 → 跳过提取；原驻留 MemoryService） */
    static final List<String> GREETING_WORDS = List.of(
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "hey", "在吗", "在不在",
            "早上好", "中午好", "下午好", "晚上好", "晚安", "早安",
            "嗯", "嗯嗯", "哦", "哦哦", "啊", "哈哈", "呵呵", "嘿嘿",
            "谢谢", "多谢", "不客气", "再见", "拜拜", "好的", "好", "行", "可以",
            "没事", "没什么");

    /**
     * 按「重要度 desc + 较新优先」排序取 top-K（注入排序）。
     *
     * @param all 记忆列表（可为空）
     *
     * @param topK 取前 N 条（<=0 返回空）
     *
     * @return 排序后的前 topK 条
     */
    public static List<ActorMemory> rankForInjection(List<ActorMemory> all, int topK) {
        if (all == null || all.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<ActorMemory> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance()).reversed()
                .thenComparing(Comparator.comparing((ActorMemory m) -> m.getCreatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()));
        return sorted.size() <= topK ? sorted : new ArrayList<>(sorted.subList(0, topK));
    }

    /**
     * 超预算滚动淘汰：返回应软删的低价值记忆列表（保留重要度高、较新的）。
     *
     * @param all 记忆列表（可为空）
     *
     * @param budget 预算上限（<=0 视为不淘汰）
     *
     * @return 应删除的记忆列表（空=无需淘汰）
     */
    public static List<ActorMemory> evictToBudget(List<ActorMemory> all, int budget) {
        if (all == null || all.size() <= budget) {
            return List.of();
        }
        List<ActorMemory> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance())
                .thenComparing(m -> m.getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ArrayList<>(sorted.subList(0, sorted.size() - budget));
    }

    /**
     * 文本重叠度（Dice 二元组系数 0~1）：用于服务端二次去重与检索 relevance 降级分。
     * <p>起委托共享工具 {@link MemoryDedup#overlapScore}（L1 巩固提升同源，防算法漂移）。</p>
     *
     * @param a 文本 a
     *
     * @param b 文本 b
     *
     * @return 重叠度（空文本返回 0）
     */
    public static double overlapScore(String a, String b) {
        return MemoryDedup.overlapScore(a, b);
    }

    /**
     * 新事实与已有记忆是否重复：任一条已有记忆达到阈值即判定重复。
     * <p>去重策略：① 整条文本重叠（Dice 二元组）；② 子句命中率——新事实按标点切分子句，
     * 若某子句的核心二元组大部分（≥70% 且至少 3 个）已出现在某条已有记忆中，判重。
     * 起委托共享工具 {@link MemoryDedup#isDuplicate}（L1 巩固提升同源）。</p>
     *
     * @param newContent 新事实内容
     *
     * @param existing 已有记忆内容列表
     *
     * @param threshold 整条重叠阈值（0~1）
     *
     * @return true 表示重复应丢弃
     */
    public static boolean isDuplicate(String newContent, List<String> existing, double threshold) {
        return MemoryDedup.isDuplicate(newContent, existing, threshold);
    }

    /**
     * 将文本切分为子句（按中文/英文标点：。，！？；英文句号逗号分号）。
     * <p>起委托共享工具 {@link MemoryDedup#splitClauses}。</p>
     *
     * @param text 文本
     *
     * @return 子句列表（空文本返回空列表）
     */
    public static List<String> splitClauses(String text) {
        return MemoryDedup.splitClauses(text);
    }

    /**
     * 是否到达会话摘要生成点：已完成 assistant 消息数达到间隔的整数倍（每 N 轮一次）。
     *
     * @param completedAssistantCount 会话内已完成（done）的 assistant 消息数
     *
     * @param interval 摘要间隔（每 N 轮）
     *
     * @return true 表示本轮应生成摘要
     */
    public static boolean shouldGenerateSummary(long completedAssistantCount, int interval) {
        return interval > 0 && completedAssistantCount >= interval
                && completedAssistantCount % interval == 0;
    }

    /**
     * 判定一段文本是否为「寒暄/客套/无实质信息」。
     *
     * @param text 待判定文本（通常为最新一条用户消息）
     *
     * @param maxLen 寒暄最大字符数（单聊 20 / 群聊 8，群聊更宽松）
     *
     * @return true=寒暄应跳过提取
     */
    public static boolean isGreetingText(String text, int maxLen) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.length() <= 2) {
            return true;
        }
        return t.length() <= maxLen && containsGreetingWord(t);
    }

    /**
     * 文本是否含寒暄/客套词。
     *
     * @param text 文本（可空）
     *
     * @return true=含寒暄词
     */
    public static boolean containsGreetingWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String w : GREETING_WORDS) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐轮门控：最近一轮（最新 user + 最新已完成 assistant）是否有实质内容。
     *
     * @param history 会话全部消息（升序）
     *
     * @param group 是否群聊（群聊阈值更宽松）
     *
     * @param greetingMaxLen 单聊寒暄最大字符数
     *
     * @param groupGreetingMaxLen 群聊寒暄最大字符数
     *
     * @return true=最近一轮有实质内容应提取
     */
    public static boolean lastRoundHasSubstance(List<ActorMessage> history, boolean group,
                                                int greetingMaxLen, int groupGreetingMaxLen) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if ("user".equals(m.getRole())) {
                int maxLen = group ? groupGreetingMaxLen : greetingMaxLen;
                return !isGreetingText(m.getContent(), maxLen);
            }
        }
        return false;
    }

    /**
     * 会话结束兜底门控：最近窗口内「任一」用户消息有实质内容即判定整段有价值。
     *
     * @param history 会话全部消息（升序）
     *
     * @param group 是否群聊
     *
     * @param greetingMaxLen 单聊寒暄最大字符数
     *
     * @param groupGreetingMaxLen 群聊寒暄最大字符数
     *
     * @return true=窗口内存在实质内容
     */
    public static boolean segmentHasSubstance(List<ActorMessage> history, boolean group,
                                              int greetingMaxLen, int groupGreetingMaxLen) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int maxLen = group ? groupGreetingMaxLen : greetingMaxLen;
        for (ActorMessage m : history) {
            if ("user".equals(m.getRole()) && !isGreetingText(m.getContent(), maxLen)) {
                return true;
            }
        }
        return false;
    }
}
