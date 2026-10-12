package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体状态更新判定输出解析单元测试（记忆系统重构 M3，2026-09-12，纯逻辑）。
 * <p>职责：验证 entity_state_update 输出解析——changed 判定/current_state 提取/
 * changed=true 但状态空白按未变化保守处理/entity_key 空白丢弃。</p>
 */
class EntityStateUpdateParseTest {

    /**
     * 标准对象解析：changed=true/false 判定 + 新状态提取。
     */
    @Test
    void parsesUpdatesArray() {
        String ai = "{\"updates\":["
                + "{\"entity_key\":\"location:村口牌坊\",\"changed\":true,"
                + "\"current_state\":\"古槐被雷劈断，村口搭起了临时木棚\",\"recent_change\":\"古槐遭雷击\"},"
                + "{\"entity_key\":\"person:special:110\",\"changed\":false}"
                + "]}";
        List<EntityStateUpdateParse.EntityUpdate> out = EntityStateUpdateParse.parse(ai);
        assertEquals(2, out.size());
        assertTrue(out.get(0).changed());
        assertEquals("古槐被雷劈断，村口搭起了临时木棚", out.get(0).currentState());
        assertEquals("古槐遭雷击", out.get(0).recentChange());
        assertFalse(out.get(1).changed());
        assertNull(out.get(1).currentState());
    }

    /**
     * changed=true 但 current_state 空白：保守按未变化处理（防 AI 异常输出污染实体表）。
     */
    @Test
    void changedTrueWithoutStateTreatsAsUnchanged() {
        String ai = "{\"updates\":[{\"entity_key\":\"location:x\",\"changed\":true,\"current_state\":\"  \"}]}";
        List<EntityStateUpdateParse.EntityUpdate> out = EntityStateUpdateParse.parse(ai);
        assertEquals(1, out.size());
        assertFalse(out.get(0).changed(), "无新状态的 changed=true 应按未变化处理");
    }

    /**
     * entity_key 空白条目丢弃；非法输入返回空列表。
     */
    @Test
    void dropsBlankKeysAndHandlesInvalid() {
        String ai = "{\"updates\":[{\"entity_key\":\"  \",\"changed\":true,\"current_state\":\"x\"}]}";
        assertTrue(EntityStateUpdateParse.parse(ai).isEmpty());
        assertTrue(EntityStateUpdateParse.parse(null).isEmpty());
        assertTrue(EntityStateUpdateParse.parse("不是 JSON").isEmpty());
        assertTrue(EntityStateUpdateParse.parse("{\"updates\":[]}").isEmpty());
    }
}
