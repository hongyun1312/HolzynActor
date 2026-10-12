package com.holzyn.actor.domain.growth.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 此刻可发生空间四段式解析单元测试（O6 轨道 G2，纯逻辑）。
 * <p>职责：标准 JSON 解析（inner/possible_states/possible_actions/forbidden）、
 * Markdown 代码块容错、数值权重钳制 0-1、非法/空输出返回 null、toJson 往返。</p>
 */
class GrowthSpaceParseTest {

    private static final String STANDARD = """
            {
              "inner": { "pad": {"pleasure": 0.3}, "dominant_thought": "想去村口看看", "body_feel": "有些乏" },
              "possible_states": [ { "state": "坐在门槛上发呆", "likelihood": 0.5, "trigger": "无事可做" } ],
              "possible_actions": [ { "action": "去村口转悠", "likelihood": 0.7, "why": "散散心" } ],
              "forbidden": [ { "what": "远行", "reason": "耄耋之年不宜远行" } ]
            }
            """;

    @Test
    @DisplayName("标准 JSON 四段式解析")
    void parseStandard() {
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse(STANDARD);
        assertNotNull(doc);
        assertNotNull(doc.inner());
        assertEquals("想去村口看看", doc.inner().dominantThought());
        assertEquals("有些乏", doc.inner().bodyFeel());
        assertNotNull(doc.inner().pad());
        assertEquals("0.3", doc.inner().pad().get("pleasure"));
        assertEquals(1, doc.possibleStates().size());
        assertEquals("坐在门槛上发呆", doc.possibleStates().get(0).state());
        assertEquals(1, doc.possibleActions().size());
        assertEquals("去村口转悠", doc.possibleActions().get(0).action());
        assertEquals(1, doc.forbidden().size());
        assertEquals("远行", doc.forbidden().get(0).what());
        assertTrue(doc.hasContent());
    }

    @Test
    @DisplayName("Markdown 代码块容错")
    void parseMarkdownBlock() {
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse("```json\n" + STANDARD + "\n```");
        assertNotNull(doc);
        assertEquals("去村口转悠", doc.possibleActions().get(0).action());
    }

    @Test
    @DisplayName("无候选且无禁止 → null（无意义输出）")
    void parseEmptyContentIsNull() {
        assertNull(GrowthSpaceParse.parse("{\"inner\": {}}"));
        assertNull(GrowthSpaceParse.parse("{}"));
        assertNull(GrowthSpaceParse.parse(null));
        assertNull(GrowthSpaceParse.parse(""));
        assertNull(GrowthSpaceParse.parse("不是 JSON"));
    }

    @Test
    @DisplayName("权重越界钳制 0-1")
    void weightClamped() {
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse("""
                {
                  "possible_states": [ { "state": "发呆", "likelihood": 9.9 } ],
                  "possible_actions": [ { "action": "走走", "likelihood": -5 } ],
                  "forbidden": [ { "what": "远行", "reason": "年迈" } ]
                }
                """);
        assertNotNull(doc);
        assertEquals(1.0, doc.possibleStates().get(0).weight(), 1e-9);
        assertEquals(0.0, doc.possibleActions().get(0).weight(), 1e-9);
    }

    @Test
    @DisplayName("toJson 往返（落库空间 JSON 可再解析）")
    void toJsonRoundTrip() {
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse(STANDARD);
        String json = doc.toJson();
        GrowthSpaceParse.GrowthSpaceDoc again = GrowthSpaceParse.parse(json);
        assertNotNull(again);
        assertEquals(doc.possibleActions().size(), again.possibleActions().size());
        assertEquals("去村口转悠", again.possibleActions().get(0).action());
        assertEquals("远行", again.forbidden().get(0).what());
    }

    @Test
    @DisplayName("空文档工厂与 hasContent")
    void emptyDoc() {
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.GrowthSpaceDoc.empty();
        assertFalse(doc.hasContent());
        assertNotNull(doc.possibleActions());
        assertNotNull(doc.forbidden());
    }
}
