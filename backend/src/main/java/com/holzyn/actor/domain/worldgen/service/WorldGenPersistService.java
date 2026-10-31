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
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.project.service.ProjectService;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.world.model.WorldCategory;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.LocationNormalizationService;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenDimensions;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界观生成 6 维映射落库服务。
 * <p>职责：把 build 工作流 6 个维度 Agent 产物映射进现有 {@code actor_world_setting} 分节
 * （core_laws→magicSystem、space_time→geography+地点表、society→factions、
 * history_culture→culture+history、existence/information→freeText 分节）与
 * {@code actor_world_location} 地点表（key_locations → 地点归一）：</p>
 * <ul>
 * <li><b>preview</b>：任务跑完后停留在「产物预览」态——把 6 维 JSON 解析 + 映射为
 * {@link WorldGenPreviewVO}（分节内容 + 地点候选），<b>不写库</b>；</li>
 * <li><b>confirm</b>：用户确认后落库——保存/更新世界观分节（版本化覆盖语义不变，
 * 防误覆盖：默认只经「确认」端点落库）+ 地点按名去重合并（复用 归一能力）；</li>
 * <li><b>script 节点产出</b>：persist_to_world_setting 脚本节点调用 preview 序列化为节点输出
 * （任务跑完即见「预览」），确认由用户后续触发。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldGenPersistService {

    /** 维度顺序 + 中文标签（链式生成顺序；与真实执行器/校验器共用 {@link WorldGenDimensions} 口径） */
    static final Map<String, String> DIMENSION_LABELS = WorldGenDimensions.LABELS;

    /** freeText 分节上限（WorldSettingDTO @Size(max=5000)，映射组装后截断） */
    private static final int FREE_TEXT_MAX = 5000;

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final ProjectService projectService;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository locationRepository;
    private final LocationNormalizationService locationNormalizationService;

    // ==================== 预览（不落库） ====================

    /**
     * 6 维产物 → 分节/地点预览（不写库，供前端展示 + 脚本节点输出）。
     *
     * @param taskId 任务 ID
     *
     * @return 预览 VO（维度 + 映射分节 + 地点候选）
     */
    public WorldGenPreviewVO preview(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        WorkflowDefinition def = requireDefinition(task);
        Map<String, JsonNode> dims = collectDimensionOutputs(taskId, def);
        return buildPreview(task, def, dims);
    }

    // ==================== 确认落库 ====================

    /**
     * 预览确认后落库：世界观分节（覆盖更新语义不变）+ 地点表按名去重合并（归一）。
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（分节各字段 + 地点新增数/候选数）
     */
    @Transactional
    public Map<String, Object> confirm(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        WorkflowDefinition def = requireDefinition(task);
        Map<String, JsonNode> dims = collectDimensionOutputs(taskId, def);
        WorldGenPreviewVO preview = buildPreview(task, def, dims);

        long start = System.currentTimeMillis();
        // ① 世界观分节落库（版本化覆盖：内容有变化才新增版本，保留历史——对齐现有保存语义）
        WorldSettingDTO dto = new WorldSettingDTO(
                truncate(preview.getSections().get("name"), 100),
                // 题材收敛到固定八类：AI 说什么都行，落库只能是那八个标签之一；
                // 映射不到时留空（saveWorldSetting 里 genre 空 → null），由用户在第 2 步下拉里补选，
                // 而不是猜一个（猜错会让整份世界观跑到另一套生成口径上）。
                WorldCategory.normalize(preview.getSections().get("genre")),
                truncate(inputOf(task, "tone"), 16),
                truncate(preview.getSections().get("era"), 50),
                preview.getSections().get("geography"),
                preview.getSections().get("factions"),
                preview.getSections().get("magicSystem"),
                preview.getSections().get("culture"),
                preview.getSections().get("history"),
                truncate(preview.getSections().get("freeText"), FREE_TEXT_MAX));
        projectService.saveWorldSetting(task.getProjectId(), dto, taskId);

        // ② 地点表按名去重合并（复用 归一能力：写入时归一为地点表规范引用）
        int added = mergeLocations(task.getProjectId(), preview.getLocations());

        // trace 贯穿：确认落库作为非 AI 环节记录（scene=worldgen，trace_id=taskId 与节点链同 trace）
        long finalStart = start;
        AiCallContext.withTrace(String.valueOf(taskId), () -> {
            AiCallContext.recordStep(task.getProjectId(), "worldgen", "persist",
                    "6 维映射落库：分节 " + preview.getSections().size() + " 字段，地点新增 " + added + " 个",
                    System.currentTimeMillis() - finalStart);
            return null;
        });

        log.info("[worldgen] 确认落库完成：task={}, project={}, 地点新增={}，耗时 {}ms",
                taskId, task.getProjectId(), added, System.currentTimeMillis() - start);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("projectId", task.getProjectId());
        out.put("sections", preview.getSections());
        out.put("locationAdded", added);
        out.put("locationCandidates", preview.getLocations().size());
        return out;
    }

    // ==================== 内部工具 ====================

    /**
     * 收集 6 个维度 Agent 节点的最新成功产物（缺失任一 → 400，预览/落库均需全量）。
     */
    private Map<String, JsonNode> collectDimensionOutputs(Long taskId, WorkflowDefinition def) {
        Map<String, JsonNode> dims = new LinkedHashMap<>();
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository.findByTaskIdOrderByIdAsc(taskId);
        Map<String, ActorWorldgenNodeAttempt> latestByNode = new LinkedHashMap<>();
        for (ActorWorldgenNodeAttempt a : attempts) {
            latestByNode.put(a.getNodeId(), a);
        }
        for (String nodeId : def.getExecutionOrder()) {
            WorkflowNode node = def.node(nodeId);
            if (node == null || !WorldGenStepType.AGENT.equals(node.getNodeType())) {
                continue;
            }
            String dimKey = dimKeyOf(node);
            if (!DIMENSION_LABELS.containsKey(dimKey)) {
                continue;
            }
            ActorWorldgenNodeAttempt latest = latestByNode.get(nodeId);
            if (latest == null || !WorldGenStatus.SUCCEEDED.equals(latest.getStatus())
                    || latest.getOutputJson() == null) {
                throw new BizException(400, "维度「" + DIMENSION_LABELS.get(dimKey) + "」尚未成功生成，无法预览/落库");
            }
            try {
                dims.put(dimKey, MAPPER.readTree(latest.getOutputJson()));
            } catch (Exception e) {
                throw new BizException(400, "维度「" + DIMENSION_LABELS.get(dimKey) + "」产物 JSON 解析失败：" + e.getMessage());
            }
        }
        if (dims.size() != DIMENSION_LABELS.size()) {
            throw new BizException(400, "世界观生成尚未完成：已成功 " + dims.size() + "/" + DIMENSION_LABELS.size() + " 个维度");
        }
        return dims;
    }

    /**
     * 组装预览 VO（维度 + 分节映射 + 地点候选）。
     */
    private WorldGenPreviewVO buildPreview(ActorWorldgenTask task, WorkflowDefinition def,
                                           Map<String, JsonNode> dims) {
        WorldGenPreviewVO vo = new WorldGenPreviewVO();
        vo.setTaskId(task.getId());
        vo.setProjectId(task.getProjectId());
        vo.setWorkflowCode(def.getCode());

        // 维度列表（顺序=标签定义顺序）
        for (Map.Entry<String, String> e : DIMENSION_LABELS.entrySet()) {
            JsonNode data = dims.get(e.getKey());
            if (data == null) {
                continue;
            }
            WorldGenPreviewVO.PreviewDimension d = new WorldGenPreviewVO.PreviewDimension();
            d.setKey(e.getKey());
            d.setLabel(e.getValue());
            JsonNode inner = unwrap(data, e.getKey());
            d.setEssence(inner == null ? "" : inner.path("essence").asText(""));
            d.setData(data);
            vo.getDimensions().add(d);
        }

        // 分节映射
        Map<String, String> sections = new LinkedHashMap<>();
        String projectName = projectRepository.findById(task.getProjectId())
                .map(ActorProject::getName).orElse("未命名项目");
        String existingName = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(task.getProjectId())
                .map(ActorWorldSetting::getName).orElse(null);
        sections.put("name", existingName != null && !existingName.isBlank() ? existingName : projectName);
        sections.put("genre", inputOf(task, "genre"));
        sections.put("era", prose(dims, "space_time", "era"));
        sections.put("magicSystem", assembleMagicSystem(dims));
        sections.put("geography", assembleGeography(dims));
        sections.put("factions", assembleFactions(dims));
        sections.put("culture", assembleCulture(dims));
        sections.put("history", assembleHistory(dims));
        sections.put("freeText", assembleFreeText(dims));
        vo.setSections(sections);

        // 地点候选（key_locations → actor_world_location 形态）
        vo.setLocations(extractLocations(dims));
        return vo;
    }

    // ==================== 分节组装（方案 §4 映射表） ====================

    /** core_laws → magicSystem（规则体系）：力量体系 + 公理铁律表 + 禁忌 + 力量显化 */
    private String assembleMagicSystem(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        appendProse(sb, dims, "core_laws", "power_system", "力量体系");
        appendTable(sb, dims, "core_laws", "axioms", "公理铁律",
                List.of("名称", "陈述", "代价", "边界", "执行"),
                List.of("name", "statement", "cost", "boundary", "enforcement"));
        appendList(sb, dims, "core_laws", "taboos", "禁忌");
        appendProse(sb, dims, "core_laws", "power_manifestation", "力量显化");
        return sb.toString();
    }

    /** space_time → geography（地理设定）+ era：世界格局 + 关键地点表 + 生态 + 环境质感 */
    private String assembleGeography(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        appendProse(sb, dims, "space_time", "world_layout", "世界格局");
        appendTable(sb, dims, "space_time", "key_locations", "关键地点",
                List.of("名称", "地形", "特征", "风险", "控制势力"),
                List.of("name", "terrain", "feature", "risk", "controlling_force"));
        appendProse(sb, dims, "space_time", "ecology", "生态");
        appendProse(sb, dims, "space_time", "environment_texture", "环境质感");
        return sb.toString();
    }

    /** society → factions（势力阵营）：种族表 + 阶层 + 政体 + 势力表 + 势力关系表 + 权力可见性 */
    private String assembleFactions(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        appendTable(sb, dims, "society", "races", "种族",
                List.of("名称", "特征", "人口", "社会地位"),
                List.of("name", "traits", "population", "social_status"));
        appendProse(sb, dims, "society", "class_structure", "阶层结构");
        appendProse(sb, dims, "society", "political_system", "政治制度");
        appendTable(sb, dims, "society", "forces", "势力",
                List.of("名称", "类型", "权力根基", "目标", "手段"),
                List.of("name", "type", "base_of_power", "goals", "methods"));
        appendTable(sb, dims, "society", "force_relations", "势力关系",
                List.of("来源", "目标", "关系", "张力点"),
                List.of("source", "target", "relation", "tension_point"));
        appendProse(sb, dims, "society", "power_visibility", "权力的日常可见性");
        return sb.toString();
    }

    /** history_culture → culture（文化风俗）：宗教表 + 风俗 + 经济 + 日常切片 */
    private String assembleCulture(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        appendTable(sb, dims, "history_culture", "religions", "宗教",
                List.of("名称", "核心信条", "信徒范围"),
                List.of("name", "core_belief", "follower_scope"));
        appendProse(sb, dims, "history_culture", "customs", "风俗习惯");
        appendProse(sb, dims, "history_culture", "economy", "经济");
        appendProse(sb, dims, "history_culture", "daily_slice", "日常切片");
        return sb.toString();
    }

    /** history_culture → history（历史背景）：重大事件表 */
    private String assembleHistory(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        appendTable(sb, dims, "history_culture", "major_events", "重大事件",
                List.of("事件", "时代", "持久影响"),
                List.of("event", "era", "lasting_impact"));
        return sb.toString();
    }

    /** essence 汇总 + existence/information → freeText（首期并入自由文本分节） */
    private String assembleFreeText(Map<String, JsonNode> dims) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 核心定调\n\n");
        for (Map.Entry<String, String> e : DIMENSION_LABELS.entrySet()) {
            JsonNode inner = unwrap(dims.get(e.getKey()), e.getKey());
            String essence = inner == null ? "" : inner.path("essence").asText("").trim();
            if (!essence.isBlank()) {
                sb.append("- ").append(e.getValue()).append("：").append(essence).append("\n");
            }
        }
        sb.append("\n## 五、存在基础\n\n");
        appendProse(sb, dims, "existence", "calendar", "历法");
        appendProse(sb, dims, "existence", "lifespan", "寿命与衰老");
        appendProse(sb, dims, "existence", "death", "死亡");
        appendProse(sb, dims, "existence", "disease_and_birth", "疾病与生育");
        sb.append("\n## 六、信息传播\n\n");
        appendProse(sb, dims, "information", "info_speed", "信息流速");
        appendProse(sb, dims, "information", "knowledge_medium", "知识媒介");
        appendProse(sb, dims, "information", "info_barriers", "信息壁垒");
        appendProse(sb, dims, "information", "rumor_and_truth", "谣言与真相");
        return truncate(sb.toString(), FREE_TEXT_MAX);
    }

    /** key_locations → 地点候选（name/type=terrain/intro=feature+风险+控制势力） */
    private List<WorldGenPreviewVO.PreviewLocation> extractLocations(Map<String, JsonNode> dims) {
        List<WorldGenPreviewVO.PreviewLocation> out = new ArrayList<>();
        JsonNode inner = unwrap(dims.get("space_time"), "space_time");
        if (inner == null) {
            return out;
        }
        JsonNode locations = inner.get("key_locations");
        if (locations != null && locations.isArray()) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode loc : locations) {
                String name = loc.path("name").asText("").trim();
                if (name.isBlank() || !seen.add(name)) {
                    continue;
                }
                WorldGenPreviewVO.PreviewLocation pl = new WorldGenPreviewVO.PreviewLocation();
                pl.setName(name);
                pl.setType(loc.path("terrain").asText("").trim());
                StringBuilder intro = new StringBuilder();
                appendField(intro, loc.path("feature"), "特征");
                appendField(intro, loc.path("risk"), "风险");
                appendField(intro, loc.path("controlling_force"), "控制势力");
                pl.setIntro(intro.toString());
                out.add(pl);
            }
        }
        return out;
    }

    // ==================== 组装小工具 ====================

    /** prose 字段（空跳过） */
    private void appendProse(StringBuilder sb, Map<String, JsonNode> dims, String dimKey,
                             String field, String label) {
        JsonNode inner = unwrap(dims.get(dimKey), dimKey);
        String v = inner == null ? "" : inner.path(field).asText("").trim();
        if (v.isBlank()) {
            return;
        }
        sb.append("### ").append(label).append("\n\n").append(v).append("\n\n");
    }

    /** table 字段（空跳过） */
    private void appendTable(StringBuilder sb, Map<String, JsonNode> dims, String dimKey,
                             String field, String label, List<String> headers, List<String> cols) {
        JsonNode inner = unwrap(dims.get(dimKey), dimKey);
        JsonNode rows = inner == null ? null : inner.get(field);
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return;
        }
        sb.append("### ").append(label).append("\n\n")
                .append(WorldGenRenderer.renderTable(rows, headers, cols)).append("\n\n");
    }

    /** list 字段（空跳过） */
    private void appendList(StringBuilder sb, Map<String, JsonNode> dims, String dimKey,
                            String field, String label) {
        JsonNode inner = unwrap(dims.get(dimKey), dimKey);
        JsonNode items = inner == null ? null : inner.get(field);
        if (items == null || !items.isArray() || items.isEmpty()) {
            return;
        }
        sb.append("### ").append(label).append("\n\n")
                .append(WorldGenRenderer.renderList(items)).append("\n\n");
    }

    /** 地点简介字段（「标签：值」形式，空跳过） */
    private void appendField(StringBuilder sb, JsonNode v, String label) {
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append(label).append("：").append(v.isTextual() ? v.asText() : v.toString());
    }

    /** 取维度 prose 字段文本（空→空串） */
    private String prose(Map<String, JsonNode> dims, String dimKey, String field) {
        JsonNode inner = unwrap(dims.get(dimKey), dimKey);
        return inner == null ? "" : inner.path(field).asText("");
    }

    /** 解开维度包裹（{"dim": {...}} → {...}；兼容裸对象） */
    private JsonNode unwrap(JsonNode data, String dimKey) {
        if (data == null || !data.isObject()) {
            return null;
        }
        if (data.has(dimKey) && data.get(dimKey).isObject()) {
            return data.get(dimKey);
        }
        return data;
    }

    /** 任务输入字段（缺省空串） */
    private String inputOf(ActorWorldgenTask task, String key) {
        if (task.getInputJson() == null || task.getInputJson().isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(task.getInputJson()).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 地点合并（按名去重 + 归一：写入即归一为地点表规范引用） */
    private int mergeLocations(Long projectId, List<WorldGenPreviewVO.PreviewLocation> candidates) {
        int added = 0;
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        Set<String> existing = new LinkedHashSet<>();
        locationRepository.findByProjectId(projectId).forEach(l -> existing.add(norm(l.getName())));
        int maxSort = locationRepository.findByProjectId(projectId).stream()
                .mapToInt(l -> l.getSortOrder() == null ? 0 : l.getSortOrder()).max().orElse(0);
        int i = 0;
        for (WorldGenPreviewVO.PreviewLocation pl : candidates) {
            if (pl.getName() == null || pl.getName().isBlank() || !existing.add(norm(pl.getName()))) {
                continue;
            }
            // 归一：命中地点表（不应发生，因已按名去重）则复用 id；未命中落自由文本名称
            String normalizedName = locationNormalizationService.normalizedName(projectId, pl.getName());
            ActorWorldLocation entity = new ActorWorldLocation();
            entity.setProjectId(projectId);
            entity.setName(truncate(normalizedName == null ? pl.getName() : normalizedName, 100));
            entity.setType(truncate(pl.getType(), 50));
            entity.setIntro(truncate(pl.getIntro(), 2000));
            entity.setImportance(3);
            entity.setSortOrder(maxSort + 1 + i++);
            locationRepository.save(entity);
            added++;
        }
        if (added > 0) {
            log.info("[worldgen] 地点表合并新增 {} 个（候选 {} 个）", added, candidates.size());
        }
        return added;
    }

    /** 节点 → 维度键（经 {@link WorldGenDimensions} 显式映射，Agent 语义名末段无下划线不可切分推导） */
    private String dimKeyOf(WorkflowNode node) {
        String dimKey = WorldGenDimensions.dimKeyOf(node.getAgentType());
        return dimKey != null ? dimKey : node.getId();
    }

    /** 名称归一（去空格/大小写，用于去重） */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 字符串截断（null 安全） */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 加载任务（缺失抛 404） */
    private ActorWorldgenTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(404, "任务不存在：" + taskId));
    }

    /** 解析任务对应工作流定义（缺失抛 404） */
    private WorkflowDefinition requireDefinition(ActorWorldgenTask task) {
        ActorWorldgenWorkflow workflow = workflowRepository.findById(task.getWorkflowId())
                .orElseThrow(() -> new BizException(404, "任务关联的工作流定义不存在：" + task.getWorkflowId()));
        return definitionParser.parse(workflow.getCode(), workflow.getName(), workflow.getVersion(),
                workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
    }
}
