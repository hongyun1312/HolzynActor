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

package com.holzyn.actor.ai.guard;

import com.holzyn.actor.domain.memory.common.MemoryDedup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 护栏运行时状态。
 * <p>职责：承载「统一护栏策略对象」的<b>有状态</b>部分——</p>
 * <ul>
 * <li><b>运行时覆盖表</b>：调试页可对单个场景热更新护栏参数（内存态，重启还原 application.yml），
 * 优先级高于 yml 场景覆盖表；</li>
 * <li><b>各场景统计</b>：空输出 / 重复命中 / 失败 / 超时 / 降级计数（网关检测点上报，可观测）；</li>
 * <li><b>最近告警环形缓冲</b>：重复命中/连续失败等异常事件留痕（上限 200 条，供调试页回看）；</li>
 * <li><b>输出重复检测窗口</b>：按「scene|traceId」链路维护最近 N 条输出指纹，连续 N 条两两
 * 相似（Dice 二元组 ≥ 阈值）判定复读机——只进不退的滚动窗口，无清理负担（键自然淘汰于
 * 下一轮同键覆盖）。</li>
 * </ul>
 * <p>线程安全：全部 ConcurrentHashMap / 同步告警队列，供引擎虚拟线程 + 决策线程并发上报。</p>
 */
@Slf4j
@Component
public class GuardRailTracker {

    /** 告警环形缓冲上限（超出淘汰最旧） */
    public static final int ALERT_CAPACITY = 200;

    /** 运行时覆盖表（scene → 覆盖参数；内存态，重启还原） */
    private final Map<String, GuardRailProperties.SceneGuard> runtimeOverrides = new ConcurrentHashMap<>();

    /** 各场景统计（scene → 计数器） */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** 最近告警环形缓冲（最旧在队首；同步访问） */
    private final Deque<Alert> alerts = new ArrayDeque<>(ALERT_CAPACITY);

    /** 输出重复检测窗口（key=scene|traceId → 最近输出指纹文本；滚动窗口） */
    private final Map<String, Deque<String>> duplicateWindows = new ConcurrentHashMap<>();

    /**
     * 单场景护栏统计计数器。
     *
     * @param retries 业务侧重试次数（调用方上报，供调试页观察）
     *
     * @param blankOutputs 空输出检测命中次数
     *
     * @param duplicates 输出重复检测命中次数
     *
     * @param failures AI 调用失败次数（网关 catch 上报）
     *
     * @param timeouts 超时失败次数（失败子集，消息含 timeout/超时）
     *
     * @param degraded 标记降级次数（空输出/重复/失败后业务侧程序化兜底，网关在拦截点上报）
     */
    public record Counter(long retries, long blankOutputs, long duplicates, long failures,
                          long timeouts, long degraded) {
        Counter inc(String field) {
            return switch (field) {
                case "retries" -> new Counter(retries + 1, blankOutputs, duplicates, failures, timeouts, degraded);
                case "blank" -> new Counter(retries, blankOutputs + 1, duplicates, failures, timeouts, degraded);
                case "duplicate" -> new Counter(retries, blankOutputs, duplicates + 1, failures, timeouts, degraded);
                case "failure" -> new Counter(retries, blankOutputs, duplicates, failures + 1, timeouts, degraded);
                case "timeout" -> new Counter(retries, blankOutputs, duplicates, failures + 1, timeouts + 1, degraded);
                case "degraded" -> new Counter(retries, blankOutputs, duplicates, failures, timeouts, degraded + 1);
                default -> this;
            };
        }
    }

    /**
     * 护栏告警条目（环形缓冲元素）。
     *
     * @param atMs 发生时间（epoch millis）
     *
     * @param scene 场景
     *
     * @param type 告警类型（duplicate / blank_output / max_consecutive_failures / timeout）
     *
     * @param message 摘要（含 traceId/角色等上下文，截断防膨胀）
     */
    public record Alert(long atMs, String scene, String type, String message) {
    }

    // ==================== 运行时覆盖表 ====================

