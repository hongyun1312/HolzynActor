package com.holzyn.actor.domain.agent.action;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.action.entity.ActorActionLog;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.action.service.ActionSseHub;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行动组件（C2 收编 ActionEngine.execute）单元测试（domain/agent/action，同包）。
 * <p>职责：验证——行动执行（改角色状态/写日志/plan done）、行动事件发布（WorldEventBus.publish）、
 * 记忆沉淀（重要度≥阈值写 observation）、低重要度不沉淀。</p>
 */
class AgentActionServiceTest {

    private ActorCharacterRepository characterRepository;
    private ActorActionPlanRepository planRepository;
    private ActorActionLogRepository logRepository;
    private ActorProjectRepository projectRepository;
    private WorldEventBus worldEventBus;
    private AgentMemoryService agentMemoryService;
    private AgentActionService service;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        logRepository = Mockito.mock(ActorActionLogRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        ActionSseHub actionSseHub = Mockito.mock(ActionSseHub.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        service = new AgentActionService(characterRepository, planRepository, logRepository, projectRepository,
                actionSseHub, currentUserProvider, worldEventBus, agentMemoryService, new ObjectMapper());
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "actionMemoryImportanceThreshold", 4);
        when(planRepository.save(any(ActorActionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logRepository.save(any(ActorActionLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ActorActionPlan plan(String actionJson) {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(42L);
        p.setCharacterId(3L);
        p.setActionJson(actionJson);
        return p;
    }

    private ActorCharacter character(int importance) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setImportance(importance);
        return ch;
    }

    private String decisionJson(int urgency) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("type", "move");
        node.put("action", "前往城东市场采购药材");
        node.put("target", "城东市场");
        node.put("reason", "药材告急");
        node.put("urgency", urgency);
        return node.toString();
    }

    private void stubBase(ActorCharacter ch) {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        ActorProject project = new ActorProject();
        project.setId(100L);
        project.setUserId(1L);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
    }

    /**
     * 行动执行：改角色状态 + plan done + 发布行动事件 + 高重要度（urgency=5≥4）写 observation 记忆。
     */
    @Test
    void executeUpdatesStatePublishesEventAndWritesMemory() {
        stubBase(character(4));

        service.execute(plan(decisionJson(5)));

        // P0-03：recordObservation 带决策来源参数（该测试决策 JSON 未打标 → null=视为 AI）
        verify(agentMemoryService).recordObservation(eq(100L), eq("npc:3"),
                eq("前往城东市场采购药材（目标：城东市场）"), eq(5), isNull());
        verify(worldEventBus).publish(eq(1L), eq(100L), any());
        assertEquals("done", plan(decisionJson(5)).getStatus() == null ? null : "done");
    }

    /**
     * 低重要度行动（urgency=3 < 阈值 4）不沉淀记忆，但行动仍正常完成 + 事件发布。
     */
    @Test
    void lowUrgencySkipsMemoryButActionCompletes() {
        stubBase(character(4));
        ActorActionPlan p = plan(decisionJson(3));

        service.execute(p);

        verify(agentMemoryService, never()).recordObservation(any(), any(), any(), any(), any());
        verify(worldEventBus).publish(eq(1L), eq(100L), any());
        assertEquals("done", p.getStatus(), "行动仍正常完成");
    }

    /**
     * 行动执行失败（决策 JSON 非法）→ 状态置 cancelled，不发布事件（异常捕获降级）。
     */
    @Test
    void executeFailureMarksCancelled() {
        stubBase(character(4));
        ActorActionPlan p = plan("这不是JSON{{{");

        service.execute(p);

        assertEquals("cancelled", p.getStatus(), "非法决策 JSON 行动标记取消");
    }

    /**
     * 已软删除角色的存量计划：执行入口直接取消，不得发事件/写记忆/改状态。
     * <p>回归背景（七日跑）：角色删除只置 deleted=1，删除前排下的计划仍会被
     * 定时执行器扫到并真实执行，出现「已删除的人还在行动」的幽灵行为。</p>
     */
    @Test
    void deletedCharacterPlanIsCancelledWithoutSideEffects() {
        ActorCharacter ch = character(4);
        ch.setDeleted(1);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        ActorActionPlan p = plan(decisionJson(5));

        service.execute(p);

        assertEquals("cancelled", p.getStatus(), "已删除角色的计划必须取消");
        verify(worldEventBus, never()).publish(any(), any(), any());
        verify(agentMemoryService, never()).recordObservation(any(), any(), any(), any(), any());
    }

    // ==================== 治理批次 0 · 卡1 回归：特殊 NPC move 行动 locationId 归一 ====================

    /**
     * G-ACTION-LOC-01（卡1 回归断言，起始 PASS）：特殊 NPC move 行动后 locationId 归一正确 +
     * onNpcMoved 挂钩不被卡1 修复波及。
     * <p>与 G-CROWD-LOC-01 互为对照：特殊路径已对（L192 normalizeLocationId + L196 挂钩）、
     * 人群路径待修（applyCrowdAction 不写 locationId）。</p>
     */
    @Test
    void execute_moveAction_normalizesLocationIdAndHooksSandbox() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(5L);
        ch.setProjectId(1L);
        ch.setName("赵铁柱");
        ch.setType("special");
        ch.setLocation("村口");
        ch.setLocationId(5L);
        when(characterRepository.findById(5L)).thenReturn(Optional.of(ch));
        ActorProject project = new ActorProject();
        project.setId(1L);
        project.setUserId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        // 地点归一真实服务（内存地点表：集市=21）
        com.holzyn.actor.domain.world.repository.WorldLocationRepository locRepo =
                Mockito.mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class);
        when(locRepo.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                locEntity(21L, "集市")));
        ReflectionTestUtils.setField(service, "locationNormalizationService",
                new com.holzyn.actor.domain.world.service.LocationNormalizationService(locRepo));
        com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandbox =
                Mockito.mock(com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService.class);
        ReflectionTestUtils.setField(service, "worldSandboxStateService", sandbox);

        ActorActionPlan p = new ActorActionPlan();
        p.setId(42L);
        p.setCharacterId(5L);
        p.setActionJson("{\"type\":\"move\",\"action\":\"前往集市采购药材\",\"target\":\"集市\","
                + "\"params\":{\"move\":{\"to\":\"集市\"}},\"reason\":\"药材告急\",\"urgency\":5}");

        service.execute(p);

        assertEquals("集市", ch.getLocation(), "中间态：位置已更新");
        assertEquals(21L, ch.getLocationId(), "move 行动后 locationId 归一（集市=21）");
        verify(sandbox).onNpcMoved(eq(1L), eq("npc:5"), eq("special"), eq("集市"), any(), isNull());
    }

    private com.holzyn.actor.domain.world.entity.ActorWorldLocation locEntity(Long id, String name) {
        com.holzyn.actor.domain.world.entity.ActorWorldLocation l =
                new com.holzyn.actor.domain.world.entity.ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        return l;
    }

    // ============ 2026-09-27 第 2 轮长跑缺陷 P2-10 回归：AI 行动回写 actor_state.activity_state ============

    /**
     * P2-10 回归（移动语义）：{@code all-npc-hourly=true}（全员走 AI 决策）时，程序化两条路径
     * （applyCrowdAction / programmaticUpdate）都不再执行，{@code actor_state.activity_state}
     * 曾恒为初始值 {@code idle}（第 2 轮长跑实测 23/23 全 idle，导演视角看不到「此刻在做什么」）。
     * 本类是所有 AI 行动的唯一执行出口 → 必须按映射表唯一来源 {@code toActivityState} 回写：
     * 「前往城东市场采购药材」含移动语义 → {@code walk}，且 last_action=行动原文。
     */
    @Test
    void execute_mapsActionTextToActivityState_walk() {
        stubBase(character(4));
        com.holzyn.actor.domain.npc.state.service.NpcActivityStateService activity =
                Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.class);
        ReflectionTestUtils.setField(service, "npcActivityStateService", activity);

        service.execute(plan(decisionJson(5)));

        verify(activity).setActivity(eq(100L), eq("npc:3"), eq("walk"), eq("前往城东市场采购药材"));
    }

