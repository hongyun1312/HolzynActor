/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.observability.service;

import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.vo.AiCallBrief;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.observability.vo.AiRuntimeStatusVO;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiRuntimeStatusService 聚合单元测试（观测域·只读端点的行为断言）。
 *
 * <p>职责：锁定「窗口钳制 / 总量与成功失败口径 / Token 与缓存命中率 / 时延均值与 P95 /
 * 场景与状态分组排序 / 最近调用与失败明细条数与顺序 / 留痕未开启与查询失败两种降级」。</p>
 *
 * <p>说明：Mockito mock 仓库返回固定记录，纯内存聚合断言；不启动 Spring 上下文（本仓库
 * 无 {@code @SpringBootTest} 约定）。</p>
 */
class AiRuntimeStatusServiceTest {

    /** 构造一条调用留痕统计投影（与 JPQL 构造表达式同序同型） */
    private AiCallBrief row(Long id, String scene, String status, int tokenIn, int tokenOut,
                            int cacheHit, int cacheMiss, int durationMs, LocalDateTime createdAt) {
        return new AiCallBrief(id, "trace-" + id, scene, "qwen-test", status, id, tokenIn, tokenOut,
                cacheHit, cacheMiss, durationMs, createdAt, null);
    }

    /** 留痕开启时的正常聚合：总量 / 成功失败 / Token / 缓存命中率 / 时延分位 */
    @Test
    void aggregatesTotalsTokensCacheAndLatency() {
        LocalDateTime now = LocalDateTime.now();
        List<AiCallBrief> rows = List.of(
                row(1L, "dialog", "success", 100, 50, 3, 1, 100, now.minusMinutes(9)),
                row(2L, "dialog", "success", 200, 60, 0, 4, 200, now.minusMinutes(8)),
                row(3L, "action_gen", "error", 10, 0, 0, 1, 900, now.minusMinutes(7)),
                row(4L, "action_gen", "success", 30, 20, 1, 0, 300, now.minusMinutes(6)),
                row(5L, "perception", "timeout", 5, 5, 0, 0, 500, now.minusMinutes(5))
        );
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(rows);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(7L, 30);

        assertEquals(7L, vo.projectId());
        assertEquals(30, vo.windowMinutes());
        assertTrue(vo.traceEnabled());
        assertFalse(vo.degraded());
        assertEquals(5L, vo.total());
        assertEquals(3L, vo.success(), "仅 status=success 计成功");
        assertEquals(2L, vo.failed(), "error / timeout 均计失败");
        assertEquals(345L, vo.tokenIn());
        assertEquals(135L, vo.tokenOut());
        assertEquals(480L, vo.tokenTotal());
        assertEquals(4L, vo.cacheHit());
        // row4（tokenIn=30, hit=1, miss=0）= 网关省略 miss 的自愈样本 → miss 推算 29
        assertEquals(35L, vo.cacheMiss(), "row4 省略 miss 按 prompt=hit+miss 推算 29，合计 1+4+1+29+0");
        assertEquals(4.0 / 39.0, vo.cacheHitRate(), 1e-9);
        assertEquals(400L, vo.avgDurationMs(), "平均 (100+200+900+300+500)/5");
        assertEquals(900L, vo.maxDurationMs());
        assertEquals(900L, vo.p95DurationMs(), "5 样本 → ceil(4.75)-1=4 → 排序第 5 个（最大）= 900");

        // 按场景：次数降序（dialog 2 / action_gen 2 / perception 1，同次数保持插入序）
        assertEquals(3, vo.byScene().size());
        assertEquals("dialog", vo.byScene().get(0).scene());
        assertEquals(2L, vo.byScene().get(0).count());
        assertEquals(300L, vo.byScene().get(0).tokenIn());
        assertEquals(150L, vo.byScene().get(0).avgDurationMs());
        assertEquals(0L, vo.byScene().get(0).failed());
        // action_gen 有 1 次失败
        AiRuntimeStatusVO.SceneStat actionGen = vo.byScene().stream()
                .filter(s -> "action_gen".equals(s.scene())).findFirst().orElseThrow();
        assertEquals(1L, actionGen.failed());

        // 按状态：success 3 / 其余各 1
        long successStat = vo.byStatus().stream().filter(s -> "success".equals(s.status()))
                .findFirst().orElseThrow().count();
        assertEquals(3L, successStat);

        // 最近调用：倒序（最新在前）
        assertEquals(5, vo.recent().size());
        assertEquals(5L, vo.recent().get(0).id());
        assertEquals(1L, vo.recent().get(4).id());
        // 失败明细：仅 error/timeout，仍按时间倒序
        assertEquals(2, vo.failures().size());
        assertEquals(5L, vo.failures().get(0).id());
        assertEquals(3L, vo.failures().get(1).id());
        assertEquals("timeout", vo.failures().get(0).status());
    }

