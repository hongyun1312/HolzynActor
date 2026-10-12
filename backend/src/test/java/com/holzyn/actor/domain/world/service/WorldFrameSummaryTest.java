package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界帧摘要组装单元测试（世界演化与游戏时间系统 M6：场景化演化重构，domain/world/service，同包）。
 * <p>职责：验证「今日世界状态」紧凑摘要的组装——各段（天气/宏观事件/信息包/环境变化）齐全、
 * 条数上限截断、空段降级、总长预算截断、无帧/非法返回空串。</p>
 * <p>设计依据：《HolzynActor_世界演化与游戏时间系统_开发路径_V1.0》§4.5（输入世界帧作导演事实源）。</p>
 */
class WorldFrameSummaryTest {

    private WorldFrameParse.FrameJson sampleFrame() {
        Map<String, WorldFrameParse.WeatherEntry> weather = new LinkedHashMap<>();
        weather.put("青禾农田", new WorldFrameParse.WeatherEntry("晴", 15.0, "北风"));
        weather.put("茶馆", new WorldFrameParse.WeatherEntry("多云", null, null));
        List<WorldFrameParse.MacroEvent> events = List.of(
                new WorldFrameParse.MacroEvent("E-5-001", "灵脉异动", "枯井", 4, "枯井深夜传出异响", 5L, 3L, List.of("枯井")),
                new WorldFrameParse.MacroEvent("E-5-002", "渡口损毁", "渡口", 2, "渡口浮桥被冲断", 5L, 2L, List.of("渡口")));
        List<WorldFrameParse.InfoPackage> packets = List.of(new WorldFrameParse.InfoPackage(
                "I-5-001-1", "官府", "官府正寻一个背剑的年轻后生", 0.8, List.of("村民口耳"), 1.0, 8L,
                List.of("赏银被传成五十两"), null));
        List<WorldFrameParse.EnvChange> env = List.of(new WorldFrameParse.EnvChange(
                "村口", "村外小路雨后泥泞", "往来车马减少"));
        return new WorldFrameParse.FrameJson(5L, weather, events, packets, env, null, List.of());
    }

    @Test
    void buildAssemblesAllSections() {
        String text = WorldFrameSummary.build(sampleFrame());
        assertNotNull(text);
        assertTrue(text.contains("第5游戏日"), "含游戏日头");
        assertTrue(text.contains("天气"), "含天气段");
        assertTrue(text.contains("青禾农田 晴 15° 北风"), "天气条目含温度风向");
        assertTrue(text.contains("宏观事件"), "含宏观事件段");
        assertTrue(text.contains("枯井深夜传出异响（重要度4）"), "宏观事件含重要度");
        assertTrue(text.contains("传闻"), "含信息包传闻段");
        assertTrue(text.contains("官府正寻一个背剑的年轻后生"), "含信息包内容");
        assertTrue(text.contains("环境变化"), "含环境变化段");
        assertTrue(text.contains("村外小路雨后泥泞"), "含环境变化内容");
    }

