package com.holzyn.actor.domain.growth.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N4 空间候选选择单测：决策输出映射器（SpaceChoiceMapper 纯静态）。
 *
 * <p>测试纪律（V1.3 第六层）：纯静态构造断言——新键（actionType/actionDetail/reasoning/
 * priority/expectedDurationMinutes/target）+ 旧键（type/action/reason/urgency/duration/params/
 * foresightCandidates）双写与 DecisionReActParse 输出同构；actionType 推断（continue/
 * move_to/start_action）；continue 走程序化兜底语义。</p>
 *
 * <p>所属模块：domain/growth/space（N 轨道 N4 程序化件）</p>
 */
class SpaceChoiceMapperTest {

    private static SpaceDecisionCandidates.SpaceCandidate cand(String key, String action, String target,
                                                               boolean generic) {
        return new SpaceDecisionCandidates.SpaceCandidate(key, action,
                action == null ? "" : action, target, null, null, generic);
    }

    @Test
    @DisplayName("null 选中候选 → null（调用方回退 Qwen 现状）")
    void nullChosen() {
        assertNull(SpaceChoiceMapper.toDecisionMap(null, "干活", List.of()));
    }

    @Test
    @DisplayName("continue_current → continue 程序化兜底语义（新键+旧键同构）")
    void continueCurrent() {
        Map<String, Object> d = SpaceChoiceMapper.toDecisionMap(
                cand(SpaceDecisionCandidates.KEY_CONTINUE, "继续当前正在做的事", null, true),
                "在田里锄地", List.of("继续当前正在做的事", "去喝水"));
        assertNotNull(d);
        assertEquals("continue", d.get("actionType"));
        assertTrue(d.containsKey("actionDetail"));
        assertTrue(d.containsKey("reasoning"));
        assertTrue(d.containsKey("priority"));
        assertTrue(d.containsKey("expectedDurationMinutes"));
        // 旧键兼容（continue 的旧 type 枚举=custom，与 DecisionReActParse 输出同构）
        assertEquals("custom", d.get("type"));
        assertEquals(d.get("actionDetail"), d.get("action"));
        assertEquals(d.get("reasoning"), d.get("reason"));
        assertEquals(d.get("priority"), d.get("urgency"));
        assertEquals(d.get("expectedDurationMinutes"), d.get("duration"));
        assertTrue(d.containsKey("params"));
    }

    @Test
    @DisplayName("空间候选带目标 → move_to + params.move.to 双写")
    void moveToWithTarget() {
        Map<String, Object> d = SpaceChoiceMapper.toDecisionMap(
                cand("space0", "去河边挑水", "河边", false), "在田里锄地", null);
        assertEquals("move_to", d.get("actionType"));
        assertEquals("去河边挑水", d.get("actionDetail"));
        assertEquals("河边", d.get("target"));
        // 旧 type 枚举映射（move_to → move，与 DecisionReActParse 输出同构）
        assertEquals("move", d.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) d.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> move = (Map<String, Object>) params.get("move");
        assertEquals("河边", move.get("to"));
    }

    @Test
    @DisplayName("无目标且无地点动词前缀 → start_action；前缀「去/回/上」→ move_to")
    void actionTypeInference() {
        assertEquals("start_action", SpaceChoiceMapper.toDecisionMap(
                cand("space0", "坐在门槛上发呆", null, false), null, null).get("actionType"));
        assertEquals("move_to", SpaceChoiceMapper.toDecisionMap(
                cand("space0", "回屋里歇着", null, false), null, null).get("actionType"));
        assertEquals("move_to", SpaceChoiceMapper.toDecisionMap(
                cand("space0", "上山砍柴", null, false), null, null).get("actionType"));
        assertEquals("move_to", SpaceChoiceMapper.inferActionType("去集市", ""));
    }

    @Test
    @DisplayName("rest/chat 优先级：rest→1、chat→2、空间候选→3")
    void priorityByType() {
        assertEquals(1, SpaceChoiceMapper.toDecisionMap(
                cand(SpaceDecisionCandidates.KEY_REST, "停下歇一歇，放空片刻", null, true),
                null, null).get("priority"));
        assertEquals(2, SpaceChoiceMapper.toDecisionMap(
                cand(SpaceDecisionCandidates.KEY_CHAT, "找人说说话", null, true),
                null, null).get("priority"));
        assertEquals(3, SpaceChoiceMapper.toDecisionMap(
                cand("space0", "去河边挑水", null, false), null, null).get("priority"));
    }

    @Test
    @DisplayName("foresightCandidates：considered 表原样写入（空 action 跳过）")
    void foresightCandidates() {
        Map<String, Object> d = SpaceChoiceMapper.toDecisionMap(
                cand("space0", "去河边挑水", null, false), null,
                java.util.Arrays.asList("去河边挑水", "  ", null, "坐在门槛上发呆"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cands = (List<Map<String, String>>) d.get("foresightCandidates");
        assertNotNull(cands);
        assertEquals(2, cands.size());
        assertFalse(cands.get(0).get("action").isBlank());
        assertEquals("去河边挑水", cands.get(0).get("action"));
    }
}
