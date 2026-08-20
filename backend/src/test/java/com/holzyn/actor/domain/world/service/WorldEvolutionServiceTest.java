package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldEvolutionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldEvolutionService 逻辑单元测试（V2.1 世界演化）。
 * <p>职责：验证 AI 编排输出解析（只保留在场角色消息、空消息兜底、无 JSON 降级）
 * 与收尾判定（AI 收束/仅剩 1 人/轮次上限），保证演化不会无限持续。</p>
 */
class WorldEvolutionServiceTest {

    /**
     * 正常解析：只保留在场角色的消息，透传 sceneEvent/joins/leaves/shouldFinish/summary。
     */
    @Test
    void parsePlanKeepsActiveMessagesOnly() {
        String json = """
                {
                  "messages": [
                    { "characterId": 1, "type": "text", "content": "你好" },
                    { "characterId": 2, "type": "action", "content": "走到窗前" },
                    { "characterId": 99, "type": "text", "content": "不该出现（不在场）" }
                  ],
                  "sceneEvent": "窗外下起了雨",
                  "joins": [ { "characterId": 3, "reason": "路过听闻动静而加入" } ],
                  "leaves": [ { "characterId": 2, "reason": "想起有要事离开" } ],
                  "shouldFinish": false,
                  "summary": "两人简短交谈"
                }
                """;
        Map<String, Object> plan = WorldEvolutionService.parsePlan(json, List.of(1L, 2L));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) plan.get("messages");
        assertEquals(2, messages.size(), "不在场角色 99 的消息应被过滤");
        assertEquals(1L, ((Number) messages.get(0).get("characterId")).longValue());
        assertEquals("走到窗前", messages.get(1).get("content"));
        assertEquals("窗外下起了雨", plan.get("sceneEvent"));
        assertEquals(1, ((List<?>) plan.get("joins")).size());
        assertEquals(1, ((List<?>) plan.get("leaves")).size());
        assertFalse((Boolean) plan.get("shouldFinish"));
        assertEquals("两人简短交谈", plan.get("summary"));
    }

    /**
     * 空 messages 兜底：用首位在场角色补一句推进，避免演化停滞。
     */
    @Test
    void parsePlanFallsBackWhenNoMessages() {
        String json = "{ \"messages\": [], \"shouldFinish\": false }";
        Map<String, Object> plan = WorldEvolutionService.parsePlan(json, List.of(7L, 8L));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) plan.get("messages");
        assertEquals(1, messages.size());
        assertEquals(7L, ((Number) messages.get(0).get("characterId")).longValue());
    }

    /**
     * 无有效 JSON：返回 null（调用方走兜底推进）。
     */
    @Test
    void parsePlanReturnsNullOnInvalidJson() {
        assertNull(WorldEvolutionService.parsePlan("抱歉，我无法输出。", List.of(1L)));
        assertNull(WorldEvolutionService.parsePlan(null, List.of(1L)));
    }

    /**
     * 收尾判定：AI 判定收束 / 仅剩 1 人 / 达到轮次上限 → 收尾；否则不收尾。
     */
    @Test
    void shouldFinishRules() {
        assertTrue(WorldEvolutionService.shouldFinish(true, 3, 5, 20), "AI 判定收束应收尾");
        assertTrue(WorldEvolutionService.shouldFinish(false, 1, 5, 20), "仅剩 1 名角色应收尾");
        assertTrue(WorldEvolutionService.shouldFinish(false, 3, 20, 20), "达到轮次上限应收尾");
        assertFalse(WorldEvolutionService.shouldFinish(false, 3, 5, 20), "多人在场且未到上限不结束");
    }

    // ==================== vP5-7.9 群聊式连续演化：逐拍调度解析 ====================

    /**
     * 正常调度解析：characterId 在场、desire 收敛、beatType 收敛、场景变化/加入退场透传。
     */
    @Test
    void parseScheduleValid() {
        String json = """
                { "characterId": 59, "desire": 4, "reason": "她听到咖啡配方话题想接话", "beatType": "action",
                  "sceneEvent": "窗外下起了雨", "joins": [], "leaves": [ { "characterId": 60, "reason": "有要事离开" } ] }""";
        Map<String, Object> d = WorldEvolutionService.parseSchedule(json, List.of(59L, 60L));
        assertEquals(59L, ((Number) d.get("characterId")).longValue());
        assertEquals(4, d.get("desire"));
        assertEquals("action", d.get("beatType"));
        assertEquals("窗外下起了雨", d.get("sceneEvent"));
        assertEquals(1, ((List<?>) d.get("leaves")).size());
        assertEquals(0, ((List<?>) d.get("joins")).size());
    }

    /**
     * 调度解析收敛：desire 越界收敛 1~5；beatType 非法回退 text；不在场角色判定无效。
     */
    @Test
    void parseScheduleClampsAndValidates() {
        Map<String, Object> d = WorldEvolutionService.parseSchedule(
                "{ \"characterId\": 59, \"desire\": 99, \"beatType\": \"walk\" }", List.of(59L));
        assertEquals(5, d.get("desire"));
        assertEquals("text", d.get("beatType"));

        // 选中不在场角色 → 判定无效（返回 null）
        assertNull(WorldEvolutionService.parseSchedule(
                "{ \"characterId\": 99, \"desire\": 5 }", List.of(59L)));
    }

    /**
     * 调度解析兜底：无有效 JSON / null 返回 null（调用方走首位角色兜底）。
     */
    @Test
    void parseScheduleInvalidReturnsNull() {
        assertNull(WorldEvolutionService.parseSchedule("抱歉，无法调度。", List.of(1L)));
        assertNull(WorldEvolutionService.parseSchedule(null, List.of(1L)));
        assertNull(WorldEvolutionService.parseSchedule("{}", List.of(1L)));
    }
}
