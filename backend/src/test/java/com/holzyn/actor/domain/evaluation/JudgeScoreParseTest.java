package com.holzyn.actor.domain.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JudgeScoreParse 单元测试（O2.5 P2-1：LLM-as-Judge 评分输出解析）。
 * <p>验证：整体 JSON 解析 / 分数钳制 / 缺维不补 / 非法输入正则兜底 / 总分计算 / 截断容错。</p>
 */
class JudgeScoreParseTest {

    /** 合法五维 JSON → 正确解析 + 总分=均值×10。 */
    @Test
    void parseValidJson() {
        String raw = """
                {"scores":{"consistency":5,"reasonability":4,"context_usage":3,"foresight":4,"naturalness":5},
                 "reasons":{"consistency":"性格相符","reasonability":"权衡得当"},
                 "summary":"整体自然可信"}
                """;
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(5, r.scores().get("consistency"));
        assertEquals(4, r.scores().get("reasonability"));
        assertEquals(3, r.scores().get("context_usage"));
        assertEquals(4, r.scores().get("foresight"));
        assertEquals(5, r.scores().get("naturalness"));
        assertEquals("性格相符", r.reasons().get("consistency"));
        assertEquals("整体自然可信", r.summary());
        // (5+4+3+4+5)/5 = 4.2 → 42
        assertEquals(42, r.total());
    }

    /** 分数越界钳制到 1-5（judge 输出 0/6/9 等非法值）。 */
    @Test
    void parseClampsScores() {
        String raw = "{\"scores\":{\"consistency\":0,\"reasonability\":6,\"context_usage\":9,\"foresight\":-1,\"naturalness\":3}}";
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(1, r.scores().get("consistency"));
        assertEquals(5, r.scores().get("reasonability"));
        assertEquals(5, r.scores().get("context_usage"));
        assertEquals(1, r.scores().get("foresight"));
        assertEquals(3, r.scores().get("naturalness"));
    }

    /** 缺失维度不补（judge 未给某维 → 该维不参与，总分按出现维度均值）。 */
    @Test
    void parseMissingDimensionSkipped() {
        String raw = "{\"scores\":{\"consistency\":5,\"reasonability\":5}}";
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(2, r.scores().size());
        assertTrue(!r.scores().containsKey("naturalness"));
        assertEquals(50, r.total(), "仅两维 5/5 → 50");
    }

    /** 非法 JSON 但含 scores 文本 → 正则兜底解析。 */
    @Test
    void parseRegexFallbackOnBrokenJson() {
        String raw = "评分结果如下：\nscores: {\"consistency\": 4, \"reasonability\": 3} 结束";
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(4, r.scores().get("consistency"));
        assertEquals(3, r.scores().get("reasonability"));
    }

    /** 代码块包裹/多余文本 → 仍解析出五维。 */
    @Test
    void parseHandlesMarkdownWrap() {
        String raw = "```json\n{\"scores\":{\"consistency\":4,\"reasonability\":4,\"context_usage\":3,\"foresight\":3,\"naturalness\":4}}\n```";
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(4, r.scores().size() == 0 ? 0 : r.scores().get("consistency"));
        assertTrue(r.scores().containsKey("consistency"));
        assertTrue(r.scores().containsKey("naturalness"));
    }

    /** 未知维键忽略（judge 输出多余键不污染）。 */
    @Test
    void parseIgnoresUnknownKeys() {
        String raw = "{\"scores\":{\"consistency\":4,\"creativity\":5,\"fluency\":3}}";
        JudgeScoreParse.JudgeResult r = JudgeScoreParse.parse(raw);
        assertEquals(1, r.scores().size());
        assertTrue(r.scores().containsKey("consistency"));
    }

    /** 空/非法输入 → 空结果零阻断。 */
    @Test
    void parseHandlesBlankAndGarbage() {
        assertEquals(0, JudgeScoreParse.parse(null).scores().size());
        assertEquals(0, JudgeScoreParse.parse("").scores().size());
        assertEquals(0, JudgeScoreParse.parse("完全不是 JSON").scores().size());
        assertEquals(0, JudgeScoreParse.parse("{\"foo\":1}").scores().size());
        assertEquals(0, JudgeScoreParse.parse("{\"scores\":\"not-object\"}").scores().size());
    }

    /** 总分规则：空分数 → 0；单维 → 该维×10。 */
    @Test
    void totalRules() {
        assertEquals(0, JudgeScoreParse.parse("{\"scores\":{}}").total());
        assertEquals(30, JudgeScoreParse.parse("{\"scores\":{\"consistency\":3}}").total());
        Map<String, Integer> m = Map.of("consistency", 3, "reasonability", 3);
        JudgeScoreParse.JudgeResult r = new JudgeScoreParse.JudgeResult(m, Map.of(), null, "raw");
        assertEquals(30, r.total());
    }
}
