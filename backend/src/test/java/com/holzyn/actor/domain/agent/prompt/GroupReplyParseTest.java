package com.holzyn.actor.domain.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊逐角色回复 JSON 契约解析器（GroupReplyParse）单元测试。
 * <p>2026-10 长跑实测：本地模型偶发以 <b>{@code dialog}</b> 键（而非契约规定的
 * {@code dialogue}）输出回复正文——旧实现只认 dialogue，导致<b>有效回复被当作空回复丢弃</b>，
 * 消息落成 failed/null（前端「只有角色名没有内容」）+ 整轮被截断。本用例锁定两键容错。</p>
 */
class GroupReplyParseTest {

    @Test
    @DisplayName("标准契约键 dialogue：正常解析（既有行为回归锁定）")
    void parsesDialogueKey() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse(
                "{\"dialogue\":\"今晚的月色真不错\",\"action\":\"抬头看天\","
                        + "\"emotion_update\":{\"emotion\":\"平静\",\"intensity\":2},\"internal_thought\":\"想回家\"}");
        assertNotNull(r);
        assertEquals("今晚的月色真不错", r.dialogue());
        assertEquals("抬头看天", r.action());
        assertEquals("平静", r.emotion());
        assertEquals(2, r.emotionIntensity());
        assertEquals("想回家", r.internalThought());
        assertTrue(r.compliant());
    }

    @Test
    @DisplayName("模型偶发输出 dialog 键（非 dialogue）→ 仍解析出正文（2026-10 容错核心）")
    void acceptsDialogKeySynonym() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse(
                "{\"dialog\":\"沈村长，这风里似有剑气，竹叶声也乱了半拍。\",\"action\":\"指尖轻叩桌面\"}");
        assertNotNull(r);
        assertEquals("沈村长，这风里似有剑气，竹叶声也乱了半拍。", r.dialogue());
        assertEquals("指尖轻叩桌面", r.action());
        assertTrue(r.compliant(), "dialog 键正文有效 → 视为合规（不再丢弃有效回复）");
    }

    @Test
    @DisplayName("同时存在 dialogue 与 dialog → 优先取 dialogue")
    void prefersDialogueOverDialog() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse(
                "{\"dialogue\":\"正式契约正文\",\"dialog\":\"备用键正文\"}");
        assertNotNull(r);
        assertEquals("正式契约正文", r.dialogue());
        assertTrue(r.compliant());
    }

    @Test
    @DisplayName("dialogue 与 dialog 均空白 → 判不合规（调用方按空回复防护清理占位）")
    void blankBothKeysIsNonCompliant() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse("{\"dialogue\":\"  \",\"dialog\":\"\"}");
        assertNotNull(r);
        assertNull(r.dialogue());
        assertFalse(r.compliant());
    }

    @Test
    @DisplayName("两键都缺失 → 判不合规")
    void missingBothKeysIsNonCompliant() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse("{\"action\":\"挥手\"}");
        assertNotNull(r);
        assertNull(r.dialogue());
        assertFalse(r.compliant());
    }

    @Test
    @DisplayName("非 JSON / 空串 → 返回 null")
    void invalidOrEmptyReturnsNull() {
        assertNull(GroupReplyParse.parse("不是JSON"));
        assertNull(GroupReplyParse.parse(null));
        assertNull(GroupReplyParse.parse("  "));
    }
}
