package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文化舆论引擎单元测试（宏观社会演化系统 M4，domain/world/service，同包）。
 * <p>职责：验证舆论传播模型（自然衰减/入账贡献/信息扩散聚合/事件冲击）、新议题诞生、
 * 舆论转向阈值、信仰演变（漂移+事件冲击+信仰危机）、新习俗兴起与超龄消退、谣言成风。</p>
 */
class MacroCultureEngineTest {

    private static MacroState.Issue issue(String topic, double orientation, double intensity) {
        MacroState.Issue i = new MacroState.Issue();
        i.setTopic(topic);
        i.setOrientation(orientation);
        i.setIntensity(intensity);
        return i;
    }

    /** 无输入稳态：议题强度按 0.92 自然衰减；无事件无新习俗。 */
    @Test
    void intensityDecaysNaturally() {
        List<MacroState.Issue> issues = new ArrayList<>();
        issues.add(issue("时局", 0.0, 0.6));
        MacroCultureEngine.CultureResult r = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                issues, beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of(), List.of(), List.of(), 100L, 1L));
        assertEquals(0.6 * 0.92, r.issues().get(0).getIntensity(), 0.001, "自然衰减 ×0.92");
        assertTrue(r.events().isEmpty());
    }

    /** 入账贡献（NPC 言行）→ 议题强度上升（×0.25 封顶 +0.2）；信息包命中 +0.1。 */
    @Test
    void deltaAndPacketBoostIntensity() {
        List<MacroState.Issue> issues = new ArrayList<>();
        issues.add(issue("时局", 0.0, 0.5));
        MacroCultureEngine.CultureResult r = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                issues, beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of("时局", 0.4), List.of("时局"), List.of(), 100L, 1L));
        double expected = 0.5 * 0.92 + Math.min(0.2, 0.4 * 0.25) + 0.1;
        assertEquals(expected, r.issues().get(0).getIntensity(), 0.001, "衰减+入账+信息扩散聚合");
    }

    /** 新议题诞生：宏观信息包话题无对应议题 → 以强度 0.3 新增（≤6 上限）。 */
    @Test
    void newIssueBornFromPacketTopic() {
        List<MacroState.Issue> issues = new ArrayList<>();
        issues.add(issue("时局", 0.0, 0.5));
        MacroCultureEngine.CultureResult r = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                issues, beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of(), List.of("粮价"), List.of("price_surge"), 100L, 1L));
        assertTrue(r.issues().stream().anyMatch(i -> i.getTopic().equals("粮价")), "粮价议题诞生");
        assertEquals(0.3, r.issues().stream()
                .filter(i -> i.getTopic().equals("粮价")).findFirst().orElseThrow().getIntensity(), 0.001);
    }

    /** 舆论转向：最强议题强度变化 ≥30% → 事件。 */
    @Test
    void opinionTurnWhenTopIssueShifts() {
        List<MacroState.Issue> issues = new ArrayList<>();
        issues.add(issue("时局", 0.0, 0.5));
        // 小幅入账（×0.25）：0.5×0.92+0.08 = 0.54 → 变化 8% 不触发
        MacroCultureEngine.CultureResult r1 = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                issues, beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of("时局", 0.32), List.of(), List.of(), 100L, 1L));
        assertFalse(r1.events().contains("opinion_shift"), "8% 变化不触发舆论转向");
        // 强入账 + 信息包：0.5×0.92+0.2+0.1 = 0.76 → 变化 52% 触发
        List<MacroState.Issue> issues2 = new ArrayList<>();
        issues2.add(issue("时局", 0.0, 0.5));
        MacroCultureEngine.CultureResult r2 = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                issues2, beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of("时局", 1.0), List.of("时局"), List.of(), 100L, 1L));
        assertTrue(r2.events().contains("opinion_shift"), "强度变化 ≥30% 应触发舆论转向");
    }

    /** 信仰演变：饥荒事件冲击 −0.05 + 漂移；信仰跌至 <0.15 → 信仰危机。 */
    @Test
    void faithShockAndCrisis() {
        Map<String, Double> beliefs = new LinkedHashMap<>();
        beliefs.put("山神信仰", 0.16);
        MacroCultureEngine.CultureResult r = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                new ArrayList<>(), beliefs, new ArrayList<>(), new LinkedHashMap<>(),
                Map.of(), List.of(), List.of("famine"), 100L, 1L));
        assertTrue(r.beliefs().get("山神信仰") < 0.16, "饥荒冲击信仰下降");
        assertTrue(r.events().contains("faith_crisis"), "信仰跌破阈值应触发信仰危机");
    }

    /** 新习俗：harvest_good → 「丰收庆典」新增（记录起始日）；超过 90 游戏日消退。 */
    @Test
    void fashionBornOnHarvestAndAgedOut() {
        List<String> fashions = new ArrayList<>();
        Map<String, Long> since = new LinkedHashMap<>();
        MacroCultureEngine.CultureResult r1 = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                new ArrayList<>(), beliefs(), fashions, since,
                Map.of(), List.of(), List.of("harvest_good"), 100L, 1L));
        assertTrue(r1.fashions().contains("丰收庆典"), "丰年应新增丰收庆典习俗");
        assertEquals(100L, r1.fashionSinceDay().get("丰收庆典"), "记录习俗起始日");
        assertTrue(r1.events().contains("new_custom"));
        // 190 日后消退（>90 日）
        MacroCultureEngine.CultureResult r2 = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                new ArrayList<>(), beliefs(), r1.fashions(), r1.fashionSinceDay(),
                Map.of(), List.of(), List.of(), 190L, 1L));
        assertFalse(r2.fashions().contains("丰收庆典"), "超龄习俗应消退");
    }

    /** 谣言成风：单月信息包话题 ≥3 → 事件。 */
    @Test
    void rumorWhenPacketsMany() {
        MacroCultureEngine.CultureResult r = MacroCultureEngine.step(new MacroCultureEngine.CultureInput(
                new ArrayList<>(), beliefs(), new ArrayList<>(), new LinkedHashMap<>(),
                Map.of(), List.of("粮价", "旱情", "民怨"), List.of(), 100L, 1L));
        assertTrue(r.events().contains("rumor"), "信息包 ≥3 应触发谣言成风");
    }

    /** 确定性：同输入恒同输出（引擎原地演进——两次用独立等价输入比对）。 */
    @Test
    void deterministic() {
        MacroCultureEngine.CultureInput inA = new MacroCultureEngine.CultureInput(
                new ArrayList<>(List.of(issue("时局", 0.0, 0.5))), beliefs(), new ArrayList<>(),
                new LinkedHashMap<>(), Map.of("时局", 0.2), List.of(), List.of(), 100L, 7L);
        MacroCultureEngine.CultureInput inB = new MacroCultureEngine.CultureInput(
                new ArrayList<>(List.of(issue("时局", 0.0, 0.5))), beliefs(), new ArrayList<>(),
                new LinkedHashMap<>(), Map.of("时局", 0.2), List.of(), List.of(), 100L, 7L);
        assertEquals(MacroCultureEngine.step(inA).issues().get(0).getIntensity(),
                MacroCultureEngine.step(inB).issues().get(0).getIntensity(), 0.0001);
    }

    private static Map<String, Double> beliefs() {
        Map<String, Double> b = new LinkedHashMap<>();
        b.put("山神信仰", 0.85);
        return b;
    }
}
