package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编年史提炼输出解析单元测试（记忆系统重构 M2，2026-09-12，纯逻辑）。
 * <p>职责：验证 chronicle_distill 输出解析——entries 数组/对象包装/title 缺省回退/
 * importance 钳制/event_ids 与 involved_* 数组读取/非法条目丢弃。</p>
 */
class ChronicleDistillParseTest {

    /**
     * 标准对象解析：entries 数组逐条提取。
     */
    @Test
    void parsesEntriesArray() {
        String ai = "{\"entries\":["
                + "{\"title\":\"村口古树遭雷击\",\"content\":\"村口的老槐树被雷劈断了大半\","
                + "\"importance\":4,\"event_ids\":[1234,1235],"
                + "\"involved_entities\":[\"location:村口牌坊\"],"
                + "\"involved_characters\":[\"special:110\"]}"
                + "]}";
        List<ChronicleDistillParse.ChronicleEntry> out = ChronicleDistillParse.parse(ai);
        assertEquals(1, out.size());
        ChronicleDistillParse.ChronicleEntry e = out.get(0);
        assertEquals("村口古树遭雷击", e.title());
        assertEquals("村口的老槐树被雷劈断了大半", e.content());
        assertEquals(4, e.importance());
        assertEquals(List.of(1234L, 1235L), e.eventIds());
        assertEquals(List.of("location:村口牌坊"), e.involvedEntities());
        assertEquals(List.of("special:110"), e.involvedCharacters());
    }

    /**
     * 顶层数组兼容（模型偶尔直接输出数组）。
     */
    @Test
    void parsesTopLevelArray() {
        String ai = "[{\"title\":\"t\",\"content\":\"c\"}]";
        List<ChronicleDistillParse.ChronicleEntry> out = ChronicleDistillParse.parse(ai);
        assertEquals(1, out.size());
    }

    /**
     * title 缺省：取 content 前 40 字；content 空白条目丢弃。
     */
    @Test
    void fallsBackTitleAndDropsBlank() {
        String ai = "{\"entries\":[{\"content\":\"这是一条很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的内容\",\"importance\":3},"
                + "{\"title\":\"\",\"content\":\"  \"}]}";
        List<ChronicleDistillParse.ChronicleEntry> out = ChronicleDistillParse.parse(ai);
        assertEquals(1, out.size());
        assertTrue(out.get(0).title().length() <= 41, "title 应回退 content 截断");
        assertEquals(3, out.get(0).importance());
    }

    /**
     * importance 越界钳制 1~5；空数组/非法输入返回空列表。
     */
    @Test
    void clampsImportanceAndHandlesInvalid() {
        String ai = "{\"entries\":[{\"title\":\"t\",\"content\":\"c\",\"importance\":9},"
                + "{\"title\":\"t2\",\"content\":\"c2\",\"importance\":0}]}";
        List<ChronicleDistillParse.ChronicleEntry> out = ChronicleDistillParse.parse(ai);
        assertEquals(2, out.size());
        assertEquals(5, out.get(0).importance());
        assertEquals(1, out.get(1).importance());
        assertTrue(ChronicleDistillParse.parse(null).isEmpty());
        assertTrue(ChronicleDistillParse.parse("{\"entries\":[]}").isEmpty());
        assertTrue(ChronicleDistillParse.parse("不是 JSON").isEmpty());
    }
}
