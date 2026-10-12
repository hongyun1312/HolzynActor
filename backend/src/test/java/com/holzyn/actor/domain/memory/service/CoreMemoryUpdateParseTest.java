package com.holzyn.actor.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 核心记忆自编辑 AI 输出解析器单元测试（M3，domain/memory/service，同包）。
 * <p>职责：验证 core_memory_update 模板输出解析——正常数组 / 空输出 / 非法 action /
 * 缺失 block_key / 非删除指令缺 content / Markdown 代码块兼容 / importance 钳制 / 整体非法容错。</p>
 */
class CoreMemoryUpdateParseTest {

    /**
     * 正常解析：update/create/delete 三种 action 齐全，字段完整。
     */
    @Test
    void parseNormalUpdates() {
        String ai = """
                {
                  "updates": [
                    { "action": "update", "block_key": "beliefs", "content": "守护家园高于一切", "importance": 5, "reason": "经历改变" },
                    { "action": "create", "block_key": "custom:近期目标", "block_name": "近期目标", "content": "修复与旧友的关系", "importance": 3 },
                    { "action": "delete", "block_key": "values", "reason": "已被新价值观取代" }
                  ],
                  "reason": "经历推动身份演进"
                }
                """;
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(ai);

        assertEquals(3, updates.size());
        assertEquals("update", updates.get(0).action());
        assertEquals("beliefs", updates.get(0).blockKey());
        assertEquals("守护家园高于一切", updates.get(0).content());
        assertEquals(5, updates.get(0).importance());
        assertEquals("create", updates.get(1).action());
        assertEquals("custom:近期目标", updates.get(1).blockKey());
        assertEquals("近期目标", updates.get(1).blockName());
        assertEquals("delete", updates.get(2).action());
        assertEquals("values", updates.get(2).blockKey());
        assertEquals("", updates.get(2).content());
    }

    /**
     * 空输出（无身份级变化）：updates=[] → 空列表。
     */
    @Test
    void parseEmptyUpdates() {
        String ai = "{ \"updates\": [], \"reason\": \"无身份级变化\" }";
        assertTrue(CoreMemoryUpdateParse.parse(ai).isEmpty());
    }

    /**
     * Markdown 代码块包裹兼容（复用 JsonUtil.extractJson）。
     */
    @Test
    void parseMarkdownCodeBlock() {
        String ai = "```json\n{\"updates\":[{\"action\":\"create\",\"block_key\":\"custom:座右铭\",\"content\":\"行胜于言\",\"importance\":4}]}\n```";
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(ai);
        assertEquals(1, updates.size());
        assertEquals("行胜于言", updates.get(0).content());
    }

    /**
     * 非法 action 丢弃；缺省 action 视为 update。
     */
    @Test
    void parseInvalidActionDroppedAndDefaultUpdate() {
        String ai = """
                { "updates": [
                    { "action": "explode", "block_key": "beliefs", "content": "不应出现" },
                    { "block_key": "values", "content": "缺省 action 视为 update" }
                ] }
                """;
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(ai);
        assertEquals(1, updates.size());
        assertEquals("update", updates.get(0).action());
        assertEquals("values", updates.get(0).blockKey());
    }

    /**
     * 缺失 block_key 丢弃；非删除指令缺 content 丢弃。
     */
    @Test
    void parseMissingKeyOrContentDropped() {
        String ai = """
                { "updates": [
                    { "action": "update", "content": "无块键，丢弃" },
                    { "action": "create", "block_key": "beliefs" },
                    { "action": "delete", "block_key": "relations" }
                ] }
                """;
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(ai);
        assertEquals(1, updates.size());
        assertEquals("delete", updates.get(0).action());
        assertEquals("relations", updates.get(0).blockKey());
    }

    /**
     * importance 钳制 1~5、缺省 3；首尾说明文字兼容。
     */
    @Test
    void parseImportanceClamped() {
        String ai = "以下是更新方案：\n{\"updates\":[{\"action\":\"update\",\"block_key\":\"identity\",\"content\":\"阿明\",\"importance\":99}]}\n完毕";
        List<CoreMemoryUpdateParse.CoreMemoryUpdate> updates = CoreMemoryUpdateParse.parse(ai);
        assertEquals(1, updates.size());
        assertEquals(5, updates.get(0).importance());
    }

    /**
     * 整体非法（非 JSON / null / 空白）→ 空列表，不抛异常。
     */
    @Test
    void parseInvalidInputReturnsEmpty() {
        assertTrue(CoreMemoryUpdateParse.parse(null).isEmpty());
        assertTrue(CoreMemoryUpdateParse.parse("  ").isEmpty());
        assertTrue(CoreMemoryUpdateParse.parse("这不是 JSON").isEmpty());
        assertTrue(CoreMemoryUpdateParse.parse("{\"foo\":\"bar\"}").isEmpty());
    }
}
