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

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorkflowVariable;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 世界观生成任务执行器。
 * <p>职责：按工作流定义的拓扑执行顺序逐节点执行（agent/script 节点抽象 + 占位符填充 +
 * 执行器分发 + 检查点落表 + 自动重试），全程向 {@link WorldGenTaskSseHub} 广播
 * log/stage/result/error 进度（对齐 WorldWorkflowController 的 log/stage/result/error 约定）。</p>
 * <p>执行语义：</p>
 * <ul>
 * <li>创建任务即 {@code runAsync}（@Async 线程池，仿 WorldWorkflowController.workflowExecutor）；</li>
 * <li>失败节点：自动重试（auto_retry_count，默认关）→ 仍失败则任务失败，<b>已产生节点产物保留</b>，
 * 支持 retry/skip 原地续跑（CAS expected_attempt_count，见 {@code WorldGenTaskService}）；</li>
 * <li>续跑（resumeFromAsync）：重放 startOrder 前节点的上游产物（outputVariable → 输出 JSON），
 * 从 startOrder 继续执行。</li>
 * </ul>
 */
@Service
@Slf4j
public class WorldGenTaskRunner {

    /** 异步执行线程池（仿 WorldWorkflowController.workflowExecutor，防阻塞请求线程） */
    private static final ExecutorService RUNNER_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "worldgen-runner");
        t.setDaemon(true);
        return t;
    });

    /** 并行层执行线程池 */
    private static final ExecutorService PARALLEL_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "worldgen-parallel");
        t.setDaemon(true);
        return t;
    });

    /**
     * 并行执行配置（并行维度优化，字段注入保持构造器签名不变——测试直接 new 时为空 →
     * 回退串行执行，既有测试零破坏；真实 Spring 环境注入 application.yml
     */
    @Autowired(required = false)
    private WorldGenProperties props;

    /** 每任务锁（防同一任务并发执行节点链——重试/跳过与初始执行串行化） */
    private final ConcurrentHashMap<Long, Object> taskLocks = new ConcurrentHashMap<>();

    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final WorldGenTaskSseHub sseHub;
    /** 注入全部节点执行器 */
    private final List<WorldGenNodeExecutor> executors;

    /** 节点类型 → 执行器（精确匹配） */
    private final Map<String, WorldGenNodeExecutor> executorByType = new HashMap<>();
    /** 通用兜底执行器（supportedNodeType=null） */
    private final List<WorldGenNodeExecutor> fallbackExecutors = new ArrayList<>();

    /** 日志时间格式化 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public WorldGenTaskRunner(ActorWorldgenWorkflowRepository workflowRepository,
                              ActorWorldgenTaskRepository taskRepository,
                              ActorWorldgenNodeAttemptRepository attemptRepository,
                              WorldGenDefinitionParser definitionParser,
                              WorldGenTaskSseHub sseHub,
                              List<WorldGenNodeExecutor> executors) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.definitionParser = definitionParser;
        this.sseHub = sseHub;
        this.executors = executors;
        for (WorldGenNodeExecutor e : executors) {
            String type = e.supportedNodeType();
            if (type == null) {
                fallbackExecutors.add(e);
            } else {
                executorByType.put(type, e);
            }
        }
    }

    /**
     * 异步启动任务（创建任务后调用；从头执行节点链）。
     *
     * @param taskId 任务 ID
     */
    public void runAsync(Long taskId) {
        RUNNER_POOL.submit(() -> runChain(taskId, 0));
    }

    /**
     * 异步续跑（retry/skip 后调用；从 startOrder 序号起继续执行节点链）。
     *
     * @param taskId 任务 ID
     *
     * @param startOrder 起始执行序号（1 起；retry=失败节点自身序号，skip=失败节点下一序号）
     */
    public void resumeFromAsync(Long taskId, int startOrder) {
        RUNNER_POOL.submit(() -> runChain(taskId, startOrder));
    }

    /**
     * 顺序执行节点链（从 startOrder 序号起；0=从头）。
     *
     * @param taskId 任务 ID
     *
     * @param startOrder 起始执行序号（0=从头；&gt;0=续跑）
     */
    void runChain(Long taskId, int startOrder) {
        Object lock = taskLocks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            try {
                runChainLocked(taskId, startOrder);
            } catch (Exception e) {
                log.error("[worldgen] 任务执行异常：task={}", taskId, e);
                failTask(taskId, "任务执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        }
    }

    /**
     * 持锁后的链执行（单任务串行，防并发重复执行）。
     */
    private void runChainLocked(Long taskId, int startOrder) {
        ActorWorldgenTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        // 终态任务不再执行（防重复启动）
        if (WorldGenStatus.isTaskTerminal(task.getStatus())) {
            log.info("[worldgen] 任务已终态，跳过执行：task={}, status={}", taskId, task.getStatus());
            return;
        }
        ActorWorldgenWorkflow workflow = workflowRepository.findById(task.getWorkflowId()).orElse(null);
        if (workflow == null) {
            failTask(taskId, "工作流定义不存在：" + task.getWorkflowId());
            return;
        }
        WorkflowDefinition def = definitionParser.parse(workflow.getCode(), workflow.getName(),
                workflow.getVersion(), workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
        List<String> order = def.getExecutionOrder();
        if (startOrder < 0 || startOrder > order.size()) {
            failTask(taskId, "续跑起始序号非法：" + startOrder);
            return;
        }
        // startOrder 为 1 基执行序号（0=从头）：换算为 0 基数组下标
        int startIdx = startOrder <= 0 ? 0 : startOrder - 1;

        // 任务输入快照 + 模拟失败钩子
        Map<String, String> taskInputs = parseInputJson(task.getInputJson());
        Set<String> simulateFail = parseSimulateFailNodeIds(taskInputs);
        // 上游产物（续跑时从已成功尝试重放；从头时为空）
        Map<String, String> upstreamOutputs = collectUpstreamOutputs(taskId, def, startOrder);

        // 任务状态推进为 running（创建时已 running，续跑时由服务层置 running，此处幂等兜底）
        if (!WorldGenStatus.RUNNING.equals(task.getStatus())) {
            task.setStatus(WorldGenStatus.RUNNING);
            task.setFinishedAt(null);
            taskRepository.save(task);
        }

        // 执行策略：
        // 从头（startOrder<=0）且配置并行（parallelism>1）且定义存在同层多节点 → 分层并行执行
        // （前 3 维并行 + 后 3 维链式，层内并行、层间同步）；
        // 其余（续跑/串行配置/无并行层）→ 线性顺序执行（续跑自动跳过已成功节点，
        // 防并行拓扑下「同层兄弟已成功」被重复执行/重复付 AI 费）。
        if (startOrder <= 0 && parallelism() > 1 && hasParallelLevels(def)) {
            runChainParallel(taskId, task, def, taskInputs, upstreamOutputs, simulateFail, order);
        } else {
            runChainSequential(taskId, task, def, taskInputs, upstreamOutputs, simulateFail, order, startIdx);
        }
    }

    /**
     * 线性顺序执行节点链。
     *
     * @param taskId 任务 ID
     *
     * @param task 任务实体（主线程独占写）
     *
     * @param def 工作流定义
     *
     * @param taskInputs 任务输入快照
     *
     * @param upstreamOutputs 上游产物（outputVariable → 输出 JSON）
     *
     * @param simulateFail 模拟失败节点集合
     *
     * @param order 执行顺序（节点 ID 列表）
     *
     * @param startIdx 起始 0 基下标
     */
    private void runChainSequential(Long taskId, ActorWorldgenTask task, WorkflowDefinition def,
                                    Map<String, String> taskInputs, Map<String, String> upstreamOutputs,
                                    Set<String> simulateFail, List<String> order, int startIdx) {
        for (int i = startIdx; i < order.size(); i++) {
            String nodeId = order.get(i);
            WorkflowNode node = def.node(nodeId);
            if (node == null) {
                failTask(taskId, "节点定义缺失：" + nodeId);
                return;
            }
            int orderIndex = i + 1;
            // ：续跑时跳过已成功节点（并行拓扑下同层兄弟节点可能已成功），
            // 其产物直接重放进上游输出（防重复执行/重复付 AI 费/覆盖既有产物）。
            ActorWorldgenNodeAttempt latest = latestAttemptOf(taskId, nodeId);
            if (latest != null && WorldGenStatus.SUCCEEDED.equals(latest.getStatus())) {
                if (node.getOutputVariable() != null && !node.getOutputVariable().isBlank()
                        && latest.getOutputJson() != null) {
                    upstreamOutputs.put(node.getOutputVariable(), latest.getOutputJson());
                }
                log.info("[worldgen] 节点已成功（续跑跳过）：task={}, node={}", taskId, nodeId);
                continue;
            }
            task.setCurrentNodeId(nodeId);
            taskRepository.save(task);

            NodeExecutionResult result = executeNodeWithAutoRetry(taskId, task, def, node, orderIndex,
                    taskInputs, upstreamOutputs, simulateFail);
            if (!WorldGenStatus.SUCCEEDED.equals(result.status())) {
                failTask(taskId, "节点「" + node.getLabel() + "」执行失败：" + result.error());
                return;
            }
            // 成功：登记上游产物（供下游【前序维度】注入）
            if (node.getOutputVariable() != null && !node.getOutputVariable().isBlank()
                    && result.outputJson() != null) {
                upstreamOutputs.put(node.getOutputVariable(), result.outputJson());
            }
        }
        finishTask(taskId, task, order.size());
    }

    /**
     * 分层并行执行节点链。
     * <p>语义：按解析器计算的层号分组（升序逐层）；<b>层内节点并行执行</b>（并行度上限
     * {@code holzyn.actor.worldgen.parallelism}，信号量限流）、<b>层间同步</b>（整层完成后
     * 才进入下一层）；层内任一节点失败 → 等整层收齐后任务失败（已成功节点产物保留，可续跑）。
     * task 实体仅主线程写（层边界 setCurrentNodeId），并行线程只读 projectId，防并发写实体。</p>
     *
     * @param taskId 任务 ID
     *
     * @param task 任务实体（仅主线程写）
     *
     * @param def 工作流定义（含层号）
     *
     * @param taskInputs 任务输入快照
     *
     * @param upstreamOutputs 上游产物（outputVariable → 输出 JSON）
     *
     * @param simulateFail 模拟失败节点集合
     *
     * @param order 执行顺序（节点 ID 列表）
     */
    private void runChainParallel(Long taskId, ActorWorldgenTask task, WorkflowDefinition def,
                                  Map<String, String> taskInputs, Map<String, String> upstreamOutputs,
                                  Set<String> simulateFail, List<String> order) {
        // 按层号分组（升序）
        Map<Integer, List<String>> levels = new TreeMap<>();
        for (String nodeId : order) {
            int level = def.getLevelByNodeId() == null ? 1 : def.getLevelByNodeId().getOrDefault(nodeId, 1);
            levels.computeIfAbsent(level, k -> new ArrayList<>()).add(nodeId);
        }
        Semaphore gate = new Semaphore(parallelism());
        for (Map.Entry<Integer, List<String>> entry : levels.entrySet()) {
            List<String> levelNodes = entry.getValue();
            // 层边界：断点定位到层内首个节点（并行执行期间 task 实体仅主线程写）
            task.setCurrentNodeId(levelNodes.get(0));
            taskRepository.save(task);

            List<Future<NodeExecutionResult>> futures = new ArrayList<>(levelNodes.size());
            for (String nodeId : levelNodes) {
                WorkflowNode node = def.node(nodeId);
                if (node == null) {
                    failTask(taskId, "节点定义缺失：" + nodeId);
                    return;
                }
                int orderIndex = order.indexOf(nodeId) + 1;
                futures.add(PARALLEL_POOL.submit(() -> {
                    try {
                        gate.acquire();
                        try {
                            return executeNodeWithAutoRetry(taskId, task, def, node, orderIndex,
                                    taskInputs, upstreamOutputs, simulateFail);
                        } finally {
                            gate.release();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return NodeExecutionResult.failure("并行执行被中断：" + nodeId);
                    }
                }));
            }
            // 等待整层收齐（含失败节点；失败节点的同层兄弟不取消——产物保留可续跑）
            List<NodeExecutionResult> results = new ArrayList<>(levelNodes.size());
            String failedMsg = null;
            for (Future<NodeExecutionResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    results.add(NodeExecutionResult.failure("并行执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage())));
                }
            }
            boolean failed = results.stream().anyMatch(r -> !WorldGenStatus.SUCCEEDED.equals(r.status()));
            if (failed) {
                failedMsg = results.stream()
                        .filter(r -> !WorldGenStatus.SUCCEEDED.equals(r.status()))
                        .map(r -> r.error() == null ? "未知错误" : r.error())
                        .findFirst().orElse("未知错误");
                failTask(taskId, "并行层「" + entry.getKey() + "」节点执行失败：" + failedMsg);
                return;
            }
            // 层末统一写入上游产物（层内节点互不依赖，写入顺序无关）
            for (int i = 0; i < levelNodes.size(); i++) {
                WorkflowNode node = def.node(levelNodes.get(i));
                NodeExecutionResult r = results.get(i);
                if (node != null && node.getOutputVariable() != null && !node.getOutputVariable().isBlank()
                        && r.outputJson() != null) {
                    upstreamOutputs.put(node.getOutputVariable(), r.outputJson());
                }
            }
        }
        finishTask(taskId, task, order.size());
    }

    /**
     * 任务成功收尾：状态 succeeded + SSE result 广播。
     *
     * @param taskId 任务 ID
     *
     * @param task 任务实体
     *
     * @param nodeCount 节点总数
     */
    private void finishTask(Long taskId, ActorWorldgenTask task, int nodeCount) {
        task.setStatus(WorldGenStatus.SUCCEEDED);
        task.setFinishedAt(LocalDateTime.now());
        task.setCurrentNodeId(null);
        taskRepository.save(task);
        log.info("[worldgen] 任务成功：task={}", taskId);
        sseHub.log(taskId, "任务执行完成：全部 " + nodeCount + " 个节点成功");
        sseHub.complete(taskId, SseLifecycle.EVENT_RESULT, Map.of("taskId", taskId,
                "status", WorldGenStatus.SUCCEEDED, "nodeCount", nodeCount));
    }

    /**
     * 取节点最新一次尝试（无尝试返回 null）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @return 最新尝试（按 id 升序取末位）
     */
    private ActorWorldgenNodeAttempt latestAttemptOf(Long taskId, String nodeId) {
        List<ActorWorldgenNodeAttempt> list = attemptRepository
                .findByTaskIdAndNodeIdOrderByIdAsc(taskId, nodeId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * 是否存在「同层多节点」（存在即可并行：层内节点互不依赖）。
     *
     * @param def 工作流定义
     *
     * @return true=存在并行层
     */
    private boolean hasParallelLevels(WorkflowDefinition def) {
        if (def.getLevelByNodeId() == null || def.getLevelByNodeId().isEmpty()) {
            return false;
        }
        Map<Integer, Integer> freq = new HashMap<>();
        for (Integer level : def.getLevelByNodeId().values()) {
            freq.merge(level, 1, Integer::sum);
        }
        return freq.values().stream().anyMatch(c -> c > 1);
    }

    /**
     * 并行度（配置缺失/非法 → 1 串行；测试直接 new runner 时 props 为空 → 串行，零破坏）。
     *
     * @return 并行度上限（≥1）
     */
    private int parallelism() {
        return props == null || props.getParallelism() <= 0 ? 1 : props.getParallelism();
    }

    /**
     * 单节点执行（含自动重试）。
     *
     * @return 最终节点结果（成功或失败）
     */
    private NodeExecutionResult executeNodeWithAutoRetry(Long taskId, ActorWorldgenTask task,
                                                         WorkflowDefinition def, WorkflowNode node, int orderIndex,
                                                         Map<String, String> taskInputs,
                                                         Map<String, String> upstreamOutputs,
                                                         Set<String> simulateFail) {
        int autoRetry = node.getAutoRetryCount() == null ? 0 : node.getAutoRetryCount();
        NodeExecutionResult last = null;
        for (int attempt = 0; attempt <= autoRetry; attempt++) {
            last = executeNodeOnce(taskId, task, def, node, orderIndex,
                    taskInputs, upstreamOutputs, simulateFail);
            if (WorldGenStatus.SUCCEEDED.equals(last.status())) {
                return last;
            }
            if (attempt < autoRetry) {
                int wait = node.getAutoRetryIntervalSeconds() == null ? 0 : node.getAutoRetryIntervalSeconds();
                log.info("[worldgen] 节点自动重试：task={}, node={}, 第 {}/{} 次失败，等待 {}s",
                        taskId, node.getId(), attempt + 1, autoRetry, wait);
                sseHub.log(taskId, "节点「" + node.getLabel() + "」第 " + (attempt + 1) + " 次尝试失败，自动重试");
                if (wait > 0) {
                    try {
                        Thread.sleep(wait * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return last;
                    }
                }
            }
        }
        return last;
    }

    /**
     * 单次节点执行：填充占位符 → 建尝试行(running) → 执行器分发 → 结果落表 → SSE 广播。
     *
     * @return 本次执行结果
     */
    private NodeExecutionResult executeNodeOnce(Long taskId, ActorWorldgenTask task,
                                                WorkflowDefinition def, WorkflowNode node, int orderIndex,
                                                Map<String, String> taskInputs,
                                                Map<String, String> upstreamOutputs,
                                                Set<String> simulateFail) {
        // 填充占位符（{{premise}}/{{genre}}/{{language}}/{{前序维度输出变量}}/{{projectId}}）
        String filledInput = fillPlaceholders(node, def, taskInputs, upstreamOutputs, task.getProjectId());

        // 建尝试行（running；retry_count=该节点已有尝试数，CAS 依据）
        List<ActorWorldgenNodeAttempt> existing = attemptRepository
                .findByTaskIdAndNodeIdOrderByIdAsc(taskId, node.getId());
        ActorWorldgenNodeAttempt attempt = new ActorWorldgenNodeAttempt();
        attempt.setTaskId(taskId);
        attempt.setNodeId(node.getId());
        attempt.setNodeOrder(orderIndex);
        attempt.setStepType(node.getNodeType());
        attempt.setRetryCount(existing.size());
        attempt.setStatus(WorldGenStatus.RUNNING);
        attempt.setInputJson(filledInput);
        attemptRepository.save(attempt);
        log.info("[worldgen] 开始执行节点：task={}, order={}/{}, node={}({}), 第{}次尝试",
                taskId, orderIndex, def.getExecutionOrder().size(), node.getId(), node.getNodeType(),
                existing.size() + 1);
        sseHub.log(taskId, "执行节点 " + orderIndex + "/" + def.getExecutionOrder().size()
                + "：「" + node.getLabel() + "」(" + node.getNodeType() + ")");

        // 执行器分发（精确类型 → 兜底）
        WorldGenNodeExecutor executor = executorByType.get(node.getNodeType());
        if (executor == null && !fallbackExecutors.isEmpty()) {
            executor = fallbackExecutors.get(0);
        }
        NodeExecutionContext ctx = new NodeExecutionContext(taskId, task.getProjectId(), def.getCode(), node,
                filledInput, taskInputs, upstreamOutputs, simulateFail);
        NodeExecutionResult result;
        if (executor == null) {
            result = NodeExecutionResult.failure("未注册 " + node.getNodeType() + " 节点执行器");
        } else {
            result = executor.execute(ctx);
        }

        // 结果落表（检查点语义：每次尝试一行）
        attempt.setStatus(result.status());
        attempt.setOutputJson(result.outputJson());
        attempt.setError(result.error());
        attempt.setTokenIn(result.tokenIn());
        attempt.setTokenOut(result.tokenOut());
        attempt.setDurationMs(result.durationMs());
        attemptRepository.save(attempt);

        if (WorldGenStatus.SUCCEEDED.equals(result.status())) {
            log.info("[worldgen] 节点成功：task={}, node={}, {}ms", taskId, node.getId(), result.durationMs());
            sseHub.log(taskId, "节点「" + node.getLabel() + "」成功（" + result.durationMs() + "ms）");
        } else {
            log.warn("[worldgen] 节点失败：task={}, node={}, error={}", taskId, node.getId(), result.error());
            sseHub.log(taskId, "节点「" + node.getLabel() + "」失败：" + result.error());
        }
        sseHub.stage(taskId, node.getLabel(), orderIndex, def.getExecutionOrder().size());
        return result;
    }

    /**
     * 填充节点输入占位符：{{key}} → 任务输入 / 上游产物 / 变量默认值 / projectId。
     * <p>替换后仍残留的 {@code {{...}}}（被跳过节点的上游输出变量）统一替换为空串，
     * 避免未解析占位符泄漏进 AI 提示词/脚本参数（跳过节点后下游模板残留）。</p>
     *
     * @param node 节点定义
     *
     * @param def 工作流定义（变量默认值）
     *
     * @param taskInputs 任务输入快照
     *
     * @param upstreamOutputs 上游产物（outputVariable → 输出 JSON）
     *
     * @param projectId 项目 ID
     *
     * @return 填充后的输入文本
     */
    String fillPlaceholders(WorkflowNode node, WorkflowDefinition def, Map<String, String> taskInputs,
                            Map<String, String> upstreamOutputs, Long projectId) {
        Map<String, String> resolution = new HashMap<>();
        if (taskInputs != null) {
            resolution.putAll(taskInputs);
        }
        if (projectId != null) {
            resolution.put("projectId", String.valueOf(projectId));
        }
        if (upstreamOutputs != null) {
            resolution.putAll(upstreamOutputs);
        }
        if (def != null && def.getVariables() != null) {
            for (WorkflowVariable v : def.getVariables()) {
                if (!resolution.containsKey(v.getKey()) && v.getDefaultValue() != null) {
                    resolution.put(v.getKey(), v.getDefaultValue());
                }
            }
        }
        String text = WorldGenStepType.AGENT.equals(node.getNodeType())
                ? (node.getFirstMessage() == null ? "" : node.getFirstMessage())
                : (node.getScriptArgs() == null ? "" : node.getScriptArgs());
        for (Map.Entry<String, String> e : resolution.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        // ：残留未解析占位符（被跳过上游节点/未知键）替换为空串
        text = text.replaceAll("\\{\\{[^{}]+}}", "");
        return text;
    }

    /**
     * 解析任务输入 JSON（容错：非法 JSON 返回空 Map）。
     *
     * @param inputJson 任务输入 JSON
     *
     * @return 输入快照
     */
    private Map<String, String> parseInputJson(String inputJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (inputJson == null || inputJson.isBlank()) {
            return out;
        }
        try {
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(inputJson);
            root.properties().forEach(e -> out.put(e.getKey(),
                    e.getValue() == null || e.getValue().isNull() ? "" : e.getValue().asText()));
        } catch (Exception ignored) {
            // 非法 JSON 按空处理
        }
        return out;
    }

    /**
     * 解析 simulateFailNodeIds 测试钩子（逗号分隔的节点 ID 列表，命中即模拟失败）。
     *
     * @param taskInputs 任务输入快照
     *
     * @return 节点 ID 集合（无则空集）
     */
    private Set<String> parseSimulateFailNodeIds(Map<String, String> taskInputs) {
        Set<String> set = new HashSet<>();
        String raw = taskInputs.get("simulateFailNodeIds");
        if (raw != null && !raw.isBlank()) {
            for (String s : raw.split(",")) {
                if (!s.isBlank()) {
                    set.add(s.trim());
                }
            }
        }
        return set;
    }

    /**
     * 重放 startOrder 之前节点的上游产物（outputVariable → 最新成功尝试的输出 JSON）。
     *
     * @param taskId 任务 ID
     *
     * @param def 工作流定义
     *
     * @param startOrder 起始序号（1 起；该序号之前的节点产物进入上游输出）
     *
     * @return 上游产物映射
     */
    private Map<String, String> collectUpstreamOutputs(Long taskId, WorkflowDefinition def, int startOrder) {
        Map<String, String> upstream = new LinkedHashMap<>();
        if (startOrder <= 1) {
            return upstream;
        }
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository.findByTaskIdOrderByIdAsc(taskId);
        Map<String, ActorWorldgenNodeAttempt> latestByNode = new LinkedHashMap<>();
        for (ActorWorldgenNodeAttempt a : attempts) {
            latestByNode.put(a.getNodeId(), a);
        }
        for (String nodeId : def.getExecutionOrder()) {
            WorkflowNode node = def.node(nodeId);
            if (node == null || node.getOutputVariable() == null || node.getOutputVariable().isBlank()) {
                continue;
            }
            Integer orderOf = def.orderOf(nodeId);
            if (orderOf != null && orderOf < startOrder) {
                ActorWorldgenNodeAttempt latest = latestByNode.get(nodeId);
                if (latest != null && WorldGenStatus.SUCCEEDED.equals(latest.getStatus())
                        && latest.getOutputJson() != null) {
                    upstream.put(node.getOutputVariable(), latest.getOutputJson());
                }
            }
        }
        return upstream;
    }

    /**
     * 任务失败终态：状态 failed + finished_at + SSE error 广播（已产生节点产物保留，可续跑）。
     *
     * @param taskId 任务 ID
     *
     * @param message 错误信息
     */
    private void failTask(Long taskId, String message) {
        ActorWorldgenTask task = taskRepository.findById(taskId).orElse(null);
        if (task != null) {
            task.setStatus(WorldGenStatus.FAILED);
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
        log.warn("[worldgen] 任务失败：task={}, reason={}", taskId, message);
        sseHub.log(taskId, "任务失败：" + message);
        sseHub.fail(taskId, message);
    }
}
