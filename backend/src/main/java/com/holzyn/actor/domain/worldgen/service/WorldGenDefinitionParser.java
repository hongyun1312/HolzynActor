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
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowEdge;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorkflowVariable;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流定义解析器。
 * <p>职责：把 actor_worldgen_workflow 的 nodes_json/edges_json/variables_json 三段 JSON
 * 解析为内存模型 {@link WorkflowDefinition}，并完成三件事：</p>
 * <ol>
 * <li><b>校验</b>：节点非空、ID 唯一、节点类型合法、边端点存在（含 __start__/__end__ 伪节点）、无环；</li>
 * <li><b>拓扑排序</b>：按 edges 计算执行顺序；</li>
 * <li><b>占位符引用提取</b>：扫描 agent first_message / script script_args 的 {@code {{key}}}
 * 占位符，凡命中其他节点 outputVariable 的记为「上游节点产物引用」
 * （TaskRunner 填充 {@code {{前序维度}}} 时依此把上游输出注入局部上下文，链式自洽）。</li>
 * </ol>
 */
@Component
@Slf4j
public class WorldGenDefinitionParser {

    /** 链起点/终点伪节点 ID（不参与执行） */
    public static final String START_NODE = "__start__";

    /** 链起点/终点伪节点 ID（不参与执行） */
    public static final String END_NODE = "__end__";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson，与项目其他位置一致） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {{占位符}} 提取正则 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /**
     * 解析三段 JSON 为工作流定义（含执行顺序）。
     *
     * @param code 工作流编码
     *
     * @param name 工作流名称
     *
     * @param version 定义版本号
     *
     * @param nodesJson 节点列表 JSON
     *
     * @param edgesJson 边列表 JSON
     *
     * @param variablesJson 输入变量列表 JSON
     *
     * @return 解析后的定义（executionOrder 已按拓扑排序）
     */
    public WorkflowDefinition parse(String code, String name, Integer version,
                                    String nodesJson, String edgesJson, String variablesJson) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setCode(code);
        def.setName(name);
        def.setVersion(version == null ? 1 : version);

        List<WorkflowNode> nodes = parseNodes(nodesJson, code);
        List<WorkflowEdge> edges = parseEdges(edgesJson, code);
        def.setNodes(nodes);
        def.setEdges(edges);
        if (variablesJson != null && !variablesJson.isBlank()) {
            try {
                def.setVariables(MAPPER.readValue(variablesJson, new TypeReference<List<WorkflowVariable>>() {
                }));
            } catch (Exception e) {
                throw new BizException(400, "工作流「" + code + "」variables_json 解析失败：" + e.getMessage());
            }
        }

