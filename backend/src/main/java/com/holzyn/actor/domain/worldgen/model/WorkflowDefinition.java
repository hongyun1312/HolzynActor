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

package com.holzyn.actor.domain.worldgen.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义（一次解析/运行时的完整模型）。
 * nodes_json/edges_json/variables_json 三段 JSON 解析为内存模型，并计算执行顺序
 * 。任务启动时冻结该定义（对齐 DeterminFlow Task 冻结 Workflow 语义）。</p>
 */
@Data
public class WorkflowDefinition {

    /** 工作流编码（如 worldgen_build_v1） */
    private String code;

    /** 工作流名称 */
    private String name;

    /** 定义版本号 */
    private Integer version;

    /** 节点列表（定义顺序，非执行顺序） */
    private List<WorkflowNode> nodes = new ArrayList<>();

    /** 边列表 */
    private List<WorkflowEdge> edges = new ArrayList<>();

    /** 输入变量定义列表 */
    private List<WorkflowVariable> variables = new ArrayList<>();

    /** 执行顺序（节点 ID 列表，由 edges 拓扑排序得到；含全部真实节点） */
    private List<String> executionOrder = new ArrayList<>();

    /** 节点 ID → 节点（快速索引） */
    private Map<String, WorkflowNode> nodeById = new java.util.LinkedHashMap<>();

    /** 节点 ID → 执行序号（1 起；同一节点多次尝试共享同一序号） */
    private Map<String, Integer> orderByNodeId = new java.util.LinkedHashMap<>();

    /**
     * 节点 ID → 并行执行层号（1 起； 并行维度优化：同一层的节点彼此无依赖可并行执行，
     * 由 {@code WorldGenDefinitionParser.computeLevels} 按「最长依赖路径深度」计算——
     * build_v1 前 3 维 corelaws/spacetime/society 同层并行，后 3 维链式逐层）。
     */
    private Map<String, Integer> levelByNodeId = new java.util.LinkedHashMap<>();

    /**
     * 按节点 ID 取节点（缺失返回 null，调用方容错）。
     *
     * @param nodeId 节点 ID
     *
     * @return 节点定义
     */
    public WorkflowNode node(String nodeId) {
        return nodeById.get(nodeId);
    }

    /**
     * 按节点 ID 取执行序号（1 起；未在执行顺序中返回 null）。
     *
     * @param nodeId 节点 ID
     *
     * @return 执行序号
     */
    public Integer orderOf(String nodeId) {
        return orderByNodeId.get(nodeId);
    }
}
