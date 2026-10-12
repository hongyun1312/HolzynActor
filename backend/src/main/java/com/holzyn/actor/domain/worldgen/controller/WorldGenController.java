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

package com.holzyn.actor.domain.worldgen.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.worldgen.dto.CreateWorldGenTaskRequest;
import com.holzyn.actor.domain.worldgen.dto.NodeOperationRequest;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.service.WorldGenTaskService;
import com.holzyn.actor.domain.worldgen.service.WorldGenTaskSseHub;
import com.holzyn.actor.domain.worldgen.vo.WorldGenNodeVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenTaskVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenWorkflowVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 世界观生成工作流控制器。
 * <p>端点（对齐方案文档 §3.5）：</p>
 * <ul>
 * <li>GET /workflows：可用工作流定义列表（build v1 / 预留扩展）；</li>
 * <li>POST /tasks：创建并异步执行任务（{projectId, workflowCode, inputs} → {taskId}）；</li>
 * <li>GET /tasks/{taskId}：任务状态/节点进度/Token 汇总；</li>
 * <li>GET /tasks/{taskId}/nodes/{nodeId}：节点产物详情（输入/输出/尝试历史）；</li>
 * <li>POST /tasks/{taskId}/nodes/{nodeId}/retry|skip：失败节点原地续跑（CAS expected_attempt_count）；</li>
 * <li>GET /tasks/{taskId}/stream：SSE 进度流（log/stage/result/error， 统一，含回放）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/worldgen")
@Slf4j
@RequiredArgsConstructor
public class WorldGenController {

    private final WorldGenTaskService taskService;
    private final WorldGenTaskSseHub sseHub;
    private final ActorWorldgenTaskRepository taskRepository;
    private final SseLifecycle sseLifecycle;

    /**
     * 可用工作流定义列表。
     *
     * @return 工作流 VO 列表
     */
    @GetMapping("/workflows")
    public R<List<WorldGenWorkflowVO>> workflows() {
        return R.ok(taskService.listWorkflows());
    }

    /**
     * 创建并异步执行任务。
     *
     * @param req 创建请求（projectId/workflowCode/inputs）
     */
    @PostMapping("/tasks")
    public R<Map<String, Object>> createTask(@Valid @RequestBody CreateWorldGenTaskRequest req) {
        Long taskId = taskService.createTask(req);
        return R.ok(Map.of("taskId", taskId));
    }

    /**
     * 任务详情。
     *
     * @param taskId 任务 ID
     *
     * @return 任务 VO
     */
    @GetMapping("/tasks/{taskId}")
    public R<WorldGenTaskVO> task(@PathVariable Long taskId) {
        return R.ok(taskService.getTask(taskId));
    }

    /**
     * 节点详情。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @return 节点 VO
     */
    @GetMapping("/tasks/{taskId}/nodes/{nodeId}")
    public R<WorldGenNodeVO> node(@PathVariable Long taskId, @PathVariable String nodeId) {
        return R.ok(taskService.getNode(taskId, nodeId));
    }

    /**
     * 任务产物预览：6 维产物 → 分节/地点映射预览，不写库。
     *
     * @param taskId 任务 ID
     *
     * @return 预览 VO（维度 + 分节 + 地点候选）
     */
    @GetMapping("/tasks/{taskId}/preview")
    public R<com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO> preview(@PathVariable Long taskId) {
        return R.ok(taskService.previewTask(taskId));
    }

    /**
     * 预览确认后落库：6 维产物映射写入 actor_world_setting 分节 + 地点表。
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（分节 + 地点新增数）
     */
    @PostMapping("/tasks/{taskId}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable Long taskId) {
        return R.ok(taskService.confirmTask(taskId));
    }

    /**
     * 失败节点原地重试（CAS expected_attempt_count）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @param req 期望尝试数
     *
     * @return 重试发起后的任务 VO
     */
    @PostMapping("/tasks/{taskId}/nodes/{nodeId}/retry")
    public R<WorldGenTaskVO> retryNode(@PathVariable Long taskId, @PathVariable String nodeId,
                                       @Valid @RequestBody NodeOperationRequest req) {
        return R.ok(taskService.retryNode(taskId, nodeId, req.expectedAttemptCount()));
    }

    /**
     * 失败节点跳过（CAS expected_attempt_count）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @param req 期望尝试数
     *
     * @return 跳过后的任务 VO
     */
    @PostMapping("/tasks/{taskId}/nodes/{nodeId}/skip")
    public R<WorldGenTaskVO> skipNode(@PathVariable Long taskId, @PathVariable String nodeId,
                                      @Valid @RequestBody NodeOperationRequest req) {
        return R.ok(taskService.skipNode(taskId, nodeId, req.expectedAttemptCount()));
    }

    /**
     * 任务进度 SSE 流（log/stage/result/error， 统一）。
     * <p>行为：连接即回放任务最近事件（最多 200 条），再实时推送；任务终态后推送
     * result/error 并 complete。任务不存在直接 404（R 包装）。</p>
     *
     * @param taskId 任务 ID
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long taskId) {
        taskRepository.findById(taskId).orElseThrow(() -> new BizException(404, "任务不存在：" + taskId));
        return subscribeSse(taskId);
    }

    /**
     * 完整项目流水线 SSE 流：
     * confirm(mode=full) 后由 {@code WorldGenPipelineService} 编排的「角色生成 →
     * 角色落库 → 世界初始化 6 步 → 编年史」进度（log/stage/result/error，事件约定与任务 SSE 一致）。
     * <p>行为：连接即回放 build 任务最近事件（含确认落库前日志），再实时推送串接进度；
     * 流水线终态后推送 result/error 并 complete。</p>
     *
     * @param buildTaskId build 任务 ID（完整模式串接的 channel key）
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/pipelines/{buildTaskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pipelineStream(@PathVariable Long buildTaskId) {
        taskRepository.findById(buildTaskId).orElseThrow(() -> new BizException(404, "任务不存在：" + buildTaskId));
        return subscribeSse(buildTaskId);
    }

    /**
     * 项目故事规划列表：全部版本（历史链，升序），
     * 含三层力学引擎/风格档案 JSON 原文 + Markdown 渲染 + 世界氛围片段，供世界详情页展示区。
     *
     * @param projectId 项目 ID
     *
     * @return 故事规划版本列表（无记录返回空数组）
     */
    @GetMapping("/projects/{projectId}/story-plans")
    public R<List<Map<String, Object>>> storyPlans(@PathVariable Long projectId) {
        return R.ok(taskService.listStoryPlans(projectId));
    }

    /**
     * 通用 SSE 订阅（任务/流水线共用 WorldGenTaskSseHub 频道：回放 + 实时 + 终态收尾）。
     *
     * @param channelKey 频道键（任务 ID 或 build 任务 ID）
     *
     * @return SSE 事件流
     */
    private SseEmitter subscribeSse(Long channelKey) {
        // 数组 holder：解决「cleanup 回调自引用 emitter」的初始化问题（发射器终止时取消订阅）
        SseEmitter[] holder = new SseEmitter[1];
        holder[0] = sseLifecycle.create(0L, 0L, () -> {
            if (holder[0] != null) {
                sseHub.unsubscribe(channelKey, holder[0]);
            }
        });
        // 订阅：回放历史事件；若已终态则回放后直接完成
        boolean terminal = sseHub.subscribe(channelKey, holder[0]);
        if (terminal) {
            try {
                holder[0].complete();
            } catch (Exception ignored) {
                // 已完成/断开，忽略
            }
        }
        return holder[0];
    }
}
