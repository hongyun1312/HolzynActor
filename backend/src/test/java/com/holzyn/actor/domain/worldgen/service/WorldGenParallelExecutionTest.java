package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WorldGenTaskRunner 并行执行（M5 并行维度优化，2026-09-29_05 适配轮）单元测试。
 * <p>职责：验证——同层节点并行执行（并发观测 ≥2）、层间同步与上游产物注入、
 * 并行层失败停链（同层已成功产物保留）、续跑从失败节点继续且跳过已成功同层兄弟
 * （防重复执行/重复付 AI 费）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库 + 内存尝试列表）；props 经
 * ReflectionTestUtils 字段注入 parallelism=3（构造器签名不变，串行路径既有测试零破坏）。</p>
 */
class WorldGenParallelExecutionTest {

    /** 定义：a/b/c 同层并行（层1）→ d 依赖三者（层2） */
    private static final String NODES_JSON = """
            [ {"id":"a","label":"维度A","nodeType":"agent","agentType":"wb-a",
               "firstMessage":"A:{{premise}}","outputVariable":"out_a"},
              {"id":"b","label":"维度B","nodeType":"agent","agentType":"wb-b",
               "firstMessage":"B:{{premise}}","outputVariable":"out_b"},
              {"id":"c","label":"维度C","nodeType":"agent","agentType":"wb-c",
               "firstMessage":"C:{{premise}}","outputVariable":"out_c"},
              {"id":"d","label":"维度D","nodeType":"agent","agentType":"wb-d",
               "firstMessage":"D:{{out_a}}|{{out_b}}|{{out_c}}","outputVariable":"out_d"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"a"},
              {"source":"__start__","target":"b"},
              {"source":"__start__","target":"c"},
              {"source":"a","target":"d"},
              {"source":"b","target":"d"},
              {"source":"c","target":"d"},
              {"source":"d","target":"__end__"} ]
            """;

    private static final String VARIABLES_JSON = """
            [ {"key":"premise","name":"题材创意","type":"textarea","defaultValue":"","required":true} ]
            """;

    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private WorldGenTaskSseHub sseHub;
    private WorldGenTaskRunner runner;
    private ConcurrencyProbe probe;

    /** 内存尝试列表（模拟仓库持久化；并行层内多线程并发写 → CopyOnWriteArrayList 线程安全） */
    private final List<ActorWorldgenNodeAttempt> attempts = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 模拟仓库 ID 序列（AtomicLong 保证并行保存下 ID 唯一——避免 size()+1 并发读重号导致
     *  更新就位覆盖他节点记录的全量套件竞态，2026-09-30 修复） */
    private final java.util.concurrent.atomic.AtomicLong attemptIdSeq = new java.util.concurrent.atomic.AtomicLong(0);
    private ActorWorldgenTask task;

    /** 并发探针执行器：记录最大并发数与执行顺序（每节点 sleep 80ms 放大并发窗口）。 */
    static final class ConcurrencyProbe implements WorldGenNodeExecutor {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<String> execOrder = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> inputs = Collections.synchronizedMap(new HashMap<>());

        @Override
        public String supportedNodeType() {
            return "agent";
        }

        @Override
        public NodeExecutionResult execute(NodeExecutionContext ctx) {
            // 模拟失败钩子（与 StubWorldGenNodeExecutor 语义一致）
            if (ctx.simulateFailNodeIds() != null && ctx.simulateFailNodeIds().contains(ctx.node().getId())) {
                return NodeExecutionResult.failure("模拟失败（simulateFailNodeIds 命中）：node=" + ctx.node().getId());
            }
            int cur = active.incrementAndGet();
            maxActive.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            execOrder.add(ctx.node().getId());
            inputs.put(ctx.node().getId(), ctx.filledInput());
            active.decrementAndGet();
            return NodeExecutionResult.success("{\"ok\":\"" + ctx.node().getId() + "\"}", 80L);
        }
    }

