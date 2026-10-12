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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Qwen 同题型重问 · 输出解析。
 * <p>Qwen 对照采用<b>同题型重问</b>：把 NanoJev 判定题（choice/boolean/
 * score）转成 Qwen 的判定任务——给定情境与候选/档位/判据，输出选中项 JSON。</p>
 * <p>输出契约（Qwen 判定提示词约定，jsonMode）：</p>
 * <ul>
 * <li>choice：{@code {"answer": "<候选键>"}}；</li>
 * <li>score：{@code {"answer": 档位索引}}（0-based，与 NanoJev score 档位索引一致）。</li>
 * </ul>
 * <p>解析策略：宽松——优先取 {@code answer} 字段；无 answer 时回退（choice=键名、
 * boolean=含 true 关键字、score=数字）由各解析方法兜底；彻底不可解析返回 null
 * （该样本 Qwen 侧标 failed，不参与一致率分子）。</p>
 */
public final class QwenAnswerParse {

    private QwenAnswerParse() {
    }

    /** Jackson 3（宽松解析）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果（Qwen 选中项 + 原文摘录）。 */
    public record QwenAnswer(Object answer, String raw) {
    }

    /**
     * 从 Qwen 输出解析选中项（jsonMode 契约）。
     *
     * @param aiText Qwen 输出文本（可为 null/非法/含 Markdown 围栏）
     *
     * @return 解析结果（answer 为字符串/布尔/整数；不可解析返回 null）
     */
    public static QwenAnswer parse(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return null;
        }
        try {
            String json = extractJson(aiText);
            JsonNode node = MAPPER.readTree(json);
            JsonNode answer = node.get("answer");
            if (answer == null || answer.isNull()) {
                return null;
            }
            if (answer.isBoolean()) {
                return new QwenAnswer(answer.asBoolean(), aiText);
            }
            if (answer.isNumber()) {
                return new QwenAnswer(answer.asInt(), aiText);
            }
            String s = answer.asText("").trim();
            if (s.isBlank()) {
                return null;
            }
            return new QwenAnswer(s, aiText);
        } catch (Exception e) {
            // 非 JSON：boolean 宽松兜底（文本含 true）
            String t = aiText.trim().toLowerCase();
            if (t.contains("true") && !t.contains("false")) {
                return new QwenAnswer(Boolean.TRUE, aiText);
            }
            if (t.contains("false")) {
                return new QwenAnswer(Boolean.FALSE, aiText);
            }
            return null;
        }
    }

    /** 从文本提取 JSON 对象（宽容：截取首个 { 到最后一个 }）。 */
    private static String extractJson(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s >= 0 && e > s) {
            return text.substring(s, e + 1);
        }
        throw new IllegalArgumentException("无可解析 JSON");
    }
}