    /** 场景/状态分组按次数降序，同次数稳定 */
    @Test
    void groupsSortedByCountDescending() {
        LocalDateTime now = LocalDateTime.now();
        List<AiCallBrief> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row((long) i + 1, "hot", "success", 1, 1, 0, 0, 10, now.minusMinutes(i)));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(row((long) i + 10, "warm", "success", 1, 1, 0, 0, 10, now.minusMinutes(i)));
        }
        rows.add(row(99L, "cold", "success", 1, 1, 0, 0, 10, now));

        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(rows);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 60);
        assertEquals("hot", vo.byScene().get(0).scene());
        assertEquals("warm", vo.byScene().get(1).scene());
        assertEquals("cold", vo.byScene().get(2).scene());
    }

    /** 场景为空 → 归入「(未标注)」；状态为空 → unknown 且不计失败 */
    @Test
    void blanksAreNormalized() {
        LocalDateTime now = LocalDateTime.now();
        AiCallBrief r = row(1L, null, null, 0, 0, 0, 0, 10, now);
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(r));

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 30);
        assertEquals("(未标注)", vo.byScene().get(0).scene());
        assertEquals("unknown", vo.byStatus().get(0).status());
        assertEquals(1L, vo.success(), "状态为空视为占位行，不判失败");
    }

    /** 留痕未开启：结构完整但全空，且不查仓库 */
    @Test
    void traceDisabledReturnsEmptyButWellFormed() {
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(false);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 15);
        assertFalse(vo.traceEnabled());
        assertFalse(vo.degraded());
        assertEquals(0L, vo.total());
        assertEquals(0L, vo.tokenTotal());
        assertEquals(0d, vo.cacheHitRate(), 1e-9);
        assertTrue(vo.recent().isEmpty());
        assertTrue(vo.failures().isEmpty());
        assertTrue(vo.byScene().isEmpty());
        Mockito.verify(repo, Mockito.never())
                .findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }

    /** 未装配留痕服务（null）→ 等同未开启 */
    @Test
    void nullTraceServiceTreatedAsDisabled() {
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, null).status(1L, null);
        assertFalse(vo.traceEnabled());
        assertEquals(AiRuntimeStatusService.DEFAULT_WINDOW_MINUTES, vo.windowMinutes());
    }

    /** 仓库异常 → 降级为空窗口且 degraded=true（不抛出、不阻断页面） */
    @Test
    void repositoryFailureDegrades() {
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("db down"));

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 30);
        assertTrue(vo.degraded(), "查询失败必须标记降级，前端才能区分「无调用」与「统计不可用」");
        assertEquals(0L, vo.total());
        assertNotNull(vo.from());
        assertNotNull(vo.generatedAt());
    }

    /** 窗口钳制：null → 默认 30；0 → 1；999999 → 1440 */
    @Test
    void windowIsClamped() {
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiRuntimeStatusService service = new AiRuntimeStatusService(repo, null);
        assertEquals(30, service.status(1L, null).windowMinutes());
        assertEquals(AiRuntimeStatusService.MIN_WINDOW_MINUTES, service.status(1L, 0).windowMinutes());
        assertEquals(AiRuntimeStatusService.MIN_WINDOW_MINUTES, service.status(1L, -5).windowMinutes());
        assertEquals(7 * 24 * 60, AiRuntimeStatusService.MAX_WINDOW_MINUTES);
        assertEquals(AiRuntimeStatusService.MAX_WINDOW_MINUTES, service.status(1L, 999999).windowMinutes());
        assertEquals(60, service.status(1L, 60).windowMinutes());
    }

    /** 最近调用与失败明细条数上限 */
    @Test
    void recentAndFailuresAreLimited() {
        LocalDateTime now = LocalDateTime.now();
        List<AiCallBrief> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row((long) i + 1, "dialog", i % 2 == 0 ? "success" : "error",
                    1, 1, 0, 0, 10, now.minusMinutes(i)));
        }
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(rows);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 60);
        assertEquals(AiRuntimeStatusVO.RECENT_LIMIT, vo.recent().size());
        assertEquals(AiRuntimeStatusVO.FAILURE_LIMIT, vo.failures().size());
        assertEquals(30L, vo.total());
        assertEquals(15L, vo.failed());
    }

    /** 空窗口：分位/均值归零而不是抛异常 */
    @Test
    void emptyWindowIsSafe() {
        AiRuntimeStatusVO vo = new AiRuntimeStatusService(Mockito.mock(ActorAiCallLogRepository.class), null)
                .status(1L, 30);
        assertEquals(0L, vo.avgDurationMs());
        assertEquals(0L, vo.p95DurationMs());
        assertEquals(0L, vo.maxDurationMs());
    }

    /** P95 取整口径：20 个样本 1..20 → ceil(19)-1=18 → 排序第 19 个 = 19 */
    @Test
    void percentile95UsesCeilIndex() {
        LocalDateTime now = LocalDateTime.now();
        List<AiCallBrief> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row((long) i, "dialog", "success", 1, 1, 0, 0, i, now.minusMinutes(i)));
        }
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(rows);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 30);
        assertEquals(19L, vo.p95DurationMs());
        assertEquals(20L, vo.maxDurationMs());
    }

    /**
     * 扫描行数上限：返回行数达到上限时标记 sampled=true（前端据此提示「按样本统计」），
     * 并且下传给仓库的 Pageable 必须带上限——否则 7 天窗口会把整张表读进内存。
     */
    @Test
    void scanCapMarksSampledAndBoundsQuery() {
        int cap = AiRuntimeStatusService.MAX_SCAN_ROWS;
        LocalDateTime now = LocalDateTime.now();
        List<AiCallBrief> rows = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            rows.add(row((long) i + 1, "dialog", "success", 1, 1, 0, 0, 5, now.minusMinutes(i % 1000)));
        }
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(rows);

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 10080);
        assertTrue(vo.sampled(), "达到扫描上限必须标记为样本统计");
        assertEquals(cap, vo.total());

        Mockito.verify(repo).findBriefInWindow(Mockito.eq(1L), Mockito.any(),
                Mockito.argThat((Pageable p) -> p != null && p.getPageSize() == AiRuntimeStatusService.MAX_SCAN_ROWS));
    }

    /** 未达上限时 sampled=false（正常窗口不应谎称样本） */
    @Test
    void underCapIsNotSampled() {
        ActorAiCallLogRepository repo = Mockito.mock(ActorAiCallLogRepository.class);
        AiCallTraceService trace = Mockito.mock(AiCallTraceService.class);
        Mockito.when(trace.isEnabled()).thenReturn(true);
        Mockito.when(repo.findBriefInWindow(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(row(1L, "dialog", "success", 1, 1, 0, 0, 5, LocalDateTime.now())));

        AiRuntimeStatusVO vo = new AiRuntimeStatusService(repo, trace).status(1L, 30);
        assertFalse(vo.sampled());
        assertTrue(vo.traceEnabled());
    }
}
