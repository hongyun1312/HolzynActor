package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryArchiveRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档服务单元测试（M4，domain/memory/service，同包）。
 * <p>职责：验证——手动归档 L2/L3（归档行字段 + 软删）、批量分发、L2 版本链更新（新条 version+1 +
 * supersedes_id + 旧条软删 + 旧条归档 superseded）、还原（L2/L3 + 去重跳过 + 块键已占用版本链）、
 * 超龄归档判定与扫描（重要度/游戏日/低访问过滤）、保留策略（上限滚动软删 + 二次物理清理）、
 * 预算淘汰归档（默认关）、反思降级（重要度阈值过滤）、export JSON/Markdown、归档软删。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§6.5/§8/§13（M4）。</p>
 */
class MemoryArchiveServiceTest {

    private ActorMemoryArchiveRepository archiveRepository;
    private ActorMemoryRepository memoryRepository;
    private ActorCoreMemoryRepository coreRepository;
    private ActorMemoryWorkingRepository workingRepository;
    private ActorProjectRepository projectRepository;
    private WorldClockService worldClockService;
    private MemoryArchiveService service;

    /** 内存存储（模拟真实 DB 的 save/find 语义） */
    private final List<ActorMemory> memoryStore = new ArrayList<>();
    private final List<ActorCoreMemory> coreStore = new ArrayList<>();
    private final List<ActorMemoryArchive> archiveStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        archiveRepository = Mockito.mock(ActorMemoryArchiveRepository.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        coreRepository = Mockito.mock(ActorCoreMemoryRepository.class);
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);

        memoryStore.clear();
        coreStore.clear();
        archiveStore.clear();