    @Test
    void buildTruncatesWeatherRegions() {
        Map<String, WorldFrameParse.WeatherEntry> weather = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            weather.put("区域" + i, new WorldFrameParse.WeatherEntry("晴", null, null));
        }
        WorldFrameParse.FrameJson frame = new WorldFrameParse.FrameJson(1L, weather,
                List.of(), List.of(), List.of(), null, List.of());
        String text = WorldFrameSummary.build(frame);
        assertTrue(text.contains("区域0"), "首区域在");
        assertFalse(text.contains("区域9"), "超过 WEATHER_MAX=6 的区域被截断");
    }

    @Test
    void buildTruncatesMacroAndPacketsAndEnv() {
        List<WorldFrameParse.MacroEvent> events = List.of(
                new WorldFrameParse.MacroEvent("1", "t", "a", 2, "事件一", 1L, 1L, List.of()),
                new WorldFrameParse.MacroEvent("2", "t", "b", 2, "事件二", 1L, 1L, List.of()),
                new WorldFrameParse.MacroEvent("3", "t", "c", 2, "事件三", 1L, 1L, List.of()),
                new WorldFrameParse.MacroEvent("4", "t", "d", 2, "事件四", 1L, 1L, List.of()));
        List<WorldFrameParse.InfoPackage> packets = List.of(
                new WorldFrameParse.InfoPackage("1", "o", "消息一", null, List.of(), null, null, List.of(), null),
                new WorldFrameParse.InfoPackage("2", "o", "消息二", null, List.of(), null, null, List.of(), null),
                new WorldFrameParse.InfoPackage("3", "o", "消息三", null, List.of(), null, null, List.of(), null),
                new WorldFrameParse.InfoPackage("4", "o", "消息四", null, List.of(), null, null, List.of(), null));
        List<WorldFrameParse.EnvChange> env = List.of(
                new WorldFrameParse.EnvChange("a", "变化一", ""),
                new WorldFrameParse.EnvChange("b", "变化二", ""),
                new WorldFrameParse.EnvChange("c", "变化三", ""));
        WorldFrameParse.FrameJson frame = new WorldFrameParse.FrameJson(1L, new LinkedHashMap<>(),
                events, packets, env, null, List.of());
        String text = WorldFrameSummary.build(frame);
        assertTrue(text.contains("事件一"));
        assertFalse(text.contains("事件四"), "宏观事件 > MACRO_MAX=3 截断");
        assertTrue(text.contains("消息一"));
        assertFalse(text.contains("消息四"), "信息包 > PACKET_MAX=3 截断");
        assertTrue(text.contains("变化一"));
        assertFalse(text.contains("变化三"), "环境变化 > ENV_MAX=2 截断");
    }

    @Test
    void buildEmptyFrameReturnsEmpty() {
        assertEquals("", WorldFrameSummary.build(WorldFrameParse.FrameJson.empty(3L)), "空帧返回空串");
        assertEquals("", WorldFrameSummary.build((WorldFrameParse.FrameJson) null), "null 帧返回空串");
        assertEquals("", WorldFrameSummary.build((ActorWorldFrame) null), "null 实体返回空串");
    }

    @Test
    void buildFromEntityMergesJsonColumns() {
        // 模拟已持久化帧（四段 JSON 列分离）→ 摘要组装成功（列合并解析）
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setGameDay(5L);
        frame.setWeatherJson("{\"青禾农田\": {\"condition\": \"晴\", \"temperature\": 15, \"wind\": \"北风\"}}");
        frame.setMacroEventsJson("[{\"event_id\":\"E-5-001\",\"type\":\"灵脉异动\",\"location\":\"枯井\","
                + "\"severity\":4,\"summary\":\"枯井深夜传出异响\",\"start_day\":5,\"duration_days\":3}]");
        frame.setInfoPacketsJson("[{\"info_id\":\"I-5-001-1\",\"origin\":\"官府\",\"content\":\"寻人告示\"}]");
        frame.setEnvironmentChangesJson("[{\"scene\":\"村口\",\"change\":\"雨后泥泞\"}]");
        String text = WorldFrameSummary.build(frame);
        assertTrue(text.contains("青禾农田 晴 15° 北风"));
        assertTrue(text.contains("枯井深夜传出异响"));
        assertTrue(text.contains("寻人告示"));
        assertTrue(text.contains("雨后泥泞"));
    }

    @Test
    void buildFromEntityWithBrokenJsonDegrades() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setGameDay(2L);
        frame.setWeatherJson("不是JSON");
        frame.setMacroEventsJson(null);
        frame.setInfoPacketsJson("[]");
        frame.setEnvironmentChangesJson(null);
        String text = WorldFrameSummary.build(frame);
        assertEquals("", text, "各段均无效 → 空串（降级不抛异常）");
    }

    @Test
    void totalBudgetTruncates() {
        // 大量内容超出 400 字符预算 → 截断到预算长度（不抛异常）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是一段很长很长的天气描述内容用于撑满预算。");
        }
        List<WorldFrameParse.MacroEvent> events = List.of(new WorldFrameParse.MacroEvent(
                "1", "t", "a", 3, sb.toString(), 1L, 1L, List.of()));
        WorldFrameParse.FrameJson frame = new WorldFrameParse.FrameJson(1L, new LinkedHashMap<>(),
                events, List.of(), List.of(), null, List.of());
        String text = WorldFrameSummary.build(frame);
        assertTrue(text.length() <= WorldFrameSummary.TOTAL_BUDGET, "总长截断到预算内");
    }
}
