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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import com.holzyn.actor.domain.usage.service.UsageService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调试用量聚合服务（调试前端用量图页）。
 * <p>职责：复用现有 {@code actor_usage_log} 用量数据，按 天/场景/项目 三维聚合
 * （tokenIn/tokenOut/次数/平均耗时），供 ECharts 趋势/占比/分布图表直接消费。</p>
 * <p>数据源与主前端「AI 用量」页一致（同一张表），仅做只读聚合。</p>
 */
@Service
public class DebugUsageStatsService {

    /** 默认统计范围（近 30 天） */
    private static final int DEFAULT_DAYS = 30;

    private final ActorUsageLogRepository usageLogRepository;

    public DebugUsageStatsService(ActorUsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    /**
     * 用量聚合统计。
     *
     * @param days 统计范围（天，默认 30；<=0 按 30）
     */
    public Map<String, Object> stats(int days) {
        int rangeDays = days <= 0 ? DEFAULT_DAYS : Math.min(days, 365);
        LocalDateTime start = LocalDate.now().minusDays(rangeDays - 1L).atStartOfDay();
        LocalDateTime end = LocalDateTime.now();
        List<ActorUsageLog> logs = usageLogRepository
                .findByUserIdAndCreatedAtBetweenOrderByIdDesc(1L, start, end);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rangeDays", rangeDays);
        out.put("total", total(logs));
        out.put("byDay", byDay(logs, start.toLocalDate()));
        out.put("byScene", byScene(logs));
        out.put("byProject", byProject(logs));
        return out;
    }

    /**
     * 总量汇总。
     *
     * @param logs 用量日志列表
     */
    private Map<String, Object> total(List<ActorUsageLog> logs) {
        Map<String, Object> m = new LinkedHashMap<>();
        long count = logs.size();
        long tokenIn = logs.stream().mapToLong(l -> l.getTokenIn() == null ? 0 : l.getTokenIn()).sum();
        long tokenOut = logs.stream().mapToLong(l -> l.getTokenOut() == null ? 0 : l.getTokenOut()).sum();
        long cacheHit = logs.stream().mapToLong(l -> l.getCacheHitTokens() == null ? 0 : l.getCacheHitTokens()).sum();
        long cacheMiss = logs.stream().mapToLong(l -> l.getCacheMissTokens() == null ? 0 : l.getCacheMissTokens()).sum();
        long duration = logs.stream().mapToLong(l -> l.getDurationMs() == null ? 0 : l.getDurationMs()).sum();
        m.put("count", count);
        m.put("tokenIn", tokenIn);
        m.put("tokenOut", tokenOut);
        m.put("cacheHit", cacheHit);
        m.put("cacheMiss", cacheMiss);
        m.put("durationMs", duration);
        m.put("avgDurationMs", count == 0 ? 0 : Math.round(duration * 10.0 / count) / 10.0);
        return m;
    }

    /**
     * 按天聚合（含 0 值日期补齐，趋势图连续）。
     *
     * @param logs 用量日志列表
     *
     * @param fromDate 范围起始日
     */
    private List<Map<String, Object>> byDay(List<ActorUsageLog> logs, LocalDate fromDate) {
        Map<LocalDate, Acc> acc = new LinkedHashMap<>();
        for (ActorUsageLog l : logs) {
            LocalDate day = l.getCreatedAt() == null ? LocalDate.now() : l.getCreatedAt().toLocalDate();
            acc.computeIfAbsent(day, k -> new Acc()).add(l);
        }
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            Acc a = acc.getOrDefault(d, new Acc());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("tokenIn", a.tokenIn);
            m.put("tokenOut", a.tokenOut);
            m.put("count", a.count);
            m.put("avgDurationMs", a.count == 0 ? 0 : Math.round(a.durationMs * 10.0 / a.count) / 10.0);
            list.add(m);
        }
        return list;
    }

    /**
     * 按场景聚合（中文标签，按调用次数降序）。
     *
     * @param logs 用量日志列表
     */
    private List<Map<String, Object>> byScene(List<ActorUsageLog> logs) {
        Map<String, Acc> acc = new LinkedHashMap<>();
        for (ActorUsageLog l : logs) {
            acc.computeIfAbsent(l.getScene(), k -> new Acc()).add(l);
        }
        return acc.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("scene", e.getKey());
                    m.put("sceneName", UsageService.sceneName(e.getKey()));
                    m.put("count", e.getValue().count);
                    m.put("tokenIn", e.getValue().tokenIn);
                    m.put("tokenOut", e.getValue().tokenOut);
                    m.put("avgDurationMs", e.getValue().count == 0 ? 0
                            : Math.round(e.getValue().durationMs * 10.0 / e.getValue().count) / 10.0);
                    return m;
                })
                .sorted(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("count")).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 按项目聚合（projectId 维度，按 token 合计降序；项目名留待 Controller 补全映射）。
     *
     * @param logs 用量日志列表
     */
    private List<Map<String, Object>> byProject(List<ActorUsageLog> logs) {
        Map<Long, Acc> acc = new LinkedHashMap<>();
        for (ActorUsageLog l : logs) {
            Long pid = l.getProjectId() == null ? 0L : l.getProjectId();
            acc.computeIfAbsent(pid, k -> new Acc()).add(l);
        }
        return acc.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projectId", e.getKey());
                    m.put("count", e.getValue().count);
                    m.put("tokenIn", e.getValue().tokenIn);
                    m.put("tokenOut", e.getValue().tokenOut);
                    m.put("durationMs", e.getValue().durationMs);
                    return m;
                })
                .sorted(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("tokenIn")).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 聚合累加器（内部）。
     */
    private static final class Acc {
        long count;
        long tokenIn;
        long tokenOut;
        long durationMs;

        void add(ActorUsageLog l) {
            count++;
            tokenIn += l.getTokenIn() == null ? 0 : l.getTokenIn();
            tokenOut += l.getTokenOut() == null ? 0 : l.getTokenOut();
            durationMs += l.getDurationMs() == null ? 0 : l.getDurationMs();
        }
    }
}
