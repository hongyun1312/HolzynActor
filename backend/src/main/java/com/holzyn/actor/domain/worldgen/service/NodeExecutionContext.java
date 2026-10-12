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

import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;

import java.util.Map;
import java.util.Set;

/**
 * 节点执行上下文（一次节点执行的全部输入，对齐 DeterminFlow 节点独立会话语义）。
 * <p>职责：携带任务 ID、项目 ID、节点定义、填充占位符后的输入文本、任务输入快照、
 * 上游节点产物（outputVariable → 输出 JSON）与 冒烟用模拟失败钩子。
 * 起 projectId 供真实 Agent 执行器解析「项目级 Prompt 模板覆盖」与「检查点文件工作区路径」。</p>
 *
 * @param taskId 任务 ID
 *
 * @param projectId 归属项目 ID
 *
 * @param workflowCode 工作流编码
 *
 * @param node 节点定义
 *
 * @param filledInput 填充占位符后的输入（agent=first_message 渲染结果；script=script_args 渲染结果）
 *
 * @param taskInputs 任务输入快照（premise/genre/language + 扩展）
 *
 * @param upstreamOutputs 上游节点产物
 *
 * @param simulateFailNodeIds 桩执行器模拟失败钩子（任务输入 simulateFailNodeIds 逗号分隔；命中即失败，供重试/跳过冒烟）
 */
public record NodeExecutionContext(
        Long taskId,
        Long projectId,
        String workflowCode,
        WorkflowNode node,
        String filledInput,
        Map<String, String> taskInputs,
        Map<String, String> upstreamOutputs,
        Set<String> simulateFailNodeIds) {

    /**
     * 是否 agent 节点。
     */
    public boolean isAgent() {
        return WorldGenStepType.AGENT.equals(node.getNodeType());
    }
}
