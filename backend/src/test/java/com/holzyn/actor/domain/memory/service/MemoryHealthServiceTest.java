package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryArchiveRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 记忆治理健康度服务单元测试（M5，domain/memory/service，同包）。
 * <p>职责：验证——健康度聚合结构（归档量[总数/按原因/按来源/已还原] + 版本链数[L2/L3] +
 * 分层分布[L1 active 按 item_type / L2 type 四类 + 摘要子类 / L3 核心块数]）、
 * 角色维度过滤（characterRef 走角色仓库查询）、非法参数（null projectId → 空结构）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§11.3。</p>
 */
class MemoryHealthServiceTest {

    private ActorMemoryRepository memoryRepository;
    private ActorMemoryWorkingRepository workingRepository;
    private ActorCoreMemoryRepository coreRepository;
    private ActorMemoryArchiveRepository archiveRepository;
    private MemoryHealthService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        coreRepository = Mockito.mock(ActorCoreMemoryRepository.class);
        archiveRepository = Mockito.mock(ActorMemoryArchiveRepository.class);
        service = new MemoryHealthService(memoryRepository, workingRepository, coreRepository, archiveRepository);
        // 默认空
        when(archiveRepository.findByProjectIdAndDeletedOrderByArchivedAtAsc(anyLong(), eq(0))).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(anyLong(), eq(0)))
                .thenReturn(List.of());
        when(coreRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeleted(anyLong(), eq(0))).thenReturn(List.of());
        when(workingRepository.findByProjectIdAndStatusAndDeleted(anyLong(), eq("active"), eq(0))).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndDeletedOrderByIdAsc(anyLong(), eq(0))).thenReturn(List.of());
        when(coreRepository.countByProjectIdAndDeleted(anyLong(), eq(0))).thenReturn(0L);
    }

    // ==================== 构造数据 ====================

    private ActorMemoryArchive archive(Long id, String reason, String sourceTable, Integer restored) {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(id);
        a.setProjectId(1L);
        a.setArchiveReason(reason);
        a.setSourceTable(sourceTable);
        a.setRestored(restored);
        a.setArchivedAt(LocalDateTime.now());
        a.setDeleted(0);
        return a;
    }

    private ActorMemoryWorking working(Long id, String itemType) {
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setId(id);
        w.setProjectId(1L);
        w.setItemType(itemType);
        w.setStatus("active");
        w.setDeleted(0);
        return w;
    }

    private ActorMemory mem(Long id, String type, String contextJson) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(1L);
        m.setType(type);
        m.setContextJson(contextJson);
        m.setDeleted(0);
        return m;
    }

    private ActorCoreMemory core(Long id) {
        ActorCoreMemory c = new ActorCoreMemory();
        c.setId(id);
        c.setProjectId(1L);
        c.setDeleted(0);
        return c;
    }

    // ==================== 非法参数 ====================

    /**
     * null projectId：返回空健康度结构（各块默认 0/空）。
     */
    @Test
    void nullProjectReturnsEmptyHealth() {
        Map<String, Object> h = service.health(null);
        assertEquals(0, ((Map<?, ?>) h.get("archive")).get("total"));
        assertEquals(0, ((Map<?, ?>) h.get("versionChain")).get("totalVersionChainCount"));
        assertEquals(0L, ((Map<?, ?>) h.get("tier")).get("l3CoreBlocks"));
    }

    // ==================== 归档量 ====================

    /**
     * 归档量：总数 + 按原因分组 + 按来源表分组 + 已还原计数。
     */
    @Test
    void archiveMetricsGroupsByReasonAndSource() {
        when(archiveRepository.findByProjectIdAndDeletedOrderByArchivedAtAsc(eq(1L), eq(0)))
                .thenReturn(List.of(
                        archive(1L, "age", "actor_memory", 1),
                        archive(2L, "superseded", "actor_memory", 0),
                        archive(3L, "superseded", "actor_memory_core", 0),
                        archive(4L, "manual", "actor_memory", 0)));
        Map<String, Object> h = service.health(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) h.get("archive");
        assertEquals(4, a.get("total"));
        assertEquals(1, ((Map<?, ?>) a.get("byReason")).get("age"));
        assertEquals(2, ((Map<?, ?>) a.get("byReason")).get("superseded"));
        assertEquals(1, ((Map<?, ?>) a.get("byReason")).get("manual"));
        assertEquals(3, ((Map<?, ?>) a.get("bySourceTable")).get("actor_memory"));
        assertEquals(1, ((Map<?, ?>) a.get("bySourceTable")).get("actor_memory_core"));
        assertEquals(1, a.get("restored"));
    }

    // ==================== 版本链数 ====================

    /**
     * 版本链数：L2 版本链条数 + L3 核心块版本链条数 + 合计。
     */
    @Test
    void versionChainCountsL2AndL3() {
        when(memoryRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(mem(1L, ActorMemoryTypes.TYPE_SEMANTIC, null),
                        mem(2L, ActorMemoryTypes.TYPE_EPISODIC, null)));
        when(coreRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeleted(eq(1L), eq(0)))
                .thenReturn(List.of(core(1L)));
        Map<String, Object> h = service.health(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) h.get("versionChain");
        assertEquals(2, v.get("l2VersionChainCount"));
        assertEquals(1, v.get("l3CoreBlockChainCount"));
        assertEquals(3, v.get("totalVersionChainCount"));
    }

    // ==================== 分层分布 ====================

    /**
     * 分层分布：L1 active 工作项按 item_type、L2 记忆 type 四类 + 摘要子类、L3 核心块数。
     */
    @Test
    void tierMetricsCountsL1L2L3() {
        when(workingRepository.findByProjectIdAndStatusAndDeleted(eq(1L), eq("active"), eq(0)))
                .thenReturn(List.of(working(1L, "draft"), working(2L, "task"), working(3L, "draft")));
        when(memoryRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(
                        mem(1L, ActorMemoryTypes.TYPE_SEMANTIC, null),
                        mem(2L, ActorMemoryTypes.TYPE_SEMANTIC, ActorMemoryTypes.CONTEXT_SUMMARY_JSON),
                        mem(3L, ActorMemoryTypes.TYPE_EPISODIC, null),
                        mem(4L, ActorMemoryTypes.TYPE_REFLECTION, null),
                        mem(5L, ActorMemoryTypes.TYPE_PLAN, null)));
        when(coreRepository.countByProjectIdAndDeleted(eq(1L), eq(0))).thenReturn(3L);
        Map<String, Object> h = service.health(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> tier = (Map<String, Object>) h.get("tier");
        @SuppressWarnings("unchecked")
        Map<String, Object> l1 = (Map<String, Object>) tier.get("l1");
        assertEquals(3, l1.get("activeCount"));
        assertEquals(2, ((Map<?, ?>) l1.get("byItemType")).get("draft"));
        assertEquals(1, ((Map<?, ?>) l1.get("byItemType")).get("task"));
        @SuppressWarnings("unchecked")
        Map<String, Object> l2 = (Map<String, Object>) tier.get("l2");
        assertEquals(5, l2.get("total"));
        assertEquals(2, l2.get("semantic"), "2 条 semantic（含 1 条摘要子类）");
        assertEquals(1, l2.get("episodic"));
        assertEquals(1, l2.get("reflection"));
        assertEquals(1, l2.get("plan"));
        assertEquals(1, l2.get("summary"), "摘要子类 1 条");
        assertEquals(3L, tier.get("l3CoreBlocks"));
    }

    // ==================== 角色维度 ====================

    /**
     * 角色维度：characterRef 非空时各仓库走角色过滤查询（special:{id}）。
     */
    @Test
    void characterRefUsesRefScopedQueries() {
        when(archiveRepository.findByProjectIdAndCharacterRefAndDeletedOrderByArchivedAtDesc(
                eq(1L), eq("special:7"), eq(0)))
                .thenReturn(List.of(archive(1L, "manual", "actor_memory", 0)));
        when(memoryRepository.findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(
                eq(1L), eq("special:7"), eq(0))).thenReturn(List.of(mem(1L, ActorMemoryTypes.TYPE_SEMANTIC, null)));
        when(coreRepository.findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeleted(
                eq(1L), eq("special:7"), eq(0))).thenReturn(List.of(core(1L)));
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(1L), eq("special:7"), eq("active"), eq(0)))
                .thenReturn(List.of(working(1L, "draft")));
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                eq(1L), eq("special:7"), eq(0))).thenReturn(List.of(mem(1L, ActorMemoryTypes.TYPE_EPISODIC, null)));
        when(coreRepository.countByProjectIdAndCharacterRefAndDeleted(eq(1L), eq("special:7"), eq(0))).thenReturn(2L);

        Map<String, Object> h = service.health(1L, "special:7");
        assertEquals("special:7", h.get("characterRef"));
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) h.get("archive");
        assertEquals(1, a.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) h.get("versionChain");
        assertEquals(2, v.get("totalVersionChainCount"), "L2 1 + L3 1");
        @SuppressWarnings("unchecked")
        Map<String, Object> tier = (Map<String, Object>) h.get("tier");
        assertEquals(1, ((Map<?, ?>) tier.get("l1")).get("activeCount"));
        assertEquals(1, ((Map<?, ?>) tier.get("l2")).get("episodic"));
        assertEquals(2L, tier.get("l3CoreBlocks"));
    }

    /**
     * characterRef 空白（角色未选）：回退项目维度全量查询。
     */
    @Test
    void blankCharacterRefFallsBackToProjectScope() {
        Map<String, Object> h = service.health(1L, "  ");
        assertTrue(h.containsKey("archive"));
        assertEquals(1L, h.get("projectId"));
    }
}
