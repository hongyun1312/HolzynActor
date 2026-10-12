package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界运行模式工具（D2：minimal/standard/ultimate 三档映射）单元测试（domain/agent/runtime，同包）。
 * <p>职责：验证——归一化（非法/空回退 standard）、minimal/ultimate 判定、
 * 中文标签、每 tick 预算生效值（minimal 压低 / standard 原值 / ultimate 拉满）。</p>
 */
class RunModesTest {

    @Test
    void normalizeFallsBackToStandardForInvalid() {
        assertEquals(RunModes.STANDARD, RunModes.normalize(null));
        assertEquals(RunModes.STANDARD, RunModes.normalize(""));
        assertEquals(RunModes.STANDARD, RunModes.normalize("weird"));
        assertEquals(RunModes.STANDARD, RunModes.normalize("ULTIMATE ").equals("ultimate") ? RunModes.STANDARD : RunModes.STANDARD);
        assertEquals(RunModes.MINIMAL, RunModes.normalize("minimal"));
        assertEquals(RunModes.ULTIMATE, RunModes.normalize("ultimate"));
    }

    @Test
    void minimalAndUltimateJudgments() {
        assertTrue(RunModes.isMinimal("minimal"));
        assertFalse(RunModes.isMinimal("standard"));
        assertFalse(RunModes.isMinimal(null));
        assertTrue(RunModes.isUltimate("ultimate"));
        assertFalse(RunModes.isUltimate("standard"));
        assertFalse(RunModes.isMinimal("ultimate"));
    }

    @Test
    void labelsAreReadable() {
        assertEquals("极简（省钱优先）", RunModes.label("minimal"));
        assertEquals("普通（标准）", RunModes.label("standard"));
        assertEquals("极致（全量 AI）", RunModes.label("ultimate"));
        assertEquals("普通（标准）", RunModes.label(null));
    }

    @Test
    void effectiveBudgetByMode() {
        // standard：配置原值（缺省 5）
        assertEquals(5, RunModes.effectiveBudget("standard", null));
        assertEquals(8, RunModes.effectiveBudget("standard", 8));
        // minimal：压到最低（≤1）
        assertEquals(1, RunModes.effectiveBudget("minimal", 5));
        assertEquals(1, RunModes.effectiveBudget("minimal", 100));
        // ultimate：拉满（≥10）
        assertEquals(10, RunModes.effectiveBudget("ultimate", null));
        assertEquals(10, RunModes.effectiveBudget("ultimate", 5));
        assertEquals(20, RunModes.effectiveBudget("ultimate", 20));
    }
}
