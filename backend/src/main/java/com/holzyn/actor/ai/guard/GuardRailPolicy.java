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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一护栏策略对象。
 * <p>职责：全项目 AI 调用的<b>唯一护栏策略源</b>——把各场景分散的失败处理规则收敛为
 * 一个可配置、可覆盖、可观测的策略对象：</p>
 * <ul>
 * <li><b>max-retry</b>：每场景重试上限（业务侧 attempt 循环解析 {@link #maxRetries}，
 * 默认 2 与现状一致，行为不回退）；</li>
 * <li><b>空输出检测</b>：{@link #isBlankOutput}——trim 后长度低于阈值视为空输出
 * （网关拦截 → 视为失败 → 业务侧程序化兜底）；</li>
 * <li><b>输出重复检测</b>：{@link #isDuplicate}——同一链路连续 N 次输出两两相似判定复读机
 * （网关拦截 → 停重试 + 告警 + 降级，防 AI 复读死循环）；</li>
 * <li><b>轮次硬上限</b>：{@link #maxConsecutiveFailures}——单链路连续失败上限（超限告警）；</li>
 * <li><b>失败分类</b>：{@link #classify}——按异常消息归类（timeout/network/empty/duplicate/other），
 * 供统计与告警细分；</li>
 * <li><b>场景可覆盖</b>：yml {@code scenes} 表 + 调试页运行时覆盖表（{@link GuardRailTracker}，
 * 优先级 运行时 &gt; yml 场景 &gt; 默认）。</li>
 * </ul>
 * <p>职责边界：本对象只做<b>决策与检测</b>（纯读配置 + 记状态），<b>执行</b>由两处承担——
 * 网关（空输出/重复检测拦截，非流式硬拦截、流式仅告警）与业务侧 attempt 循环（重试执行，
 * 上限解析本对象）。超时硬兜底已由 {@code Http11OpenAiHttpClient} 看门狗在 HTTP 层保证。</p>
 */
@Slf4j
@Component
public class GuardRailPolicy {

    /** 失败分类（供统计/告警细分与重试语义参考） */
    public enum FailureKind {
        /** 网络/连接类失败 */
        NETWORK,
        /** 超时（读/连接/请求总时长） */
        TIMEOUT,
        /** 空输出（护栏检测命中） */
        EMPTY,
        /** 输出重复（护栏检测命中） */
        DUPLICATE,
        /** 其他（格式/解析/供应商错误等） */
        OTHER
    }

    private final GuardRailProperties props;
    private final GuardRailTracker tracker;

    public GuardRailPolicy(GuardRailProperties props, GuardRailTracker tracker) {
        this.props = props;
        this.tracker = tracker;
    }

    /**
     * 场景生效后的护栏参数（默认 + yml 场景覆盖 + 运行时覆盖合并结果）。
     *
     * @param scene 场景
     */
    public Effective effective(String scene) {
        GuardRailProperties.SceneGuard yml = scene == null ? null : props.getScenes().get(scene);
        GuardRailProperties.SceneGuard runtime = tracker.overrideOf(scene);
        return new Effective(
                firstNonZero(runtime != null ? runtime.getMaxRetries() : null,
                        yml != null ? yml.getMaxRetries() : null, props.getDefaultMaxRetries()),
                firstNonZero(runtime != null ? runtime.getMinOutputChars() : null,
                        yml != null ? yml.getMinOutputChars() : null, props.getMinOutputChars()),
                firstNonNull(runtime != null ? runtime.getDuplicateEnabled() : null,
                        yml != null ? yml.getDuplicateEnabled() : null, props.isDuplicateEnabled()),
                firstNonZero(runtime != null ? runtime.getDuplicateWindow() : null,
                        yml != null ? yml.getDuplicateWindow() : null, props.getDuplicateWindow()),
                firstNonNull(runtime != null ? runtime.getDuplicateDiceThreshold() : null,
                        yml != null ? yml.getDuplicateDiceThreshold() : null, props.getDuplicateDiceThreshold()),
                firstNonZero(runtime != null ? runtime.getMaxConsecutiveFailures() : null,
                        yml != null ? yml.getMaxConsecutiveFailures() : null, props.getMaxConsecutiveFailures()));
    }

    /** 护栏总开关 */
    public boolean enabled() {
        return props.isEnabled();
    }

    /** 该场景重试上限（业务侧 attempt 循环解析用）。 */
    public int maxRetries(String scene) {
        return effective(scene).maxRetries();
    }

    /**
     * 空输出判定：文本 trim 后长度低于该场景阈值（默认 1=全空判空）。
     *
     * @param scene 场景
     *
     * @param text 输出文本
     *
     * @return true=视为空输出
     */
    public boolean isBlankOutput(String scene, String text) {
        int min = effective(scene).minOutputChars();
        return text == null || text.trim().length() < min;
    }

    /**
     * 输出重复检测：连续 N 次两两相似判定复读机（命中会记统计 + 告警）。
     *
     * @param scene 场景
     *
     * @param traceId 调用链路 trace_id（分组维度）
     *
     * @param text 输出文本
     *
     * @return true=判定复读机（调用方按失败处置）
     */
    public boolean isDuplicate(String scene, String traceId, String text) {
        Effective eff = effective(scene);
        if (!eff.duplicateEnabled()) {
            return false;
        }
        boolean hit = tracker.checkDuplicate(scene, traceId, text, eff.duplicateWindow(),
                eff.duplicateDiceThreshold(), props.getDuplicateMaxTextChars());
        if (hit) {
            tracker.recordDuplicate(scene);
            tracker.alert(scene, "duplicate",
                    "连续 " + eff.duplicateWindow() + " 次输出两两相似（Dice≥" + eff.duplicateDiceThreshold()
                            + "），判定复读机，traceId=" + (traceId == null ? "-" : traceId));
        }
        return hit;
    }

    /** 该场景连续失败硬上限（超限建议告警/中断）。 */
    public int maxConsecutiveFailures(String scene) {
        return effective(scene).maxConsecutiveFailures();
    }

    /**
     * 失败分类（供统计/告警细分）：按异常消息关键词归类。
     *
     * @param t 异常（可空=OTHER）
     *
     * @return 分类
     */
    public FailureKind classify(Throwable t) {
        if (t == null) {
            return FailureKind.OTHER;
        }
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("超时")) {
            return FailureKind.TIMEOUT;
        }
        if (msg.contains("connect") || msg.contains("连接") || msg.contains("网络")
                || msg.contains("socket") || msg.contains("read timed")) {
            return FailureKind.NETWORK;
        }
        if (msg.contains("护栏拦截") && msg.contains("为空")) {
            return FailureKind.EMPTY;
        }
        if (msg.contains("护栏拦截") && msg.contains("重复")) {
            return FailureKind.DUPLICATE;
        }
        return FailureKind.OTHER;
    }

    /** 生效参数不可变快照（合并后的最终值）。 */
    public record Effective(int maxRetries, int minOutputChars, boolean duplicateEnabled, int duplicateWindow,
                            double duplicateDiceThreshold, int maxConsecutiveFailures) {
    }

    private static int firstNonZero(Integer a, Integer b, int def) {
        if (a != null && a > 0) {
            return a;
        }
        if (b != null && b > 0) {
            return b;
        }
        return def;
    }

    private static double firstNonNull(Double a, Double b, double def) {
        return a != null ? a : (b != null ? b : def);
    }

    private static boolean firstNonNull(Boolean a, Boolean b, boolean def) {
        return a != null ? a : (b != null ? b : def);
    }
}
