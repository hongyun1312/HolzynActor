package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 宏观感知上下文服务单元测试（宏观社会演化系统 M2，domain/world/service，同包，回灌路④）。
 * <p>职责：验证【宏观环境】段组装（粮价/势力局势/舆论议题、预算内）、未启用/无状态返回空串
 * （零阻断）。</p>
 */
class MacroContextServiceTest {

    private MacroProperties props;
    private MacroStateService macroStateService;
    private MacroContextService service;

    @BeforeEach
    void setUp() {
        props = new MacroProperties();
        macroStateService = Mockito.mock(MacroStateService.class);
        service = new MacroContextService(props, macroStateService,
                Mockito.mock(ActorCharacterRepository.class));
    }

    /**
     * 默认状态 → 【宏观环境】段含粮价、最有权势势力、最强议题。
     */
    @Test
    void segmentContainsPriceFactionAndIssue() {
        MacroState st = new MacroState();
        st.getEconomy().setGrainPrice(100.0);
        MacroState.Faction top = new MacroState.Faction();
        top.setName("王氏宗族");
        top.setLeaderRef(null);
        top.setInfluence(60.0);
        st.getFactions().put("王氏宗族", top);
        MacroState.Issue issue = new MacroState.Issue();
        issue.setTopic("时局");
        issue.setIntensity(0.6);
        st.getIssues().add(issue);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);

        String seg = service.macroDynamicsSegment(100L);
        assertTrue(seg.startsWith("【宏观环境】"));
        assertTrue(seg.contains("粮价 100"));
        assertTrue(seg.contains("王氏宗族"));
        assertTrue(seg.contains("时局"));
        assertTrue(seg.length() <= 200, "预算内（≤150 字 + 段头）");
    }

    /**
     * 未启用 → 空串（零阻断）。
     */
    @Test
    void disabledReturnsEmpty() {
        props.setEnabled(false);
        assertFalse(service.macroDynamicsSegment(100L).length() > 0);
    }

    /**
     * 低强度议题（<0.5）不注入舆论行；经济压力高 → 粮价备注「贵了不少」。
     */
    @Test
    void weakIssueOmittedAndPriceNote() {
        MacroState st = new MacroState();
        st.getEconomy().setGrainPrice(130.0); // 1.3 倍基准 → 贵了不少
        MacroState.Issue issue = new MacroState.Issue();
        issue.setTopic("民生");
        issue.setIntensity(0.4);
        st.getIssues().add(issue);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        String seg = service.macroDynamicsSegment(100L);
        assertTrue(seg.contains("贵了不少"));
        assertFalse(seg.contains("民生"), "低强度议题不注入");
    }

    // ==================== M3 生计压力段（行为参数联动 v2） ====================

    /**
     * 【生计压力】段：粮价贵（1.5 倍）→ 「算计着吃」；民怨高 → 怨气描述；收成差 → 发愁。
     */
    @Test
    void livelihoodSegmentReflectsPressure() {
        MacroState st = new MacroState();
        st.getEconomy().setGrainPrice(150.0); // 1.5 倍基准
        st.getEconomy().setSeason(SeasonService.AUTUMN);
        st.getEconomy().setWeatherCondition("晴");
        st.getEconomy().setHarvestFactor(0.6);
        st.getEconomy().setHarvestOutcome(-1);
        st.setGrievance(75.0);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        String seg = service.livelihoodSegment(100L);
        assertTrue(seg.startsWith("【生计压力】"));
        assertTrue(seg.contains("算计着吃"), "粮价贵应有生计压力描述");
        assertTrue(seg.contains("怨气"), "高民怨应有怨气描述");
        assertTrue(seg.contains("收成差"), "低收成应有收成描述");
        assertTrue(seg.length() <= 200, "预算内");
    }

    /**
     * 【生计压力】段：粮价便宜 → 日子松快；未启用 → 空串（零阻断）。
     */
    @Test
    void livelihoodSegmentCheapPriceAndDisabled() {
        MacroState st = new MacroState();
        st.getEconomy().setGrainPrice(70.0); // 0.7 倍基准
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        assertTrue(service.livelihoodSegment(100L).contains("松快"), "粮价便宜应描述日子松快");
        props.setEnabled(false);
        assertFalse(service.livelihoodSegment(100L).length() > 0, "未启用零阻断");
    }

    /**
     * M3 模板语义：未来模板（科幻）下【宏观环境】段用「能源价/信用点」而非「粮价/文」。
     */
    @Test
    void macroSegmentUsesTemplateSemantics() {
        MacroTemplateService templateService = new MacroTemplateService();
        templateService.override(100L, MacroTemplate.ID_FUTURE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "macroTemplateService", templateService);
        MacroState st = new MacroState();
        st.getEconomy().setGrainPrice(120.0);
        when(macroStateService.stateOf(anyLong())).thenReturn(st);
        String seg = service.macroDynamicsSegment(100L);
        assertTrue(seg.contains("能源价 120"), "未来模板核心资源=能源，实际: " + seg);
        assertFalse(seg.contains("粮价"), "未来模板不应出现粮价语义");
    }
}
