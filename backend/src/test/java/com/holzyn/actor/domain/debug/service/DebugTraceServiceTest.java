package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DebugTraceService（D5 F19 决策链路可视化 · trace 关联查询）单元测试（与实现同包，domain/debug/service）。
 * <p>职责：验证——按 trace_id 聚合链路（一次决策一条链路：同 trace_id 多行合并/汇总 token 与耗时/
 * 状态取最差）、trace_id 为空行各自成独立链路、单链路详情（id 升序 + detail_json）、
 * 每角色最近 N 次决策轨迹（取 N 条、按链路最新时间倒序）、行级筛选（projectId/characterId）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库）；findAll(spec, pageable) 返回构造的分页行。</p>
 */
class DebugTraceServiceTest {

    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorProjectRepository projectRepository;
    private ActorCharacterRepository characterRepository;
    private DebugTraceService service;

    @BeforeEach
    void setUp() {
        aiCallLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        service = new DebugTraceService(aiCallLogRepository, projectRepository, characterRepository);
    }

    /** 构造 AI 调用环节行 */
    private static ActorAiCallLog call(Long id, String traceId, String stepType, int attempt, String status,
                                       int tokenIn, int tokenOut, int durationMs, Long projectId,
                                       Long characterId, String scene, LocalDateTime createdAt) {
        ActorAiCallLog c = new ActorAiCallLog();
        c.setId(id);
        c.setTraceId(traceId);
        c.setStepType(stepType);
        c.setAttempt(attempt);
        c.setStatus(status);
        c.setTokenIn(tokenIn);
        c.setTokenOut(tokenOut);
        c.setDurationMs(durationMs);
        c.setProjectId(projectId);
        c.setCharacterId(characterId);
        c.setScene(scene);
        c.setCreatedAt(createdAt);
        return c;
    }

    /** 构造项目名解析 */
    private static ActorProject project(Long id, String name) {
        ActorProject p = new ActorProject();
        p.setId(id);
        p.setName(name);
        return p;
    }

    /** 构造角色名解析 */
    private static ActorCharacter character(Long id, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        return c;
    }

