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

package com.holzyn.actor.domain.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Prompt 工程指标服务。
 * <p>按场景（scene）统计 AI 调用链的<b>指令遵循度与缓存效率</b>四项指标：</p>
 * <ul>
 * <li><b>版本</b>：模板版本号（场景最后出现的版本，来自 {@link PromptSpecRegistry}）；</li>
 * <li><b>token 消耗</b>：prompt/completion + 前缀缓存命中/未命中（cacheHit/cacheMiss 累计）；</li>
 * <li><b>格式合规率</b>：calls / jsonOk / jsonFail / fallback（程序化回退）/ blank / degraded；
 * 合规率 = jsonOk ÷ calls（gateway/解析器埋点）；</li>
 * <li><b>决策与角色一致性</b>：judge 一致性抽样评分记录（复用 评估基础设施的评分，
 * 此处仅登记最近批次均值，供调试页展示——真实批跑走 /api/projects/{id}/test/eval-runs）。</li>
 * </ul>
 * <p>统计为内存态（重启清零，与 GuardRailTracker 同风格）；最近告警环形 200 条（新→旧）。
 * 各埋点方法均空值安全，失败静默不阻断业务。</p>
 */
@Slf4j
@Service
public class PromptMetricsService {

    /** 单场景计数器（LongAdder 线程安全累计）。 */
    public static final class Counter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder jsonOk = new LongAdder();
        private final LongAdder jsonFail = new LongAdder();
        private final LongAdder fallback = new LongAdder();
        private final LongAdder blankOutputs = new LongAdder();
        private final LongAdder degraded = new LongAdder();
        private final LongAdder cacheHitTokens = new LongAdder();
        private final LongAdder cacheMissTokens = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        /** 出现过的模板版本集合。 */
        private final Set<Integer> versions = ConcurrentHashMap.newKeySet();
        /** 最近一次一致性 judge 均值（可空）。 */
        private volatile Double lastConsistencyAvg;
        /** 一致性评分样本数。 */
        private final LongAdder consistencySamples = new LongAdder();
    }

    /** 告警/合规事件（环形缓冲）。 */
    public record MetricEvent(LocalDateTime time, String scene, String type, String message) {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String toString() {
            return FMT.format(time) + " [" + scene + "] " + type + " " + message;
        }
    }

    /** 最近告警环形上限。 */
    private static final int ALERT_RING = 200;

    /** 场景 → 计数器。 */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** 最近合规/告警事件（环形同步缓冲，新→旧）。 */
    private final ArrayDeque<MetricEvent> alerts = new ArrayDeque<>();

    // ==================== 埋点 ====================

    /** 记录一次 AI 调用（calls+1，token 累计）。 */
    public void recordCall(String scene, int promptTokens, int completionTokens, int cacheHit, int cacheMiss) {
        Counter c = counter(scene);
        c.calls.increment();
        if (promptTokens > 0) {
            c.promptTokens.add(promptTokens);
        }
        if (completionTokens > 0) {
            c.completionTokens.add(completionTokens);
        }
        if (cacheHit > 0) {
            c.cacheHitTokens.add(cacheHit);
        }
        if (cacheMiss > 0) {
            c.cacheMissTokens.add(cacheMiss);
        }
        if (cacheHit > 0 || cacheMiss > 0) {
            push(scene, "cache", "本次调用前缀缓存命中=" + cacheHit + " tokens，未命中=" + cacheMiss);
        }
    }

    /** 记录 JSON 解析成功（指令遵循）。 */
    public void recordJsonOk(String scene) {
        counter(scene).jsonOk.increment();
    }

    /** 记录 JSON 解析失败（未遵循格式）。 */
    public void recordJsonFail(String scene, String reason) {
        Counter c = counter(scene);
        c.jsonFail.increment();
        push(scene, "format_fail", reason == null || reason.isBlank() ? "AI 输出不符合输出契约" : reason);
    }

    /** 记录程序化回退（AI 失败/不合规后走程序化兜底）。 */
    public void recordFallback(String scene, String reason) {
        counter(scene).fallback.increment();
        push(scene, "fallback", reason == null || reason.isBlank() ? "程序化回退" : reason);
    }

    /** 记录空输出。 */
    public void recordBlank(String scene) {
        counter(scene).blankOutputs.increment();
        push(scene, "blank", "AI 输出为空（护栏拦截）");
    }

    /** 记录降级。 */
    public void recordDegraded(String scene) {
        counter(scene).degraded.increment();
    }

    /** 记录模板版本（调试页「版本」指标）。 */
    public void recordVersion(String scene, int version) {
        if (version > 0) {
            counter(scene).versions.add(version);
        }
    }

    /** 记录一次 judge 一致性抽样评分。 */
    public void recordConsistency(String scene, double avg, int samples) {
        if (scene == null || scene.isBlank()) {
            return;
        }
        Counter c = counter(scene);
        c.lastConsistencyAvg = avg;
        c.consistencySamples.add(Math.max(0, samples));
    }

    // ==================== 查询 ====================

    /** 单场景快照（调试页）。 */
    public Map<String, Object> snapshot(String scene) {
        Counter c = counters.get(scene);
        if (c == null) {
            return Map.of("scene", scene == null ? "" : scene);
        }
        long calls = c.calls.sum();
        long jsonOk = c.jsonOk.sum();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("scene", scene == null ? "" : scene);
        m.put("versions", new ArrayList<>(c.versions).stream().sorted().collect(Collectors.toList()));
        m.put("calls", calls);
        m.put("jsonOk", jsonOk);
        m.put("jsonFail", c.jsonFail.sum());
        m.put("fallback", c.fallback.sum());
        m.put("blankOutputs", c.blankOutputs.sum());
        m.put("degraded", c.degraded.sum());
        m.put("complianceRate", calls == 0 ? null : Math.round(jsonOk * 1000.0 / calls) / 10.0);
        m.put("promptTokens", c.promptTokens.sum());
        m.put("completionTokens", c.completionTokens.sum());
        m.put("cacheHitTokens", c.cacheHitTokens.sum());
        m.put("cacheMissTokens", c.cacheMissTokens.sum());
        m.put("consistencyAvg", c.lastConsistencyAvg);
        m.put("consistencySamples", c.consistencySamples.sum());
        return m;
    }

    /** 全部场景快照（调试页全表）。 */
    public List<Map<String, Object>> snapshotAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        counters.keySet().stream().sorted().forEach(scene -> out.add(snapshot(scene)));
        return out;
    }

    /** 最近合规/告警事件（新→旧，上限 ALERT_RING）。 */
    public synchronized List<MetricEvent> recentAlerts() {
        return new ArrayList<>(alerts);
    }

    // ==================== 内部 ====================

    private Counter counter(String scene) {
        return counters.computeIfAbsent(scene == null || scene.isBlank() ? "(unknown)" : scene, k -> new Counter());
    }

    /** 环形推送（新事件插入头部，超限裁剪尾部；同步保证 ArrayDeque 线程安全）。 */
    private synchronized void push(String scene, String type, String message) {
        alerts.addFirst(new MetricEvent(LocalDateTime.now(),
                scene == null || scene.isBlank() ? "(unknown)" : scene, type, message));
        while (alerts.size() > ALERT_RING) {
            alerts.removeLast();
        }
    }
}
