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

package com.holzyn.actor.domain.debug.vo;

import java.time.LocalDateTime;

/**
 * AI 调用留痕的**统计投影**（只含聚合所需字段，不含 Prompt / Response 大文本）。
 *
 * <p>为什么必须要有它：{@code actor_ai_call_log} 的 {@code content} / {@code system_prompt} /
 * {@code user_prompt} / {@code response} 四列都是 LONGTEXT（单条可达 2 万字）。观测域的
 * 「AI 运行时状态」每 8 秒轮询一次、且要按窗口扫描成千上万条记录——若直接加载实体，
 * 每次轮询会搬运数百 MB 文本，足以拖垮一个正在运行的世界。用 JPQL 构造表达式只取
 * 统计字段后，单条行只有几十字节。</p>
 *
 * <p>字段与 {@code ActorAiCallLog} 同名同序，便于 JPQL 构造表达式书写与映射核对。</p>
 *
 * @param id           主键（前端列表 key）
 *
 * @param traceId      留痕 ID（跨端排查用）
 *
 * @param scene        场景标识（如 dialog / action_gen；可为空）
 *
 * @param model        模型名（可为空）
 *
 * @param status       状态字面量（success / error / timeout / ...）
 *
 * @param characterId  发起调用的角色 ID（可为空）
 *
 * @param tokenIn      输入 Token
 *
 * @param tokenOut     输出 Token
 *
 * @param cacheHit     缓存命中 Token
 *
 * @param cacheMiss    缓存未命中 Token
 *
 * @param durationMs   耗时（毫秒）
 *
 * @param createdAt    调用时间
 *
 * @param errorMessage 失败原因（成功时为空；TEXT 列，失败行才可能较长）
 */
public record AiCallBrief(
        Long id,
        String traceId,
        String scene,
        String model,
        String status,
        Long characterId,
        Integer tokenIn,
        Integer tokenOut,
        Integer cacheHit,
        Integer cacheMiss,
        Integer durationMs,
        LocalDateTime createdAt,
        String errorMessage) {
}
