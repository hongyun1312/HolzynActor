package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.memory.vo.MemoryWorkingVO;
import com.holzyn.actor.common.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 短期（工作记忆）服务单元测试（M2，domain/memory/service，同包）。
 * <p>职责：验证——四类工作项写入（draft 去重 / task 上限淘汰 / context·perception 单槽替换）、
 * 【当前情境】热缓冲注入、会话结束双缓冲巩固（draft→L2 semantic / task→semantic / context·perception→episodic、
 * 重要度阈值门控、与 L2 去重、status 标记）、TTL 清理与定时巩固扫描、列表 API。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§5/§10。</p>
 */
class MemoryWorkingServiceTest {

    private ActorMemoryWorkingRepository workingRepository;
    private ActorMemoryRepository memoryRepository;
    private MemoryWorkingService service;

    /** 内存仓库模拟：写库后可读，模拟真实 DB 语义（巩固状态流转/物理清理验证用） */
    private final List<ActorMemoryWorking> workingStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        service = new MemoryWorkingService(workingRepository, memoryRepository);
        // @Value 字段回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ttlHours", 48);
        ReflectionTestUtils.setField(service, "hotTopK", 5);
        ReflectionTestUtils.setField(service, "promoteImportanceThreshold", 3);
        ReflectionTestUtils.setField(service, "maxTaskActive", 3);
        ReflectionTestUtils.setField(service, "maintenanceEnabled", true);
        ReflectionTestUtils.setField(service, "sweepMinAgeMinutes", 120);
        ReflectionTestUtils.setField(service, "retainDays", 7);
        ReflectionTestUtils.setField(service, "dedupThreshold", 0.6);
        when(workingRepository.save(any(ActorMemoryWorking.class))).thenAnswer(inv -> {
            ActorMemoryWorking w = inv.getArgument(0);
            if (w.getId() == null) {
                w.setId((long) (workingStore.size() + 1));
            }
            // 内存替换（模拟 DB 按主键更新）
            workingStore.removeIf(x -> x.getId().equals(w.getId()));
            workingStore.add(w);
            return w;
        });
        Mockito.doAnswer(inv -> {
            workingStore.removeAll(inv.getArgument(0));
            return null;
        }).when(workingRepository).deleteAll(anyList());
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== 构造工作项 ====================

    private ActorMemoryWorking item(long id, Long projectId, String ref, String sessionKey, String itemType,
                                    String content, String status, Integer importanceHint, LocalDateTime createdAt) {
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setId(id);
        w.setProjectId(projectId);
        w.setCharacterRef(ref);
        w.setSessionKey(sessionKey);
        w.setItemType(itemType);
        w.setContent(content);
        w.setSource(ActorMemoryTypes.SOURCE_DIALOG);
        w.setStatus(status);
        w.setImportanceHint(importanceHint);
        w.setCreatedAt(createdAt);
        w.setUpdatedAt(createdAt);
        w.setDeleted(0);
        return w;
    }

    private ActorMemory mem(long id, String ref, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterRef(ref);
        m.setContent(content);
        m.setDeleted(0);
        return m;
    }

    // ==================== 写入：draft 去重 ====================

    /**
     * draft 写入：无重复时写入（session_key=会话 id、status=active、expires_at 已填 TTL）。
     */
    @Test
    void writeDraftWritesWhenNoDuplicate() {
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of());

        boolean written = service.writeDraft(100L, "npc:3", "9", "玄铁是隐居云雾山的铁匠", 4);

