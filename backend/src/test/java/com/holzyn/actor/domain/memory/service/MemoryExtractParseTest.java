package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.service.MemoryExtractParse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryExtractParse 纯逻辑单元测试（P4-1 长期记忆）。
 * <p>职责：验证记忆抽取/摘要 AI 输出的 JSON 解析——数组/对象包装/Markdown 包裹/非法输入/字段钳制。</p>
 */
class MemoryExtractParseTest {

    /**
     * 抽取解析：标准 JSON 数组 → 逐条提取 kind/content/importance。
     */
    @Test
    void parseExtractParsesArray() {
        String ai = "[{\"kind\":\"fact\",\"content\":\"李雷在城东市场开了药材铺\",\"importance\":4},"
                + "{\"content\":\"林安与城主结盟\",\"importance\":5}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(2, out.size());
        assertEquals("fact", out.get(0).kind());
        assertEquals("李雷在城东市场开了药材铺", out.get(0).content());
        assertEquals(4, out.get(0).importance());
        // kind 缺省回退 fact
        assertEquals("fact", out.get(1).kind());
        assertEquals(5, out.get(1).importance());
    }

    /**
     * 抽取解析：Markdown 代码块包裹的 JSON 数组。
     */
    @Test
    void parseExtractStripsMarkdownFence() {
        String ai = "```json\n[{\"content\":\"世界树枯萎了\",\"importance\":3}]\n```";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        assertEquals("世界树枯萎了", out.get(0).content());
    }

    /**
     * 抽取解析：对象包装 {memories:[...]} 兼容。
     */
    @Test
    void parseExtractAcceptsObjectWrapper() {
        String ai = "{\"memories\":[{\"content\":\"a\"},{\"content\":\"b\",\"importance\":2}]}";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(2, out.size());
    }

    /**
     * 抽取解析：空数组/空白/非法 JSON → 空列表（不抛异常）。
     */
    @Test
    void parseExtractHandlesEmptyAndInvalid() {
        assertTrue(MemoryExtractParse.parseExtract("[]").isEmpty());
        assertTrue(MemoryExtractParse.parseExtract("").isEmpty());
        assertTrue(MemoryExtractParse.parseExtract(null).isEmpty());
        assertTrue(MemoryExtractParse.parseExtract("这不是 JSON").isEmpty());
        assertTrue(MemoryExtractParse.parseExtract("{\"foo\":1}").isEmpty(), "无 memories 数组的对象应返回空");
    }

    /**
     * 抽取解析：content 空白条目丢弃；importance 越界钳制到 1~5。
     */
    @Test
    void parseExtractFiltersBlankAndClampsImportance() {
        String ai = "[{\"content\":\"  \",\"importance\":9},{\"content\":\"有效记忆\",\"importance\":0}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size(), "空白 content 应被丢弃");
        assertEquals("有效记忆", out.get(0).content());
        assertEquals(1, out.get(0).importance(), "importance 越界应钳制到 1");
    }

    /**
     * 摘要解析：标准 JSON 对象 → 摘要文本。
     */
    @Test
    void parseSummarizeParsesObject() {
        assertEquals("李雷与林安商定了秋季贸易约定", MemoryExtractParse.parseSummarize("{\"content\":\"李雷与林安商定了秋季贸易约定\"}"));
    }

    /**
     * 摘要解析：Markdown 包裹 / 非法 / 空白 → null。
     */
    @Test
    void parseSummarizeHandlesEdgeCases() {
        assertEquals("摘要", MemoryExtractParse.parseSummarize("```json\n{\"content\":\"摘要\"}\n```"));
        assertNull(MemoryExtractParse.parseSummarize("不是 JSON"));
        assertNull(MemoryExtractParse.parseSummarize("{\"content\":\"  \"}"));
        assertNull(MemoryExtractParse.parseSummarize(null));
        assertNull(MemoryExtractParse.parseSummarize("[]"), "非对象输入应返回 null");
    }
}
