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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judge 评分输出解析器。
 * <p>judge 输出契约（behavior_judge 模板）：</p>
 * <pre>
 * "foresight": 1-5（未注入前瞻样本可缺省）, "naturalness": 1-5 },
 * "summary": "一句话总评"
 * </pre>
 * <p>解析策略：先整体 JSON 解析（tools.jackson）；失败 → 正则提取 scores 对象（截断/代码块包裹
 * 容错）；仍失败 → 空结果（该样本评分失败，批次统计跳过，不阻断批跑）。</p>
 * <p>分数钳制：非 1-5 整数按最近合法值钳制；缺失维不回填（总分按实际出现维度均值×10 取整）。</p>
 */
public final class JudgeScoreParse {

    private JudgeScoreParse() {
    }

    /** 五维键集合（EvaluationProperties.RUBRIC_DIMENSIONS 同源）。 */
    private static final java.util.Set<String> DIMENSIONS =
            new java.util.HashSet<>(EvaluationProperties.RUBRIC_DIMENSIONS);

    /** 正则：从任意文本中抓取 scores 对象（容错截断/包裹/未加引号键）。 */
    private static final Pattern SCORES_OBJECT = Pattern.compile(
            "(?:\"scores\"|scores)\\s*:\\s*\\{(.*?)}", Pattern.DOTALL);

    /** 正则：键值对（"key": 数值）。 */
    private static final Pattern SCORE_ENTRY = Pattern.compile(
            "\"([A-Za-z_]+)\"\\s*:\\s*(\\d+)");

    /** 正则：reasons 对象（容错）。 */
    private static final Pattern REASONS_OBJECT = Pattern.compile(
            "(?:\"reasons\"|reasons)\\s*:\\s*\\{(.*?)}", Pattern.DOTALL);

    /**
     * 评分结果（解析产物）。
     *
     * @param scores 逐维分数（仅合法维键；非法输入为空 Map）
     *
     * @param reasons 逐维理由（可空 Map）
     *
     * @param summary 一句话总评（可空）
     *
     * @param raw 原始输出全文（回放/审计）
     */
    public record JudgeResult(Map<String, Integer> scores, Map<String, String> reasons,
                              String summary, String raw) {

        /** 总分：出现维度均值 ×10 取整（1-50；无维度=0）。 */
        public int total() {
            if (scores.isEmpty()) {
                return 0;
            }
            double sum = 0;
            for (int s : scores.values()) {
                sum += s;
            }
            return (int) Math.round(sum / scores.size() * 10.0);
        }
    }

    /**
     * 解析 judge 原始输出。
     *
     * @param raw judge 原始输出（可空/非法 → 空结果零阻断）
     *
     * @return 解析结果
     */
    public static JudgeResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JudgeResult(Map.of(), Map.of(), null, raw);
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        String summary = null;
        // ① 整体 JSON 解析
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            tools.jackson.databind.JsonNode root = mapper.readTree(raw);
            tools.jackson.databind.JsonNode sc = root.path("scores");
            if (sc.isObject()) {
                sc.properties().forEach(e -> {
                    if (DIMENSIONS.contains(e.getKey())) {
                        Integer v = clampScore(e.getValue());
                        if (v != null) {
                            scores.put(e.getKey(), v);
                        }
                    }
                });
            }
            tools.jackson.databind.JsonNode rs = root.path("reasons");
            if (rs.isObject()) {
                rs.properties().forEach(e -> reasons.put(e.getKey(), e.getValue().asText("")));
            }
            tools.jackson.databind.JsonNode sm = root.path("summary");
            if (sm.isTextual() && !sm.asText().isBlank()) {
                summary = sm.asText();
            }
        } catch (Exception ignored) {
            // 整体解析失败 → ② 正则兜底
        }
        if (scores.isEmpty()) {
            Matcher m = SCORES_OBJECT.matcher(raw);
            if (m.find()) {
                Matcher em = SCORE_ENTRY.matcher(m.group(1));
                while (em.find()) {
                    String key = em.group(1);
                    if (DIMENSIONS.contains(key)) {
                        scores.put(key, clamp(parseIntSafe(em.group(2))));
                    }
                }
            }
            Matcher rm = REASONS_OBJECT.matcher(raw);
            if (rm.find() && reasons.isEmpty()) {
                String body = rm.group(1);
                Matcher km = Pattern.compile("\"([A-Za-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
                while (km.find()) {
                    reasons.put(km.group(1), unescape(km.group(2)));
                }
            }
        }
        return new JudgeResult(scores, reasons, summary, raw);
    }

    /** 节点 → 1-5 整数（非法 null）。 */
    private static Integer clampScore(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.canConvertToInt()) {
            return null;
        }
        return clamp(node.asInt());
    }

    /** 钳制到 1-5。 */
    static int clamp(int v) {
        return Math.max(1, Math.min(5, v));
    }

    /** 安全解析整数。 */
    static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** JSON 字符串反转义（仅常见转义，审计展示用）。 */
    static String unescape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
