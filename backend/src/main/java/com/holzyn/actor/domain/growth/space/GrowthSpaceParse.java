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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.growth.persona.PersonaJson;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四段式空间 JSON 模型与解析器。
 * <p><b>契约</b>（{@code growth_space_generate} 场景 SCHEMA_JSON 输出）：</p>
 * <p><b>解析口径</b>：宽容解析（缺段→空列表；字段缺失→默认；JSON 非法→{@code null}，调用方
 * 程序化兜底零阻断）；{@code recordJsonOk/Fail} 由调用方（{@code GrowthSpaceService}）接入
 * Prompt 指标（对齐 Prompt 工程规范 §5「格式合规率」）。</p>
 */
public final class GrowthSpaceParse {

    private GrowthSpaceParse() {
    }

    /**
     * 解析四段式空间 JSON。
     * <p>宽容口径：剥 Markdown 代码块围栏（AI 偶发 ```json 包裹）后再解析；
     * 字段名兼容 {@code weight}/{@code likelihood}（AI 输出字段漂移容错）；
     * 缺段→空列表；JSON 非法→{@code null}（调用方程序化兜底零阻断）。</p>
     *
     * @param json AI 输出 JSON 文本（可空）
     *
     * @return 空间文档；JSON 非法返回 null（调用方程序化兜底）
     */
    public static GrowthSpaceDoc parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = PersonaJson.mapper().readTree(stripFence(json));
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        Inner inner = parseInner(root.get("inner"));
        List<StateItem> states = parseStates(root.get("possible_states"));
        List<ActionItem> actions = parseActions(root.get("possible_actions"));
        List<ForbiddenItem> forbidden = parseForbidden(root.get("forbidden"));
        // 至少要有候选行动或禁止项之一，否则视为无意义输出
        if (actions.isEmpty() && forbidden.isEmpty()) {
            return null;
        }
        return new GrowthSpaceDoc(inner, states, actions, forbidden);
    }

    private static Inner parseInner(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Inner(null, null, null);
        }
        Map<String, Object> pad = null;
        JsonNode padNode = node.get("pad");
        if (padNode != null && padNode.isObject()) {
            Map<String, Object> p = new LinkedHashMap<>();
            padNode.properties().forEach(e -> p.put(e.getKey(), e.getValue().asText()));
            pad = p;
        }
        return new Inner(pad,
                text(node.get("dominant_thought")),
                text(node.get("body_feel")));
    }

    private static List<StateItem> parseStates(JsonNode node) {
        List<StateItem> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String state = text(item.get("state"));
            if (state == null || state.isBlank()) {
                continue;
            }
            out.add(new StateItem(state, weightOf(item), text(item.get("precondition"))));
        }
        return out;
    }

    private static List<ActionItem> parseActions(JsonNode node) {
        List<ActionItem> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String action = text(item.get("action"));
            if (action == null || action.isBlank()) {
                continue;
            }
            out.add(new ActionItem(action, weightOf(item),
                    text(item.get("precondition")), text(item.get("target"))));
        }
        return out;
    }

    private static List<ForbiddenItem> parseForbidden(JsonNode node) {
        List<ForbiddenItem> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String what = text(item.get("what"));
            if (what == null || what.isBlank()) {
                continue;
            }
            out.add(new ForbiddenItem(what, text(item.get("reason"))));
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 倾向权重（兼容 weight/likelihood 两个字段名；钳制 0-1）。 */
    private static Double weightOf(JsonNode item) {
        Double w = weight(item.get("weight"));
        if (w != null) {
            return w;
        }
        return weight(item.get("likelihood"));
    }

    private static Double weight(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, node.asDouble()));
    }

    /** 剥 Markdown 代码块围栏（```json / ``` 首行 + 结尾 ```）。 */
    private static String stripFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        return t;
    }

    //
    // 模型
    //

    /** 四段式空间文档（AI 或程序化产物的统一形态）。 */
    public record GrowthSpaceDoc(
            Inner inner,
            List<StateItem> possibleStates,
            List<ActionItem> possibleActions,
            List<ForbiddenItem> forbidden) {

        /** 空文档（程序化兜底起点；调用方填充）。 */
        public static GrowthSpaceDoc empty() {
            return new GrowthSpaceDoc(new Inner(null, null, null), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>());
        }

        /** 是否有实际候选（有行动候选或禁止项）。 */
        public boolean hasContent() {
            return (possibleActions != null && !possibleActions.isEmpty())
                    || (forbidden != null && !forbidden.isEmpty());
        }

        /** 序列化为 JSON（落库 space_json）。 */
        public String toJson() {
            return PersonaJson.mapper().writeValueAsString(this);
        }
    }

    /** 内心（inner）：PAD 快照 + 主导念头 + 身体感受。 */
    public record Inner(Map<String, Object> pad, String dominantThought, String bodyFeel) {
    }

    /** 可发生状态（possible_states）：state + 倾向权重 + 前置条件。 */
    public record StateItem(String state, Double weight, String precondition) {
    }

    /** 可发生行为（possible_actions）：action + 倾向权重 + 前置条件 + 目标。 */
    public record ActionItem(String action, Double weight, String precondition, String target) {
    }

    /** 不可发生（forbidden）：what + reason。 */
    public record ForbiddenItem(String what, String reason) {
    }
}
