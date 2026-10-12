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

package com.holzyn.actor.domain.observability.vo;

import java.util.List;

/**
 * AI 运行时状态视图（只读聚合）。
 *
 * <p>职责：把「某项目最近一段时间窗口内的 AI 调用」聚合为前端顶栏状态条 / AI 任务中心
 * 可直接渲染的一个容器对象——总量、Token 与缓存命中、时延分位、按场景与状态分布、
 * 最近调用流水与失败明细。</p>
 *
 * <p>数据来源：{@code actor_ai_call_log}（AI trace 表）。该表受
 * {@code holzyn.actor.debug.ai-log.enabled} 开关控制（默认 true）：关闭时无记录，此时
 * {@link #traceEnabled()} 为 false、各统计为 0/空列表——前端据此显示「调用留痕未开启」
 * 而不是「世界没有 AI 调用」。</p>
 *
 * @param projectId     项目 ID
 * @param windowMinutes 统计窗口（分钟）
 * @param from          窗口起点（ISO-8601 文本）
 * @param generatedAt   生成本视图的时刻（ISO-8601 文本）
 * @param traceEnabled  AI 调用留痕是否开启（false 时下列统计不可采信）
 * @param degraded      本次聚合是否发生降级（查询失败 → 统计为空但留痕已开启；前端应提示「统计暂不可用」）
 * @param sampled       统计是否基于「最近 N 行样本」而非窗口全量（窗口调用数超过扫描上限时为 true；
 *                      前端应提示「按最近 N 次调用统计」，避免把样本当全量）
 * @param total         窗口内调用总数（sampled=true 时为样本内数量）
 * @param success       成功数
 * @param failed        失败数
 * @param tokenIn       输入 token 合计
 * @param tokenOut      输出 token 合计
 * @param tokenTotal    输入 + 输出
 * @param cacheHit      缓存命中合计
 * @param cacheMiss     缓存未命中合计
 * @param cacheHitRate  缓存命中率（0~1，无样本时 0）
 * @param avgDurationMs 平均耗时（毫秒，四舍五入）
 * @param p95DurationMs P95 耗时（毫秒）
 * @param maxDurationMs 最大耗时（毫秒）
 * @param byScene       按场景分布（次数降序）
 * @param byStatus      按状态分布
 * @param recent        最近调用（倒序，最多 {@code RECENT_LIMIT} 条）
 * @param failures      窗口内失败调用（倒序，最多 {@code FAILURE_LIMIT} 条）
 */
public record AiRuntimeStatusVO(
        Long projectId,
        int windowMinutes,
        String from,
        String generatedAt,
        boolean traceEnabled,
        boolean degraded,
        boolean sampled,
        long total,
        long success,
        long failed,
        long tokenIn,
        long tokenOut,
        long tokenTotal,
        long cacheHit,
        long cacheMiss,
        double cacheHitRate,
        long avgDurationMs,
        long p95DurationMs,
        long maxDurationMs,
        List<SceneStat> byScene,
        List<StatusStat> byStatus,
        List<CallItem> recent,
        List<CallItem> failures
) {

    /** 最近调用条数上限 */
    public static final int RECENT_LIMIT = 20;
    /** 失败明细条数上限 */
    public static final int FAILURE_LIMIT = 10;

    /**
     * 场景维度统计。
     *
     * @param scene       场景标识（如 dialog / action_gen）
     * @param count       调用次数
     * @param tokenIn     输入 token
     * @param tokenOut    输出 token
     * @param avgDurationMs 平均耗时
     * @param failed      失败次数
     */
    public record SceneStat(String scene, long count, long tokenIn, long tokenOut,
                            long avgDurationMs, long failed) {
    }

    /**
     * 状态维度统计。
     *
     * @param status 状态（success / error 等）
     * @param count  次数
     */
    public record StatusStat(String status, long count) {
    }

    /**
     * 单条调用摘要（不含 Prompt / 响应正文——正文体积大且可能含敏感信息，需要时走调试台详情端点）。
     *
     * @param id           记录 ID
     * @param traceId      决策链路 trace_id
     * @param scene        场景
     * @param model        模型
     * @param status       状态
     * @param characterId  关联角色（可空）
     * @param durationMs   耗时（毫秒）
     * @param tokenIn      输入 token
     * @param tokenOut     输出 token
     * @param cacheHit     是否命中缓存
     * @param createdAt    发生时刻（ISO-8601 文本）
     * @param errorMessage 错误信息（成功时为空）
     */
    public record CallItem(Long id, String traceId, String scene, String model, String status,
                           Long characterId, int durationMs, int tokenIn, int tokenOut,
                           boolean cacheHit, String createdAt, String errorMessage) {
    }
}
