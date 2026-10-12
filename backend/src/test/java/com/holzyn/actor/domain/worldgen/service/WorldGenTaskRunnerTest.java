package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenTaskRunner（M1 引擎骨架：顺序执行节点链）单元测试。
 * <p>职责：验证——全链成功（每节点一次尝试/序号递增/任务 succeeded/result 广播）、
 * 中间节点失败即停链（后续节点零尝试/任务 failed/error 广播）、失败节点原地续跑
 * （重放上游产物 + 从该节点继续到成功）、自动重试（auto_retry_count 内失败重跑）、
 * 占位符填充（任务输入/上游产物/默认值/projectId）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库 + 内存尝试列表）；直接调用包内同步
 * {@code runChain} 验证执行逻辑（异步提交由运行时冒烟验证）。</p>
 */
class WorldGenTaskRunnerTest {

    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private WorldGenTaskSseHub sseHub;
    private WorldGenTaskRunner runner;

    /** 内存尝试列表（模拟仓库持久化） */
    private final List<ActorWorldgenNodeAttempt> attempts = new ArrayList<>();

    /** 任务实体（可变：状态/输入随用例调整） */
    private ActorWorldgenTask task;

    /** 工作流定义 JSON（agent_a → script_x → agent_b，agent_b 引用 agent_a 输出） */
    private static final String NODES_JSON = """
            [ {"id":"agent_a","label":"维度A","nodeType":"agent","agentType":"wb-a",
               "firstMessage":"【创意】{{premise}}\\n【前序】{{agent_b_output}}","outputVariable":"agent_a_output",
               "save_output_to_file":true,"outputFilePath":"world/a.json"},
              {"id":"script_x","label":"脚本X","nodeType":"script","script_name":"render",
               "script_args":"--in world/a.json"},
              {"id":"agent_b","label":"维度B","nodeType":"agent","agentType":"wb-b",
               "firstMessage":"【前序】{{agent_a_output}}","outputVariable":"agent_b_output"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"agent_a"},
              {"source":"agent_a","target":"script_x"},
              {"source":"script_x","target":"agent_b"},
              {"source":"agent_b","target":"__end__"} ]
            """;

    private static final String VARIABLES_JSON = """
            [ {"key":"premise","name":"题材创意","type":"textarea","defaultValue":"","required":true},
              {"key":"language","name":"输出语言","type":"text","defaultValue":"中文","required":false} ]
            """;

    @BeforeEach
    void setUp() {
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        sseHub = Mockito.mock(WorldGenTaskSseHub.class);
        attempts.clear();

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode("test_wf");
        workflow.setName("测试工作流");
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
        task.setInputJson("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\"}");
        when(taskRepository.findById(10L)).thenAnswer(inv -> Optional.of(task));
        doAnswer(inv -> {
            task = inv.getArgument(0);
            return task;
        }).when(taskRepository).save(any(ActorWorldgenTask.class));

        // 尝试仓库：内存 list 模拟（save 追加；两类查询按需过滤）
        when(attemptRepository.findByTaskIdOrderByIdAsc(anyLong())).thenAnswer(inv -> new ArrayList<>(attempts));
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(anyLong(), anyString()))
                .thenAnswer(inv -> attempts.stream().filter(a -> a.getNodeId().equals(inv.getArgument(1))).toList());
        when(attemptRepository.findByTaskIdAndNodeOrderGreaterThanEqualOrderByIdAsc(anyLong(), any()))
                .thenAnswer(inv -> attempts.stream()
                        .filter(a -> a.getNodeOrder() >= (int) inv.getArgument(1)).toList());
        doAnswer(inv -> {
            ActorWorldgenNodeAttempt a = inv.getArgument(0);
            if (a.getId() == null) {
                // 模拟 JPA identity 分配：无 id = 插入
                a.setId((long) (attempts.size() + 1));
                attempts.add(a);
            } else {
                // 有 id = 更新原行（节点执行中会先插后改两次 save）
                for (int i = 0; i < attempts.size(); i++) {
                    if (attempts.get(i).getId().equals(a.getId())) {
                        attempts.set(i, a);
                        break;
                    }
                }
            }
            return a;
        }).when(attemptRepository).save(any(ActorWorldgenNodeAttempt.class));

