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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 群聊逐角色回复 JSON 契约解析器。
 * <p>解析 group_reply 模板的 DIALOG_JSON 契约输出：</p>
 * <pre>
 * </pre>
 * <ul>
 * <li><b>dialogue</b>：回复正文（落 ActorMessage.content）——契约必填，空则判不合规；
 * <b>2026-10 长跑实测容错：模型偶发以 <code>dialog</code> 键输出正文</b>（同一模型交替输出
 * <code>dialogue</code>/<code>dialog</code>），本解析器两个键都接受，<code>dialogue</code> 优先——修复
 * 「有效回复被当作空回复丢弃 → 消息 failed/null → 前端仅显示角色名无内容 + 整轮被截断」缺陷；</li>
 * <li><b>action</b>：伴随动作（SSE 附带/落 rawStream 调试回看）；</li>
 * <li><b>emotion_update</b>：情绪与强度（写回角色情绪/SSE 附带）；</li>
 * <li><b>internal_thought</b>：内心想法（沙盘想法气泡/SSE 附带）。</li>
 * </ul>
 * <p>解析容错：非 JSON / 缺 dialogue 与 dialog / 两者均空白 → 返回 null（调用方按空回复防护处理）；
 * 其余字段缺失逐项回退空串/默认，不阻断。纯静态可单测。</p>
 */
public final class GroupReplyParse {

    /** Jackson 3 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果（不可变）。 */
    public record GroupReply(String dialogue, String action, String emotion, Integer emotionIntensity,
                             String internalThought, boolean compliant) {
    }

    private GroupReplyParse() {
    }

    /**
     * 解析群聊回复 JSON 文本。
     *
     * @param raw AI 原始输出
     *
     * @return 解析结果（JSON 无法提取或 dialogue/dialog 均空白 → 返回 null）
     */
    public static GroupReply parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = com.holzyn.actor.common.JsonUtil.extractJson(raw);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            // 2026-10 长跑实测容错：模型偶发输出 dialog 键（与 dialogue 交替）——
            // 两键都接受，dialogue 优先；修复「有效回复被当作空回复丢弃 → failed/null 消息 + 整轮截断」
            String dialogue = text(node, "dialogue");
            if (dialogue.isBlank()) {
                dialogue = text(node, "dialog");
            }
            // 契约必填：dialogue/dialog 均空白 → 不合规（调用方按空回复防护处理）
            if (dialogue.isBlank()) {
                return new GroupReply(null, text(node, "action"), null, null, null, false);
            }
            String emotion = node.path("emotion_update").path("emotion").asText("").trim();
            int intensity = node.path("emotion_update").path("intensity").asInt(0);
            return new GroupReply(dialogue,
                    text(node, "action"),
                    emotion.isBlank() ? null : emotion,
                    intensity >= 1 && intensity <= 5 ? intensity : null,
                    text(node, "internal_thought"),
                    true);
        } catch (Exception e) {
            return null;
        }
    }

    /** 安全取字符串字段（缺失/非文本 → 空串）。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        if (v.isTextual()) {
            return v.asText().trim();
        }
        return v.toString().trim();
    }
}
