package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 月度 tick 极端状态回归测试（O1 轮前置修复：月度 tick NPE，D30 极端态复现锁定）。
 * <p>背景（M6 冒烟记录）：段 1 主剧本 D30（首个月度结算日 day%30==0）tickMonthly 反复失败，
 * 异常 getMessage()==null 的 NPE 特征（空值解包），疑似 MacroCultureEngine/MacroTechEngine
 * 在某输入分支（舆情满强度/民怨 100/大旱叠加）空值解包；单测未覆盖该状态组合。</p>
 * <p>本文件 = 极端状态组合回归锁定：舆情满强度（议题强度 1.0）+ 民怨 100 + 极端事件叠加 +
 * 科技满值/失传重置边界 + 可选集合字段缺失（techPrevLevels/techNoInputMonths/fashionSinceDay
 * 为 null）——断言全部不抛异常且 monthly 快照落库。</p>
 */
class MacroMonthlyTickExtremeTest {

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
        props.getThresholds().setPlagueChance(0.0);
        props.getThresholds().setCaravanChance(0.0);
        macroStateService = Mockito.mock(MacroStateService.class);
        macroEventService = Mockito.mock(MacroEventService.class);
        macroFeedbackService = Mockito.mock(MacroFeedbackService.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        eventRepository = Mockito.mock(ActorWorldMacroEventRepository.class);
        npcStateService = Mockito.mock(NpcStateService.class);
        scheduler = new MacroScheduler(props, macroStateService, macroEventService, macroFeedbackService,
                frameRepository, eventRepository, npcStateService, new ObjectMapper());
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

    /** 极端态 A：舆情满强度（议题全 1.0）+ 民怨 100 + 大旱/洪涝/雪灾/瘟疫/饥荒/民变事件叠加。 */
    private MacroState extremeCultureState(long day) {
        MacroState st = baseState(day);
        // 舆情满强度：6 个议题全部强度 1.0（封顶）
        List<MacroState.Issue> issues = new ArrayList<>();
        for (String t : List.of("时局", "生计", "天旱", "粮价", "流言", "信仰")) {
            MacroState.Issue i = new MacroState.Issue();
            i.setTopic(t);
            i.setOrientation(0.8);
            i.setIntensity(1.0);
            issues.add(i);
        }
        st.setIssues(issues);
        st.setGrievance(100.0);
        // 信仰漂到下限附近（信仰危机判定边界）
        st.getBeliefs().put("神祇信仰", 0.06);
        // 习俗超龄（消退边界）
        st.getFashionSinceDay().put("丰收庆典", day - 91);
        return st;
    }

    /** 极端态 B：科技满值（全部 100，突破边界无增量）+ 职业人才爆棚 + 无投入月数 3（衰退失传边界）。 */
    private MacroState extremeTechState(long day) {
        MacroState st = baseState(day);
        st.getTechLevels().put("agriculture", 100.0);
        st.getTechLevels().put("craft", 100.0);
        st.getTechLevels().put("medicine", 9.0);   // 失传边界（<10 且无投入≥3 月 → 重置 5）
        st.getTechLevels().put("array", 90.0);
        st.getTechPrevLevels().putAll(st.getTechLevels());
        st.getTechNoInputMonths().put("medicine", 3);
        st.getTechNoInputMonths().put("array", 0);
        st.getPopulation().getOccupations().put("农户", 10000L);
        st.getPopulation().getOccupations().put("工匠", 10000L);
        // 注意：不给「医者」人才加成——medicine 保持无投入（失传判定路径）
        return st;
    }

    /** 极端态 C：可选集合字段缺失（techPrevLevels/techNoInputMonths/fashionSinceDay=null——
     * 快照反序列化异常/旧版快照可能出现的形态）。 */
    private MacroState missingOptionalMapsState(long day) {
        MacroState st = baseState(day);
        st.setTechPrevLevels(null);
        st.setTechNoInputMonths(null);
        st.setFashionSinceDay(null);
        return st;
    }

    /** 基础状态（与 MacroSchedulerTest.state 同构）。 */
    private MacroState baseState(long day) {
        MacroState st = new MacroState();
        st.setProjectId(100L);
        st.setGameDay(day);
        st.setTemplateId(MacroTemplate.DEFAULT_ID);
        st.getEconomy().setGrainPrice(60.0);
        st.getEconomy().setGrainStock(800.0);
        st.setGrievance(0.0);
        st.getTechLevels().put("agriculture", 35.0);
        st.getTechLevels().put("craft", 30.0);
        st.getTechLevels().put("medicine", 30.0);
        st.getTechLevels().put("array", 25.0);
        st.getTechPrevLevels().putAll(st.getTechLevels());
        Map<String, MacroState.Faction> factions = new LinkedHashMap<>();
        for (MacroTemplate.GroupTemplate g : MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID).groupTemplates()) {
            MacroState.Faction f = new MacroState.Faction();
            f.setName(g.name());
            f.setInfluence(g.influence());
            f.setMemberCount(10L);
            f.setRelations(new LinkedHashMap<>());
            factions.put(g.name(), f);
        }
        st.setFactions(factions);
        return st;
    }

