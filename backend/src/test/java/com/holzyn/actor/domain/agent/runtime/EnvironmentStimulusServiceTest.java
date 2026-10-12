package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.service.WorldFrameParse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环境刺激服务单元测试（实时行为决策系统 M5，domain/agent/runtime，同包）。
 * <p>职责：验证——① 帧 diff 纯逻辑（天气变化/新增区域/温度变化/环境变化新增/未变跳过/宏观事件
 * 不做 diff/条数上限护栏②）；② 区域匹配；③ 实例链路（全局天气→全部特殊 NPC 即时决策、
 * 区域变化→区域 NPC、帧指纹幂等护栏③④、每日 AI 预算护栏⑤、扩散到达事件特判、人群轻量 AI 预算门控）。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径_V1.0》§7 M5 + 用户决策 Q2/Q3/Q4/Q5。</p>
 */
class EnvironmentStimulusServiceTest {

    private RealtimeDecisionProperties props;
    private WorldEventBus worldEventBus;
    private RealTimeDecisionService realTimeDecisionService;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private ActorWorldFrameRepository frameRepository;
    private BudgetGuard budgetGuard;
    private EnvironmentStimulusService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        worldEventBus = Mockito.mock(WorldEventBus.class);
        realTimeDecisionService = Mockito.mock(RealTimeDecisionService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        budgetGuard = Mockito.mock(BudgetGuard.class);
        when(budgetGuard.tryConsume(any())).thenReturn(true);

        service = new EnvironmentStimulusService(props, worldEventBus, realTimeDecisionService,
                characterRepository, ordinaryNpcRepository, frameRepository, budgetGuard);
        // 默认无特殊 NPC/人群（避免 mock 默认 null）
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
    }

    // ==================== 帧 diff 纯逻辑（静态） ====================

    private static WorldFrameParse.FrameJson frame(Map<String, WorldFrameParse.WeatherEntry> weather,
                                                    List<WorldFrameParse.EnvChange> env) {
        return new WorldFrameParse.FrameJson(1L, weather, List.of(),
                List.of(), env, null, new ArrayList<>());
    }

