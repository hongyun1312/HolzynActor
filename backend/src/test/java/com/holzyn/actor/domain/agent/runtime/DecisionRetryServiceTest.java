package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DecisionRetryService（P0-02 根治 · 可重试性）单元测试。
 * <p>职责：验证决策失败入队（开关/容量/去重闸门 + PENDING 落行）、到点扫描限批、
 * 原子认领委托、失败收口（指数退避重排 / max 档不限次数 / 耗尽放弃）、成功/主动放弃终态、
 * 队列统计（可观测端点数据源）。</p>
 */
class DecisionRetryServiceTest {

    private ActorDecisionRetryRepository repository;
    private DecisionRetryService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorDecisionRetryRepository.class);
        service = new DecisionRetryService(repository);
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "retryEnabled", true);
        ReflectionTestUtils.setField(service, "maxAttempts", 8);
        ReflectionTestUtils.setField(service, "initialBackoffMs", 15_000L);
        ReflectionTestUtils.setField(service, "backoffFactor", 2.0);
        ReflectionTestUtils.setField(service, "maxBackoffMs", 300_000L);
        ReflectionTestUtils.setField(service, "maxPending", 500);
        ReflectionTestUtils.setField(service, "staleMs", 600_000L);
    }

    /** 构造待重试作业（已认领/未认领均可，按测试场景用）。 */
    private ActorDecisionRetry job(long id, long gameSecond, int attempt, String status) {
        ActorDecisionRetry j = new ActorDecisionRetry();
        j.setId(id);
        j.setProjectId(1L);
        j.setCharacterId(10L);
        j.setCharacterRef("npc:10");
        j.setGameSecond(gameSecond);
        j.setGameDay(gameSecond / 86400L);
        j.setAttemptCount(attempt);
        j.setStatus(status);
        j.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return j;
    }

    /**
     * 决策失败入队：无存量行 → 落 PENDING 作业（去重键/原定时刻/首次退避/失败原因），返回 true。
     */
    @Test
    void enqueueWritesPendingWithInitialBackoff() {
        when(repository.countByProjectIdAndStatus(1L, "PENDING")).thenReturn(0L);
        when(repository.findByProjectIdAndCharacterIdAndGameSecond(1L, 10L, 7200L))
                .thenReturn(Optional.empty());
        LocalDateTime before = LocalDateTime.now();

        boolean enqueued = service.enqueue(1L, 10L, "npc:10", 1L, 7200L, "AI 不可用");

        assertTrue(enqueued, "入队成功");
        ArgumentCaptor<ActorDecisionRetry> cap = ArgumentCaptor.forClass(ActorDecisionRetry.class);
        verify(repository, times(1)).save(cap.capture());
        ActorDecisionRetry row = cap.getValue();
        assertEquals(1L, row.getProjectId());
        assertEquals(10L, row.getCharacterId());
        assertEquals("npc:10", row.getCharacterRef());
        assertEquals(1L, row.getUserId(), "用户透传（重跑决策上下文）");
        assertEquals(Long.valueOf(7200L), row.getGameSecond(), "原定决策时刻");
        assertEquals(Long.valueOf(0L), row.getGameDay());
        assertEquals(DecisionRetryService.STATUS_PENDING, row.getStatus());
        assertEquals(Integer.valueOf(0), row.getAttemptCount());
        assertEquals("AI 不可用", row.getLastError());
        assertTrue(!row.getNextRetryAt().isBefore(before.plusSeconds(14))
                        && !row.getNextRetryAt().isAfter(LocalDateTime.now().plusSeconds(16)),
                "首次退避≈15s（墙钟）");
    }

    /** 重试总开关关闭 → 不入队不落行（退回 P0-02 止血 (a) 缺失留痕行为）。 */
    @Test
    void enqueueDisabledReturnsFalseWithoutSaving() {
        ReflectionTestUtils.setField(service, "retryEnabled", false);

        boolean enqueued = service.enqueue(1L, 10L, "npc:10", null, 3600L, "AI 不可用");

        assertFalse(enqueued);
        verify(repository, never()).save(any(ActorDecisionRetry.class));
    }

    /** 去重：同决策点已有 PENDING 行 → 仅刷新失败原因，不新建不重置退避。 */
    @Test
    void enqueueExistingPendingRefreshesErrorOnly() {
        ActorDecisionRetry existing = job(99L, 7200L, 1, DecisionRetryService.STATUS_PENDING);
        when(repository.findByProjectIdAndCharacterIdAndGameSecond(1L, 10L, 7200L))
                .thenReturn(Optional.of(existing));

        boolean enqueued = service.enqueue(1L, 10L, "npc:10", null, 7200L, "第二次失败");

        assertTrue(enqueued, "已有 PENDING 仍视为已入队");
        verify(repository, times(1)).save(existing);
        assertEquals("第二次失败", existing.getLastError(), "仅刷新失败原因");
        assertEquals(Integer.valueOf(1), existing.getAttemptCount(), "不重置尝试次数");
    }

    /** 去重：同决策点已成功/已放弃 → 不再重入（每决策点恰好一次补算）。 */
    @Test
    void enqueueExistingTerminalDoesNotReenter() {
        when(repository.findByProjectIdAndCharacterIdAndGameSecond(1L, 10L, 7200L))
                .thenReturn(Optional.of(job(99L, 7200L, 3, DecisionRetryService.STATUS_SUCCEEDED)));

        boolean enqueued = service.enqueue(1L, 10L, "npc:10", null, 7200L, "AI 不可用");

        assertFalse(enqueued);
        verify(repository, never()).save(any(ActorDecisionRetry.class));
    }

    /** 容量闸门：PENDING 达上限 → 不入队（调用方按决策缺失留痕，有界队列语义）。 */
    @Test
    void enqueueQueueFullReturnsFalse() {
        ReflectionTestUtils.setField(service, "maxPending", 2);
        when(repository.countByProjectIdAndStatus(1L, "PENDING")).thenReturn(2L);

        boolean enqueued = service.enqueue(1L, 10L, "npc:10", null, 3600L, "AI 不可用");

        assertFalse(enqueued);
        verify(repository, never()).save(any(ActorDecisionRetry.class));
    }

    /** 到点扫描：只返回 PENDING 且 next_retry_at ≤ now 的作业，并按 limit 限批。 */
    @Test
    void dueJobsReturnsDueOnlyBoundedByLimit() {
        when(repository.findByProjectIdAndStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(1L), eq("PENDING"), any(LocalDateTime.class)))
                .thenReturn(List.of(job(1L, 3600L, 0, "PENDING"), job(2L, 7200L, 0, "PENDING"), job(3L, 10800L, 0, "PENDING")));

        List<ActorDecisionRetry> limited = service.dueJobs(1L, LocalDateTime.now(), 2);
        assertEquals(2, limited.size(), "按 limit 限批，超出留到下一轮扫描");
        List<ActorDecisionRetry> all = service.dueJobs(1L, LocalDateTime.now(), 0);
        assertEquals(3, all.size(), "limit<=0 不限批");
        assertEquals(DecisionRetryService.STATUS_PENDING, all.get(0).getStatus());
    }

    /** 失败收口：未达上限 → 尝试次数 +1、指数退避重排（继续 PENDING），返回 true。 */
    @Test
    void markFailedBumpsAttemptAndBackoffs() {
        ActorDecisionRetry j = job(1L, 7200L, 0, DecisionRetryService.STATUS_RETRYING);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);

        boolean willRetry = service.markFailed(j, "AI 超时", now, false);

        assertTrue(willRetry, "未达上限继续重试");
        assertEquals(Integer.valueOf(1), j.getAttemptCount());
        assertEquals(DecisionRetryService.STATUS_PENDING, j.getStatus(), "失败重排回 PENDING");
        assertEquals(now.plusSeconds(15), j.getNextRetryAt(), "第 1 次重试退避 15s");
        assertEquals("AI 超时", j.getLastError());
        verify(repository, times(1)).save(j);
    }

    /** 失败收口：达到上限 → ABANDONED，返回 false（调用方随后落显式缺失）。 */
    @Test
    void markFailedExhaustsAttemptsAbandons() {
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        ActorDecisionRetry j = job(1L, 7200L, 2, DecisionRetryService.STATUS_RETRYING); // 已试 2 次
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);

        boolean willRetry = service.markFailed(j, "AI 超时", now, false);

        assertFalse(willRetry, "第 3 次失败 > 上限 2 → 放弃");
        assertEquals(Integer.valueOf(3), j.getAttemptCount());
        assertEquals(DecisionRetryService.STATUS_ABANDONED, j.getStatus());
        assertNull(j.getNextRetryAt(), "终态清空退避排程");
    }

    /** max 档（unlimited=true）：不限重试次数，永不因上限放弃（对齐「最高性能档不限成本」）。 */
    @Test
    void markFailedUnlimitedNeverAbandons() {
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        ActorDecisionRetry j = job(1L, 7200L, 10, DecisionRetryService.STATUS_RETRYING);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);

        boolean willRetry = service.markFailed(j, "AI 超时", now, true);

        assertTrue(willRetry, "max 档不限次数");
        assertEquals(DecisionRetryService.STATUS_PENDING, j.getStatus(), "仍继续重排");
    }

    /** 指数退避：×2 递增并封顶。 */
    @Test
    void backoffGrowsExponentiallyAndCaps() {
        assertEquals(15_000L, service.backoffMs(1), "首次 15s");
        assertEquals(30_000L, service.backoffMs(2), "二次 30s");
        assertEquals(60_000L, service.backoffMs(3), "三次 60s");
        assertEquals(300_000L, service.backoffMs(8), "超过封顶取 maxBackoffMs（8 次≈1.92e6→300s）");
        assertEquals(300_000L, service.backoffMs(20), "继续封顶");
    }

    /** 成功收口：作业 → SUCCEEDED，清空退避与失败原因。 */
    @Test
    void markSucceededSetsFinalState() {
        ActorDecisionRetry j = job(1L, 7200L, 2, DecisionRetryService.STATUS_RETRYING);

        service.markSucceeded(j);

        assertEquals(DecisionRetryService.STATUS_SUCCEEDED, j.getStatus());
        assertNull(j.getNextRetryAt());
        assertNull(j.getLastError());
        verify(repository, times(1)).save(j);
    }

    /** 主动放弃（角色已删除/已故等）：作业 → ABANDONED 并记录原因。 */
    @Test
    void markAbandonedRecordsReason() {
        ActorDecisionRetry j = job(1L, 7200L, 0, DecisionRetryService.STATUS_RETRYING);

        service.markAbandoned(j, "角色已故，放弃补算");

        assertEquals(DecisionRetryService.STATUS_ABANDONED, j.getStatus());
        assertEquals("角色已故，放弃补算", j.getLastError());
        verify(repository, times(1)).save(j);
    }

    /** 统计：pending/retrying/succeeded/abandoned 各状态计数 + 配置（可观测端点数据源）。 */
    @Test
    void statsCountsAllStates() {
        when(repository.countByProjectIdAndStatus(1L, "PENDING")).thenReturn(3L);
        when(repository.countByProjectIdAndStatus(1L, "RETRYING")).thenReturn(1L);
        when(repository.countByProjectIdAndStatus(1L, "SUCCEEDED")).thenReturn(5L);
        when(repository.countByProjectIdAndStatus(1L, "ABANDONED")).thenReturn(2L);

        Map<String, Object> stats = service.stats(1L);

        assertEquals(3L, stats.get("pending"));
        assertEquals(1L, stats.get("retrying"));
        assertEquals(5L, stats.get("succeeded"));
        assertEquals(2L, stats.get("abandoned"));
        assertEquals(true, stats.get("enabled"));
    }
}