        // 节点索引 + 校验
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            if (n.getId() == null || n.getId().isBlank()) {
                throw new BizException(400, "工作流「" + code + "」存在无 ID 的节点");
            }
            if (byId.containsKey(n.getId())) {
                throw new BizException(400, "工作流「" + code + "」节点 ID 重复：" + n.getId());
            }
            if (!WorldGenStepType.isValid(n.getNodeType())) {
                throw new BizException(400, "工作流「" + code + "」节点 " + n.getId() + " 类型非法：" + n.getNodeType());
            }
            byId.put(n.getId(), n);
        }
        if (nodes.isEmpty()) {
            throw new BizException(400, "工作流「" + code + "」未定义任何节点");
        }
        def.setNodeById(byId);

        // 边校验 + 拓扑排序
        Set<String> realIds = new LinkedHashSet<>(byId.keySet());
        Set<String> known = new HashSet<>(realIds);
        known.add(START_NODE);
        known.add(END_NODE);
        for (WorkflowEdge e : edges) {
            if (!known.contains(e.getSource()) || !known.contains(e.getTarget())) {
                throw new BizException(400, "工作流「" + code + "」边 " + e.getSource() + "→" + e.getTarget()
                        + " 引用了不存在的节点");
            }
        }
        List<String> order = topoSort(edges, realIds, code);
        def.setExecutionOrder(order);
        Map<String, Integer> orderByNodeId = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            orderByNodeId.put(order.get(i), i + 1);
        }
        def.setOrderByNodeId(orderByNodeId);

        // 并行层计算：level[node] = max(level[前驱真实节点]) + 1，
        // 无真实前驱（仅 __start__ 指入）= 1。同层节点彼此无依赖，TaskRunner 可并行执行。
        def.setLevelByNodeId(computeLevels(edges, realIds));

        // 占位符引用提取（仅前序节点产物：输出变量所属节点必须位于当前节点之前）
        Map<String, String> outputVarOwner = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            if (n.getOutputVariable() != null && !n.getOutputVariable().isBlank()) {
                outputVarOwner.put(n.getOutputVariable(), n.getId());
            }
        }
        for (WorkflowNode n : nodes) {
            List<String> refs = new ArrayList<>();
            String text = WorldGenStepType.AGENT.equals(n.getNodeType()) ? n.getFirstMessage() : n.getScriptArgs();
            Integer currentOrder = orderByNodeId.get(n.getId());
            if (text != null) {
                Matcher m = PLACEHOLDER.matcher(text);
                while (m.find()) {
                    String key = m.group(1).trim();
                    String owner = outputVarOwner.get(key);
                    if (owner != null && !refs.contains(key)) {
                        Integer ownerOrder = orderByNodeId.get(owner);
                        if (ownerOrder != null && currentOrder != null && ownerOrder < currentOrder) {
                            refs.add(key);
                        }
                    }
                }
            }
            n.setReferencedOutputVariables(refs);
        }
        return def;
    }

    /**
     * 解析节点列表 JSON。
     *
     * @param nodesJson 节点列表 JSON
     *
     * @param code 工作流编码（错误提示用）
     *
     * @return 节点列表
     */
    private List<WorkflowNode> parseNodes(String nodesJson, String code) {
        if (nodesJson == null || nodesJson.isBlank()) {
            throw new BizException(400, "工作流「" + code + "」nodes_json 为空");
        }
        try {
            return MAPPER.readValue(nodesJson, new TypeReference<List<WorkflowNode>>() {
            });
        } catch (Exception e) {
            throw new BizException(400, "工作流「" + code + "」nodes_json 解析失败：" + e.getMessage());
        }
    }

    /**
     * 解析边列表 JSON（允许为空=无显式边，此时按节点定义顺序执行）。
     *
     * @param edgesJson 边列表 JSON
     *
     * @param code 工作流编码（错误提示用）
     *
     * @return 边列表
     */
    private List<WorkflowEdge> parseEdges(String edgesJson, String code) {
        if (edgesJson == null || edgesJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(edgesJson, new TypeReference<List<WorkflowEdge>>() {
            });
        } catch (Exception e) {
            throw new BizException(400, "工作流「" + code + "」edges_json 解析失败：" + e.getMessage());
        }
    }

    /**
     * Kahn 拓扑排序：edges → 真实节点的执行顺序。
     * <p>只统计「真实节点 → 真实节点」的边；仅由 __start__ 指入的节点初始入度为 0。
     * 按节点定义顺序稳定出队（确定性）；若产出节点数 &lt; 真实节点数 → 存在环，抛 400。</p>
     *
     * @param edges 边列表
     *
     * @param realIds 真实节点 ID 集合
     *
     * @param code 工作流编码（错误提示用）
     *
     * @return 执行顺序（节点 ID 列表）
     */
    private List<String> topoSort(List<WorkflowEdge> edges, Set<String> realIds, String code) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : realIds) {
            indegree.put(id, 0);
            adj.put(id, new ArrayList<>());
        }
        for (WorkflowEdge e : edges) {
            if (realIds.contains(e.getSource()) && realIds.contains(e.getTarget())) {
                adj.get(e.getSource()).add(e.getTarget());
                indegree.merge(e.getTarget(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String next : adj.get(id)) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != realIds.size()) {
            throw new BizException(400, "工作流「" + code + "」存在环，无法确定执行顺序");
        }
        return order;
    }

    /**
     * 并行层计算：按拓扑序求每节点最长依赖路径深度。
     * <p>level[node] = max(level[真实前驱]) + 1；无真实前驱（仅由 __start__ 指入）→ 1。
     * 同层节点彼此无依赖，{@code WorldGenTaskRunner} 并行执行时按层分批（层内并行、层间同步）。</p>
     *
     * @param edges 边列表
     *
     * @param realIds 真实节点 ID 集合
     *
     * @return 节点 ID → 层号（1 起）
     */
    private Map<String, Integer> computeLevels(List<WorkflowEdge> edges, Set<String> realIds) {
        Map<String, Integer> level = new LinkedHashMap<>();
        for (String id : realIds) {
            level.put(id, 1);
        }
        // 拓扑序已保证前驱先于后继；对每条真实边做松弛（多轮直至收敛，防御非拓扑序输入）
        boolean changed;
        do {
            changed = false;
            for (WorkflowEdge e : edges) {
                if (!realIds.contains(e.getSource()) || !realIds.contains(e.getTarget())) {
                    continue;
                }
                int candidate = level.get(e.getSource()) + 1;
                if (candidate > level.get(e.getTarget())) {
                    level.put(e.getTarget(), candidate);
                    changed = true;
                }
            }
        } while (changed);
        return level;
    }
}
