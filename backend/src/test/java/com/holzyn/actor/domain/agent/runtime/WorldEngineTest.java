package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.service.WorldEventService;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldEngine（B3 F3 世界推进引擎）单元测试（与实现同包，domain/agent/runtime）。
 * <p>职责：验证纯逻辑（持续运行开关判定/回补换算/步长封顶/程序化活动/机制事件草稿/标题提取）
 * 与推进编排（暂停全停/未运行不推进/逐时人群/封顶回补/预算耗尽降级程序化/事件仅跨日生成/
 * 跨日事件双通道发布+预算内注入/首次基准 idle/旧库 lastSimTime 兼容回补/感知用引擎游戏时刻）。</p>
 * <p>测试要点对齐闭环评估 §7.3：running=0 不推进、paused 不推进、逐时推进普通 NPC 中间时刻状态
 * 正确、封顶回补、预算耗尽降级程序化、事件仅跨日生成。</p>
 */
class WorldEngineTest {

    private ActorWorldClockRepository clockRepository;
    private ActorProjectRepository projectRepository;
    private ActorConversationRepository conversationRepository;
    private ActorCharacterRepository characterRepository;
    private ActorWorldEngineRepository engineRepository;
    private WorldLocationRepository locationRepository;
    private WorldClockService worldClockService;
    private OrdinaryNpcService ordinaryNpcService;
    private WorldEventService worldEventService;
    private AgentRuntime agentRuntime;
    private PerceptionService perceptionService;
    private WorldEventBus worldEventBus;
    private BudgetGuard budgetGuard;
    private com.holzyn.actor.domain.agent.roleplay.AutonomousChatService autonomousChatService;
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;
    private com.holzyn.actor.domain.world.service.InfoDiffusionService infoDiffusionService;
    /** 治理批次 0 · 卡4-A：作息运行态访问器（actor_state 同步断言；mock 在 setUp 创建，
     *  注入在 G-ENGINE-ACT-01 测试内做——修复前 WorldEngine 无此字段，避免破坏其他测试） */
    private com.holzyn.actor.domain.npc.state.service.NpcActivityStateService npcActivityStateService;
    private WorldEngine engine;

    @BeforeEach
    void setUp() {
        clockRepository = Mockito.mock(ActorWorldClockRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        ordinaryNpcService = Mockito.mock(OrdinaryNpcService.class);
        worldEventService = Mockito.mock(WorldEventService.class);
        agentRuntime = Mockito.mock(AgentRuntime.class);
        perceptionService = Mockito.mock(PerceptionService.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        budgetGuard = new BudgetGuard();
        engine = new WorldEngine(clockRepository, projectRepository, conversationRepository,
                characterRepository, engineRepository, locationRepository, worldClockService,
                ordinaryNpcService, worldEventService, agentRuntime, perceptionService, worldEventBus, budgetGuard);
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(engine, "simEnabled", true);
        ReflectionTestUtils.setField(engine, "aiPolishEnabled", true);
        ReflectionTestUtils.setField(engine, "maxAiCalls", 5);
        ReflectionTestUtils.setField(engine, "maxCatchUpSeconds", 86400);
        // M1（2026-09-03）：感知周期 tick 粒度（默认 5 游戏分钟=300 游戏秒）
        ReflectionTestUtils.setField(engine, "tickGameMinutes", 5);
        // R1（2026-09-28）：前瞻预热默认值（生产默认 true/15 游戏分钟）
        ReflectionTestUtils.setField(engine, "lookaheadEnabled", true);
        ReflectionTestUtils.setField(engine, "lookaheadGameMinutes", 15);
        // D4 F12 无玩家群聊（字段注入）：默认注入 mock + 开关开，由各测试按需断言
        autonomousChatService = Mockito.mock(com.holzyn.actor.domain.agent.roleplay.AutonomousChatService.class);
        ReflectionTestUtils.setField(engine, "autonomousChatService", autonomousChatService);
        ReflectionTestUtils.setField(engine, "autonomousChatEnabled", true);
        // M1 感知周期挂钩（字段注入）：默认注入 mock，由各测试按需断言（advance 每 tick 调用）
        npcStateService = Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcStateService.class);
        ReflectionTestUtils.setField(engine, "npcStateService", npcStateService);
        // M5 信息包扩散挂钩（字段注入）：默认注入 mock，由各测试按需断言（每游戏小时边界调用）
        infoDiffusionService = Mockito.mock(com.holzyn.actor.domain.world.service.InfoDiffusionService.class);
        ReflectionTestUtils.setField(engine, "infoDiffusionService", infoDiffusionService);
        // 治理批次 0 · 卡4-A：作息运行态访问器 mock（字段注入在 G-ENGINE-ACT-01 测试内做）
        npcActivityStateService = Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.class);
    }

    // ==================== 静态纯逻辑 ====================

    /**
     * world_running 常驻开关判定：1=开；0=关；NULL（存量行）=关（存量默认关，用户决策）；无时钟行=关。
     */
    @Test
    void isWorldRunningByField() {
        ActorWorldClock on = new ActorWorldClock();
        on.setWorldRunning(1);
        ActorWorldClock off = new ActorWorldClock();
        off.setWorldRunning(0);
        ActorWorldClock legacy = new ActorWorldClock();
        legacy.setWorldRunning(null);
        assertTrue(WorldEngine.isWorldRunning(on), "1=持续运行");
        assertFalse(WorldEngine.isWorldRunning(off), "0=不运行");
        assertFalse(WorldEngine.isWorldRunning(legacy), "存量 NULL=默认关");
        assertFalse(WorldEngine.isWorldRunning(null), "无时钟行=不运行");
    }

