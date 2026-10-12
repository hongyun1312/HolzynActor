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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界观维度 Schema 校验器。
 * <p>职责：对 Agent 节点输出的维度 JSON 做<b>结构校验 + 轻量自动修复</b>：</p>
 * <ul>
 * <li>提取纯 JSON（剥离 Markdown 围栏/说明文字，复用 {@link JsonUtil}）；</li>
 * <li>解析失败 → 自动修复（清洗尾随逗号、弯引号→ASCII 直引号）后重试解析；</li>
 * <li>结构校验：接受 {@code {"&lt;dim&gt;": {...}}} 包裹形态（对齐 bishu-novel 输出惯例），
 * 裸维度对象自动补包裹；字段级校验——每个维度的必填字段按类型约束
 * （prose=字符串非空 / list=字符串数组 / table=对象数组且含必填子键）；</li>
 * <li>essence 定调句非空校验（≤80 字软约束，超出截断）；</li>
 * <li>通过后返回<b>规范化</b>的 JSON 文本（维度键包裹，去除尾随逗号/修复引号后的干净结构），
 * 供执行器写检查点与后续渲染/映射复用。</li>
 * </ul>
 */
@Component
public class WorldGenSchemaValidator {

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 字段类型约束：prose=字符串 / list=字符串数组 / table=对象数组（子键见 fieldKeys） */
    private record FieldSpec(String key, String type, List<String> fieldKeys) {
    }

    /** 各维度 Schema（自写，字段语义对齐方案 §2.4 与渲染 Schema） */
    private static final Map<String, List<FieldSpec>> DIMENSION_SCHEMA = buildSchema();

