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
     * 抽取解析：对象包装 {facts:[...]} 兼容（2026-09-12 M3 交接冒烟抓修——
     * deepseek json_object 模式在「只输出严格 JSON」+「只输出 JSON 数组」双重指令下
     * 常把数组包成对象且字段名用 facts，原实现仅认 memories → 解析为空静默丢事实）。
     */
    @Test
    void parseExtractAcceptsFactsWrapper() {
        String ai = "{\"facts\":[{\"kind\":\"fact\",\"content\":\"罗小黑否认自己曾说要改名墨白\",\"importance\":4},"
                + "{\"content\":\"罗小黑的师父是无限\",\"importance\":2}]}";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(2, out.size());
        assertEquals("罗小黑否认自己曾说要改名墨白", out.get(0).content());
        assertEquals(4, out.get(0).importance());
        assertEquals("罗小黑的师父是无限", out.get(1).content());
    }

    /**
     * 抽取解析：{facts:[...], reason:"..."} 混合对象（facts 优先，reason 等非数组字段忽略）。
     */
    @Test
    void parseExtractAcceptsFactsWrapperWithReason() {
        String ai = "{\"reason\":\"无\",\"facts\":[{\"content\":\"a\"},{\"content\":\"b\"}]}";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(2, out.size());
    }

    /**
     * 抽取解析：memories 与 facts 同时存在时优先 memories（既有行为不回归）。
     */
    @Test
    void parseExtractPrefersMemoriesOverFacts() {
        String ai = "{\"memories\":[{\"content\":\"m\"}],\"facts\":[{\"content\":\"f\"}]}";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        assertEquals("m", out.get(0).content());
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
     * 抽取解析：兼容单对象输出（模型偶尔返回 {kind,content,importance} 而非数组；
     * 2026-08-21 B5 冒烟抓修——否则记忆抽取静默丢事实，普通 NPC 记忆沉淀不落库）。
     */
    @Test
    void parseExtractAcceptsSingleObject() {
        String ai = "{\"kind\":\"fact\",\"content\":\"玩家来自猎户村，名叫阿明\",\"importance\":3}";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size(), "单对象应解析为一条记忆");
        assertEquals("fact", out.get(0).kind());
        assertEquals("玩家来自猎户村，名叫阿明", out.get(0).content());
        assertEquals(3, out.get(0).importance());
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