    /**
     * 回补换算（M1 秒级）：自 lastEngineRunAt 的真实流逝 × 速率 → 游戏秒（秒级精度，无小时截断）。
     * <p>⚠️ M1 关键验证：12.5 真实秒 × rate=24 = 300 游戏秒 = 5 游戏分钟——原「游戏小时」换算
     * （300/3600 floor=0）在 12.5s 扫描下恒为 0 导致引擎永远 idle，秒级换算后引擎能推进。</p>
     */
    @Test
    void engineElapsedGameSecondsConvertsByRate() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        LocalDateTime base = now.minusHours(1);
        assertEquals(86400L, WorldEngine.engineElapsedGameSeconds(base, now, 24), "1 真实小时×24=86400 游戏秒");
        assertEquals(7200L, WorldEngine.engineElapsedGameSeconds(base, now, 2), "rate=2：1 真实小时→7200 游戏秒");
        assertEquals(0L, WorldEngine.engineElapsedGameSeconds(base, base.minusMinutes(1), 24), "now 早于基准=0");
        assertEquals(0L, WorldEngine.engineElapsedGameSeconds(null, now, 24), "无基准=0");
        assertEquals(0L, WorldEngine.engineElapsedGameSeconds(base, now, 0), "rate<=0=0");
        // M1 核心：12.5s 扫描 × rate=24 = 300 游戏秒（5 游戏分钟），引擎不再 idle
        assertEquals(300L, WorldEngine.engineElapsedGameSeconds(now.minusSeconds(12).minusNanos(500_000_000L), now, 24),
                "12.5 真实秒×24=300 游戏秒（floor 后非 0，引擎可推进）");
        assertEquals(30L, WorldEngine.engineElapsedGameSeconds(now.minusSeconds(30), now, 1),
                "rate=1：30 秒=30 游戏秒（秒级精度，无分钟截断）");
    }

    /**
     * 单次步长封顶（M1 单位=游戏秒）：超上限截断、未超原样、无流逝 0、封顶<=0 不封顶。
     */
    @Test
    void stepOfCapsCatchUp() {
        assertEquals(86400L, WorldEngine.stepOf(86400L * 2, 86400), "超上限截断（86400 游戏秒=24 游戏小时）");
        assertEquals(300L, WorldEngine.stepOf(300, 86400), "未超上限原样");
        assertEquals(0L, WorldEngine.stepOf(0, 86400), "无流逝 0");
        assertEquals(50L, WorldEngine.stepOf(50, 0), "不封顶");
    }

    /**
     * 程序化角色状态（B3 从 WorldSimulationJob 收编迁入）：深夜歇息、白天按位置忙日常、无位置回退驻地。
     */
    @Test
    void programmaticActivityByPeriod() {
        assertTrue(WorldEngine.programmaticActivity("城东市场", 2).contains("歇息就寝"), "深夜应歇息就寝");
        assertTrue(WorldEngine.programmaticActivity("城东市场", 10).contains("城东市场"), "白天应引用位置");
        assertTrue(WorldEngine.programmaticActivity(null, 14).contains("驻地"), "无位置回退驻地");
        assertTrue(WorldEngine.programmaticActivity(null, 23).contains("歇息就寝"), "深夜无位置也应歇息");
    }

    /**
     * 机制生成跨日事件草稿（T-4 第一通道，零 AI 成本）：引用世界观地点名；无地点回退「城中」；按日轮换模板。
     */
    @Test
    void mechanismWorldEventDraftUsesLocations() {
        String draft = WorldEngine.mechanismWorldEventDraft(1, 10, List.of("城东市场", "王都酒馆"));
        assertTrue(draft.contains("城东市场"), "应引用地点名");
        assertTrue(draft.contains("第1日"), "应含游戏日序号");
        String draft2 = WorldEngine.mechanismWorldEventDraft(2, 10, List.of("城东市场", "王都酒馆"));
        assertTrue(draft2.contains("王都酒馆"), "第 2 日应轮换到下一个地点");
        String fallback = WorldEngine.mechanismWorldEventDraft(1, 22, List.of());
        assertTrue(fallback.contains("城中"), "无地点回退城中");
        assertTrue(WorldEngine.mechanismWorldEventDraft(1, 22, null).contains("城中"), "null 地点列表回退城中");
    }

    /**
     * D3 F10：机制事件选点（与草稿一致的确定性轮换）——事件携带 location_id 供按地点路由/感知过滤。
     */
    @Test
    void mechanismLocationPicksDeterministically() {
        ActorWorldLocation east = new ActorWorldLocation();
        east.setId(1L);
        east.setName("城东市场");
        ActorWorldLocation tavern = new ActorWorldLocation();
        tavern.setId(2L);
        tavern.setName("王都酒馆");
        List<ActorWorldLocation> locations = List.of(east, tavern);

        assertEquals(1L, WorldEngine.mechanismLocation(1, locations).getId(), "第 1 日选中首个地点");
        assertEquals(2L, WorldEngine.mechanismLocation(2, locations).getId(), "第 2 日轮换到下一个地点");
        assertEquals(1L, WorldEngine.mechanismLocation(3, locations).getId(), "第 3 日回到首个地点");
        assertNull(WorldEngine.mechanismLocation(1, List.of()), "空地点列表返回 null（事件不携带地点）");
        assertNull(WorldEngine.mechanismLocation(1, null), "null 地点列表返回 null");
    }

    /**
     * 事件正文 → 标题：取首行；空回退「世界事件」。
     */
    @Test
    void titleOfExtractsFirstLine() {
        assertEquals("标题", WorldEngine.titleOf("标题\n内容"));
        assertEquals("世界事件", WorldEngine.titleOf(""));
        assertEquals("世界事件", WorldEngine.titleOf(null));
    }

    // ==================== 推进编排 ====================

    /** 构造未删除项目（id=1，user=1） */
    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(1L);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    /** 构造世界时钟行（M1：last_game_second 同步初始化 = last_game_hour×3600，模拟 requireClock 惰性迁移后状态） */
    private ActorWorldClock clock(long lastGameHour, int paused, Integer worldRunning, LocalDateTime lastEngineRunAt) {
        ActorWorldClock c = new ActorWorldClock();
        c.setProjectId(1L);
        c.setRate(24);
        c.setWorldStartAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        c.setWorldStartGameHour(0L);
        c.setLastGameHour(lastGameHour);
        c.setLastGameSecond(lastGameHour * 3600L);
        c.setPaused(paused);
        c.setWorldRunning(worldRunning);
        c.setLastEngineRunAt(lastEngineRunAt);
        return c;
    }

    /** 构造引擎配置行 */
    private ActorWorldEngine engineConfig(int budget, int maxStep) {
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(1L);
        e.setBudgetPerTick(budget);
        e.setMaxStepHours(maxStep);
        return e;
    }

    /** 构造特殊角色 */
    private ActorCharacter character(long id, int importance) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName("角色" + id);
        ch.setImportance(importance);
        return ch;
    }

    /** 通用推进前置 mock（项目/时钟/引擎配置/空角色列表） */
    private void stubBase(LocalDateTime now, ActorWorldClock c) {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(worldClockService.requireClock(1L)).thenReturn(c);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engineConfig(5, 24)));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of());
    }

    /**
     * paused=1 → 跳过推进（统一暂停语义，修 C-1）；角色/人群/事件都不动。
     */
    @Test
    void pausedProjectIsSkipped() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 1, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("paused", r.get("status"));
        verify(ordinaryNpcService, never()).scheduleProgrammaticByGameHour(anyLong(), anyInt(), any());
        verify(agentRuntime, never()).trigger(any(), any(), any());
        verify(clockRepository, never()).save(any(ActorWorldClock.class));
        // P0-6 配套：跳过的路径也不得写窄列更新（时钟一行都不动）
        verify(clockRepository, never()).updateEnginePosition(any(), anyLong(), anyLong(), any(), any());
        verify(clockRepository, never()).updateEngineBase(any(), any());
    }

    /**
     * world_running=0 → 完全不推进（修 L-02/C-6）；存量 NULL 视为关由 isWorldRunning 覆盖。
     */
    @Test
    void stoppedProjectIsNotAdvanced() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 0, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("stopped", r.get("status"));
        verify(ordinaryNpcService, never()).scheduleProgrammaticByGameHour(anyLong(), anyInt(), any());
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    /**
     * 无流逝（lastEngineRunAt=now）→ idle 且刷新回补基准（避免下次仍从空基准计算）。
     */
    @Test
    void noElapsedReturnsIdleAndSavesBaseline() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now);
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("idle", r.get("status"));
        ArgumentCaptor<LocalDateTime> baseCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(clockRepository).updateEngineBase(eq(1L), baseCap.capture());
        assertEquals(now, baseCap.getValue(), "无流逝也刷新回补基准");
    }

    /**
     * 首次运行（lastEngineRunAt/lastSimTime 皆空）→ 以当前为基准，idle 不补推。
     */
    @Test
    void firstRunWithoutBaselineIsIdle() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, null);
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("idle", r.get("status"));
    }

    /**
     * 旧库兼容（B3 前活跃窗口时代只有 lastSimTime）→ 以 lastSimTime 为回补基准补推。
     */
    @Test
    void legacyClockUsesLastSimTimeAsBaseline() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, null);
        c.setLastSimTime(now.minusMinutes(30));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(12L, r.get("advancedHours"), "30 分钟×24/60=12 游戏小时");
    }

    /**
     * 逐游戏小时推进普通 NPC（修 C-9/V2-2）：60 分钟真实×24=24 游戏小时，逐时调用 24 次，
     * 游戏时刻从 1 递进到 24（引擎传入统一游戏时刻，非真实钟）。
     */
    @Test
    void advancesCrowdPerGameHour() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(24L, r.get("advancedHours"));
        assertEquals(24L, r.get("gameHour"));

        ArgumentCaptor<Long> ghCaptor = ArgumentCaptor.forClass(Long.class);
        verify(ordinaryNpcService, times(24)).scheduleProgrammaticByGameHour(eq(1L), anyInt(), ghCaptor.capture());
        List<Long> ghs = ghCaptor.getAllValues();
        assertEquals(24, ghs.size(), "逐时 24 次");
        assertEquals(24L, ghs.get(23), "最后一次推进到游戏小时 24");
        assertTrue(ghs.contains(1L) && ghs.contains(12L) && ghs.contains(24L), "逐时覆盖中间时刻");

        // 跨日边界（hour=24）恰好生成 1 条 world 事件（事件仅跨日生成）
        verify(worldEventBus, times(1)).publish(anyLong(), eq(1L), any(EventSpec.class));
    }

    /**
     * 未跨游戏日不生成世界事件（事件仅跨日生成，T-4）。
     */
    @Test
    void worldEventOnlyOnDayCrossing() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusMinutes(30)); // 12 游戏小时，未跨日
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals(12L, r.get("advancedHours"));
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any(EventSpec.class));
        verify(ordinaryNpcService, times(12)).scheduleProgrammaticByGameHour(anyLong(), anyInt(), any());
    }

    /**
     * 封顶回补（M1 秒级）：长时间离线后单次推进封顶 max_step（DB 列 max_step_hours=24 → ×3600
     * = 86400 游戏秒），防雪崩；last_game_second 落 86400、last_game_hour 派生 24。
     */
    @Test
    void catchUpIsCappedByMaxStepHours() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(48)); // 48 真实小时×24=1152 游戏小时
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(24L, r.get("advancedHours"), "封顶 24，不补 1152");
        // P0-6（2026-09-27）：推进位置改走窄列更新 updateEnginePosition（不再整实体 save）
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> hourCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), hourCap.capture(), any(), any());
        assertEquals(Long.valueOf(86400L), posCap.getValue(), "秒级推进位置=24h×3600");
        assertEquals(Long.valueOf(24L), hourCap.getValue(), "last_game_hour 兼容派生");
    }

    // ==================== M1 感知周期 tick 步进（世界演化与游戏时间系统，2026-09-03） ====================

    /**
     * M1 核心：12.5 真实秒扫描（rate=24）→ 流逝 300 游戏秒（5 游戏分钟）→ 引擎能推进（不再 idle）。
     * 状态惰性衰减每 tick 调用（NpcStateService.advance(projectId, 游戏秒)）；<1 游戏小时不触发
     * 小时级钩子（人群/特殊 NPC 都不动）。
     */
    @Test
    void engineAdvancesAtPerceptionTickGranularity() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(12).minusNanos(500_000_000L)); // 12.5 真实秒
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "12.5s 扫描下引擎能推进（不再 idle）");
        assertEquals(0L, r.get("advancedHours"), "300 游戏秒 < 1 游戏小时");
        // 秒级推进位置落库（窄列更新）：last_game_second=300（300/3600=0 小时）
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> hourCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), hourCap.capture(), any(), any());
        assertEquals(Long.valueOf(300L), posCap.getValue(), "last_game_second=5 游戏分钟");
        assertEquals(Long.valueOf(0L), hourCap.getValue(), "last_game_hour 派生 0");
        // 感知周期：每 tick 状态惰性衰减追平到游戏秒（s=300）
        verify(npcStateService, times(1)).advance(eq(1L), eq(300L));
        // <1 游戏小时：不触发小时级钩子（人群调度/世界事件都不动）
        verify(ordinaryNpcService, never()).scheduleProgrammaticByGameHour(anyLong(), anyInt(), any());
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any(EventSpec.class));
    }

    /**
     * 1 游戏小时回补（1 真实小时×24）：288 个 5 分钟 tick 内跨 24 个游戏小时边界——
     * 小时级钩子仍按小时触发 24 次（M1 保持原小时语义）；秒级位置与小时派生一致落库。
     */
    @Test
    void hourlyHooksStillFireOnHourBoundariesAtFineGranularity() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1)); // 86400 游戏秒 = 24 游戏小时
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(24L, r.get("advancedHours"));
        // 288 个 5 分钟 tick：状态惰性追平每 tick 一次
        verify(npcStateService, times(288)).advance(eq(1L), anyLong());
        // 小时级钩子 24 次（游戏小时 1..24）
        ArgumentCaptor<Long> ghCaptor = ArgumentCaptor.forClass(Long.class);
        verify(ordinaryNpcService, times(24)).scheduleProgrammaticByGameHour(eq(1L), anyInt(), ghCaptor.capture());
        List<Long> ghs = ghCaptor.getAllValues();
        assertEquals(24L, ghs.get(23), "最后一次推进到游戏小时 24");
        assertTrue(ghs.contains(1L) && ghs.contains(12L), "逐时覆盖中间时刻");
        // 秒级位置与小时派生（窄列更新）
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> hourCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), hourCap.capture(), any(), any());
        assertEquals(Long.valueOf(86400L), posCap.getValue());
        assertEquals(Long.valueOf(24L), hourCap.getValue());
    }

    /**
     * 2026-09-25 长跑第 3 轮抓修（吞吐，验收项⑨）：可发生空间定期兜底**不得跑在引擎线程上**。
     * <p>长跑实测：该调用原为引擎线程串行执行，`growth_space_generate` 103 次/34 分钟、均值 31.5 s
     * ⇒ 每引擎小时约 10.5 真实分钟（rate=24 下 1 游戏小时仅 2.5 真实分钟）⇒ 世界只能推进到标称速率
     * 的 ~22%，流动历法时钟与引擎位置持续发散（jstack 实证 scheduling-1 阻塞在 okhttp HTTPS 读）。
     * 移入异步虚拟线程通道后：调用仍会发生（不牺牲空间新鲜度），但执行线程不是引擎线程。</p>
     */
    @Test
    void spacePeriodicRecomputeRunsOffEngineThread() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        GrowthProperties gp = new GrowthProperties();
        gp.setEnabled(true);
        ReflectionTestUtils.setField(engine, "growthProperties", gp);
        GrowthSpaceService space = Mockito.mock(GrowthSpaceService.class);
        ReflectionTestUtils.setField(engine, "growthSpaceService", space);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(inv -> {
            workerThread.set(Thread.currentThread().getName());
            done.countDown();
            return null;
        }).when(space).periodicRecompute(anyLong(), anyLong());

        Map<String, Object> r = engine.advanceProject(1L, now);

        assertEquals("advanced", r.get("status"));
        assertTrue(done.await(3, TimeUnit.SECONDS), "空间定期兜底应被触发（异步）");
        assertNotEquals(Thread.currentThread().getName(), workerThread.get(),
                "空间定期兜底不得在调用线程（引擎线程）上执行");
    }

    /**
     * 2026-09-25 长跑第 3 轮抓修：上一轮空间兜底未跑完时不得重复堆叠触发（单飞标记）——
     * 慢生成（均值 31.5 s）若按小时边界堆叠，会同时占满虚拟线程与网关。
     */
    @Test
    void spacePeriodicRecomputeIsSingleFlight() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        GrowthProperties gp = new GrowthProperties();
        gp.setEnabled(true);
        ReflectionTestUtils.setField(engine, "growthProperties", gp);
        GrowthSpaceService space = Mockito.mock(GrowthSpaceService.class);
        ReflectionTestUtils.setField(engine, "growthSpaceService", space);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            calls.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(space).periodicRecompute(anyLong(), anyLong());

        try {
            // 24 个游戏小时边界：首轮触发后仍阻塞，其余边界必须被单飞标记拦下
            Map<String, Object> r = engine.advanceProject(1L, now);
            assertEquals("advanced", r.get("status"));
            assertTrue(started.await(3, TimeUnit.SECONDS), "首轮空间兜底应已开始");
            assertEquals(1, calls.get(), "上一轮未完成时不得重复触发（单飞标记）");
        } finally {
            release.countDown();
        }
    }

    /**
     * 亚 tick 余数不丢失：流逝 370 游戏秒（>1 tick 300s）→ 先推进 1 个 tick（状态追平到 300），
     * 剩余 70 游戏秒由 last_game_second 保留（下次扫描惰性补偿，不丢弃——引擎位置与流动时钟几乎同步）。
     */
    @Test
    void subTickRemainderPreservedInLastGameSecond() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        // 15.417 真实秒 → 15417ms×24/1000=370 游戏秒（>1 tick 300s）
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(15).minusNanos(417_000_000L));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(0L, r.get("advancedHours"), "370 游戏秒 < 1 游戏小时");
        // 1 个整 tick（300s）被步进，状态追平到 300
        verify(npcStateService, times(1)).advance(eq(1L), eq(300L));
        // 余数 70 游戏秒保留在 last_game_second（不取整丢弃；窄列更新提交）
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), anyLong(), any(), any());
        assertEquals(Long.valueOf(370L), posCap.getValue(), "余数保留，下次惰性补偿");
    }

    /**
     * M2 三周期分层（世界演化与游戏时间系统，2026-09-06）：特殊 NPC 决策机会从「仅跨日边界 +
     * 重要度≥3」提升为「<b>每游戏小时全部角色</b>」——24 游戏小时回补 × 2 角色 = 48 次
     * AgentRuntime 决策循环（预算在门面内部门控——AI 预算用尽走程序化兜底不冻结，世界不因预算
     * 耗尽而冻结）；重要度仅决定预算内优先级（降序：高重要度角色先进入决策循环）。
     */
    @Test
    void hourlyDecisionOpportunityForAllSpecialNpcs() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engineConfig(1, 24)));
        ActorCharacter a = character(1, 4);
        ActorCharacter b = character(2, 3);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(a, b));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        // 24 游戏小时 × 2 角色 = 48 次决策机会（全部特殊 NPC 每游戏小时，不再限跨日/重要度门槛）
        ArgumentCaptor<String> refCap = ArgumentCaptor.forClass(String.class);
        verify(agentRuntime, times(48)).trigger(eq(AgentTrigger.ENGINE_TICK), refCap.capture(), any(DecisionContext.class));
        List<String> refs = refCap.getAllValues();
        // 重要度降序：每轮首个决策机会是高重要度角色 special:1（预算内优先）
        assertEquals("npc:1", refs.get(0), "每轮首个决策机会为高重要度角色");
        assertEquals("npc:2", refs.get(1), "其次低重要度角色");
        assertEquals("npc:1", refs.get(24), "下一小时又从高重要度开始");
        // 决策上下文 reason 标识「每游戏小时决策机会」
        ArgumentCaptor<DecisionContext> ctxCap = ArgumentCaptor.forClass(DecisionContext.class);
        verify(agentRuntime, atLeastOnce()).trigger(eq(AgentTrigger.ENGINE_TICK), eq("npc:1"), ctxCap.capture());
        assertEquals("世界引擎每游戏小时决策机会", ctxCap.getValue().reason());
    }

    /**
     * M5（世界演化与游戏时间系统 2026-09-07）：信息包扩散挂钩——每游戏小时边界调用
     * InfoDiffusionService.diffuseProject（含长离线回补逐小时物化，Q1 用户决策）——
     * 24 游戏小时回补 → 24 次扩散调用，游戏时刻从 1×3600 递进到 24×3600。
     */
    @Test
    void hourlyDiffusionHookFiresOnHourBoundaries() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        // 24 游戏小时 × 每小时 1 次扩散 = 24 次（含跨日回补逐小时）
        ArgumentCaptor<Long> gsCap = ArgumentCaptor.forClass(Long.class);
        verify(infoDiffusionService, times(24)).diffuseProject(eq(1L), gsCap.capture());
        List<Long> gss = gsCap.getAllValues();
        assertEquals(24, gss.size(), "逐时 24 次扩散调用");
        assertEquals(Long.valueOf(1L * 3600L), gss.get(0), "首个游戏小时边界");
        assertEquals(Long.valueOf(24L * 3600L), gss.get(23), "最后一次推进到游戏小时 24");
    }

    /**
     * M2：决策机会失败 → 程序化兜底（不阻断推进）——每小时 trigger 抛异常时该角色每小时
     * 走 programmaticUpdate（当前活动文本随时段变化 → 落库；推进仍 advanced）。
     */
    @Test
    void hourlyDecisionFailureFallsBackToProgrammatic() {        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        ActorCharacter a = character(1, 4);
        a.setLocation("城东市场");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(a));
        when(perceptionService.buildPerceptionText(anyLong(), anyString(), any())).thenReturn("【当前所见】测试感知");
        when(agentRuntime.trigger(any(), any(), any())).thenThrow(new RuntimeException("AI 不可用"));

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "决策失败不阻断推进");
        // 24 小时 × 1 角色：24 次尝试全部程序化兜底
        verify(agentRuntime, times(24)).trigger(eq(AgentTrigger.ENGINE_TICK), eq("npc:1"), any(DecisionContext.class));
        // 程序化更新随时段变化至少落库 1 次（活动文本变化才写）
        verify(characterRepository, atLeast(1)).save(any(ActorCharacter.class));
    }

    /**
     * 治理批次 0 · 卡4-A（G-ENGINE-ACT-01，起始 FAIL）：引擎程序化决策同步写 actor_state。
     * <p>现状错误值：programmaticUpdate 只写 profile.currentActivity 不写 actor_state → 普通 NPC
     * 感知读方（resolveOwnState 普通分支）仍读旧值。断言引擎程序化决策后 setActivity 被调用且
     * activityOf 读到引擎写的新活动文本（统一读 actor_state 方向，禁止改读 profile 偷懒）。</p>
     */
    @Test
    void programmaticUpdate_writesActorStateForCommonNpc() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        ActorCharacter a = character(10, 0);
        a.setType("common");
        a.setLocation("村口");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(a));
        when(perceptionService.buildPerceptionText(anyLong(), anyString(), any())).thenReturn("【当前所见】测试感知");
        when(agentRuntime.trigger(any(), any(), any())).thenThrow(new RuntimeException("AI 不可用"));
        // 注入作息运行态访问器（字段注入，不改构造器）——修复前 WorldEngine 无此字段 → 本测试先红
        ReflectionTestUtils.setField(engine, "npcActivityStateService", npcActivityStateService);
        // 写侧 fake：setActivity 记录 → activityOf 可读回（模拟 actor_state 落库）
        Map<String, com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.ActivityUpdate> stateStore =
                new java.util.HashMap<>();
        org.mockito.Mockito.doAnswer(inv -> {
            stateStore.put(inv.getArgument(1),
                    new com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.ActivityUpdate(
                            inv.getArgument(2), inv.getArgument(3)));
            return null;
        }).when(npcActivityStateService).setActivity(anyLong(), anyString(), anyString(), anyString());
        when(npcActivityStateService.activityOf(anyLong(), anyString())).thenAnswer(inv ->
                stateStore.getOrDefault(inv.getArgument(1),
                        new com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.ActivityUpdate("idle", null)));

        engine.advanceProject(1L, now);

        // ① setActivity 被调用（引擎程序化决策同步 actor_state）
        ArgumentCaptor<String> refCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stateCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> actCap = ArgumentCaptor.forClass(String.class);
        verify(npcActivityStateService, atLeastOnce())
                .setActivity(eq(1L), refCap.capture(), stateCap.capture(), actCap.capture());
        assertEquals("npc:10", refCap.getAllValues().get(0), "同步对象为普通 NPC npc:10");
        List<String> states = stateCap.getAllValues();
        List<String> acts = actCap.getAllValues();
        // 映射一致性：写的 state == toActivityState(写的 activity)（映射表唯一来源）
        for (int i = 0; i < acts.size(); i++) {
            assertEquals(com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.toActivityState(acts.get(i)),
                    states.get(i), "引擎活动文本→activity_state 映射一致（第 " + i + " 次）");
        }
        // ② activityOf 读到引擎写的新活动文本（读 actor_state，非 profile）
        com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.ActivityUpdate readBack =
                npcActivityStateService.activityOf(1L, "npc:10");
        assertEquals(acts.get(acts.size() - 1), readBack.lastAction(), "activityOf 读到引擎写的新活动文本");
        assertEquals(states.get(states.size() - 1), readBack.state(), "activityOf 读到映射后的 activity_state");
    }

    /**
     * 跨日事件双通道（T-4）：机制草稿 → AI 润色（预算内）→ publish(kind=world/source=engine/幂等键按日) →
     * 预算内注入最近活跃会话触发在场角色回应。
     */
    @Test
    void crossDayEventPublishesPolishesAndInjects() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(worldEventService.polishEventByAi(anyLong(), eq(1L), anyString(), anyString()))
                .thenReturn("润色标题\n润色内容");
        ActorConversation conv = new ActorConversation();
        conv.setId(9L);
        conv.setProjectId(1L);
        conv.setUserId(1L);
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(conv));
        when(locationRepository.findByProjectId(1L))
                .thenReturn(List.of(location("城东市场"), location("王都酒馆")));

        engine.advanceProject(1L, now);

        // AI 润色以机制草稿为基础被调用
        verify(worldEventService, times(1)).polishEventByAi(eq(1L), eq(1L), contains("第1日"), anyString());
        // publish kind=world source=engine，幂等键按游戏日唯一，重要度 4（B4 F18 进【世界大事记】）
        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, times(1)).publish(anyLong(), eq(1L), specCap.capture());
        assertEquals("world", specCap.getValue().getKind());
        assertEquals("engine", specCap.getValue().getSource());
        assertEquals("engine:1:1", specCap.getValue().getSourceKey());
        assertEquals(Long.valueOf(24L), specCap.getValue().getGameHour());
        assertEquals(Integer.valueOf(4), specCap.getValue().getImportance(), "世界事件重要度 4 以进项目级大事记");
        // 注入最近活跃会话触发在场角色回应（内容为润色结果）
        verify(worldEventService, times(1)).injectExternal(9L, 1L, "润色标题\n润色内容");
    }

    /**
     * AI 润色失败（AI 异常）→ 回退机制草稿发布（可选增强不阻断世界推进）。
     */
    @Test
    void polishFailureFallsBackToMechanismDraft() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(worldEventService.polishEventByAi(anyLong(), eq(1L), anyString(), anyString()))
                .thenThrow(new RuntimeException("AI 不可用"));
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location("城东市场")));

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "润色失败不阻断推进");

        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, times(1)).publish(anyLong(), eq(1L), specCap.capture());
        assertTrue(specCap.getValue().getContent().contains("第1日"), "回退机制草稿内容");
    }

    /**
     * 无会话项目跨日事件仅广播（publish）不注入（无对话项目也自转，L-02）。
     */
    @Test
    void crossDayEventBroadcastsWithoutConversation() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L)).thenReturn(List.of());

        engine.advanceProject(1L, now);
        verify(worldEventBus, times(1)).publish(anyLong(), eq(1L), any(EventSpec.class));
        verify(worldEventService, never()).injectExternal(anyLong(), anyLong(), anyString());
    }

    /**
     * 2026-09-29 冒烟抓修守护：跨日边界的目标/日计划生成（advanceDayPlanning）与跨日事件已改
     * {@code runDailyTaskAsync} 异步——planningService.ensurePlanDay 阻塞时引擎仍立即返回推进
     * （修复前同步执行：plan_day 每角色 40~120s 串行，网关慢时实测引擎冻结 10+ 分钟，
     * 反思/世界帧等日级任务全部延后；R1「引擎线程同步 AI 钩子清理」同族补漏）。
     */
    @Test
    void dayCrossingPlanningRunsAsyncNotBlockingEngine() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1)); // 24 游戏小时（跨日）
        stubBase(now, c);
        // 两个特殊角色（planning_enabled=1 且非 programmatic → advanceDayPlanning 会生成日计划）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 4)));
        ActorAgentProfile prof = new ActorAgentProfile();
        prof.setPlanningEnabled(1);
        prof.setBrainLevel("full");
        com.holzyn.actor.domain.agent.brain.service.BrainProfileService brainProfileService =
                Mockito.mock(com.holzyn.actor.domain.agent.brain.service.BrainProfileService.class);
        when(brainProfileService.resolveByRef(anyString())).thenReturn(prof);
        ReflectionTestUtils.setField(engine, "brainProfileService", brainProfileService);
        // planningService.ensurePlanDay 阻塞（模拟网关慢：40~120s/次）——同步执行会卡死引擎
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        com.holzyn.actor.domain.agent.thinking.PlanningService planning =
                Mockito.mock(com.holzyn.actor.domain.agent.thinking.PlanningService.class);
        Mockito.doAnswer(inv -> {
            started.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(planning).ensurePlanDay(anyLong(), anyString(), anyLong());
        ReflectionTestUtils.setField(engine, "planningService", planning);
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location("城东市场")));

        long t0 = System.currentTimeMillis();
        Map<String, Object> r = engine.advanceProject(1L, now);
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals("advanced", r.get("status"));
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "异步日计划任务已启动");
        assertTrue(elapsed < 5_000, "引擎立即返回（异步派发，不等待阻塞的 plan_day），实际 " + elapsed + "ms");
        release.countDown();
        engine.awaitDailyIdle(3000);
        // 异步任务落地：两个角色各生成一次日计划
        verify(planning, times(2)).ensurePlanDay(eq(1L), anyString(), anyLong());
    }

    /**
     * 特殊角色 AI 行动的感知用引擎推进到的游戏时刻（统一时间口径，修 L-07/C-2）。
     * <p>M2（2026-09-06）：每游戏小时一次决策机会——24 游戏小时回补 → 24 次 ENGINE_TICK。
     * <p>R1（2026-09-28）预热口径（B1）：每小时的决策在「下一小时边界 − 提前量 15 游戏分钟」的
     * tick 触发预热（感知=预热触发 tick 时刻）——hour 24 档的决策在 23:45（=85500 游戏秒）预热，
     * 感知/决策上下文 gameSecond = 85500（而非旧口径的 24×3600=86400 整点）。</p>
     */
    @Test
    void aiActionPerceptionUsesEngineGameHour() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        ActorCharacter a = character(1, 4);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(a));
        when(perceptionService.buildPerceptionText(anyLong(), anyString(), any())).thenReturn("【当前所见】测试感知");

        engine.advanceProject(1L, now);

        // M2：每游戏小时一次决策机会（24 小时 → 24 次 ENGINE_TICK，预热去重后每角色每小时恰好 1 次）
        ArgumentCaptor<DecisionContext> ctxCap = ArgumentCaptor.forClass(DecisionContext.class);
        verify(agentRuntime, times(24)).trigger(eq(AgentTrigger.ENGINE_TICK), eq("npc:1"), ctxCap.capture());
        List<DecisionContext> ctxs = ctxCap.getAllValues();
        // R1 预热口径：hour 24 档决策在 23:45（=85500 游戏秒）预热触发 → gameSecond=85500
        assertEquals(Long.valueOf(85500L), ctxs.get(23).gameSecond(),
                "R1 预热口径：hour 24 档决策的感知时刻 = 预热触发 tick（23:45=85500），非整点 86400");
        assertEquals("【当前所见】测试感知", ctxs.get(23).situation());
        // 感知构建 24 次，最后一次 gameSecond=85500（统一时间口径 + R1 预热口径）
        ArgumentCaptor<Long> gs = ArgumentCaptor.forClass(Long.class);
        verify(perceptionService, times(24)).buildPerceptionText(eq(1L), eq("npc:1"), gs.capture());
        assertEquals(Long.valueOf(85500L), gs.getAllValues().get(23));
    }

    /** 构造地点实体 */
    private ActorWorldLocation location(String name) {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(1L);
        loc.setProjectId(1L);
        loc.setName(name);
        return loc;
    }

    /**
     * 引擎配置懒创建：无 actor_world_engine 行时按缺省预算/步长建行（每项目唯一）。
     */
    @Test
    void engineConfigLazilyCreated() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusMinutes(30));
        stubBase(now, c);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(engineRepository.save(any(ActorWorldEngine.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.advanceProject(1L, now);

        ArgumentCaptor<ActorWorldEngine> cap = ArgumentCaptor.forClass(ActorWorldEngine.class);
        verify(engineRepository).save(cap.capture());
        assertEquals(1L, cap.getValue().getProjectId());
        assertEquals(Integer.valueOf(5), cap.getValue().getBudgetPerTick());
        assertEquals(Integer.valueOf(24), cap.getValue().getMaxStepHours());
        assertNotNull(cap.getValue().getLastAiBudgetResetAt());
    }

    /**
     * 推进结果 Map 字段完整（status/advancedHours/gameHour/actions/crowds/events/aiCalls/summary）。
     */
    @Test
    void advanceResultFields() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusMinutes(30));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertNotNull(r.get("status"));
        assertNotNull(r.get("advancedHours"));
        assertNotNull(r.get("gameHour"));
        assertNotNull(r.get("summary"));
        assertTrue(r.containsKey("actions") && r.containsKey("crowds") && r.containsKey("events")
                && r.containsKey("aiCalls"));
    }

    // ==================== D4 F12 无玩家群聊接线 ====================

    /**
     * F12 概率闲聊：standard 模式每游戏小时触发概率检查（预算门控在服务内部）；
     * 跨日世界事件照常发布——事件回应轮自 B 层（2026-09-24）起由 AutonomousChatEventSubscriber
     * 订阅事件总线统一触发，引擎不再直挂（防同事件双触发），故此处只锁定「事件已进总线」。
     */
    @Test
    void standardModeTriggersProbabilityChatPerHour() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1)); // 24 游戏小时（跨日）
        stubBase(now, c);
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location("城东市场")));

        engine.advanceProject(1L, now);

        // 逐游戏小时概率检查（24 小时 → 24 次调用，mock 至少验证调用过；2026-09-17 改异步入口）
        verify(autonomousChatService, atLeastOnce()).triggerProbabilityAsync(any(ActorProject.class), anyLong());
        // 跨日世界事件已发布（kind=world）——供 AutonomousChatEventSubscriber 触发所在地点回应轮
        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), specCap.capture());
        assertTrue(specCap.getAllValues().stream().anyMatch(s -> "world".equals(s.getKind())),
                "跨日世界事件已进事件总线（回应轮由订阅者接线）");
    }

    /**
     * F12 minimal 模式：不触发概率闲聊（省钱优先）；跨日世界事件照常发布
     * （事件回应轮链路与 minimal 无关——B 层起由订阅者统一接线，minimal 亦保留）。
     */
    @Test
    void minimalModeSkipsProbabilityChatButKeepsWorldEventPublish() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(minimalEngineConfig(24)));
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location("城东市场")));

        engine.advanceProject(1L, now);

        verify(autonomousChatService, never()).triggerProbabilityAsync(any(ActorProject.class), anyLong());
        // 跨日事件照常发布（minimal 不拦事件；回应轮由订阅者按自身配置处理）
        ArgumentCaptor<EventSpec> specCap = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, atLeastOnce()).publish(anyLong(), eq(1L), specCap.capture());
        assertTrue(specCap.getAllValues().stream().anyMatch(s -> "world".equals(s.getKind())),
                "minimal 下跨日世界事件仍发布");
    }

    /**
     * F12 总开关关闭：引擎不触发概率闲聊（事件回应轮的触发权已移交订阅者，
     * 其总开关口径由 AutonomousChatEventSubscriberTest 覆盖）。
     */
    @Test
    void autonomousChatDisabledWhenSwitchOff() {
        ReflectionTestUtils.setField(engine, "autonomousChatEnabled", false);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location("城东市场")));

        engine.advanceProject(1L, now);

        verify(autonomousChatService, never()).triggerProbabilityAsync(any(), anyLong());
        verify(autonomousChatService, never()).triggerEventResponseAsync(anyLong(), anyLong(), any(), any(), any(), any());
    }

    /** 构造 minimal 引擎配置（run_mode=minimal） */
    private ActorWorldEngine minimalEngineConfig(int maxStep) {
        ActorWorldEngine e = engineConfig(5, maxStep);
        e.setRunMode("minimal");
        return e;
    }

    // ==================== M6 实时决策激活闸门（默认关；未激活世界暂停） ====================

    /**
     * 实时决策未激活（默认关）→ 引擎跳过推进（等同 world_running=0 语义，不耗 AI）；
     * 手动推进 force=true 绕过闸门仍可推进（用户显式操作）。
     */
    @Test
    void realtimeDecisionInactiveSkipsAdvance() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(engine, "realtimeDecisionActivationService", activationService);
        when(activationService.isActive(1L)).thenReturn(false);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("stopped", r.get("status"), "未激活 → 世界暂停（等同 world_running=0）");
        assertTrue(String.valueOf(r.get("summary")).contains("实时决策未激活"), "摘要说明未激活原因");
        verify(agentRuntime, never()).trigger(any(), any(), any());
        verify(clockRepository, never()).save(any(ActorWorldClock.class));
        // P0-6 配套：跳过的路径也不得写窄列更新（时钟一行都不动）
        verify(clockRepository, never()).updateEnginePosition(any(), anyLong(), anyLong(), any(), any());
        verify(clockRepository, never()).updateEngineBase(any(), any());
    }

    /**
     * 实时决策已激活 → 正常推进（打开沙盘/演化页或手动常开后的行为）。
     */
    @Test
    void realtimeDecisionActiveAdvances() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(engine, "realtimeDecisionActivationService", activationService);
        when(activationService.isActive(1L)).thenReturn(true);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "已激活 → 正常推进");
    }

    /**
     * 手动推进（force=true）绕过激活闸门：未激活项目仍可手动推进（用户显式操作，非后台自动输出）。
     */
    @Test
    void realtimeDecisionInactiveButForceAdvances() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(engine, "realtimeDecisionActivationService", activationService);
        when(activationService.isActive(1L)).thenReturn(false);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProjectForce(1L, true);
        assertEquals("advanced", r.get("status"), "手动强制推进绕过激活闸门");
    }

    // ==================== M5.5 微层接线：AI 强度档位预算折算（静态纯逻辑） ====================

    /** 构造档位闸门（mock 引擎行）。 */
    private AiIntensityGate gateOf(String level) {
        ActorWorldEngineRepository repo = Mockito.mock(ActorWorldEngineRepository.class);
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(1L);
        e.setAiIntensity(level);
        when(repo.findByProjectId(1L)).thenReturn(Optional.of(e));
        return new AiIntensityGate(repo);
    }

    @Test
    void intensityBudgetByLevel() {
        // programmatic → 0（全程序化）
        assertEquals(0, WorldEngine.intensityBudget(5, gateOf("programmatic"), 1L));
        // light → ceil(5×0.5)=3；奇数原值 3 → ceil(1.5)=2；原值 1 → 至少 1
        assertEquals(3, WorldEngine.intensityBudget(5, gateOf("light"), 1L));
        assertEquals(2, WorldEngine.intensityBudget(3, gateOf("light"), 1L));
        assertEquals(1, WorldEngine.intensityBudget(1, gateOf("light"), 1L));
        // max → Int.MAX（全 AI 必调无预算）
        assertEquals(Integer.MAX_VALUE, WorldEngine.intensityBudget(5, gateOf("max"), 1L));
        // full / 未知档 → 原值
        assertEquals(5, WorldEngine.intensityBudget(5, gateOf("full"), 1L));
        assertEquals(5, WorldEngine.intensityBudget(5, gateOf("unknown"), 1L));
        // 未装配 / 无项目 → 原值
        assertEquals(7, WorldEngine.intensityBudget(7, null, 1L));
        assertEquals(7, WorldEngine.intensityBudget(7, gateOf("max"), null));
    }

    /**
     * M6 冒烟抓修（2026-09-16 真实生产缺陷）：推进结束后预算窗口必须关闭（endTick）——
     * 原实现窗口泄漏导致暂停期手动/干预行动恒程序化兜底；修复后推进结束 budgetTotal=-1（无窗口）。
     */
    @Test
    void budgetWindowClosedAfterAdvance() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(engine, "realtimeDecisionActivationService", activationService);
        when(activationService.isActive(1L)).thenReturn(true);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(1));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "应正常推进");
        assertEquals(-1, budgetGuard.budgetTotal(1L), "推进结束后预算窗口应关闭（无泄漏）");
        // idle 路径（无流逝）同样关闭窗口
        LocalDateTime now2 = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c2 = clock(0, 0, 1, now2);
        stubBase(now2, c2);
        engine.advanceProject(1L, now2);
        assertEquals(-1, budgetGuard.budgetTotal(1L), "idle 路径同样关闭窗口");
    }

    // ==================== tick 屏障接线（2026-09-21，O6 G1a 阶段⑤） ====================

    /**
     * 屏障必须完整包住每个感知 tick：beginTick →（派发排队刺激）→ awaitQuiescence → endTick，
     * <b>包括没有小时级钩子的 tick</b>。
     *
     * <p>为什么这是关键回归点：② 主决策周期里有 `hour == lastHour` 的 `continue`——若屏障收尾写在
     * tick 体末尾而不是 finally，这些 tick 会漏掉 endTick，屏障 epoch 残留（epoch&gt;0 且
     * barrierPassed=true）→ 之后所有刺激永久排队、NPC 再也不反应（世界看着在跑，人却死了）。</p>
     */
    @Test
    void barrierWrapsEveryTickIncludingContinueTicks() {
        RecordingBarrier barrier = new RecordingBarrier();
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        // 150 真实秒 × rate=24 = 3600 游戏秒 = 1 游戏小时 = 12 个 5 游戏分钟 tick
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(12, barrier.count("begin"), "12 个 tick 各开一次屏障");
        assertEquals(12, barrier.count("drain"), "每个 tick 的派发相都尝试取出排队刺激");
        assertEquals(12, barrier.count("await"), "12 个 tick 各等一次（含 11 个无小时钩子的 continue tick）");
        assertEquals(12, barrier.count("end"), "12 个 tick 各收尾一次（continue 路径也必须收尾）");
        // 首个 tick（s=300，未跨小时 → continue）的完整序列
        assertEquals(List.of("begin", "drain", "await", "end"), barrier.calls.subList(0, 4),
                "无小时钩子的 tick 也必须 begin→drain→await→end 全序闭合");
    }

    /**
     * 刺激排队相的派发：上一 tick「屏障已过」之后入队的刺激，在下一 tick 的派发相执行（恰好一次）。
     */
    @Test
    void deferredStimulusIsRedispatchedOnNextTick() {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        // 模拟「屏障已过之后到达的刺激」：先有活动 tick 让队列存在，入队后 tick 结束（引擎停）
        barrier.beginTick(1L);
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        barrier.defer(1L, fired::incrementAndGet);
        barrier.endTick(1L);

        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        assertEquals(1, fired.get(), "排队刺激在下一 tick 的派发相执行（恰好一次）");
    }

    /**
     * 屏障模式下的回补封顶：单轮推进最多 maxCatchUpTicks 个 tick（时钟宁可走慢，也不快进雪崩）——
     * 10 真实小时的回补（rate=24 → 240 游戏小时）本轮只推进 2 tick=600 游戏秒，
     * 剩余量留给下一轮扫描（last_engine_run_at 已推进，不会重复补）。
     */
    @Test
    void backlogClampLimitsTicksPerRound() {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(barrier, "maxCatchUpTicks", 2);
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusHours(10));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), anyLong(), any(), any());
        assertEquals(Long.valueOf(600L), posCap.getValue(),
                "单轮推进收敛到 2 tick=600 游戏秒（不快进补完整 240 游戏小时）");
        verify(npcStateService, times(2)).advance(eq(1L), anyLong());
        verify(agentRuntime, never()).trigger(any(), any(), any());
    }

    /**
     * 超时放行：屏障等待超时（在途 AI 链超过 max-wait-ms）时世界照常推进，并在摘要里留下可观测痕迹
     * ——「观测数据不得反噬业务」在引擎层的落点。
     */
    @Test
    void barrierTimeoutStillAdvancesWorld() {
        TickBarrier timeoutBarrier = new TickBarrier() {
            @Override
            public AwaitResult awaitQuiescence(Long projectId) {
                // 模拟「在途任务迟迟不结束、等待到点放行」
                return new AwaitResult(30_000L, 1, true);
            }
        };
        ReflectionTestUtils.setField(engine, "tickBarrier", timeoutBarrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"), "超时放行：世界不被慢依赖冻结");
        ArgumentCaptor<Long> posCap = ArgumentCaptor.forClass(Long.class);
        verify(clockRepository).updateEnginePosition(eq(1L), posCap.capture(), anyLong(), any(), any());
        assertEquals(Long.valueOf(3600L), posCap.getValue(), "时钟照常推进到目标");
        String summary = String.valueOf(r.get("summary"));
        assertTrue(summary.contains("屏障等待"), "摘要记录屏障等待（可观测）：" + summary);
        assertTrue(summary.contains("超时放行"), "摘要记录超时放行次数（可观测）：" + summary);
    }

    /**
     * 小时级决策异步派发：并行度&gt;1（屏障开启）时，同一目标小时的决策在线程池异步执行——
     * 引擎在预热/边界补发提交后立即返回（不等待 AI），世界不被自己的决策拖慢（R1 2026-09-28：
     * 小时边界零阻塞，替代旧「同步扇出等待」语义）。
     */
    @Test
    void hourlyDecisionsFanOutOnBoundedPool() throws Exception {
        TickBarrier barrier = new TickBarrier(); // parallel-dispatch 默认 4
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engineConfig(5, 24)));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        Mockito.doAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        // R1：异步派发——advance 立即返回（不等待 AI），等任务落地后断言 2 次决策（预热去重后恰好 1 次/角色）
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "异步派发的 2 次决策应在批次内完成");
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
        assertTrue(threads.stream().anyMatch(n -> n.startsWith("world-dispatch-")),
                "并行度>1 时角色决策应在扇出线程池执行，实际线程=" + threads);
    }

    /**
     * 扇出并行度=1（或屏障未装配/已关闭）时退回串行：全部决策在引擎调用线程执行——
     * 逐行等价改造前行为（可回退开关的语义保证）。
     */
    @Test
    void hourlyDecisionsStaySerialWhenParallelismIsOne() {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(barrier, "parallelDispatch", 1);
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Mockito.doAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        engine.advanceProject(1L, now);
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
        assertTrue(threads.stream().noneMatch(n -> n.startsWith("world-dispatch-")),
                "并行度=1 时不得创建/使用扇出线程池，实际线程=" + threads);
    }

    /**
     * 真并发证据：并行度&gt;1 时同一个目标小时的两个角色决策<b>同时</b>在途
     * （两边互相等待对方的 in-flight 信号；串行执行会因互相等待而超时）。
     */
    @Test
    void hourlyDecisionsRunConcurrentlyWithinOneHour() throws Exception {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(barrier, "parallelDispatch", 2);
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CyclicBarrier bothInFlight = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicBoolean concurrent = new java.util.concurrent.atomic.AtomicBoolean(false);
        Mockito.doAnswer(inv -> {
            try {
                bothInFlight.await(3, java.util.concurrent.TimeUnit.SECONDS);
                concurrent.set(true);
            } catch (Exception e) {
                // 串行执行：等不到对面的在途信号 → 超时（concurrent 保持 false）
            }
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        engine.advanceProject(1L, now);
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "异步派发的 2 次决策应在批次内完成");
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
        assertTrue(concurrent.get(), "同一目标小时的两个角色决策应同时在途（扇出并发，tick 耗时 Σ→max）");
    }

    /**
     * ⚠️ R1 回归（2026-09-28 重写）：单次 AI 调用悬挂（对端连接半死：不返回也不关闭）不得冻结世界。
     *
     * <p>R1 前语义（2026-09-21 jstack 抓修）：小时边界同步扇出等待，用无上限 {@code Future.get()}
     * 收集结果会把世界推进线程的生死交给单次 AI 调用——改为有界等待预算（dispatch-wait-seconds）。
     * R1 后语义：小时边界<b>不再有任何同步等待</b>（预热 + 异步补发），悬挂的 AI 调用在池线程里
     * 继续跑、写库照常落地，引擎立即返回推进——dispatchTimeouts 恒 0（无等待即无放弃），
     * 且悬挂任务不被取消（放弃等待 ≠ 取消任务，已付费 AI 结果不丢）。</p>
     *
     * <p>断言：①推进立即返回（不被悬挂拖死）；②{@code dispatchTimeouts}=0（异步派发无等待）；
     * ③悬挂任务事后照常完成（不取消、不丢）；④批次预算窗口正确收尾。</p>
     */
    @Test
    void hangingDecisionDoesNotFreezeWorld() throws Exception {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(barrier, "parallelDispatch", 2);
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150)); // rate=24 → 恰好推进 1 游戏小时
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CountDownLatch hung = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (seq.incrementAndGet() == 1) {
                // 模拟悬挂：远长于任何等待预算（真实故障=HTTP 连接半死，read 超时也没能兜住）
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        long t0 = System.currentTimeMillis();
        Map<String, Object> r = engine.advanceProject(1L, now);
        long elapsed = System.currentTimeMillis() - t0;
        hung.countDown(); // （保留兼容命名；异步派发下推进本就不等悬挂）

        assertEquals("advanced", r.get("status"), "悬挂的 AI 调用不得阻断世界推进");
        assertTrue(elapsed < 15_000, "推进必须立即返回（异步派发不等待 AI，实际 " + elapsed + "ms）");
        assertEquals(0, r.get("dispatchTimeouts"), "异步派发无等待 → 无放弃计数：" + r);
        assertEquals(2, r.get("actions"), "异步派发即计入派发数（提交 2 个角色）：" + r);
        // 悬挂任务事后照常完成（不取消、不丢已付费 AI 结果）
        release.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "悬挂任务应在放行后照常完成");
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
    }

    // ==================== R1 引擎 AI 异步化/前瞻缓冲（2026-09-28） ====================

    /**
     * R1 核心验收①：前瞻预热——小时中段（整点前 15 游戏分钟=2700 游戏秒）预热触发下一小时全体
     * 角色决策（异步、引擎立即返回），边界到达时去重跳过（每角色每游戏小时恰好 1 次决策入口）。
     *
     * <p>断言：①advance 立即返回（异步派发不等待 AI）；②恰好 2 次触发（预热 1 次/角色，边界跳过）；
     * ③决策感知时刻=预热触发 tick（2700，B1 口径，非整点 3600）；④摘要含「前瞻预热/边界跳过」。</p>
     */
    @Test
    void lookaheadPrefiresBeforeBoundaryAndDedupsAtBoundary() throws Exception {
        TickBarrier barrier = new TickBarrier(); // parallel-dispatch 默认 4 → 异步派发
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150)); // rate=24 → 推进 1 游戏小时
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        Mockito.doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        long t0 = System.currentTimeMillis();
        Map<String, Object> r = engine.advanceProject(1L, now);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "预热派发的决策应在批次内完成");

        assertEquals("advanced", r.get("status"));
        assertTrue(elapsed < 15_000, "异步派发：advance 立即返回（不等待 AI），实际 " + elapsed + "ms");
        // 恰好 2 次触发（预热 1 次/角色；边界去重跳过，不重复触发）
        ArgumentCaptor<DecisionContext> ctxCap = ArgumentCaptor.forClass(DecisionContext.class);
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), ctxCap.capture());
        // B1 口径：感知时刻 = 预热触发 tick（2700=10:45），非边界整点（3600=11:00）
        for (DecisionContext ctx : ctxCap.getAllValues()) {
            assertEquals(Long.valueOf(2700L), ctx.gameSecond(),
                    "预热决策的感知时刻 = 预热触发 tick（2700），非边界整点：" + ctx);
        }
        assertEquals(2, r.get("actions"), "派发数=2（预热派发 2 个角色）：" + r);
        assertEquals(0, r.get("dispatchTimeouts"), "异步派发无等待超时：" + r);
        String summary = String.valueOf(r.get("summary"));
        assertTrue(summary.contains("前瞻预热"), "摘要应记录前瞻预热：" + summary);
        assertTrue(summary.contains("边界跳过 2"), "摘要应记录边界跳过 2 个已预热角色：" + summary);
        assertTrue(summary.contains("异步派发 2"), "摘要应记录异步派发数：" + summary);
    }

    /**
     * R1 核心验收②：边界异步兜底——lookahead 关闭（或冷启动未预热）时，边界对全部角色异步补发
     * 不等待（引擎零阻塞），感知时刻=边界整点（保持旧「整点决策」语义）。
     */
    @Test
    void lookaheadDisabledFallsBackToAsyncBoundaryDispatch() throws Exception {
        ReflectionTestUtils.setField(engine, "lookaheadEnabled", false);
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        Mockito.doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        long t0 = System.currentTimeMillis();
        Map<String, Object> r = engine.advanceProject(1L, now);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "边界补发的决策应在批次内完成");

        assertEquals("advanced", r.get("status"));
        assertTrue(elapsed < 15_000, "边界补发异步：advance 立即返回（不等待 AI），实际 " + elapsed + "ms");
        ArgumentCaptor<DecisionContext> ctxCap = ArgumentCaptor.forClass(DecisionContext.class);
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), ctxCap.capture());
        // 边界补发语义：感知时刻 = 边界整点（3600=11:00，保持旧「整点决策」口径）
        for (DecisionContext ctx : ctxCap.getAllValues()) {
            assertEquals(Long.valueOf(3600L), ctx.gameSecond(), "边界补发的感知时刻 = 边界整点：" + ctx);
        }
        assertEquals(2, r.get("actions"), "边界补发派发 2 个角色：" + r);
        assertEquals(0, r.get("dispatchTimeouts"), "异步补发无等待超时：" + r);
    }

    /**
     * R1 去重记账：预热命中后，边界对同一目标小时不再重复触发（内存 preparedHour 记账）。
     * 跨两个推进调用验证：调用 1 预热小时 1（感知 2700）；调用 2 从 3600 推进到 7200——
     * 小时 2 的预热在 6300 触发（感知 6300）、边界 7200 跳过 → 每角色每游戏小时恰好 1 次。
     */
    @Test
    void lookaheadDedupAcrossAdvanceCalls() throws Exception {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(4);
        java.util.List<Long> seenSeconds = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Mockito.doAnswer(inv -> {
            seenSeconds.add(inv.getArgument(2, DecisionContext.class).gameSecond());
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        // 调用 1：0→3600（小时 1 档预热于 2700）——独立时钟实例，避免实体被引擎推进修改影响下一轮
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        Map<String, Object> r1 = engine.advanceProject(1L, now);
        assertEquals("advanced", r1.get("status"));
        // 调用 2：3600→7200（小时 2 档预热于 6300）——重新 stub 全新时钟实例（模拟 DB 重新读取）
        LocalDateTime now2 = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c2 = clock(1, 0, 1, now2.minusSeconds(150));
        stubBase(now2, c2);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        Map<String, Object> r2 = engine.advanceProject(1L, now2);
        assertEquals("advanced", r2.get("status"));

        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "两次推进的预热派发应在批次内完成");
        verify(agentRuntime, times(4)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
        // 每角色每游戏小时恰好 1 次：感知时刻 = 预热触发 tick（2700 / 6300），边界全部去重跳过
        assertEquals(4, seenSeconds.size());
        assertEquals(2, seenSeconds.stream().filter(s -> s == 2700L).count(), "小时 1 档预热 2 角色（2700）");
        assertEquals(2, seenSeconds.stream().filter(s -> s == 6300L).count(), "小时 2 档预热 2 角色（6300）");
    }

    /**
     * R1 串行路径（并行度=1/屏障未装配）：预热在引擎线程同步触发（逐行等价改造前行为），
     * 感知时刻=预热触发 tick，边界去重跳过——单测/回退开关语义保证。
     */
    @Test
    void lookaheadSerialPathPrefiresInlineWhenParallelismOne() {
        // 不装配 barrier → 串行路径
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Mockito.doAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        Map<String, Object> r = engine.advanceProject(1L, now);
        assertEquals("advanced", r.get("status"));
        verify(agentRuntime, times(2)).trigger(eq(AgentTrigger.ENGINE_TICK), anyString(), any(DecisionContext.class));
        assertTrue(threads.stream().noneMatch(n -> n.startsWith("world-dispatch-")),
                "并行度=1 时预热应在引擎调用线程同步执行，实际线程=" + threads);
    }

    /**
     * R1 批次预算窗口生命周期：异步派发批次开启独立预算命名空间（BudgetGuard batch 窗口），
     * 批次全部任务完成后窗口关闭（begin/end 成对，无泄漏）。
     */
    @Test
    void lookaheadBatchBudgetWindowClosedAfterBatchDrains() throws Exception {
        TickBarrier barrier = new TickBarrier();
        ReflectionTestUtils.setField(engine, "tickBarrier", barrier);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorWorldClock c = clock(0, 0, 1, now.minusSeconds(150));
        stubBase(now, c);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1, 4), character(2, 3)));
        when(perceptionService.buildPerceptionText(eq(1L), anyString(), any())).thenReturn("【当前所见】测试感知");
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        Mockito.doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(agentRuntime).trigger(any(), anyString(), any(DecisionContext.class));

        engine.advanceProject(1L, now);
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "批次任务应完成");
        assertFalse(budgetGuard.hasBatchWindow(1L), "批次全部完成后 batch 预算窗口应关闭（无泄漏）");
        // 引擎 tick 窗口独立于 batch 窗口：推进结束后 tick 窗口照常关闭
        assertEquals(-1, budgetGuard.budgetTotal(1L), "引擎 tick 预算窗口照常关闭（与 batch 命名空间互不干扰）");
    }

    /**
     * 记录调用序列的屏障替身：仅观测 WorldEngine 的接线（不改屏障语义，继承真实实现）。
     */
    private static final class RecordingBarrier extends TickBarrier {

        private final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public long beginTick(Long projectId) {
            calls.add("begin");
            return super.beginTick(projectId);
        }

        @Override
        public List<Runnable> drainDeferred(Long projectId) {
            calls.add("drain");
            return super.drainDeferred(projectId);
        }

        @Override
        public AwaitResult awaitQuiescence(Long projectId) {
            calls.add("await");
            return super.awaitQuiescence(projectId);
        }

        @Override
        public void endTick(Long projectId) {
            calls.add("end");
            super.endTick(projectId);
        }

        private int count(String call) {
            return (int) calls.stream().filter(call::equals).count();
        }
    }
}
