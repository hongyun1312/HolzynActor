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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界观合并渲染器。
 * <p>职责：把 6 个维度 JSON 按维度 Schema 渲染为层级清晰的 Markdown（对齐方案 §4 渲染策略）：</p>
 * <ul>
 * <li>prose → 段落；table → Markdown 表格（表头/列取维度 Schema 定义）；list → 无序列表；</li>
 * <li>essence → {@code > } 引用定调句（每维度最前）；空字段跳过；</li>
 * <li>输出 {@code data/worldgen/&lt;project&gt;/&lt;task&gt;/meta/world_foundation.md}（对齐 bishu build 落盘惯例）；</li>
 * <li>缺某维度文件 → 警告并跳过（不阻断已生成维度渲染）。</li>
 * </ul>
 * <p>说明：维度 Schema（字段键/中文标签/表格列）为事实性结构，自写实现，不复制参考项目代码文案。</p>
 */
@Slf4j
@Component
public class WorldGenRenderer {

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 维度渲染顺序（链式生成顺序） */
    private static final List<String> DIMENSION_ORDER = List.of(
            "core_laws", "space_time", "society", "history_culture", "existence", "information");

    /** 维度渲染 Schema：维度键 → 中文标题 + 字段（类型/标签/表头/列） */
    private record RenderField(String key, String label, String type, List<String> headers, List<String> cols) {
    }

    private record RenderDimension(String title, List<RenderField> fields) {
    }

    /** 渲染 Schema（字段键对齐方案 §2.4 维度字段；自写中文标签与表格结构） */
    private static final Map<String, RenderDimension> RENDER_SCHEMA = buildRenderSchema();

    private static Map<String, RenderDimension> buildRenderSchema() {
        Map<String, RenderDimension> map = new LinkedHashMap<>();
        map.put("core_laws", new RenderDimension("一、核心法则", List.of(
                new RenderField("power_system", "力量体系", "prose", null, null),
                new RenderField("axioms", "公理铁律", "table",
                        List.of("名称", "陈述", "代价", "边界", "执行"), List.of("name", "statement", "cost", "boundary", "enforcement")),
                new RenderField("taboos", "禁忌", "list", null, null),
                new RenderField("power_manifestation", "力量显化", "prose", null, null))));
        map.put("space_time", new RenderDimension("二、时空地理", List.of(
                new RenderField("world_layout", "世界格局", "prose", null, null),
                new RenderField("key_locations", "关键地点", "table",
                        List.of("名称", "地形", "特征", "风险", "控制势力"), List.of("name", "terrain", "feature", "risk", "controlling_force")),
                new RenderField("ecology", "生态", "prose", null, null),
                new RenderField("era", "时代", "prose", null, null),
                new RenderField("environment_texture", "环境质感", "prose", null, null))));
        map.put("society", new RenderDimension("三、社会权力", List.of(
                new RenderField("races", "种族", "table",
                        List.of("名称", "特征", "人口", "社会地位"), List.of("name", "traits", "population", "social_status")),
                new RenderField("class_structure", "阶层结构", "prose", null, null),
                new RenderField("political_system", "政治制度", "prose", null, null),
                new RenderField("forces", "势力", "table",
                        List.of("名称", "类型", "权力根基", "目标", "手段"), List.of("name", "type", "base_of_power", "goals", "methods")),
                new RenderField("force_relations", "势力关系", "table",
                        List.of("来源", "目标", "关系", "张力点"), List.of("source", "target", "relation", "tension_point")),
                new RenderField("power_visibility", "权力的日常可见性", "prose", null, null))));
        map.put("history_culture", new RenderDimension("四、历史文化", List.of(
                new RenderField("major_events", "重大事件", "table",
                        List.of("事件", "时代", "持久影响"), List.of("event", "era", "lasting_impact")),
                new RenderField("religions", "宗教", "table",
                        List.of("名称", "核心信条", "信徒范围"), List.of("name", "core_belief", "follower_scope")),
                new RenderField("customs", "风俗习惯", "prose", null, null),
                new RenderField("economy", "经济", "prose", null, null),
                new RenderField("daily_slice", "日常切片", "prose", null, null))));
        map.put("existence", new RenderDimension("五、存在基础", List.of(
                new RenderField("calendar", "历法", "prose", null, null),
                new RenderField("lifespan", "寿命与衰老", "prose", null, null),
                new RenderField("death", "死亡", "prose", null, null),
                new RenderField("disease_and_birth", "疾病与生育", "prose", null, null))));
        map.put("information", new RenderDimension("六、信息传播", List.of(
                new RenderField("info_speed", "信息流速", "prose", null, null),
                new RenderField("knowledge_medium", "知识媒介", "prose", null, null),
                new RenderField("info_barriers", "信息壁垒", "prose", null, null),
                new RenderField("rumor_and_truth", "谣言与真相", "prose", null, null))));
        return map;
    }

