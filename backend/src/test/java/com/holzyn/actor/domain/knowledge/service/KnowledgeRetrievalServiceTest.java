package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeRetrievalService 纯逻辑单元测试（P3 知识库 RAG）。
 * <p>职责：验证文本分块（大小/重叠/空文本）、句子切分、余弦相似度与文本关键词降级打分。</p>
 */
class KnowledgeRetrievalServiceTest {

    /**
     * 分块：长文本按约 500 字切分，每块不超上限，且内容无丢失（拼接还原主要文本）。
     */
    @Test
    void chunkSplitsLongTextWithinLimit() {
        // 构造 ~1500 字文本（50 句 × 30 字）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("第").append(i).append("句：晨曦大陆地理设定与势力分布描述内容，用于测试分块逻辑的正确性。");
        }
        List<String> chunks = KnowledgeRetrievalService.chunk(sb.toString(), 500, 50);
        assertTrue(chunks.size() >= 3, "约 1500 字应分为至少 3 块，实际 " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 500, "单块不应超过 500 字，实际 " + c.length());
        }
        // 关键内容完整保留
        String joined = String.join("", chunks);
        assertTrue(joined.contains("第0句"), "首句内容应保留");
        assertTrue(joined.contains("第49句"), "末句内容应保留");
    }

    /**
     * 分块：空文本/空白返回空列表。
     */
    @Test
    void chunkEmptyReturnsEmpty() {
        assertTrue(KnowledgeRetrievalService.chunk("", 500, 50).isEmpty());
        assertTrue(KnowledgeRetrievalService.chunk("   \n  ", 500, 50).isEmpty());
        assertTrue(KnowledgeRetrievalService.chunk(null, 500, 50).isEmpty());
    }

    /**
     * 句子切分：按中文/英文标点与换行切分并保留标点。
     */
    @Test
    void splitIntoSentencesSplitsByPunctuation() {
        List<String> sentences = KnowledgeRetrievalService.splitIntoSentences("第一句。第二句！第三句？\n第四句；第五句");
        assertEquals(5, sentences.size());
        assertEquals("第一句。", sentences.get(0));
        assertTrue(sentences.get(4).contains("第五句"));
    }

    /**
     * 余弦相似度：相同向量=1，正交向量=0，维度不同=0。
     */
    @Test
    void cosineSimilarityBasics() {
        assertEquals(1.0, KnowledgeRetrievalService.cosine(new float[]{1, 2, 3}, new float[]{1, 2, 3}), 1e-9);
        assertEquals(0.0, KnowledgeRetrievalService.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(0.0, KnowledgeRetrievalService.cosine(new float[]{1, 0, 0}, new float[]{1, 0}), 1e-9);
        // 同向不同模长：余弦仍为 1
        assertEquals(1.0, KnowledgeRetrievalService.cosine(new float[]{1, 2}, new float[]{2, 4}), 1e-9);
        // 部分相似 > 无关
        double similar = KnowledgeRetrievalService.cosine(new float[]{1, 1, 0}, new float[]{1, 1, 0});
        double different = KnowledgeRetrievalService.cosine(new float[]{1, 1, 0}, new float[]{0, 0, 1});
        assertTrue(similar > different);
    }

    /**
     * 文本关键词降级打分：命中查询的内容得分 > 无关内容；空查询=0。
     */
    @Test
    void textScoreRanksRelevantContent() {
        double hit = KnowledgeRetrievalService.textScore("晨曦大陆地理", "晨曦大陆位于永昼高原，地理险峻。");
        double miss = KnowledgeRetrievalService.textScore("晨曦大陆地理", "这里的市场很热闹，人们往来穿梭。");
        assertTrue(hit > miss, "命中查询的文档应得分更高");
        assertEquals(0, KnowledgeRetrievalService.textScore("", "任意内容"));
        assertEquals(0, KnowledgeRetrievalService.textScore("查询", null));
    }

    /**
     * Embedding 批量拆分：11 条输入按每批 10 拆为 2 批，顺序与条数正确（修复「input limit exceeded: max 10, got 11」）。
     */
    @Test
    void embeddingBatchSplitsOverLimit() {
        String[] texts = new String[11];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = "文本" + i;
        }
        var batches = EmbeddingService.splitIntoBatches(texts, 10);
        assertEquals(2, batches.size(), "11 条应拆为 2 批");
        assertEquals(10, batches.get(0).length);
        assertEquals(1, batches.get(1).length);
        // 顺序保持：第 2 批第 1 条 = 输入第 11 条
        assertEquals("文本10", batches.get(1)[0]);
    }

    /**
     * Embedding 批量拆分：恰好等于上限 / 小于上限 / 空输入边界。
     */
    @Test
    void embeddingBatchSplitsBoundaries() {
        String[] ten = new String[10];
        for (int i = 0; i < ten.length; i++) ten[i] = "t" + i;
        assertEquals(1, EmbeddingService.splitIntoBatches(ten, 10).size(), "恰好 10 条 = 1 批");
        String[] three = {"a", "b", "c"};
        assertEquals(1, EmbeddingService.splitIntoBatches(three, 10).size(), "少于上限 = 1 批");
        assertTrue(EmbeddingService.splitIntoBatches(new String[0], 10).isEmpty(), "空输入 = 空批次");
    }
}
