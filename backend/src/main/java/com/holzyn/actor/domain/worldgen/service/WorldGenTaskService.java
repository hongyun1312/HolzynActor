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
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.worldgen.dto.CreateWorldGenTaskRequest;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorkflowVariable;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import com.holzyn.actor.domain.worldgen.vo.WorldGenAttemptVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenNodeVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenTaskVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenWorkflowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界观生成任务服务。
 * <p>职责：</p>
 * <ul>
 * <li>工作流定义列表（GET /workflows）；</li>
 * <li>创建任务（POST /tasks）：校验项目/工作流/必填输入 → 冻结输入 → 建任务（running）→ 异步执行；</li>
 * <li>任务/节点查询（GET /tasks/{id}、GET /tasks/{id}/nodes/{nodeId}）：节点进度 + 尝试历史 + Token 汇总；</li>
 * <li>失败节点原地续跑（POST .../retry|skip）：CAS expected_attempt_count 乐观锁防并发双击，
 * 重试=重新执行该节点后继续下游；跳过=该节点标记 skipped 后从下一节点继续。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorldGenTaskService {

    /** 时间格式化（VO 展示用） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final ActorProjectRepository projectRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final WorldGenTaskRunner taskRunner;
    private final WorldGenPersistService persistService;

    /**
     * character 工作流专用服务。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldGenCharacterService characterService;

    /**
     * story-plan 工作流专用服务。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldGenStoryPlanService storyPlanService;

    /**
     * 项目故事规划列表：版本链（升序=历史），
     * 每版含 JSON 原文/Markdown 渲染/氛围片段/时间；未装配或暂无记录返回空列表。
     *
     * @param projectId 项目 ID
     *
     * @return 故事规划版本列表（Map 序列化友好）
     */
    public List<Map<String, Object>> listStoryPlans(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (storyPlanService == null) {
            return out;
        }
        for (var row : storyPlanService.listByProject(projectId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("projectId", row.getProjectId());
            m.put("taskId", row.getTaskId());
            m.put("version", row.getVersion());
            m.put("storyPlanJson", parseJsonObject(row.getStoryPlanJson()));
            m.put("styleProfileJson", parseJsonObject(row.getStyleProfileJson()));
            m.put("storyPlanMd", row.getStoryPlanMd());
            m.put("styleProfileMd", row.getStyleProfileMd());
            m.put("atmosphereSegment", row.getAtmosphereSegment());
            m.put("createdAt", formatTime(row.getCreatedAt()));
            out.add(m);
        }
        return out;
    }

    /**
     * 完整项目串接编排器（字段注入——confirm(mode=full) 后触发；测试直接 new 时为空 → 不串接）。
     * <p>⚠️ @Lazy 破循环：pipelineService 构造器注入本服务（异步线程才真正调用），
     * 字段级 @Lazy 注入代理，Spring 容器装配时无需真正实例化 pipelineService，
     * 避免 taskService → pipelineService → taskService 循环引用（Spring 默认禁止）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WorldGenPipelineService pipelineService;

    /**
     * character 工作流输入注入：worldSetting/genre 缺省时从项目最新世界设定自动拼装。
     *
     * @param req 创建请求
     *
     * @param inputs 已冻结的输入 Map（原地补全）
     */
    private void enrichCharacterInputs(CreateWorldGenTaskRequest req, Map<String, String> inputs) {
        if (!WorldGenCharacterService.CODE_CHARACTER_V1.equals(req.workflowCode())
                || characterService == null) {
            return;
        }
        Map<String, String> injected = characterService.buildCharacterInputs(req.projectId());
        if ((inputs.get("worldSetting") == null || inputs.get("worldSetting").isBlank())
                && injected.get("worldSetting") != null && !injected.get("worldSetting").isBlank()) {
            inputs.put("worldSetting", injected.get("worldSetting"));
        }
        if ((inputs.get("genre") == null || inputs.get("genre").isBlank())
                && injected.get("genre") != null && !injected.get("genre").isBlank()) {
            inputs.put("genre", injected.get("genre"));
        }
    }

    /**
     * story-plan 工作流输入注入：
     * worldSetting/characterSummaries/genre 缺省时从项目最新世界设定 + 既有角色自动拼装。
     *
     * @param req 创建请求
     *
     * @param inputs 已冻结的输入 Map（原地补全）
     */
    private void enrichStoryInputs(CreateWorldGenTaskRequest req, Map<String, String> inputs) {
        if (!WorldGenStoryPlanService.CODE_STORY_PLAN_V1.equals(req.workflowCode())
                || storyPlanService == null) {
            return;
        }
        Map<String, String> injected = storyPlanService.buildStoryInputs(req.projectId());
        for (String key : new String[]{"worldSetting", "characterSummaries", "genre"}) {
            if ((inputs.get(key) == null || inputs.get(key).isBlank())
                    && injected.get(key) != null && !injected.get(key).isBlank()) {
                inputs.put(key, injected.get(key));
            }
        }
    }

    // ==================== 工作流定义 ====================

    /**
     * 可用工作流定义列表（GET /api/worldgen/workflows）。
     *
     * @return 启用的工作流 VO 列表
     */
    public List<WorldGenWorkflowVO> listWorkflows() {
        List<WorldGenWorkflowVO> vos = new ArrayList<>();
        for (ActorWorldgenWorkflow w : workflowRepository.findByEnabledOrderByIdAsc(1)) {
            WorldGenWorkflowVO vo = new WorldGenWorkflowVO();
            vo.setId(w.getId());
            vo.setCode(w.getCode());
            vo.setName(w.getName());
            vo.setVersion(w.getVersion());
            vo.setEnabled(w.getEnabled());
            vo.setNodeCount(countNodes(w.getNodesJson()));
            vo.setUpdatedAt(formatTime(w.getUpdatedAt()));
            vos.add(vo);
        }
        return vos;
    }

    // ==================== 任务创建 ====================

    /**
     * 创建并异步执行世界观生成任务（POST /api/worldgen/tasks）。
     *
     * @param req 创建请求（projectId/workflowCode/inputs）
     *
     * @return 新任务 ID
     */
    public Long createTask(CreateWorldGenTaskRequest req) {
        ActorWorldgenWorkflow workflow = workflowRepository.findByCode(req.workflowCode())
                .orElseThrow(() -> new BizException(404, "工作流不存在：" + req.workflowCode()));
        if (!Integer.valueOf(1).equals(workflow.getEnabled())) {
            throw new BizException(400, "工作流已停用：" + req.workflowCode());
        }
        if (req.projectId() == null || !projectRepository.existsById(req.projectId())) {
            throw new BizException(404, "项目不存在：" + req.projectId());
        }
        // 预解析定义（提前暴露定义损坏/环等配置错误）
        WorkflowDefinition def = definitionParser.parse(workflow.getCode(), workflow.getName(),
                workflow.getVersion(), workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
        if (def.getExecutionOrder().isEmpty()) {
            throw new BizException(400, "工作流无可执行节点：" + req.workflowCode());
        }

        // 输入冻结：应用变量默认值 + 必填校验
        Map<String, String> inputs = new LinkedHashMap<>();
        if (req.inputs() != null) {
            inputs.putAll(req.inputs());
        }
        for (WorkflowVariable v : def.getVariables()) {
            if (!inputs.containsKey(v.getKey()) && v.getDefaultValue() != null) {
                inputs.put(v.getKey(), v.getDefaultValue());
            }
        }
        for (WorkflowVariable v : def.getVariables()) {
            if (v.required() && (inputs.get(v.getKey()) == null || inputs.get(v.getKey()).isBlank())) {
                throw new BizException(400, "缺少必填输入：" + v.getKey() + "（" + v.getName() + "）");
            }
        }
        // 适配：character 工作流自动注入世界设定（worldSetting/genre 缺省时从项目最新设定拼装，
        // 供阵容节点锚定「角色须活在设定里」；字段注入缺失时降级为不注入，零阻断）
        enrichCharacterInputs(req, inputs);
        // story-plan 适配（_06）：story-plan 工作流自动注入世界设定 + 角色摘要
        enrichStoryInputs(req, inputs);
        // 生成模式落 input_json（confirm 时读取决定是否串接完整模式）
        inputs.put("_mode", req.mode() == null || req.mode().isBlank()
                ? CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY : req.mode());

        ActorWorldgenTask task = new ActorWorldgenTask();
        task.setWorkflowId(workflow.getId());
        task.setProjectId(req.projectId());
        task.setInputJson(writeJson(inputs));
        task.setStatus(WorldGenStatus.RUNNING);
        taskRepository.save(task);
        log.info("[worldgen] 创建任务：id={}, workflow={}, project={}", task.getId(), req.workflowCode(), req.projectId());
        // 异步执行节点链（SSE 进度经 hub 广播）
        taskRunner.runAsync(task.getId());
        return task.getId();
    }

    // ==================== 查询 ====================

    /**
     * 任务详情（GET /api/worldgen/tasks/{taskId}）。
     *
     * @param taskId 任务 ID
     *
     * @return 任务 VO（节点进度 + 尝试历史 + Token 汇总）
     */
    public WorldGenTaskVO getTask(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        WorkflowDefinition def = requireDefinition(task);
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository.findByTaskIdOrderByIdAsc(taskId);
        Map<String, List<ActorWorldgenNodeAttempt>> byNode = new LinkedHashMap<>();
        for (ActorWorldgenNodeAttempt a : attempts) {
            byNode.computeIfAbsent(a.getNodeId(), k -> new ArrayList<>()).add(a);
        }

        WorldGenTaskVO vo = new WorldGenTaskVO();
        vo.setId(task.getId());
        vo.setWorkflowCode(def.getCode());
        vo.setWorkflowName(def.getName());
        vo.setProjectId(task.getProjectId());
        vo.setStatus(task.getStatus());
        vo.setInput(parseJsonObject(task.getInputJson()));
        vo.setCurrentNodeId(task.getCurrentNodeId());
        vo.setCreatedAt(formatTime(task.getCreatedAt()));
        vo.setFinishedAt(formatTime(task.getFinishedAt()));

        long tokenIn = 0, tokenOut = 0, duration = 0;
        for (String nodeId : def.getExecutionOrder()) {
            List<ActorWorldgenNodeAttempt> nodeAttempts = byNode.get(nodeId);
            WorldGenNodeVO nodeVo = buildNodeVO(def, nodeId, nodeAttempts);
            if (nodeVo != null) {
                vo.getNodes().add(nodeVo);
                for (WorldGenAttemptVO a : nodeVo.getAttempts()) {
                    tokenIn += a.getTokenIn() == null ? 0 : a.getTokenIn();
                    tokenOut += a.getTokenOut() == null ? 0 : a.getTokenOut();
                    duration += a.getDurationMs() == null ? 0 : a.getDurationMs();
                }
            }
        }
        vo.setTokenIn(tokenIn);
        vo.setTokenOut(tokenOut);
        vo.setDurationMs(duration);
        return vo;
    }

    /**
     * 节点详情（GET /api/worldgen/tasks/{taskId}/nodes/{nodeId}）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @return 节点 VO（尝试历史）
     */
    public WorldGenNodeVO getNode(Long taskId, String nodeId) {
        ActorWorldgenTask task = requireTask(taskId);
        WorkflowDefinition def = requireDefinition(task);
        if (def.node(nodeId) == null) {
            throw new BizException(404, "节点不存在：" + nodeId);
        }
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository
                .findByTaskIdAndNodeIdOrderByIdAsc(taskId, nodeId);
        WorldGenNodeVO vo = buildNodeVO(def, nodeId, attempts);
        if (vo == null) {
            throw new BizException(404, "节点未执行：" + nodeId);
        }
        return vo;
    }

    // ==================== 失败节点原地续跑（retry / skip，CAS expected_attempt_count） ====================

    /**
     * 失败节点原地重试（POST .../nodes/{nodeId}/retry）：重新执行该节点后继续下游。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @param expectedAttemptCount CAS：客户端看到的该节点当前尝试数
     *
     * @return 重试发起后的任务 VO（状态 running，节点进度异步推进）
     */
    public WorldGenTaskVO retryNode(Long taskId, String nodeId, Integer expectedAttemptCount) {
        NodeOperationGuard guard = prepareNodeOperation(taskId, nodeId, expectedAttemptCount, "重试");
        // 置回 running 并续跑（从该节点自身序号起）
        guard.task().setStatus(WorldGenStatus.RUNNING);
        guard.task().setCurrentNodeId(nodeId);
        guard.task().setFinishedAt(null);
        taskRepository.save(guard.task());
        log.info("[worldgen] 节点重试：task={}, node={}, expectedAttempt={}", taskId, nodeId, expectedAttemptCount);
        taskRunner.resumeFromAsync(taskId, guard.orderIndex());
        return getTask(taskId);
    }

    /**
     * 失败节点跳过（POST .../nodes/{nodeId}/skip）：标记 skipped 后从下一节点继续。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @param expectedAttemptCount CAS：客户端看到的该节点当前尝试数
     *
     * @return 跳过后的任务 VO（状态 running，节点进度异步推进）
     */
    public WorldGenTaskVO skipNode(Long taskId, String nodeId, Integer expectedAttemptCount) {
        NodeOperationGuard guard = prepareNodeOperation(taskId, nodeId, expectedAttemptCount, "跳过");
        // 一行 skipped 尝试（不可变记录语义，与重试追加行一致）
        ActorWorldgenNodeAttempt skipped = new ActorWorldgenNodeAttempt();
        skipped.setTaskId(taskId);
        skipped.setNodeId(nodeId);
        skipped.setNodeOrder(guard.orderIndex());
        skipped.setStepType(guard.node().getNodeType());
        skipped.setStatus(WorldGenStatus.SKIPPED);
        skipped.setRetryCount(guard.currentCount());
        // 输入沿用上次失败尝试（可追溯）；输出/错误置空
        skipped.setInputJson(guard.latest().getInputJson());
        skipped.setTokenIn(0);
        skipped.setTokenOut(0);
        skipped.setDurationMs(0L);
        attemptRepository.save(skipped);

        guard.task().setStatus(WorldGenStatus.RUNNING);
        guard.task().setCurrentNodeId(nodeId);
        guard.task().setFinishedAt(null);
        taskRepository.save(guard.task());
        log.info("[worldgen] 节点跳过：task={}, node={}, expectedAttempt={}", taskId, nodeId, expectedAttemptCount);
        // 从下一节点序号续跑
        taskRunner.resumeFromAsync(taskId, guard.orderIndex() + 1);
        return getTask(taskId);
    }

    /**
     * 节点操作前置校验（CAS + 状态守卫）结果。
     *
     * @param task 任务
     *
     * @param node 节点定义
     *
     * @param orderIndex 节点执行序号
     *
     * @param currentCount 节点当前尝试数
     *
     * @param latest 最新尝试
     */
    private record NodeOperationGuard(ActorWorldgenTask task, WorkflowNode node, int orderIndex,
                                      int currentCount, ActorWorldgenNodeAttempt latest) {
    }

    /**
     * retry/skip 公共前置校验：
     * 任务存在 → 任务非运行中/非终态成功 → 节点存在 → CAS（尝试数一致）→ 最新尝试为 failed。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @param expectedAttemptCount 客户端期望的尝试数
     *
     * @param action 操作名（错误提示用）
     *
     * @return 校验结果（任务/节点/序号/尝试数/最新尝试）
     */
    private NodeOperationGuard prepareNodeOperation(Long taskId, String nodeId, Integer expectedAttemptCount,
                                                    String action) {
        ActorWorldgenTask task = requireTask(taskId);
        if (WorldGenStatus.RUNNING.equals(task.getStatus())) {
            throw new BizException(409, "任务运行中，不能" + action + "（任务 " + taskId + "）");
        }
        if (WorldGenStatus.SUCCEEDED.equals(task.getStatus())) {
            throw new BizException(409, "任务已成功，无需" + action);
        }
        if (WorldGenStatus.CANCELED.equals(task.getStatus())) {
            throw new BizException(409, "任务已取消，不能" + action);
        }
        WorkflowDefinition def = requireDefinition(task);
        WorkflowNode node = def.node(nodeId);
        if (node == null) {
            throw new BizException(404, "节点不存在：" + nodeId);
        }
        Integer orderIndex = def.orderOf(nodeId);
        if (orderIndex == null) {
            throw new BizException(400, "节点不在执行链中：" + nodeId);
        }
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository
                .findByTaskIdAndNodeIdOrderByIdAsc(taskId, nodeId);
        int currentCount = attempts.size();
        if (expectedAttemptCount == null || currentCount != expectedAttemptCount) {
            throw new BizException(409, "节点尝试数不匹配（CAS 冲突）：当前 " + currentCount
                    + "，期望 " + expectedAttemptCount + "，请刷新后重试");
        }
        if (attempts.isEmpty()) {
            throw new BizException(400, "节点从未执行，不能" + action + "：" + nodeId);
        }
        ActorWorldgenNodeAttempt latest = attempts.get(attempts.size() - 1);
        if (!WorldGenStatus.FAILED.equals(latest.getStatus())) {
            throw new BizException(409, "仅可" + action + "失败节点（当前状态：" + latest.getStatus() + "）");
        }
        return new NodeOperationGuard(task, node, orderIndex, currentCount, latest);
    }

    /**
     * 任务产物预览：
     * 6 维产物 → 分节/地点映射预览，不写库（先预览确认再落库，防未确认覆盖现有世界观）。
     *
     * @param taskId 任务 ID
     *
     * @return 预览 VO
     */
    public com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO previewTask(Long taskId) {
        requireTask(taskId);
        return persistService.preview(taskId);
    }

    /**
     * 预览确认后落库：
     * 6 维产物映射写入 actor_world_setting 分节 + actor_world_location 地点表。
     * <p>适配（_05）：任务创建时 mode=full（完整项目）→ 落库后自动串接
     * character 工作流 + 世界初始化 6 步（由 {@link WorldGenPipelineService} 编排，
     * 返回 characterTaskId 供前端订阅串接进度）；mode=worldview_only（默认）→ 现状行为不变。</p>
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（分节 + 地点新增数；完整模式含 characterTaskId/pipelineStarted）
     */
    public Map<String, Object> confirmTask(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        // persistService.confirm 返回不可变 Map（Map.of）→ 拷贝为可变后追加模式标记
        Map<String, Object> out = new LinkedHashMap<>(persistService.confirm(taskId));
        // ：完整模式串接（mode 存于 input_json._mode；字段注入缺失（测试环境）→ 不串接，零破坏）
        if (pipelineService != null && CreateWorldGenTaskRequest.MODE_FULL.equals(modeOf(task))) {
            Long characterTaskId = pipelineService.startFullPipeline(task.getProjectId(), taskId);
            out.put("pipelineStarted", true);
            out.put("characterTaskId", characterTaskId);
            out.put("pipelineStreamEndpoint", "/api/worldgen/pipelines/" + taskId + "/stream");
        } else {
            out.put("pipelineStarted", false);
        }
        return out;
    }

    /**
     * 取任务生成模式（input_json._mode；缺省 worldview_only）。
     *
     * @param task 任务实体
     *
     * @return 模式（worldview_only/full）
     */
    static String modeOf(ActorWorldgenTask task) {
        if (task.getInputJson() == null || task.getInputJson().isBlank()) {
            return CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY;
        }
        try {
            return MAPPER.readTree(task.getInputJson()).path("_mode").asText(CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY);
        } catch (Exception e) {
            return CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 加载任务（缺失抛 404）。
     *
     * @param taskId 任务 ID
     *
     * @return 任务实体
     */
    private ActorWorldgenTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(404, "任务不存在：" + taskId));
    }

    /**
     * 解析任务对应工作流定义（缺失抛 404）。
     *
     * @param task 任务实体
     *
     * @return 工作流定义
     */
    private WorkflowDefinition requireDefinition(ActorWorldgenTask task) {
        ActorWorldgenWorkflow workflow = workflowRepository.findById(task.getWorkflowId())
                .orElseThrow(() -> new BizException(404, "任务关联的工作流定义不存在：" + task.getWorkflowId()));
        return definitionParser.parse(workflow.getCode(), workflow.getName(), workflow.getVersion(),
                workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
    }

    /**
     * 构造节点 VO（无尝试返回 null）。
     *
     * @param def 工作流定义（label 来源）
     *
     * @param nodeId 节点 ID
     *
     * @param attempts 该节点全部尝试（按 id 升序=执行顺序）
     *
     * @return 节点 VO
     */
    private WorldGenNodeVO buildNodeVO(WorkflowDefinition def, String nodeId,
                                       List<ActorWorldgenNodeAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        WorkflowNode node = def.node(nodeId);
        WorldGenNodeVO vo = new WorldGenNodeVO();
        vo.setNodeId(nodeId);
        vo.setLabel(node == null ? nodeId : node.getLabel());
        vo.setStepType(node == null ? null : node.getNodeType());
        ActorWorldgenNodeAttempt latest = attempts.get(attempts.size() - 1);
        vo.setStatus(latest.getStatus());
        vo.setRetryCount(latest.getRetryCount());
        vo.setAttemptCount(attempts.size());
        vo.setError(latest.getError());
        // 产物=最新成功尝试的输出（解析为对象，前端预览友好）
        for (int i = attempts.size() - 1; i >= 0; i--) {
            ActorWorldgenNodeAttempt a = attempts.get(i);
            if (WorldGenStatus.SUCCEEDED.equals(a.getStatus()) && a.getOutputJson() != null) {
                vo.setOutput(parseJsonObject(a.getOutputJson()));
                break;
            }
        }
        for (ActorWorldgenNodeAttempt a : attempts) {
            WorldGenAttemptVO av = new WorldGenAttemptVO();
            av.setId(a.getId());
            av.setNodeId(a.getNodeId());
            av.setNodeOrder(a.getNodeOrder());
            av.setStepType(a.getStepType());
            av.setStatus(a.getStatus());
            av.setRetryCount(a.getRetryCount());
            av.setError(a.getError());
            av.setTokenIn(a.getTokenIn());
            av.setTokenOut(a.getTokenOut());
            av.setDurationMs(a.getDurationMs());
            av.setInput(a.getInputJson());
            av.setOutput(a.getOutputJson());
            av.setCreatedAt(formatTime(a.getCreatedAt()));
            vo.getAttempts().add(av);
        }
        return vo;
    }

    /**
     * 统计节点数（容错：解析失败返回 0）。
     *
     * @param nodesJson 节点列表 JSON
     *
     * @return 节点数
     */
    private int countNodes(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode arr = MAPPER.readTree(nodesJson);
            return arr.isArray() ? arr.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * JSON 序列化（容错返回 "{}"）。
     *
     * @param obj 对象
     *
     * @return JSON 文本
     */
    private String writeJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 解析 JSON 为对象（容错：失败返回 null）。
     *
     * @param json JSON 文本
     *
     * @return 解析后的对象（Map/List/字符串等）
     */
    private Object parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 时间格式化（容错 null → null）。
     *
     * @param time 时间
     *
     * @return 格式化字符串
     */
    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }
}
