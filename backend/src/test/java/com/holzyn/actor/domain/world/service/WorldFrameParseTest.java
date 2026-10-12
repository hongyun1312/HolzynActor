package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldFrameParse.FrameJson;
import com.holzyn.actor.domain.world.service.WorldFrameParse.InfoPackage;
import com.holzyn.actor.domain.world.service.WorldFrameParse.MacroEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界帧 AI 输出解析单元测试（世界演化与游戏时间系统 M4，纯静态）。
 * <p>职责：验证——完整 JSON 解析（天气/宏观事件/信息包/环境变化/前日摘要）；字段缺失/非数组容错
 * （空值兜底不抛异常）；摘要缺失的宏观事件丢弃；origin/content 缺失的信息包丢弃；非法 JSON 返回空帧。</p>
 */
class WorldFrameParseTest {

    /** 完整合法帧 JSON（对齐主开发方案 §4.3 示例）。 */
    private static final String FULL_JSON = """
            {
              "gameDay": 12,
              "weather": { "青禾村": { "condition": "阴", "temperature": 15, "wind": "北风" }, "临水镇": { "condition": "小雨" } },
              "macro_events": [
                { "event_id": "E-12-001", "type": "粮食短缺", "location": "临水镇", "severity": 3,
                  "summary": "临水镇周边粮食减产，粮价开始上涨。", "start_day": 12, "duration_days": 5,
                  "affected_area": ["临水镇", "临水镇周边"] }
              ],
              "info_packages": [
                { "info_id": "I-12-001-1", "origin": "临水镇", "content": "临水镇粮价上涨，有人开始囤粮。",
                  "reliability": 0.8, "spread_channels": ["商队"], "spread_speed_days_per_km": 0.5,
                  "valid_until_day": 20, "distortion_rules": ["距离越远越夸大"],
                  "visibility_condition": { "profession": ["商人"], "location": ["青禾村市集"] } }
              ],
              "environment_changes": [
                { "scene": "青禾村", "change": "村外小路因雨泥泞", "effect": "贸易车辆减少" }
              ]
            }
            """;

    @Test
    void parseFullFrame() {
        FrameJson f = WorldFrameParse.parse(FULL_JSON, 12L);
        assertNotNull(f);
        assertEquals(12L, f.gameDay());
        // 天气：两个区域
        assertEquals(2, f.weather().size());
        assertEquals("阴", f.weather().get("青禾村").condition());
        assertEquals(Double.valueOf(15.0), f.weather().get("青禾村").temperature());
        assertEquals("北风", f.weather().get("青禾村").wind());
        assertEquals("小雨", f.weather().get("临水镇").condition());
        assertNull(f.weather().get("临水镇").temperature(), "缺 temperature 应容错为 null");
        // 宏观事件
        assertEquals(1, f.macroEvents().size());
        MacroEvent e = f.macroEvents().get(0);
        assertEquals("E-12-001", e.eventId());
        assertEquals("粮食短缺", e.type());
        assertEquals(3, e.severity());
        assertEquals(2, e.affectedArea().size());
        // 信息包
        assertEquals(1, f.infoPackages().size());
        InfoPackage p = f.infoPackages().get(0);
        assertEquals("临水镇", p.origin());
        assertEquals(Double.valueOf(0.8), p.reliability());
        assertEquals(List.of("商队"), p.spreadChannels());
        assertEquals(Double.valueOf(0.5), p.spreadSpeedDaysPerKm());
        assertEquals(List.of("距离越远越夸大"), p.distortionRules());
        assertNotNull(p.visibilityCondition());
        assertEquals(List.of("商人"), p.visibilityCondition().profession());
        // 环境变化
        assertEquals(1, f.environmentChanges().size());
        assertEquals("青禾村", f.environmentChanges().get(0).scene());
    }

    @Test
    void parseIgnoresMarkdownCodeBlock() {
        FrameJson f = WorldFrameParse.parse("```json\n" + FULL_JSON + "\n```", 12L);
        assertEquals(1, f.macroEvents().size(), "代码块包裹应被剥离");
    }

    @Test
    void parseDropsMacroEventWithoutSummary() {
        String json = """
                { "macro_events": [
                    { "event_id": "E-1", "type": "无摘要事件", "location": "某地", "severity": 3 },
                    { "event_id": "E-2", "type": "正常事件", "location": "某地", "severity": 2, "summary": "正常摘要" }
                  ] }
                """;
        FrameJson f = WorldFrameParse.parse(json, 1L);
        assertEquals(1, f.macroEvents().size(), "无摘要宏观事件应丢弃");
        assertEquals("E-2", f.macroEvents().get(0).eventId());
    }

    @Test
    void parseDropsPacketWithoutOriginOrContent() {
        String json = """
                { "info_packages": [
                    { "info_id": "I-1", "content": "无 origin 的包" },
                    { "info_id": "I-2", "origin": "某地" },
                    { "info_id": "I-3", "origin": "某地", "content": "完整信息包" }
                  ] }
                """;
        FrameJson f = WorldFrameParse.parse(json, 1L);
        assertEquals(1, f.infoPackages().size(), "缺 origin/content 的信息包应丢弃");
        assertEquals("I-3", f.infoPackages().get(0).infoId());
    }

    @Test
    void parseInvalidJsonReturnsEmptyFrame() {
        FrameJson f = WorldFrameParse.parse("这不是 JSON", 3L);
        assertNotNull(f);
        assertEquals(3L, f.gameDay());
        assertTrue(f.weather().isEmpty());
        assertTrue(f.macroEvents().isEmpty());
        assertTrue(f.infoPackages().isEmpty());
    }

    @Test
    void parseNullReturnsEmptyFrame() {
        FrameJson f = WorldFrameParse.parse(null, 5L);
        assertNotNull(f);
        assertTrue(f.macroEvents().isEmpty());
    }
}
