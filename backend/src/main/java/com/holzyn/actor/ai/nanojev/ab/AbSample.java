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

package com.holzyn.actor.ai.nanojev.ab;

import com.holzyn.actor.ai.nanojev.NanoJevQuestion;

/**
 * NanoJev A/B 判定样本。
 * <p>一条样本 = 一段真实情境（来自青禾村 actor_ai_call_log 历史调用）+ 一道 NanoJev
 * 三题型判定题（choice/boolean/score）。同一条题面同时喂给 NanoJev（evaluate 分布）与
 * Qwen（同题型重问选中项），比较两者 top-1 是否一致。</p>
 *
 * @param scene 目标场景：emotion_eval / crowd_decision / social_willingness
 *
 * @param questionType 题型：score / choice / boolean
 *
 * @param npcRef 角色引用（npc:{id}；可空）
 *
 * @param npcName 角色名（可空）
 *
 * @param stateText 情境文本（NanoJev state 共享上下文；Qwen 重问的 user 输入主体）
 *
 * @param question 判定题
 *
 * @param sourceScene 真实情境来源 scene（actor_ai_call_log.scene；构造样本空）
 *
 * @param traceId 原 trace_id（回查链路；可空）
 */
public record AbSample(String scene, String questionType, String npcRef, String npcName,
                       String stateText, NanoJevQuestion question, String sourceScene, String traceId) {

    /** 目标场景码常量（与 PromptSpecRegistry nanojev_* 场景一一对应）。 */
    public static final String SCENE_EMOTION_EVAL = "emotion_eval";
    /** 候选选择场景码常量 */
    public static final String SCENE_CROWD_DECISION = "crowd_decision";
    /** 社交意愿场景码常量 */
    public static final String SCENE_SOCIAL_WILLINGNESS = "social_willingness";

    /** 对应 NanoJev 场景码（PromptSpecRegistry 登记）：nanojev_{scene}。 */
    public String nanojevScene() {
        return "nanojev_" + scene;
    }
}
