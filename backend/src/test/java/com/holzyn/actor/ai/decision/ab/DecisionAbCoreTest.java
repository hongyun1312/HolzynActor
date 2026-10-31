package com.holzyn.actor.ai.decision.ab;

import com.holzyn.actor.ai.decision.DecisionQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N2 A/B 评估单测：QwenAnswerParse 解析 + DecisionAbBatchRunner.top1Equals 一致判定
 * + 阈值标定分桶（纯静态，零依赖）。
 *
 * <p>测试纪律（V1.3 第六层）：纯单元，无 Spring 上下文；分布/桶级断言而非点断言。</p>
 *
 * <p>所属模块：ai/decision/ab（N 轨道 N2）</p>
 */
class DecisionAbCoreTest {

    // ==================== QwenAnswerParse ====================

    @Test
    @DisplayName("Qwen 输出解析：choice 候选键 / boolean / score 档位索引")
    void qwenParse_allTypes() {
        QwenAnswerParse.QwenAnswer choice = QwenAnswerParse.parse("{\"answer\":\"act_1\"}");
        assertNotNull(choice);
        assertEquals("act_1", choice.answer());

        QwenAnswerParse.QwenAnswer boolTrue = QwenAnswerParse.parse("{\"answer\": true}");
        assertNotNull(boolTrue);
        assertEquals(Boolean.TRUE, boolTrue.answer());

        QwenAnswerParse.QwenAnswer score = QwenAnswerParse.parse("{\"answer\": 2}");
        assertNotNull(score);
        assertEquals(2, score.answer());
    }

    @Test
    @DisplayName("Qwen 输出解析：Markdown 围栏包裹与前后说明文字容错")
    void qwenParse_toleratesMarkdownAndSurroundingText() {
        QwenAnswerParse.QwenAnswer a = QwenAnswerParse.parse("```json\n{\"answer\":\"continue_current\"}\n```");
        assertNotNull(a);
        assertEquals("continue_current", a.answer());

        QwenAnswerParse.QwenAnswer b = QwenAnswerParse.parse("我选择：{\"answer\": 1}（档位1）");
        assertNotNull(b);
        assertEquals(1, b.answer());
    }

    @Test
    @DisplayName("Qwen 输出解析：非法输入回退 null（样本标 failed，不参与一致率）")
    void qwenParse_invalidReturnsNull() {
        assertNull(QwenAnswerParse.parse(null));
        assertNull(QwenAnswerParse.parse(""));
        assertNull(QwenAnswerParse.parse("完全无法解析的文本"));
        assertNull(QwenAnswerParse.parse("{\"other\": 1}"));
    }

    @Test
    @DisplayName("Qwen 输出解析：boolean 宽松兜底（文本含 true 且无 false）")
    void qwenParse_booleanTextFallback() {
        QwenAnswerParse.QwenAnswer a = QwenAnswerParse.parse("答案是 true，愿意回应");
        assertNotNull(a);
        assertEquals(Boolean.TRUE, a.answer());
        QwenAnswerParse.QwenAnswer b = QwenAnswerParse.parse("答案是 false");
        assertNotNull(b);
        assertEquals(Boolean.FALSE, b.answer());
    }

    // ==================== top1Equals（一致判定） ====================

    @Test
    @DisplayName("top-1 一致：score 档位索引比较（决策模型 level vs Qwen 整数）")
    void top1Equals_score() {
        DecisionQuestion q = DecisionAbTemplates.emotionQuestion();
        assertTrue(DecisionAbBatchRunner.top1Equals(2, 2, q));
        assertTrue(DecisionAbBatchRunner.top1Equals("2", 2, q));
        assertFalse(DecisionAbBatchRunner.top1Equals(1, 2, q));
        assertFalse(DecisionAbBatchRunner.top1Equals(null, 2, q));
        assertFalse(DecisionAbBatchRunner.top1Equals(2, null, q));
    }

    @Test
    @DisplayName("top-1 一致：boolean 判定比较（决策模型 p_true>=0.5 vs Qwen true/false）")
    void top1Equals_boolean() {
        DecisionQuestion q = DecisionAbTemplates.willingnessQuestion();
        assertTrue(DecisionAbBatchRunner.top1Equals(true, Boolean.TRUE, q));
        assertTrue(DecisionAbBatchRunner.top1Equals(false, false, q));
        assertTrue(DecisionAbBatchRunner.top1Equals(true, "true", q));
        assertFalse(DecisionAbBatchRunner.top1Equals(true, Boolean.FALSE, q));
        assertFalse(DecisionAbBatchRunner.top1Equals(null, true, q));
    }

