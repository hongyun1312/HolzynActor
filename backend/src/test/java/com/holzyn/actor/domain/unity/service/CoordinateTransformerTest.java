package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.unity.config.UnityProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 坐标换算器单元测试（U 轨道 U1，domain/unity/service）。
 * <p>职责：验证——默认映射（1 格=0.2 米，500 网格 ↔ 100m 场景，原点 (0,0)）、
 * 双向互逆（toUnity ∘ toHa = 恒等）、可配映射（meter-per-cell + 原点偏移）、
 * y 直通、空数组/长度不足兜底、1:1 占位（非法档位回退）。</p>
 */
class CoordinateTransformerTest {

    @Test
    void defaultMapping() {
        // 默认配置：1 格 = 0.2 米，原点偏移 0
        UnityProperties props = new UnityProperties();
        CoordinateTransformer t = new CoordinateTransformer(props);
        assertEquals(0.2, t.meterPerCell());
        assertEquals(0, t.originOffsetX());
        assertEquals(0, t.originOffsetZ());

        double[] unity = t.toUnity(new double[]{100, 0, -100});
        assertEquals(20.0, unity[0], 1e-9, "100 格 → 20 米");
        assertEquals(0.0, unity[1], 1e-9, "y 直通");
        assertEquals(-20.0, unity[2], 1e-9, "-100 格 → -20 米");
    }

    @Test
    void roundTripInverse() {
        UnityProperties props = new UnityProperties();
        props.getCoordinate().setMeterPerCell(0.2);
        CoordinateTransformer t = new CoordinateTransformer(props);
        double[] ha = {250, 3.5, -120};
        double[] unity = t.toUnity(ha);
        double[] back = t.toHa(unity);
        assertEquals(ha[0], back[0], 1e-9, "双向互逆 X");
        assertEquals(ha[1], back[1], 1e-9, "双向互逆 Y");
        assertEquals(ha[2], back[2], 1e-9, "双向互逆 Z");
    }

    @Test
    void customMappingWithOffset() {
        UnityProperties props = new UnityProperties();
        props.getCoordinate().setMeterPerCell(1.0);
        props.getCoordinate().setOriginOffsetX(10);
        props.getCoordinate().setOriginOffsetZ(-5);
        CoordinateTransformer t = new CoordinateTransformer(props);

        double[] unity = t.toUnity(new double[]{5, 0, 5});
        assertEquals(15.0, unity[0], 1e-9, "5 格×1 米 + 10 = 15");
        assertEquals(0.0, unity[2], 1e-9, "5 格×1 米 - 5 = 0");

        double[] back = t.toHa(unity);
        assertEquals(5.0, back[0], 1e-9, "逆算还原");
        assertEquals(5.0, back[2], 1e-9);
    }

    @Test
    void staticAxisFunctions() {
        assertEquals(20.0, CoordinateTransformer.toUnityAxis(100, 0.2, 0), 1e-9);
        assertEquals(30.0, CoordinateTransformer.toUnityAxis(100, 0.2, 10), 1e-9, "带偏移");
        assertEquals(100.0, CoordinateTransformer.toHaAxis(20, 0.2, 0), 1e-9, "逆算");
        assertEquals(50.0, CoordinateTransformer.toHaAxis(20, 0.2, 10), 1e-9, "逆算带偏移");
        // 非法档位（<=0）回退 1:1 占位
        assertEquals(5.0, CoordinateTransformer.toUnityAxis(5, 0, 0), 1e-9);
        assertEquals(5.0, CoordinateTransformer.toUnityAxis(5, -1, 0), 1e-9);
    }

    @Test
    void nullAndShortArrays() {
        UnityProperties props = new UnityProperties();
        CoordinateTransformer t = new CoordinateTransformer(props);
        double[] unity = t.toUnity(null);
        assertNotNull(unity);
        assertEquals(3, unity.length);
        assertEquals(0.0, unity[0], 1e-9);
        assertEquals(0.0, unity[1], 1e-9);
        assertEquals(0.0, unity[2], 1e-9);
        double[] shortArr = t.toUnity(new double[]{10});
        assertEquals(2.0, shortArr[0], 1e-9);
        assertEquals(0.0, shortArr[1], 1e-9);
        assertEquals(0.0, shortArr[2], 1e-9, "长度不足补 0");
    }

    @Test
    void configSummary() {
        UnityProperties props = new UnityProperties();
        CoordinateTransformer t = new CoordinateTransformer(props);
        assertEquals("1格=0.2米 原点(0.0,0.0)", t.configSummary());
    }
}