    @Test
    void diffFrames_weatherChangeDetected() {
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(prevW, List.of()), frame(currW, List.of()), 5);
        assertEquals(1, diff.size());
        assertEquals("weather:青禾村", diff.get(0).key());
        assertEquals(null, diff.get(0).region(), "天气变化按全局定位（天空级现象，覆盖整个区域）");
        assertTrue(diff.get(0).text().contains("晴"));
        assertTrue(diff.get(0).text().contains("暴雨"));
    }

    @Test
    void diffFrames_weatherUnchangedSkipped() {
        Map<String, WorldFrameParse.WeatherEntry> w = new LinkedHashMap<>();
        w.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(w, List.of()), frame(w, List.of()), 5);
        assertTrue(diff.isEmpty(), "天气未变不触发");
    }

    @Test
    void diffFrames_temperatureChangeDetected() {
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 25d, null));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(prevW, List.of()), frame(currW, List.of()), 5);
        assertEquals(1, diff.size(), "温差 ≥5° 视为天气变化");
        assertTrue(diff.get(0).text().contains("25°"));
    }

    @Test
    void diffFrames_newRegionDetected() {
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        currW.put("后山", new WorldFrameParse.WeatherEntry("雾", null, null));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(prevW, List.of()), frame(currW, List.of()), 5);
        assertEquals(1, diff.size(), "新增区域视为变化");
        assertEquals("weather:后山", diff.get(0).key());
    }

    @Test
    void diffFrames_envChangeNewDetected() {
        List<WorldFrameParse.EnvChange> currEnv = List.of(
                new WorldFrameParse.EnvChange("集市", "摊贩匆匆收摊", "人流减少"));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(Map.of(), List.of()),
                        frame(Map.of(), currEnv), 5);
        assertEquals(1, diff.size());
        assertTrue(diff.get(0).key().startsWith("env:"));
        assertEquals("集市", diff.get(0).region());
        assertTrue(diff.get(0).text().contains("摊贩匆匆收摊"));
    }

    @Test
    void diffFrames_envChangeExistingSkipped() {
        List<WorldFrameParse.EnvChange> env = List.of(
                new WorldFrameParse.EnvChange("集市", "摊贩匆匆收摊", "人流减少"));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(Map.of(), env), frame(Map.of(), env), 5);
        assertTrue(diff.isEmpty(), "已存在的环境变化不重复触发");
    }

    @Test
    void diffFrames_capLimit() {
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("甲", new WorldFrameParse.WeatherEntry("雨", null, null));
        currW.put("乙", new WorldFrameParse.WeatherEntry("雨", null, null));
        currW.put("丙", new WorldFrameParse.WeatherEntry("雨", null, null));
        List<EnvironmentStimulusService.EnvChangeEntry> diff =
                EnvironmentStimulusService.diffFrames(frame(prevW, List.of()), frame(currW, List.of()), 2);
        assertEquals(2, diff.size(), "单帧 diff 条数上限护栏②生效");
    }

    @Test
    void diffFrames_macroEventsNotDiffed() {
        // 宏观事件差异不做帧 diff（M1 事件通道已覆盖 severity≥3 全局触发，避免双触发）
        WorldFrameParse.FrameJson prev = new WorldFrameParse.FrameJson(1L, Map.of(),
                List.of(new WorldFrameParse.MacroEvent("E1", "异动", "集市", 2, "旧摘要", 1L, 3L, List.of("集市"))),
                List.of(), List.of(), null, new ArrayList<>());
        WorldFrameParse.FrameJson curr = new WorldFrameParse.FrameJson(1L, Map.of(),
                List.of(new WorldFrameParse.MacroEvent("E2", "异动", "集市", 4, "新摘要", 1L, 3L, List.of("集市"))),
                List.of(), List.of(), null, new ArrayList<>());
        assertTrue(EnvironmentStimulusService.diffFrames(prev, curr, 5).isEmpty(), "宏观事件差异不做帧 diff");
    }

    @Test
    void matchesRegionVariants() {
        assertTrue(EnvironmentStimulusService.matchesRegion("青禾村·集市", "青禾村"), "地点包含区域");
        assertTrue(EnvironmentStimulusService.matchesRegion("集市", "青禾村·集市"), "区域包含地点");
        assertTrue(EnvironmentStimulusService.matchesRegion("集市", " 集市 "), "归一化相等（trim）");
        assertFalse(EnvironmentStimulusService.matchesRegion("村口牌坊", "后山"), "无关区域不匹配");
        assertFalse(EnvironmentStimulusService.matchesRegion(null, "青禾村"), "空地点不匹配");
        assertFalse(EnvironmentStimulusService.matchesRegion("集市", null), "空区域不匹配");
    }

    // ==================== 实例链路 ====================

    private ActorCharacter newChar(Long id, String name, String location) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setLocation(location);
        c.setProjectId(1L);
        c.setDeleted(0);
        return c;
    }

    private static WorldFrameParse.FrameJson weatherFrame(Map<String, WorldFrameParse.WeatherEntry> weather) {
        return new WorldFrameParse.FrameJson(1L, weather, List.of(), List.of(), List.of(), null, new ArrayList<>());
    }

    @Test
    void onFrameChanged_globalWeatherTriggersAllSpecial() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市"), newChar(2L, "乙", "村口牌坊")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        service.onFrameChanged(1L, weatherFrame(prevW), weatherFrame(currW), 100000L);
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"), eq(StimulusType.ENVIRONMENT), anyString(), eq(100000L));
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:2"), eq(StimulusType.ENVIRONMENT), anyString(), eq(100000L));
    }

    @Test
    void onFrameChanged_fingerprintIdempotent() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        WorldFrameParse.FrameJson prev = weatherFrame(prevW);
        WorldFrameParse.FrameJson curr = weatherFrame(currW);
        service.onFrameChanged(1L, prev, curr, 100000L);
        service.onFrameChanged(1L, prev, curr, 101000L);
        verify(realTimeDecisionService, Mockito.times(1))
                .onStimulus(eq(1L), eq("npc:1"), eq(StimulusType.ENVIRONMENT), anyString(), any());
    }

    @Test
    void onFrameChanged_regionalEnvChangeTriggersRegionOnly() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市"), newChar(2L, "乙", "后山")));
        List<WorldFrameParse.EnvChange> currEnv = List.of(
                new WorldFrameParse.EnvChange("集市", "摊贩匆匆收摊", "人流减少"));
        WorldFrameParse.FrameJson curr = new WorldFrameParse.FrameJson(1L, Map.of(), List.of(),
                List.of(), currEnv, null, new ArrayList<>());
        service.onFrameChanged(1L, weatherFrame(Map.of()), curr, 100000L);
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"), eq(StimulusType.ENVIRONMENT), anyString(), eq(100000L));
        verify(realTimeDecisionService, never())
                .onStimulus(eq(1L), eq("npc:2"), eq(StimulusType.ENVIRONMENT), anyString(), any());
    }

    @Test
    void onFrameChanged_dailyBudgetExhaustedSkips() {
        props.setEnvironmentDailyMax(1);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        WorldFrameParse.FrameJson prev = weatherFrame(prevW);
        WorldFrameParse.FrameJson curr = weatherFrame(currW);
        service.onFrameChanged(1L, prev, curr, 100000L); // 消耗当日预算 1 → 触发
        // 第二次（新指纹：天气再变 雾）→ 当日预算耗尽 → 跳过
        Map<String, WorldFrameParse.WeatherEntry> currW2 = new LinkedHashMap<>();
        currW2.put("青禾村", new WorldFrameParse.WeatherEntry("雾", 15d, null));
        service.onFrameChanged(1L, curr, weatherFrame(currW2), 106000L);
        verify(realTimeDecisionService, Mockito.times(1))
                .onStimulus(eq(1L), eq("npc:1"), eq(StimulusType.ENVIRONMENT), anyString(), any());
    }

    @Test
    void onFrameChanged_disabledSkips() {
        props.setEnvironmentEnabled(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        service.onFrameChanged(1L, weatherFrame(prevW), weatherFrame(currW), 100000L);
        verify(realTimeDecisionService, never()).onStimulus(any(), any(), any(), anyString(), any());
    }

    @Test
    void onEvent_diffusionArrivalTriggersRegionNpc() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市"), newChar(2L, "乙", "后山")));
        ActorEvent e = new ActorEvent();
        e.setProjectId(1L);
        e.setKind("world_frame");
        e.setSourceKey("diffusion:1:5:99:集市");
        e.setLocationName("集市");
        e.setTitle("后山灵脉异动传闻");
        e.setGameHour(100L);
        service.onEvent(e);
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"), eq(StimulusType.ENVIRONMENT), anyString(), eq(360000L));
        verify(realTimeDecisionService, never())
                .onStimulus(eq(1L), eq("npc:2"), eq(StimulusType.ENVIRONMENT), anyString(), any());
    }

    @Test
    void onEvent_nonDiffusionIgnored() {
        ActorEvent e = new ActorEvent();
        e.setProjectId(1L);
        e.setKind("world_frame");
        e.setSourceKey("frame:1:5:E-5-001");
        e.setLocationName("集市");
        service.onEvent(e);
        verify(realTimeDecisionService, never()).onStimulus(any(), any(), any(), anyString(), any());
    }

    @Test
    void onFrameChanged_crowdReactionBudgetGated() throws Exception {
        // 装配人群轻量 AI 依赖（用户决策 Q4：特殊全量 + 人群轻量 AI[预算门控]）
        NpcStateService npcStateService = Mockito.mock(NpcStateService.class);
        java.lang.reflect.Field f = EnvironmentStimulusService.class.getDeclaredField("npcStateService");
        f.setAccessible(true);
        f.set(service, npcStateService);
        when(npcStateService.isCrowdAiEligible(any(), any())).thenReturn(true);
        // R1 异步化：人群决策在虚拟线程执行，用 latch 等其落地再断言
        java.util.concurrent.CountDownLatch decided = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> decideThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(npcStateService.decideCrowdAction(any(), any(), any(), anyString())).thenAnswer(inv -> {
            decideThread.set(Thread.currentThread().getName());
            decided.countDown();
            return new com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse.CrowdAction(
                    "收摊避雨", "custom", "屋檐下", "环境反应");
        });
        // 抓修（2026-09-30）：仅等 decide 未等 apply → 全量套件负载下虚拟线程在 countDown 后被调度走，
        // verify(applyCrowdAction) 落空（异步竞态，非注释规范化引入）。补 apply latch 同步后再断言。
        java.util.concurrent.CountDownLatch applied = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            applied.countDown();
            return null;
        }).when(npcStateService).applyCrowdAction(any(), any(), any(), anyLong());
        ActorCharacter n = new ActorCharacter();
        n.setId(39L);
        n.setProjectId(1L);
        n.setName("铁匠");
        n.setLocation("集市");
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(n));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(newChar(1L, "甲", "集市")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        service.onFrameChanged(1L, weatherFrame(prevW), weatherFrame(currW), 100000L);
        assertTrue(decided.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "人群反应应在异步执行器内完成（R1 引擎线程不跑同步 AI）");
        assertTrue(applied.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "人群反应 apply 也应在异步执行器内完成（避免 decide/apply 间竞态）");
        // R1 关键断言：预算在调用线程预扣（保持 tick 窗口记账语义）+ 决策在异线程执行（引擎不阻塞）
        verify(budgetGuard).tryConsume(1L);
        assertFalse("main".equals(decideThread.get()) || decideThread.get() == null,
                "人群决策不得在调用线程（引擎线程）同步执行，实际线程=" + decideThread.get());
        verify(npcStateService).decideCrowdAction(eq(1L), eq(n), eq(100000L), anyString());
        verify(npcStateService).applyCrowdAction(eq(1L), eq(n), any(), eq(100000L));
    }
}
