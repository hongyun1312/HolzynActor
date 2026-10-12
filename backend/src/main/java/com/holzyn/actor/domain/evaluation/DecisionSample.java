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

package com.holzyn.actor.domain.evaluation;

/**
 * 评估样本。
 * <p>字段取自 {@code actor_ai_call_log}（或外部 ai-calls.jsonl 同构行），judge 仅依据
 * 本快照打分（离线、可回放、与实时世界解耦）。</p>
 *
 * @param source 样本来源：db / jsonl
 *
 * @param npcRef 角色引用（special:{id}/crowd:{id}；无归属 null）
 *
 * @param npcName 角色名（无归属 null）
 *
 * @param scene 调用场景（决策类 scene）
 *
 * @param callStatus 原调用状态（success/degraded/failed）
 *
 * @param gameSecond 游戏秒位置（来自 detail_json 摘录，不可得 null）
 *
 * @param stimulusText 刺激/情境文本（user_prompt 摘录）
 *
 * @param contextText 决策上下文（system_prompt 摘录）
 *
 * @param responseText AI 决策输出（response 全文）
 *
 * @param traceId 原 trace_id（回查链路用）
 */
public record DecisionSample(
        String source,
        String npcRef,
        String npcName,
        String scene,
        String callStatus,
        Long gameSecond,
        String stimulusText,
        String contextText,
        String responseText,
        String traceId) {
}
