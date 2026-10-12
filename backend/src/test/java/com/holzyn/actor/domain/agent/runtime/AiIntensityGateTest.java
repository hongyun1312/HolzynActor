package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * AI 强度闸门单元测试（宏观社会演化系统 M5，domain/agent/runtime，同包）。
 * <p>职责：验证四档独立维度（programmatic/light/full/max）——档位归一化、默认 full、
 * programmatic 零 AI、light 预算减半、max 无预算限制、设置持久化+缓存、与 runMode 正交语义。</p>
 */
class AiIntensityGateTest {

    private ActorWorldEngineRepository engineRepository;
    private AiIntensityGate gate;

    @BeforeEach
    void setUp() {
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        when(engineRepository.findByProjectId(anyLong())).thenReturn(Optional.empty());
        gate = new AiIntensityGate(engineRepository);
    }

    /** 默认档=full；未知档归一化 full；非法值回退 full。 */
    @Test
    void defaultsToFullAndNormalizes() {
        assertEquals(AiIntensityGate.FULL, gate.intensityOf(37L), "无引擎行默认 full");
        assertEquals(AiIntensityGate.FULL, AiIntensityGate.normalize("crazy"), "非法档回退 full");
        assertEquals(AiIntensityGate.MAX, AiIntensityGate.normalize("max"));
        assertEquals(AiIntensityGate.LIGHT, AiIntensityGate.normalize(" light "), "空白容忍");
    }

    /** programmatic：不允许 AI、预算 0；full：允许、预算原值。 */
    @Test
    void programmaticBlocksAi() {
        gate.setIntensity(37L, AiIntensityGate.PROGRAMMATIC);
        assertFalse(gate.allowsAi(37L), "纯规则档零 AI");
        assertEquals(0, gate.effectiveCap(37L, 8), "纯规则档预算 0");
        gate.setIntensity(37L, AiIntensityGate.FULL);
        assertTrue(gate.allowsAi(37L));
        assertEquals(8, gate.effectiveCap(37L, 8), "标准档预算原值");
    }

    /** light：预算减半向上取整；max：预算不限（Int.MAX）。 */
    @Test
    void lightHalvesAndMaxUnlimited() {
        gate.setIntensity(37L, AiIntensityGate.LIGHT);
        assertEquals(4, gate.effectiveCap(37L, 8), "light 档 8×0.5=4");
        assertEquals(3, gate.effectiveCap(37L, 5), "light 档向上取整 ceil(2.5)=3");
        gate.setIntensity(37L, AiIntensityGate.MAX);
        assertTrue(gate.allowsAi(37L));
        assertEquals(Integer.MAX_VALUE, gate.effectiveCap(37L, 8), "max 档无预算限制");
    }

    /** 设置持久化：写引擎行 + 缓存同步（findByProjectId 返回已存行）。 */
    @Test
    void setPersistsAndCaches() {
        ActorWorldEngine saved = new ActorWorldEngine();
        saved.setProjectId(37L);
        when(engineRepository.save(any(ActorWorldEngine.class))).thenAnswer(inv -> inv.getArgument(0));
        gate.setIntensity(37L, AiIntensityGate.MAX);
        assertEquals(AiIntensityGate.MAX, gate.intensityOf(37L), "缓存命中");
        // 重启语义：引擎行读到 max（模拟行持久化后）
        when(engineRepository.findByProjectId(anyLong())).thenReturn(Optional.of(saved));
        AiIntensityGate gate2 = new AiIntensityGate(engineRepository);
        saved.setAiIntensity("light");
        assertEquals(AiIntensityGate.LIGHT, gate2.intensityOf(37L), "从引擎行恢复");
    }

    /** 档位显示名。 */
    @Test
    void labels() {
        assertEquals("纯规则", AiIntensityGate.label(AiIntensityGate.PROGRAMMATIC));
        assertEquals("Max 全 AI", AiIntensityGate.label(AiIntensityGate.MAX));
        assertEquals("标准", AiIntensityGate.label(null));
    }
}
