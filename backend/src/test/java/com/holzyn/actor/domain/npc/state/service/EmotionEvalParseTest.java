package com.holzyn.actor.domain.npc.state.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC 情绪 AI 评估输出解析器单元测试（状态模块 M2，EmotionEvalParse，纯逻辑）。
 * <p>职责：验证——完整 JSON 解析/字段缺失兜底/强度与 PAD/OCC 刻度钳制/显式标签与 padDelta 分离/
 * Markdown 代码块容错/全空返回 null。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§7.2（AI 评估写回输出契约）。</p>
 */
class EmotionEvalParseTest {

    @Test
    void parse_fullValidJson() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(
                """
                {"emotion":"angry","intensity":62,
                 "padDelta":{"pleasure":-0.2,"arousal":0.3,"dominance":0.1},
                 "desirability":-0.6,"praiseworthiness":0.0,"liking":-0.3,
                 "reason":"被当众羞辱，感到愤怒"}
                """);
        assertNotNull(eval);
        assertEquals("angry", eval.emotion());
        assertEquals(62, eval.intensity());
        assertEquals(-0.2d, eval.padPleasure(), 0.001);
        assertEquals(0.3d, eval.padArousal(), 0.001);
        assertEquals(0.1d, eval.padDominance(), 0.001);
        assertEquals(-0.6d, eval.desirability(), 0.001);
        assertEquals(0.0d, eval.praiseworthiness(), 0.001);
        assertEquals(-0.3d, eval.liking(), 0.001);
        assertEquals("被当众羞辱，感到愤怒", eval.reason());
    }

    @Test
    void parse_markdownFenceAndSurroundingText() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(
                "评估结果如下：\n```json\n{\"emotion\":\"happy\",\"intensity\":70}\n```\n以上。");
        assertNotNull(eval);
        assertEquals("happy", eval.emotion());
        assertEquals(70, eval.intensity());
    }

    @Test
    void parse_blankOrNullReturnsNull() {
        assertNull(EmotionEvalParse.parse(null));
        assertNull(EmotionEvalParse.parse("  "));
        assertNull(EmotionEvalParse.parse("这不是 JSON"));
    }

    @Test
    void parse_nonObjectReturnsNull() {
        assertNull(EmotionEvalParse.parse("[1,2,3]"));
    }

    @Test
    void parse_allFieldsEmptyReturnsNull() {
        assertNull(EmotionEvalParse.parse("{\"emotion\":\"\",\"reason\":\"无变化\"}"));
    }

    @Test
    void parse_intensityClampedTo100() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse("{\"intensity\":250}");
        assertNotNull(eval);
        assertEquals(100, eval.intensity());
        EmotionEvalParse.EmotionEval neg = EmotionEvalParse.parse("{\"intensity\":-5}");
        assertEquals(0, neg.intensity());
    }

    @Test
    void parse_padDeltaBoundedAndOCCClamped() {
        // padDelta 允许小幅越界（界限 ±2）；OCC 钳制 ±1
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(
                "{\"padDelta\":{\"pleasure\":5.0},\"desirability\":3.0}");
        assertNotNull(eval);
        assertEquals(2.0d, eval.padPleasure(), 0.001);
        assertEquals(1.0d, eval.desirability(), 0.001);
    }

    @Test
    void parse_emotionOnlyWithoutDeltas() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse("{\"emotion\":\"custom:心事重重\"}");
        assertNotNull(eval);
        assertEquals("custom:心事重重", eval.emotion());
        assertNull(eval.intensity());
        assertNull(eval.padPleasure());
        assertNull(eval.desirability());
    }

    @Test
    void parse_occDimsOnly() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(
                "{\"desirability\":0.8,\"praiseworthiness\":0.6,\"liking\":0.5}");
        assertNotNull(eval);
        assertEquals(0.8d, eval.desirability(), 0.001);
        assertEquals("", eval.emotion(), "缺省 emotion 为空串（服务层按空串判定无显式标签）");
        assertNull(eval.padPleasure());
    }

    @Test
    void parse_returnsFullyClampedPADAndReason() {
        EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(
                "{\"padDelta\":{\"pleasure\":0.1,\"arousal\":-0.2,\"dominance\":0.05},\"reason\":\"稍有好转\"}");
        assertNotNull(eval);
        assertTrue(eval.padPleasure() > 0 && eval.padArousal() < 0 && eval.padDominance() > 0);
        assertEquals("稍有好转", eval.reason());
    }
}