    @BeforeEach
    void setUp() {
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        sseHub = Mockito.mock(WorldGenTaskSseHub.class);
        attempts.clear();

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode("parallel_wf");
        workflow.setName("并行工作流");
        workflow.setVersion(1);
        workflow.setNodesJson(NODES_JSON);
        workflow.setEdgesJson(EDGES_JSON);
        workflow.setVariablesJson(VARIABLES_JSON);
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));

        task = new ActorWorldgenTask();
        task.setId(10L);
        task.setWorkflowId(1L);
        task.setProjectId(5L);
        task.setStatus(WorldGenStatus.RUNNING);
        task.setInputJson("{\"premise\":\"废土之上\"}");
        when(taskRepository.findById(10L)).thenAnswer(inv -> Optional.of(task));
        doAnswer(inv -> {
            task = inv.getArgument(0);
            return task;
        }).when(taskRepository).save(any(ActorWorldgenTask.class));

        when(attemptRepository.findByTaskIdOrderByIdAsc(anyLong())).thenAnswer(inv -> new ArrayList<>(attempts));
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(anyLong(), anyString()))
                .thenAnswer(inv -> attempts.stream().filter(a -> a.getNodeId().equals(inv.getArgument(1))).toList());
        doAnswer(inv -> {
            ActorWorldgenNodeAttempt a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(attemptIdSeq.incrementAndGet());
                attempts.add(a);
            } else {
                for (int i = 0; i < attempts.size(); i++) {
                    if (attempts.get(i).getId().equals(a.getId())) {
                        attempts.set(i, a);
                        break;
                    }
                }
            }
            return a;
        }).when(attemptRepository).save(any(ActorWorldgenNodeAttempt.class));

        probe = new ConcurrencyProbe();
        runner = new WorldGenTaskRunner(workflowRepository, taskRepository, attemptRepository,
                new WorldGenDefinitionParser(), sseHub, List.of(probe));
        // 字段注入并行度（保持构造器签名不变）
        WorldGenProperties props = new WorldGenProperties();
        props.setParallelism(3);
        ReflectionTestUtils.setField(runner, "props", props);
    }

    @Test
    @DisplayName("同层节点并行执行 + 层间同步注入上游产物")
    void parallelLayerExecutesConcurrentlyAndInjectsUpstream() {
        runner.runChain(10L, 0);

        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        assertEquals(4, attempts.size(), "4 节点各 1 次成功尝试");
        // 并行实证：层1（a/b/c）最大并发 ≥ 2
        assertTrue(probe.maxActive.get() >= 2, "层内应并行执行（最大并发=" + probe.maxActive.get() + "）");
        // 层1 三节点全部执行
        assertTrue(probe.execOrder.containsAll(List.of("a", "b", "c")));
        // 层间同步：d 的输入包含 a/b/c 三个上游产物（占位符被替换为上游输出 JSON）
        String dInput = probe.inputs.get("d");
        assertTrue(dInput.contains("\"ok\":\"a\"") && dInput.contains("\"ok\":\"b\"") && dInput.contains("\"ok\":\"c\""),
                "d 应注入全部前序产物（层间同步）");
        // 顺序：d 在 a/b/c 之后
        int idxA = probe.execOrder.indexOf("a");
        int idxD = probe.execOrder.indexOf("d");
        assertTrue(idxD > idxA, "d 应在层1 之后执行");
    }

    @Test
    @DisplayName("并行层节点失败 → 任务失败、同层已成功产物保留")
    void parallelLayerFailureStopsChainButKeepsSiblings() {
        task.setInputJson("{\"premise\":\"废土之上\",\"simulateFailNodeIds\":\"c\"}");
        runner.runChain(10L, 0);

        assertEquals(WorldGenStatus.FAILED, task.getStatus());
        // a/b 成功（产物保留），c 失败，d 未执行
        assertTrue(attempts.stream().anyMatch(a -> "a".equals(a.getNodeId()) && WorldGenStatus.SUCCEEDED.equals(a.getStatus())));
        assertTrue(attempts.stream().anyMatch(a -> "b".equals(a.getNodeId()) && WorldGenStatus.SUCCEEDED.equals(a.getStatus())));
        assertTrue(attempts.stream().anyMatch(a -> "c".equals(a.getNodeId()) && WorldGenStatus.FAILED.equals(a.getStatus())));
        assertTrue(attempts.stream().noneMatch(a -> "d".equals(a.getNodeId())), "失败层后不应执行下游");
    }

    @Test
    @DisplayName("续跑跳过已成功同层兄弟（防重复执行/重复付 AI 费）")
    void resumeSkipsSucceededSiblings() {
        // 第一轮：c 失败（a/b 成功）
        task.setInputJson("{\"premise\":\"废土之上\",\"simulateFailNodeIds\":\"c\"}");
        runner.runChain(10L, 0);
        assertEquals(WorldGenStatus.FAILED, task.getStatus());

        // 续跑：移除失败钩子，从 c（序号 3）续跑
        task.setInputJson("{\"premise\":\"废土之上\"}");
        task.setStatus(WorldGenStatus.RUNNING);
        runner.runChain(10L, 3);

        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        // 尝试计数：a=1 b=1（未被重跑）c=2（失败+成功）d=1
        long countA = attempts.stream().filter(a -> "a".equals(a.getNodeId())).count();
        long countB = attempts.stream().filter(a -> "b".equals(a.getNodeId())).count();
        long countC = attempts.stream().filter(a -> "c".equals(a.getNodeId())).count();
        long countD = attempts.stream().filter(a -> "d".equals(a.getNodeId())).count();
        assertEquals(1, countA, "a 已成功不应被重跑");
        assertEquals(1, countB, "b 已成功不应被重跑");
        assertEquals(2, countC, "c 应重跑（失败→成功）");
        assertEquals(1, countD);
        // d 的输入注入三份上游（a/b 重放 + c 新产物）
        String dInput = probe.inputs.get("d");
        assertTrue(dInput != null && dInput.contains("\"ok\":\"a\"") && dInput.contains("\"ok\":\"b\"")
                && dInput.contains("\"ok\":\"c\""));
    }

    @Test
    @DisplayName("并行度=1（串行配置）仍顺序执行")
    void parallelismOneFallsBackToSequential() {
        WorldGenProperties props = new WorldGenProperties();
        props.setParallelism(1);
        ReflectionTestUtils.setField(runner, "props", props);

        runner.runChain(10L, 0);

        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        assertEquals(1, probe.maxActive.get(), "并行度=1 不应出现并发");
        assertEquals(4, attempts.size());
    }

    @Test
    @DisplayName("解析器层号：同层多节点识别（并行判定依据）")
    void parserComputesLevels() {
        WorkflowDefinition def = new WorldGenDefinitionParser().parse("parallel_wf", "并行", 1,
                NODES_JSON, EDGES_JSON, VARIABLES_JSON);
        assertEquals(1, def.getLevelByNodeId().get("a"));
        assertEquals(1, def.getLevelByNodeId().get("b"));
        assertEquals(1, def.getLevelByNodeId().get("c"));
        assertEquals(2, def.getLevelByNodeId().get("d"));
        // 链式定义（线性边）→ 层严格递增（无并行层）
        String chainEdges = """
                [ {"source":"__start__","target":"a"},
                  {"source":"a","target":"b"},
                  {"source":"b","target":"c"},
                  {"source":"c","target":"d"},
                  {"source":"d","target":"__end__"} ]
                """;
        WorkflowDefinition chain = new WorldGenDefinitionParser().parse("chain", "链式", 1,
                NODES_JSON, chainEdges, VARIABLES_JSON);
        assertEquals(1, chain.getLevelByNodeId().get("a"));
        assertEquals(2, chain.getLevelByNodeId().get("b"));
        assertEquals(3, chain.getLevelByNodeId().get("c"));
        assertEquals(4, chain.getLevelByNodeId().get("d"));
        // 同一层无重复 → hasParallelLevels=false（经 runner 判定不并行）
        assertEquals(4, chain.getLevelByNodeId().values().stream().distinct().count());
    }
}
