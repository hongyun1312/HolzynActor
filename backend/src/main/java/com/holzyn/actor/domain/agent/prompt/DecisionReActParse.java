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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实时决策 ReAct 新契约解析器 + 内部规范适配。
 * <p>解析 action_gen 模板的 DECISION_JSON 契约输出（&lt;decision&gt; 标签内 JSON）：</p>
 * <pre>
 * "reasoning": "……",
 * "action_type": "continue|start_action|move_to|speak_to|wait",
 * "action_detail": "……",
 * "target": "……",
 * "priority": 1..5,
 * "expected_duration_minutes": 30,
 * "wait_minutes": 可选,
 * "candidates": 可选（前瞻推演）
 * </pre>
 * <p><b>完全替换语义</b>：action_type 枚举取代旧 type 枚举；reasoning 取代 reason；priority 取代 urgency；
 * action_detail 取代 action。为让下游执行链（AgentActionService/冲突检测/state delta/宏观入账等）零改动，
 * 适配层同时映射内部规范旧键（type/action/target/reason/urgency/duration/params/waitMinutes/foresightCandidates）。</p>
 * <p><b>continue 兜底语义</b>：action_type=continue 表示 AI 判定继续当前行为（不切换行动）——
 * 由 AgentRuntime 决策循环识别并跳过行动执行（不建执行计划）；AI 超时/输出不合规时的程序化兜底
 * 同样语义=继续当前行为（见 {@link #continueDecision}）。</p>
 * <p>纯静态可单测。</p>
 */
public final class DecisionReActParse {

    /** action_type 枚举（固定五值）。 */
    public static final Set<String> ACTION_TYPES = Set.of("continue", "start_action", "move_to", "speak_to", "wait");

    /** &lt;decision&gt; 标签提取（无标签时按裸 JSON 提取）。 */
    private static final Pattern DECISION_TAG = Pattern.compile("<decision>(.*?)</decision>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionReActParse() {
    }

    /**
     * 解析决策 JSON 文本 → 内部规范 Map（含新键与映射旧键）。
     *
     * @param raw AI 原始输出
     *
     * @return 内部规范决策 Map；无法提取 JSON / action_type 非法 / action_detail 空白 → null
     */
    public static Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractDecisionJson(raw);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return parse(node);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析决策 JSON 节点 → 内部规范 Map。
     *
     * @param node 已解析的 JSON 节点（含 <decision>标签内容）
     *
     * @return 内部规范 Map（校验失败返回 null）
     */
    public static Map<String, Object> parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String actionType = node.path("action_type").asText("").trim();
        String actionDetail = node.path("action_detail").asText("").trim();
        String reasoning = node.path("reasoning").asText("").trim();
        if (!ACTION_TYPES.contains(actionType)) {
            return null;
        }
        // continue 也要求 action_detail 描述「在继续什么」（程序化兜底由 continueDecision 生成）
        if (actionDetail.isBlank()) {
            return null;
        }
        String target = node.path("target").asText("").trim();
        int priority = node.path("priority").asInt(3);
        priority = Math.max(1, Math.min(5, priority));
        int duration = node.path("expected_duration_minutes").asInt(30);
        duration = Math.max(1, duration);

        Map<String, Object> decision = new LinkedHashMap<>();
        // —— 新契约键（权威） ——
        decision.put("actionType", actionType);
        decision.put("actionDetail", actionDetail);
        decision.put("reasoning", reasoning.isBlank() ? "（未给出推理）" : reasoning);
        decision.put("priority", priority);
        decision.put("expectedDurationMinutes", duration);
        decision.put("target", target);

        // —— 映射旧键（下游执行链兼容） ——
        decision.put("type", legacyType(actionType, actionDetail));
        decision.put("action", actionDetail);
        decision.put("target", target);
        decision.put("reason", reasoning.isBlank() ? "（未给出推理）" : reasoning);
        decision.put("urgency", priority);
        decision.put("duration", duration);
        Map<String, Object> params = new LinkedHashMap<>();
        if ("move_to".equals(actionType) && !target.isBlank()) {
            params.put("move", Map.of("to", target));
        }
        decision.put("params", params);
        // 可选计划时间（scheduled 触发源兼容）
        if (node.has("plannedTime") || node.has("scheduledAt")) {
            String raw = node.has("plannedTime") ? node.path("plannedTime").asText("") : node.path("scheduledAt").asText("");
            if (!raw.isBlank()) {
                decision.put("plannedTime", raw);
            }
        }
        // 可选等待分钟（wait action_type 或显式 wait_minutes）
        int waitMinutes = node.path("wait_minutes").asInt(-1);
        if (waitMinutes <= 0 && "wait".equals(actionType)) {
            waitMinutes = duration;
        }
        if (waitMinutes > 0) {
            decision.put("waitMinutes", waitMinutes);
        }
        // 前瞻推演候选（可选字段保留）
        JsonNode candidates = node.path("candidates");
        if (candidates.isArray()) {
            List<Map<String, Object>> candList = new ArrayList<>();
            for (JsonNode c : candidates) {
                if (c == null || !c.isObject()) {
                    continue;
                }
                String candAction = c.path("action").asText("").trim();
                if (candAction.isBlank()) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("action", candAction);
                one.put("consequence", c.path("consequence").asText("").trim());
                candList.add(one);
            }
            if (!candList.isEmpty()) {
                decision.put("foresightCandidates", candList);
            }
        }
        return decision;
    }

    /**
     * action_type → 旧 type 枚举映射（下游旁路执行链兼容用）。
     * <ul>
     * <li>move_to → move（移动）；speak_to → speak（交谈）；wait → rest（等待类计划）；</li>
     * <li>start_action → 按 action_detail 关键词推断（含「前往/去/到/赴」→move；含「吃/喝/用膳」→rest 恢复语义；
     * 含「找/见/聊/问」→speak；否则 custom）；</li>
     * <li>continue → custom（不切换行动）。</li>
     * </ul>
     */
    static String legacyType(String actionType, String actionDetail) {
        String d = actionDetail == null ? "" : actionDetail;
        switch (actionType) {
            case "move_to":
                return "move";
            case "speak_to":
                return "speak";
            case "wait":
                return "rest";
            case "start_action":
                if (d.contains("前往") || d.contains("去") || d.contains("到") || d.contains("赴")
                        || d.contains("回到") || d.contains("赶往")) {
                    return "move";
                }
                if (d.contains("找") || d.contains("见") || d.contains("聊") || d.contains("问") || d.contains("商量")) {
                    return "speak";
                }
                if (d.contains("吃") || d.contains("喝") || d.contains("用膳") || d.contains("进食")) {
                    return "rest";
                }
                return "custom";
            default:
                return "custom";
        }
    }

    /**
     * 公开委托（空间候选选择程序化映射用——{@code SpaceChoiceMapper} 在
     * domain/growth/space 包，跨包调用本映射）。
     *
     * @param actionType action_type 枚举（continue/start_action/move_to/speak_to/wait）
     *
     * @param actionDetail 行动描述
     *
     * @return 旧 type 枚举（move/speak/rest/custom）
     */
    public static String legacyTypeFor(String actionType, String actionDetail) {
        return legacyType(actionType, actionDetail);
    }

    /**
     * 程序化兜底决策（AI 超时/不合规时）=「继续当前行为」。
     *
     * @param activity 当前行为描述（可空；空用通用继续语）
     *
     * @return 内部规范决策 Map（actionType=continue）
     */
    public static Map<String, Object> continueDecision(String activity) {
        String cur = activity == null || activity.isBlank() ? "手头的事" : activity.trim();
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("actionType", "continue");
        decision.put("actionDetail", "继续" + cur);
        decision.put("reasoning", "AI 决策不可用，按程序化兜底继续当前行为");
        decision.put("priority", 1);
        decision.put("expectedDurationMinutes", 30);
        decision.put("target", "");
        decision.put("type", "custom");
        decision.put("action", "继续" + cur);
        decision.put("reason", "AI 决策不可用，按程序化兜底继续当前行为");
        decision.put("urgency", 1);
        decision.put("duration", 30);
        decision.put("params", Map.of());
        return decision;
    }

    /**
     * 提取 &lt;decision&gt; 标签内 JSON（无标签时回退 {@link com.holzyn.actor.common.JsonUtil#extractJson}）。
     *
     * @param raw AI 原始输出
     *
     * @return 提取的 JSON 文本（可空）
     */
    public static String extractDecisionJson(String raw) {
        Matcher m = DECISION_TAG.matcher(raw);
        if (m.find()) {
            String inner = m.group(1).trim();
            if (!inner.isBlank()) {
                return inner;
            }
        }
        return com.holzyn.actor.common.JsonUtil.extractJson(raw);
    }
}
