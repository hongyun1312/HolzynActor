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

/**
 * Prompt 输出契约类型。
 * <p>每类场景的输出形态显式化，配合 {@link PromptSpec} 的 maxOutputTokens/temperature
 * 与程序化回退：</p>
 * <ul>
 * <li><b>PLAIN_TEXT</b>：仅输出正文（单聊 SSE 流式通道——流式无法保证 JSON 完整性，
 * 单聊保持流式正文，主前端零改动）；</li>
 * <li><b>DIALOG_JSON</b>：群聊/自主对话逐角色回复 JSON 契约
 * {dialogue, action, emotion_update, internal_thought}（非流式调用，落库结构化）；</li>
 * <li><b>DECISION_JSON</b>：实时决策 ReAct 契约——&lt;decision&gt; 内输出
 * （action_type 枚举 continue/start_action/move_to/speak_to/wait，continue=程序化兜底）；</li>
 * <li><b>SCHEMA_JSON</b>：其余全部场景统一「严格 JSON Schema 输出 + 全大写强调」。</li>
 * </ul>
 */
public enum PromptContractType {

    /** 纯文本正文（单聊流式） */
    PLAIN_TEXT,

    /** 对话 JSON 契约（群聊/自主对话逐角色） */
    DIALOG_JSON,

    /** 决策 ReAct JSON 契约（实时决策） */
    DECISION_JSON,

    /** 通用 Schema JSON（其余全部模板） */
    SCHEMA_JSON;

    /**
     * 输出契约的「禁出格」强制句（全大写强调，防 AI 输出多余文字）。
     *
     * @return 强制句
     */
    public String forceJsonClause() {
        return "DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
    }
}