    private final LocalArchiveService archiveService;

    public WorldGenRenderer(LocalArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /**
     * 渲染整个世界观：读 6 个 world/*.json → 拼接 Markdown → 写 meta/world_foundation.md。
     *
     * @param workspace 任务工作区
     *
     * @param title 一级标题（默认「世界观基础」）
     *
     * @return 渲染结果（sections=已渲染维度数，markdown=完整文档，outputPath=相对输出路径）
     */
    public RenderResult render(Path workspace, String title) {
        List<String> sections = new ArrayList<>();
        int rendered = 0;
        for (String dimKey : DIMENSION_ORDER) {
            String relative = "world/" + dimKey + ".json";
            Path f = archiveService.safeResolve(workspace, relative);
            if (!Files.isRegularFile(f)) {
                log.warn("[worldgen-render] 缺少维度文件，跳过：{}", relative);
                continue;
            }
            try {
                JsonNode data = MAPPER.readTree(Files.readString(f, StandardCharsets.UTF_8));
                sections.add(renderDimension(data, dimKey));
                rendered++;
            } catch (Exception e) {
                log.warn("[worldgen-render] 维度渲染失败，跳过：{}：{}", relative, e.getMessage());
            }
        }
        if (sections.isEmpty()) {
            throw new BizException(400, "没有可渲染的维度文件");
        }
        String markdown = "# " + (title == null || title.isBlank() ? "世界观基础" : title)
                + "\n\n" + String.join("\n\n---\n\n", sections) + "\n";
        archiveService.writeText(workspace, "meta/world_foundation.md", markdown);
        log.info("[worldgen-render] 已生成 world_foundation.md：{} 个维度，{} 字符", rendered, markdown.length());
        return new RenderResult(rendered, markdown, "meta/world_foundation.md");
    }

    /**
     * 渲染单个维度为 Markdown section。
     *
     * @param data 维度 JSON（{@code {"&lt;dim&gt;": {...}}} 包裹形态，与校验器输出一致）
     *
     * @param dimKey 维度键
     */
    public String renderDimension(JsonNode data, String dimKey) {
        JsonNode inner = data;
        if (data != null && data.isObject() && data.has(dimKey) && data.get(dimKey).isObject()) {
            inner = data.get(dimKey);
        }
        RenderDimension schema = RENDER_SCHEMA.get(dimKey);
        if (schema == null) {
            // 退化：未知维度用简单标题
            return "## " + dimKey.replace("_", " ") + "\n\n"
                    + (inner == null ? "" : inner.toPrettyString());
        }
        List<String> parts = new ArrayList<>();
        parts.add("## " + schema.title());
        String essence = inner == null ? "" : inner.path("essence").asText("").trim();
        if (!essence.isBlank()) {
            parts.add("> " + essence);
        }
        for (RenderField field : schema.fields()) {
            JsonNode value = inner == null ? null : inner.get(field.key());
            if (value == null || value.isNull() || value.isMissingNode()
                    || (value.isTextual() && value.asText().isBlank())
                    || (value.isArray() && value.isEmpty())) {
                continue; // 跳过空字段
            }
            parts.add("### " + field.label());
            switch (field.type()) {
                case "prose" -> parts.add(value.asText().strip());
                case "table" -> parts.add(renderTable(value, field.headers(), field.cols()));
                case "list" -> parts.add(renderList(value));
                default -> {
                    // 未知类型按文本
                    parts.add(value.asText());
                }
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 把对象数组渲染为 Markdown 表格（单元格换行压成空格，列表值用「；」连接）。
     *
     * @param rows 对象数组节点
     *
     * @param headers 表头（与 cols 同序）
     *
     * @param cols 取值列名
     *
     * @return Markdown 表格文本
     */
    public static String renderTable(JsonNode rows, List<String> headers, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append(String.join("|", headers.stream().map(h -> "------").toList())).append("|\n");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (String col : cols) {
                    JsonNode v = row.get(col);
                    String val = "";
                    if (v != null && !v.isNull()) {
                        if (v.isArray()) {
                            List<String> items = new ArrayList<>();
                            for (JsonNode item : v) {
                                items.add(item.isTextual() ? item.asText() : item.toString());
                            }
                            val = String.join("；", items);
                        } else {
                            val = v.asText();
                        }
                    }
                    cells.add(val.replace("\n", " "));
                }
                sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            }
        }
        return sb.toString();
    }

    /**
     * 把字符串数组渲染为 Markdown 无序列表。
     *
     * @param items 数组节点
     *
     * @return 无序列表文本
     */
    public static String renderList(JsonNode items) {
        StringBuilder sb = new StringBuilder();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                sb.append("- ").append(item.isTextual() ? item.asText() : item.toString()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 渲染结果（script 节点输出 JSON 用） */
    public record RenderResult(int sections, String markdown, String outputPath) {
    }
}