    private static Map<String, List<FieldSpec>> buildSchema() {
        Map<String, List<FieldSpec>> map = new LinkedHashMap<>();
        map.put("core_laws", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("power_system", "prose", null),
                new FieldSpec("axioms", "table", List.of("name", "statement", "cost", "boundary", "enforcement")),
                new FieldSpec("taboos", "list", null),
                new FieldSpec("power_manifestation", "prose", null)));
        map.put("space_time", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("world_layout", "prose", null),
                new FieldSpec("key_locations", "table", List.of("name", "terrain", "feature", "risk", "controlling_force")),
                new FieldSpec("ecology", "prose", null),
                new FieldSpec("era", "prose", null),
                new FieldSpec("environment_texture", "prose", null)));
        map.put("society", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("races", "table", List.of("name", "traits", "population", "social_status")),
                new FieldSpec("class_structure", "prose", null),
                new FieldSpec("political_system", "prose", null),
                new FieldSpec("forces", "table", List.of("name", "type", "base_of_power", "goals", "methods")),
                new FieldSpec("force_relations", "table", List.of("source", "target", "relation", "tension_point")),
                new FieldSpec("power_visibility", "prose", null)));
        map.put("history_culture", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("major_events", "table", List.of("event", "era", "lasting_impact")),
                new FieldSpec("religions", "table", List.of("name", "core_belief", "follower_scope")),
                new FieldSpec("customs", "prose", null),
                new FieldSpec("economy", "prose", null),
                new FieldSpec("daily_slice", "prose", null)));
        map.put("existence", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("calendar", "prose", null),
                new FieldSpec("lifespan", "prose", null),
                new FieldSpec("death", "prose", null),
                new FieldSpec("disease_and_birth", "prose", null)));
        map.put("information", List.of(
                new FieldSpec("essence", "prose", null),
                new FieldSpec("info_speed", "prose", null),
                new FieldSpec("knowledge_medium", "prose", null),
                new FieldSpec("info_barriers", "prose", null),
                new FieldSpec("rumor_and_truth", "prose", null)));
        return map;
    }

    /**
     * 校验并规范化一个维度的输出。
     *
     * @param dimKey 维度键（core_laws/space_time/...；决定校验 Schema）
     *
     * @param rawContent 模型输出的原始文本
     *
     * @return 规范化后的 JSON 文本（{@code {"&lt;dim&gt;": {...}}} 包裹形态）
     *
     * @throws BizException 校验失败（字段缺失/类型错误/JSON 无法修复）
     */
    public String validateAndRepair(String dimKey, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("维度「" + dimKey + "」输出为空");
        }
        String json = JsonUtil.extractJson(rawContent);
        if (json == null) {
            throw new BizException("维度「" + dimKey + "」未返回有效 JSON");
        }
        JsonNode root = parseWithRepair(json, dimKey);

        // 结构归一：接受 {"dim": {...}} 包裹 或 裸维度对象，统一为包裹形态
        JsonNode dim;
        if (root.isObject() && root.has(dimKey) && root.get(dimKey).isObject()) {
            dim = root.get(dimKey);
        } else if (root.isObject() && looksLikeDimension(root, dimKey)) {
            // 裸维度对象：自动补包裹（保持下游渲染/映射形态一致）
            dim = root;
        } else {
            throw new BizException("维度「" + dimKey + "」输出结构非法：应为 {" + dimKey + ": {...}}");
        }

        // 字段级校验 + 类型修复
        ObjectNode dimNode = (ObjectNode) dim;
        List<FieldSpec> specs = DIMENSION_SCHEMA.get(dimKey);
        if (specs == null) {
            throw new BizException("维度「" + dimKey + "」无校验 Schema");
        }
        for (FieldSpec spec : specs) {
            JsonNode v = dimNode.get(spec.key);
            if (v == null || v.isNull()) {
                throw new BizException("维度「" + dimKey + "」缺少必填字段：" + spec.key);
            }
            switch (spec.type) {
                case "prose" -> {
                    if (!v.isTextual() || v.asText().isBlank()) {
                        throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 应为非空字符串");
                    }
                }
                case "list" -> {
                    if (!v.isArray()) {
                        throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 应为数组");
                    }
                    for (JsonNode item : v) {
                        if (!item.isTextual()) {
                            throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 数组元素应为字符串");
                        }
                    }
                }
                case "table" -> {
                    if (!v.isArray()) {
                        throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 应为对象数组");
                    }
                    for (JsonNode item : v) {
                        if (!item.isObject()) {
                            throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 数组元素应为对象");
                        }
                        for (String sub : spec.fieldKeys) {
                            JsonNode sv = item.get(sub);
                            if (sv == null || sv.isNull() || (sv.isTextual() && sv.asText().isBlank())) {
                                throw new BizException("维度「" + dimKey + "」字段 " + spec.key + " 元素缺少子键：" + sub);
                            }
                        }
                    }
                }
                default -> {
                    // 未知类型不校验
                }
            }
        }

        // essence 定调句：非空已校验；超长（>80）截断防膨胀
        JsonNode essence = dimNode.get("essence");
        if (essence != null && essence.isTextual() && essence.asText().length() > 80) {
            dimNode.put("essence", essence.asText().substring(0, 80));
        }

        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(dimKey, dimNode);
        return writeJson(wrapped);
    }

    /**
     * 判断裸对象是否「长得像本维度」（含 essence 或任一 Schema 字段）——避免把无关对象误当维度。
     */
    private boolean looksLikeDimension(JsonNode obj, String dimKey) {
        List<FieldSpec> specs = DIMENSION_SCHEMA.get(dimKey);
        if (specs == null) {
            return false;
        }
        for (FieldSpec spec : specs) {
            if (obj.has(spec.key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 JSON；失败时自动修复（清洗尾随逗号 + 弯引号→ASCII 直引号）后重试。
     */
    private JsonNode parseWithRepair(String json, String dimKey) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception first) {
            String repaired = json
                    .replaceAll(",\\s*\\]", "]")
                    .replaceAll(",\\s*\\}", "}")
                    .replace('\u201C', '"')
                    .replace('\u201D', '"')
                    .replace('\u2018', '\'')
                    .replace('\u2019', '\'');
            try {
                return MAPPER.readTree(repaired);
            } catch (Exception e) {
                throw new BizException("维度「" + dimKey + "」JSON 解析失败（修复后仍非法）：" + e.getMessage());
            }
        }
    }

    /**
     * JSON 序列化（失败抛异常——校验通过的节点不应序列化失败）。
     */
    private String writeJson(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new BizException("维度 JSON 序列化失败：" + e.getMessage());
        }
    }

    // ==================== character 工作流校验 ====================

    /** character 阵容输出键（roster 输出包裹形态：{"characters": [...]}） */
    public static final String ROSTER_KEY = "characters";

    /** character 深度输出键（depth 输出包裹形态：{"character_depth": {...}}） */
    public static final String DEPTH_KEY = "character_depth";

    /**
     * 校验并规范化角色阵容输出（agent_roster 节点）。
     * affiliation?, occupation?, tagline?}]}}——name/role 必填非空，其余字段可选
     * （宽松校验：阵容是「导演视角」的定位清单，细节留给逐角色深度生成）。
     * 输出统一为包裹形态（防裸数组）。</p>
     *
     * @param rawContent 模型输出的原始文本
     *
     * @return 规范化后的 JSON 文本（{"characters": [...]}）
     *
     * @throws BizException 校验失败
     */
    public String validateRoster(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("角色阵容输出为空");
        }
        String json = JsonUtil.extractJson(rawContent);
        if (json == null) {
            throw new BizException("角色阵容未返回有效 JSON");
        }
        JsonNode root = parseWithRepair(json, "roster");
        JsonNode arr;
        if (root.isObject() && root.has(ROSTER_KEY) && root.get(ROSTER_KEY).isArray()) {
            arr = root.get(ROSTER_KEY);
        } else if (root.isArray()) {
            // 裸数组自动补包裹
            arr = root;
        } else {
            throw new BizException("角色阵容输出结构非法：应为 {\"characters\": [...]}");
        }
        if (arr.isEmpty()) {
            throw new BizException("角色阵容为空（characters 数组无元素）");
        }
        ObjectNode out = MAPPER.createObjectNode();
        var list = out.putArray(ROSTER_KEY);
        for (JsonNode item : arr) {
            if (!item.isObject()) {
                throw new BizException("角色阵容元素应为对象");
            }
            String name = item.path("name").asText("").trim();
            String role = item.path("role").asText("").trim();
            if (name.isBlank()) {
                throw new BizException("角色阵容元素缺少必填字段：name");
            }
            if (role.isBlank()) {
                throw new BizException("角色阵容元素缺少必填字段：role（角色定位）");
            }
            list.add(item);
        }
        return writeJson(out);
    }

    /**
     * 校验并规范化单个角色的深度档案输出（agent_character_depth batch 每角色）。
     * <p>结构（4 维度，映射 persona 11 维 v2 的 ①⑤⑦⑨⑪）：</p>
     * <pre>
     * skeleton.name 必填；beliefs 数组为字符串数组；deep/voice 为 prose。
     * 输出统一为包裹形态（防裸对象）。</p>
     *
     * @param rawContent 模型输出的原始文本
     *
     * @return 规范化后的 JSON 文本（{"character_depth": {...}}）
     *
     * @throws BizException 校验失败
     */
    public String validateCharacterDepth(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("角色深度输出为空");
        }
        String json = JsonUtil.extractJson(rawContent);
        if (json == null) {
            throw new BizException("角色深度未返回有效 JSON");
        }
        JsonNode root = parseWithRepair(json, "character_depth");
        JsonNode depth;
        if (root.isObject() && root.has(DEPTH_KEY) && root.get(DEPTH_KEY).isObject()) {
            depth = root.get(DEPTH_KEY);
        } else if (root.isObject() && root.has("skeleton")) {
            depth = root;
        } else {
            throw new BizException("角色深度输出结构非法：应为 {\"character_depth\": {skeleton, beliefs, deep, voice}}");
        }
        ObjectNode depthNode = (ObjectNode) depth;
        // skeleton.name 必填
        JsonNode skeleton = depthNode.get("skeleton");
        if (skeleton == null || !skeleton.isObject()) {
            throw new BizException("角色深度缺少 skeleton（骨架）段");
        }
        String name = skeleton.path("name").asText("").trim();
        if (name.isBlank()) {
            throw new BizException("角色深度 skeleton.name 缺失（须与阵容角色名一致）");
        }
        // beliefs 数组字段校验（core_values/desires/fears/beliefs 为字符串数组，宽松：缺失可空）
        JsonNode beliefs = depthNode.get("beliefs");
        if (beliefs != null && beliefs.isObject()) {
            for (String key : new String[]{"core_values", "desires", "fears", "beliefs"}) {
                JsonNode v = beliefs.get(key);
                if (v != null && !v.isArray()) {
                    throw new BizException("角色深度 beliefs." + key + " 应为字符串数组");
                }
            }
        } else if (beliefs != null && !beliefs.isNull()) {
            throw new BizException("角色深度 beliefs 段应为对象");
        }
        // deep/voice 段存在性（宽松：缺失可空——模型偶尔会漏段，校验器不强求全量）
        for (String seg : new String[]{"deep", "voice"}) {
            JsonNode v = depthNode.get(seg);
            if (v != null && !v.isNull() && !v.isObject()) {
                throw new BizException("角色深度 " + seg + " 段应为对象");
            }
        }
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(DEPTH_KEY, depthNode);
        return writeJson(wrapped);
    }

    /**
     * 从校验通过的阵容 JSON 中读取角色数组（供 batch 执行器遍历；容错返回空列表）。
     *
     * @param rosterJson 校验后的阵容 JSON（{"characters": [...]}）
     *
     * @return 角色节点列表
     */
    public List<JsonNode> readRoster(String rosterJson) {
        List<JsonNode> out = new ArrayList<>();
        if (rosterJson == null || rosterJson.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(rosterJson);
            JsonNode arr = root.get(ROSTER_KEY);
            if (arr != null && arr.isArray()) {
                arr.forEach(out::add);
            }
        } catch (Exception ignored) {
            // 容错：解析失败按空列表
        }
        return out;
    }

    // ==================== story-plan 工作流校验 ====================

    /** story-plan 故事引擎输出键（包裹形态：{"story_plan": {...}}） */
    public static final String STORY_PLAN_KEY = "story_plan";

    /** story-plan 风格档案输出键（包裹形态：{"style_profile": {...}}） */
    public static final String STYLE_PROFILE_KEY = "style_profile";

    /**
     * 校验并规范化故事引擎输出（agent_story_engine 节点）。
     * <p>结构（三层故事力学引擎·世界版，映射 bishu-novel novel-story-planner）：</p>
     * <pre>
     * 三段对象 + 各段 essence 必填非空（定向重试驱动修正）；其余字段类型校验宽松
     * （缺失可空——模型偶发漏段不强求全量，防过度重试）。输出统一为包裹形态。</p>
     *
     * @param rawContent 模型输出的原始文本
     *
     * @return 规范化后的 JSON 文本（{"story_plan": {...}}）
     *
     * @throws BizException 校验失败
     */
    public String validateStoryPlan(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("故事引擎输出为空");
        }
        String json = JsonUtil.extractJson(rawContent);
        if (json == null) {
            throw new BizException("故事引擎未返回有效 JSON");
        }
        JsonNode root = parseWithRepair(json, "story_plan");
        JsonNode plan;
        if (root.isObject() && root.has(STORY_PLAN_KEY) && root.get(STORY_PLAN_KEY).isObject()) {
            plan = root.get(STORY_PLAN_KEY);
        } else if (root.isObject() && root.has("surface") && root.has("engine") && root.has("payoff")) {
            // 裸三层对象自动补包裹
            plan = root;
        } else {
            throw new BizException("故事引擎输出结构非法：应为 {\"story_plan\": {surface, engine, payoff}}");
        }
        ObjectNode planNode = (ObjectNode) plan;
        // 三段存在性 + 各段 essence 必填非空
        requireSegment(planNode, "surface", "显性层");
        requireSegment(planNode, "engine", "引擎层");
        requireSegment(planNode, "payoff", "兑现层");
        for (String seg : new String[]{"surface", "engine", "payoff"}) {
            JsonNode s = planNode.get(seg);
            String essence = s.path("essence").asText("").trim();
            if (essence.isBlank()) {
                throw new BizException("故事引擎 " + seg + " 段缺少 essence 定调句");
            }
        }
        // 类型校验（宽松：缺失可空，类型错误抛错驱动重试）
        checkStringArray(planNode.path("surface"), "world_tags");
        checkStringArray(planNode.path("payoff"), "tension_archetypes");
        checkObjectSegment(planNode.path("engine"), "conflict_layers");
        checkObjectSegment(planNode.path("engine"), "dual_story");
        JsonNode issues = planNode.get("issues");
        if (issues != null && !issues.isNull()) {
            if (!issues.isArray()) {
                throw new BizException("故事引擎 issues 应为数组");
            }
            for (JsonNode it : issues) {
                if (it.isObject()) {
                    String type = it.path("type").asText("");
                    if (!type.isBlank() && !"conflict".equals(type) && !"missing_info".equals(type)) {
                        throw new BizException("故事引擎 issues.type 非法：" + type);
                    }
                }
            }
        }
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(STORY_PLAN_KEY, planNode);
        return writeJson(wrapped);
    }

    /**
     * 校验并规范化风格档案输出（agent_style_profile 节点）。
     * <p>结构（世界风格宪法，映射 bishu-novel novel-style-profiler）：</p>
     * <pre>
     * "narrative_persona": "…", "commandments": ["…"], "genre_notes": "…"
     * tone/narrative_persona 必填非空；references/commandments 数组类型校验（缺失可空）。</p>
     *
     * @param rawContent 模型输出的原始文本
     *
     * @return 规范化后的 JSON 文本（{"style_profile": {...}}）
     *
     * @throws BizException 校验失败
     */
    public String validateStyleProfile(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("风格档案输出为空");
        }
        String json = JsonUtil.extractJson(rawContent);
        if (json == null) {
            throw new BizException("风格档案未返回有效 JSON");
        }
        JsonNode root = parseWithRepair(json, "style_profile");
        JsonNode profile;
        if (root.isObject() && root.has(STYLE_PROFILE_KEY) && root.get(STYLE_PROFILE_KEY).isObject()) {
            profile = root.get(STYLE_PROFILE_KEY);
        } else if (root.isObject() && root.has("tone")) {
            // 裸风格对象自动补包裹
            profile = root;
        } else {
            throw new BizException("风格档案输出结构非法：应为 {\"style_profile\": {tone, ...}}");
        }
        ObjectNode profileNode = (ObjectNode) profile;
        String tone = profileNode.path("tone").asText("").trim();
        if (tone.isBlank()) {
            throw new BizException("风格档案缺少 tone（一句话基调定位）");
        }
        String persona = profileNode.path("narrative_persona").asText("").trim();
        if (persona.isBlank()) {
            throw new BizException("风格档案缺少 narrative_persona（叙事人格）");
        }
        JsonNode refs = profileNode.get("references");
        if (refs != null && !refs.isNull()) {
            if (!refs.isArray()) {
                throw new BizException("风格档案 references 应为数组");
            }
            for (JsonNode it : refs) {
                if (!it.isObject()) {
                    throw new BizException("风格档案 references 元素应为对象");
                }
            }
        }
        checkStringArray(profileNode, "commandments");
        JsonNode genreNotes = profileNode.get("genre_notes");
        if (genreNotes != null && !genreNotes.isNull() && !genreNotes.isTextual()) {
            throw new BizException("风格档案 genre_notes 应为字符串");
        }
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(STYLE_PROFILE_KEY, profileNode);
        return writeJson(wrapped);
    }

    /** 校验对象段存在且为对象（缺失可空——宽松；类型错误抛错）。 */
    private void requireSegment(ObjectNode planNode, String key, String label) {
        JsonNode seg = planNode.get(key);
        if (seg == null || seg.isNull() || !seg.isObject()) {
            throw new BizException("故事引擎缺少" + label + "段：" + key);
        }
    }

    /** 校验对象字段（缺失可空；非对象抛错）。 */
    private void checkObjectSegment(JsonNode parent, String key) {
        JsonNode v = parent == null ? null : parent.get(key);
        if (v == null || v.isNull()) {
            return;
        }
        if (!v.isObject()) {
            throw new BizException("故事引擎 " + key + " 应为对象");
        }
    }

    /** 校验字符串数组字段（缺失可空；非数组或含非字符串抛错）。 */
    private void checkStringArray(JsonNode parent, String key) {
        JsonNode v = parent == null ? null : parent.get(key);
        if (v == null || v.isNull()) {
            return;
        }
        if (!v.isArray()) {
            throw new BizException("字段 " + key + " 应为字符串数组");
        }
        for (JsonNode item : v) {
            if (!item.isTextual()) {
                throw new BizException("字段 " + key + " 数组元素应为字符串");
            }
        }
    }

    /**
     * 从校验通过的故事引擎 JSON 读取 story_plan 对象（供落库/渲染服务；容错返回 null）。
     *
     * @param storyPlanJson 校验后的故事引擎 JSON（{"story_plan": {...}}）
     *
     * @return story_plan 对象节点（解析失败返回 null）
     */
    public JsonNode readStoryPlan(String storyPlanJson) {
        if (storyPlanJson == null || storyPlanJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(storyPlanJson);
            return root.get(STORY_PLAN_KEY);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从校验通过的风格档案 JSON 读取 style_profile 对象（供落库/渲染服务；容错返回 null）。
     *
     * @param styleJson 校验后的风格档案 JSON（{"style_profile": {...}}）
     *
     * @return style_profile 对象节点（解析失败返回 null）
     */
    public JsonNode readStyleProfile(String styleJson) {
        if (styleJson == null || styleJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(styleJson);
            return root.get(STYLE_PROFILE_KEY);
        } catch (Exception ignored) {
            return null;
        }
    }
}
