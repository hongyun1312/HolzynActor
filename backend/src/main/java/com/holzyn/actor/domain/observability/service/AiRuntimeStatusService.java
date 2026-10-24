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

import com.holzyn.actor.domain.debug.vo.AiCallBrief;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.observability.vo.AiRuntimeStatusVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 运行时状态聚合服务（观测域·只读）。
 *
 * <p>职责：把 {@code actor_ai_call_log} 在给定时间窗口内的记录聚合为前端可渲染的状态对象
 * （{@link AiRuntimeStatusVO}）——总量/成功失败、Token 与缓存命中、时延均值与 P95、
 * 按场景与状态分布、最近调用与失败明细。</p>
 *
 * <p>架构不变量：</p>
 * <ul>
 *   <li><b>纯只读、零 AI</b>：只查不写，不调用任何模型（不含 AI 调用的方法，故不涉及
 *       「AI 调用方法不得加 @Transactional」的治理约束）。</li>
 *   <li><b>降级不抛</b>：trace 未开启（{@link AiCallTraceService#isEnabled()} 为 false）
 *       时仍返回结构完整的空状态，仅把 {@code traceEnabled} 置 false；仓库异常也只在
 *       日志留痕后返回空状态——顶栏状态条永不因观测失败而中断页面。</li>
 *   <li><b>窗口有界</b>：窗口分钟数按 {@link #MIN_WINDOW_MINUTES}~{@link #MAX_WINDOW_MINUTES}
 *       钳制，避免全表扫描。</li>
 * </ul>
 */
@Service
public class AiRuntimeStatusService {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeStatusService.class);

    /** 窗口下限（分钟） */
    public static final int MIN_WINDOW_MINUTES = 1;
    /** 窗口上限（分钟，7 天；够看「昨天/上周世界跑了什么」，同时仍是有限扫描） */
    public static final int MAX_WINDOW_MINUTES = 7 * 24 * 60;
    /** 默认窗口（分钟） */
    public static final int DEFAULT_WINDOW_MINUTES = 30;
    /**
     * 单次统计的最大扫描行数。
     *
     * <p>为什么需要它：窗口是「时间」维度，不能限制行数——世界全速运行一天可产生数万次调用。
     * 投影已把单行压到几十字节，但 7 天窗口仍可能上百万行。取最近 {@value} 行做统计，
     * 并在 {@link AiRuntimeStatusVO#sampled()} 标记「这是样本」——前端据此显示
     * 「按最近 N 次调用统计」，而不是把样本当全量展示。</p>
     */
    public static final int MAX_SCAN_ROWS = 20000;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ActorAiCallLogRepository callLogRepository;
    private final AiCallTraceService traceService;

    /**
     * 构造服务。
     *
     * @param callLogRepository AI 调用留痕仓库
     *
     * @param traceService      留痕开关（用于区分「未开启」与「无调用」，可为 null——单测/裁剪装配下按未开启处理）
     */
    @Autowired
    public AiRuntimeStatusService(ActorAiCallLogRepository callLogRepository,
                                  @Autowired(required = false) AiCallTraceService traceService) {
        this.callLogRepository = callLogRepository;
        this.traceService = traceService;
    }

    /**
     * 聚合某项目最近一段时间的 AI 运行时状态。
     *
     * @param projectId     项目 ID
     *
     * @param windowMinutes 窗口分钟数（null/越界时钳制到合法区间）
     *
     * @return 状态视图（永不返回 null）
     */
    public AiRuntimeStatusVO status(Long projectId, Integer windowMinutes) {
        int window = clampWindow(windowMinutes);
        LocalDateTime from = LocalDateTime.now().minusMinutes(window);
        boolean traceEnabled = traceService != null && traceService.isEnabled();

        List<AiCallBrief> rows;
        boolean degraded = false;
        boolean sampled = false;
        if (traceEnabled) {
            try {
                // 投影查询：只取统计字段（不含 4 个 LONGTEXT 大列），并限定最近 MAX_SCAN_ROWS 行
                rows = callLogRepository.findBriefInWindow(projectId, from, PageRequest.of(0, MAX_SCAN_ROWS));
                sampled = rows.size() >= MAX_SCAN_ROWS;
            } catch (RuntimeException e) {
                // 观测失败绝不阻断页面：降级为空窗口并留痕（degraded=true 让前端区分「无调用」与「统计不可用」）
                log.warn("[observability] AI 运行时状态查询失败，降级为空窗口：project={}, err={}",
                        projectId, e.getMessage());
                rows = List.of();
                degraded = true;
            }
        } else {
            rows = List.of();
        }
        return aggregate(projectId, window, from, traceEnabled, degraded, sampled, rows);
    }

    /**
     * 纯函数聚合（单测直接驱动，不触碰仓库）。
     *
     * @param projectId    项目 ID
     *
     * @param window       已钳制的窗口分钟数
     *
     * @param from         窗口起点
     *
     * @param traceEnabled 留痕是否开启
     *
     * @param degraded     是否发生了降级（查询失败）
     *
     * @param sampled      统计是否基于「最近 N 行样本」而非窗口全量
     *
     * @param rows         窗口内记录投影（null 视为空）
     *
     * @return 状态视图
     */
    AiRuntimeStatusVO aggregate(Long projectId, int window, LocalDateTime from, boolean traceEnabled,
                                boolean degraded, boolean sampled, List<AiCallBrief> rows) {
        List<AiCallBrief> list = rows == null ? List.of() : rows;

        long total = list.size();
        long success = 0;
        long failed = 0;
        long tokenIn = 0;
        long tokenOut = 0;
        long cacheHit = 0;
        long cacheMiss = 0;
        long durationSum = 0;
        long maxDuration = 0;
        List<Long> durations = new ArrayList<>(list.size());
        Map<String, long[]> sceneAgg = new LinkedHashMap<>();
        Map<String, Long> statusAgg = new LinkedHashMap<>();

        for (AiCallBrief r : list) {
            int tin = nz(r.tokenIn());
            int tout = nz(r.tokenOut());
            int dur = nz(r.durationMs());
            tokenIn += tin;
            tokenOut += tout;
            int hit = nz(r.cacheHit());
            int miss = nz(r.cacheMiss());
            // 兼容网关只回传命中不回传未命中（miss 缺失记 0）时按「prompt = hit + miss」推算，
            // 避免命中率恒为 100%（与 UsageService.effectiveMiss 同口径）
            if (miss == 0 && hit > 0 && tin > hit) {
                miss = tin - hit;
            }
            cacheHit += hit;
            cacheMiss += miss;
            durationSum += dur;
            maxDuration = Math.max(maxDuration, dur);
            durations.add((long) dur);

            boolean ok = !isFailure(r);
            if (ok) {
                success++;
            } else {
                failed++;
            }

            String scene = r.scene() == null || r.scene().isBlank() ? "(未标注)" : r.scene();
            long[] agg = sceneAgg.computeIfAbsent(scene, k -> new long[5]);
            agg[0]++;
            agg[1] += tin;
            agg[2] += tout;
            agg[3] += dur;
            if (!ok) {
                agg[4]++;
            }

            String status = r.status() == null || r.status().isBlank() ? "unknown" : r.status();
            statusAgg.merge(status, 1L, Long::sum);
        }

        long avgDuration = total == 0 ? 0 : Math.round((double) durationSum / total);
        long p95 = percentile95(durations);
        long cacheTotal = cacheHit + cacheMiss;

        List<AiRuntimeStatusVO.SceneStat> byScene = sceneAgg.entrySet().stream()
                .map(e -> new AiRuntimeStatusVO.SceneStat(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[0] == 0 ? 0 : e.getValue()[3] / e.getValue()[0], e.getValue()[4]))
                .sorted(Comparator.comparingLong(AiRuntimeStatusVO.SceneStat::count).reversed())
                .toList();

        List<AiRuntimeStatusVO.StatusStat> byStatus = statusAgg.entrySet().stream()
                .map(e -> new AiRuntimeStatusVO.StatusStat(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(AiRuntimeStatusVO.StatusStat::count).reversed())
                .toList();

        // 最近调用：窗口内按发生时刻倒序（仓库已按 id 升序返回，这里再按时间兜底排序）
        List<AiCallBrief> desc = new ArrayList<>(list);
        desc.sort(Comparator.comparing(AiCallBrief::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        List<AiRuntimeStatusVO.CallItem> recent = desc.stream()
                .limit(AiRuntimeStatusVO.RECENT_LIMIT)
                .map(this::toItem)
                .toList();

        List<AiRuntimeStatusVO.CallItem> failures = desc.stream()
                .filter(this::isFailure)
                .limit(AiRuntimeStatusVO.FAILURE_LIMIT)
                .map(this::toItem)
                .toList();

        return new AiRuntimeStatusVO(projectId, window, from.format(ISO), LocalDateTime.now().format(ISO),
                traceEnabled, degraded, sampled, total, success, failed, tokenIn, tokenOut, tokenIn + tokenOut,
                cacheHit, cacheMiss, cacheTotal == 0 ? 0d : (double) cacheHit / cacheTotal,
                avgDuration, p95, maxDuration, byScene, byStatus, recent, failures);
    }

    /** 单条记录 → 调用摘要 */
    private AiRuntimeStatusVO.CallItem toItem(AiCallBrief r) {
        return new AiRuntimeStatusVO.CallItem(r.id(), r.traceId(), r.scene(), r.model(),
                r.status(), r.characterId(), nz(r.durationMs()), nz(r.tokenIn()), nz(r.tokenOut()),
                nz(r.cacheHit()) > 0, r.createdAt() == null ? null : r.createdAt().format(ISO),
                r.errorMessage());
    }

    /**
     * 判定失败：状态非 success 一律计失败（含 error / timeout / 未知状态），
     * 但 trace 的**非 AI 环节行**（stepType 非 ai_call 且有错误信息）不参与成功/失败统计之外的口径——
     * 这里保持简单口径：status 为空视为成功占位行不判失败。
     */
    private boolean isFailure(AiCallBrief r) {
        String s = r.status();
        if (s == null || s.isBlank()) {
            return false;
        }
        return !"success".equalsIgnoreCase(s.trim());
    }

    /** 就近取整的 P95（样本 < 1 时返回 0；四舍五入到整数毫秒） */
    private long percentile95(List<Long> durations) {
        if (durations.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(durations);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** 窗口钳制 */
    private int clampWindow(Integer windowMinutes) {
        if (windowMinutes == null) {
            return DEFAULT_WINDOW_MINUTES;
        }
        return Math.max(MIN_WINDOW_MINUTES, Math.min(MAX_WINDOW_MINUTES, windowMinutes));
    }

    /** Integer 空值归零 */
    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
