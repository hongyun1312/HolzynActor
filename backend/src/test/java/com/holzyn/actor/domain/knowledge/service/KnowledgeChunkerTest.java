package com.holzyn.actor.domain.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeChunker 单元测试（AI 接入层重构 M3，2026-09-05，方案 §5.3 抽取；原逻辑由
 * KnowledgeRetrievalServiceTest 覆盖，此处补独立直测，验证抽取后行为不回归）。
 * <p>职责：验证中文分块——①按句子边界分块 + 超限带重叠；②空/空白/超长句兜底；③句子切分保留标点。</p>
 */
class KnowledgeChunkerTest {

    /**
     * 分块：短文本单块；长文本按 chunkSize 切分且相邻块带重叠。
     */
    @Test
    void chunkSplitsByChunkSizeWithOverlap() {
        // 短文本：单块
        List<String> single = KnowledgeChunker.chunk("晨曦大陆位于永昼高原，地理险峻。", 500, 50);
        assertEquals(1, single.size());

        // 长文本（30 句 ≈ 690 字 > 500）：多块，块间重叠（第二块开头应含第一块尾部文本）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("晨曦大陆位于永昼高原，地理险峻，云雾缭绕。");
        }
        List<String> chunks = KnowledgeChunker.chunk(sb.toString(), 500, 50);
        assertTrue(chunks.size() > 1, "超长文本应切分为多块");
        assertTrue(chunks.get(1).contains("云雾缭绕"), "第二块应含重叠尾部");
    }

    /**
     * 空/空白/超长句兜底：空文本返回空列表；超长句硬切分不丢内容。
     */
    @Test
    void chunkHandlesEmptyAndLongSentence() {
        assertTrue(KnowledgeChunker.chunk("", 500, 50).isEmpty());
        assertTrue(KnowledgeChunker.chunk("   \n  ", 500, 50).isEmpty());
        assertTrue(KnowledgeChunker.chunk(null, 500, 50).isEmpty());
        // 无标点超长句：硬切分
        StringBuilder noPunct = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            noPunct.append('字');
        }
        List<String> chunks = KnowledgeChunker.chunk(noPunct.toString(), 500, 50);
        assertTrue(chunks.size() >= 6, "3000 字无标点按 500 字硬切分至少 6 块");
        assertTrue(chunks.stream().mapToInt(String::length).sum() >= noPunct.length(),
                "重叠只会增加总长度，不会丢内容段");
    }

    /**
     * 句子切分：以中文/英文标点与换行为边界，纯标点片段并入上一句。
     */
    @Test
    void splitIntoSentencesSplitsAtPunctuation() {
        List<String> sentences = KnowledgeChunker.splitIntoSentences("第一句。第二句！第三句？\n第四句；第五句");
        assertEquals(5, sentences.size());
        assertEquals("第一句。", sentences.get(0));
        assertEquals("第五句", sentences.get(4));
    }
}
