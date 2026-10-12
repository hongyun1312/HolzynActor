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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.domain.agent.prompt.PromptMetricsService;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 工程调试接口。
 * <p>调试前端「Prompt 工程」页数据源：模板规格注册表（场景/版本/输出契约/温度/输出上限）
 * + 各场景指标（调用次数/token 消耗/前缀缓存命中/格式合规率/一致性抽样）+ 最近合规/告警事件。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/prompt-metrics")
@RequiredArgsConstructor
public class PromptMetricsController {

    private final PromptMetricsService promptMetrics;

    /**
     * 综合状态（调试页一次性加载：规格表 + 全场景统计 + 最近事件）。
     */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("specs", specs());
        out.put("stats", promptMetrics.snapshotAll());
        out.put("recentAlerts", promptMetrics.recentAlerts());
        return out;
    }

    /**
     * 模板规格注册表（场景/版本/输出契约/默认温度/输出上限）。
     *
     * @return 规格行列表
     */
    @GetMapping("/specs")
    public List<Map<String, Object>> specs() {
        List<Map<String, Object>> rows = new ArrayList<>();
        PromptSpecRegistry.all().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    PromptSpecRegistry.PromptSpec s = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("scene", e.getKey());
                    row.put("version", s.version());
                    row.put("contract", s.contract().name());
                    row.put("maxOutputTokens", s.maxOutputTokens());
                    row.put("temperature", s.temperature());
                    rows.add(row);
                });
        return rows;
    }

    /**
     * 各场景统计快照（调用/token/缓存/合规率/一致性）。
     *
     * @return 统计行列表
     */
    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        return promptMetrics.snapshotAll();
    }

    /**
     * 最近合规/告警事件（新→旧）。
     *
     * @return 事件列表
     */
    @GetMapping("/alerts")
    public List<PromptMetricsService.MetricEvent> alerts() {
        return promptMetrics.recentAlerts();
    }
}
