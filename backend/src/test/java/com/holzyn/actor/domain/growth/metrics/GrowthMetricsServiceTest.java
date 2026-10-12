package com.holzyn.actor.domain.growth.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 增长指标计数器单元测试（O6 轨道 G2，纯逻辑）。
 * <p>职责：计数器只增、快照键齐全、合规率/空间内率口径、触发码计数与归一。</p>
 */
class GrowthMetricsServiceTest {

    @Test
    @DisplayName("计数只增 + 快照键齐全")
    void countersAndSnapshot() {
        GrowthMetricsService m = new GrowthMetricsService();
        m.recordSpaceAiOk();
        m.recordSpaceAiOk();
        m.recordSpaceJsonOk();
        m.recordSpaceJsonOk();
        m.recordSpaceProgrammatic();
        m.recordDecisionInSpace();
        m.recordDecisionInSpace();
        m.recordDecisionInSpace();
        m.recordDecisionOutside();
        m.recordStageComplianceIntercept();
        m.recordSpaceComplianceIntercept();
        m.recordKnowledgeBoundIntercept();
        m.recordTrigger(GrowthMetricsService.GrowthSpaceTriggerCode.STAGE_TRANSITION);
        m.recordTrigger(GrowthMetricsService.GrowthSpaceTriggerCode.STAGE_TRANSITION);
        m.recordTrigger(GrowthMetricsService.GrowthSpaceTriggerCode.LOD_PROMOTE);

        Map<String, Object> s = m.snapshot();
        assertEquals(2L, s.get("spaceAiOk"));
        assertEquals(1L, s.get("spaceProgrammatic"));
        assertEquals(100.0, ((Number) s.get("spaceComplianceRate")).doubleValue(), 1e-9);
        assertEquals(3L, s.get("decisionInSpace"));
        assertEquals(1L, s.get("decisionOutside"));
        assertEquals(75.0, ((Number) s.get("inSpaceRate")).doubleValue(), 1e-9);
        assertEquals(4L, s.get("decisionTotal"));
        assertEquals(1L, s.get("stageComplianceIntercepts"));
        assertEquals(1L, s.get("spaceComplianceIntercepts"));
        assertEquals(1L, s.get("knowledgeBoundIntercepts"));
        Map<?, ?> triggers = (Map<?, ?>) s.get("triggerCounts");
        assertEquals(2L, triggers.get("STAGE_TRANSITION"));
        assertEquals(1L, triggers.get("LOD_PROMOTE"));
    }

    @Test
    @DisplayName("无数据时合规率/空间内率为 null（避免 0/0 误导）")
    void emptyRatesAreNull() {
        Map<String, Object> s = new GrowthMetricsService().snapshot();
        assertNull(s.get("spaceComplianceRate"));
        assertNull(s.get("inSpaceRate"));
        assertEquals(0L, s.get("decisionTotal"));
    }

    @Test
    @DisplayName("触发码归一：非法码回退 PERIODIC，大小写不敏感")
    void triggerCodeNormalization() {
        assertTrue(GrowthMetricsService.GrowthSpaceTriggerCode.of("lod_promote") ==
                GrowthMetricsService.GrowthSpaceTriggerCode.LOD_PROMOTE);
        assertTrue(GrowthMetricsService.GrowthSpaceTriggerCode.of("NOT_A_CODE") ==
                GrowthMetricsService.GrowthSpaceTriggerCode.PERIODIC);
        assertTrue(GrowthMetricsService.GrowthSpaceTriggerCode.of(null) ==
                GrowthMetricsService.GrowthSpaceTriggerCode.PERIODIC);
    }
}
