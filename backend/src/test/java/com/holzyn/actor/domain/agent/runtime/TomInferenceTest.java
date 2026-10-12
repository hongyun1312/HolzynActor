package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToM 推断深度参数单元测试（O2 决策与协调 P1-3，domain/agent/runtime 同包）。
 * <p>职责：验证——档位计算（高宜+高神→过度解读、低宜+低神→直接理解、缺失→常规推断）、
 * persona_json 解析、注入段文本形态。</p>
 * <p>设计依据：现状优化轨道 §7.2 P1-3（宜人性×神经质 → 推断深度档）。</p>
 */
class TomInferenceTest {

    /** 高宜+高神 → 过度解读。 */
    @Test
    void tierOverInterpretForHighAgreeableHighNeurotic() {
        assertEquals(TomInference.TIER_OVER_INTERPRET, TomInference.tierOf(5, 4));
        assertEquals(TomInference.TIER_OVER_INTERPRET, TomInference.tierOf(4, 5));
    }

    /** 低宜+低神 → 直接理解。 */
    @Test
    void tierLiteralForLowAgreeableLowNeurotic() {
        assertEquals(TomInference.TIER_LITERAL, TomInference.tierOf(1, 2));
        assertEquals(TomInference.TIER_LITERAL, TomInference.tierOf(2, 1));
    }

    /** 混合/中间值 → 常规推断。 */
    @Test
    void tierNormalForMixed() {
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(3, 3));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(5, 1));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(1, 5));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(4, 3));
    }

    /** OCEAN 缺失/非法 → 常规推断（零阻断降级）。 */
    @Test
    void tierNormalForMissingOcean() {
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(-1, 4));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(4, 0));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf(0, 0));
    }

    /** v2 档案解析：dims.personality.ocean.agreeableness/neuroticism（0-1 小数 → 1-5 档）。 */
    @Test
    void tierFromPersonaJson() {
        // 1.0 → 1+1.0*4 = 5；0.8 → 1+0.8*4 = 4.2 → 4 ⇒ 高宜+高神 → 过度解读
        String persona = """
                {"dims":{"personality":{"ocean":{"openness":0.4,"conscientiousness":0.9,
                "extraversion":0.3,"agreeableness":1.0,"neuroticism":0.8}}}}
                """;
        assertEquals(TomInference.TIER_OVER_INTERPRET, TomInference.tierOf(persona));

        // 0.25 → 2；0.0 → 1 ⇒ 低宜+低神 → 直接理解
        String literal = """
                {"dims":{"personality":{"ocean":{"agreeableness":0.25,"neuroticism":0.0}}}}
                """;
        assertEquals(TomInference.TIER_LITERAL, TomInference.tierOf(literal));

        // 解析失败/缺失 → 常规推断
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf("not json"));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf("{\"dims\":{\"personality\":{}}}"));
        assertEquals(TomInference.TIER_NORMAL, TomInference.tierOf((String) null));
    }

    /** 注入段文本：档位不同 → 约束文本不同；恒非空。 */
    @Test
    void segmentTextPerTier() {
        String over = TomInference.segmentOf(TomInference.TIER_OVER_INTERPRET, "沈砚秋");
        String literal = TomInference.segmentOf(TomInference.TIER_LITERAL, "沈砚秋");
        String normal = TomInference.segmentOf(TomInference.TIER_NORMAL, "沈砚秋");
        assertTrue(over.contains("过度解读"));
        assertTrue(literal.contains("直接理解"));
        assertTrue(normal.contains("常规推断"));
        assertTrue(over.contains("沈砚秋"));
        assertFalse(over.isEmpty());
    }

    /** 未知档位回退常规推断文本。 */
    @Test
    void segmentFallsBackForUnknownTier() {
        String text = TomInference.segmentOf("unknown_tier", null);
        assertTrue(text.contains("常规推断"));
    }
}
