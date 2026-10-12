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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NPC 情绪 AI 评估输出解析器。
 * <p>职责：把 AI 调用（npc_emotion_eval 模板，json_object 模式）返回的文本解析为
 * 结构化情绪评估——期望 JSON 对象：<pre>
 * "desirability": -0.6, "praiseworthiness": 0.0, "liking": -0.3,
 * "reason": "被当众羞辱，感到愤怒" }
 * </pre>
 * 兼容 Markdown 代码块/首尾说明文字（复用 {@link JsonUtil}）；字段缺失安全兜底：
 * emotion 可空（无显式标签时按 PAD 最近锚点）、padDelta 可空（仅情绪标签）、
 * OCC 三维仅 occ-enabled 时消费；全部字段缺失返回 null（无可应用内容）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§7.2（AI 评估写回 + OCC 三维评价）。
 * </p>
 */
public final class EmotionEvalParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** padDelta 位移合理上界（PAD 结果仍钳制 [-1,1]，delta 本身允许小幅越界，防野生大值） */
    private static final double DELTA_LIMIT = 2.0;

    /** OCC 三维刻度上界（-1~1 评价） */
    private static final double OCC_LIMIT = 1.0;

    /** 工具类禁止实例化 */
    private EmotionEvalParse() {
    }

    /**
     * 解析情绪评估 AI 输出（非法/空返回 null，不抛异常）。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 情绪评估（无可应用内容返回 null）
     */
    public static EmotionEval parse(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return null;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            String emotion = root.path("emotion").asText("").trim();
            Integer intensity = null;
            JsonNode intNode = root.path("intensity");
            if (intNode.isNumber()) {
                intensity = Math.max(0, Math.min(100, intNode.asInt()));
            }
            Double padP = padDelta(root, "pleasure");
            Double padA = padDelta(root, "arousal");
            Double padD = padDelta(root, "dominance");
            Double desirability = occAxis(root, "desirability");
            Double praiseworthiness = occAxis(root, "praiseworthiness");
            Double liking = occAxis(root, "liking");
            String reason = root.path("reason").asText("");
            if (emotion.isBlank() && intensity == null && padP == null && padA == null && padD == null
                    && desirability == null && praiseworthiness == null && liking == null) {
                return null; // 无可应用内容
            }
            return new EmotionEval(emotion, intensity, padP, padA, padD,
                    desirability, praiseworthiness, liking, reason);
        } catch (Exception e) {
            // 解析失败：整体返回 null（上层回退程序化情绪规则）
            return null;
        }
    }

    /**
     * padDelta 三维位移（[-DELTA_LIMIT, DELTA_LIMIT] 界限守卫；结果仍由钳制 [-1,1] 保证合法）。
     */
    private static Double padDelta(JsonNode node, String field) {
        JsonNode v = node.path("padDelta").path(field);
        return axis(v, DELTA_LIMIT);
    }

    /**
     * OCC 三维评价（[-OCC_LIMIT, OCC_LIMIT]，-1~1 刻度）。
     */
    private static Double occAxis(JsonNode node, String field) {
        return axis(node.path(field), OCC_LIMIT);
    }

    /**
     * 数值轴取值（数字 → 钳制到 ±limit；非数字 → null）。
     */
    private static Double axis(JsonNode v, double limit) {
        if (v == null || !v.isNumber()) {
            return null;
        }
        double d = v.asDouble();
        return Math.max(-limit, Math.min(limit, d));
    }

    /**
     * 解析后的情绪评估指令。
     *
     * @param emotion 显式主导情绪标签（可空；锚点 code 或 custom:*，空=按 PAD 最近锚点）
     *
     * @param intensity AI 给出的情绪强度 0-100（可空；空=按 PAD 向量模长重算）
     *
     * @param padPleasure padDelta-P 位移（可空）
     *
     * @param padArousal padDelta-A 位移（可空）
     *
     * @param padDominance padDelta-D 位移（可空）
     *
     * @param desirability OCC 合意性（-1~1，可空；occ-enabled 时消费）
     *
     * @param praiseworthiness OCC 称赞性（-1~1，可空）
     *
     * @param liking OCC 喜好性（-1~1，可空）
     *
     * @param reason 评估理由（记录审计/日志）
     */
    public record EmotionEval(String emotion, Integer intensity,
                              Double padPleasure, Double padArousal, Double padDominance,
                              Double desirability, Double praiseworthiness, Double liking,
                              String reason) {
    }
}
