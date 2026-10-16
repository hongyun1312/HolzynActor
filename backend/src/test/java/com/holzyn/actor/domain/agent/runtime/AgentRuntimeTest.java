package com.holzyn.actor.domain.agent.runtime;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRuntime（阶段 C C3 决策循环管线 + 行动复合触发接线）单元测试（与实现同包，domain/agent/runtime）。
 * <p>职责：验证——触发源分派（对话→仅决策链路记录不执行行动 / 引擎 tick→行动组件执行）、
 * 管线顺序（感知→记忆→思考[DecisionService]→行动[AgentActionService]→学习[LearningService]）、
 * 防重入（running 时二次触发直接返回）、预算联动（自开/复用窗口、预算用尽程序化兜底不冻结）、
 * 普通/程序化智能体不委托 AI 思考、复合行动触发 triggerAction（归属/计划持久化/定时/执行）与
 * after_dialog/event 自动评估。</p>
 */
class AgentRuntimeTest {

    private PerceptionService perceptionService;
    private AgentMemoryService agentMemoryService;
    private DecisionService decisionService;
    private AgentActionService agentActionService;
    private LearningService learningService;
    private BudgetGuard budgetGuard;
    private BrainProfileService brainProfileService;
    private ActorCharacterRepository characterRepository;
    private CharacterService characterService;
    private ActorActionPlanRepository planRepository;
    private ActorConversationMemberRepository memberRepository;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        perceptionService = Mockito.mock(PerceptionService.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        decisionService = Mockito.mock(DecisionService.class);
        agentActionService = Mockito.mock(AgentActionService.class);
        learningService = Mockito.mock(LearningService.class);
        budgetGuard = new BudgetGuard();
        brainProfileService = Mockito.mock(BrainProfileService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        characterService = Mockito.mock(CharacterService.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        runtime = new AgentRuntime(perceptionService, agentMemoryService, decisionService,
                agentActionService, learningService, budgetGuard,
                brainProfileService, characterRepository, characterService, planRepository, memberRepository,
                new ObjectMapper(), new NpcTypeResolver(characterRepository));
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(runtime, "agentEnabled", true);
        ReflectionTestUtils.setField(runtime, "defaultBudgetPerTick", 5);
        ReflectionTestUtils.setField(runtime, "autoEvaluate", true);
        // 脑力配置解析：npc:1 → full（走 AI 思考）；npc:5 → light（普通型）
        when(brainProfileService.resolveByRef("npc:1"))
                .thenReturn(profile("full"));
        when(brainProfileService.resolveByRef("npc:5"))
                .thenReturn(profile("light"));
        // 特殊角色实体（thinking 步骤加载）
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(100L);
        ch.setName("测试角色");
        ch.setImportance(4);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        // G1a：普通型 NPC 夹具（单主表下必须显式 type=common）——npc:5 走「不委托思考/行动组件」分支
        ActorCharacter commonNpc = new ActorCharacter();
        commonNpc.setId(5L);
        commonNpc.setProjectId(100L);
        commonNpc.setName("普通居民");
        commonNpc.setType("common");
        when(characterRepository.findById(5L)).thenReturn(Optional.of(commonNpc));
        // 复合行动触发：归属校验返回角色实体
        when(characterService.requireOwned(1L, 1L)).thenReturn(ch);
    }

    /** 构造已执行的决策（思考组件返回）。 */
    private Map<String, Object> decision() {
        return Map.of("type", "schedule", "action", "按日常习惯活动", "target", "驻地附近",
                "reason", "测试", "urgency", 3);
    }

    private ActorAgentProfile profile(String brainLevel) {
        ActorAgentProfile p = new ActorAgentProfile();
        p.setBrainLevel(brainLevel);
        return p;
    }

    // ==================== 触发源分派（各枚举路由正确，C3 委托新组件） ====================

    /**
     * ENGINE_TICK 触发：思考委托 DecisionService.generateDecision；非对话 → 计划持久化 +
     * 行动委托 AgentActionService.execute（行动组件执行决策计划）。
     */
    @Test
    void engineTickTriggerDelegatesThinkingAndAction() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertTrue(result.success());
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
        // 行动计划持久化 + 行动组件执行（C3 接线）
        verify(planRepository, times(1)).save(any(ActorActionPlan.class));
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
        assertTrue(result.steps().contains("roleplay_action"));
    }

    /**
     * DIALOG 触发：对话流式回复由 RoleplayService 组件承载（C3 接线），门面仅做决策链路记录，
     * 不执行行动（避免 SSE IO 进门面）。
     */
    @Test
    void dialogTriggerRecordsDecisionLinkWithoutExecutingAction() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder()
                .projectId(100L).userId(1L).conversationId(200L).build();

        AgentResult result = runtime.trigger(AgentTrigger.DIALOG, "npc:1", ctx);

        assertTrue(result.success());
        assertEquals(AgentTrigger.DIALOG, result.trigger());
        // 思考仍在决策链路中发生
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
        // 对话类触发不执行行动（RoleplayService 承载流式回复）
        verify(agentActionService, never()).execute(any());
        assertTrue(result.steps().containsAll(List.of("perception", "memory", "thinking", "roleplay_action", "learning")));
    }