    /** 极端态 D：集合字段整体缺失（issues/beliefs/fashions/techLevels=null——最坏形态防御）。 */
    private MacroState nullCollectionsState(long day) {
        MacroState st = baseState(day);
        st.setIssues(null);
        st.setBeliefs(null);
        st.setFashions(null);
        st.setTechLevels(null);
        return st;
    }

    /** 极端态 E：科技值含 null（个别维度值为 null——空值解包 NPE 的直接嫌疑形态）。 */
    private MacroState nullTechValueState(long day) {
        MacroState st = baseState(day);
        st.getTechLevels().put("craft", null);
        st.getTechPrevLevels().put("craft", null);
        return st;
    }

    /** 极端态 F：信仰值含 null（个别信仰强度为 null——CultureEngine 空值解包直接嫌疑形态）。 */
    private MacroState nullBeliefValueState(long day) {
        MacroState st = baseState(day);
        st.getBeliefs().put("禁忌信仰", null);
        return st;
    }

    private void runMonthly(long day, MacroState st) {
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        List<ActorWorldMacroEvent> monthEvents = List.of(
                evt("drought"), evt("drought"), evt("flood"), evt("blizzard"),
                evt("plague"), evt("famine"), evt("revolt_risk"), evt("harvest_good"), evt("relief"));
        when(eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(monthEvents);
        scheduler.tickIfDue(100L, day * 86400L + 15 * 60L); // 月首 00:15
        scheduler.awaitIdle(2000); // R1 异步化：tickMonthly 在宏执行器线程派发，等待落盘后再断言
    }

    private static ActorWorldMacroEvent evt(String type) {
        ActorWorldMacroEvent e = new ActorWorldMacroEvent();
        e.setProjectId(100L);
        e.setEventType(type);
        e.setGameDay(20L);
        return e;
    }

    // ==================== 回归断言 ====================

    /** 极端态 A（舆情满强度+民怨 100+事件叠加）：不抛异常且 monthly 快照落库。 */
    @Test
    void monthlyTickExtremeCultureStateNoNpe() {
        MacroState st = extremeCultureState(30L);
        runMonthly(30L, st);
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
        MacroState after = cap.getValue();
        assertEquals(100.0, after.getGrievance(), 0.001, "民怨保持封顶");
        assertEquals(30L, after.getGameDay(), "游戏日推进");
    }

    /** 极端态 B（科技满值/失传重置边界）：不抛异常且科技演进完成。 */
    @Test
    void monthlyTickExtremeTechStateNoNpe() {
        MacroState st = extremeTechState(30L);
        runMonthly(30L, st);
        org.mockito.ArgumentCaptor<MacroState> cap = org.mockito.ArgumentCaptor.forClass(MacroState.class);
        verify(macroStateService).saveSnapshot(eq(100L), cap.capture(), eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
        MacroState after = cap.getValue();
        assertEquals(5.0, after.getTechLevels().get("medicine"), 0.001, "失传重置 5（<10 且无投入≥3 月）");
        assertEquals(100.0, after.getTechLevels().get("agriculture"), 0.001, "满值保持钳制");
    }

    /** 极端态 C（可选集合字段缺失）：防御归一化后不抛异常且快照落库。 */
    @Test
    void monthlyTickMissingOptionalMapsNoNpe() {
        assertDoesNotThrow(() -> {
            MacroState st = missingOptionalMapsState(30L);
            runMonthly(30L, st);
        });
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
    }

    /** 极端态 D（集合字段整体缺失最坏形态）：防御归一化后不抛异常且快照落库。 */
    @Test
    void monthlyTickNullCollectionsNoNpe() {
        assertDoesNotThrow(() -> {
            MacroState st = nullCollectionsState(30L);
            runMonthly(30L, st);
        });
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
    }

    /** 极端态 E（科技值含 null 空值解包嫌疑）：防御归一化后不抛异常。 */
    @Test
    void monthlyTickNullTechValueNoNpe() {
        assertDoesNotThrow(() -> {
            MacroState st = nullTechValueState(30L);
            runMonthly(30L, st);
        });
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
    }

    /** 极端态 F（信仰值含 null 空值解包嫌疑）：防御归一化后不抛异常。 */
    @Test
    void monthlyTickNullBeliefValueNoNpe() {
        assertDoesNotThrow(() -> {
            MacroState st = nullBeliefValueState(30L);
            runMonthly(30L, st);
        });
        verify(macroStateService).saveSnapshot(eq(100L), any(MacroState.class),
                eq(ActorWorldMacroSnapshot.TICK_MONTHLY), anyLong());
    }
}
