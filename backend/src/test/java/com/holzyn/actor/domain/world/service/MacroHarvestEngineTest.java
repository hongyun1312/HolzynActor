package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收成系数引擎单元测试（宏观社会演化系统 M3，domain/world/service，同包）。
 * <p>职责：验证季节基准×模板缩放、天气修正、连续累积（旱/涝/雪被保温）、极端季节事件判定
 * （大旱/洪涝/暴雪）、钳制与确定性——M3 全链联动源头（季节/天气→收成→粮价→生计→势力/舆论）。</p>
 */
class MacroHarvestEngineTest {

    /** 秋季基准 1.2（无天气修正/无累积）→ 收成系数 = 1.2。 */
    @Test
    void autumnBaseFactor() {
        MacroHarvestEngine.HarvestResult r = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.AUTUMN, null, 0, 0, 0, 1.0, 1L));
        assertEquals(1.2, r.harvestFactor(), 0.001, "秋季收获季基准 1.2");
        assertFalse(r.drought());
        assertFalse(r.flood());
        assertFalse(r.blizzard());
    }

    /** 冬季基准 0.4 × 模板缩放：仙侠 1.0 → 0.4；现代 0.4 → 1+(0.4-1)×0.4=0.76。 */
    @Test
    void winterSeasonScalePerTemplate() {
        MacroHarvestEngine.HarvestResult full = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, null, 0, 0, 0, 1.0, 1L));
        assertEquals(0.4, full.harvestFactor(), 0.001, "仙侠模板冬季全联动 0.4");
        MacroHarvestEngine.HarvestResult modern = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, null, 0, 0, 0, 0.4, 1L));
        assertEquals(0.76, modern.harvestFactor(), 0.001, "现代模板季节弱化 1+(0.4-1)×0.4");
        MacroHarvestEngine.HarvestResult future = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, null, 0, 0, 0, 0.15, 1L));
        assertEquals(0.91, future.harvestFactor(), 0.001, "未来模板气候控制近零 1+(0.4-1)×0.15");
    }

    /** 天气修正：小雨 +0.05 利生长；雷雨 −0.12；大雪 −0.15（叠加在季节基准上）。 */
    @Test
    void weatherModifierApplied() {
        MacroHarvestEngine.HarvestResult rain = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "小雨", 0, 0, 0, 1.0, 1L));
        assertEquals(1.1025, rain.harvestFactor(), 0.001, "夏 1.05 × (1+0.05)");
        MacroHarvestEngine.HarvestResult storm = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "雷雨", 0, 0, 0, 1.0, 1L));
        assertEquals(0.924, storm.harvestFactor(), 0.001, "夏 1.05 × (1-0.12)");
        // 未知天气零修正（零阻断）
        MacroHarvestEngine.HarvestResult unknown = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "沙尘暴", 0, 0, 0, 1.0, 1L));
        assertEquals(1.05, unknown.harvestFactor(), 0.001, "未知天气按 0 修正");
    }

    /** 旱情累积：生长季连续 ≥5 日无雨 → 逐日加深 −2%/日；第 7 日触发大旱事件。 */
    @Test
    void droughtStreakPenaltyAndEvent() {
        // 无旱情基线（连续 3 日无雨未入累积）：夏 1.05 × 晴 +2%
        MacroHarvestEngine.HarvestResult base = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "晴", 3, 0, 0, 1.0, 1L));
        // 连续 5 日无雨（第 5 日进入累积 −2%；晴 +2% 在三位小数处近似抵消，仍应低于基线）
        MacroHarvestEngine.HarvestResult r5 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "晴", 4, 0, 0, 1.0, 1L));
        assertTrue(r5.harvestFactor() < base.harvestFactor(), "第 5 日起旱情惩罚");
        assertFalse(r5.drought());
        // 连续 7 日无雨 → 大旱事件
        MacroHarvestEngine.HarvestResult r7 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "晴", 6, 0, 0, 1.0, 1L));
        assertTrue(r7.drought(), "连续 7 日无雨应触发大旱");
        assertTrue(r7.harvestFactor() < r5.harvestFactor(), "旱情逐日加深");
        // 冬季无旱情（休耕不需水；用「阴」零天气修正，排除晴 +2% 干扰）
        MacroHarvestEngine.HarvestResult winter = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, "阴", 6, 0, 0, 1.0, 1L));
        assertEquals(0.4, winter.harvestFactor(), 0.001, "冬季旱情不惩罚");
        assertFalse(winter.drought());
    }

    /** 有效降水打断旱情累积；连续强降水 → 涝情 ≥3 日累积、≥4 日洪涝事件。 */
    @Test
    void rainResetsDroughtAndFloodStreak() {
        MacroHarvestEngine.HarvestResult rain = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "小雨", 6, 0, 0, 1.0, 1L));
        assertEquals(0, rain.dryStreak(), "有效降水重置旱情");
        assertFalse(rain.drought());
        // 连续 4 日强降水 → 洪涝
        MacroHarvestEngine.HarvestResult flood = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.SUMMER, "大雨", 0, 3, 0, 1.0, 1L));
        assertTrue(flood.flood(), "连续 4 日强降水应触发洪涝");
        assertEquals(4, flood.floodStreak());
        assertTrue(flood.harvestFactor() < 1.05, "涝情降低收成");
    }

    /** 冬季连续降雪 → 雪被保温（+2%/日封顶 +6%，叠加当日降雪天气修正）；连续 3 日 → 暴雪事件。 */
    @Test
    void winterSnowInsulationAndBlizzard() {
        // 首日小雪无保温（雪被尚未形成）：0.4 × (1−2%)
        MacroHarvestEngine.HarvestResult s1 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, "小雪", 0, 0, 0, 1.0, 1L));
        // 连续 2 日降雪：保温 +2%（×1.02）叠加当日小雪天气 −2%
        MacroHarvestEngine.HarvestResult s2 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, "小雪", 0, 0, 1, 1.0, 1L));
        assertTrue(s2.harvestFactor() > s1.harvestFactor(), "连续 2 日降雪保温 +2%");
        assertEquals(0.4 * 0.98 * 1.02, s2.harvestFactor(), 0.001, "连续 2 日降雪：0.4×0.98×1.02");
        // 连续 3 日：保温 +4%（×1.04）叠加当日中雪天气 −8%
        MacroHarvestEngine.HarvestResult s3 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, "中雪", 0, 0, 2, 1.0, 1L));
        assertTrue(s3.blizzard(), "连续 3 日降雪应触发暴雪");
        assertEquals(0.4 * 0.92 * 1.04, s3.harvestFactor(), 0.001, "连续 3 日：0.4×0.92×1.04");
        // 封顶 +6%
        MacroHarvestEngine.HarvestResult s6 = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.WINTER, "大雪", 0, 0, 5, 1.0, 1L));
        assertTrue(s6.harvestFactor() <= 0.4 * 1.06 + 0.001, "雪被保温封顶 +6%");
    }

    /** 极端组合钳制：严重旱涝叠加 → 收成系数不低于 0.2（世界不归零）。 */
    @Test
    void factorClampedAtMinimum() {
        MacroHarvestEngine.HarvestResult r = MacroHarvestEngine.step(
                new MacroHarvestEngine.HarvestInput(SeasonService.AUTUMN, "雷雨", 20, 10, 0, 1.0, 1L));
        assertTrue(r.harvestFactor() >= 0.2, "收成系数钳制下限 0.2");
    }

    /** 确定性：同输入恒同输出（可复现可单测）。 */
    @Test
    void deterministic() {
        MacroHarvestEngine.HarvestInput in = new MacroHarvestEngine.HarvestInput(
                SeasonService.SUMMER, "晴", 3, 0, 0, 1.0, 42L);
        assertEquals(MacroHarvestEngine.step(in).harvestFactor(),
                MacroHarvestEngine.step(in).harvestFactor(), 0.0001);
    }
}
