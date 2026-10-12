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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.learning.LearningService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.thinking.DecisionService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.character.service.CharacterService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体运行时（阶段 C，domain/agent/runtime）——六组件<b>唯一编排入口（facade）</b>。
 * <p>定位：对外提供统一触发方法，对内按脑力配置分派
 * 组件管线（感知→记忆→思考→行动→学习）。 交付骨架 + 组件桩； 五步桩改委托新组件；
 * <b>统一接线</b>：WorldEngine（引擎逐时/跨日）、ActionController（手动）、WorldEventService
 * （事件后自动评估）、RoleplayService（对话完成后 after_dialog 自动评估）全部经本门面；
 * 旧服务（ActionEngine/ChatService/GroupChatService/WorldEvolutionService/MemoryService）物理删除。</p>
 * <p>管线五步：</p>
 * <ol>
 * <li>① 感知：真实调用 {@link PerceptionService#buildPerception}（B2 已落地，不变）；</li>
 * <li>② 记忆：真实调用 {@link AgentMemoryService#retrieve}（B4 已落地，不变）；</li>
 * <li>③ 思考：<b>委托 {@link DecisionService#generateDecision}</b>（thinking 组件，
 * brainProfile 门控 programmatic/full/light；预算 tryConsume 门控，用尽/纯程序化档走
 * {@link DecisionService#generateProgrammaticDecision} 程序化兜底不冻结，修）；</li>
 * <li>④ 行动：<b>委托 {@link AgentActionService#execute}</b>（行动组件执行决策计划——
 * 改状态/写日志/SSE/发布事件/沉记忆）；计划经 {@link ActorActionPlanRepository} 持久化；</li>
 * <li>⑤ 学习：<b>委托 {@link LearningService#scheduleConsolidation}</b>（骨架，实现留 D 阶段）。</li>
 * </ol>
 * <p><b>行动复合入口</b>：{@link #triggerAction}（等同旧 ActionEngine.trigger——归属校验/情境组装/
 * 思考决策/计划持久化[支持 plannedTime 定时]/执行）供 ActionController 手动触发与
 * {@link #evaluateMembers} 自动评估（对话/事件后，经 {@link #evaluateAfterDialogAsync}/
 * {@link #triggerForEventAsync} 异步触发，迁移自旧 ActionEngine）。</p>
 * <p>骨架机制：<b>预算</b>（复用 {@link BudgetGuard}，外层已开 tick 复用不重开——按角色并发后
 * 开窗走原子入口 {@link BudgetGuard#beginTickIfAbsent}）、
 * <b>防重入</b>、
 * <b>时间口径</b>（一律游戏时间：DecisionContext.gameSecond 显式传入或 null=读世界时钟，禁止真实时间）、
 * <b>trace</b>（AiCallContext.withTrace 业务动作级 trace 贯穿，各环节 recordStep 入决策链路）。</p>
 * <p><b>接线边界</b>：对话/群聊流式回复由 RoleplayService 组件直连（ConversationController 调用，
 * 不包进门面——门面不做 SSE IO）；对话完成后 after_dialog 自动评估经本门面（RoleplayService 注入本门面，
 * 单向无循环：本门面不依赖 RoleplayService）。开关：{@code holzyn.actor.agent.enabled}（默认 true）/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {

    /** 记忆检索 top-K（管线默认，后续按脑力配置/配置化） */
    private static final int DEFAULT_MEMORY_TOP_K = 8;

    /** 行动决策 JSON 序列化失败兜底文本 */
    private static final String DECISION_JSON_FALLBACK = "{\"type\":\"custom\",\"action\":\"日常活动\",\"target\":\"驻地附近\",\"reason\":\"序列化失败\",\"urgency\":3}";

    private final PerceptionService perceptionService;
    private final AgentMemoryService agentMemoryService;
    private final DecisionService decisionService;
    private final AgentActionService agentActionService;
    private final LearningService learningService;
    private final BudgetGuard budgetGuard;
    private final BrainProfileService brainProfileService;
    private final ActorCharacterRepository characterRepository;
    private final CharacterService characterService;
    private final ActorActionPlanRepository planRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码类型——「逐 NPC AI 决策/日计划补生成」等
     * 只有特殊型才做的行为必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /** 决策循环总开关（默认 true；false=触发直接跳过不执行管线） */
    @Value("${holzyn.actor.agent.enabled:true}")
    private boolean agentEnabled;

    /** 每决策循环 AI 预算缺省值（ai_budget_per_tick 缺省取此，默认 5，与 WorldEngine max-ai-calls 同源） */
    @Value("${holzyn.actor.agent.budget-per-tick:5}")
    private int defaultBudgetPerTick;

    /** 对话/事件后自动评估行动总开关（迁移自旧 ActionEngine；false 仅手动 + scheduled 触发） */
    @Value("${holzyn.actor.action.auto-evaluate:true}")
    private boolean autoEvaluate;

    /** 规划组件（注入：决策循环惰性补生成日计划；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.thinking.PlanningService planningService;

    /**
     * 打断与恢复服务（实时行为决策系统：STIMULUS 即时决策响应行动执行完后同流同预算执行
     * maybeResume——AI 恢复决策三态[resume/adjust/abandon]；字段注入 + @Lazy 破 Bean 环
     * [经 AgentActionService→沙盘→实时决策→本服务]，null=未装配跳过恢复）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.InterruptRecoveryService interruptRecoveryService;

    /**
     * 等待类计划服务（实时行为决策系统 时间感行动：AI 决策 wait_minutes → 等待类计划
     * [planned_game_second 游戏时刻排期，到点重新决策]；null=未装配忽略等待立即执行）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.WaitPlanService waitPlanService;

    /**
     * 空闲降频服务（实时行为决策系统 空闲降频：决策循环完成时记录决策时刻，
     * WorldEngine 据此判定「有进行中行动 → 本小时周期决策跳过」；null=未装配不记录，
     * 行为回到 每小时全量决策）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.IdleFrequencyService idleFrequencyService;

    /**
     * AI 强度闸门（微层接线， 决策生成按项目档位生效——
     * programmatic=强制程序化决策[generateProgrammaticDecision，零 AI] / light=预算减半
     * [WorldEngine 侧折算] / full=标准 / max=预算耗尽也放行 AI[全 AI 必调]；
     * 字段注入保持构造器不变）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /**
     * 行为多样性注入器（：AI 决策路径注入【状态涟漪】段——OCEAN 偏好波动/情绪干扰/
     * 社会参照，同一情境不同角色行动有差异；字段注入保持构造器不变，null=未装配跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.BehaviorDiversityService behaviorDiversityService;

    /**
     * 冲突协调服务（决策执行前冲突检测/资源登记/AI 协商[scene=negotiate，
     * 事务外路径，失败回退确定性等待]；后决策回写计划；null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.ActionCoordinatorService actionCoordinatorService;

    /**
     * 生命周期配置（dispatch.all-npc-hourly 决定普通 NPC 是否逐人走引擎决策；
     * null=未装配[单测]按 行为[仅特殊型]）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.config.GrowthProperties growthProperties;

    /**
     * 此刻可发生空间（情境组装前空间保障——感知注入的空间段为最新生成结果；
     * null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.GrowthSpaceService growthSpaceService;

    /** 世界时钟服务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /**
     * 决策循环防重入。
     * <p>原实现是**一个** {@link AtomicBoolean} 做"项目级/全局"单锁——引擎 tick 在跑 20 个角色的
     * 决策（每轮约 30 秒）时，任何刺激驱动的实时决策都会被拦下并重试，重试 20 次后**连刺激一起丢弃**。
     * 实测世界运行 40 分钟内 73 次拦截、2 个 NPC 的刺激彻底丢失——表现就是"有人在你面前出事，
     * 你却完全没反应"，与"像真人"的目标直接冲突。</p>
     * <p>现在改为按 {@code 项目|角色} 粒度：<b>同一个 NPC 仍然不会重复决策</b>（原始意图保留），
     * 但不同 NPC 可以并发决策——引擎自身的顺序循环行为不变，收益是"刺激决策能在 tick 期间插进去"。</p>
     */
    private final java.util.Set<String> runningRefs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 防重入粒度开关（true=按角色并发[默认]；false=沿用历史的项目级单锁全局串行，便于快速回退） */
    @Value("${holzyn.actor.agent.reentrancy-per-character:true}")
    private boolean reentrancyPerCharacter = true;

    /** 历史项目级单锁（仅在 {@link #reentrancyPerCharacter}=false 时使用） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * tick 屏障（阶段⑤：窄屏障成员——本决策链会写 {@code actor_state}/行动结果，
     * 因此入屏障；tick 未排空不推进下一 tick）。null=未装配（单测/独立使用）→ 退化为无屏障。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TickBarrier tickBarrier;

    // ==================== R1 引擎 AI 异步化/前瞻缓冲：异步批次线程标志 ====================
    // WorldEngine 的预热/边界补发批次在池线程上异步执行本决策链。两个语义与普通触发不同：
    // ① 不进 tick 屏障——批次任务跨多个引擎 tick/扫描运行，若登记在途会让每个 tick 的
    // awaitQuiescence 白等，把「小时边界停顿」变成「小时末段全程变慢」；
    // R1 （第 5 问）：边界到达时在途任务跳过不等待、跑完照常落库；
    // ② 预算走独立批次命名空间（BudgetGuard.beginBatch/tryConsumeBatch）——引擎 tick 窗口随
    // 推进结束关闭，批次任务跨窗口消费会饿死（见 BudgetGuard 批次命名空间注释）。
    // 两者语义天然绑定（「本线程正在引擎异步批次内执行」），故合并为一个线程标志。
    private static final ThreadLocal<Boolean> BATCH_DISPATCH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 标记当前线程进入引擎异步决策批次（WorldEngine 预热/补发任务包装调用）。 */
    public static void markBatchDispatch() {
        BATCH_DISPATCH.set(Boolean.TRUE);
    }

    /** 清除当前线程的引擎异步决策批次标志（任务 finally 调用，防线程池线程污染）。 */
    public static void clearBatchDispatch() {
        BATCH_DISPATCH.remove();
    }

    /** 当前线程是否在引擎异步决策批次内（true=不进 tick 屏障、预算走批次命名空间）。 */
    public static boolean inBatchDispatch() {
        return Boolean.TRUE.equals(BATCH_DISPATCH.get());
    }

    /** 异步自动评估执行器（虚拟线程，不阻塞对话/事件主流程；迁移自旧 ActionEngine） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 在途异步评估任务计数（供测试 awaitIdle 排空断言） */
    private final AtomicInteger inFlight = new AtomicInteger();

    // ==================== 统一触发入口 ====================

    /**
     * 统一触发入口（接线：WorldEngine 引擎 tick / ActionController 手动 / 未来 ENGINE_API）。
     * <p>流程：总开关 → 防重入 → 脑力配置解析（权威配置源）→ 预算窗口（复用 BudgetGuard）→
     * AiCallContext.withTrace 包裹决策循环管线（感知→记忆→思考→行动→学习）。</p>
     *
     * @param trigger 触发源（AgentTrigger 六枚举）
     *
     * @param characterRef 统一角色引用（npc:{id}）
     *
     * @param ctx 决策上下文（可空；感知/记忆/思考/行动各步消费）
     *
     * @return 决策循环结果（防重入时 reentrant=true）
     */
    public AgentResult trigger(AgentTrigger trigger, String characterRef, DecisionContext ctx) {
        if (!agentEnabled) {
            log.info("[AgentRuntime] 决策循环总开关关闭（holzyn.actor.agent.enabled=false），跳过: trigger={} ref={}",
                    trigger, characterRef);
            return AgentResult.success(trigger, characterRef, null, List.of(), "决策循环总开关关闭，跳过");
        }
        Long projectId = ctx == null ? null : ctx.projectId();
        // ：按「项目|角色」防重入（同角色不重入；不同角色可并发，刺激决策不再被 tick 挤掉）
        String lockKey = (projectId == null ? "-" : projectId) + "|"
                + (characterRef == null ? "-" : characterRef);
        if (reentrancyPerCharacter) {
            if (!runningRefs.add(lockKey)) {
                log.warn("[AgentRuntime] 决策循环防重入（同一角色正在决策）：本次触发直接返回不阻塞: trigger={} ref={}",
                        trigger, characterRef);
                return AgentResult.reentrant(trigger, characterRef);
            }
        } else if (!running.compareAndSet(false, true)) {
            log.warn("[AgentRuntime] 决策循环防重入（项目级单锁模式）：正在执行中，本次触发直接返回不阻塞: trigger={} ref={}",
                    trigger, characterRef);
            return AgentResult.reentrant(trigger, characterRef);
        }
        try {
            if (tickBarrier != null && !inBatchDispatch()) {
                // tick 屏障登记：本决策链从进入到行动落库完成都算"本 tick 的在途工作"
                // （R1 异步批次除外——批次任务跨 tick 运行，登记会让每 tick 屏障白等，见批次标志注释）
                tickBarrier.register(projectId);
            }
            String brainLevel = resolveBrainLevel(projectId, characterRef);
            // 预算窗口：原子地"没有窗口就开一个"——按角色并发后必须原子（否则并发线程各开一个窗口，
            // 每 tick 预算会被翻倍，见 BudgetGuard.beginTickIfAbsent）
            boolean ownsTick = budgetGuard.beginTickIfAbsent(projectId, defaultBudgetPerTick);
            try {
                return AiCallContext.withTrace(null, () -> loop(trigger, characterRef, ctx, brainLevel));
            } finally {
                if (ownsTick) {
                    budgetGuard.endTick(projectId);
                }
            }
        } finally {
            if (tickBarrier != null && !inBatchDispatch()) {
                tickBarrier.complete(projectId);
            }
            if (reentrancyPerCharacter) {
                runningRefs.remove(lockKey);
            } else {
                running.set(false);
            }
        }
    }

    /**
     * 复合行动触发（接线，等同旧 ActionEngine.trigger）：归属校验 → 情境组装 → 思考决策
     * （预算门控，用尽/纯程序化档程序化兜底）→ 计划持久化（支持 plannedTime 未来=定时）→ 立即执行或留待定时。
     *
     * @param characterId 角色 ID（归属校验）
     *
     * @param userId 归属用户 ID
     *
     * @param triggerType 触发类型（manual/after_dialog/event/scheduled）
     *
     * @param conversationId 会话 ID（可空）
     *
     * @param reason 触发理由（可空）
     *
     * @param plannedTime 计划时间（可空；未来时间=定时执行）
     *
     * @param situation 情境覆盖（可空=思考组件按游戏时间/角色状态组装）
     *
     * @return 行动计划（status=planned 表示待定时执行 / done 表示已执行）
     */
    public ActorActionPlan triggerAction(Long characterId, Long userId, String triggerType,
                                         Long conversationId, String reason, LocalDateTime plannedTime,
                                         String situation) {
        ActorCharacter ch = characterService.requireOwned(characterId, userId);
        // 此刻可发生空间：情境组装前空间保障——buildSituation 内感知注入的空间段
        // 必须是最新生成结果（空间缺失/过期才 AI 生成，有效复用零成本；失败零阻断）
        ensureDecisionSpace(ch);
        // 情境组装：显式传入优先；否则思考组件按「游戏时间 + 角色状态 + 感知」组装（统一时间口径）
        String situationText = (situation == null || situation.isBlank())
                ? decisionService.buildSituation(ch) : situation;
        // 计划时间（lambda 内解析决策 JSON 后回填，用数组持有器满足 effectively final）
        LocalDateTime[] plannedHolder = {plannedTime};
        // 无外层预算窗口时自开预算窗口（对齐 trigger() 的 ownsTick 模式）——
        // 否则 BudgetGuard.tryConsume 在无窗口时恒返回 false，手动/after_dialog/event 行动决策
        // 将全部退化为程序化兜底，AI 决策链（Doubao 等）永不触发（回归旧 ActionEngine.trigger 的 AI 行为）。
        // 有外层窗口（世界引擎 tick 进行中）则复用共享窗口，预算统一进门面（修）。
        Long projectId = ch.getProjectId();
        boolean ownsTick = projectId != null && budgetGuard.budgetTotal(projectId) < 0;
        if (ownsTick) {
            budgetGuard.beginTick(projectId, defaultBudgetPerTick);
        }
        try {
            return AiCallContext.withTrace(null, () -> {
                // 双保险之惰性补生成：手动行动路径也补生成日计划（对齐 trigger() 决策循环，
                // 否则手动触发行动（前端「行动」按钮）不产生当日计划，planning 仅在引擎决策时生效）
                maybeEnsurePlanLazy(projectId, com.holzyn.actor.common.NpcRefs.of(characterId), null);
                Map<String, Object> decision = resolveDecision(ch, userId, situationText, false, false);
                // 计划时间：显式 plannedTime 优先；否则取决策 JSON 内的 scheduled 计划时间
                if (plannedHolder[0] == null) {
                    Object scheduled = decision == null ? null : decision.get("plannedTime");
                    if (scheduled != null) {
                        plannedHolder[0] = parseTime(String.valueOf(scheduled));
                    }
                }
                ActorActionPlan plan = new ActorActionPlan();
                plan.setCharacterId(characterId);
                plan.setConversationId(conversationId);
                plan.setTriggerType(triggerType == null ? "manual" : triggerType);
                plan.setActionJson(toJson(decision));
                plan.setPlannedTime(plannedHolder[0]);
                plan.setStatus("planned");
                planRepository.save(plan);
                if (plannedHolder[0] != null && plannedHolder[0].isAfter(LocalDateTime.now())) {
                    // 定时执行：保留 planned，由 ActionScheduledJob 到期后经 AgentActionService 执行
                    log.info("[AgentRuntime] 行动计划已排期（plannedTime 未来）：plan={} char={} 时间={}",
                            plan.getId(), characterId, plannedHolder[0]);
                    return plan;
                }
                // 立即执行（AgentActionService 内部完成 executing→done 全生命周期持久化）
                agentActionService.execute(plan);
                return plan;
            });
        } finally {
            if (ownsTick) {
                budgetGuard.endTick(projectId);
            }
        }
    }

    /**
     * 引擎 tick 触发壳（接线：WorldEngine 逐游戏小时/跨日边界驱动特殊角色决策循环）。
     *
     * @param projectId 项目 ID
     *
     * @return 各角色决策结果列表
     */
    public List<AgentResult> tick(Long projectId) {
        List<AgentResult> results = new ArrayList<>();
        if (projectId == null) {
            return results;
        }
        // 长跑抓修：全角色决策入口排除已故角色
        List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        for (ActorCharacter ch : characters) {
            results.add(trigger(AgentTrigger.ENGINE_TICK, com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                    DecisionContext.builder().projectId(projectId).build()));
        }
        return results;
    }

    /**
     * 手动触发壳（接线：页面手动触发行动；与 {@link #triggerAction} 等价但走完整管线）。
     */
    public AgentResult manualTrigger(String characterRef, DecisionContext ctx) {
        return trigger(AgentTrigger.MANUAL, characterRef, ctx);
    }

    /**
     * 对话完成后的行动自动评估（after_dialog 触发源， 由 RoleplayService 调用，迁移自旧 ActionEngine）。
     * <p>异步评估会话内高重要度成员（importance≥3）是否应自动行动，不阻塞对话返回。</p>
     *
     * @param conversationId 会话 ID
     *
     * @param userId 归属用户 ID
     */
    public void evaluateAfterDialogAsync(Long conversationId, Long userId) {
        if (!autoEvaluate) {
            return;
        }
        submitAsync(() -> {
            try {
                evaluateMembers(conversationId, userId, "after_dialog");
            } catch (Exception e) {
                log.warn("[AgentRuntime] after_dialog 行动评估失败: conv={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 世界事件注入后的行动自动评估（event 触发源， 由 WorldEventService 调用，迁移自旧 ActionEngine）。
     *
     * @param conversationId 会话 ID
     *
     * @param userId 归属用户 ID
     */
    public void triggerForEventAsync(Long conversationId, Long userId) {
        if (!autoEvaluate) {
            return;
        }
        submitAsync(() -> {
            try {
                evaluateMembers(conversationId, userId, "event");
            } catch (Exception e) {
                log.warn("[AgentRuntime] event 行动评估失败: conv={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /** 提交异步评估任务（在途计数，供 awaitIdle 排空断言） */
    private void submitAsync(Runnable task) {
        inFlight.incrementAndGet();
        executor.execute(() -> {
            try {
                task.run();
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /**
     * 等待在途异步评估任务排空（测试断言用；超时后返回，不阻塞业务）。
     *
     * @param timeoutMs 最大等待毫秒
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 评估会话成员（迁移自旧 ActionEngine）：遍历会话成员，高重要度（≥3）未删除角色
     * 触发一次自动行动（after_dialog/event），单角色失败隔离。
     * <p>包内可见（供异步包装调用与同包单测确定性验证）。</p>
     */
    void evaluateMembers(Long conversationId, Long userId, String triggerType) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
        for (ActorConversationMember m : members) {
            ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
            // 迁移修正：旧 ActionEngine 用 Integer.valueOf(0).equals(deleted) 把「未删除（deleted=0）」
            // 误判为跳过 → after_dialog/event 自动评估从不触发（no-op）；按意图跳过已删除（deleted=1）
            if (ch == null || Integer.valueOf(1).equals(ch.getDeleted())) {
                continue;
            }
            if (ch.getImportance() == null || ch.getImportance() < 3) {
                continue;
            }
            try {
                triggerAction(ch.getId(), userId, triggerType, conversationId, null, null, null);
            } catch (Exception e) {
                log.warn("[AgentRuntime] 角色行动评估失败: char={} : {}", ch.getId(), e.getMessage());
            }
        }
    }

    // ==================== 决策循环管线 ====================

    /**
     * 决策循环管线（五步：感知→记忆→思考→行动→学习； / 各步委托新组件）。
     * <p>单步失败均降级继续（不阻断管线），与既有「AI 失败程序化兜底」哲学一致；
     * 各环节经 {@link AiCallContext#recordStep} 记入当前业务动作级 trace（决策链路）。
     * 对话类触发（DIALOG/EVENT）由 RoleplayService 组件承载流式回复，本管线仅做决策链路
     * 记录不执行行动（避免 SSE IO 进门面）。</p>
     */
    private AgentResult loop(AgentTrigger trigger, String characterRef, DecisionContext ctx, String brainLevel) {
        Long projectId = ctx == null ? null : ctx.projectId();
        List<String> steps = new ArrayList<>();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「逐 NPC AI 决策/行动」原属只有特殊型才做的
        // 行为（普通型走人群程序化 0 AI），是否特殊型改用 NpcTypeResolver 查 actor_profile.type 判定；
        // projectId 为空（手动触发未带 ctx）时按 typeOf(null, ref) 只按引用解析、不做项目校验
        Long actorId = NpcRefs.idOf(characterRef);
        boolean special = actorId != null && npcTypeResolver.isSpecial(projectId, characterRef);
        // 决策调度 ——
        // dispatch.all-npc-hourly=true 时普通 NPC 也走引擎决策（逐人感知/思考/行动，空间全量消费）；
        // false=回退 行为（特殊型 AI 决策 + 普通型程序化作息 0 AI）
        boolean engineDecision = special || (growthProperties != null && growthProperties.isEnabled()
                && growthProperties.getDispatch().isAllNpcHourly());
        boolean programmatic = "programmatic".equals(brainLevel);
        boolean dialogue = trigger != null && trigger.isDialogue();

        // ① 感知（真实调用现有 agent 服务，B2 已落地；时间口径=ctx.gameSecond 或世界时钟当前值）
        try {
            if (projectId != null && characterRef != null) {
                perceptionService.buildPerception(projectId, characterRef,
                        ctx == null ? null : ctx.gameSecond());
            }
            steps.add("perception");
            AiCallContext.recordStep(projectId, "agent_decision", "perception",
                    "感知组装（ref=" + characterRef + "：游戏时间/自身状态/可见事件/关系摘要）", 0);
        } catch (Exception e) {
            log.warn("[AgentRuntime] 感知失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
        }

        // ② 记忆（真实调用现有 agent 服务，B4 已落地；Smallville 评分检索）
        try {
            if (projectId != null && characterRef != null) {
                List<ActorMemory> memories = agentMemoryService.retrieve(projectId, characterRef,
                        ctx == null ? null : ctx.query(), DEFAULT_MEMORY_TOP_K);
                // trace 数据流补全：本次调用读取的记忆 memoryId 记入数据源（异常可回溯）
                if (memories != null && !memories.isEmpty()) {
                    StringBuilder ds = new StringBuilder("记忆检索 ").append(memories.size()).append(" 条: ");
                    int cnt = 0;
                    for (ActorMemory mem : memories) {
                        if (mem == null || mem.getId() == null) {
                            continue;
                        }
                        if (cnt++ > 0) {
                            ds.append(",");
                        }
                        ds.append("#").append(mem.getId());
                        if (cnt >= 10) {
                            break;
                        }
                    }
                    AiCallContext.addDataSource(ds.toString());
                }
                AiCallContext.recordStep(projectId, "agent_decision", "memory",
                        "记忆检索 " + (memories == null ? 0 : memories.size())
                                + " 条（Smallville 评分 top-K）", 0);
            }
            steps.add("memory");
        } catch (Exception e) {
            log.warn("[AgentRuntime] 记忆检索失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
        }

        // ③ 思考（/：委托 thinking 组件 DecisionService——brainProfile 门控 programmatic/full/light；
        // 预算经 BudgetGuard.tryConsume 门控，用尽/纯程序化档走程序化兜底不冻结，修）
        // ：special 或 allNpcHourly（普通 NPC 逐人决策）都加载角色实体进决策链
        ActorCharacter ch = null;
        if (engineDecision) {
            ch = characterRepository.findById(actorId).orElse(null);
        }
        // 双保险之惰性补生成：决策时若当天尚无日计划（planning_enabled 且非 programmatic 档）则补生成
        maybeEnsurePlanLazy(projectId, characterRef, ctx == null ? null : ctx.gameSecond());
        Map<String, Object> decision = resolveDecision(ch, ctx == null ? null : ctx.userId(),
                ctx == null ? null : ctx.situation(), programmatic,
                ctx != null && ctx.stimulusType() != null);
        steps.add("thinking");

        // ④ 行动（非对话触发且决策已生成：计划持久化 → AgentActionService.execute 执行决策计划）
        // Prompt 工程：action_type=continue 表示 AI 判定「继续当前行为」——
        // 不新建行动计划、不执行（真实的人不会无时无刻切换行动）；程序化兜底同样返回 continue
        if (decision != null && "continue".equals(decision.get("actionType"))) {
            steps.add("continue");
            AiCallContext.recordStep(projectId, "agent_decision", "action",
                    "决策=continue（AI 判定继续当前行为，不新建行动计划）", 0);
            log.info("[AgentRuntime] 决策=continue（继续当前行为，不执行新行动）: ref={}", characterRef);
        } else if (!dialogue && engineDecision && decision != null && ch != null) {
            try {
                ActorActionPlan plan = new ActorActionPlan();
                plan.setCharacterId(actorId);
                plan.setConversationId(ctx == null ? null : ctx.conversationId());
                plan.setTriggerType(trigger == null ? "manual" : trigger.name().toLowerCase(Locale.ROOT));
                // 一跳防环：链式反应（CHAIN_REACTION）的响应行动带
                // _chainReaction 标记 → 其执行不再触发链式反应（一跳扩散，防递归风暴）
                if (ctx != null && StimulusType.CHAIN_REACTION.code().equals(ctx.stimulusType())) {
                    decision.put(ChainReactionService.CHAIN_MARKER, true);
                }
                plan.setActionJson(toJson(decision));
                plan.setStatus("planned");
                // 冲突协调（，AgentRuntime 事务外路径——允许 AI 协商
                // scene=negotiate；决策目标被其他 NPC 占用且本方可让时，AI 协商/确定性兜底调整
                // 行动[等待/改道]，调整后决策回写计划[含 waitMinutes → 走等待类计划排期，
                // 到点重新决策]，冲突记录落 actor_conflict_record）
                if (actionCoordinatorService != null && ch != null && ch.getProjectId() != null) {
                    try {
                        var adj = actionCoordinatorService.resolveConflict(ch.getProjectId(),
                                com.holzyn.actor.common.NpcRefs.of(ch.getId()), ch.getName(),
                                objectMapper.readTree(plan.getActionJson()),
                                ctx == null ? null : ctx.gameSecond(), true);
                        if (adj != null && adj.changed() && adj.decision() != null) {
                            decision = adj.decision();
                            plan.setActionJson(toJson(decision));
                            String adjAction = String.valueOf(decision.getOrDefault("action", ""));
                            AiCallContext.recordStep(ch.getProjectId(), "agent_decision", "coordination",
                                    "冲突协调：行动已调整（目标被占用 → "
                                            + (adjAction.length() > 40 ? adjAction.substring(0, 40) + "…" : adjAction)
                                            + "）", 0);
                        }
                    } catch (Exception e) {
                        log.warn("[AgentRuntime] 冲突协调失败（原样执行）: ref={} : {}", characterRef, e.getMessage());
                    }
                }
                // 时间感行动：AI 决策可选 wait_minutes（等待游戏分钟数）→
                // 等待类计划（actor_plan.planned_game_second 游戏时刻排期，到点由 WaitPlanService
                // 触发重新决策）；排期成功 → 本次不立即执行；否则立即执行并重置连续等待计数
                Long waitUntil = null;
                if (waitPlanService != null
                        && decision.get("waitMinutes") instanceof Number waitNum && waitNum.intValue() > 0) {
                    try {
                        waitUntil = waitPlanService.scheduleWaitPlan(projectId, characterRef,
                                ctx == null ? null : ctx.gameSecond(), waitNum.intValue(), decision);
                    } catch (Exception e) {
                        log.warn("[AgentRuntime] 等待计划排期失败（立即执行）: ref={} : {}",
                                characterRef, e.getMessage());
                    }
                }
                planRepository.save(plan);
                if (waitUntil != null) {
                    plan.setPlannedGameSecond(waitUntil);
                    planRepository.save(plan);
                    steps.add("wait_plan");
                    AiCallContext.recordStep(projectId, "agent_decision", "wait_plan",
                            "时间感行动：等待类计划排期（plan=" + plan.getId() + " 到点游戏秒=" + waitUntil
                                    + "，到点重新决策）", 0);
                    log.info("[AgentRuntime] 等待类计划已排期（到点重新决策）: plan={} ref={} 到点={}",
                            plan.getId(), characterRef, waitUntil);
                } else {
                    agentActionService.execute(plan);
                    if (waitPlanService != null) {
                        try {
                            waitPlanService.noteActionExecuted(projectId, characterRef);
                        } catch (Exception ignored) {
                            // 连续等待计数重置失败不影响行动执行
                        }
                    }
                    steps.add("roleplay_action");
                    AiCallContext.recordStep(projectId, "agent_decision", "action",
                            "行动组件：AgentActionService 执行决策（plan=" + plan.getId() + "）", 0);
                }
            } catch (Exception e) {
                log.warn("[AgentRuntime] 行动失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
            }
        } else if (dialogue) {
            steps.add("roleplay_action");
            AiCallContext.recordStep(projectId, "agent_decision", "roleplay",
                    "扮演组件：对话流式回复由 RoleplayService 组件承载（C3 接线，门面不执行 SSE IO）", 0);
        } else {
            steps.add("roleplay_action");
            AiCallContext.recordStep(projectId, "agent_decision", "roleplay_action",
                    "扮演/行动组件：无特殊角色/无决策上下文，本步仅占位", 0);
        }

        // 实时行为决策系统：④.5 打断恢复——STIMULUS 即时决策的响应行动执行完后，
        // 同流同预算执行 maybeResume（AI 恢复决策三态：resume 回原地点继续原活动 / adjust 调整新任务 /
        // abandon 放弃；AI 不可用回退 - 共性框架；无 active 打断记录零成本返回）。
        // 放置于决策循环内：与即时决策共享同一 BudgetGuard 预算窗口，
        // 且无论响应行动是否产出（AI 决定仅观察/行动失败）都执行恢复判断（行为连续性）。
        if (trigger == AgentTrigger.STIMULUS && interruptRecoveryService != null) {
            try {
                interruptRecoveryService.maybeResume(projectId, characterRef,
                        ctx == null ? null : ctx.gameSecond(),
                        ctx == null ? null : ctx.stimulusText());
                steps.add("resume");
                AiCallContext.recordStep(projectId, "agent_decision", "resume",
                        "打断恢复：maybeResume（AI 恢复决策三态，同流同预算）", 0);
            } catch (Exception e) {
                log.warn("[AgentRuntime] 打断恢复失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
            }
        }

        // ⑤ 学习（：委托 LearningService.scheduleConsolidation 骨架——log + trace 记录，实现留 D 阶段）
        scheduleLearning(projectId, characterRef, trigger);
        steps.add("learning");

        // 空闲降频：决策循环完成 → 记录决策时刻（任何触发源共用）——
        // WorldEngine 周期通道据此判定「有进行中行动 → 本小时周期决策跳过（延续行动零 AI）」；
        // 即时通道（STIMULUS）不受降频影响（不经该判定），行为连续性语义保持。
        if (idleFrequencyService != null) {
            try {
                idleFrequencyService.noteDecision(projectId, characterRef,
                        ctx == null ? null : ctx.gameSecond());
            } catch (Exception e) {
                log.debug("[AgentRuntime] 空闲降频决策时刻记录失败（跳过）: ref={} : {}",
                        characterRef, e.getMessage());
            }
        }

        String message = "决策循环完成（trigger=" + trigger + " brainLevel=" + brainLevel
                + " 步骤=" + String.join("→", steps) + "）";
        return AgentResult.success(trigger, characterRef, brainLevel, steps, message);
    }

    /**
     * 双保险之惰性补生成：决策循环前若该特殊角色「当天尚无日计划」则补生成
     * （brainProfile planning_enabled=1 且非 programmatic 档；AI 预算不可用回退机制计划）。
     * 失败降级继续（不影响决策管线）。
     */
    private void maybeEnsurePlanLazy(Long projectId, String characterRef, Long gameSecond) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——日计划补生成属只有特殊型才做的行为
        // （planning_enabled 是特殊型脑力配置；普通型无目标/计划表），改用类型解析器判定
        if (planningService == null || projectId == null || characterRef == null
                || !npcTypeResolver.isSpecial(projectId, characterRef)) {
            return;
        }
        try {
            ActorAgentProfile profile = brainProfileService.resolveByRef(characterRef);
            if (profile == null || Integer.valueOf(0).equals(profile.getPlanningEnabled())
                    || "programmatic".equals(profile.getBrainLevel())) {
                return;
            }
            Long gameHour = gameSecond != null ? gameSecond / 3600L : null;
            planningService.ensurePlanDay(projectId, characterRef, gameHour);
        } catch (Exception e) {
            log.warn("[AgentRuntime] 日计划惰性补生成失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
        }
    }

    /**
     * 决策生成（思考组件统一出口）：纯程序化档（brainLevel=programmatic）或 AI 预算用尽 →
     * {@link DecisionService#generateProgrammaticDecision}（0 AI 成本，不冻结）；否则走 AI 决策链。
     *
     * @param ch 特殊角色实体（null 返回 null）
     *
     * @param userId 归属用户 ID
     *
     * @param situation 情境覆盖（可空）
     *
     * @param programmatic 是否强制程序化档
     *
     * @param hasStimulus 是否有外部刺激（O6 发育演化：STIMULUS 即时通道=true——阶段门控让路；
     *                    引擎主决策/手动=false——感知情境文本不是刺激，阶段门控空闲接管）
     *
     * @return 决策 Map（可空=无角色）
     */
    private Map<String, Object> resolveDecision(ActorCharacter ch, Long userId, String situation,
                                                boolean programmatic, boolean hasStimulus) {
        if (ch == null || ch.getProjectId() == null) {
            return null;
        }
        Long projectId = ch.getProjectId();
        // 微层接线：AI 强度档位与 brainProfile/预算正交——programmatic 档强制程序化决策
        // （零 AI，不消耗预算）；max 档预算耗尽也放行 AI（全 AI 必调，无预算限制）
        // R1：异步批次（预热/补发）内预算走独立批次命名空间（tryConsumeBatch），
        // 与引擎 tick 窗口隔离——批次任务跨窗口运行时不会被窗口关闭/其它消费饿死。
        boolean intensityProgrammatic = aiIntensityGate != null && !aiIntensityGate.allowsAi(projectId);
        boolean aiBudget = !programmatic && !intensityProgrammatic
                && ((aiIntensityGate != null && aiIntensityGate.isMax(projectId))
                || (inBatchDispatch()
                ? budgetGuard.tryConsumeBatch(projectId)
                : budgetGuard.tryConsume(projectId)));
        if (programmatic || intensityProgrammatic || !aiBudget) {
            // 纯程序化档 / AI 强度 programmatic / 预算用尽：程序化决策（0 AI 成本；不冻结，修）
            String reason = intensityProgrammatic ? "AI 强度档位=programmatic"
                    : (programmatic ? "brainProfile=programmatic 档" : "AI 预算用尽");
            AiCallContext.recordStep(projectId, "agent_decision", "thinking",
                    "思考组件：程序化兜底决策（" + reason + "，0 AI 成本）", 0);
            return decisionService.generateProgrammaticDecision(ch);
        }
        AiCallContext.recordStep(projectId, "agent_decision", "thinking",
                "思考组件：DecisionService 生成决策（brainLevel=" + resolveBrainLevel(projectId, com.holzyn.actor.common.NpcRefs.of(ch.getId()))
                        + "）", 0);
        // ：行为多样性注入（AI 决策路径——同一情境不同角色行动有差异；
        // 注入【状态涟漪】段到情境；服务未装配/失败跳过零阻断）
        if (behaviorDiversityService != null) {
            try {
                String ripple = behaviorDiversityService.inject(
                        projectId, com.holzyn.actor.common.NpcRefs.of(ch.getId()), null, null);
                if (ripple != null && !ripple.isBlank()) {
                    situation = situation == null || situation.isBlank() ? ripple : situation + "\n" + ripple;
                }
            } catch (Exception e) {
                log.warn("[AgentRuntime] 行为多样性注入失败（跳过）: ref={} : {}", com.holzyn.actor.common.NpcRefs.of(ch.getId()), e.getMessage());
            }
        }
        // O6 发育演化：外部刺激标记传给决策链（STIMULUS 即时通道=true——阶段门控让路；
        // 引擎主决策/手动触发=false——即使 situation 带感知情境文本也视为空闲，阶段门控接管）
        return decisionService.generateDecision(ch, userId, situation, hasStimulus);
    }

    /**
     * 解析脑力档位（统一入口 resolveByRef；解析失败/未传 projectId 返回 null=按缺省管线继续）。
     */
    private String resolveBrainLevel(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        try {
            ActorAgentProfile profile = brainProfileService.resolveByRef(characterRef);
            return profile == null ? null : profile.getBrainLevel();
        } catch (Exception e) {
            log.warn("[AgentRuntime] 脑力配置解析失败（按缺省管线继续）: ref={} : {}", characterRef, e.getMessage());
            return null;
        }
    }

    /**
     * 此刻可发生空间：情境组装前空间保障（空间缺失/过期 → 按 LOD 生成；有效复用零成本）。
     * <p>感知情境中的空间段由 {@code PerceptionService.buildPerceptionText} 注入，本方法保证
     * 「决策/对话情境组装时空间恒有效」——任何失败零阻断（情境照常组装）。</p>
     */
    private void ensureDecisionSpace(ActorCharacter ch) {
        if (growthSpaceService == null || ch == null || ch.getProjectId() == null) {
            return;
        }
        try {
            Long gameSecond = worldClockService == null ? null
                    : worldClockService.currentEngineGameSecond(ch.getProjectId());
            growthSpaceService.ensureSpace(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                    gameSecond);
        } catch (Exception e) {
            log.warn("[AgentRuntime] 此刻可发生空间保障失败（情境照常组装，零阻断）: char={} : {}",
                    ch.getId(), e.getMessage());
        }
    }

    /**
     * 学习步骤（：委托 LearningService 骨架；D 阶段真实实现填充）。
     */
    private void scheduleLearning(Long projectId, String characterRef, AgentTrigger trigger) {
        try {
            learningService.scheduleConsolidation(projectId, null);
        } catch (Exception e) {
            log.warn("[AgentRuntime] 学习骨架调用失败（降级继续）: ref={} : {}", characterRef, e.getMessage());
        }
        AiCallContext.recordStep(projectId, "agent_decision", "learning",
                "学习组件：LearningService 周期巩固骨架（反思/关系/偏好/生态留 D 阶段）", 0);
    }

    /** 决策 Map → JSON（失败兜底占位文本，不阻断计划创建） */
    private String toJson(Map<String, Object> decision) {
        try {
            return objectMapper.writeValueAsString(decision == null ? Map.of() : decision);
        } catch (Exception e) {
            return DECISION_JSON_FALLBACK;
        }
    }

    /** 解析计划时间（支持 ISO 或 yyyy-MM-dd HH:mm；解析失败返回 null，迁移自旧 ActionEngine） */
    private static LocalDateTime parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
