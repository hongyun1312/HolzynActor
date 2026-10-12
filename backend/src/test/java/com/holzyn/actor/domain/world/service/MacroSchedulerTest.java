package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观多周期调度器单元测试（宏观社会演化系统 M1~M4，domain/world/service，同包）。
 * <p>职责：验证 tick 窗口判定（00:05/00:10/00:15 槽、其他分钟不触发、同日幂等、旬/月周期）、
 * 每日 tick 链路（M3：收成链→经济→民怨→生计联动；入账汇总→快照→帧板块）、每旬势力 tick
 * （民怨/收成修正 + 赈济/民变）、每月文化科技 tick、总开关关闭零成本跳过。</p>
 */
class MacroSchedulerTest {

    private MacroProperties props;
    private MacroStateService macroStateService;
    private MacroEventService macroEventService;
    private MacroFeedbackService macroFeedbackService;
    private ActorWorldFrameRepository frameRepository;
    private ActorWorldMacroEventRepository eventRepository;
    private NpcStateService npcStateService;
    private MacroScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new MacroProperties();
        props.getThresholds().setPlagueChance(0.0); // 测试确定性：无随机瘟疫
        props.getThresholds().setCaravanChance(0.0); // 无随机商队
        macroStateService = Mockito.mock(MacroStateService.class);
        macroEventService = Mockito.mock(MacroEventService.class);
        macroFeedbackService = Mockito.mock(MacroFeedbackService.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        eventRepository = Mockito.mock(ActorWorldMacroEventRepository.class);
        npcStateService = Mockito.mock(NpcStateService.class);
        scheduler = new MacroScheduler(props, macroStateService, macroEventService, macroFeedbackService,
                frameRepository, eventRepository, npcStateService, new ObjectMapper());
        when(macroStateService.stateOf(anyLong())).thenAnswer(inv -> {
            MacroState st = new MacroState();
            st.setProjectId(inv.getArgument(0));
            // 与经济引擎模型自洽：库存 800 石 → 均衡价≈60（避免首日误报粮价暴涨）
            st.getEconomy().setGrainPrice(60.0);
            return st;
        });
        when(macroStateService.templateOf(anyLong()))
                .thenReturn(MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID));
        when(macroStateService.aggregateDeltas(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(Map.of());
        when(macroStateService.pickNamedNpc(anyLong(), anyLong())).thenReturn(null);
        when(frameRepository.findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
    }

    /** R1 异步化辅助：触发宏观 tick 并等待异步任务排空（虚拟线程执行，mock 快任务微秒级完成）。 */
    private void fire(Long projectId, Long gameSecond) {
        scheduler.tickIfDue(projectId, gameSecond);
        scheduler.awaitIdle(2000);
    }

    /**
     * 00:05 槽触发每日 tick。
     */
    @Test
    void dailyTickFiresAtMinute5() {
        long day5 = 5L * 86400L + 5 * 60L; // 第 6 日 00:05
        fire(100L, day5);
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
        verify(macroFeedbackService).writeFrameSummary(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY));
    }

    /**
     * 00:00（帧生成时刻）不触发每日 tick——与帧生成错峰成立。
     */
    @Test
    void dailyTickNotAtMinute0() {
        fire(100L, 5L * 86400L); // 00:00
        verify(macroStateService, never()).saveSnapshot(anyLong(), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
    }

    /**
     * 同日幂等：00:05 触发一次，同窗口后续 tick 不重复。
     */
    @Test
    void dailyTickIdempotentPerDay() {
        long day5 = 5L * 86400L + 5 * 60L;
        fire(100L, day5);
        fire(100L, day5 + 2 * 60L); // 00:07 仍在窗口
        fire(100L, 5L * 86400L + 10 * 60L); // 00:10 越过窗口
        verify(macroStateService, times(1)).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
    }

    /**
     * 每旬首日（day%10==0）00:10 槽触发势力 tick。
     */
    @Test
    void factionTickFiresOnXunBoundary() {
        fire(100L, 20L * 86400L + 10 * 60L); // day 20（%10==0，旬首）00:10
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_FACTION), anyLong());
    }

