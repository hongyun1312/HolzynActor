package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆巩固编排服务单元测试（M5，domain/memory/service，同包）。
 * <p>职责：验证——会话结束巩固（开关/空 sessionKey 门控、委托 MemoryWorkingService.consolidateSession、
 * 返回提升条数、无服务/异常隔离）+ 统一定时维护（开关门控、L1 维护[TTL 清理 + 巩固扫描]、
 * 归档维护[按项目遍历 超龄扫描 + 保留滚动 + 二次清理]、单步失败隔离、步骤计数）。
 * 全部管理动作落 scene=memory_manage trace（AiCallContext.recordStep——无 sink 时安全 no-op）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§10。</p>
 */
class MemoryConsolidationServiceTest {

    private MemoryWorkingService workingService;
    private MemoryArchiveService archiveService;
    private ActorProjectRepository projectRepository;
    private MemoryConsolidationService service;

    @BeforeEach
    void setUp() {
        workingService = Mockito.mock(MemoryWorkingService.class);
        archiveService = Mockito.mock(MemoryArchiveService.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        service = new MemoryConsolidationService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maintenanceEnabled", true);
        ReflectionTestUtils.setField(service, "maintenanceIntervalMs", 1800000L);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        ReflectionTestUtils.setField(service, "archiveService", archiveService);
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
    }

    // ==================== 会话结束巩固 ====================

    /**
     * 总开关关闭：直接返回 0，不委托巩固。
     */
    @Test
    void consolidateConversationDisabledSkips() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(0, service.consolidateConversation("s1"));
        verify(workingService, never()).consolidateSession(Mockito.anyString());
    }

    /**
     * sessionKey 空白：直接返回 0，不委托巩固。
     */
    @Test
    void consolidateConversationBlankSessionSkips() {
        assertEquals(0, service.consolidateConversation("  "));
        assertEquals(0, service.consolidateConversation(null));
        verify(workingService, never()).consolidateSession(Mockito.anyString());
    }

    /**
     * 正常巩固：委托 workingService.consolidateSession 并返回提升条数。
     */
    @Test
    void consolidateConversationDelegatesAndReturnsPromoted() {
        when(workingService.consolidateSession("s1")).thenReturn(3);
        assertEquals(3, service.consolidateConversation("s1"));
        verify(workingService).consolidateSession("s1");
    }

    /**
     * workingService 不可用：返回 0 不抛异常（编排层可空依赖）。
     */
    @Test
    void consolidateConversationNullWorkingIsSafe() {
        ReflectionTestUtils.setField(service, "workingService", null);
        assertEquals(0, service.consolidateConversation("s1"));
    }

    /**
     * workingService 抛异常：隔离返回 0，不向上抛（trace 仍记录）。
     */
    @Test
    void consolidateConversationIsolatesWorkingFailure() {
        when(workingService.consolidateSession("s1")).thenThrow(new RuntimeException("L1 失败"));
        assertEquals(0, service.consolidateConversation("s1"));
    }

    // ==================== 统一定时维护 ====================

    /**
     * 维护开关门控：总开关关 → 0；maintenanceEnabled 关 → 0。
     */
    @Test
    void maintenanceDisabledSkips() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(0, service.maintenance());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maintenanceEnabled", false);
        assertEquals(0, service.maintenance());
        verify(workingService, never()).cleanupExpired();
        verify(archiveService, never()).scanAgeArchiving(Mockito.anyLong());
    }

    /**
     * 正常维护：L1 维护（TTL 清理 + 巩固扫描）+ 归档维护（按项目遍历 超龄扫描/保留滚动/二次清理），
     * 返回步骤数 = 2（L1 + 归档）。
     */
    @Test
    void maintenanceRunsWorkingAndArchive() {
        when(workingService.cleanupExpired()).thenReturn(2);
        when(workingService.consolidateStaleSweep()).thenReturn(3);
        when(archiveService.scanAgeArchiving(1L)).thenReturn(1);
        when(archiveService.enforceRetention(1L)).thenReturn(1);
        when(archiveService.cleanupArchive(1L)).thenReturn(2);
        when(archiveService.scanAgeArchiving(2L)).thenReturn(0);
        when(archiveService.enforceRetention(2L)).thenReturn(0);
        when(archiveService.cleanupArchive(2L)).thenReturn(0);
        when(projectRepository.findAll()).thenReturn(List.of(project(1L), project(2L)));

        int steps = service.maintenance();
        assertEquals(2, steps, "L1 维护 + 归档维护两步");
        verify(workingService).cleanupExpired();
        verify(workingService).consolidateStaleSweep();
        verify(archiveService, times(2)).scanAgeArchiving(Mockito.anyLong());
        verify(archiveService, times(2)).enforceRetention(Mockito.anyLong());
        verify(archiveService, times(2)).cleanupArchive(Mockito.anyLong());
    }

    /**
     * L1 维护失败：隔离继续归档维护，仍返回步骤数。
     */
    @Test
    void maintenanceIsolatesWorkingFailure() {
        when(workingService.cleanupExpired()).thenThrow(new RuntimeException("L1 维护失败"));
        when(projectRepository.findAll()).thenReturn(List.of(project(1L)));
        when(archiveService.scanAgeArchiving(1L)).thenReturn(0);
        when(archiveService.enforceRetention(1L)).thenReturn(0);
        when(archiveService.cleanupArchive(1L)).thenReturn(0);

        int steps = service.maintenance();
        assertEquals(1, steps, "L1 失败隔离后仍执行归档维护 1 步");
        verify(archiveService).scanAgeArchiving(1L);
    }

    /**
     * 单项目归档维护失败：隔离该步继续下一项目，不阻断整体。
     */
    @Test
    void maintenanceIsolatesPerProjectArchiveFailure() {
        when(projectRepository.findAll()).thenReturn(List.of(project(1L), project(2L)));
        when(archiveService.scanAgeArchiving(1L)).thenThrow(new RuntimeException("项目 1 超龄扫描失败"));
        when(archiveService.enforceRetention(1L)).thenReturn(0);
        when(archiveService.cleanupArchive(1L)).thenReturn(0);
        when(archiveService.scanAgeArchiving(2L)).thenReturn(0);
        when(archiveService.enforceRetention(2L)).thenReturn(0);
        when(archiveService.cleanupArchive(2L)).thenReturn(0);

        int steps = service.maintenance();
        assertEquals(2, steps, "单项目失败隔离，归档维护仍完成并计步");
        verify(archiveService, times(2)).scanAgeArchiving(Mockito.anyLong());
    }

    /**
     * 无归档服务：仅 L1 维护，返回 1 步。
     */
    @Test
    void maintenanceWithoutArchiveRunsWorkingOnly() {
        ReflectionTestUtils.setField(service, "archiveService", null);
        when(workingService.cleanupExpired()).thenReturn(0);
        when(workingService.consolidateStaleSweep()).thenReturn(0);
        assertEquals(1, service.maintenance());
        verify(workingService).cleanupExpired();
        verify(workingService).consolidateStaleSweep();
    }

    /**
     * 无项目仓库：归档维护跳过项目遍历仍计步（不抛异常）。
     */
    @Test
    void maintenanceNullProjectRepositorySafe() {
        ReflectionTestUtils.setField(service, "projectRepository", null);
        when(workingService.cleanupExpired()).thenReturn(0);
        when(workingService.consolidateStaleSweep()).thenReturn(0);
        assertEquals(2, service.maintenance(), "归档服务在但无项目仓库 → 空遍历仍计 1 步");
    }

    private static ActorProject project(Long id) {
        ActorProject p = new ActorProject();
        p.setId(id);
        return p;
    }
}
