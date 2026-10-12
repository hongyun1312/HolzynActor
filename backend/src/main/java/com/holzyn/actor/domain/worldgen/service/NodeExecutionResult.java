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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;

/**
 * 节点执行结果（一次节点执行的产品，由执行器返回、TaskRunner 落 actor_worldgen_node_attempt）。
 * <p>职责：携带节点执行状态（succeeded/failed）、输出 JSON、错误信息与节点级 Token 账本
 * （桩执行器 token=0； 真实 AI 由 AiCallContext.run(scene="worldgen", trace_id=taskId) 记账）。</p>
 *
 * @param outputJson 输出 JSON（成功后的产物；失败可为 null）
 *
 * @param error 错误信息（失败时填充）
 *
 * @param tokenIn 输入 Token 数
 *
 * @param tokenOut 输出 Token 数
 *
 * @param durationMs 执行耗时（毫秒）
 */
public record NodeExecutionResult(
        String status,
        String outputJson,
        String error,
        int tokenIn,
        int tokenOut,
        long durationMs) {

    /**
     * 构造成功结果。
     *
     * @param outputJson 输出 JSON
     *
     * @param durationMs 耗时（毫秒）
     *
     * @return 成功结果
     */
    public static NodeExecutionResult success(String outputJson, long durationMs) {
        return new NodeExecutionResult(WorldGenStatus.SUCCEEDED, outputJson, null, 0, 0, durationMs);
    }

    /**
     * 构造成功结果。
     *
     * @param outputJson 输出 JSON
     *
     * @param tokenIn 输入 Token 数
     *
     * @param tokenOut 输出 Token 数
     *
     * @param durationMs 耗时（毫秒）
     *
     * @return 成功结果
     */
    public static NodeExecutionResult success(String outputJson, int tokenIn, int tokenOut, long durationMs) {
        return new NodeExecutionResult(WorldGenStatus.SUCCEEDED, outputJson, null, tokenIn, tokenOut, durationMs);
    }

    /**
     * 构造失败结果。
     *
     * @param error 错误信息
     *
     * @return 失败结果
     */
    public static NodeExecutionResult failure(String error) {
        return new NodeExecutionResult(WorldGenStatus.FAILED, null, error, 0, 0, 0);
    }
}