    /**
     * 设置单场景运行时护栏覆盖（热更新，优先级高于 yml scenes 表；空覆盖=清空该场景）。
     *
     * @param scene 场景
     *
     * @param guard 覆盖参数（任一字段为空=沿用默认）
     */
    public void putOverride(String scene, GuardRailProperties.SceneGuard guard) {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("场景不能为空");
        }
        if (guard == null) {
            runtimeOverrides.remove(scene);
            return;
        }
        runtimeOverrides.put(scene, guard);
    }

    /** 移除单场景运行时覆盖（回到 yml/默认）。 */
    public void removeOverride(String scene) {
        if (scene != null) {
            runtimeOverrides.remove(scene);
        }
    }

    /** 运行时覆盖表快照（复制返回，防外部修改）。 */
    public Map<String, GuardRailProperties.SceneGuard> overrides() {
        return new LinkedHashMap<>(runtimeOverrides);
    }

    /** 取单场景运行时覆盖（无则 null）。 */
    public GuardRailProperties.SceneGuard overrideOf(String scene) {
        return scene == null ? null : runtimeOverrides.get(scene);
    }

    // ==================== 统计 ====================

    /** 上报一次重试（业务侧 attempt 循环调用；null-safe）。 */
    public void recordRetry(String scene) {
        record(scene, "retries");
    }

    /** 上报一次空输出命中（网关拦截点）。 */
    public void recordBlankOutput(String scene) {
        record(scene, "blank");
    }

    /** 上报一次重复命中（网关拦截点）。 */
    public void recordDuplicate(String scene) {
        record(scene, "duplicate");
    }

    /** 上报一次失败（网关 catch 点）。 */
    public void recordFailure(String scene) {
        record(scene, "failure");
    }

    /** 上报一次超时失败（失败子集）。 */
    public void recordTimeout(String scene) {
        record(scene, "timeout");
    }

    /** 上报一次降级（拦截后业务侧程序化兜底；网关在拦截点同记）。 */
    public void recordDegraded(String scene) {
        record(scene, "degraded");
    }

    private void record(String scene, String field) {
        String key = scene == null || scene.isBlank() ? "chat" : scene;
        counters.compute(key, (k, c) -> (c == null ? new Counter(0, 0, 0, 0, 0, 0) : c).inc(field));
    }

    /** 各场景统计快照（复制返回）。 */
    public Map<String, Counter> stats() {
        return new LinkedHashMap<>(counters);
    }

    /** 取单场景统计（无则零计数）。 */
    public Counter statsOf(String scene) {
        Counter c = counters.get(scene == null || scene.isBlank() ? "chat" : scene);
        return c == null ? new Counter(0, 0, 0, 0, 0, 0) : c;
    }

    // ==================== 告警环形缓冲 ====================

    /**
     * 记录一条护栏告警（环形缓冲，上限 {@link #ALERT_CAPACITY} 条，超出淘汰最旧）。
     *
     * @param scene 场景
     *
     * @param type 告警类型
     *
     * @param message 摘要
     */
    public void alert(String scene, String type, String message) {
        String safeScene = scene == null || scene.isBlank() ? "chat" : scene;
        String safeMsg = message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message);
        synchronized (alerts) {
            if (alerts.size() >= ALERT_CAPACITY) {
                alerts.removeFirst();
            }
            alerts.addLast(new Alert(System.currentTimeMillis(), safeScene, type, safeMsg));
        }
        log.warn("[护栏告警] scene={} type={} message={}", safeScene, type, safeMsg);
    }

    /** 最近告警列表（新→旧）。 */
    public List<Alert> recentAlerts() {
        synchronized (alerts) {
            List<Alert> copy = new ArrayList<>(alerts);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    // ==================== 输出重复检测窗口 ====================

    /**
     * 输出重复检测（复读机防护）：按「scene|traceId」链路维护最近 N 条输出指纹，
     * 当窗口已满且新输出与窗口内<b>全部</b>历史输出两两相似（Dice ≥ 阈值）时判定复读机。
     * <p>无论命中与否都会把新输出滚入窗口（保持「最近 N 条」语义）；文本截断到
     * {@code maxTextChars} 参与比较防长文本开销。</p>
     *
     * @param scene 场景（窗口分组维度 1）
     *
     * @param traceId 调用链路 trace_id（窗口分组维度 2；可为 null=按场景分组）
     *
     * @param text 本次输出文本
     *
     * @param window 窗口大小（&lt;=1 关闭检测）
     *
     * @param threshold Dice 相似阈值（0~1）
     *
     * @param maxTextChars 指纹文本截断长度（&lt;=0 不截断）
     *
     * @return true=判定复读机（调用方按失败处置）
     */
    public boolean checkDuplicate(String scene, String traceId, String text, int window, double threshold,
                                  int maxTextChars) {
        if (window <= 1 || text == null || text.isBlank()) {
            return false;
        }
        String key = (scene == null || scene.isBlank() ? "chat" : scene)
                + "|" + (traceId == null ? "" : traceId);
        String fingerprint = maxTextChars > 0 && text.length() > maxTextChars
                ? text.substring(0, maxTextChars) : text;
        Deque<String> windowQ = duplicateWindows.computeIfAbsent(key, k -> new ArrayDeque<>(window + 1));
        synchronized (windowQ) {
            boolean hit = false;
            if (windowQ.size() >= window - 1) {
                // 窗口已积累 window-1 条历史：新输出需与全部历史两两相似才判复读
                hit = !windowQ.isEmpty() && windowQ.stream()
                        .allMatch(hist -> MemoryDedup.overlapScore(fingerprint, hist) >= threshold);
            }
            windowQ.addLast(fingerprint);
            while (windowQ.size() > window) {
                windowQ.removeFirst();
            }
            return hit;
        }
    }
}
