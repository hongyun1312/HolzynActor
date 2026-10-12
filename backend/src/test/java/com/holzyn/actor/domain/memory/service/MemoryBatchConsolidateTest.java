package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆批量巩固单元测试（记忆系统重构 M4，2026-09-12）。
 * <p>职责：验证 batchConsolidate 编排——游戏日边界主调度（引擎传入 gameHour）与 cron 兜底
 * （null 按世界时钟解析）、L1 项目级巩固委托、相关事实合并（确定性 Dice 合并 + 版本链归档）、
 * 项目记忆巩固委托（编年史补漏 + 归档）、开关门控与单步失败隔离。</p>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案_V1.0》§3.3/§9 M4；用户 2026-09-12 决策：
 * 调度点=游戏日边界为主 + 真实 cron 兜底。</p>
 */
class MemoryBatchConsolidateTest {

    private MemoryWorkingService workingService;
    private MemoryArchiveService archiveService;
    private ActorMemoryRepository memoryRepository;
    private WorldMemoryService worldMemoryService;
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;
    private ActorProjectRepository projectRepository;
    private MemoryConsolidationService service;

    @BeforeEach
    void setUp() {
        workingService = Mockito.mock(MemoryWorkingService.class);
        archiveService = Mockito.mock(MemoryArchiveService.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        worldMemoryService = Mockito.mock(WorldMemoryService.class);
        worldClockService = Mockito.mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        service = new MemoryConsolidationService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "batchCronEnabled", true);
        ReflectionTestUtils.setField(service, "mergeThreshold", 0.85);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        ReflectionTestUtils.setField(service, "archiveService", archiveService);
        ReflectionTestUtils.setField(service, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(service, "worldMemoryService", worldMemoryService);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
    }

    /**
     * 批量巩固：三步编排（L1 项目巩固 + 事实合并 + 项目记忆巩固），返回步骤数。
     */
    @Test
    void batchConsolidateRunsAllSteps() {
        when(workingService.consolidateProject(100L)).thenReturn(2);
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L,
                ActorMemoryTypes.TYPE_SEMANTIC, 0)).thenReturn(List.of());
        int steps = service.batchConsolidate(100L, 24L); // 第 1 游戏日 00:00
        assertEquals(3, steps);
        verify(workingService).consolidateProject(100L);
        verify(worldMemoryService).consolidateWorldMemory(100L, 1L, 24L * 3600L);
    }

    /**
     * 开关门控：enabled=false → 0，不执行任何步骤。
     */
    @Test
    void batchConsolidateDisabledSkips() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(0, service.batchConsolidate(100L, 24L));
        verify(workingService, never()).consolidateProject(anyLong());
    }

    /**
     * cron 兜底：gameHour=null → 按世界时钟解析当前游戏小时；时钟不可用 → 跳过。
     */
    @Test
    void batchConsolidateCronFallbackResolvesClock() {
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(100L); // 第 4 日 04:00
        int steps = service.batchConsolidate(100L, null);
        assertEquals(3, steps);
        verify(worldMemoryService).consolidateWorldMemory(100L, 4L, 100L * 3600L);

        // 时钟不可用 → 跳过
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(null);
        assertEquals(0, service.batchConsolidate(100L, null));
    }

    /**
     * 单步失败隔离：L1 巩固抛异常 → 后续步骤仍执行（步骤计数不含失败步）。
     */
    @Test
    void batchConsolidateIsolatesStepFailures() {
        when(workingService.consolidateProject(100L)).thenThrow(new RuntimeException("L1 失败"));
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L,
                ActorMemoryTypes.TYPE_SEMANTIC, 0)).thenReturn(List.of());
        int steps = service.batchConsolidate(100L, 24L);
        assertEquals(2, steps, "L1 失败隔离，事实合并+项目记忆仍执行");
        verify(worldMemoryService).consolidateWorldMemory(100L, 1L, 24L * 3600L);
    }

    /**
     * 事实合并：同角色语义记忆 Dice ≥0.85 强重复 → 保留较新、旧条软删归档（supersedes 版本链）。
     */
    @Test
    void mergeDuplicateFactsArchivesOldStrongDuplicates() {
        ActorMemory older = new ActorMemory();
        older.setId(1L);
        older.setProjectId(100L);
        older.setCharacterRef("special:10");
        older.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        older.setContent("张屠户在村西开了肉铺");
        older.setDeleted(0);
        older.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        ActorMemory newer = new ActorMemory();
        newer.setId(2L);
        newer.setProjectId(100L);
        newer.setCharacterRef("special:10");
        newer.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        newer.setContent("张屠户在村西开了肉铺");
        newer.setDeleted(0);
        newer.setCreatedAt(LocalDateTime.of(2026, 9, 5, 8, 0));
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L,
                ActorMemoryTypes.TYPE_SEMANTIC, 0)).thenReturn(List.of(older, newer));
        int merged = service.batchConsolidate(100L, 48L);
        assertEquals(3, merged, "1 合并 + L1 + 项目记忆 = 3 步");
        verify(memoryRepository).save(older);
        verify(archiveService).archiveL2Memory(older, "superseded");
    }

    /**
     * 事实合并阈值：不同主题（Dice 低于阈值）→ 不合并。
     */
    @Test
    void mergeSkipsUnrelatedFacts() {
        ActorMemory a = new ActorMemory();
        a.setId(1L);
        a.setProjectId(100L);
        a.setCharacterRef("special:10");
        a.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        a.setContent("张屠户在村西开了肉铺");
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        ActorMemory b = new ActorMemory();
        b.setId(2L);
        b.setProjectId(100L);
        b.setCharacterRef("special:10");
        b.setType(ActorMemoryTypes.TYPE_SEMANTIC);
        b.setContent("白灵犀的巫祝祭坛在竹林深处");
        b.setDeleted(0);
        b.setCreatedAt(LocalDateTime.of(2026, 9, 2, 8, 0));
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L,
                ActorMemoryTypes.TYPE_SEMANTIC, 0)).thenReturn(List.of(a, b));
        service.batchConsolidate(100L, 48L);
        verify(memoryRepository, never()).save(Mockito.argThat(m -> m instanceof ActorMemory
                && (m.getId() != null && (m.getId() == 1L || m.getId() == 2L))
                && m.getDeleted() != null && m.getDeleted() == 1));
    }

    /**
     * cron 兜底遍历：全部项目依次批量巩固（单项目失败隔离继续）。
     */
    @Test
    void batchConsolidateCronWalksProjects() {
        var p1 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p1.setId(100L);
        var p2 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p2.setId(101L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(24L);
        when(worldClockService.currentEngineGameHour(101L)).thenReturn(24L);
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(Mockito.anyLong(),
                Mockito.any(), Mockito.any())).thenReturn(List.of());
        when(workingService.consolidateProject(anyLong())).thenReturn(0);
        service.batchConsolidateCron();
        verify(workingService).consolidateProject(100L);
        verify(workingService).consolidateProject(101L);
    }

    // ==================== O2 世界运行时门控（2026-09-16 用户需求：启动全静止，进入沙盘才运行） ====================

    /**
     * O2 门控：cron 兜底遍历只批量巩固运行中的世界——未激活项目跳过
     * （记忆蒸馏/批量巩固/L3 核心自编辑不对静止世界发生，世界静止=数据静止）。
     */
    @Test
    void batchConsolidateCronSkipsStoppedWorlds() {
        var p1 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p1.setId(100L);
        var p2 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p2.setId(101L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(24L);
        when(worldClockService.currentEngineGameHour(101L)).thenReturn(24L);
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(Mockito.anyLong(),
                Mockito.any(), Mockito.any())).thenReturn(List.of());
        when(workingService.consolidateProject(anyLong())).thenReturn(0);
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(true);
        when(gate.isActive(101L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);
        service.batchConsolidateCron();
        verify(workingService).consolidateProject(100L);
        verify(workingService, never()).consolidateProject(101L);
        verify(worldMemoryService, never()).consolidateWorldMemory(101L, 1L, 24L * 3600L);
    }

    /**
     * O2 门控：cron 兜底单项目（gameHour=null）遇未运行世界 → 直接跳过（0 步，不执行任何动作）。
     */
    @Test
    void batchConsolidateCronFallbackSkipsWhenWorldStopped() {
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(100L);
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);
        assertEquals(0, service.batchConsolidate(100L, null));
        verify(workingService, never()).consolidateProject(anyLong());
        verify(worldMemoryService, never()).consolidateWorldMemory(anyLong(), any(), any());
    }

    /**
     * O2 门控不越界：引擎记忆周期钩子路径（gameHour 非 null）不因门控跳过——
     * 该路径本就由引擎激活闸门覆盖，此处保证未装配门控/引擎直调语义不变。
     */
    @Test
    void batchConsolidateEngineHookNotGated() {
        when(workingService.consolidateProject(100L)).thenReturn(2);
        when(memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L,
                ActorMemoryTypes.TYPE_SEMANTIC, 0)).thenReturn(List.of());
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);
        int steps = service.batchConsolidate(100L, 24L);
        assertEquals(3, steps, "引擎钩子路径（gameHour 非 null）不被 cron 门控拦截");
        verify(workingService).consolidateProject(100L);
    }
}
