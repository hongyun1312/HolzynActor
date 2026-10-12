package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人口数值引擎单元测试（宏观社会演化系统 M1，domain/world/service，纯静态）。
 * <p>职责：验证出生/死亡/迁徙统计模型数学正确性、饥荒经济压力修正（出生↓死亡↑外迁）、
 * 瘟疫/流民确定性触发、总人口不为负、jitter 确定性。</p>
 */
class MacroPopulationEngineTest {

    /**
     * 安居稳态（无经济压力）：出生=round(300×0.0005)=0/日级小步进，死亡同量级；
     * 总人口 = 前值 + 出生 - 死亡 + 净迁徙（恒 ≥1）。
     */
    @Test
    void peacefulSteadyStateKeepsPopulation() {
        MacroPopulationEngine.PopulationResult r = MacroPopulationEngine.step(
                300L, 0.0005, 0.0003, 0.0002, 0.0, 0.0, 5L);
        assertEquals(300L + r.births() - r.deaths() + r.migrationNet(), r.total());
        assertTrue(r.total() >= 1);
        assertTrue(r.events().isEmpty() || r.events().contains("refugee_influx"),
                "安居稳态不应触发饥荒/瘟疫，实际 " + r.events());
    }

    /**
     * 饥荒（经济压力 2.0）→ 出生率下降、死亡率上升、外迁（migrationNet ≤ 0）。
     */
    @Test
    void famineReducesBirthsRaisesDeathsAndOutMigration() {
        MacroPopulationEngine.PopulationResult famine = MacroPopulationEngine.step(
                300L, 0.0005, 0.0003, 0.0002, 2.0, 0.0, 5L);
        MacroPopulationEngine.PopulationResult peace = MacroPopulationEngine.step(
                300L, 0.0005, 0.0003, 0.0002, 0.0, 0.0, 5L);
        assertTrue(famine.deaths() >= peace.deaths(), "饥荒死亡应 ≥ 安居");
        assertTrue(famine.births() <= peace.births(), "饥荒出生应 ≤ 安居");
        assertTrue(famine.migrationNet() <= 0, "饥荒应外迁，实际 " + famine.migrationNet());
        assertTrue(famine.events().contains("famine"));
    }

    /**
     * 瘟疫确定性概率：plagueChance=1.0 → 必触发瘟疫减员并追加减员 ≥1。
     */
    @Test
    void plagueTriggersAtFullChance() {
        MacroPopulationEngine.PopulationResult r = MacroPopulationEngine.step(
                300L, 0.0005, 0.0003, 0.0002, 0.0, 1.0, 5L);
        assertTrue(r.events().contains("plague"));
        assertTrue(r.deaths() >= 1);
    }

    /**
     * 流民涌入：安居（压力 0）+ 幸运日（daySeed 命中 3% 概率）→ 正迁徙。
     */
    @Test
    void influxEventOnPeacefulLuckyDay() {
        boolean found = false;
        for (long day = 0; day < 500 && !found; day++) {
            MacroPopulationEngine.PopulationResult r = MacroPopulationEngine.step(
                    300L, 0.0005, 0.0003, 0.0002, 0.0, 0.0, day);
            if (r.events().contains("refugee_influx")) {
                found = true;
                assertTrue(r.migrationNet() > 0, "流民涌入应净增人口");
            }
        }
        assertTrue(found, "500 日内应能命中流民涌入（3% 概率）");
    }

    /**
     * 确定性：同输入同输出；总人口永不为负。
     */
    @Test
    void deterministicAndNeverNegative() {
        MacroPopulationEngine.PopulationResult a = MacroPopulationEngine.step(
                5L, 0.0005, 0.0003, 0.0002, 0.0, 0.0, 5L);
        MacroPopulationEngine.PopulationResult b = MacroPopulationEngine.step(
                5L, 0.0005, 0.0003, 0.0002, 0.0, 0.0, 5L);
        assertEquals(a.total(), b.total());
        assertEquals(a.deaths(), b.deaths());
        assertTrue(a.total() >= 1);
    }

    /**
     * jitter 确定性 + [0,1) 区间。
     */
    @Test
    void jitterDeterministicAndInRange() {
        double v1 = MacroPopulationEngine.jitter(7L, 3);
        double v2 = MacroPopulationEngine.jitter(7L, 3);
        assertEquals(v1, v2, 0.0);
        assertTrue(v1 >= 0.0 && v1 < 1.0);
    }
}
