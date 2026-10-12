package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeRetrievalService 纯逻辑单元测试（P3 知识库 RAG）。
 * <p>职责：验证文本分块（大小/重叠/空文本）、句子切分、批量拆分与文本关键词降级打分。
 * （余弦相似度 M5 起随旧余弦路径移除，改由 SemanticCacheService 内部实现并单独测试）</p>
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
     * M5 起静态方法随旧 EmbeddingService 迁入 {@link KnowledgeChunker}。
     */
    @Test
    void embeddingBatchSplitsOverLimit() {
        String[] texts = new String[11];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = "文本" + i;
        }
        var batches = KnowledgeChunker.splitIntoBatches(texts, 10);
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
        assertEquals(1, KnowledgeChunker.splitIntoBatches(ten, 10).size(), "恰好 10 条 = 1 批");
        String[] three = {"a", "b", "c"};
        assertEquals(1, KnowledgeChunker.splitIntoBatches(three, 10).size(), "少于上限 = 1 批");
        assertTrue(KnowledgeChunker.splitIntoBatches(new String[0], 10).isEmpty(), "空输入 = 空批次");
    }

    // ==================== 有界命中片段 excerpt（round-46：不整篇注入世界观） ====================

    /** 世界设定长文档：12 段重复句（~60 字/段 ≈ 720 字，远超 400 字窗口预算）+ 结尾含唯一命中词「灵蚀症」。 */
    private static String longDoc() {
        String base = "青禾村地处青岳山东麓灵溪河畔，气候温和，村民世代以耕种灵稻为生，兼营采药打铁，月相潮汐决定灵气浓淡，望日充盈朔日稀薄。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(base);
        }
        sb.append("灵蚀症是过度吸纳灵息的病症，需在望日由村医以针灸引气疏导治疗。");
        return sb.toString();
    }

    /** 短文档（≤ 窗口预算）：原样返回，无省略号。 */
    @Test
    void excerptShortContentReturnsFull() {
        String shortDoc = "守脉堂掌控古灵脉封印，每逢望日征收灵税。";
        assertEquals(shortDoc, KnowledgeRetrievalService.excerpt(shortDoc, "守脉堂", 400));
        assertEquals(shortDoc, KnowledgeRetrievalService.excerpt(shortDoc, "无关查询", 400));
        assertEquals("", KnowledgeRetrievalService.excerpt(null, "q", 400));
        assertEquals("", KnowledgeRetrievalService.excerpt("   ", "q", 400));
    }

    /** 长文档命中：窗口有界、含命中内容、不再整篇注入（round-46 回归：此前整篇返回）。 */
    @Test
    void excerptLongContentWindowsAroundMatch() {
        String doc = longDoc();
        assertTrue(doc.length() > 500, "测试文档需超过窗口预算以便验证截断");
        String result = KnowledgeRetrievalService.excerpt(doc, "灵蚀症", 400);
        assertTrue(result.contains("灵蚀症"), "窗口应含命中内容，实际: " + result);
        assertTrue(result.length() <= KnowledgeRetrievalService.SNIPPET_WINDOW_CHARS * 2 + 2,
                "窗口不超 2 倍预算（+省略号），实际 " + result.length());
        assertTrue(result.length() < doc.length(), "不再整篇注入（长文档必须截断）");
    }

    /** 长文档全文只到一处命中：不得把文首文尾全带上（整篇注入回归断言）。 */
    @Test
    void excerptDoesNotInjectWholeWorldview() {
        String doc = longDoc();
        String result = KnowledgeRetrievalService.excerpt(doc, "灵蚀症", 400);
        assertTrue(result.length() <= KnowledgeRetrievalService.SNIPPET_WINDOW_CHARS * 2 + 2,
                "知识段单条必须保持在窗口预算内（含句边界扩展），实际 " + result.length());
        assertTrue(!result.equals(doc), "绝不能返回整篇文档");
    }

    /** 无位置锚点（query 与正文无文本交集）：取文首句边界窗口 + 省略号。 */
    @Test
    void excerptNoAnchorTakesHead() {
        String result = KnowledgeRetrievalService.excerpt(longDoc(), "完全无关的查询词", 400);
        assertTrue(result.startsWith("青禾村地处青岳山东麓"), "无锚点应取文首");
        assertTrue(result.endsWith("…"), "截断应有省略号标注，实际: " + result);
        assertTrue(result.length() <= KnowledgeRetrievalService.SNIPPET_WINDOW_CHARS + 1,
                "文首窗口不超预算（+省略号），实际 " + result.length());
    }

    /** round-45 残句回归：无省略号收尾时必收在完整句边界（不得以「的」「（灵石或」这类残句结束）。 */
    @Test
    void excerptNeverCutsMidSentenceWithoutMarker() {
        String result = KnowledgeRetrievalService.excerpt(longDoc(), "灵蚀症", 400);
        if (!result.endsWith("…")) {
            assertTrue(result.endsWith("。") || result.endsWith("！") || result.endsWith("？"),
                    "无省略号收尾必须收在完整句边界，实际尾部: " + tail(result, 12));
        }
        // 文首被截断必有省略号标注
        String headCut = KnowledgeRetrievalService.excerpt(longDoc(), "灵蚀症", 60);
        assertTrue(headCut.startsWith("…"), "文首被截断必须有省略号标注，实际: " + headCut);
    }

    /** 长 query 整体未命中时按二元组定位（与 textScore 打分口径一致）。 */
    @Test
    void excerptAnchorsByBigramWhenWholeQueryMisses() {
        String result = KnowledgeRetrievalService.excerpt(longDoc(), "灵蚀症的症状是什么", 400);
        assertTrue(result.contains("灵蚀症"), "整体未命中时应按二元组定位到「灵蚀」附近");
    }

    /** 预算兜底：maxChars<=0 回退默认窗口，不抛异常。 */
    @Test
    void excerptBudgetFallback() {
        String result = KnowledgeRetrievalService.excerpt(longDoc(), "灵蚀症", 0);
        assertTrue(result.contains("灵蚀症"));
        assertTrue(result.length() <= KnowledgeRetrievalService.SNIPPET_WINDOW_CHARS * 2 + 2);
    }

    /** 取字符串尾部 N 字（断言诊断用）。 */
    private static String tail(String s, int n) {
        return s.substring(Math.max(0, s.length() - n));
    }
}
