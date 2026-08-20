package com.holzyn.actor.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JsonColumnConverter 单元测试。
 * <p>验证 H2 JSON 列「双重编码」的解包逻辑：单层 JSON 原样、双重/多重编码逐层解包、
 * 数组解包、非法文本/空值容错、入库透传。</p>
 * <p>背景：本地 H2（MODE=MySQL）下 JDBC 读 JSON 列会返回 JSON 字符串字面量
 * （多一层引号与转义），转换器在实体读取路径上恢复单层 JSON 文本。</p>
 * <p>所属模块：common（通用组件）测试，与主代码同包。</p>
 */
class JsonColumnConverterTest {

    private final JsonColumnConverter converter = new JsonColumnConverter();

    /** 构造「字符串字面量包裹」：模拟 H2 JSON 列读回的追加层（先转义反斜杠再转义引号，与 H2 实际输出一致） */
    private static String wrap(String json) {
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    @Test
    void 单层对象原样返回() {
        String input = "{\"identity\": {\"name\": \"n\"}}";
        assertEquals(input, converter.convertToEntityAttribute(input));
    }

    @Test
    void 单层数组原样返回() {
        String input = "[{\"text\": \"x\", \"embedding\": [1,2]}]";
        assertEquals(input, converter.convertToEntityAttribute(input));
    }

    @Test
    void 双重编码对象解包一层() {
        String inner = "{\"identity\": {\"name\": \"n\"}}";
        String wrapped = wrap(inner);
        assertEquals(inner, converter.convertToEntityAttribute(wrapped));
    }

    @Test
    void 双重编码数组解包() {
        String inner = "[{\"text\": \"x\"}]";
        String wrapped = wrap(inner);
        assertEquals(inner, converter.convertToEntityAttribute(wrapped));
    }

    @Test
    void 多重编码循环解包至对象() {
        // 修复前导出的 .holzyn 再导入：可能已多包一层，应循环解包到对象为止
        String inner = "{\"identity\": {\"name\": \"n\"}}";
        String once = wrap(inner);
        String twice = wrap(once);
        String thrice = wrap(twice);
        assertEquals(inner, converter.convertToEntityAttribute(twice));
        assertEquals(inner, converter.convertToEntityAttribute(thrice));
    }

    @Test
    void 短字符串值不重复解包() {
        // 内层不是 JSON 形状（不以 { [ " 开头）：保持原样返回（含引号），防止过度解包
        assertEquals("\"hello\"", converter.convertToEntityAttribute("\"hello\""));
    }

    @Test
    void 空值与null原样返回() {
        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToEntityAttribute(""));
        assertEquals("   ", converter.convertToEntityAttribute("   "));
    }

    @Test
    void 非法文本原样返回() {
        assertEquals("not json", converter.convertToEntityAttribute("not json"));
        assertEquals("12345", converter.convertToEntityAttribute("12345"));
    }

    @Test
    void 入库原样透传() {
        String json = "{\"a\":1}";
        assertEquals(json, converter.convertToDatabaseColumn(json));
        assertNull(converter.convertToDatabaseColumn(null));
    }
}
