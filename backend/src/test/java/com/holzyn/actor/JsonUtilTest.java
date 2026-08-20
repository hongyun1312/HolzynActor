package com.holzyn.actor;

import com.holzyn.actor.common.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JsonUtil 单元测试（P2 结构化输出通用工具）。
 * <p>职责：验证从模型输出中提取 JSON 的能力（剥离代码块 / 截取花括号 / 空输入）。</p>
 */
class JsonUtilTest {

    /**
     * 纯净 JSON 直接通过。
     */
    @Test
    void extractPlainJson() {
        assertEquals("{\"a\":1}", JsonUtil.extractJson("{\"a\":1}"));
    }

    /**
     * Markdown 代码块包裹的 JSON 被剥离。
     */
    @Test
    void extractFromCodeBlock() {
        String input = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", JsonUtil.extractJson(input));
    }

    /**
     * 首尾有说明文字时截取花括号之间的 JSON。
     */
    @Test
    void extractFromSurroundingText() {
        String input = "好的，这是结果：{\"a\":1} 以上。";
        assertEquals("{\"a\":1}", JsonUtil.extractJson(input));
    }

    /**
     * JSON 数组（角色解析等场景）被完整提取（含代码块包裹）。
     */
    @Test
    void extractJsonArray() {
        assertEquals("[1,2]", JsonUtil.extractJson("前置说明 [1,2] 后置"));
        assertEquals("[{\"a\":1},{\"b\":2}]", JsonUtil.extractJson("```json\n[{\"a\":1},{\"b\":2}]\n```"));
    }
    /**
     * 空输入返回 null。
     */
    @Test
    void extractBlankReturnsNull() {
        assertNull(JsonUtil.extractJson(null));
        assertNull(JsonUtil.extractJson("  "));
    }
}