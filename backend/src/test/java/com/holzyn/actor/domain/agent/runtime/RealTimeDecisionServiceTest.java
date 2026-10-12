package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时行为决策服务单元测试（实时行为决策系统 M1，domain/agent/runtime，同包）。
 * <p>职责：验证——① 触发对象三层解析（当事人/同地点在场/global 全局，去重与删除角色跳过）；
 * ② 刺激检测三源入口（事件重要度阈值 / kind=action 类型映射 / urgency 升档[升档才触发] /
 * onNpcApproach 同地点触发且排除移动者）；③ 仲裁护栏（每角色每刺激源游戏时间冷却 + 紧急突破 /
 * 预算只读预检[引擎窗口耗尽跳过、无窗口放行]）；④ 即时决策上下文（STIMULUS 触发源 +
 * stimulusType/stimulusText/reason/situation 携带）与异步触发（awaitIdle 排空断言）。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径_V1.0》§4.2/§4.6/§7 M1。
 * ⚠️ 项目 Mockito 特性：未打桩的 Long 返回方法默认返回 0（非 null）——BudgetGuard.budgetTotal
 * 必须显式打桩 -1（无窗口）为默认，避免被误判为「窗口存在且预算耗尽」。</p>
 */
class RealTimeDecisionServiceTest {

    private RealtimeDecisionProperties props;
    private WorldEventBus worldEventBus;
    private AgentRuntime agentRuntime;
    private BudgetGuard budgetGuard;
    private ActorCharacterRepository characterRepository;
    private ActorNpcStateRepository npcStateRepository;
    private InterruptRecoveryService interruptRecoveryService;
    private PersonalityInjectionService personalityInjectionService;
    private RealtimeDecisionActivationService activationService;
    private RealTimeDecisionService service;

    /** 异步触发调用记录（trigger 的 ctx 快照） */
    private final List<DecisionContext> triggeredCtxs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        worldEventBus = Mockito.mock(WorldEventBus.class);
        agentRuntime = Mockito.mock(AgentRuntime.class);
        budgetGuard = Mockito.mock(BudgetGuard.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcStateRepository = Mockito.mock(ActorNpcStateRepository.class);
        interruptRecoveryService = Mockito.mock(InterruptRecoveryService.class);
        personalityInjectionService = Mockito.mock(PersonalityInjectionService.class);
        // M6 实时决策激活闸门：默认激活（存量用例语义不变：世界在运行）；未激活用例单独打桩 false
        activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        when(activationService.isActive(any())).thenReturn(true);

        // G1a：构造器末参为 NpcTypeResolver（npc:{id} 不再编码类型，类型查 actor_profile.type）
        service = new RealTimeDecisionService(props, worldEventBus, agentRuntime, budgetGuard, characterRepository,
                activationService, new NpcTypeResolver(characterRepository));
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("npcStateRepository");
        f.setAccessible(true);
        f.set(service, npcStateRepository);
        // M2：打断与恢复服务装配（默认不产生打断计划 ID）
        java.lang.reflect.Field f2 = RealTimeDecisionService.class.getDeclaredField("interruptRecoveryService");
        f2.setAccessible(true);
        f2.set(service, interruptRecoveryService);
        when(interruptRecoveryService.interrupt(any(), any(), any())).thenReturn(null);
        // M2：打断前探针默认返回 null（AI 不可用 → 兜底默认打断，延续 M2 行为）
        when(interruptRecoveryService.probeInterrupt(any(), any(), any(), any(), any())).thenReturn(null);
        // M3：个性注入服务装配（默认返回空串 → 情境回退 M2 纯刺激，存量用例零改动）
        java.lang.reflect.Field f3 = RealTimeDecisionService.class.getDeclaredField("personalityInjectionService");
        f3.setAccessible(true);
        f3.set(service, personalityInjectionService);
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("");

        // 默认：引擎无预算窗口（放行，由 AgentRuntime 门面 ownsTick 自开）
        when(budgetGuard.budgetTotal(any())).thenReturn(-1);
        when(budgetGuard.remaining(any())).thenReturn(0);
        // characterRepository 默认返回空列表（避免 mock 默认 null）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        // npcStateRepository 默认无行（非紧急）
        when(npcStateRepository.findByProjectIdAndNpcRef(any(), any())).thenReturn(java.util.Optional.empty());
        // agentRuntime.trigger 记录 ctx 并返回成功结果
        Mockito.doAnswer(inv -> {
            triggeredCtxs.add(inv.getArgument(2));
            return AgentResult.success(inv.getArgument(0), inv.getArgument(1), null, List.of(), "mock");
        }).when(agentRuntime).trigger(any(), any(), any());
    }

