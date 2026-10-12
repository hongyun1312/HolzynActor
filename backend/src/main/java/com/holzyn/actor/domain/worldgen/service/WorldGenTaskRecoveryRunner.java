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

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 世界观生成任务恢复器。
 * <p>职责：应用启动时扫描 {@code actor_worldgen_task} 中遗留 <b>running</b>状态的任务
 * （进程重启/崩溃中断，任务执行链未完成），从断点（{@code current_node_id}）续跑：
 * <ul>
 * <li>定位断点：从 {@code current_node_id} 对应的执行序号起，向后找<b>第一个最新尝试非成功</b>
 * 的节点作为续跑起点（已成功节点跳过——并行拓扑下同层兄弟可能已成功，防重复付 AI 费/覆盖产物）；</li>
 * <li>全部节点均已成功 → 任务实际已完成（进程死在收尾前），直接标记 succeeded 收尾；</li>
 * <li>仅恢复 running 任务；failed（用户可经 retry/skip 手动续跑）/succeeded/canceled 不自动恢复；
 * 失败不阻断启动（与 Seeder 语义一致）。</li>
 * </ul>
 * 续跑走 {@code WorldGenTaskRunner.resumeFromAsync}（线性执行路径，自动跳过已成功节点）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldGenTaskRecoveryRunner implements ApplicationRunner {

    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final WorldGenTaskRunner taskRunner;

    /**
     * 启动时扫描并恢复遗留 running 任务。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ActorWorldgenTask> running = taskRepository.findByStatusOrderByIdAsc(WorldGenStatus.RUNNING);
            if (running.isEmpty()) {
                return;
            }
            for (ActorWorldgenTask task : running) {
                try {
                    int startOrder = resolveStartOrder(task);
                    if (startOrder < 0) {
                        // 全部节点已成功（进程死在收尾前）→ 直接收尾
                        task.setStatus(WorldGenStatus.SUCCEEDED);
                        task.setCurrentNodeId(null);
                        taskRepository.save(task);
                        log.info("[worldgen-恢复] 任务实际已完成（全部节点成功），直接收尾：task={}", task.getId());
                        continue;
                    }
                    log.info("[worldgen-恢复] 任务续跑：task={}, workflow={}, project={}, 从序号 {} 继续",
                            task.getId(), task.getWorkflowId(), task.getProjectId(), startOrder);
                    taskRunner.resumeFromAsync(task.getId(), startOrder);
                } catch (Exception e) {
                    log.warn("[worldgen-恢复] 单任务恢复失败（跳过，可手动重试/跳过节点）: task={} : {}",
                            task.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[worldgen-恢复] 任务恢复扫描失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /**
     * 计算续跑起始序号（1 基）。
     * <p>从 {@code current_node_id} 对应执行序号起，向后找第一个「最新尝试非成功」的节点；
     * 全部成功返回 -1（任务应收尾）。</p>
     *
     * @param task 任务实体
     *
     * @return 续跑起始序号（1 基）；-1=无待执行节点（任务实际已完成）
     */
    int resolveStartOrder(ActorWorldgenTask task) {
        ActorWorldgenWorkflow workflow = workflowRepository.findById(task.getWorkflowId()).orElse(null);
        if (workflow == null) {
            // 工作流定义缺失：无从恢复，按从头续跑（runner 会 failTask 提示定义缺失）
            return 1;
        }
        WorkflowDefinition def = definitionParser.parse(workflow.getCode(), workflow.getName(),
                workflow.getVersion(), workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
        List<String> order = def.getExecutionOrder();
        // 断点定位：current_node_id 的执行序号（缺失/不在链中 → 从头）
        int startIdx = 0;
        if (task.getCurrentNodeId() != null && !task.getCurrentNodeId().isBlank()) {
            Integer idx = def.orderOf(task.getCurrentNodeId());
            if (idx != null) {
                startIdx = idx - 1;
            }
        }
        // 向后找第一个最新尝试非成功的节点
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository.findByTaskIdOrderByIdAsc(task.getId());
        java.util.Map<String, ActorWorldgenNodeAttempt> latestByNode = new java.util.LinkedHashMap<>();
        for (ActorWorldgenNodeAttempt a : attempts) {
            latestByNode.put(a.getNodeId(), a);
        }
        while (startIdx < order.size()) {
            ActorWorldgenNodeAttempt latest = latestByNode.get(order.get(startIdx));
            if (latest == null || !WorldGenStatus.SUCCEEDED.equals(latest.getStatus())) {
                return startIdx + 1;
            }
            startIdx++;
        }
        return -1;
    }
}