    /**
     * MANUAL 触发：手动触发壳路由正确（无会话 → 行动组件 execute）。
     */
    @Test
    void manualTriggerRoutesCorrectly() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.manualTrigger("npc:1", ctx);

        assertEquals(AgentTrigger.MANUAL, result.trigger());
        verify(decisionService).generateDecision(any(), any(), any(), anyBoolean());
        verify(agentActionService).execute(any(ActorActionPlan.class));
    }

    /**
     * 普通型 NPC（npc:5，type=common）触发：不委托思考/行动组件（无特殊角色决策链），仅占位；
     * 感知/记忆仍真实调用；学习骨架仍调用。
     */
    @Test
    void commonNpcRefSkipsComponentDelegation() {
        DecisionContext ctx = DecisionContext.builder()
                .projectId(100L).userId(1L).conversationId(200L).query("在吗").build();

        AgentResult result = runtime.trigger(AgentTrigger.DIALOG, "npc:5", ctx);

        assertTrue(result.success());
        assertEquals("light", result.brainLevel());
        verify(perceptionService).buildPerception(100L, "npc:5", null);
        verify(agentMemoryService).retrieve(100L, "npc:5", "在吗", 8);
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(agentActionService, never()).execute(any());
        assertTrue(result.steps().containsAll(List.of("perception", "memory", "thinking", "roleplay_action", "learning")));
    }

    /**
     * 总开关关闭（agentEnabled=false）：触发直接跳过，不执行任何管线。
     */
    @Test
    void disabledAgentSkipsPipeline() {
        ReflectionTestUtils.setField(runtime, "agentEnabled", false);
        DecisionContext ctx = DecisionContext.builder().projectId(100L).build();

        AgentResult result = runtime.trigger(AgentTrigger.MANUAL, "npc:1", ctx);

        assertTrue(result.success());
        verify(perceptionService, never()).buildPerception(anyLong(), anyString(), any());
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
    }

    // ==================== 管线顺序（感知→记忆→思考→行动→学习） ====================

    /**
     * 管线顺序断言：感知 → 记忆 → 思考（DecisionService）→ 行动（AgentActionService）。
     */
    @Test
    void pipelineRunsInOrder() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder()
                .projectId(100L).userId(1L).query("聊聊").build();

        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        InOrder inOrder = Mockito.inOrder(perceptionService, agentMemoryService, decisionService, agentActionService);
        inOrder.verify(perceptionService).buildPerception(100L, "npc:1", null);
        inOrder.verify(agentMemoryService).retrieve(100L, "npc:1", "聊聊", 8);
        inOrder.verify(decisionService).generateDecision(any(), any(), any(), anyBoolean());
        inOrder.verify(agentActionService).execute(any(ActorActionPlan.class));
    }

    // ==================== 防重入（O6 G1a：按角色粒度；可用配置回退项目级单锁） ====================

    /**
     * 防重入（O6 G1a 默认粒度 = 按角色）：<b>同一角色</b>正在决策时二次触发直接返回 reentrant，
     * 不执行任何管线；<b>另一个角色不受影响</b>——这正是"刺激驱动的实时决策不再被其他 NPC 的
     * tick 决策挤掉"的回归锁定（改造前是全局单锁，任何角色在跑就一律拦截）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void reentrantTriggerIsPerCharacter() {
        ReflectionTestUtils.setField(runtime, "reentrancyPerCharacter", true);
        java.util.Set<String> refs =
                (java.util.Set<String>) ReflectionTestUtils.getField(runtime, "runningRefs");
        DecisionContext ctx = DecisionContext.builder().projectId(100L).build();

        refs.add("100|npc:1");
        AgentResult same = runtime.trigger(AgentTrigger.MANUAL, "npc:1", ctx);
        assertTrue(same.reentrant(), "同一角色正在决策 → 重入直接返回");
        assertFalse(same.success());

        AgentResult other = runtime.trigger(AgentTrigger.MANUAL, "npc:2", ctx);
        assertFalse(other.reentrant(), "另一个角色不应被拦（按角色防重入）");
        refs.clear();
    }

    /**
     * 回退开关：{@code holzyn.actor.agent.reentrancy-per-character=false} → 沿用项目级单锁
     * （running=true 时任何角色一律 reentrant）。用于并发模型出问题时快速回退，不重新编译。
     */
    @Test
    void reentrantLegacyProjectLockStillWorks() {
        ReflectionTestUtils.setField(runtime, "reentrancyPerCharacter", false);
        ReflectionTestUtils.setField(runtime, "running", new AtomicBoolean(true));
        DecisionContext ctx = DecisionContext.builder().projectId(100L).build();

        AgentResult result = runtime.trigger(AgentTrigger.MANUAL, "npc:1", ctx);

        assertTrue(result.reentrant());
        assertFalse(result.success());
        verify(perceptionService, never()).buildPerception(anyLong(), anyString(), any());
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
    }

    // ==================== 预算（beginTick/tryConsume/endTick 联动） ====================

    /**
     * 预算窗口：无外层窗口时 trigger 自开 beginTick 并在结束后 endTick 清理（budgetTotal 回到 -1）。
     */
    @Test
    void budgetWindowOpenedAndClosed() {
        assertEquals(-1, budgetGuard.budgetTotal(100L), "初始无窗口");
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertEquals(-1, budgetGuard.budgetTotal(100L), "结束后窗口已清理（endTick）");
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
    }

    /**
     * 预算用尽（外层窗口 budget=0）：思考步骤走程序化决策（不委托 AI 思考，不冻结，修 C-6），
     * 程序化决策仍执行行动；外层窗口不被 endTick 误清理。
     */
    @Test
    void budgetExhaustedFallsBackProgrammaticAndPreservesOuterWindow() {
        budgetGuard.beginTick(100L, 0);
        when(decisionService.generateProgrammaticDecision(any())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(decisionService, times(1)).generateProgrammaticDecision(any());
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
        assertTrue(result.success(), "预算用尽不中断管线（程序化兜底）");
        assertTrue(result.steps().contains("thinking"));
        assertEquals(0, budgetGuard.budgetTotal(100L), "外层窗口未被 endTick 清理");
    }

    /**
     * 外层窗口预算充足：复用外窗预算（不重开），思考组件正常委托 DecisionService。
     */
    @Test
    void reusesOuterBudgetWindow() {
        budgetGuard.beginTick(100L, 3);
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertEquals(2, budgetGuard.remaining(100L), "复用的外层窗口消耗 1 次预算");
        assertEquals(3, budgetGuard.budgetTotal(100L));
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
    }

    // ==================== 复合行动触发（triggerAction，C3 接线 ActionController） ====================

    /**
     * triggerAction：归属校验 + 计划持久化 + 立即执行（无 plannedTime 时 execute）。
     */
    @Test
    void triggerActionPersistsPlanAndExecutesImmediately() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        when(characterService.requireOwned(1L, 1L))
                .thenReturn(character(1L, 100L, "测试角色"));

        ActorActionPlan plan = runtime.triggerAction(1L, 1L, "manual", null,
                "手动触发", null, null);

        assertNotNull(plan);
        assertEquals(1L, plan.getCharacterId());
        assertEquals("manual", plan.getTriggerType());
        verify(characterService).requireOwned(1L, 1L);
        verify(planRepository).save(any(ActorActionPlan.class));
        verify(agentActionService).execute(plan);
        // C3 修复后锁定：无外层预算窗口时 triggerAction 自开窗口 → 思考委托 AI 决策链（非程序化兜底）
        verify(decisionService).generateDecision(any(), any(), any(), anyBoolean());
    }

    /**
     * triggerAction：plannedTime 为未来 → 保留 planned 状态留待定时（ActionScheduledJob 到期执行），不立即 execute。
     */
    @Test
    void triggerActionWithFuturePlannedTimeSchedules() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        when(characterService.requireOwned(1L, 1L)).thenReturn(character(1L, 100L, "测试角色"));

        ActorActionPlan plan = runtime.triggerAction(1L, 1L, "manual", null,
                null, LocalDateTime.now().plusMinutes(30), null);

        assertEquals("planned", plan.getStatus());
        verify(agentActionService, never()).execute(any());
        // 定时排期同样先走 AI 决策链（自开预算窗口）
        verify(decisionService).generateDecision(any(), any(), any(), anyBoolean());
    }

    /**
     * triggerAction：归属校验失败（角色不存在）→ 抛异常不执行。
     */
    @Test
    void triggerActionRequiresOwned() {
        when(characterService.requireOwned(1L, 1L)).thenThrow(new RuntimeException("角色不存在"));
        try {
            runtime.triggerAction(1L, 1L, "manual", null, null, null, null);
        } catch (RuntimeException e) {
            assertEquals("角色不存在", e.getMessage());
        }
        verify(agentActionService, never()).execute(any());
    }

    // ==================== 对话/事件后自动评估（C3 接线 RoleplayService/WorldEventService） ====================

    /**
     * evaluateAfterDialogAsync：遍历会话成员，仅高重要度（≥3）未删除角色触发自动行动（after_dialog）。
     */
    @Test
    void evaluateAfterDialogTriggersHighImportanceMembers() {
        ActorConversationMember m1 = member(1L, 1L); // importance 4（高，触发）
        ActorConversationMember m2 = member(1L, 9L); // importance 2（低，跳过）
        when(memberRepository.findByConversationId(100L)).thenReturn(List.of(m1, m2));
        // 低重要度角色（importance=2）：自动评估应跳过
        ActorCharacter low = new ActorCharacter();
        low.setId(9L);
        low.setProjectId(100L);
        low.setImportance(2);
        when(characterRepository.findById(9L)).thenReturn(Optional.of(low));
        when(characterService.requireOwned(1L, 1L)).thenReturn(character(1L, 100L, "高重要度"));
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());

        // 同步调用评估核心（包内方法，确定性验证；异步包装由 evaluateAfterDialogAsync 覆盖）
        runtime.evaluateMembers(100L, 1L, "after_dialog");

        verify(memberRepository).findByConversationId(100L);
        verify(characterRepository).findById(1L);
        verify(characterService).requireOwned(1L, 1L);
        verify(characterService, never()).requireOwned(9L, 1L);
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
        // after_dialog 自动评估同样走 AI 决策链（自开预算窗口，非程序化兜底）
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
    }

    private ActorCharacter character(Long id, Long projectId, String name) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(projectId);
        ch.setName(name);
        return ch;
    }

    private ActorConversationMember member(Long conversationId, Long characterId) {
        ActorConversationMember m = new ActorConversationMember();
        m.setConversationId(conversationId);
        m.setCharacterId(characterId);
        return m;
    }

    // ==================== tick 壳（engine_tick 触发壳，C3 由 WorldEngine 调用） ====================

    /**
     * tick 壳：遍历项目未删除特殊角色各触发一次 ENGINE_TICK（C3 由 WorldEngine 逐时驱动调用）。
     */
    @Test
    void tickShellTriggersPerCharacter() {
        ActorCharacter c1 = new ActorCharacter();
        c1.setId(1L);
        ActorCharacter c2 = new ActorCharacter();
        c2.setId(2L);
        c2.setProjectId(100L);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(c1, c2));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(c2));
        when(brainProfileService.resolveByRef(anyString())).thenReturn(profile("full"));
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());

        List<AgentResult> results = runtime.tick(100L);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.trigger() == AgentTrigger.ENGINE_TICK && r.success()));
        verify(decisionService, times(2)).generateDecision(any(), any(), any(), anyBoolean());
    }

    // ==================== 实时行为决策系统 M2：STIMULUS 触发后打断恢复钩子 ====================

    /**
     * STIMULUS 即时决策：响应行动执行完后同流执行 maybeResume（恢复决策三态，ctx 携带
     * gameSecond/stimulusText 传入）；管线步骤含 resume。
     */
    @Test
    void stimulusTriggerRunsResumeAfterAction() {
        InterruptRecoveryService recovery = Mockito.mock(InterruptRecoveryService.class);
        ReflectionTestUtils.setField(runtime, "interruptRecoveryService", recovery);
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L)
                .gameSecond(36000L).stimulusText("粮仓失火").build();

        AgentResult result = runtime.trigger(AgentTrigger.STIMULUS, "npc:1", ctx);

        assertTrue(result.success());
        verify(recovery).maybeResume(100L, "npc:1", 36000L, "粮仓失火");
        assertTrue(result.steps().contains("resume"), "STIMULUS 决策循环含恢复步骤");
    }

    /**
     * 非 STIMULUS 触发（周期/手动/对话）：不执行打断恢复（maybeResume 不调用，行为连续性——
     * 只有即时通道的响应行动结束后才判断恢复）。
     */
    @Test
    void nonStimulusTriggerSkipsResume() {
        InterruptRecoveryService recovery = Mockito.mock(InterruptRecoveryService.class);
        ReflectionTestUtils.setField(runtime, "interruptRecoveryService", recovery);
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());

        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1",
                DecisionContext.builder().projectId(100L).userId(1L).build());

        verify(recovery, never()).maybeResume(any(), any(), any(), any());
    }

    /**
     * 未装配打断恢复服务（字段 null）：STIMULUS 决策照常执行，恢复步骤跳过（行为回 M1）。
     */
    @Test
    void stimulusTriggerWithoutResumeServiceSkips() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L)
                .gameSecond(36000L).stimulusText("粮仓失火").build();

        AgentResult result = runtime.trigger(AgentTrigger.STIMULUS, "npc:1", ctx);

        assertTrue(result.success());
        assertFalse(result.steps().contains("resume"), "未装配恢复服务 → 无恢复步骤");
    }

    // ==================== M5.5 微层接线：AI 强度档位（决策生成） ====================

    /** 构造档位闸门（mock 引擎行）并注入 runtime。 */
    private AiIntensityGate gateOf(String level) {
        ActorWorldEngineRepository repo = Mockito.mock(ActorWorldEngineRepository.class);
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(100L);
        e.setAiIntensity(level);
        when(repo.findByProjectId(100L)).thenReturn(Optional.of(e));
        AiIntensityGate gate = new AiIntensityGate(repo);
        ReflectionTestUtils.setField(runtime, "aiIntensityGate", gate);
        return gate;
    }

    /**
     * programmatic 档：即使 brainProfile=full（AI 档）也强制程序化决策——AI 思考零调用，
     * 程序化决策仍产出计划并执行（世界不冻结）。
     */
    @Test
    void intensityProgrammaticForcesProgrammaticDecision() {
        gateOf("programmatic");
        when(decisionService.generateProgrammaticDecision(any())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertTrue(result.success());
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(decisionService, Mockito.times(1)).generateProgrammaticDecision(any());
        verify(planRepository, Mockito.times(1)).save(any(ActorActionPlan.class));
        verify(agentActionService, Mockito.times(1)).execute(any(ActorActionPlan.class));
    }

    /**
     * max 档：预算窗口耗尽也放行 AI（全 AI 必调无预算）——耗尽后仍委托 AI 思考。
     */
    @Test
    void intensityMaxGrantsAiDespiteBudgetExhausted() {
        gateOf("max");
        budgetGuard.beginTick(100L, 1);
        assertTrue(budgetGuard.tryConsume(100L), "先耗尽预算");
        assertEquals(0, budgetGuard.remaining(100L));
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertTrue(result.success());
        verify(decisionService, Mockito.times(1)).generateDecision(any(), any(), any(), anyBoolean());
    }

    /**
     * full 档（标准）：预算耗尽后程序化兜底（M5.5 回归——max 之外档位保持既有预算语义）。
     */
    @Test
    void intensityFullFallsBackProgrammaticWhenBudgetExhausted() {
        gateOf("full");
        budgetGuard.beginTick(100L, 1);
        assertTrue(budgetGuard.tryConsume(100L));
        when(decisionService.generateProgrammaticDecision(any())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertTrue(result.success());
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(decisionService, Mockito.times(1)).generateProgrammaticDecision(any());
    }

    // ==================== R1 引擎异步批次线程标志（2026-09-28） ====================

    /**
     * 线程标志生命周期：mark → inBatchDispatch=true → clear → false（WorldEngine 任务包装
     * try/finally 成对调用；标志是 ThreadLocal——池线程复用不受污染，清除后才算完整契约）。
     */
    @Test
    void batchDispatchFlagLifecycle() {
        assertFalse(AgentRuntime.inBatchDispatch(), "初始非批次线程");
        AgentRuntime.markBatchDispatch();
        assertTrue(AgentRuntime.inBatchDispatch(), "标记后进入批次语义");
        AgentRuntime.clearBatchDispatch();
        assertFalse(AgentRuntime.inBatchDispatch(), "清除后恢复普通语义");
    }

    /**
     * R1 异步批次语义①：批次线程内的决策链<b>不进 tick 屏障</b>（register/complete 均跳过）——
     * 批次任务跨引擎 tick/扫描运行，登记在途会让每个 tick 的 awaitQuiescence 白等（≤30s/次）。
     * 对照组：非批次线程的 trigger 照常 register/complete。
     */
    @Test
    void batchDispatchSkipsTickBarrierRegistration() {
        RecordingTickBarrier barrier = new RecordingTickBarrier();
        ReflectionTestUtils.setField(runtime, "tickBarrier", barrier);
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        // 批次线程：不登记
        AgentRuntime.markBatchDispatch();
        try {
            runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);
        } finally {
            AgentRuntime.clearBatchDispatch();
        }
        assertEquals(0, barrier.registers(), "批次线程决策不得登记 tick 屏障");

        // 对照组（普通线程）：照常 register/complete
        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);
        assertEquals(1, barrier.registers(), "普通线程决策照常登记 tick 屏障");
        assertEquals(1, barrier.completes(), "普通线程决策照常完成登记");
    }

    /**
     * R1 异步批次语义②：批次线程内的决策链预算走<b>独立批次命名空间</b>（tryConsumeBatch），
     * 与引擎 tick 窗口隔离——批次窗口开启时消费批次预算；批次预算用尽 → 程序化兜底；
     * 批次窗口未开启（无批次上下文误标）→ 程序化兜底（不误用 tick 窗口）。
     */
    @Test
    void batchDispatchConsumesBatchBudgetNotTickBudget() {
        budgetGuard.beginBatch(100L, 1);
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentRuntime.markBatchDispatch();
        try {
            AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);
            assertTrue(result.success());
            // 批次窗口消费计数：须在 endBatch 前断言（endBatch 会清理内存态）
            assertEquals(1, budgetGuard.batchUsed(100L), "批次预算消费 1 次");
            assertEquals(-1, budgetGuard.budgetTotal(100L), "tick 窗口完全未被触碰（命名空间隔离）");
        } finally {
            AgentRuntime.clearBatchDispatch();
            budgetGuard.endBatch(100L);
        }
        verify(decisionService, times(1)).generateDecision(any(), any(), any(), anyBoolean());
    }

    /**
     * R1 异步批次语义②补充：批次预算用尽 → 程序化兜底（不冻结、不误用 tick 窗口预算）。
     */
    @Test
    void batchDispatchBudgetExhaustedFallsBackProgrammatic() {
        budgetGuard.beginBatch(100L, 0);
        when(decisionService.generateProgrammaticDecision(any())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentRuntime.markBatchDispatch();
        try {
            runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);
        } finally {
            AgentRuntime.clearBatchDispatch();
            budgetGuard.endBatch(100L);
        }
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(decisionService, times(1)).generateProgrammaticDecision(any());
        assertEquals(0, budgetGuard.batchUsed(100L), "预算 0 → 无批次预算消费");
    }

    /**
     * R1 异步批次语义②补充：批次窗口未开启时，批次标志内的决策链预算门控失败 →
     * 程序化兜底（fail-safe：批次上下文缺失不会退回 tick 窗口乱扣预算）。
     */
    @Test
    void batchDispatchWithoutBatchWindowFallsBackProgrammatic() {
        // 不开批次窗口（异常场景：标记了批次但窗口未开——如任务提交失败竞态）
        when(decisionService.generateProgrammaticDecision(any())).thenReturn(decision());
        DecisionContext ctx = DecisionContext.builder().projectId(100L).userId(1L).build();

        AgentRuntime.markBatchDispatch();
        try {
            runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);
        } finally {
            AgentRuntime.clearBatchDispatch();
        }
        verify(decisionService, never()).generateDecision(any(), any(), any(), anyBoolean());
        verify(decisionService, times(1)).generateProgrammaticDecision(any());
        assertEquals(-1, budgetGuard.budgetTotal(100L), "tick 窗口未被误开/误扣");
    }

    /**
     * P0-02 验收 3（可重试性）：重试补算的决策（ctx.retry()=true）成功 → 决策点打标
     * {@code AI_RETRY}（可追溯「这条 AI 决策经过重试」）——台账来源为 AI_RETRY（聚合属 AI），
     * 且 action_json 随计划 JSON 透传该标记（下游动作日志/记忆/事件/编年史同源打标）。
     */
    @Test
    void engineTickRetryContextTagsDecisionAsAiRetry() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionLedgerService ledger = Mockito.mock(DecisionLedgerService.class);
        ReflectionTestUtils.setField(runtime, "decisionLedgerService", ledger);
        DecisionContext ctx = DecisionContext.builder()
                .projectId(100L).userId(1L).gameSecond(3600L).retry(true).build();

        AgentResult result = runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        assertTrue(result.success());
        // 台账：来源 AI_RETRY、gameSecond=原定时刻（回填到历史位置）
        verify(ledger, times(1)).record(eq(100L), eq(1L), eq("npc:1"), eq(3600L),
                eq(DecisionSource.AI_RETRY), eq("按日常习惯活动"), eq("engine_tick"));
        // 下游透传：行动计划 action_json 携带 _decisionSource=AI_RETRY（AgentActionService
        // 据此写动作日志/事件/记忆的 decision_source 列）
        ArgumentCaptor<ActorActionPlan> planCap = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(planRepository, atLeastOnce()).save(planCap.capture());
        assertTrue(planCap.getAllValues().stream()
                        .anyMatch(p -> p.getActionJson() != null && p.getActionJson().contains("\"AI_RETRY\"")),
                "action_json 应透传 _decisionSource=AI_RETRY，实际=" + planCap.getAllValues());
    }

    /**
     * 非重试路径不打标 AI_RETRY：常规 ENGINE_TICK 决策（ctx.retry()=false）→ 台账来源保持
     * 决策链原生标记（此处 mock 未打标 → 视为 AI，不升级为 AI_RETRY）。
     */
    @Test
    void engineTickWithoutRetryKeepsOriginalSource() {
        when(decisionService.generateDecision(any(), any(), any(), anyBoolean())).thenReturn(decision());
        DecisionLedgerService ledger = Mockito.mock(DecisionLedgerService.class);
        ReflectionTestUtils.setField(runtime, "decisionLedgerService", ledger);
        DecisionContext ctx = DecisionContext.builder()
                .projectId(100L).userId(1L).gameSecond(7200L).build();

        runtime.trigger(AgentTrigger.ENGINE_TICK, "npc:1", ctx);

        // 常规路径不升级为 AI_RETRY；决策链产物未打标（mock 直返 Map）→ 传 null 来源，
        // 由 DecisionLedgerService.record 兜底视为 AI（台账完整性：未打标不丢行）
        verify(ledger, times(1)).record(eq(100L), eq(1L), eq("npc:1"), eq(7200L),
                isNull(), eq("按日常习惯活动"), eq("engine_tick"));
    }

    /**
     * 记录 register/complete 的屏障替身（R1 批次不进屏障断言用）。
     */
    private static final class RecordingTickBarrier extends TickBarrier {

        private final java.util.concurrent.atomic.AtomicInteger registers = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger completes = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void register(Long projectId) {
            registers.incrementAndGet();
            super.register(projectId);
        }

        @Override
        public void complete(Long projectId) {
            completes.incrementAndGet();
            super.complete(projectId);
        }

        private int registers() {
            return registers.get();
        }

        private int completes() {
            return completes.get();
        }
    }
}
