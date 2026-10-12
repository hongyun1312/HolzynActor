package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 科技演进引擎单元测试（宏观社会演化系统 M4，domain/world/service，同包）。
 * <p>职责：验证缓慢累积（时间+入账+人才）、平台期放缓、里程碑突破判定（50/70/90）、
 * 联动效果事件格式、衰退失传（无投入 ≥3 月衰退、&lt;10 失传重置）。</p>
 */
class MacroTechEngineTest {

    private static Map<String, Double> levels(double agriculture, double medicine) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("agriculture", agriculture);
        m.put("medicine", medicine);
        return m;
    }

    /** 稳态累积：时间自然增长 0.15/月（无投入无人才），平台期放缓。 */
    @Test
    void timeAccumulatesSlowly() {
        Map<String, Double> lv = levels(35.0, 45.0);
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 0);
        noInput.put("medicine", 0);
        MacroTechEngine.TechResult r = MacroTechEngine.step(new MacroTechEngine.TechInput(
                lv, Map.of(), noInput, new LinkedHashMap<>(lv), Map.of(), 1L));
        double plateau = 1.0 - 35.0 / 100.0 * 0.4;
        assertEquals(35.13, r.techLevels().get("agriculture"), 0.001,
                "时间自然增长 × 平台期放缓（两位小数舍入 35.129→35.13）");
        assertTrue(r.events().isEmpty());
    }

    /** 入账贡献 + 人才加成 → 增长显著快于纯时间；入账×0.5 封顶 +0.8。 */
    @Test
    void inputAndTalentBoost() {
        Map<String, Double> lv = levels(35.0, 45.0);
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 0);
        noInput.put("medicine", 0);
        // 农业入账 1.0（×0.5=0.5）+ 农户 160 人（×0.5×3=240 → 远超封顶，按平台后值）——显著提升
        MacroTechEngine.TechResult r = MacroTechEngine.step(new MacroTechEngine.TechInput(
                lv, Map.of("agriculture", 1.0), noInput, new LinkedHashMap<>(lv),
                Map.of("农户", 160L, "医者", 6L), 1L));
        assertTrue(r.techLevels().get("agriculture") > 36.0,
                "入账+人才应显著推高农业累积，实际 " + r.techLevels().get("agriculture"));
        assertTrue(r.techLevels().get("medicine") > 45.2,
                "医者人才应推高医术累积，实际 " + r.techLevels().get("medicine"));
    }

    /** 里程碑突破：45 + 大额投入跨过 50 → 技艺突破事件（联动效果由调度器应用）。 */
    @Test
    void milestoneBreakthroughEvent() {
        Map<String, Double> lv = levels(45.0, 45.0);
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 0);
        noInput.put("medicine", 0);
        MacroTechEngine.TechResult r = MacroTechEngine.step(new MacroTechEngine.TechInput(
                lv, Map.of("agriculture", 1.0), noInput, new LinkedHashMap<>(lv),
                Map.of("农户", 160L), 1L));
        boolean broke = r.events().stream().anyMatch(e -> e.equals("tech_breakthrough:agriculture"));
        assertTrue(broke, "跨过 50 里程碑应触发技艺突破（农业）");
        assertTrue(r.techLevels().get("agriculture") >= 50.0);
    }

    /** 衰退失传：无投入连续 ≥3 月 → 每月净 −1.35（时间 +0.15 抵消后）；跌破 10 → 技艺失传并重置 5。 */
    @Test
    void decayAndLoss() {
        Map<String, Double> lv = levels(11.0, 45.0); // 农业濒临失传（单步净 −1.35 可跌破 10）
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 2); // 已连续 2 月无投入
        noInput.put("medicine", 0);
        MacroTechEngine.TechResult r = MacroTechEngine.step(new MacroTechEngine.TechInput(
                lv, Map.of(), noInput, new LinkedHashMap<>(lv), Map.of(), 1L));
        assertTrue(r.events().stream().anyMatch(e -> e.equals("tech_lost:agriculture")),
                "无投入 ≥3 月且跌破 10 应触发技艺失传");
        assertEquals(5.0, r.techLevels().get("agriculture"), 0.001, "失传后重置 5");
        assertEquals(0, r.techNoInputMonths().get("agriculture"), "失传后清投入记录");
    }

    /** 有投入清零无投入月数（不衰退）。 */
    @Test
    void inputResetsNoInputCounter() {
        Map<String, Double> lv = levels(35.0, 45.0);
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 5);
        noInput.put("medicine", 0);
        MacroTechEngine.TechResult r = MacroTechEngine.step(new MacroTechEngine.TechInput(
                lv, Map.of("agriculture", 0.1), noInput, new LinkedHashMap<>(lv),
                Map.of("农户", 160L), 1L));
        assertEquals(0, r.techNoInputMonths().get("agriculture"), "有投入清零无投入月数");
        assertTrue(r.techLevels().get("agriculture") > 35.0);
    }

    /** 确定性：同输入恒同输出。 */
    @Test
    void deterministic() {
        Map<String, Double> lv = levels(35.0, 45.0);
        Map<String, Integer> noInput = new LinkedHashMap<>();
        noInput.put("agriculture", 0);
        noInput.put("medicine", 0);
        MacroTechEngine.TechInput in = new MacroTechEngine.TechInput(
                lv, Map.of(), noInput, new LinkedHashMap<>(lv), Map.of("农户", 160L), 7L);
        assertEquals(MacroTechEngine.step(in).techLevels().get("agriculture"),
                MacroTechEngine.step(in).techLevels().get("agriculture"), 0.0001);
    }
}
