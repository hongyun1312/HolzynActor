package com.holzyn.actor.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行动文本收口单元测试（{@code com.holzyn.actor.common}）。
 *
 * <p>职责：锁定三件事——① 模型把内层契约套成字符串时能被解包成人话；
 * ② 解不出人话时返回空串而不是原始 JSON（宁可为空，也不把 JSON 当人类语言落库）；
 * ③ 系统模板句（觅食充饥 / 稍候片刻）可识别，供记忆层与行动层共用同一份黑名单。</p>
 */
class ActionTextsTest {

    // ==================== 解包 ====================

    /** 裸 JSON 包一层 action：抽出内层动作，而不是把整段 JSON 当动作。 */
    @Test
    void unwrapExtractsActionFromJson() {
        String raw = "{ \"action\": \"傍晚，老支书从桥头慢慢走回家\" }";
        assertEquals("傍晚，老支书从桥头慢慢走回家", ActionTexts.unwrap(raw));
    }

    /** 双层套娃（action 的值本身又是 JSON）：再解一层。 */
    @Test
    void unwrapHandlesNestedJsonString() {
        String inner = "{\\\"action\\\":\\\"去河边挑水\\\"}";
        String raw = "{\"action\": \"" + inner + "\"}";
        assertEquals("去河边挑水", ActionTexts.unwrap(raw));
    }

    /** 键名按语义优先：action_detail 先于 type 命中。 */
    @Test
    void unwrapPrefersActionDetailOverType() {
        String raw = "{\"type\":\"move\",\"action_detail\":\"去米行讨碗水喝\"}";
        assertEquals("去米行讨碗水喝", ActionTexts.unwrap(raw));
    }

    /** 抽不到任何字符串（数组/纯结构体）：返回空串，绝不回灌原始 JSON。 */
    @Test
    void unwrapReturnsEmptyWhenNothingExtractable() {
        assertEquals("", ActionTexts.unwrap("[1, 2, 3]"));
        assertEquals("", ActionTexts.unwrap("{\"foo\": 1}"));
    }

    /** 非 JSON：压空白后原样返回；null 保持 null（与既有台账口径一致）。 */
    @Test
    void unwrapKeepsPlainTextAndNull() {
        assertEquals("去集市 买米", ActionTexts.unwrap("去集市   买米"));
        assertNull(ActionTexts.unwrap(null));
        assertEquals("", ActionTexts.unwrap("   "));
    }

    /** 句首不是 JSON 的文本（含中文引号/冒号）不受影响。 */
    @Test
    void unwrapDoesNotTouchNaturalText() {
        String text = "他说：「今日风大，早些回家」";
        assertEquals(text, ActionTexts.unwrap(text));
    }

    /** looksLikeJson：只认 { 或 [ 开头（允许前导空白）。 */
    @Test
    void looksLikeJsonOnlyForStructuredText() {
        assertTrue(ActionTexts.looksLikeJson(" {\"a\":1}"));
        assertTrue(ActionTexts.looksLikeJson("[1]"));
        assertFalse(ActionTexts.looksLikeJson("去集市"));
        assertFalse(ActionTexts.looksLikeJson(null));
        assertFalse(ActionTexts.looksLikeJson(""));
    }

    // ==================== 模板句 ====================

    /** 生理兜底「觅食充饥」= 模板句（不同上下文都算）。 */
    @Test
    void routineTemplateDetectsForageFallback() {
        assertTrue(ActionTexts.isRoutineTemplate("觅食充饥"));
        assertTrue(ActionTexts.isRoutineTemplate("当前行动：觅食充饥"));
    }

    /** 占位改写「…稍候片刻，稍后再去」= 模板句。 */
    @Test
    void routineTemplateDetectsWaitingPlaceholder() {
        assertTrue(ActionTexts.isRoutineTemplate("我见河神庙正有人使用，先在附近稍候片刻，稍后再去"));
        assertTrue(ActionTexts.isRoutineTemplate("陈阿伯在米行附近稍候片刻，稍后再去"));
    }

    /** 正常行动文本不误伤。 */
    @Test
    void routineTemplateLeavesRealActionsAlone() {
        assertFalse(ActionTexts.isRoutineTemplate("去河边挑两桶水回家"));
        assertFalse(ActionTexts.isRoutineTemplate("在集市上问了问粮价"));
        assertFalse(ActionTexts.isRoutineTemplate(null));
        assertFalse(ActionTexts.isRoutineTemplate("  "));
    }
}
