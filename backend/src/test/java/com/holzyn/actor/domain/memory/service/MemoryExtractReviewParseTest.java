package com.holzyn.actor.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆抽取「按需复核」解析单元测试（记忆系统重构 M1，2026-09-12）。
 * <p>职责：验证 memory_extract_review 输出解析——index→判定映射（keep/drop/modify）、
 * 非法输入/空结果、未提及 index 缺省 keep（调用方语义）。</p>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案_V1.0》§3.2 三重机制③。</p>
 */
class MemoryExtractReviewParseTest {

    /**
     * 标准数组解析：index→action/content/importance 映射。
     */
    @Test
    void parsesReviewArray() {
        String ai = "[{\"index\":0,\"action\":\"keep\"},"
                + "{\"index\":1,\"action\":\"drop\"},"
                + "{\"index\":2,\"action\":\"modify\",\"content\":\"修正后的内容\",\"importance\":2}]";
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> out = MemoryExtractReviewParse.parseReview(ai);
        assertEquals(3, out.size());
        assertTrue(out.get(0).isDrop() == false);
        assertEquals("keep", out.get(0).action());
        assertTrue(out.get(1).isDrop());
        assertEquals("modify", out.get(2).action());
        assertEquals("修正后的内容", out.get(2).content());
        assertEquals(2, out.get(2).importance());
    }

    /**
     * reviews/results 包装兼容（模型偶尔包一层）。
     */
    @Test
    void parsesWrappedReviews() {
        String ai = "{\"reviews\":[{\"index\":0,\"action\":\"drop\"}]}";
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> out = MemoryExtractReviewParse.parseReview(ai);
        assertEquals(1, out.size());
        assertTrue(out.get(0).isDrop());
    }

    /**
     * modify 缺省 content/importance：可空（调用方沿用初判）。
     */
    @Test
    void modifyAllowsBlankContentAndImportance() {
        String ai = "[{\"index\":0,\"action\":\"modify\"}]";
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> out = MemoryExtractReviewParse.parseReview(ai);
        assertEquals(1, out.size());
        assertNull(out.get(0).content());
        assertNull(out.get(0).importance());
    }

    /**
     * 非法输入：空/非法 JSON/无 index 条目 → 空 Map（不抛异常，调用方按初判执行）。
     */
    @Test
    void handlesInvalidInputs() {
        assertTrue(MemoryExtractReviewParse.parseReview(null).isEmpty());
        assertTrue(MemoryExtractReviewParse.parseReview("").isEmpty());
        assertTrue(MemoryExtractReviewParse.parseReview("不是 JSON").isEmpty());
        assertTrue(MemoryExtractReviewParse.parseReview("[{\"action\":\"drop\"}]").isEmpty(), "无 index 应丢弃");
    }

    /**
     * action 非法值回退 keep；importance 越界钳制 1~5。
     */
    @Test
    void normalizesIllegalActionAndClampsImportance() {
        String ai = "[{\"index\":0,\"action\":\"banana\",\"importance\":9}]";
        Map<Integer, MemoryExtractReviewParse.ReviewDecision> out = MemoryExtractReviewParse.parseReview(ai);
        assertEquals(1, out.size());
        assertEquals("keep", out.get(0).action());
        assertEquals(5, out.get(0).importance());
    }
}
