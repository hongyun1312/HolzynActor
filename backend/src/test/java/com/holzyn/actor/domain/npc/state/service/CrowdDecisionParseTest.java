package com.holzyn.actor.domain.npc.state.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 人群行动 AI 决策解析器单元测试（NPC 状态模块 M3，纯逻辑）。
 * <p>职责：验证——标准 JSON 解析（action/actionType/target/reason）、Markdown 代码块容错、
 * actionType 枚举校验（非法回退空串）、target/reason 空回退 null、action 空返回 null、非法输入 null。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§8.2（人群 AI 建议式决策）。</p>
 */
class CrowdDecisionParseTest {

    @Test
    void parse_standardJson() {
        var act = CrowdDecisionParse.parse(
                "{\"action\": \"前往王都酒馆用餐\", \"actionType\": \"eat\", \"target\": \"王都酒馆\", \"reason\": \"有些饿了\"}");
        assertNotNull(act);
        assertEquals("前往王都酒馆用餐", act.action());
        assertEquals("eat", act.actionType());
        assertEquals("王都酒馆", act.target());
        assertEquals("有些饿了", act.reason());
    }

    @Test
    void parse_markdownCodeBlock() {
        var act = CrowdDecisionParse.parse(
                "```json\n{\"action\": \"就地歇息\", \"actionType\": \"rest\", \"target\": null, \"reason\": \"疲惫\"}\n```");
        assertNotNull(act);
        assertEquals("就地歇息", act.action());
        assertEquals("rest", act.actionType());
        assertNull(act.target(), "target null → 回退 null");
    }

    @Test
    void parse_invalidActionTypeFallsBackBlank() {
        var act = CrowdDecisionParse.parse(
                "{\"action\": \"去集市逛逛\", \"actionType\": \"WANDER\", \"target\": \"城东市场\"}");
        assertNotNull(act);
        assertEquals("", act.actionType(), "非法枚举回退空串（上层按动作文本关键词恢复需求）");
        assertNotNull(act.target());
    }

    @Test
    void parse_emptyTargetAndReasonBecomeNull() {
        var act = CrowdDecisionParse.parse(
                "{\"action\": \"寻水饮用\", \"actionType\": \"drink\", \"target\": \"\", \"reason\": \"\"}");
        assertNotNull(act);
        assertNull(act.target());
        assertNull(act.reason());
    }

    @Test
    void parse_blankActionReturnsNull() {
        assertNull(CrowdDecisionParse.parse("{\"action\": \"\", \"actionType\": \"idle\"}"));
    }

    @Test
    void parse_invalidInputsReturnNull() {
        assertNull(CrowdDecisionParse.parse(null));
        assertNull(CrowdDecisionParse.parse(""));
        assertNull(CrowdDecisionParse.parse("  不是 JSON  "));
        assertNull(CrowdDecisionParse.parse("[1,2,3]"));
    }
}
