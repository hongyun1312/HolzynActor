package com.holzyn.actor.domain.action.service;

import com.holzyn.actor.domain.action.service.ActionEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ActionEngine 决策逻辑单元测试（P2 阶段二）。
 * <p>职责：验证 action_decision Schema 校验（必填字段 + type 枚举）与程序化兜底决策生成。</p>
 */
class ActionEngineDecisionTest {

    /** Jackson 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 合法决策通过校验。
     */
    @Test
    void validateValidDecision() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "move");
        node.put("action", "前往市场购买药材");
        node.put("target", "城东市场");
        node.put("reason", "药材告急");
        node.put("urgency", 4);
        Map<String, Object> d = ActionEngine.validateDecision(node, objectMapper);
        assertNotNull(d);
        assertEquals("move", d.get("type"));
        assertEquals(4, d.get("urgency"));
    }

    /**
     * 缺失必填字段（action）的决策校验失败。
     */
    @Test
    void validateMissingFieldFails() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "move");
        node.put("target", "城东市场");
        node.put("reason", "药材告急");
        assertNull(ActionEngine.validateDecision(node, objectMapper));
    }

    /**
     * 非法 type 枚举的决策校验失败。
     */
    @Test
    void validateInvalidTypeFails() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "teleport");
        node.put("action", "瞬移");
        node.put("target", "市场");
        node.put("reason", "测试");
        assertNull(ActionEngine.validateDecision(node, objectMapper));
    }

    /**
     * 程序化兜底：生成 schedule 类型合理决策，urgency 按重要度钳制。
     */
    @Test
    void programmaticFallbackProducesReasonableDecision() {
        Map<String, Object> d = ActionEngine.programmaticFallback("张三", 5, "清晨巡视领地");
        assertEquals("schedule", d.get("type"));
        assertTrue(String.valueOf(d.get("action")).contains("张三"));
        assertTrue(String.valueOf(d.get("action")).contains("清晨巡视领地"));
        assertEquals(5, d.get("urgency"));
        assertNotNull(d.get("reason"));
    }

    /**
     * 程序化兜底：重要度为空时 urgency 取默认 3。
     */
    @Test
    void programmaticFallbackClampsUrgency() {
        Map<String, Object> d = ActionEngine.programmaticFallback("李四", null, null);
        assertEquals(3, d.get("urgency"));
        assertTrue(String.valueOf(d.get("action")).contains("日常活动"));
    }
}