        assertTrue(written);
        ArgumentCaptor<ActorMemoryWorking> cap = ArgumentCaptor.forClass(ActorMemoryWorking.class);
        verify(workingRepository).save(cap.capture());
        ActorMemoryWorking w = cap.getValue();
        assertEquals("draft", w.getItemType());
        assertEquals("9", w.getSessionKey());
        assertEquals(MemoryWorkingService.STATUS_ACTIVE, w.getStatus());
        assertEquals(Integer.valueOf(4), w.getImportanceHint());
        assertNotNull(w.getExpiresAt());
        assertTrue(w.getExpiresAt().isAfter(LocalDateTime.now()), "expires_at 应填 TTL 过期时间");
    }

    /**
     * draft 写入：同会话已有内容高度重叠的 active 草稿 → 去重跳过（不落库）。
     */
    @Test
    void writeDraftDedupedAgainstActiveDrafts() {
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of(item(1L, 100L, "npc:3", "9", "draft",
                        "玄铁是隐居云雾山的铁匠", MemoryWorkingService.STATUS_ACTIVE, 4, LocalDateTime.now())));
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:9"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of());

        assertFalse(service.writeDraft(100L, "npc:3", "9", "玄铁是隐居云雾山的铁匠", 4),
                "与既有 active 草稿重叠度过高应去重跳过");
        assertTrue(service.writeDraft(100L, "npc:9", "10", "石鳞在集市叫卖", 3),
                "不同角色不受影响");
    }

    // ==================== 写入：task 上限淘汰 / context·perception 单槽替换 ====================

    /**
     * task 写入：active task 超上限（3）时淘汰最旧一条再写入。
     */
    @Test
    void writeTaskEvictsOldestWhenOverCap() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking old = item(1L, 100L, "npc:3", "auto", "task", "旧任务", MemoryWorkingService.STATUS_ACTIVE, 2, t.minusHours(3));
        ActorMemoryWorking mid = item(2L, 100L, "npc:3", "auto", "task", "中任务", MemoryWorkingService.STATUS_ACTIVE, 2, t.minusHours(2));
        ActorMemoryWorking recent = item(3L, 100L, "npc:3", "auto", "task", "新任务", MemoryWorkingService.STATUS_ACTIVE, 2, t.minusHours(1));
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.ITEM_TASK), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(new ArrayList<>(List.of(recent, mid, old))); // 新→旧

        boolean written = service.writeTask(100L, "npc:3", "当前行动：巡视会馆", 4);

        assertTrue(written);
        // 最旧 task 被标记 expired（淘汰）后新增
        ArgumentCaptor<ActorMemoryWorking> cap = ArgumentCaptor.forClass(ActorMemoryWorking.class);
        verify(workingRepository, org.mockito.Mockito.times(2)).save(cap.capture());
        List<ActorMemoryWorking> saved = cap.getAllValues();
        ActorMemoryWorking evicted = saved.get(0);
        assertEquals(MemoryWorkingService.STATUS_EXPIRED, evicted.getStatus(), "超上限应淘汰最旧 task");
        assertEquals("旧任务", evicted.getContent());
        ActorMemoryWorking added = saved.get(1);
        assertEquals("当前行动：巡视会馆", added.getContent());
        assertEquals("task", added.getItemType());
    }

    /**
     * 2026-09-21 世界运行抓修：最新 task 与写入内容完全相同 → 视为"同一件事仍在进行"，只刷新过期时间，
     * 不新增行（原实现每轮 AI 给出同一行动就新增一行，实测同一句「当前行动：…」重复 4 行、
     * task 行占 23/54，污染注入上下文并让工作记忆表膨胀）。
     */
    @Test
    void writeTaskSameContentIsIdempotent() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking newest = item(9L, 100L, "npc:3", "auto", "task",
                "当前行动：披衣持灯，前往灵溪河畔查看水位", MemoryWorkingService.STATUS_ACTIVE, 3, t.minusMinutes(10));
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.ITEM_TASK), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(new ArrayList<>(List.of(newest)));

        boolean written = service.writeTask(100L, "npc:3", "当前行动：披衣持灯，前往灵溪河畔查看水位", 3);

        assertTrue(written, "幂等刷新同样返回 true");
        // 只发生一次 save（原地刷新），没有新增行
        verify(workingRepository, org.mockito.Mockito.times(1)).save(newest);
        assertEquals("当前行动：披衣持灯，前往灵溪河畔查看水位", newest.getContent());
        assertTrue(newest.getExpiresAt() != null && newest.getExpiresAt().isAfter(t),
                "同内容只刷新过期时间");
    }

    /**
     * 最新 task 内容不同 → 仍按原语义新增一行（同内容幂等不得吞掉真实行动变化）。
     */
    @Test
    void writeTaskDifferentContentStillAppends() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking newest = item(9L, 100L, "npc:3", "auto", "task",
                "当前行动：在医馆坐诊", MemoryWorkingService.STATUS_ACTIVE, 3, t.minusMinutes(10));
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.ITEM_TASK), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(new ArrayList<>(List.of(newest)));

        service.writeTask(100L, "npc:3", "当前行动：前往老井打水", 3);

        ArgumentCaptor<ActorMemoryWorking> cap = ArgumentCaptor.forClass(ActorMemoryWorking.class);
        verify(workingRepository).save(cap.capture());
        assertEquals("当前行动：前往老井打水", cap.getValue().getContent(), "内容变化=新行动，正常新增");
        assertEquals("task", cap.getValue().getItemType());
    }

    /**
     * perception 写入：同角色已有 active perception → 原地更新（单槽替换，不新增行）。
     */
    @Test
    void writePerceptionReplacesSingleSlot() {
        ActorMemoryWorking existing = item(1L, 100L, "npc:3", "auto", "perception",
                "当前感知：在妖灵会馆", MemoryWorkingService.STATUS_ACTIVE, 2, LocalDateTime.now().minusHours(1));
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.ITEM_PERCEPTION), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(new ArrayList<>(List.of(existing)));

        boolean written = service.writePerception(100L, "npc:3", "当前感知：在城东市场", 2);

        assertTrue(written);
        verify(workingRepository).save(existing); // 原地更新
        assertEquals("当前感知：在城东市场", existing.getContent());
        assertEquals(Long.valueOf(1L), existing.getId(), "单槽替换应保留原主键");
    }

    // ==================== 热缓冲注入 ====================

    /**
     * 注入段：active 工作项渲染为【当前情境】段（类型标注 + 条数限制）。
     */
    @Test
    void injectWorkingSegmentRendersActiveItems() {
        LocalDateTime t = LocalDateTime.now();
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of(
                        item(1L, 100L, "npc:3", "9", "task", "当前行动：巡视会馆", MemoryWorkingService.STATUS_ACTIVE, 4, t),
                        item(2L, 100L, "npc:3", "9", "draft", "玄铁是隐居云雾山的铁匠", MemoryWorkingService.STATUS_ACTIVE, 4, t.minusMinutes(1))));

        String segment = service.injectWorkingSegment(100L, "npc:3", 5);

        assertTrue(segment.contains("【当前情境】"));
        assertTrue(segment.contains("[任务] 当前行动：巡视会馆"));
        assertTrue(segment.contains("[草稿] 玄铁是隐居云雾山的铁匠"));
    }

    /**
     * 注入段：无 active 工作项/开关关 → 空串（调用方跳过注入）。
     */
    @Test
    void injectWorkingSegmentEmptyWhenNoItems() {
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyString(), anyString(), eq(0))).thenReturn(List.of());
        assertEquals("", service.injectWorkingSegment(100L, "npc:3", 5));
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals("", service.injectWorkingSegment(100L, "npc:3", 5));
    }

    // ==================== 会话结束双缓冲巩固 ====================

    /**
     * 巩固（draft→L2 semantic）：全部草稿提升，source=consolidation，status=consolidated；
     * 与 L2 重复的草稿不重复写（仍标记 consolidated）。
     */
    @Test
    void consolidateSessionPromotesDraftsToL2() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking draft = item(1L, 100L, "npc:3", "9", "draft",
                "昨天在山中救下受伤的猎户", MemoryWorkingService.STATUS_ACTIVE, 4, t);
        ActorMemoryWorking dupDraft = item(2L, 100L, "npc:3", "9", "draft",
                "玄铁是隐居云雾山的铁匠（常去城东湖边钓鱼）", MemoryWorkingService.STATUS_ACTIVE, 5, t.minusMinutes(1));
        when(workingRepository.findBySessionKeyAndStatusAndDeletedOrderByCreatedAtDesc(
                eq("9"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of(draft, dupDraft));
        // L2 已有与 dupDraft 重叠的记忆（去重命中）；draft 与既有 L2 无重叠（正常提升）
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(mem(1L, "npc:3", "玄铁是隐居云雾山的铁匠（常去城东湖边钓鱼）")));
        when(memoryRepository.findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(eq(100L), eq(3L), eq(0)))
                .thenReturn(List.of());

        int promoted = service.consolidateSession("9");

        assertEquals(1, promoted, "去重命中的草稿不重复写 L2，仅计未重复的");
        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository).save(cap.capture());
        ActorMemory m = cap.getValue();
        assertEquals("semantic", m.getType());
        assertEquals("npc:3", m.getCharacterRef());
        assertEquals(Long.valueOf(3L), m.getCharacterId(), "special 应双写 character_id");
        assertEquals(ActorMemoryTypes.SOURCE_CONSOLIDATION, m.getSource());
        // 状态流转：两条草稿均标记 consolidated（重复的也标记，防重复提升）
        assertEquals(MemoryWorkingService.STATUS_CONSOLIDATED, draft.getStatus());
        assertEquals(MemoryWorkingService.STATUS_CONSOLIDATED, dupDraft.getStatus());
        assertNotNull(draft.getConsolidatedAt());
    }

    /**
     * 巩固（task/context/perception 重要度门控）：重要度≥阈值提升（task→semantic、context→episodic），
     * 低于阈值直接过期不写 L2。
     */
    @Test
    void consolidateSessionGatesTaskContextPerceptionByImportance() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking taskHigh = item(1L, 100L, "npc:9", "auto", "task",
                "当前行动：巡视集市", MemoryWorkingService.STATUS_ACTIVE, 4, t);
        ActorMemoryWorking perceptionLow = item(2L, 100L, "npc:9", "auto", "perception",
                "当前感知：在城东市场", MemoryWorkingService.STATUS_ACTIVE, 2, t);
        ActorMemoryWorking contextHigh = item(3L, 100L, "npc:9", "auto", "context",
                "情境知识：城东市场逢三集市", MemoryWorkingService.STATUS_ACTIVE, 3, t);
        when(workingRepository.findBySessionKeyAndStatusAndDeletedOrderByCreatedAtDesc(
                eq("auto"), eq(MemoryWorkingService.STATUS_ACTIVE), eq(0)))
                .thenReturn(List.of(taskHigh, perceptionLow, contextHigh));
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:9"), eq(0)))
                .thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(eq(100L), eq(9L), eq(0)))
                .thenReturn(List.of());

        int promoted = service.consolidateSession("auto");

        assertEquals(2, promoted, "task(4)+context(3) 提升；perception(2) 低于阈值直接过期");
        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(cap.capture());
        List<ActorMemory> saved = cap.getAllValues();
        ActorMemory taskL2 = saved.stream().filter(x -> "当前行动：巡视集市".equals(x.getContent())).findFirst().orElseThrow();
        assertEquals("semantic", taskL2.getType(), "task→L2 semantic");
        ActorMemory ctxL2 = saved.stream().filter(x -> "情境知识：城东市场逢三集市".equals(x.getContent())).findFirst().orElseThrow();
        assertEquals("episodic", ctxL2.getType(), "context→L2 episodic");
        assertEquals(MemoryWorkingService.STATUS_EXPIRED, perceptionLow.getStatus(), "低重要度感知直接过期");
    }

    /**
     * 定时巩固扫描：active 超龄项（created_at 超阈值）被巩固；TTL 清理把已过期项置 expired。
     */
    @Test
    void consolidateStaleSweepAndCleanupExpired() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking stale = item(1L, 100L, "npc:3", "9", "draft",
                "超龄草稿", MemoryWorkingService.STATUS_ACTIVE, 4, t.minusHours(5));
        ActorMemoryWorking ttlExpired = item(2L, 100L, "npc:3", "9", "task",
                "已过期任务", MemoryWorkingService.STATUS_ACTIVE, 4, t.minusHours(3));
        when(workingRepository.findByStatusAndDeletedAndCreatedAtBefore(
                eq(MemoryWorkingService.STATUS_ACTIVE), eq(0), any()))
                .thenReturn(List.of(stale));
        when(workingRepository.findByStatusAndDeletedAndExpiresAtBefore(
                eq(MemoryWorkingService.STATUS_ACTIVE), eq(0), any()))
                .thenReturn(List.of(ttlExpired));
        when(workingRepository.findByStatusAndDeletedAndUpdatedAtBefore(
                eq(MemoryWorkingService.STATUS_CONSOLIDATED), eq(0), any())).thenReturn(List.of());
        when(workingRepository.findByStatusAndDeletedAndUpdatedAtBefore(
                eq(MemoryWorkingService.STATUS_EXPIRED), eq(0), any())).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(eq(100L), eq(3L), eq(0)))
                .thenReturn(List.of());

        int promoted = service.consolidateStaleSweep();
        assertEquals(1, promoted, "超龄 active 草稿被巩固提升");

        int cleaned = service.cleanupExpired();
        assertEquals(1, cleaned, "TTL 过期项被标记 expired");
        assertEquals(MemoryWorkingService.STATUS_EXPIRED, ttlExpired.getStatus());
        assertEquals(MemoryWorkingService.STATUS_CONSOLIDATED, stale.getStatus());
    }

    // ==================== 列表 API ====================

    /**
     * 列表 API：委托分页查询并映射 VO（含 itemType/status/importanceHint 字段）。
     */
    @Test
    void listReturnsPagedVOs() {
        LocalDateTime t = LocalDateTime.now();
        ActorMemoryWorking w = item(1L, 100L, "npc:3", "9", "draft",
                "玄铁是隐居云雾山的铁匠", MemoryWorkingService.STATUS_ACTIVE, 4, t);
        com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository repo =
                Mockito.mock(com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository.class);
        com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository memRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository.class);
        MemoryWorkingService svc = new MemoryWorkingService(repo, memRepo);
        ReflectionTestUtils.setField(svc, "enabled", true);
        org.springframework.data.domain.Page<ActorMemoryWorking> page =
                new org.springframework.data.domain.PageImpl<>(List.of(w));
        when(repo.findByProjectAndRefOptionalStatus(eq(100L), eq("npc:3"), any(), any()))
                .thenReturn(page);

        PageResult<MemoryWorkingVO> result = svc.list(100L, "npc:3", null, 1, 20);

        assertEquals(1, result.getList().size());
        MemoryWorkingVO vo = result.getList().get(0);
        assertEquals("draft", vo.getItemType());
        assertEquals(MemoryWorkingService.STATUS_ACTIVE, vo.getStatus());
        assertEquals(Integer.valueOf(4), vo.getImportanceHint());
        assertEquals("9", vo.getSessionKey());
        assertEquals("玄铁是隐居云雾山的铁匠", vo.getContent());
    }

    // ==================== O2 世界运行时门控（2026-09-16 用户需求：启动全静止，进入沙盘才运行） ====================

    /**
     * O2 门控：定时维护只处理运行中世界的工作项——静止世界的 active 工作项不被巩固扫描
     * 提升、不被 TTL 清理触碰（世界静止=数据静止）。
     */
    @Test
    void maintenanceFiltersByActiveProjects() {
        var projectRepository = Mockito.mock(com.holzyn.actor.domain.project.repository.ActorProjectRepository.class);
        var p1 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p1.setId(100L);
        var p2 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p2.setId(101L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(true);
        when(gate.isActive(101L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);

        LocalDateTime old = LocalDateTime.now().minusDays(3);
        ActorMemoryWorking w100 = item(1L, 100L, "npc:3", "9", "draft",
                "运行世界的草稿", MemoryWorkingService.STATUS_ACTIVE, 4, old);
        ActorMemoryWorking w101 = item(2L, 101L, "npc:3", "9", "draft",
                "静止世界的草稿", MemoryWorkingService.STATUS_ACTIVE, 4, old);
        when(workingRepository.findByStatusAndDeletedAndCreatedAtBefore(
                eq(MemoryWorkingService.STATUS_ACTIVE), eq(0), any())).thenReturn(List.of(w100, w101));
        when(workingRepository.findByStatusAndDeletedAndExpiresAtBefore(
                eq(MemoryWorkingService.STATUS_ACTIVE), eq(0), any())).thenReturn(List.of());
        when(workingRepository.findByStatusAndDeletedAndUpdatedAtBefore(
                eq(MemoryWorkingService.STATUS_CONSOLIDATED), eq(0), any())).thenReturn(List.of());
        when(workingRepository.findByStatusAndDeletedAndUpdatedAtBefore(
                eq(MemoryWorkingService.STATUS_EXPIRED), eq(0), any())).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(eq(100L), eq(3L), eq(0)))
                .thenReturn(List.of());

        service.maintenance();

        // 运行世界（100）：超龄草稿被巩固提升进 L2（consolidated）
        ActorMemoryWorking saved100 = workingStore.stream()
                .filter(x -> x.getId() != null && x.getId() == 1L).findFirst().orElse(null);
        assertNotNull(saved100, "运行世界的工作项被正常维护");
        assertEquals(MemoryWorkingService.STATUS_CONSOLIDATED, saved100.getStatus());
        // 静止世界（101）：工作项完全不被触碰（未保存、状态保持 active）
        ActorMemoryWorking saved101 = workingStore.stream()
                .filter(x -> x.getId() != null && x.getId() == 2L).findFirst().orElse(null);
        assertNull(saved101, "静止世界的工作项不被维护触碰");
        assertEquals(MemoryWorkingService.STATUS_ACTIVE, w101.getStatus());
    }
}
