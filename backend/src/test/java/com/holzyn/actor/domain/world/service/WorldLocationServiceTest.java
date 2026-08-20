package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldLocationService.IncrementalJsonArrayParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldLocationService 流式地点提取的增量 JSON 数组元素解析器测试。
 * <p>验证：完整数组切块 / 任意字符切分 / 字符串内花括号 / 代码块前后缀 /
 * 末尾未闭合元素忽略 / 嵌套对象与数组。</p>
 */
class WorldLocationServiceTest {

    /** 完整数组一次性喂入：两个元素都被识别 */
    @Test
    void parserEmitsCompleteElementsFromFullArray() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("[{\"name\":\"妖灵会馆总馆\",\"type\":\"组织总部\",\"intro\":\"昆仑山脉。\",\"importance\":5},"
                + "{\"name\":\"流石会馆\",\"type\":\"会馆\",\"intro\":\"横断山区。\",\"importance\":4}]");
        assertEquals(2, elements.size());
        assertTrue(elements.get(0).contains("妖灵会馆总馆"));
        assertTrue(elements.get(1).contains("流石会馆"));
    }

    /** 逐字符喂入（模拟流式 token 任意切分）：仍能正确识别完整元素 */
    @Test
    void parserHandlesArbitraryChunkSplits() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        String json = "[{\"name\":\"冰云城\",\"type\":\"城市\",\"intro\":\"关押重犯的审判重地。\",\"importance\":4}]";
        for (char c : json.toCharArray()) {
            p.feed(String.valueOf(c));
        }
        assertEquals(1, elements.size());
        assertTrue(elements.get(0).contains("冰云城"));
    }

    /** 字符串内部的花括号/转义不参与元素闭合判断 */
    @Test
    void parserHandlesBracesInsideStrings() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("[{\"name\":\"钢厂\",\"intro\":\"设下断金阵 {金属法阵} 的地方。\",\"importance\":3}]");
        assertEquals(1, elements.size());
        assertTrue(elements.get(0).contains("断金阵"));
    }

    /** Markdown 代码块前后缀被忽略 */
    @Test
    void parserSkipsCodeFencePrefixSuffix() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("```json\n[{\"name\":\"蓝溪镇\",\"type\":\"城镇\",\"intro\":\"中立特区。\",\"importance\":4}]\n```");
        assertEquals(1, elements.size());
        assertTrue(elements.get(0).contains("蓝溪镇"));
    }

    /** 末尾未闭合的元素不触发回调（等待后续或由权威重解析兜底） */
    @Test
    void parserIgnoresIncompleteTrailingElement() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("[{\"name\":\"龙游会馆\",\"type\":\"会馆\",\"intro\":\"东部沿海。\",\"importance\":4},{\"name\":\"苍南会馆\"");
        assertEquals(1, elements.size());
        assertTrue(elements.get(0).contains("龙游会馆"));
    }

    /** 元素内的嵌套对象与嵌套数组不影响外层元素识别 */
    @Test
    void parserHandlesNestedObjectsAndArrays() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("[{\"name\":\"老君山\",\"tags\":[\"秘境\",{\"k\":\"v\"}],\"intro\":\"山体内部嵌套灵质空间。\",\"importance\":5}]");
        assertEquals(1, elements.size());
        assertTrue(elements.get(0).contains("老君山"));
    }

    /** 空数组 / 空文本：无回调 */
    @Test
    void parserEmitsNothingForEmptyArray() {
        List<String> elements = new ArrayList<>();
        IncrementalJsonArrayParser p = new IncrementalJsonArrayParser(elements::add);
        p.feed("[]");
        assertEquals(0, elements.size());
    }
}
