package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.memory.service.MemoryHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DebugDriftMetricsService（D5 F19 决策链路可视化 · 漂移度量视图[R4]）单元测试（与实现同包，
 * domain/debug/service）。
 * <p>职责：验证——目标一致率（actor_character_goal 状态分布 + done/全部）、矛盾率（kind=reflection
 * 含矛盾关键词条数占比，中文/英文关键词命中）、Token 成本（窗口内调用次数/token/耗时聚合 +
 * 按日趋势 + 按角色排行 + 项目/角色过滤）、M5 记忆治理健康度（memoryHealth 块委托
 * MemoryHealthService.health，角色维度 special:{id} 透传）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库）；token 查询走 findByCreatedAtGreaterThanEqual 后
 * 内存按项目/角色过滤。</p>
 */
class DebugDriftMetricsServiceTest {

    private ActorCharacterGoalRepository goalRepository;
    private ActorMemoryRepository memoryRepository;
    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorCharacterRepository characterRepository;
    private MemoryHealthService memoryHealthService;
    private DebugDriftMetricsService service;

    @BeforeEach
    void setUp() {
        goalRepository = Mockito.mock(ActorCharacterGoalRepository.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        aiCallLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        memoryHealthService = Mockito.mock(MemoryHealthService.class);
        service = new DebugDriftMetricsService(goalRepository, memoryRepository, aiCallLogRepository,
                characterRepository, memoryHealthService);
        // M5 记忆治理健康度默认空（既有用例不断言该块）
        when(memoryHealthService.health(anyLong(), nullable(String.class)))
                .thenReturn(Map.of("archive", Map.of("total", 0, "byReason", Map.of(), "bySourceTable", Map.of(), "restored", 0)));
    }

    /** 构造目标 */
    private static ActorCharacterGoal goal(Long id, String status) {
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(id);
        g.setProjectId(1L);
        g.setAgentRef("npc:2");
        g.setTitle("目标" + id);
        g.setStatus(status);
        return g;
    }

    /** 构造反思记忆 */
    private static ActorMemory reflection(Long id, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(1L);
        m.setCharacterId(2L);
        m.setCharacterRef("npc:2");
        m.setType("reflection");
        m.setContent(content);
        return m;
    }

    /** 构造 AI 调用行 */
    private static ActorAiCallLog call(Long id, Long projectId, Long characterId, int tokenIn, int tokenOut,
                                       int durationMs, LocalDateTime createdAt) {
        ActorAiCallLog c = new ActorAiCallLog();
        c.setId(id);
        c.setProjectId(projectId);
        c.setCharacterId(characterId);
        c.setTokenIn(tokenIn);
        c.setTokenOut(tokenOut);
        c.setDurationMs(durationMs);
        c.setStatus("success");
        c.setScene("action");
        c.setCreatedAt(createdAt);
        return c;
    }

    /**
     * 目标一致率：状态分布统计正确（active/done/abandoned），一致率 = done/全部。
     */
    @Test
    void goalMetricsCountsStatusAndConsistency() {
        when(goalRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(goal(1L, "done"), goal(2L, "active"), goal(3L, "done"), goal(4L, "abandoned")));

        Map<String, Object> r = service.driftMetrics(1L, null, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> goals = (Map<String, Object>) r.get("goals");
        assertEquals(4, goals.get("total"));
        assertEquals(1, goals.get("active"));
        assertEquals(2, goals.get("done"));
        assertEquals(1, goals.get("abandoned"));
        assertEquals(0.5d, goals.get("consistencyRate"));
    }

    /**
     * 目标一致率：角色维度走 agentRef 查询；无目标时一致率 0 不除零。
     */
    @Test
    void goalMetricsCharacterScopeAndEmptySafe() {
        when(goalRepository.findByAgentRefAndDeletedOrderByIdAsc("npc:2", 0)).thenReturn(List.of());
        Map<String, Object> r = service.driftMetrics(1L, 2L, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> goals = (Map<String, Object>) r.get("goals");
        assertEquals(0, goals.get("total"));
        assertEquals(0d, goals.get("consistencyRate"));
    }

    /**
     * 矛盾率：命中矛盾关键词的反思条数占比（中文 + 英文小写）。
     */
    @Test
    void reflectionMetricsDetectsContradictionKeywords() {
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(1L, "reflection", 0))
                .thenReturn(List.of(
                        reflection(1L, "发现过去目标与当前行动存在矛盾，需要重新审视"),
                        reflection(2L, "决定专注于酿酒技艺"),
                        reflection(3L, "此前的判断与现状不一致，已修正"),
                        reflection(4L, "The action contradicts my earlier belief")
                ));
        Map<String, Object> r = service.driftMetrics(1L, null, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) r.get("reflection");
        assertEquals(4, ref.get("total"));
        assertEquals(3, ref.get("contradictionCount"));
        assertEquals(0.75d, ref.get("contradictionRate"));
    }

    /**
     * Token 成本：窗口内聚合正确（次数/token/耗时），按日与按角色分组。
     */
    @Test
    void tokenMetricsAggregatesByDayAndCharacter() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 26, 10, 0, 0);
        LocalDateTime t1 = LocalDateTime.of(2026, 8, 27, 11, 0, 0);
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any(LocalDateTime.class)))
                .thenReturn(List.of(
                        call(1L, 1L, 2L, 100, 50, 1000, t0),
                        call(2L, 1L, 2L, 200, 60, 1500, t0),
                        call(3L, 1L, 3L, 300, 70, 2000, t1)
                ));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(character(2L, "林晓")));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L, "阿栗")));

        Map<String, Object> r = service.driftMetrics(1L, null, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) r.get("token");
        assertEquals(3, token.get("calls"));
        assertEquals(600, token.get("tokenIn"));
        assertEquals(180, token.get("tokenOut"));
        assertEquals(780, token.get("totalToken"));
        assertEquals(4500, token.get("durationMs"));

        // 按日：两天各 1 组
        List<?> byDay = (List<?>) token.get("byDay");
        assertEquals(2, byDay.size());
        assertEquals("2026-08-26", ((Map<?, ?>) byDay.get(0)).get("date"));
        assertEquals(2, ((Map<?, ?>) byDay.get(0)).get("calls"));
        assertEquals(300, ((Map<?, ?>) byDay.get(0)).get("tokenIn"));
        assertEquals("2026-08-27", ((Map<?, ?>) byDay.get(1)).get("date"));

        // 按角色排行：token 总量倒序（阿栗 370 > 林晓 410? 不对：阿栗 300+70=370，林晓 300+110=410）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCharacter = (List<Map<String, Object>>) token.get("byCharacter");
        assertEquals(2, byCharacter.size());
        assertEquals("林晓", byCharacter.get(0).get("characterName"));
        assertEquals("阿栗", byCharacter.get(1).get("characterName"));
        assertEquals(410, (Integer) byCharacter.get(0).get("tokenIn") + (Integer) byCharacter.get(0).get("tokenOut"));
    }

    /**
     * Token 成本：角色过滤只统计该角色（projectId+characterId 均过滤）。
     */
    @Test
    void tokenMetricsFiltersByCharacter() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 26, 10, 0, 0);
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any(LocalDateTime.class)))
                .thenReturn(List.of(
                        call(1L, 1L, 2L, 100, 50, 1000, t0),
                        call(2L, 1L, 3L, 300, 70, 2000, t0),
                        call(3L, 2L, 2L, 50, 20, 500, t0)
                ));
        Map<String, Object> r = service.driftMetrics(1L, 2L, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) r.get("token");
        assertEquals(1, token.get("calls"), "仅统计 project=1 且 character=2 的调用");
        assertEquals(100, token.get("tokenIn"));
        assertEquals(50, token.get("tokenOut"));
    }

    /**
     * 无角色归属（character_id=null）的调用归「无角色/普通NPC」，排行不丢。
     */
    @Test
    void tokenMetricsIncludesNullCharacterBucket() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 26, 10, 0, 0);
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any(LocalDateTime.class)))
                .thenReturn(List.of(call(1L, 1L, null, 100, 50, 1000, t0)));
        Map<String, Object> r = service.driftMetrics(1L, null, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) r.get("token");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCharacter = (List<Map<String, Object>>) token.get("byCharacter");
        assertEquals(1, byCharacter.size());
        assertTrue(String.valueOf(byCharacter.get(0).get("characterName")).contains("无角色"));
    }

    private static ActorCharacter character(Long id, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        return c;
    }

    // ==================== M5 记忆治理健康度（§11.3） ====================

    /**
     * 漂移度量含 memoryHealth 块：项目维度委托 MemoryHealthService.health(projectId, null)。
     */
    @Test
    void driftMetricsIncludesMemoryHealthBlock() {
        when(memoryHealthService.health(1L, null))
                .thenReturn(Map.of("archive", Map.of("total", 5, "byReason", Map.of("manual", 5),
                        "bySourceTable", Map.of(), "restored", 1)));
        Map<String, Object> r = service.driftMetrics(1L, null, 30);
        verify(memoryHealthService).health(1L, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> mh = (Map<String, Object>) r.get("memoryHealth");
        assertEquals(5, ((Map<?, ?>) mh.get("archive")).get("total"));
        assertEquals(1, ((Map<?, ?>) mh.get("archive")).get("restored"));
    }

    /**
     * 漂移度量角色维度：memoryHealth 以 special:{id} 透传（与目标/反思/Token 同角色口径）。
     */
    @Test
    void driftMetricsMemoryHealthPassesCharacterRef() {
        when(memoryHealthService.health(1L, "npc:2"))
                .thenReturn(Map.of("archive", Map.of("total", 1, "byReason", Map.of(), "bySourceTable", Map.of(), "restored", 0)));
        when(goalRepository.findByAgentRefAndDeletedOrderByIdAsc("npc:2", 0)).thenReturn(List.of());
        Map<String, Object> r = service.driftMetrics(1L, 2L, 30);
        verify(memoryHealthService).health(1L, "npc:2");
        assertTrue(r.containsKey("memoryHealth"));
    }

    /**
     * 漂移度量无项目选择：memoryHealth 走空占位（不调用服务，不报错）。
     */
    @Test
    void driftMetricsNullProjectUsesEmptyMemoryHealth() {
        when(goalRepository.findByDeletedOrderByIdAsc(0)).thenReturn(List.of());
        when(memoryRepository.findByTypeAndDeletedOrderByIdAsc("reflection", 0)).thenReturn(List.of());
        Map<String, Object> r = service.driftMetrics(null, null, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> mh = (Map<String, Object>) r.get("memoryHealth");
        assertEquals(0, ((Map<?, ?>) mh.get("archive")).get("total"));
        assertEquals(0, ((Map<?, ?>) mh.get("versionChain")).get("totalVersionChainCount"));
        assertEquals(0L, ((Map<?, ?>) mh.get("tier")).get("l3CoreBlocks"));
    }
}