    @Test
    @DisplayName("top-1 一致：choice 候选键一致 + Qwen 输出描述文本时反向映射")
    void top1Equals_choice() {
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("continue_current", "继续当前正在做的事");
        candidates.put("act_1", "前往集市卖菜");
        DecisionQuestion q = DecisionAbTemplates.crowdQuestion(candidates);
        // 键一致
        assertTrue(DecisionAbBatchRunner.top1Equals("continue_current", "continue_current", q));
        // Qwen 输出描述文本 → 反向映射到键后一致
        assertTrue(DecisionAbBatchRunner.top1Equals("act_1", "前往集市卖菜", q));
        // 不一致
        assertFalse(DecisionAbBatchRunner.top1Equals("act_1", "continue_current", q));
        // 未知候选
        assertFalse(DecisionAbBatchRunner.top1Equals("act_1", "不存在的动作", q));
    }

    // ==================== 动态候选集提取 ====================

    @Test
    @DisplayName("候选集动态提取：从决策 response 提炼动作 + 通用项（≥5 项）")
    void dynamicCandidates_fromDecisionResponse() {
        String response = "<decision>{\"action_type\":\"start_action\",\"action_detail\":\"前往集市卖菜\","
                + "\"candidates\":[{\"action\":\"继续整理药材铺\"},{\"action\":\"去灵溪河洗衣\"}]}</decision>";
        Map<String, String> c = DecisionAbSampleFactory.dynamicCandidates(response);
        assertNotNull(c);
        assertTrue(c.size() >= 5, "候选集至少 5 项（3 通用 + 提取）");
        assertTrue(c.containsKey("continue_current"));
        assertTrue(c.containsKey("rest"));
        assertTrue(c.containsKey("chat"));
        assertTrue(c.containsKey("act_1"));
        assertEquals("前往集市卖菜", c.get("act_1"));
    }

    @Test
    @DisplayName("候选集动态提取：response 不可解析时回退纯通用项（仍合法 ≥3 项）")
    void dynamicCandidates_fallbackToCommon() {
        Map<String, String> c = DecisionAbSampleFactory.dynamicCandidates("不可解析的文本");
        assertNotNull(c);
        assertEquals(3, c.size());
        assertTrue(c.containsKey("continue_current"));
        assertTrue(c.containsKey("rest"));
        assertTrue(c.containsKey("chat"));
    }

    @Test
    @DisplayName("候选集动态提取：candidates 列表补充（前瞻推演产物）且不超过 8 项")
    void dynamicCandidates_withCandidateList() {
        StringBuilder sb = new StringBuilder("<decision>{\"action_type\":\"start_action\",\"action_detail\":\"去村长家议事\","
                + "\"candidates\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"action\":\"候选动作").append(i).append("\"}");
        }
        sb.append("]}</decision>");
        Map<String, String> c = DecisionAbSampleFactory.dynamicCandidates(sb.toString());
        assertNotNull(c);
        assertTrue(c.size() <= 8, "候选集不超过 8 项");
        assertTrue(c.size() >= 4);
    }

    // ==================== 阈值标定分桶 ====================

    @Test
    @DisplayName("阈值标定：inBucketValue 按 top-1 概率/熵分桶判断（纯静态）")
    void thresholdCalibration_buckets() {
        String lowEntropySharp = "{\"entropy\":0.4,\"probabilities\":{\"0\":0.1,\"1\":0.8,\"2\":0.1}}";
        String flatEntropy = "{\"entropy\":1.2,\"probabilities\":{\"0\":0.3,\"1\":0.3,\"2\":0.4}}";
        // top-1 概率分桶
        assertTrue(DecisionAbBatchRunner.inBucketValue(lowEntropySharp, "top1", ">=0.7"));
        assertTrue(DecisionAbBatchRunner.inBucketValue(flatEntropy, "top1", "0.4-0.5"));
        assertFalse(DecisionAbBatchRunner.inBucketValue(lowEntropySharp, "top1", "0.4-0.5"));
        // 熵分桶
        assertTrue(DecisionAbBatchRunner.inBucketValue(lowEntropySharp, "entropy", "<0.5"));
        assertTrue(DecisionAbBatchRunner.inBucketValue(flatEntropy, "entropy", "1.0-1.5"));
        assertFalse(DecisionAbBatchRunner.inBucketValue(lowEntropySharp, "entropy", ">=1.5"));
        // 不可解析 → false
        assertFalse(DecisionAbBatchRunner.inBucketValue("不可解析", "top1", ">=0.7"));
    }
}