    /**
     * 链路聚合：同 trace_id 多行合并为一条链路——环节数/token 汇总/耗时汇总/状态取最差
     * （degraded 覆盖 success，failed 覆盖 degraded）。
     */
    @Test
    void traceListGroupsSameTraceIntoOneChain() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 10, 0, 0);
        List<ActorAiCallLog> rows = List.of(
                // id 倒序传入（findAll 按 id desc）
                call(5L, "trace-1", "event", 1, "success", 0, 0, 5, 1L, 2L, "action", t0.plusSeconds(30)),
                call(4L, "trace-1", "action", 1, "success", 0, 0, 10, 1L, 2L, "action", t0.plusSeconds(25)),
                call(3L, "trace-1", "ai_call", 2, "degraded", 30, 10, 300, 1L, 2L, "action", t0.plusSeconds(20)),
                call(2L, "trace-1", "ai_call", 1, "success", 60, 20, 200, 1L, 2L, "action", t0.plusSeconds(10)),
                call(1L, "trace-1", "perception", 1, "success", 0, 0, 3, 1L, 2L, "action", t0)
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, "测试项目")));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(character(2L, "林晓")));

        Map<String, Object> r = service.traceList(null, null, null, null, null, null, null, 1, 20);
        List<?> items = (List<?>) r.get("items");
        assertEquals(1, items.size(), "同 trace_id 应合并为一条链路");
        @SuppressWarnings("unchecked")
        Map<String, Object> chain = (Map<String, Object>) items.get(0);
        assertEquals("trace-1", chain.get("traceId"));
        assertEquals(5, chain.get("stepCount"));
        assertEquals("degraded", chain.get("status")); // 组内最差（failed>degraded>success）
        assertEquals(90, chain.get("tokenIn"));
        assertEquals(30, chain.get("tokenOut"));
        assertEquals(518, chain.get("durationMs"));
        assertEquals("测试项目", chain.get("projectName"));
        assertEquals("林晓", chain.get("characterName"));
        assertEquals("action", chain.get("scene"));
        assertEquals(2L, chain.get("characterId"));

        // steps 按 id 升序（感知→思考→行动→事件）
        List<?> steps = (List<?>) chain.get("steps");
        assertEquals("perception", ((Map<?, ?>) steps.get(0)).get("stepType"));
        assertEquals("event", ((Map<?, ?>) steps.get(4)).get("stepType"));
    }

    /**
     * trace_id 为空的行各自成独立链路（不合并、不丢）。
     */
    @Test
    void traceListKeepsNullTraceRowsAsSingles() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 11, 0, 0);
        List<ActorAiCallLog> rows = List.of(
                call(3L, null, "ai_call", 1, "success", 10, 5, 100, 1L, null, "dialog", t0.plusSeconds(20)),
                call(2L, null, "ai_call", 1, "success", 20, 6, 90, 1L, null, "dialog", t0.plusSeconds(10)),
                call(1L, "trace-2", "ai_call", 1, "success", 30, 7, 80, 1L, null, "action", t0)
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        Map<String, Object> r = service.traceList(null, null, null, null, null, null, null, 1, 20);
        List<?> items = (List<?>) r.get("items");
        assertEquals(3, items.size(), "null trace 行各自成组 + trace-2 一组");
        // 按链路最新时间倒序：两单条在前，trace-2 在后
        assertEquals("trace-2", ((Map<?, ?>) items.get(2)).get("traceId"));
        assertNull(((Map<?, ?>) items.get(0)).get("traceId"));
        assertEquals(1, ((Map<?, ?>) items.get(0)).get("stepCount"));
    }

    /**
     * 链路状态最差合并：组内有 failed 时整链路取 failed。
     */
    @Test
    void traceListWorstStatusFailedWins() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 12, 0, 0);
        List<ActorAiCallLog> rows = List.of(
                call(2L, "trace-3", "ai_call", 2, "failed", 0, 0, 100, 1L, 2L, "action", t0.plusSeconds(10)),
                call(1L, "trace-3", "ai_call", 1, "success", 50, 20, 90, 1L, 2L, "action", t0)
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        Map<String, Object> r = service.traceList(null, null, null, null, null, null, null, 1, 20);
        List<?> items = (List<?>) r.get("items");
        assertEquals("failed", ((Map<?, ?>) items.get(0)).get("status"));
    }

    /**
     * 单链路详情：trace_id 下全部环节按 id 升序，AI 环节附带 detail_json。
     */
    @Test
    void traceDetailReturnsStepsAscWithDetailJson() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 13, 0, 0);
        ActorAiCallLog p = call(1L, "trace-9", "perception", 1, "success", 0, 0, 3, 1L, 2L, "action", t0);
        ActorAiCallLog a = call(2L, "trace-9", "ai_call", 1, "success", 60, 20, 200, 1L, 2L, "action", t0.plusSeconds(5));
        a.setDetailJson("{\"time\":\"2026-08-27T13:00:05\",\"status\":\"success\"}");
        when(aiCallLogRepository.findByTraceIdOrderByIdAsc("trace-9")).thenReturn(List.of(p, a));

        Map<String, Object> r = service.traceDetail("trace-9");
        assertEquals("trace-9", r.get("traceId"));
        List<?> steps = (List<?>) r.get("steps");
        assertEquals(2, steps.size());
        assertEquals("perception", ((Map<?, ?>) steps.get(0)).get("stepType"));
        assertNull(((Map<?, ?>) steps.get(0)).get("detailJson"), "非 AI 环节无 detail_json");
        assertEquals("ai_call", ((Map<?, ?>) steps.get(1)).get("stepType"));
        assertNotNull(((Map<?, ?>) steps.get(1)).get("detailJson"), "AI 环节应附带 detail_json");
    }

    /**
     * 每角色最近 N 次决策轨迹：只取该角色链路（仓库按 characterId 过滤），按链路最新时间倒序。
     */
    @Test
    void characterTracesReturnsRecentNChains() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 14, 0, 0);
        // 仓库已按 characterId=2 过滤（Specification 行为），此处只返回角色 2 的两条链路
        List<ActorAiCallLog> rows = List.of(
                call(6L, "trace-b", "ai_call", 1, "success", 10, 5, 80, 1L, 2L, "action", t0.plusSeconds(40)),
                call(5L, "trace-a", "event", 1, "success", 0, 0, 5, 1L, 2L, "action", t0.plusSeconds(30)),
                call(4L, "trace-a", "action", 1, "success", 0, 0, 8, 1L, 2L, "action", t0.plusSeconds(20)),
                call(3L, "trace-a", "ai_call", 1, "success", 50, 10, 100, 1L, 2L, "action", t0.plusSeconds(10))
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(character(2L, "林晓")));

        Map<String, Object> r = service.characterTraces(2L, 10);
        assertEquals(2L, r.get("characterId"));
        assertEquals("林晓", r.get("characterName"));
        List<?> items = (List<?>) r.get("items");
        assertEquals(2, items.size(), "角色 2 只有两条链路");
        // 按链路最新时间倒序：trace-b（最新）在前
        assertEquals("trace-b", ((Map<?, ?>) items.get(0)).get("traceId"));
        assertEquals("trace-a", ((Map<?, ?>) items.get(1)).get("traceId"));
    }

    /**
     * limit 生效：角色链路多于 N 时只取最近 N 条。
     */
    @Test
    void characterTracesRespectsLimit() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 15, 0, 0);
        List<ActorAiCallLog> rows = List.of(
                call(4L, "t-4", "ai_call", 1, "success", 1, 1, 10, 1L, 2L, "action", t0.plusSeconds(30)),
                call(3L, "t-3", "ai_call", 1, "success", 1, 1, 10, 1L, 2L, "action", t0.plusSeconds(20)),
                call(2L, "t-2", "ai_call", 1, "success", 1, 1, 10, 1L, 2L, "action", t0.plusSeconds(10)),
                call(1L, "t-1", "ai_call", 1, "success", 1, 1, 10, 1L, 2L, "action", t0)
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        Map<String, Object> r = service.characterTraces(2L, 2);
        List<?> items = (List<?>) r.get("items");
        assertEquals(2, items.size());
        assertEquals("t-4", ((Map<?, ?>) items.get(0)).get("traceId"));
        assertEquals("t-3", ((Map<?, ?>) items.get(1)).get("traceId"));
    }

    /**
     * 行级筛选：characterId 过滤传入 Specification（冒烟：非 null 行时正常返回）。
     */
    @Test
    void traceListSupportsCharacterFilter() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 27, 16, 0, 0);
        List<ActorAiCallLog> rows = List.of(
                call(1L, null, "ai_call", 1, "success", 1, 1, 10, 1L, 2L, "dialog", t0)
        );
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        Map<String, Object> r = service.traceList(null, 2L, null, null, null, null, null, 1, 20);
        List<?> items = (List<?>) r.get("items");
        assertEquals(1, items.size());
        assertTrue(((Map<?, ?>) items.get(0)).get("steps") instanceof List);
    }
}
