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

package com.holzyn.actor.ai;

/**
 * AI 调用用量明细（含上下文缓存命中统计）。
 * <p>用途：承载一次 Chat Completions 调用的 token 用量，以及 OpenAI 兼容接口
 * （DeepSeek / 火山方舟等）返回的上下文缓存命中/未命中 token
 * （prompt_cache_hit_tokens / prompt_cache_miss_tokens），供流式回调与用量日志落库。
 * 缓存命中部分按平台折扣价计费，统计命中率是优化成本的依据。</p>
 *
 * @param promptTokens 输入 token 数（可空，部分供应商不返回）
 *
 * @param completionTokens 输出 token 数（可空）
 *
 * @param cacheHitTokens 输入中命中上下文缓存的 token 数（可空）
 *
 * @param cacheMissTokens 输入中未命中缓存、需全量计算的 token 数（可空）
 */
public record AiUsage(Integer promptTokens, Integer completionTokens,
                      Integer cacheHitTokens, Integer cacheMissTokens) {
}