package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DebugUsageStatsService 用量聚合单元测试（2026-08-20 调试前端用量图）。
 * <p>职责：验证——按天聚合（含 0 值补齐）、按场景聚合（中文标签 + 次数降序）、
 * 按项目聚合（token 降序）、总量汇总。</p>
 * <p>说明：Mockito mock 仓库返回固定数据，纯内存聚合断言。</p>
 */
class DebugUsageStatsServiceTest {

    private ActorUsageLog log(Long id, Long projectId, String scene, int tokenIn, int tokenOut,
                              int durationMs, LocalDateTime createdAt) {
        ActorUsageLog l = new ActorUsageLog();
        l.setId(id);
        l.setProjectId(projectId);
        l.setScene(scene);
        l.setTokenIn(tokenIn);
        l.setTokenOut(tokenOut);
        l.setDurationMs(durationMs);
        l.setCreatedAt(createdAt);
        return l;
    }

    /**
     * 聚合正确性：总量/按天（含 0 值补齐）/按场景/按项目。
     */
    @Test
    void aggregatesCorrectly() {
        ActorUsageLogRepository repo = Mockito.mock(ActorUsageLogRepository.class);
        // 用相对今天的数据保证落在近 30 天窗口内
        LocalDateTime day1 = LocalDate.now().minusDays(2).atTime(10, 0, 0);
        LocalDateTime day2 = LocalDate.now().minusDays(1).atTime(11, 30, 0);
        Mockito.when(repo.findByUserIdAndCreatedAtBetweenOrderByIdDesc(Mockito.anyLong(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(List.of(
                        log(1L, 100L, "dialog", 10, 20, 100, day1),
                        log(2L, 100L, "dialog", 30, 40, 200, day1),
                        log(3L, 101L, "card_gen", 5, 5, 50, day2),
                        log(4L, null, "action", 1, 1, 10, day2)
                ));

        DebugUsageStatsService service = new DebugUsageStatsService(repo);
        Map<String, Object> stats = service.stats(30);

        // 总量
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) stats.get("total");
        assertEquals(4L, total.get("count"));
        assertEquals(46L, total.get("tokenIn"));
        assertEquals(66L, total.get("tokenOut"));
        assertEquals(360L, total.get("durationMs"));

        // 按天：30 天列表（含 0 值补齐），逐日升序；前两天有数据，其余为 0
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDay = (List<Map<String, Object>>) stats.get("byDay");
        assertEquals(30, byDay.size(), "近 30 天应逐日补齐");
        assertEquals(LocalDate.now().minusDays(29).toString(), byDay.get(0).get("date"));
        // 找到有数据的两天
        Map<String, Object> day1Row = byDay.stream().filter(r -> r.get("date").equals(day1.toLocalDate().toString()))
                .findFirst().orElseThrow();
        assertEquals(2L, day1Row.get("count"));
        assertEquals(40L, day1Row.get("tokenIn"));
        Map<String, Object> day2Row = byDay.stream().filter(r -> r.get("date").equals(day2.toLocalDate().toString()))
                .findFirst().orElseThrow();
        assertEquals(2L, day2Row.get("count"));
        assertEquals(6L, day2Row.get("tokenIn"));
        // 存在 0 值补齐日
        assertTrue(byDay.stream().anyMatch(r -> Long.valueOf(0L).equals(r.get("count"))), "无数据日 count=0");

        // 按场景：中文标签 + 次数降序（dialog 2 次 > card_gen 1 次 = action 1 次）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byScene = (List<Map<String, Object>>) stats.get("byScene");
        assertEquals(3, byScene.size());
        assertEquals("dialog", byScene.get(0).get("scene"));
        assertEquals("对话", byScene.get(0).get("sceneName"));
        assertEquals(2L, byScene.get(0).get("count"));
        assertTrue(byScene.get(1).get("scene").equals("card_gen") || byScene.get(1).get("scene").equals("action"));

        // 按项目：tokenIn 降序（100 > 101 > 0(null)）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byProject = (List<Map<String, Object>>) stats.get("byProject");
        assertEquals(3, byProject.size());
        assertEquals(100L, byProject.get(0).get("projectId"));
        assertEquals(2L, byProject.get(0).get("count"));
        assertEquals(40L, byProject.get(0).get("tokenIn"));
        assertNotNull(byProject.get(0).get("tokenOut"));
    }

    /**
     * 边界：无数据时各维度返回空列表/0 总量，不抛异常。
     */
    @Test
    void emptyDataYieldsZeroes() {
        ActorUsageLogRepository repo = Mockito.mock(ActorUsageLogRepository.class);
        Mockito.when(repo.findByUserIdAndCreatedAtBetweenOrderByIdDesc(Mockito.anyLong(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(List.of());

        DebugUsageStatsService service = new DebugUsageStatsService(repo);
        Map<String, Object> stats = service.stats(7);
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) stats.get("total");
        assertEquals(0L, total.get("count"));
        assertEquals(0L, total.get("tokenIn"));
        assertEquals(7, ((List<?>) stats.get("byDay")).size());
        assertTrue(((List<?>) stats.get("byScene")).isEmpty());
        assertTrue(((List<?>) stats.get("byProject")).isEmpty());
    }
}
