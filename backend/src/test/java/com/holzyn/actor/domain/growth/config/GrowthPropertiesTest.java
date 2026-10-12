package com.holzyn.actor.domain.growth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 生命周期配置默认值单元测试（O6 轨道 G2）。
 * <p>职责：11 项用户决策对应的默认开关与参数不漂移（死亡默认关、硬约束默认关、
 * 全 NPC 每小时默认开、反推默认开、护栏三开关默认开、TTL/阈值默认值）。</p>
 */
class GrowthPropertiesTest {

    @Test
    @DisplayName("总开关与核心默认值")
    void defaults() {
        GrowthProperties p = new GrowthProperties();
        assertTrue(p.isEnabled());
        assertEquals(1, p.getStageCheckCycleGameDays());
        assertTrue(p.getGateTable().isSeedOnStartup());
        // 空间
        assertTrue(p.getSpace().isEnabled());
        assertEquals(120, p.getSpace().getTtlGameMinutes());
        assertEquals(0.35, p.getSpace().getEmotionShiftThreshold(), 1e-9);
        assertFalse(p.getSpace().isHardConstraint(), "硬约束默认关（软引导）");
        assertEquals(900, p.getSpace().getInjectMaxChars());
        // LOD
        assertTrue(p.getLod().isEnabled());
        assertEquals("focus", p.getLod().getDefaultTierSpecial());
        assertEquals("ambient", p.getLod().getDefaultTierCommon());
        assertEquals(25.0, p.getLod().getCameraNearMeters(), 1e-9);
        assertEquals(120, p.getLod().getDowngradeHysteresisGameMinutes());
        // 衰老/死亡
        assertTrue(p.getAging().isEnabled());
        assertEquals(31_536_000L, p.getAging().getGameSecondsPerYear());
        assertFalse(p.getDeath().isEnabled(), "死亡调试期默认关（完整实现）");
        assertEquals(0.08, p.getDeath().getBaseRateByStage().get("ANCIENT"), 1e-9);
        // 调度/反推
        assertTrue(p.getDispatch().isAllNpcHourly(), "全 20 人每小时逐人决策默认开");
        assertTrue(p.getBackfill().isEnabled());
        assertEquals(30, p.getBackfill().getDefaultAgeSpecial());
        assertEquals(25, p.getBackfill().getDefaultAgeCommon());
        // 护栏
        assertTrue(p.getGuardrails().isStageComplianceEnabled());
        assertTrue(p.getGuardrails().isSpaceComplianceEnabled());
        assertTrue(p.getGuardrails().isKnowledgeBoundEnabled());
    }

    @Test
    @DisplayName("死亡阶段基线：耄耋最高、中年次之、青年最低")
    void deathBaseRates() {
        GrowthProperties p = new GrowthProperties();
        var rates = p.getDeath().getBaseRateByStage();
        assertTrue(rates.get("ANCIENT") > rates.get("ELDER"));
        assertTrue(rates.get("ELDER") > rates.get("MIDDLE"));
        assertTrue(rates.get("YOUTH") < rates.get("MIDDLE"));
        assertTrue(rates.get("INFANT") > rates.get("TODDLER"), "新生儿风险高于幼儿");
    }
}