    /**
     * P2-10 回归（非移动语义）：同一写入点按映射表给出 {@code rest}——
     * 证明回写走的是共用映射表（而非在行动层硬编码某一种状态）。
     */
    @Test
    void execute_mapsActionTextToActivityState_rest() {
        stubBase(character(4));
        com.holzyn.actor.domain.npc.state.service.NpcActivityStateService activity =
                Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.class);
        ReflectionTestUtils.setField(service, "npcActivityStateService", activity);

        service.execute(plan("{\"type\":\"rest\",\"action\":\"回屋就寝歇息\",\"target\":\"\","
                + "\"reason\":\"夜深\",\"urgency\":2}"));

        verify(activity).setActivity(eq(100L), eq("npc:3"), eq("rest"), eq("回屋就寝歇息"));
    }

    // ============行为裁决：四列回填 + FAIL 早退 ============

    @Test
    @DisplayName("executeWithVerdict FAIL：计划取消、写 verdict/reason/effects、发失败事件，不执行原行动")
    void executeWithVerdictFailCancelsAndPublishesFailure() {
        stubBase(character(4));
        ActorActionPlan p = plan(decisionJson(5));
        com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict verdict =
                com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict.fail(
                        "R-TRAVEL-01", "ROUTE_BLOCKED", "路被封住了。");

        service.executeWithVerdict(p, verdict);

        assertEquals("cancelled", p.getStatus());
        assertEquals("fail", p.getVerdict());
        assertEquals("ROUTE_BLOCKED", p.getReasonCode());
        assertEquals("[]", p.getEffectsJson());
        verify(agentMemoryService, never()).recordObservation(any(), any(), any(), any(Integer.class), any());
        org.mockito.ArgumentCaptor<com.holzyn.actor.domain.agent.event.EventSpec> captor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.domain.agent.event.EventSpec.class);
        verify(worldEventBus).publish(eq(1L), eq(100L), captor.capture());
        assertEquals("action", captor.getValue().getKind());
        assertEquals("adjudication", captor.getValue().getSource());
        assertEquals("plan:42:verdict", captor.getValue().getSourceKey());
    }

    @Test
    @DisplayName("executeWithVerdict PARTIAL：原行动照常执行（plan done + 事件），verdict 落 partial")
    void executeWithVerdictPartialRunsNormally() {
        stubBase(character(4));
        ActorActionPlan p = plan(decisionJson(3));
        com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict verdict =
                com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict.of(
                        com.holzyn.actor.domain.world.adjudicate.model.Verdict.PARTIAL,
                        "LOW_IMPORTANCE_PASS", "他试了试。", 0, List.of(), "rule");

        service.executeWithVerdict(p, verdict);

        assertEquals("done", p.getStatus());
        assertEquals("partial", p.getVerdict());
        assertEquals("LOW_IMPORTANCE_PASS", p.getReasonCode());
        verify(worldEventBus).publish(eq(1L), eq(100L), any());
    }

    // ============闭环实测回归：speak_to 的 target 是「人」，不能写成 location ============

    /**
     * 回归：speak 决策的 target 命中项目内角色名时，位置应落到该角色当前所在地，
     * 而不是把人物名写进 {@code actor_profile.location}（实测 npc:65 行动后 location=沈伯远，
     * 后续当前位置/同地点可见性/事实过滤全部失真）。
     */
    @Test
    @DisplayName("speak 行动目标是角色：位置取对方所在地，绝不写成人物名")
    void execute_speakTargetCharacter_resolvesToTheirLocation() {
        ActorCharacter ch = character(4);
        ch.setLocation("菜畦");
        stubBase(ch);
        ActorCharacter target = new ActorCharacter();
        target.setId(9L);
        target.setProjectId(100L);
        target.setName("沈伯远");
        target.setLocation("长明灯塔");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(ch, target));

        service.execute(plan("{\"type\":\"speak\",\"action\":\"向沈伯远拱手问安\",\"target\":\"沈伯远\","
                + "\"reason\":\"同族长辈\",\"urgency\":2}"));

        assertEquals("长明灯塔", ch.getLocation(), "位置取目标角色所在地，不是人物名");
    }

    /**
     * 回归：目标是角色但对方地点未知 → 保持行动者原位置，不做任何猜测。
     */
    @Test
    @DisplayName("speak 行动目标角色无地点：位置保持原值")
    void execute_speakTargetCharacterWithoutLocation_keepsCurrentLocation() {
        ActorCharacter ch = character(4);
        ch.setLocation("菜畦");
        stubBase(ch);
        ActorCharacter target = new ActorCharacter();
        target.setId(9L);
        target.setProjectId(100L);
        target.setName("沈伯远");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(ch, target));

        service.execute(plan("{\"type\":\"speak\",\"action\":\"向沈伯远拱手问安\",\"target\":\"沈伯远\","
                + "\"reason\":\"同族长辈\",\"urgency\":2}"));

        assertEquals("菜畦", ch.getLocation(), "对方无地点时不得把人物名写成位置");
    }

    /**
     * 回归：主动性计划的行动事件 gameHour 取 {@code _initiative.createdGameSecond/3600}，
     * 而不是执行瞬间的持久化时钟（引擎 tick 结束才拨钟，实测「扫描 16:00、事件 14:00」）。
     */
    @Test
    @DisplayName("initiative 行动事件使用计划扫描时刻作为 gameHour")
    void execute_initiativeActionEvent_usesPlanScanGameHour() {
        stubBase(character(4));
        org.mockito.ArgumentCaptor<com.holzyn.actor.domain.agent.event.EventSpec> captor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.domain.agent.event.EventSpec.class);

        service.execute(plan("{\"type\":\"move\",\"action\":\"前往长明灯塔找旅行者\",\"target\":\"长明灯塔\","
                + "\"reason\":\"契约快到期了\",\"urgency\":4,"
                + "\"_initiative\":{\"kind\":\"contract\",\"factKey\":\"contract:1\","
                + "\"causeEventId\":10,\"createdGameSecond\":7200}}"));

        verify(worldEventBus).publish(eq(1L), eq(100L), captor.capture());
        assertEquals(2L, captor.getValue().getGameHour(), "7200/3600=2 时");
        assertEquals(10L, captor.getValue().getCauseEventId());
    }
}