    private ActorCharacter newChar(Long id, String name, String location, Long locationId) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setName(name);
        ch.setLocation(location);
        ch.setLocationId(locationId);
        ch.setDeleted(0);
        return ch;
    }

    private ActorEvent newEvent(Long projectId, String kind, Integer importance, String characterIds,
                                Long locationId, String locationName, String visibility) {
        ActorEvent e = new ActorEvent();
        e.setProjectId(projectId);
        e.setKind(kind);
        e.setImportance(importance);
        e.setCharacterIds(characterIds);
        e.setLocationId(locationId);
        e.setLocationName(locationName);
        e.setVisibility(visibility);
        return e;
    }

    // ==================== 触发对象三层解析（静态） ====================

    @Test
    void resolveEventTargets_participantsFirstThenLocationThenGlobal() {
        List<ActorCharacter> chars = List.of(
                newChar(1L, "甲", "王都酒馆", 10L),
                newChar(2L, "乙", "城东市场", 11L),
                newChar(3L, "丙", "王都酒馆", 10L));
        // location 可见性事件：当事人 special:1 + 同地点(王都酒馆) special:3
        ActorEvent e = newEvent(1L, "world", 4, "npc:1", 10L, "王都酒馆", "location");
        List<String> targets = RealTimeDecisionService.resolveEventTargets(e, chars);
        assertEquals(List.of("npc:1", "npc:3"), targets, "当事人优先，其次同地点在场，非 global 不含全部");
    }

    @Test
    void resolveEventTargets_globalAddsAllOthers() {
        List<ActorCharacter> chars = List.of(
                newChar(1L, "甲", "王都酒馆", 10L),
                newChar(2L, "乙", "城东市场", 11L),
                newChar(3L, "丙", "王都酒馆", 10L));
        ActorEvent e = newEvent(1L, "world", 4, "npc:2", null, null, "global");
        List<String> targets = RealTimeDecisionService.resolveEventTargets(e, chars);
        assertEquals(List.of("npc:2", "npc:1", "npc:3"), targets,
                "global：当事人 special:2 优先，其余全部特殊 NPC 按列表序补齐");
    }

    @Test
    void resolveEventTargets_locationNameFallbackWhenNoLocationId() {
        List<ActorCharacter> chars = List.of(
                newChar(1L, "甲", "王都酒馆", null),
                newChar(2L, "乙", "城东市场", 11L));
        // 无 locationId 仅 locationName → 名称归一化匹配
        ActorEvent e = newEvent(1L, "world", 4, null, null, " 王都酒馆 ", "location");
        List<String> targets = RealTimeDecisionService.resolveEventTargets(e, chars);
        assertEquals(List.of("npc:1"), targets);
    }

    @Test
    void resolveEventTargets_skipsDeletedAndDedups() {
        ActorCharacter deleted = newChar(5L, "己", "王都酒馆", 10L);
        deleted.setDeleted(1);
        List<ActorCharacter> chars = List.of(
                newChar(1L, "甲", "王都酒馆", 10L),
                deleted);
        // 当事人与在场重叠 → 去重；已删除角色跳过
        ActorEvent e = newEvent(1L, "world", 4, "npc:1,special:5", 10L, "王都酒馆", "global");
        List<String> targets = RealTimeDecisionService.resolveEventTargets(e, chars);
        assertEquals(List.of("npc:1"), targets);
    }

    @Test
    void resolveLocationTargets_matchesLocationAndExcludesMover() {
        List<ActorCharacter> chars = List.of(
                newChar(1L, "甲", "王都酒馆", null),
                newChar(2L, "乙", "王都酒馆", null),
                newChar(3L, "丙", "城东市场", null));
        List<String> targets = RealTimeDecisionService.resolveLocationTargets("王都酒馆", chars, "npc:1");
        assertEquals(List.of("npc:2"), targets, "同地点且排除移动者自身");
    }

    @Test
    void eventStimulusType_mapsActionKind() {
        assertEquals(StimulusType.NPC_ACTION,
                RealTimeDecisionService.eventStimulusType(newEvent(1L, "action", 4, null, null, null, null)));
        assertEquals(StimulusType.WORLD_EVENT,
                RealTimeDecisionService.eventStimulusType(newEvent(1L, "world", 4, null, null, null, null)));
        assertEquals(StimulusType.WORLD_EVENT,
                RealTimeDecisionService.eventStimulusType(newEvent(1L, "evolution", 4, null, null, null, null)));
    }

    @Test
    void eventStimulusText_combinesTitleAndContent() {
        ActorEvent e = newEvent(1L, "world", 4, null, null, null, null);
        e.setTitle("集市异动");
        e.setContent("商贩压低声音议论");
        assertEquals("集市异动：商贩压低声音议论", RealTimeDecisionService.eventStimulusText(e));
    }

    @Test
    void coolingActive_boundaryLogic() {
        assertTrue(RealTimeDecisionService.coolingActive(1000L, 1599L, 600), "差 599 秒 < 600 → 冷却中");
        assertFalse(RealTimeDecisionService.coolingActive(1000L, 1600L, 600), "差 600 秒 = 冷却时长 → 已过");
        assertFalse(RealTimeDecisionService.coolingActive(1000L, 3000L, 600));
        assertFalse(RealTimeDecisionService.coolingActive(1000L, 3000L, 0), "冷却时长 <=0 视为不冷却");
    }

    @Test
    void locationEquals_ignoresCaseAndTrim() {
        assertTrue(RealTimeDecisionService.locationEquals(" 王都酒馆 ", "王都酒馆"));
        assertFalse(RealTimeDecisionService.locationEquals("王都酒馆", "城东市场"));
        assertFalse(RealTimeDecisionService.locationEquals(null, "王都酒馆"));
    }

    @Test
    void isGlobal_and_cooldownKey() {
        assertTrue(RealTimeDecisionService.isGlobal("GLOBAL"));
        assertFalse(RealTimeDecisionService.isGlobal("location"));
        assertFalse(RealTimeDecisionService.isGlobal(null));
        assertEquals("1:npc:2:world_event",
                RealTimeDecisionService.cooldownKey(1L, "npc:2", StimulusType.WORLD_EVENT));
    }

    // ==================== 事件触发（刺激②/③行动类） ====================

    @Test
    void onEvent_importanceAboveThresholdTriggersParticipant() {
        List<ActorCharacter> chars = List.of(newChar(1L, "甲", "王都酒馆", 10L));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0))).thenReturn(chars);
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("集市异动");

        service.onEvent(e);
        service.awaitIdle(2000);

        assertEquals(1, triggeredCtxs.size());
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("world_event", ctx.stimulusType());
        assertNotNull(ctx.stimulusText());
        assertTrue(ctx.stimulusText().contains("集市异动"));
        assertNotNull(ctx.situation(), "刺激文本同时进 situation 供思考决策");
        assertTrue(ctx.reason().startsWith("刺激：world_event："));
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:1"), any());
    }

    @Test
    void onEvent_importanceBelowThresholdSkips() {
        ActorEvent e = newEvent(1L, "world", 2, "npc:1", null, null, "location");
        service.onEvent(e);
        service.awaitIdle(500);
        assertTrue(triggeredCtxs.isEmpty(), "重要度 2 < 阈值 3 → 只记录不触发");
    }

    @Test
    void onEvent_actionKindMapsNpcActionStimulus() {
        // M4 链式默认开启：kind=action 由链式统一接管；本用例显式关闭链式验证旧 NPC_ACTION 路径仍可用
        props.setChainReactionEnabled(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "action", 4, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("甲在酒馆与乙交谈");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertEquals("npc_action", triggeredCtxs.get(0).stimulusType());
    }

    @Test
    void onEvent_actionKindExcludesActorSelf() {
        // M4 链式默认开启：kind=action 由链式统一接管；本用例显式关闭链式验证旧路径的行动者自我排除
        props.setChainReactionEnabled(false);
        // 他角色行动：行动者自身不触发（characterId=73 → special:73 被排除），仅其他在场者反应
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(73L, "甲", "王都酒馆", 10L), newChar(74L, "乙", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "action", 4, "npc:73", 10L, "王都酒馆", "location");
        e.setCharacterId(73L);
        e.setTitle("甲前往城东粮仓协助抢救");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "行动者自身被排除，仅同地点其他角色（乙 special:74）触发");
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:74"), any());
        verify(agentRuntime, never()).trigger(eq(AgentTrigger.STIMULUS), eq("npc:73"), any());
    }

    @Test
    void onEvent_actionKindSkippedWhenChainEnabled() {
        // M4 链式（Q9 用户决策）：链式默认开启 → kind=action 事件分支跳过（同地点触发由
        // ChainReactionService 统一接管，防 NPC_ACTION 与 CHAIN_REACTION 双触发精分）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L), newChar(2L, "乙", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "action", 4, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("甲在酒馆与乙交谈");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertTrue(triggeredCtxs.isEmpty(), "链式开启：kind=action 事件分支不直接触发（由链式接管）");
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    @Test
    void onEvent_globalBroadcastsToAllSpecial() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L), newChar(2L, "乙", "城东市场", 11L)));
        ActorEvent e = newEvent(1L, "world", 5, null, null, null, "global");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "global 事件触发全部特殊 NPC 即时决策");
    }

    // ==================== 冷却与预算（成本护栏） ====================

    @Test
    void onStimulus_cooldownBlocksSameSourceWithinWindow() {
        // 用 urgency 升档入口精确控制游戏秒（引擎感知 tick 粒度）：
        // 1000 秒触发 → 1300 秒（+300 < 600 冷却）同源再触发被拦截
        service.onStateUrgencyRise(1L, "npc:1", 0, 2, 1000L);
        service.awaitIdle(2000);
        service.onStateUrgencyRise(1L, "npc:1", 1, 2, 1300L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "同角色同刺激源 10 游戏分钟内第二次被冷却拦截");
    }

    @Test
    void onStimulus_cooldownExpiresAfterWindow() {
        // 1000 秒触发 → 2000 秒（+1000 > 600 冷却已过）同源再触发
        service.onStateUrgencyRise(1L, "npc:1", 0, 2, 1000L);
        service.awaitIdle(2000);
        service.onStateUrgencyRise(1L, "npc:1", 1, 2, 2000L);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "冷却期过后同源再次触发");
    }

    @Test
    void onStimulus_differentSourceNotBlockedByCooldown() {
        // 同角色：state_urgency 触发后，world_event（不同刺激源）不被同冷却键拦截
        service.onStateUrgencyRise(1L, "npc:1", 0, 2, 1000L);
        service.awaitIdle(2000);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 1300L);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "不同刺激源独立冷却");
    }

    @Test
    void onEvent_sameGameHourDedupByCooldown() {
        // 事件 gameHour 粒度（小时）：同游戏小时内同源两事件 gameSecond 相同 → 冷却防抖拦截
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e1 = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        e1.setGameHour(100L);
        ActorEvent e2 = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        e2.setGameHour(100L);
        service.onEvent(e1);
        service.awaitIdle(2000);
        service.onEvent(e2);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "同游戏小时同源事件被冷却防抖拦截");
        // 跨游戏小时（差 2h > 600s）→ 允许再次触发
        ActorEvent e3 = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        e3.setGameHour(102L);
        service.onEvent(e3);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "跨游戏小时冷却已过允许再次触发");
    }

    @Test
    void onStimulus_urgentBypassesCooldown() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        // 角色当前 urgency=3（健康告急）→ 冷却可突破
        ActorNpcState st = new ActorNpcState();
        st.setProjectId(1L);
        st.setNpcRef("npc:1");
        st.setUrgencyLevel(3);
        when(npcStateRepository.findByProjectIdAndNpcRef(eq(1L), eq("npc:1")))
                .thenReturn(java.util.Optional.of(st));
        ActorEvent e1 = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        e1.setGameHour(100L);
        ActorEvent e2 = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        e2.setGameHour(105L);
        service.onEvent(e1);
        service.awaitIdle(2000);
        service.onEvent(e2);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "紧急状态（urgency≥2）突破冷却");
    }

    @Test
    void onStimulus_budgetWindowExhaustedSkips() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        // 引擎 tick 窗口存在且预算耗尽 → 跳过（与周期通道共享窗口，护栏生效）
        when(budgetGuard.budgetTotal(1L)).thenReturn(5);
        when(budgetGuard.remaining(1L)).thenReturn(0);
        ActorEvent e = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        service.onEvent(e);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "预算窗口内耗尽 → 跳过即时决策");
    }

    @Test
    void onStimulus_noBudgetWindowPasses() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        // 无窗口（引擎空闲）→ 放行（AgentRuntime 门面 ownsTick 自开）
        when(budgetGuard.budgetTotal(1L)).thenReturn(-1);
        ActorEvent e = newEvent(1L, "world", 4, "npc:1", null, null, "location");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
    }

    @Test
    void onStimulus_physiologicalUrgeFlowsToTrigger() {
        // 生理需求危险区打断（2026-10-02 生理需求耦合轮）：PHYSIOLOGICAL_URGE 走标准
        // 刺激链路（冷却/预算/打断/异步触发），决策上下文携带 physiological_urge 标识
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        when(budgetGuard.budgetTotal(1L)).thenReturn(-1);
        service.onStimulus(1L, "npc:1", StimulusType.PHYSIOLOGICAL_URGE,
                "饿得发慌了，必须马上找东西吃", 3600L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("physiological_urge", ctx.stimulusType(), "刺激类型编码稳定");
        assertEquals("饿得发慌了，必须马上找东西吃", ctx.stimulusText(), "刺激文本完整进上下文");
        assertTrue(ctx.reason().contains("physiological_urge"));
    }

    @Test
    void onStimulus_crowdRefIgnored() {
        // G1a：类型不再由引用前缀编码——npc:12 需查 actor_profile.type=common 才会被按普通型忽略
        ActorCharacter common = new ActorCharacter();
        common.setId(12L);
        common.setProjectId(1L);
        common.setType("common");
        when(characterRepository.findById(12L)).thenReturn(java.util.Optional.of(common));
        service.onStimulus(1L, "npc:12", StimulusType.WORLD_EVENT, "刺激", 100L);
        service.awaitIdle(500);
        assertTrue(triggeredCtxs.isEmpty(), "普通型角色不触发即时决策（走分层概率化）");
    }

    @Test
    void onStimulus_disabledSkipsAll() {
        props.setEnabled(false);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "刺激", 100L);
        service.awaitIdle(500);
        assertTrue(triggeredCtxs.isEmpty());
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    // ==================== urgency 升档（刺激①） ====================

    @Test
    void onStateUrgencyRise_riseTriggersRiseOnly() {
        service.onStateUrgencyRise(1L, "npc:1", 0, 2, 3600L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("state_urgency", ctx.stimulusType());
        assertTrue(ctx.stimulusText().contains("强烈"), "刺激文本含级别描述");
        assertEquals(3600L, ctx.gameSecond());

        triggeredCtxs.clear();
        service.onStateUrgencyRise(1L, "npc:1", 2, 1, 7200L);
        service.onStateUrgencyRise(1L, "npc:1", 2, 2, 10800L);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "降档/持平不触发（行为连续性）");
    }

    // ==================== 他角色靠近（刺激③位置） ====================

    @Test
    void onNpcApproach_triggersOthersAtSameLocation() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", null),
                        newChar(2L, "乙", "王都酒馆", null),
                        newChar(3L, "丙", "城东市场", null)));
        service.onNpcApproach(1L, "npc:1", "王都酒馆", 5000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "同地点其他角色（乙）触发，移动者（甲）与异地点（丙）不触发");
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("npc_approach", ctx.stimulusType());
        assertTrue(ctx.stimulusText().contains("甲来到了王都酒馆"));
        assertEquals(5000L, ctx.gameSecond());
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:2"), any());
    }

    @Test
    void onNpcApproach_noOthersAtLocationNoTrigger() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", null)));
        service.onNpcApproach(1L, "npc:1", "王都酒馆", 5000L);
        service.awaitIdle(500);
        assertTrue(triggeredCtxs.isEmpty(), "无其他在场角色 → 不触发");
    }

    // ==================== 事件→触发对象静态解析补充 ====================

    @Test
    void resolveEventTargets_emptyEventOrNullCharsSafe() {
        assertTrue(RealTimeDecisionService.resolveEventTargets(null, List.of()).isEmpty());
        assertTrue(RealTimeDecisionService.resolveEventTargets(newEvent(1L, "world", 4, null, null, null, null), null).isEmpty());
    }

    @Test
    void urgencyLevelText_clamps() {
        assertEquals("正常", RealTimeDecisionService.urgencyLevelText(0));
        assertEquals("强烈", RealTimeDecisionService.urgencyLevelText(2));
        assertEquals("健康告急", RealTimeDecisionService.urgencyLevelText(3));
        assertEquals("健康告急", RealTimeDecisionService.urgencyLevelText(99), "越界钳制到最高档");
    }

    // ==================== M2 打断与恢复接线（打断记录 + interruptedPlanId 回填） ====================

    @Test
    void onStimulus_setsInterruptedPlanIdFromInterruptService() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        // 打断服务返回被打断计划 ID → 回填 DecisionContext.interruptedPlanId（M2 预留字段启用）
        when(interruptRecoveryService.interrupt(eq(1L), eq("npc:1"), eq(36000L))).thenReturn(42L);
        ActorEvent e = newEvent(1L, "world", 4, "npc:1", 10L, "王都酒馆", "location");
        e.setGameHour(10L);
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertEquals(Long.valueOf(42L), triggeredCtxs.get(0).interruptedPlanId(), "被打断计划 ID 回填 ctx");
        verify(interruptRecoveryService).interrupt(eq(1L), eq("npc:1"), eq(36000L));
    }

    @Test
    void onStimulus_interruptFailureIsolated() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));        // 打断记录失败 → 隔离继续（即时决策照常触发，interruptedPlanId 为 null）
        when(interruptRecoveryService.interrupt(any(), any(), any()))
                .thenThrow(new RuntimeException("打断记录失败"));
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "打断失败不阻断即时决策");
        assertNull(triggeredCtxs.get(0).interruptedPlanId());
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:1"), any());
    }

    @Test
    void onStimulus_interruptServiceNotWiredCtxNull() throws Exception {
        // 未装配打断服务（字段 null）：即时决策照常，interruptedPlanId 恒 null（行为回 M1）
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("interruptRecoveryService");
        f.setAccessible(true);
        f.set(service, null);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertNull(triggeredCtxs.get(0).interruptedPlanId());
    }

    // ==================== M2 冒烟抓修：防重入自动重试（多角色并发刺激闭环） ====================

    @Test
    void triggerWithRetry_retriesWhenReentrantThenSucceeds() {
        props.setStimulusRetryMaxAttempts(3);
        props.setStimulusRetryWaitMs(5);
        // 第一次 reentrant（被其他决策占用），第二次成功
        Mockito.when(agentRuntime.trigger(any(), any(), any()))
                .thenReturn(AgentResult.reentrant(AgentTrigger.STIMULUS, "npc:1"))
                .thenReturn(AgentResult.success(AgentTrigger.STIMULUS, "npc:1", null, List.of(), "ok"));
        DecisionContext ctx = DecisionContext.builder().projectId(1L).build();
        AgentResult result = service.triggerWithRetry(1L, "npc:1", ctx);
        assertTrue(result.success(), "重试后成功");
        Mockito.verify(agentRuntime, Mockito.times(2)).trigger(any(), any(), any());
    }

    @Test
    void triggerWithRetry_retryExhaustedReturnsLastReentrant() {
        props.setStimulusRetryMaxAttempts(2);
        props.setStimulusRetryWaitMs(2);
        // 始终 reentrant → 重试耗尽返回最后一次 reentrant（不抛异常）
        Mockito.when(agentRuntime.trigger(any(), any(), any()))
                .thenReturn(AgentResult.reentrant(AgentTrigger.STIMULUS, "npc:1"));
        AgentResult result = service.triggerWithRetry(1L, "npc:1",
                DecisionContext.builder().projectId(1L).build());
        assertTrue(result.reentrant());
        // 初始 1 次 + 重试 2 次 = 3 次
        Mockito.verify(agentRuntime, Mockito.times(3)).trigger(any(), any(), any());
    }

    @Test
    void triggerWithRetry_zeroAttemptsNoRetry() {
        props.setStimulusRetryMaxAttempts(0);
        Mockito.when(agentRuntime.trigger(any(), any(), any()))
                .thenReturn(AgentResult.reentrant(AgentTrigger.STIMULUS, "npc:1"));
        AgentResult result = service.triggerWithRetry(1L, "npc:1",
                DecisionContext.builder().projectId(1L).build());
        assertTrue(result.reentrant());
        Mockito.verify(agentRuntime, Mockito.times(1)).trigger(any(), any(), any());
    }

    // ==================== M3 个性注入 + 社交差异化（打断前探针 + 个性情境） ====================

    @Test
    void onStimulus_p0ProbeFalseSkipsInterruptAndTrigger() {
        // P0 常规刺激（无重要度）：探针判断「不打断」→ 不记录打断、不触发即时决策（真人感「不理会」）
        when(interruptRecoveryService.probeInterrupt(eq(1L), eq("npc:1"), eq(36000L),
                eq("集市异动"), any())).thenReturn(Boolean.FALSE);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertTrue(triggeredCtxs.isEmpty(), "探针 FALSE：不触发即时决策");
        verify(interruptRecoveryService, never()).interrupt(any(), any(), any());
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    @Test
    void onStimulus_p0ProbeTrueProceedsToInterruptAndTrigger() {
        when(interruptRecoveryService.probeInterrupt(eq(1L), eq("npc:1"), eq(36000L),
                eq("集市异动"), any())).thenReturn(Boolean.TRUE);
        when(interruptRecoveryService.interrupt(eq(1L), eq("npc:1"), eq(36000L))).thenReturn(42L);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "探针 TRUE：继续打断+触发");
        assertEquals(Long.valueOf(42L), triggeredCtxs.get(0).interruptedPlanId());
    }

    @Test
    void onStimulus_p0ProbeUnavailableFallsBackToInterrupt() {
        // 探针 AI 不可用（null）→ 兜底默认打断（M3 决策：延续 M2 行为安全性优先）
        when(interruptRecoveryService.probeInterrupt(any(), any(), any(), any(), any())).thenReturn(null);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "探针 null → 兜底默认打断继续");
        verify(interruptRecoveryService).interrupt(eq(1L), eq("npc:1"), eq(36000L));
    }

    @Test
    void onEvent_importanceAboveForceThresholdSkipsProbe() {
        // P2 重大事件（重要度≥4）：跳过探针强制打断（共性框架 P2 安全第一）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 4, "npc:1", 10L, "王都酒馆", "location");
        e.setGameHour(10L);
        service.onEvent(e);
        service.awaitIdle(2000);
        verify(interruptRecoveryService, never()).probeInterrupt(any(), any(), any(), any(), any());
        assertEquals(1, triggeredCtxs.size());
        verify(interruptRecoveryService).interrupt(eq(1L), eq("npc:1"), eq(36000L));
    }

    @Test
    void onEvent_importance3GoesProbePath() {
        // P0/P1（重要度 3）：走探针（返回 null 兜底默认打断）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setGameHour(10L);
        service.onEvent(e);
        service.awaitIdle(2000);
        verify(interruptRecoveryService).probeInterrupt(eq(1L), eq("npc:1"), eq(36000L), any(), any());
        assertEquals(1, triggeredCtxs.size());
    }

    @Test
    void onStimulus_personalitySegmentInjectedIntoSituation() {
        when(interruptRecoveryService.probeInterrupt(any(), any(), any(), any(), any())).thenReturn(null);
        when(personalityInjectionService.buildPersonalitySegment(eq(1L), eq("npc:1"), eq(36000L), any()))
                .thenReturn("我是甲，稳重隐忍；与乙：好友【好感80/信任75/信任】");
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        String situation = triggeredCtxs.get(0).situation();
        assertTrue(situation.contains("【角色个性】"), "个性段进情境");
        assertTrue(situation.contains("我是甲，稳重隐忍"), "个性段内容");
        assertTrue(situation.contains("【本次刺激】"), "刺激段");
        assertTrue(situation.contains("集市异动"));
        assertTrue(situation.contains("【决策要求】"), "决策要求指令");
        // 刺激原文字段不受个性注入影响（独立字段）
        assertEquals("集市异动", triggeredCtxs.get(0).stimulusText());
    }

    @Test
    void onStimulus_personalityEmptyKeepsM2Situation() {
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("");
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertEquals("集市异动", triggeredCtxs.get(0).situation(), "个性段为空 → 情境=纯刺激（M2 行为）");
    }

    @Test
    void onStimulus_personalityServiceNotWiredFallsBack() throws Exception {
        // 个性服务未装配（字段 null）→ 情境回退纯刺激（行为回 M2）
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("personalityInjectionService");
        f.setAccessible(true);
        f.set(service, null);
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertEquals("集市异动", triggeredCtxs.get(0).situation());
    }

    @Test
    void onEvent_relatedNamesResolvedForSocialDifferentiation() {
        // 事件当事人 special:4（温叙）→ 个性段相关人物=温叙（社交差异化：相关人物关系优先注入）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L), newChar(4L, "温叙", "医馆", 12L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:4", 12L, "医馆", "location");
        e.setGameHour(10L);
        service.onEvent(e);
        service.awaitIdle(2000);
        verify(personalityInjectionService).buildPersonalitySegment(eq(1L), eq("npc:4"), eq(36000L),
                org.mockito.ArgumentMatchers.argThat(names -> names != null && names.contains("温叙")));
        assertEquals(1, triggeredCtxs.size(), "当事人温叙触发（非 global 不扩散）");
    }

    @Test
    void onEvent_probeDisabledSkipsProbeButStillTriggers() {
        props.setInterruptProbeEnabled(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setGameHour(10L);
        service.onEvent(e);
        service.awaitIdle(2000);
        verify(interruptRecoveryService, never()).probeInterrupt(any(), any(), any(), any(), any());
        assertEquals(1, triggeredCtxs.size(), "探针开关关：直接走 M2 打断+触发");
    }

    // ==================== M6 实时决策激活闸门（默认关；未激活不触发） ====================

    @Test
    void onEvent_inactiveProjectSkipsAllTrigger() {
        // 激活闸门：项目未激活（默认关）→ 即使重要度达标也不触发
        when(activationService.isActive(eq(1L))).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 5, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("重大异动");
        service.onEvent(e);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "未激活项目：刺激不触发即时决策");
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    @Test
    void onEvent_activeProjectTriggersNormally() {
        // 激活闸门：项目已激活 → 正常触发（回扫既有语义）
        when(activationService.isActive(eq(1L))).thenReturn(true);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("集市异动");
        service.onEvent(e);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "已激活项目：正常触发");
    }

    @Test
    void onNpcApproach_inactiveProjectSkips() {
        when(activationService.isActive(eq(1L))).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L), newChar(2L, "乙", "王都酒馆", 10L)));
        service.onNpcApproach(1L, "npc:1", "王都酒馆", 1000L);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "未激活项目：靠近刺激不触发");
    }

    @Test
    void onStateUrgencyRise_inactiveProjectSkips() {
        when(activationService.isActive(eq(1L))).thenReturn(false);
        when(npcStateRepository.findByProjectIdAndNpcRef(eq(1L), eq("npc:1"))).thenReturn(java.util.Optional.empty());
        service.onStateUrgencyRise(1L, "npc:1", 0, 2, 1000L);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "未激活项目：urgency 升档刺激不触发");
    }

    // ==================== M4 链式反应（onChainReaction）+ 独处段注入 ====================

    @Test
    void onChainReaction_triggersFullStimulusWithRelatedNames() {
        // 链式反应：ChainReactionService 调用 → CHAIN_REACTION 刺激 → 完整即时决策管线
        // （非紧急常规刺激 importance=null → 走探针；相关人物=行动者名，社交差异化关系注入）
        when(interruptRecoveryService.probeInterrupt(any(), any(), any(), any(), any())).thenReturn(null);
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("我是乙");
        service.onChainReaction(1L, "npc:2", "你看到甲在村口：召集村民清点粮仓", 36000L,
                java.util.List.of("甲"));
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        DecisionContext ctx = triggeredCtxs.get(0);
        assertEquals("chain_reaction", ctx.stimulusType(), "链式反应刺激类型编码");
        assertTrue(ctx.stimulusText().contains("你看到甲在村口"));
        assertTrue(ctx.situation().contains("我是乙"), "个性段进情境");
        // 相关人物=行动者名传入个性段组装（社交差异化数据基础）
        verify(personalityInjectionService).buildPersonalitySegment(eq(1L), eq("npc:2"), eq(36000L),
                org.mockito.ArgumentMatchers.argThat(names -> names != null && names.contains("甲")));
        verify(agentRuntime).trigger(eq(AgentTrigger.STIMULUS), eq("npc:2"), any());
    }

    @Test
    void onChainReaction_inactiveProjectSkips() {
        when(activationService.isActive(eq(1L))).thenReturn(false);
        service.onChainReaction(1L, "npc:2", "你看到甲在村口：召集村民", 36000L, java.util.List.of("甲"));
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "未激活项目：链式反应不触发");
    }

    @Test
    void onStimulus_solitudeSegmentAppendedWhenAlone() throws Exception {
        // M4 独处（Q10 用户决策）：独处判定通过 → 情境追加【独处】段（AI 决定独处行为）
        SolitudeService solitudeService = Mockito.mock(SolitudeService.class);
        when(solitudeService.solitudeSegment(eq(1L), eq("npc:1")))
                .thenReturn("【独处】此刻四周没有旁人，只有你独自一人");
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("solitudeService");
        f.setAccessible(true);
        f.set(service, solitudeService);
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("");
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertTrue(triggeredCtxs.get(0).situation().contains("【独处】"),
                "独处时情境含【独处】段");
        assertTrue(triggeredCtxs.get(0).situation().contains("集市异动"), "刺激原文仍保留");
    }

    @Test
    void onStimulus_solitudeSegmentSkippedWhenNotAlone() throws Exception {
        // 非独处（服务返回空串）→ 情境不含【独处】段（行为回到 M3）
        SolitudeService solitudeService = Mockito.mock(SolitudeService.class);
        when(solitudeService.solitudeSegment(any(), any())).thenReturn("");
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("solitudeService");
        f.setAccessible(true);
        f.set(service, solitudeService);
        when(personalityInjectionService.buildPersonalitySegment(any(), any(), any(), any())).thenReturn("");
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 36000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size());
        assertEquals("集市异动", triggeredCtxs.get(0).situation(), "非独处 → 纯刺激情境");
    }

    @Test
    void onStimulus_environmentUsesDedicatedLongerCooldown() {
        // M5 环境刺激护栏①：ENVIRONMENT 独立冷却键 + 独立时长（默认 30 游戏分钟 vs 普通 10 分钟）——
        // 同角色 600 秒（10 分钟）后：环境刺激仍在 30 分钟冷却内被拦截，普通刺激已过冷却可触发
        props.setEnvironmentCooldownGameMinutes(30);
        props.setCooldownGameMinutes(10);
        service.onStimulus(1L, "npc:1", StimulusType.ENVIRONMENT, "天气变化：晴 → 暴雨", 1000L);
        service.awaitIdle(2000);
        // 差 600 秒 = 普通冷却边界：环境刺激仍拦截（30 分钟窗口未过）
        service.onStimulus(1L, "npc:1", StimulusType.ENVIRONMENT, "天气变化：暴雨 → 雾", 1600L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "ENVIRONMENT 独立冷却 30 分钟内第二次被拦截");
        // 普通刺激独立键不受环境冷却影响
        service.onStimulus(1L, "npc:1", StimulusType.WORLD_EVENT, "集市异动", 1600L);
        service.awaitIdle(2000);
        assertEquals(2, triggeredCtxs.size(), "普通刺激独立冷却键不被环境冷却挤占");
        // 环境冷却过期后再次触发（30 分钟 = 1800 秒）
        service.onStimulus(1L, "npc:1", StimulusType.ENVIRONMENT, "天气变化：雾 → 晴", 1000L + 1800L);
        service.awaitIdle(2000);
        assertEquals(3, triggeredCtxs.size(), "环境冷却过后允许再次触发");
    }

    // ==================== M5.5 微层接线：AI 强度档位（实时决策） ====================

    /** 构造档位闸门（mock 引擎行）并注入服务。 */
    private AiIntensityGate gateOf(String level) throws Exception {
        ActorWorldEngineRepository repo = Mockito.mock(ActorWorldEngineRepository.class);
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(1L);
        e.setAiIntensity(level);
        when(repo.findByProjectId(1L)).thenReturn(java.util.Optional.of(e));
        AiIntensityGate gate = new AiIntensityGate(repo);
        java.lang.reflect.Field f = RealTimeDecisionService.class.getDeclaredField("aiIntensityGate");
        f.setAccessible(true);
        f.set(service, gate);
        return gate;
    }

    /**
     * programmatic 档：跳过打断前 AI 探针（零 AI——P0/P1 走兜底默认打断），
     * 即时决策仍触发（由 AgentRuntime 程序化决策承接）。
     */
    @Test
    void onEvent_programmaticSkipsProbeButStillTriggers() throws Exception {
        gateOf("programmatic");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("集市异动");

        service.onEvent(e);
        service.awaitIdle(2000);

        verify(interruptRecoveryService, never()).probeInterrupt(any(), any(), any(), any(), any());
        assertEquals(1, triggeredCtxs.size(), "programmatic 档跳过探针但仍触发即时决策（程序化兜底）");
    }

    /** full 档（标准）：P0/P1 仍走打断前 AI 探针（回归）。 */
    @Test
    void onEvent_fullStillProbes() throws Exception {
        gateOf("full");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", 10L)));
        ActorEvent e = newEvent(1L, "world", 3, "npc:1", 10L, "王都酒馆", "location");
        e.setTitle("集市异动");

        service.onEvent(e);
        service.awaitIdle(2000);

        verify(interruptRecoveryService, Mockito.times(1))
                .probeInterrupt(any(), any(), any(), any(), any());
        assertEquals(1, triggeredCtxs.size());
    }

    /** light 档：共享引擎窗口只放行前半（预算减半语义）——窗口后半跳过，前半放行。 */
    @Test
    void onNpcApproach_lightOnlyUsesFirstHalfOfSharedWindow() throws Exception {
        gateOf("light");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", null),
                        newChar(2L, "乙", "王都酒馆", null)));
        // 窗口总量 10：剩余 4（≤ceil(10×0.5)=5）→ light 档跳过
        when(budgetGuard.budgetTotal(1L)).thenReturn(10);
        when(budgetGuard.remaining(1L)).thenReturn(4);
        service.onNpcApproach(1L, "npc:1", "王都酒馆", 5000L);
        service.awaitIdle(1000);
        assertTrue(triggeredCtxs.isEmpty(), "light 档共享窗口后半（剩余≤一半）跳过即时决策");
        // 剩余 6（>5）→ light 档放行
        when(budgetGuard.remaining(1L)).thenReturn(6);
        service.onNpcApproach(1L, "npc:1", "王都酒馆", 6000L);
        service.awaitIdle(2000);
        assertEquals(1, triggeredCtxs.size(), "light 档共享窗口前半放行即时决策");
    }

    /** max 档：预算窗口耗尽也放行（全 AI 必调无预算限制）。 */
    @Test
    void onNpcApproach_maxPassesBudgetPreCheck() throws Exception {
        gateOf("max");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", null),
                        newChar(2L, "乙", "王都酒馆", null)));
        when(budgetGuard.budgetTotal(1L)).thenReturn(10);
        when(budgetGuard.remaining(1L)).thenReturn(0);

        service.onNpcApproach(1L, "npc:1", "王都酒馆", 5000L);
        service.awaitIdle(2000);

        assertEquals(1, triggeredCtxs.size(), "max 档预算耗尽仍放行即时决策（全 AI 必调）");
    }

    /** programmatic 档：预算窗口耗尽也放行（程序化决策不耗预算）。 */
    @Test
    void onNpcApproach_programmaticPassesBudgetPreCheck() throws Exception {
        gateOf("programmatic");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "王都酒馆", null),
                        newChar(2L, "乙", "王都酒馆", null)));
        when(budgetGuard.budgetTotal(1L)).thenReturn(10);
        when(budgetGuard.remaining(1L)).thenReturn(0);

        service.onNpcApproach(1L, "npc:1", "王都酒馆", 5000L);
        service.awaitIdle(2000);

        assertEquals(1, triggeredCtxs.size(), "programmatic 档预算耗尽仍放行（决策零 AI 不耗预算）");
    }
}