    /**
     * 非旬首日 00:10 不触发势力 tick。
     */
    @Test
    void factionTickNotOnNonXunDay() {
        fire(100L, 15L * 86400L + 10 * 60L); // day 15 非旬首
        verify(macroStateService, never()).saveSnapshot(anyLong(), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_FACTION), anyLong());
    }

    /**
     * 每月首日（day%30==0）00:15 槽触发 monthly 快照（M1 仅落行）。
     */
    @Test
    void monthlyTickFiresOnMonthBoundary() {
        fire(100L, 30L * 86400L + 15 * 60L); // day 30（%30==0，月首）
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
    }

    /**
     * 每日 tick 完整链路：前一游戏日入账汇总 → 经济/人口演进 → daily 快照 → 帧板块简报。
     * 入账市场增量使市集活跃度 > 50（M2 可归因）。
     */
    @Test
    void dailyTickAppliesLedgerDeltas() {
        when(macroStateService.aggregateDeltas(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(Map.of("market_activity", 5.0));
        fire(100L, 30L * 86400L + 5 * 60L);
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
        verify(macroFeedbackService).writeFrameSummary(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY));
        // 平安日（无瘟疫/无商队/产量偏差小）→ 零规则事件
        verify(macroEventService, never()).emit(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyInt());
    }

    /**
     * 宏观层总开关关闭 → tickIfDue 直接跳过（零成本）。
     */
    @Test
    void disabledSkipsAll() {
        props.setEnabled(false);
        fire(100L, 5L * 86400L + 5 * 60L);
        verify(macroStateService, never()).saveSnapshot(anyLong(), any(MacroState.class), anyString(), anyLong());
    }

    /**
     * 世界帧「社会宏观」板块为回灌路①：帧板块写入依赖 active 帧存在（writeFrameSummary 被调用）。
     */
    @Test
    void frameSummaryWrittenOnDailyTick() {
        fire(100L, 2L * 86400L + 5 * 60L);
        verify(macroFeedbackService, times(1)).writeFrameSummary(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_DAILY));
    }

    /** 简单占位断言（防空测试类警告）。 */
    @Test
    void schedulePropertiesDefaults() {
        assertTrue(props.getSchedule().getDaysPerXun() == 10);
        assertTrue(props.getSchedule().getDaysPerMonth() == 30);
        assertTrue(props.getSchedule().getDailyMinute() == 5);
        assertTrue(props.getSchedule().getFactionMinute() == 10);
        assertTrue(props.getSchedule().getMonthlyMinute() == 15);
    }

    // ==================== M3 全链联动（收成→粮价→生计→民怨→势力/舆论） ====================

    /**
     * M3 收成链：每日 tick 填充季节/收成系数（季节基准×模板缩放×天气累积），并注入经济引擎。
     * day 30 = 游戏月 2 → 春季（1-3 月），无帧天气 → 收成系数 = 1+(0.9-1)×1.0 = 0.9。
     */
    @Test
    void dailyTickFillsHarvestChain() {
        when(frameRepository.findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(anyLong(), anyLong()))
                .thenReturn(Optional.of(frameWithWeather(null)));
        fire(100L, 30L * 86400L + 5 * 60L); // day 30 00:05（春）
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
        MacroState st = cap.getValue();
        assertEquals(SeasonService.SPRING, st.getEconomy().getSeason(), "day 30=月 2=春季");
        assertEquals(0.9, st.getEconomy().getHarvestFactor(), 0.001, "春季基准 0.9（模板缩放 1.0）");
        assertNotNull(st.getEconomy().getGrainPrice(), "经济引擎应产出粮价");
    }

    /**
     * M3 收成天气源：最近帧（≤当日）天气注入收成系数——小雨 +5% 利生长（夏 1.05 → 1.1025）。
     */
    @Test
    void dailyTickUsesFrameWeather() {
        MacroState st = state(30L);
        st.getEconomy().setGrainPrice(60.0);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        when(frameRepository.findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(anyLong(), anyLong()))
                .thenReturn(Optional.of(frameWithWeather("{\"本村\":{\"condition\":\"小雨\",\"temperature\":22,\"wind\":\"东风\"}}")));
        fire(100L, 90L * 86400L + 5 * 60L); // day 90 = 月 4 → 夏季
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
        assertEquals(SeasonService.SUMMER, cap.getValue().getEconomy().getSeason());
        assertEquals("小雨", cap.getValue().getEconomy().getWeatherCondition());
        assertEquals(1.1025, cap.getValue().getEconomy().getHarvestFactor(), 0.001, "夏 1.05×1.05");
    }

    /**
     * M3 极端季节事件：生长季连续 7 日无雨（dryStreak=6 输入）→ 大旱事件（emitDetailed）+ 民怨上升。
     */
    @Test
    void dailyTickDroughtEmitsEventAndGrievance() {
        MacroState st = state(90L); // 夏季
        st.getEconomy().setDryStreak(6);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        when(frameRepository.findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(anyLong(), anyLong()))
                .thenReturn(Optional.of(frameWithWeather("{\"本村\":{\"condition\":\"晴\",\"temperature\":30,\"wind\":\"南风\"}}")));
        fire(100L, 90L * 86400L + 5 * 60L);
        verify(macroEventService).emitDetailed(eq(100L), anyLong(), anyLong(), eq("economy"), eq("drought"),
                anyString(), any(), any(), any(), any(), anyInt(), any());
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_DAILY), anyLong());
        assertTrue(cap.getValue().getGrievance() > 0, "大旱应推高民怨");
        assertEquals(7, cap.getValue().getEconomy().getDryStreak(), "旱情累积推进到 7");
    }

    /**
     * M3 生计联动：粮价高 → 普通 NPC 状态参数联动调用（applyMacroLivelihoodAdjust）。
     */
    @Test
    void dailyTickAppliesLivelihoodAdjust() {
        MacroState st = state(30L);
        st.getEconomy().setGrainPrice(60.0); // 均衡价（基线）
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        fire(100L, 30L * 86400L + 5 * 60L);
        verify(npcStateService).applyMacroLivelihoodAdjust(eq(100L), anyDouble());
    }

    /**
     * M3 赈济负反馈环：民怨 ≥50 + 当权有实力 → 开仓赈济（粮价回落/库存减/民怨降）+ 事件产出。
     */
    @Test
    void factionTickReliefLoop() {
        MacroState st = state(20L);
        st.setGrievance(60.0);
        st.getFactions().get("村中宗族").setInfluence(60.0);
        st.getFactions().get("庙宇祭司一脉").setInfluence(55.0);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        fire(100L, 20L * 86400L + 10 * 60L); // 旬首 00:10
        verify(macroEventService).emitDetailed(eq(100L), anyLong(), anyLong(), eq("economy"), eq("relief"),
                anyString(), any(), any(), any(), any(), anyInt(), any());
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_FACTION), anyLong());
        MacroState after = cap.getValue();
        assertTrue(after.getGrievance() < 60.0, "赈济后民怨应下降，实际 " + after.getGrievance());
        assertTrue(after.getEconomy().getGrainStock() < 800.0, "赈济消耗库存");
        assertTrue(after.getEconomy().getGrainPrice() < 60.0, "赈济后粮价回落");
    }

    // ==================== M4 文化科技月度演化 ====================

    /**
     * M4 每月 tick：宏观事件聚合 → 信息包话题 → 谣言成风事件（≥3 话题）+ 议题/科技演进 + 快照。
     */
    @Test
    void monthlyTickEvolvesCultureAndTech() {
        MacroState st = state(30L);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        List<com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent> monthEvents = List.of(
                macroEvt("price_surge"), macroEvt("drought"), macroEvt("revolt_risk"));
        when(eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(monthEvents);
        fire(100L, 30L * 86400L + 15 * 60L); // 月首 00:15
        verify(macroEventService).emitDetailed(eq(100L), anyLong(), anyLong(), eq("culture"), eq("rumor"),
                anyString(), any(), any(), any(), any(), anyInt(), any());
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
        MacroState after = cap.getValue();
        assertNotNull(after.getTechPrevLevels(), "上月科技值应更新（下月突破判定基准）");
        assertTrue(after.getTechLevels().containsKey("agriculture"), "科技维度保留");
        assertTrue(after.getFashionSinceDay() != null);
    }

    /** M4 技艺突破联动效果：农业跨 50 里程碑 → techHarvestBonus +0.05（产量加成）。 */
    @Test
    void monthlyTickTechBreakthroughAppliesBonus() {
        MacroState st = state(30L);
        st.getTechLevels().put("agriculture", 45.0);
        st.getTechPrevLevels().put("agriculture", 45.0);
        st.getPopulation().getOccupations().put("农户", 160L); // 人才加成推过 50 里程碑
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        when(macroStateService.aggregateDeltas(anyLong(), anyLong(), anyLong(), eq("tech")))
                .thenReturn(Map.of("agriculture", 1.0));
        when(macroStateService.aggregateDeltas(anyLong(), anyLong(), anyLong(), eq("culture")))
                .thenReturn(Map.of());
        when(macroStateService.aggregateDeltas(anyLong(), anyLong(), anyLong(), eq("economy")))
                .thenReturn(Map.of());
        fire(100L, 30L * 86400L + 15 * 60L);
        verify(macroEventService).emitDetailed(eq(100L), anyLong(), anyLong(), eq("tech"), eq("tech_breakthrough:agriculture"),
                anyString(), any(), any(), any(), any(), anyInt(), any());
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
        MacroState after = cap.getValue();
        assertEquals(0.05, after.getEconomy().getTechHarvestBonus(), 0.001, "农业突破产量加成 +5%");
    }

    // ==================== 测试辅助 ====================

    /** 最小宏观状态（默认模板语义：通用初始值 + 模板骨架势力集）。 */
    private MacroState state(long day) {
        MacroState st = new MacroState();
        st.setProjectId(100L);
        st.setGameDay(day);
        st.setTemplateId(MacroTemplate.DEFAULT_ID);
        st.getEconomy().setGrainPrice(60.0);
        st.getEconomy().setGrainStock(800.0);
        st.setGrievance(0.0);
        // 默认科技维度（月度 tick 演进/突破判定基准；与 MacroStateService.defaultState 同构）
        st.getTechLevels().put("agriculture", 35.0);
        st.getTechLevels().put("craft", 30.0);
        st.getTechLevels().put("medicine", 30.0);
        st.getTechLevels().put("array", 25.0);
        st.getTechPrevLevels().putAll(st.getTechLevels());
        // 势力集 = 默认模板的中观组织骨架（M6.5a：不再使用任何硬编码势力名）
        Map<String, MacroState.Faction> factions = new java.util.LinkedHashMap<>();
        for (MacroTemplate.GroupTemplate g : MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID).groupTemplates()) {
            MacroState.Faction f = new MacroState.Faction();
            f.setName(g.name());
            f.setInfluence(g.influence());
            f.setMemberCount(10L);
            f.setRelations(new java.util.LinkedHashMap<>());
            factions.put(g.name(), f);
        }
        st.setFactions(factions);
        return st;
    }

    /** 带 weatherJson 的帧（可空=无天气信息）。 */
    private com.holzyn.actor.domain.world.entity.ActorWorldFrame frameWithWeather(String weatherJson) {
        com.holzyn.actor.domain.world.entity.ActorWorldFrame f =
                new com.holzyn.actor.domain.world.entity.ActorWorldFrame();
        f.setProjectId(100L);
        f.setGameDay(1L);
        f.setWeatherJson(weatherJson);
        return f;
    }

    /** 宏观事件构造（月度聚合源）。 */
    private com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent macroEvt(String type) {
        com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent e =
                new com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent();
        e.setProjectId(100L);
        e.setEventType(type);
        e.setGameDay(20L);
        return e;
    }
}
