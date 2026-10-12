package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 等待类计划服务单元测试（实时行为决策系统 M4 时间感行动，domain/agent/runtime，同包）。
 * <p>职责：验证——① scheduleWaitPlan（开关/非正数/时刻未知→null 立即执行；成功→计划游戏时刻 +
 * 时长钳制 + 连续计数；连续超限→null）；② noteActionExecuted 重置；③ runDueWaitPlans
 * （到期→标记 done + 重新 AI 决策[ctx.stimulusType=wait_plan_due] / 激活闸门跳过 / 连续超限直接执行）。</p>
 */
class WaitPlanServiceTest {

    private RealtimeDecisionProperties props;
    private ActorActionPlanRepository planRepository;
    private ActorCharacterRepository characterRepository;
    private AgentRuntime agentRuntime;
    private AgentActionService agentActionService;
    private RealtimeDecisionActivationService activationService;
    private PersonalityInjectionService personalityInjectionService;
    private WorldClockService worldClockService;
    private WaitPlanService service;

    private final List<DecisionContext> triggeredCtxs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        agentRuntime = Mockito.mock(AgentRuntime.class);
        agentActionService = Mockito.mock(AgentActionService.class);
        activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        personalityInjectionService = Mockito.mock(PersonalityInjectionService.class);
        service = new WaitPlanService(props, planRepository, characterRepository);

        // M4 启动抓修（Bean 环）：agentRuntime 改为 @Lazy 字段注入，测试经反射装配 mock
        java.lang.reflect.Field f0 = WaitPlanService.class.getDeclaredField("agentRuntime");
        f0.setAccessible(true);
        f0.set(service, agentRuntime);

        worldClockService = Mockito.mock(WorldClockService.class);
        when(worldClockService.currentEngineGameSecond(any())).thenReturn(36000L);
        java.lang.reflect.Field f1 = WaitPlanService.class.getDeclaredField("worldClockService");
        f1.setAccessible(true);
        f1.set(service, worldClockService);

        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        java.lang.reflect.Field f2 = WaitPlanService.class.getDeclaredField("currentUserProvider");
        f2.setAccessible(true);
        f2.set(service, currentUserProvider);

        java.lang.reflect.Field f3 = WaitPlanService.class.getDeclaredField("realtimeDecisionActivationService");
        f3.setAccessible(true);
        f3.set(service, activationService);
        when(activationService.isActive(any())).thenReturn(true);

        java.lang.reflect.Field f4 = WaitPlanService.class.getDeclaredField("personalityInjectionService");
        f4.setAccessible(true);
        f4.set(service, personalityInjectionService);
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("");

        java.lang.reflect.Field f5 = WaitPlanService.class.getDeclaredField("agentActionService");
        f5.setAccessible(true);
        f5.set(service, agentActionService);

        Mockito.doAnswer(inv -> {
            triggeredCtxs.add(inv.getArgument(2));
            return AgentResult.success(inv.getArgument(0), inv.getArgument(1), null, List.of(), "mock");
        }).when(agentRuntime).trigger(any(), any(), any());

