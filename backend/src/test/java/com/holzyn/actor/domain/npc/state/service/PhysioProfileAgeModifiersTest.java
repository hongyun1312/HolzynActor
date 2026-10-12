package com.holzyn.actor.domain.npc.state.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 年龄/机能修正叠加单元测试（O6 轨道 G2，纯逻辑）。
 * <p>职责：withAgeModifiers 幂等语义（从基准出发逐项相乘）、倍率钳制到既有区间、
 * 键缺失/空表原样返回、默认档位不受影响。</p>
 */
class PhysioProfileAgeModifiersTest {

    private static final PhysioProfile BASE = PhysioProfile.defaults();

    @Test
    @DisplayName("老年修正：恢复/精力下降，感官敏感上升，数值落钳制区间")
    void elderModifiersApplied() {
        // 耄耋期（capacity≈0.25 附近）典型修正
        Map<String, Double> mods = new LinkedHashMap<>();
        mods.put("recoverScale", 0.55);
        mods.put("energyScale", 0.70);
        mods.put("hungerScale", 0.85);
        mods.put("coldSens", 1.35);
        mods.put("heatSens", 1.25);
        mods.put("painFactor", 1.20);
        PhysioProfile aged = BASE.withAgeModifiers(mods);
        assertEquals(0.55, aged.recoverScale(), 1e-9);
        assertEquals(0.70, aged.energyScale(), 1e-9);
        assertEquals(0.85, aged.hungerScale(), 1e-9);
        assertEquals(1.35, aged.coldSens(), 1e-9);
        assertEquals(1.25, aged.heatSens(), 1e-9);
        assertEquals(1.20, aged.painFactor(), 1e-9);
        // 未修正字段保持基准
        assertEquals(1.0, aged.thirstScale(), 1e-9);
        assertEquals(1.0, aged.bowelScale(), 1e-9);
        assertEquals(8.0, aged.sleepNeedHours(), 1e-9);
        assertEquals("intermediate", aged.chronotype());
    }

    @Test
    @DisplayName("钳制：修正倍率极端值时系数不越界（衰减系统数值稳定）")
    void clampsOnExtremes() {
        Map<String, Double> mods = Map.of(
                "recoverScale", 5.0,
                "energyScale", 0.05,
                "coldSens", 9.0,
                "painFactor", 9.0);
        PhysioProfile aged = BASE.withAgeModifiers(mods);
        assertEquals(1.3, aged.recoverScale(), 1e-9);
        assertEquals(0.6, aged.energyScale(), 1e-9);
        assertEquals(1.6, aged.coldSens(), 1e-9);
        assertEquals(1.5, aged.painFactor(), 1e-9);
    }

    @Test
    @DisplayName("空表/null 原样返回（幂等入口不产生漂移）")
    void noModsNoChange() {
        assertSame(BASE, BASE.withAgeModifiers(null));
        assertSame(BASE, BASE.withAgeModifiers(Map.of()));
    }

    @Test
    @DisplayName("键缺失按 1.0 处理（只修正在表的项）")
    void missingKeysUntouched() {
        PhysioProfile aged = BASE.withAgeModifiers(Map.of("recoverScale", 0.5));
        assertEquals(0.5, aged.recoverScale(), 1e-9);
        assertEquals(1.0, aged.energyScale(), 1e-9);
        assertEquals(1.0, aged.hungerScale(), 1e-9);
    }

    @Test
    @DisplayName("fromPhysioJson 基准 + 年龄修正 → toJson 往返（日级重算链路）")
    void refreshChainRoundTrip() {
        // 基准：代谢 fast → energyScale 基准 = (7/8)×1.08 = 0.945（睡眠 7h 睡得少→耗得慢）
        String physioJson = "{\"chronotype\":\"early_bird\",\"sleep_need_hours\":7,\"metabolism\":\"fast\"}";
        PhysioProfile base = PhysioProfile.fromPhysioJson(physioJson);
        PhysioProfile aged = base.withAgeModifiers(Map.of("recoverScale", 0.6, "energyScale", 0.8));
        String json = aged.toJson();
        PhysioProfile restored = PhysioProfile.fromCoefJson(json);
        assertEquals(0.6, restored.recoverScale(), 1e-9);
        // 幂等语义：从基准 0.945 × 0.8 = 0.756 → 序列化四舍五入 0.76
        assertEquals(0.76, restored.energyScale(), 1e-9);
        assertEquals("early_bird", restored.chronotype());
    }
}
