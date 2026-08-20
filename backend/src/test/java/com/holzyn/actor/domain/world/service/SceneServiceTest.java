package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.SceneService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneService 纯逻辑单元测试（vP5-7.6 AI 自动填充场景）。
 * <p>覆盖 parseGenerated：AI 场景数组解析（字段提取/数量上限/名称缺失跳过/非法输入兜底）。</p>
 * <p>所属模块：service/world（场景子域）</p>
 */
class SceneServiceTest {

    /**
     * 正常数组解析：字段完整提取，含 source 来源依据。
     */
    @Test
    void parseValidArray() {
        String json = """
                [
                  {"name":"城东药材铺","location":"城东市场","description":"林安经营的药材铺","background":"药香浓郁，常有行脚商人歇脚","source":"取自世界观【地理设定】城东市场与角色【林安】的药材铺"},
                  {"name":"王都西门","location":"王都西侧城墙","description":"王国西面门户","background":"重兵把守的城门口，往来商旅频繁","source":"取自世界观【历史背景】王都防卫"}
                ]""";
        List<Map<String, Object>> list = SceneService.parseGenerated(json, 10);
        assertEquals(2, list.size());
        assertEquals("城东药材铺", list.get(0).get("name"));
        assertEquals("城东市场", list.get(0).get("location"));
        assertEquals("取自世界观【地理设定】城东市场与角色【林安】的药材铺", list.get(0).get("source"));
        assertEquals("王都西门", list.get(1).get("name"));
    }

    /**
     * 数量上限：count 限制返回条数。
     */
    @Test
    void parseRespectsCountLimit() {
        String json = """
                [{"name":"甲"},{"name":"乙"},{"name":"丙"}]""";
        List<Map<String, Object>> list = SceneService.parseGenerated(json, 2);
        assertEquals(2, list.size());
        assertEquals("甲", list.get(0).get("name"));
        assertEquals("乙", list.get(1).get("name"));
    }

    /**
     * 名称缺失的条目被跳过（避免落库空名称场景）。
     */
    @Test
    void parseSkipsBlankName() {
        String json = """
                [{"name":"","location":"x"},{"name":"有效场景","location":"y"}]""";
        List<Map<String, Object>> list = SceneService.parseGenerated(json, 10);
        assertEquals(1, list.size());
        assertEquals("有效场景", list.get(0).get("name"));
    }

    /**
     * Markdown 代码块包裹的 JSON 数组可正常提取。
     */
    @Test
    void parseStripsMarkdownFence() {
        String json = """
                ```json
                [{"name":"酒馆","background":"暖绒市中心的常客聚集地"}]
                ```""";
        List<Map<String, Object>> list = SceneService.parseGenerated(json, 10);
        assertEquals(1, list.size());
        assertEquals("酒馆", list.get(0).get("name"));
        assertEquals("暖绒市中心的常客聚集地", list.get(0).get("background"));
    }

    /**
     * 非法输入兜底：null / 非数组 / 无 JSON 均返回空。
     */
    @Test
    void parseInvalidReturnsEmpty() {
        assertTrue(SceneService.parseGenerated(null, 3).isEmpty());
        assertTrue(SceneService.parseGenerated("{\"a\":1}", 3).isEmpty());
        assertTrue(SceneService.parseGenerated("没有 JSON 内容", 3).isEmpty());
        assertTrue(SceneService.parseGenerated("[{\"name\":\"甲\"}]", 0).isEmpty());
    }
}