        when(planRepository.findByStatusAndPlannedGameSecondLessThanEqual(anyString(), any())).thenReturn(List.of());
    }

    // ==================== scheduleWaitPlan ====================

    @Test
    void scheduleWaitPlan_disabledReturnsNull() {
        props.setTimeSenseActionEnabled(false);
        assertNull(service.scheduleWaitPlan(1L, "npc:1", 36000L, 30, new java.util.LinkedHashMap<>()));
    }

    @Test
    void scheduleWaitPlan_invalidInputsReturnNull() {
        assertNull(service.scheduleWaitPlan(1L, "npc:1", 36000L, 0, new java.util.LinkedHashMap<>()),
                "waitMinutes<=0 → null 立即执行");
        // 游戏时刻未知（显式传入 null 且世界时钟也不可用）→ null（无法排期，立即执行）
        when(worldClockService.currentEngineGameSecond(any())).thenReturn(null);
        assertNull(service.scheduleWaitPlan(1L, "npc:1", null, 30, new java.util.LinkedHashMap<>()),
                "游戏时刻未知 → null（无法排期）");
    }

    @Test
    void scheduleWaitPlan_successComputesGameSecond() {
        Long until = service.scheduleWaitPlan(1L, "npc:1", 36000L, 30, new java.util.LinkedHashMap<>());
        assertEquals(Long.valueOf(36000L + 30 * 60L), until, "计划游戏时刻 = 当前 + wait_minutes×60");
        assertEquals(1, service.consecutiveCount(1L, "npc:1"), "连续等待计数 +1");
    }

    @Test
    void scheduleWaitPlan_capsMaxGameMinutes() {
        props.setWaitMaxGameMinutes(120);
        Long until = service.scheduleWaitPlan(1L, "npc:1", 36000L, 5000, new java.util.LinkedHashMap<>());
        assertEquals(Long.valueOf(36000L + 120 * 60L), until, "等待时长钳制到 wait-max-game-minutes");
    }

    @Test
    void scheduleWaitPlan_consecutiveCapReturnsNull() {
        props.setWaitMaxConsecutive(2);
        assertNotNull(service.scheduleWaitPlan(1L, "npc:1", 36000L, 10, new java.util.LinkedHashMap<>()));
        assertNotNull(service.scheduleWaitPlan(1L, "npc:1", 36100L, 10, new java.util.LinkedHashMap<>()));
        assertNull(service.scheduleWaitPlan(1L, "npc:1", 36200L, 10, new java.util.LinkedHashMap<>()),
                "连续等待超限 → 不再排期（立即执行，防死循环）");
    }

    @Test
    void scheduleWaitPlan_prefersEnginePositionOverPassedGameSecond() {
        // ⚠️ 2026-09-07 冒烟抓修：即时决策 ctx.gameSecond=事件时刻（流动时钟），而到期检查
        // runDueWaitPlans 用引擎位置（hour×3600）——双口径会导致等待计划永不到期（Q12 重新决策死掉）；
        // 统一以世界时钟引擎位置（mock 返回 36000）为基准，忽略调用方传入的流动时钟时刻。
        Long until = service.scheduleWaitPlan(1L, "npc:1", 999999999L, 30, new java.util.LinkedHashMap<>());
        assertEquals(Long.valueOf(36000L + 30 * 60L), until, "以引擎位置为基准，忽略入参（流动时钟）");
    }

    @Test
    void noteActionExecuted_resetsCounter() {
        service.scheduleWaitPlan(1L, "npc:1", 36000L, 10, new java.util.LinkedHashMap<>());
        assertEquals(1, service.consecutiveCount(1L, "npc:1"));
        service.noteActionExecuted(1L, "npc:1");
        assertEquals(0, service.consecutiveCount(1L, "npc:1"), "非等待行动执行后重置");
    }

    // ==================== runDueWaitPlans ====================

    private ActorActionPlan waitPlan(Long id, Long characterId, String action) {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(id);
        p.setCharacterId(characterId);
        p.setStatus("planned");
        p.setPlannedGameSecond(36000L);
        p.setActionJson("{\"type\":\"custom\",\"action\":\"" + action + "\",\"target\":\"河边\",\"urgency\":2}");
        return p;
    }

    private ActorCharacter charOf(Long id) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName("甲");
        return ch;
    }

    @Test
    void runDueWaitPlans_marksDoneAndRedeciDes() throws Exception {
        ActorActionPlan plan = waitPlan(10L, 1L, "去河边打水");
        when(planRepository.findByStatusAndPlannedGameSecondLessThanEqual(eq("planned"), eq(36000L)))
                .thenReturn(List.of(plan));
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(charOf(1L)));

        service.runDueWaitPlans(1L, 36000L);

        assertEquals("done", plan.getStatus(), "原等待计划标记完成");
        verify(planRepository).save(plan);
        // R1 异步化：到点重决策在虚拟线程异步触发，轮询等待其落地
        awaitTriggered(1);
        assertEquals(1, triggeredCtxs.size(), "到点重新决策一次");
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("wait_plan_due", ctx.stimulusType(), "等待到期刺激类型");
        assertTrue(ctx.situation().contains("去河边打水"), "情境含原动作提示");
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:1"), any());
    }

    /** R1 异步化辅助：轮询等待到点重决策在虚拟线程落地（≤3s，触发是 mock 立即返回，实际微秒级）。 */
    private void awaitTriggered(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (triggeredCtxs.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void runDueWaitPlans_inactiveProjectSkips() {
        when(activationService.isActive(eq(1L))).thenReturn(false);
        ActorActionPlan plan = waitPlan(10L, 1L, "去河边打水");
        when(planRepository.findByStatusAndPlannedGameSecondLessThanEqual(eq("planned"), eq(36000L)))
                .thenReturn(List.of(plan));
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(charOf(1L)));

        service.runDueWaitPlans(1L, 36000L);

        assertEquals("planned", plan.getStatus(), "未激活项目：保持 planned 不触发");
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    @Test
    void runDueWaitPlans_consecutiveCapDirectExecutes() {
        props.setWaitMaxConsecutive(2);
        service.scheduleWaitPlan(1L, "npc:1", 36000L, 10, new java.util.LinkedHashMap<>());
        service.scheduleWaitPlan(1L, "npc:1", 36100L, 10, new java.util.LinkedHashMap<>());
        // 已达连续上限 → 第 3 个到期直接执行原动作（不再重新决策）
        ActorActionPlan plan = waitPlan(10L, 1L, "去河边打水");
        when(planRepository.findByStatusAndPlannedGameSecondLessThanEqual(eq("planned"), eq(37000L)))
                .thenReturn(List.of(plan));
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(charOf(1L)));

        service.runDueWaitPlans(1L, 37000L);

        verify(agentActionService).execute(plan);
        verify(agentRuntime, never()).trigger(any(), any(), any());
        assertEquals(0, service.consecutiveCount(1L, "npc:1"), "直接执行后重置连续计数");
    }

    @Test
    void runDueWaitPlans_noDuePlansNoop() {
        service.runDueWaitPlans(1L, 36000L);
        verify(agentRuntime, never()).trigger(any(), any(), any());
        verify(agentActionService, never()).execute(any());
    }
}