        runner = new WorldGenTaskRunner(workflowRepository, taskRepository, attemptRepository,
                new WorldGenDefinitionParser(), sseHub, List.of(new StubWorldGenNodeExecutor()));
    }

    /** 修改任务输入（模拟失败钩子/续跑前移除钩子） */
    private void setTaskInput(String json) {
        task.setInputJson(json);
    }

    @Test
    void fullChainSucceeds() {
        runner.runChain(10L, 0);

        // 任务最终 succeeded
        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        assertEquals(10L, task.getId());
        // 3 个节点各 1 次成功尝试，序号 1/2/3
        assertEquals(3, attempts.size());
        assertEquals("agent_a", attempts.get(0).getNodeId());
        assertEquals(1, attempts.get(0).getNodeOrder());
        assertEquals(WorldGenStatus.SUCCEEDED, attempts.get(0).getStatus());
        assertEquals("script_x", attempts.get(1).getNodeId());
        assertEquals(2, attempts.get(1).getNodeOrder());
        assertEquals("agent_b", attempts.get(2).getNodeId());
        assertEquals(3, attempts.get(2).getNodeOrder());
        // 桩输出为占位 JSON
        assertTrue(attempts.get(0).getOutputJson().contains("stub"));
        // 上游产物注入：agent_b 的输入包含 agent_a 输出回显
        assertTrue(attempts.get(2).getInputJson().contains("agent_a_output"));
        // result 广播
        verify(sseHub).complete(eq(10L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(sseHub, never()).fail(anyLong(), anyString());
    }

    @Test
    void failureStopsChainAtNode() {
        // 模拟 script_x 失败 → 链停在 script_x，agent_b 零尝试
        setTaskInput("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\","
                + "\"simulateFailNodeIds\":\"script_x\"}");
        runner.runChain(10L, 0);

        assertEquals(WorldGenStatus.FAILED, task.getStatus());
        assertEquals("script_x", task.getCurrentNodeId());
        // 只有 agent_a + script_x 两次尝试（script_x 为失败）
        assertEquals(2, attempts.size());
        assertEquals(WorldGenStatus.SUCCEEDED, attempts.get(0).getStatus());
        assertEquals(WorldGenStatus.FAILED, attempts.get(1).getStatus());
        assertTrue(attempts.get(1).getError().contains("script_x"));
        // 下游未执行
        assertTrue(attempts.stream().noneMatch(a -> a.getNodeId().equals("agent_b")),
                "失败节点后不应继续执行");
        verify(sseHub).fail(eq(10L), anyString());
    }

    @Test
    void resumeFromFailedNodeReplaysUpstreamAndCompletes() {
        // 第一轮：agent_b 失败（链停在 agent_b）
        setTaskInput("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\","
                + "\"simulateFailNodeIds\":\"agent_b\"}");
        runner.runChain(10L, 0);
        assertEquals(WorldGenStatus.FAILED, task.getStatus());
        assertEquals(3, attempts.size());
        assertEquals(WorldGenStatus.FAILED, attempts.get(2).getStatus());

        // 续跑：移除失败钩子，置回 running，从 agent_b（序号 3）续跑
        setTaskInput("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\"}");
        task.setStatus(WorldGenStatus.RUNNING);
        runner.runChain(10L, 3);

        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        // 追加 1 次尝试：agent_b 第二次尝试（retry_count=1）成功，重放了上游 agent_a 产物
        assertEquals(4, attempts.size());
        ActorWorldgenNodeAttempt retried = attempts.get(3);
        assertEquals("agent_b", retried.getNodeId());
        assertEquals(1, retried.getRetryCount());
        assertEquals(WorldGenStatus.SUCCEEDED, retried.getStatus());
        // 续跑输入仍注入上游（agent_a_output 已重放）
        assertTrue(retried.getInputJson().contains("agent_a_output"));
        // 首轮以 fail 收尾（error 事件），续跑成功才推送 result 事件（恰好 1 次）
        verify(sseHub, times(1)).complete(eq(10L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(sseHub, times(1)).fail(eq(10L), anyString());
    }

    @Test
    void autoRetryRerunsFailedNodeUntilSuccess() {
        // 单 agent 节点 + auto_retry_count=1；自定义执行器首调失败、次调成功
        String nodes = """
                [ {"id":"agent_a","label":"维度A","nodeType":"agent","agentType":"wb-a",
                   "firstMessage":"{{premise}}","outputVariable":"agent_a_output",
                   "autoRetryCount":1,"autoRetryIntervalSeconds":0} ]
                """;
        String edges = """
                [ {"source":"__start__","target":"agent_a"}, {"source":"agent_a","target":"__end__"} ]
                """;
        ActorWorldgenWorkflow wf = new ActorWorldgenWorkflow();
        wf.setId(2L);
        wf.setCode("retry_wf");
        wf.setName("自动重试");
        wf.setNodesJson(nodes);
        wf.setEdgesJson(edges);
        wf.setVariablesJson("[]");
        when(workflowRepository.findById(2L)).thenReturn(Optional.of(wf));
        task.setWorkflowId(2L);

        AtomicInteger calls = new AtomicInteger();
        WorldGenNodeExecutor flaky = new WorldGenNodeExecutor() {
            @Override
            public String supportedNodeType() {
                return "agent";
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionContext ctx) {
                if (calls.incrementAndGet() == 1) {
                    return NodeExecutionResult.failure("首次失败（测试）");
                }
                return NodeExecutionResult.success("{\"ok\":true}", 1L);
            }
        };
        runner = new WorldGenTaskRunner(workflowRepository, taskRepository, attemptRepository,
                new WorldGenDefinitionParser(), sseHub, List.of(flaky));

        runner.runChain(10L, 0);

        assertEquals(WorldGenStatus.SUCCEEDED, task.getStatus());
        // 2 次尝试：retry_count 0（失败）+ 1（成功）
        assertEquals(2, attempts.size());
        assertEquals(0, attempts.get(0).getRetryCount());
        assertEquals(WorldGenStatus.FAILED, attempts.get(0).getStatus());
        assertEquals(1, attempts.get(1).getRetryCount());
        assertEquals(WorldGenStatus.SUCCEEDED, attempts.get(1).getStatus());
        assertEquals("{\"ok\":true}", attempts.get(1).getOutputJson());
    }

    @Test
    void terminalTaskSkipped() {
        // 任务已 succeeded：不再执行
        task.setStatus(WorldGenStatus.SUCCEEDED);
        runner.runChain(10L, 0);
        assertEquals(0, attempts.size());
        verify(sseHub, never()).complete(anyLong(), anyString(), any());
    }

    @Test
    void fillPlaceholdersResolvesInputsUpstreamDefaultsAndProjectId() {
        WorkflowDefinition def = new WorldGenDefinitionParser().parse("p", "占位", 1,
                NODES_JSON, EDGES_JSON, VARIABLES_JSON);
        WorkflowNode node = def.node("agent_b");
        // 补 {{premise}}/{{language}} 占位符以验证任务输入与变量默认值（默认 中文）
        node.setFirstMessage("题材创意：{{premise}}\n【前序】{{agent_a_output}}\n输出语言：{{language}}");
        String filled = runner.fillPlaceholders(node, def,
                Map.of("premise", "废土之上", "genre", "科幻末世"),
                Map.of("agent_a_output", "{\"essence\":\"法则\"}"),
                5L);
        assertTrue(filled.contains("废土之上"));
        assertTrue(filled.contains("{\"essence\":\"法则\"}"), "上游产物应注入【前序维度】");
        assertTrue(filled.contains("中文"), "变量默认值应生效（language=中文）");

        // script 节点 script_args 含 {{projectId}} 也应填充
        WorkflowNode script = def.node("script_x");
        script.setScriptArgs("persist --project-id {{projectId}}");
        String scriptFilled = runner.fillPlaceholders(script, def, Map.of(), Map.of(), 5L);
        assertTrue(scriptFilled.contains("persist --project-id 5"));
    }

    @Test
    void leftoverPlaceholdersReplacedWithEmptyString() {
        // M1 遗留收尾：被跳过上游节点的输出变量/未知键残留 {{xxx}} → 替换为空串
        WorkflowDefinition def = new WorldGenDefinitionParser().parse("p", "占位", 1,
                NODES_JSON, EDGES_JSON, VARIABLES_JSON);
        WorkflowNode node = def.node("agent_b");
        node.setFirstMessage("【前序】{{agent_a_output}}，未知：{{ghost_key}}");
        String filled = runner.fillPlaceholders(node, def, Map.of(), Map.of(), 5L);
        assertTrue(!filled.contains("{{"), "残留占位符应被替换为空串");
        assertTrue(!filled.contains("ghost_key"));
    }
}