        service = new MemoryArchiveService(archiveRepository, memoryRepository, coreRepository,
                workingRepository, projectRepository);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPerProject", 5000);
        ReflectionTestUtils.setField(service, "retainDays", 90);
        ReflectionTestUtils.setField(service, "lowAccessCount", 1);
        ReflectionTestUtils.setField(service, "evictEnabled", false);
        ReflectionTestUtils.setField(service, "downgradeEnabled", true);
        ReflectionTestUtils.setField(service, "downgradeImportanceThreshold", 3);
        ReflectionTestUtils.setField(service, "staleImportanceThreshold", 3);
        ReflectionTestUtils.setField(service, "staleAfterGameDays", 3);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);

        // 项目归属校验：纯数据测试上下文跳过（currentUserProvider 留 null）

        // ===== 归档仓库：内存存储 =====
        when(archiveRepository.save(any(ActorMemoryArchive.class))).thenAnswer(inv -> {
            ActorMemoryArchive a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId((long) (archiveStore.size() + 1));
            }
            archiveStore.removeIf(x -> x.getId() != null && x.getId().equals(a.getId()));
            archiveStore.add(a);
            return a;
        });
        when(archiveRepository.findById(anyLong())).thenAnswer(inv -> archiveStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getId())).findFirst());
        when(archiveRepository.countByProjectIdAndDeleted(anyLong(), eq(0))).thenAnswer(inv ->
                archiveStore.stream().filter(x -> inv.getArgument(0).equals(x.getProjectId())
                        && Integer.valueOf(0).equals(x.getDeleted())).count());
        when(archiveRepository.findByProjectIdOrderByImportanceAtArchiveAscArchivedAtAsc(anyLong()))
                .thenAnswer(inv -> archiveStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId()))
                        .sorted((a, b) -> {
                            int byImp = (a.getImportanceAtArchive() == null ? 1 : a.getImportanceAtArchive())
                                    - (b.getImportanceAtArchive() == null ? 1 : b.getImportanceAtArchive());
                            if (byImp != 0) {
                                return byImp;
                            }
                            return (a.getArchivedAt() == null ? LocalDateTime.MIN : a.getArchivedAt())
                                    .compareTo(b.getArchivedAt() == null ? LocalDateTime.MIN : b.getArchivedAt());
                        }).toList());
        when(archiveRepository.findByProjectIdAndDeletedAndUpdatedAtBefore(anyLong(), eq(1), any()))
                .thenAnswer(inv -> archiveStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && Integer.valueOf(1).equals(x.getDeleted()))
                        .toList());
        when(archiveRepository.findByProjectIdAndDeletedOrderByArchivedAtDesc(anyLong(), eq(0)))
                .thenAnswer(inv -> archiveStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && Integer.valueOf(0).equals(x.getDeleted()))
                        .toList());
        when(archiveRepository.findByProjectIdAndCharacterRefAndDeletedOrderByArchivedAtDesc(
                anyLong(), anyString(), eq(0))).thenAnswer(inv -> archiveStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                        && inv.getArgument(1).equals(x.getCharacterRef())
                        && Integer.valueOf(0).equals(x.getDeleted()))
                .toList());
        when(archiveRepository.findAll((org.springframework.data.jpa.domain.Specification<ActorMemoryArchive>) any(),
                any(Pageable.class))).thenAnswer(inv -> {
            // 简化：返回全部未删归档（列表过滤单测按整表断言）
            return new PageImpl<>(new ArrayList<>(archiveStore));
        });

        // ===== L2 记忆仓库：内存存储 =====
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> {
            ActorMemory m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (memoryStore.size() + 1));
            }
            memoryStore.removeIf(x -> x.getId() != null && x.getId().equals(m.getId()));
            memoryStore.add(m);
            return m;
        });
        when(memoryRepository.findById(anyLong())).thenAnswer(inv -> memoryStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getId())).findFirst());
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyString(), eq(0))).thenAnswer(inv -> memoryStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                        && inv.getArgument(1).equals(x.getCharacterRef())
                        && Integer.valueOf(0).equals(x.getDeleted()))
                .toList());
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(
                anyLong(), eq(0))).thenAnswer(inv -> memoryStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                        && x.getCharacterId() == null
                        && Integer.valueOf(0).equals(x.getDeleted()))
                .toList());
        when(memoryRepository.findByProjectIdAndDeleted(anyLong(), eq(0), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(memoryStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && Integer.valueOf(0).equals(x.getDeleted()))
                        .toList()));

        // ===== L3 核心块仓库：内存存储 =====
        when(coreRepository.save(any(ActorCoreMemory.class))).thenAnswer(inv -> {
            ActorCoreMemory b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId((long) (coreStore.size() + 1));
            }
            coreStore.removeIf(x -> x.getId() != null && x.getId().equals(b.getId()));
            coreStore.add(b);
            return b;
        });
        when(coreRepository.findById(anyLong())).thenAnswer(inv -> coreStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getId())).findFirst());
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                anyLong(), anyString(), anyString(), eq(0))).thenAnswer(inv -> coreStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                        && inv.getArgument(1).equals(x.getCharacterRef())
                        && inv.getArgument(2).equals(x.getBlockKey())
                        && Integer.valueOf(0).equals(x.getDeleted()))
                .toList());
    }

    // ==================== 测试数据 ====================

    private ActorMemory memory(Long id, Long projectId, String ref, String type, String content,
                                int importance, int version, int deleted, int accessCount, LocalDateTime created) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(projectId);
        m.setCharacterRef(ref);
        // G1a：私有 parseSpecialId 解析器已删除——统一身份解析走 NpcRefs.idOf（严格 npc: 前缀）
        m.setCharacterId(com.holzyn.actor.common.NpcRefs.idOf(ref));
        m.setType(type);
        m.setContent(content);
        m.setImportance(importance);
        m.setVersion(version);
        m.setDeleted(deleted);
        m.setAccessCount(accessCount);
        m.setCreatedAt(created == null ? LocalDateTime.of(2026, 1, 1, 0, 0) : created);
        return m;
    }

    private ActorCoreMemory core(Long id, Long projectId, String ref, String key, String name, String content,
                                 int importance, int deleted) {
        ActorCoreMemory b = new ActorCoreMemory();
        b.setId(id);
        b.setProjectId(projectId);
        b.setCharacterRef(ref);
        b.setBlockKey(key);
        b.setBlockName(name);
        b.setContent(content);
        b.setImportance(importance);
        b.setEditableByAgent(1);
        b.setVersion(1);
        b.setDeleted(deleted);
        return b;
    }

    // ==================== 手动归档 ====================

    @Test
    void manualArchiveL2_writesArchiveAndSoftDeletes() {
        ActorMemory m = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "罗小黑喜欢竹叶青", 4, 1, 0, 0, null);
        memoryStore.add(m);
        int n = service.manualArchiveL2(10L, List.of(1L));
        assertEquals(1, n);
        assertEquals(1, m.getDeleted()); // 软删
        assertEquals(1, archiveStore.size());
        ActorMemoryArchive a = archiveStore.get(0);
        assertEquals("actor_memory", a.getSourceTable());
        assertEquals(1L, a.getSourceMemoryId());
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC, a.getType());
        assertEquals("罗小黑喜欢竹叶青", a.getContent());
        assertEquals(4, a.getImportanceAtArchive());
        assertEquals(MemoryArchiveService.REASON_MANUAL, a.getArchiveReason());
        assertEquals("npc:5", a.getCharacterRef());
    }

    @Test
    void manualArchiveCore_writesArchiveAndSoftDeletes() {
        ActorCoreMemory b = core(1L, 10L, "npc:5", "goals", "长期目标", "守护青岩山三年", 4, 0);
        coreStore.add(b);
        int n = service.manualArchiveCore(10L, List.of(1L));
        assertEquals(1, n);
        assertEquals(1, b.getDeleted());
        assertEquals(1, archiveStore.size());
        ActorMemoryArchive a = archiveStore.get(0);
        assertEquals("actor_memory_core", a.getSourceTable());
        assertEquals("core", a.getType());
        assertEquals("守护青岩山三年", a.getContent());
        assertEquals(MemoryArchiveService.REASON_MANUAL, a.getArchiveReason());
        assertTrue(a.getContextJson() != null && a.getContextJson().contains("\"block_key\":\"goals\""));
    }

    @Test
    void batchArchive_dispatchsBySourceTable() {
        ActorMemory m = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "事实A", 3, 1, 0, 0, null);
        ActorCoreMemory b = core(2L, 10L, "npc:5", "identity", "身份", "身份B", 4, 0);
        memoryStore.add(m);
        coreStore.add(b);
        int nMem = service.batchArchive(10L, "actor_memory", List.of(1L));
        int nCore = service.batchArchive(10L, "actor_memory_core", List.of(2L));
        assertEquals(1, nMem);
        assertEquals(1, nCore);
        assertEquals(2, archiveStore.size());
    }

    // ==================== L2 版本链更新 ====================

    @Test
    void updateL2Memory_createsVersionChain_andArchivesOld() {
        ActorMemory old = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "旧事实", 3, 1, 0, 2, null);
        memoryStore.add(old);
        ActorMemory next = service.updateL2Memory(10L, 1L, "新事实（修正）", 5);
        assertEquals(2, next.getVersion());
        assertEquals(1L, next.getSupersedesId());
        assertEquals("新事实（修正）", next.getContent());
        assertEquals(5, next.getImportance());
        assertEquals(1, old.getDeleted()); // 旧条软删
        assertEquals(1, archiveStore.size());
        assertEquals(1L, archiveStore.get(0).getSourceMemoryId());
        assertEquals(MemoryArchiveService.REASON_SUPERSEDED, archiveStore.get(0).getArchiveReason());
    }

    @Test
    void updateL2Memory_rejectsBlankContent() {
        ActorMemory old = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "旧事实", 3, 1, 0, 2, null);
        memoryStore.add(old);
        assertThrows(BizException.class, () -> service.updateL2Memory(10L, 1L, "  ", null));
    }

    // ==================== 还原 ====================

    @Test
    void restoreToMemory_createsEntry_andMarksRestored() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(1L);
        a.setProjectId(10L);
        a.setCharacterRef("npc:5");
        a.setSourceTable("actor_memory");
        a.setSourceMemoryId(9L);
        a.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        a.setContent("还原的事实内容");
        a.setImportanceAtArchive(4);
        archiveStore.add(a);
        Map<String, Object> r = service.restore(1L);
        assertEquals(true, r.get("restored"));
        assertEquals("actor_memory", r.get("sourceTable"));
        assertEquals(1, a.getRestored());
        assertEquals(1, memoryStore.size());
        ActorMemory m = memoryStore.get(0);
        assertEquals("还原的事实内容", m.getContent());
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC, m.getType());
        assertEquals("npc:5", m.getCharacterRef());
    }

    @Test
    void restoreToMemory_dedupSkips_whenContentExists() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(2L);
        a.setProjectId(10L);
        a.setCharacterRef("npc:5");
        a.setSourceTable("actor_memory");
        a.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        a.setContent("相同内容");
        a.setImportanceAtArchive(3);
        archiveStore.add(a);
        ActorMemory existing = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "相同内容", 3, 1, 0, 0, null);
        memoryStore.add(existing);
        assertThrows(BizException.class, () -> service.restore(2L));
    }

    @Test
    void restoreToCore_createsBlock_fromContextJson_andVersionChainsWhenOccupied() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(3L);
        a.setProjectId(10L);
        a.setCharacterRef("npc:5");
        a.setSourceTable("actor_memory_core");
        a.setType("core");
        a.setContent("信念：不伤害弱者");
        a.setContextJson("{\"block_key\":\"beliefs\",\"block_name\":\"核心信念\"}");
        a.setImportanceAtArchive(5);
        archiveStore.add(a);
        Long id = (Long) service.restore(3L).get("createdId");
        assertEquals(1, coreStore.size());
        ActorCoreMemory b = coreStore.get(0);
        assertEquals("beliefs", b.getBlockKey());
        assertEquals("核心信念", b.getBlockName());
        assertEquals(1, a.getRestored());

        // 结论：首次还原已建 beliefs 块（id=1）。再次以同块键还原 → 走版本链（新块 supersedes 现活动块 +
        // 现活动块软删 + 归档 superseded，不重建重复块）
        ActorMemoryArchive a2 = new ActorMemoryArchive();
        a2.setId(4L);
        a2.setProjectId(10L);
        a2.setCharacterRef("npc:5");
        a2.setSourceTable("actor_memory_core");
        a2.setType("core");
        a2.setContent("新信念");
        a2.setContextJson("{\"block_key\":\"beliefs\",\"block_name\":\"核心信念\"}");
        a2.setImportanceAtArchive(4);
        archiveStore.add(a2);
        Long id2 = (Long) service.restore(4L).get("createdId");
        ActorCoreMemory restored = coreStore.stream().filter(x -> x.getId().equals(id2)).findFirst().orElseThrow();
        assertEquals(2, restored.getVersion());
        assertEquals(id, restored.getSupersedesId());
        // 原活动块（id=id）被软删 + 归档 superseded
        ActorCoreMemory original = coreStore.stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(1, original.getDeleted());
        assertTrue(archiveStore.stream().anyMatch(x -> x.getArchiveReason() != null
                && x.getArchiveReason().equals(MemoryArchiveService.REASON_SUPERSEDED)));
    }

    @Test
    void restore_rejectsWorkingSource() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(5L);
        a.setProjectId(10L);
        a.setSourceTable("actor_memory_working");
        a.setType("working");
        a.setContent("工作项");
        archiveStore.add(a);
        assertThrows(BizException.class, () -> service.restore(5L));
    }

    // ==================== 超龄归档 ====================

    @Test
    void isAgeArchivable_requiresCharacterLevelStaleAndLowAccess() {
        // 命中：重要度4 + 超3游戏日 + access 1
        when(worldClockService.gameHourAt(anyLong(), any())).thenReturn(0L); // base 游戏日0
        ActorMemory m = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "超期事实", 4, 1, 0, 1,
                LocalDateTime.of(2026, 1, 1, 0, 0));
        assertTrue(service.isAgeArchivable(m, 4)); // now 游戏日4 - base 0 = 4 >= 3

        // 项目级（无 characterRef）不参与
        ActorMemory proj = memory(2L, 10L, null, ActorMemoryTypes.TYPE_SEMANTIC, "大事记", 4, 1, 0, 0, null);
        assertFalse(service.isAgeArchivable(proj, 10));

        // 高访问不归档
        ActorMemory hot = memory(3L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "高频", 4, 1, 0, 5, null);
        assertFalse(service.isAgeArchivable(hot, 10));

        // 低重要度不归档
        ActorMemory low = memory(4L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "低值", 1, 1, 0, 0, null);
        assertFalse(service.isAgeArchivable(low, 10));
    }

    @Test
    void scanAgeArchiving_archivesMatching_andSoftDeletes() {
        when(worldClockService.currentGameHour(anyLong())).thenReturn(96L); // now 游戏日4
        when(worldClockService.gameHourAt(anyLong(), any())).thenReturn(0L); // base 游戏日0
        ActorMemory stale = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "超期", 4, 1, 0, 1,
                LocalDateTime.of(2026, 1, 1, 0, 0));
        ActorMemory fresh = memory(2L, 10L, "npc:5", ActorMemoryTypes.TYPE_EPISODIC, "近期", 4, 1, 0, 5,
                LocalDateTime.now());
        memoryStore.add(stale);
        memoryStore.add(fresh);
        int n = service.scanAgeArchiving(10L);
        assertEquals(1, n);
        assertEquals(1, stale.getDeleted());
        assertEquals(0, fresh.getDeleted());
        assertEquals(1, archiveStore.size());
        assertEquals(MemoryArchiveService.REASON_AGE, archiveStore.get(0).getArchiveReason());
    }

    // ==================== 预算淘汰归档（默认关） ====================

    @Test
    void archiveEvicted_defaultDisabled_noWrite() {
        ActorMemory m = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "淘汰", 2, 1, 0, 0, null);
        int n = service.archiveEvicted(List.of(m));
        assertEquals(0, n);
        assertEquals(0, archiveStore.size()); // 默认关不写
    }

    @Test
    void archiveEvicted_enabled_writes() {
        ReflectionTestUtils.setField(service, "evictEnabled", true);
        ActorMemory m = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "淘汰", 2, 1, 0, 0, null);
        int n = service.archiveEvicted(List.of(m));
        assertEquals(1, n);
        assertEquals(MemoryArchiveService.REASON_EVICTED, archiveStore.get(0).getArchiveReason());
    }

    // ==================== 反思降级 ====================

    @Test
    void downgradeEvidence_archivesLowImportance_keepsHigh() {
        ActorMemory low = memory(1L, 10L, "npc:5", ActorMemoryTypes.TYPE_EPISODIC, "低价值证据", 2, 1, 0, 0, null);
        ActorMemory high = memory(2L, 10L, "npc:5", ActorMemoryTypes.TYPE_SEMANTIC, "高价值证据", 5, 1, 0, 0, null);
        memoryStore.add(low);
        memoryStore.add(high);
        int n = service.downgradeEvidence(10L, "npc:5", List.of(1L, 2L));
        assertEquals(1, n);
        assertEquals(1, low.getDeleted());
        assertEquals(0, high.getDeleted());
        assertEquals(1, archiveStore.size());
        assertEquals(MemoryArchiveService.REASON_DOWNGRADED, archiveStore.get(0).getArchiveReason());
    }

    // ==================== 保留策略 ====================

    @Test
    void enforceRetention_softDeletesOldestBeyondCap() {
        ReflectionTestUtils.setField(service, "maxPerProject", 2);
        for (int i = 1; i <= 4; i++) {
            ActorMemoryArchive a = new ActorMemoryArchive();
            a.setId((long) i);
            a.setProjectId(10L);
            a.setCharacterRef("npc:5");
            a.setContent("归档" + i);
            a.setImportanceAtArchive(i);
            a.setDeleted(0);
            archiveStore.add(a);
        }
        int softDeleted = service.enforceRetention(10L);
        assertEquals(2, softDeleted);
        // 重要度低的最旧两条被软删（id 1、2）
        assertEquals(2, archiveStore.stream().filter(a -> a.getDeleted() == 1).count());
    }

    @Test
    void cleanupArchive_deletesExpiredSoftDeleted() {
        ReflectionTestUtils.setField(service, "retainDays", 90);
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(1L);
        a.setProjectId(10L);
        a.setDeleted(1);
        a.setUpdatedAt(LocalDateTime.now().minusDays(120));
        archiveStore.add(a);
        int n = service.cleanupArchive(10L);
        assertEquals(1, n);
    }

    // ==================== 导出 / 软删归档 ====================

    @Test
    void exportJson_andMarkdown_nonEmpty() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(1L);
        a.setProjectId(10L);
        a.setCharacterRef("npc:5");
        a.setSourceTable("actor_memory");
        a.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        a.setContent("导出内容");
        a.setArchiveReason("age");
        a.setDeleted(0);
        archiveStore.add(a);
        String json = service.export(10L, null, "json");
        assertNotNull(json);
        assertTrue(json.contains("导出内容"));
        String md = service.export(10L, "npc:5", "markdown");
        assertTrue(md.contains("导出内容"));
        assertTrue(md.contains("超龄"));
    }

    @Test
    void softDeleteArchive_marksDeleted() {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setId(1L);
        a.setProjectId(10L);
        a.setContent("x");
        archiveStore.add(a);
        service.softDeleteArchive(1L);
        assertEquals(1, a.getDeleted());
    }

    // ==================== O2 世界运行时门控（2026-09-16 用户需求：启动全静止，进入沙盘才运行） ====================

    /**
     * O2 门控：定时维护只处理运行中的世界——未激活项目跳过超龄扫描/保留滚动/二次清理
     * （世界静止=数据静止，静止世界的记忆不被归档/清理触碰，连时钟解析都不发生）。
     */
    @Test
    void maintenanceSkipsStoppedWorlds() {
        ActorProject p1 = new ActorProject();
        p1.setId(100L);
        ActorProject p2 = new ActorProject();
        p2.setId(101L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));
        ReflectionTestUtils.setField(service, "maintenanceEnabled", true);
        ReflectionTestUtils.setField(service, "ageScanEnabled", true);
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(true);
        when(gate.isActive(101L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);
        service.maintenance();
        // 静止世界：维护完全不触碰（连时钟解析都不发生）
        verify(worldClockService, never()).currentGameHour(101L);
        verify(memoryRepository, never()).findByProjectIdAndDeleted(eq(101L), eq(0), any());
        // 运行中世界：正常进入维护（时钟解析 + 超龄扫描发生）
        verify(worldClockService).currentGameHour(100L);
        verify(memoryRepository).findByProjectIdAndDeleted(eq(100L), eq(0), any());
    }
}
