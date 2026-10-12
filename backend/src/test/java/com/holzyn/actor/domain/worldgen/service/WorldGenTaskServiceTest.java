package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.worldgen.dto.CreateWorldGenTaskRequest;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import com.holzyn.actor.domain.worldgen.vo.WorldGenNodeVO;
import com.holzyn.actor.domain.worldgen.vo.WorldGenTaskVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenTaskService（M1 引擎骨架：任务创建/查询/失败节点重试·跳过 CAS）单元测试。
 * <p>职责：验证——创建任务（项目/工作流/必填输入校验 + 输入冻结 + 异步执行）、任务 VO 组装
 * （节点进度 + Token 汇总）、retry/skip 前置守卫（运行中/已成功/仅失败节点/CAS 尝试数不匹配→409）、
 * retry 续跑起点=节点自身序号、skip 追加 skipped 尝试行且续跑起点=下一序号。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库 + 真实解析器 + mock runner）。</p>
 */
class WorldGenTaskServiceTest {

    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private ActorProjectRepository projectRepository;
    private WorldGenTaskRunner taskRunner;
    private WorldGenPersistService persistService;
    private WorldGenTaskService service;

    private ActorWorldgenWorkflow workflow;
    private ActorWorldgenTask task;

    private static final String NODES_JSON = """
            [ {"id":"agent_a","label":"维度A","nodeType":"agent","agentType":"wb-a",
               "firstMessage":"{{premise}}","outputVariable":"agent_a_output"},
              {"id":"script_x","label":"脚本X","nodeType":"script","script_name":"render","script_args":"--in a.json"},
              {"id":"agent_b","label":"维度B","nodeType":"agent","agentType":"wb-b",
               "firstMessage":"{{agent_a_output}}","outputVariable":"agent_b_output"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"agent_a"},
              {"source":"agent_a","target":"script_x"},
              {"source":"script_x","target":"agent_b"},
              {"source":"agent_b","target":"__end__"} ]
            """;

    private static final String VARIABLES_JSON = """
            [ {"key":"premise","name":"题材创意","type":"textarea","defaultValue":"","required":true},
              {"key":"genre","name":"题材类型","type":"text","defaultValue":"","required":true},
              {"key":"language","name":"输出语言","type":"text","defaultValue":"中文","required":false} ]
            """;

    @BeforeEach
    void setUp() {
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        taskRunner = Mockito.mock(WorldGenTaskRunner.class);
        persistService = Mockito.mock(WorldGenPersistService.class);
        service = new WorldGenTaskService(workflowRepository, taskRepository, attemptRepository,
                projectRepository, new WorldGenDefinitionParser(), taskRunner, persistService);

        workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode("worldgen_build_v1");
        workflow.setName("世界观生成（6 维链式）");
        workflow.setVersion(1);
        workflow.setEnabled(1);
        workflow.setNodesJson(NODES_JSON);
        workflow.setEdgesJson(EDGES_JSON);
        workflow.setVariablesJson(VARIABLES_JSON);
        when(workflowRepository.findByCode("worldgen_build_v1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));

        task = new ActorWorldgenTask();
        task.setId(10L);
        task.setWorkflowId(1L);
        task.setProjectId(5L);
        task.setStatus(WorldGenStatus.SUCCEEDED);
        task.setInputJson("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\"}");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        doAnswer(inv -> {
            ActorWorldgenTask t = inv.getArgument(0);
            if (t.getId() == null) {
                // 模拟 JPA identity 分配主键（createTask 保存新实体后返回 id）
                t.setId(10L);
            }
            task = t;
            return t;
        }).when(taskRepository).save(any(ActorWorldgenTask.class));

        when(projectRepository.existsById(5L)).thenReturn(true);
        // 默认无尝试（retryNode/getTask 内部查询兜底为空）
        when(attemptRepository.findByTaskIdOrderByIdAsc(anyLong())).thenReturn(List.of());
    }

    // ==================== 工作流列表 ====================

    @Test
    void listWorkflowsReturnsEnabledWithNodeCount() {
        when(workflowRepository.findByEnabledOrderByIdAsc(1)).thenReturn(List.of(workflow));
        var vos = service.listWorkflows();
        assertEquals(1, vos.size());
        assertEquals("worldgen_build_v1", vos.get(0).getCode());
        assertEquals(3, vos.get(0).getNodeCount());
    }

    // ==================== 创建任务 ====================

    @Test
    void createTaskSucceedsWithDefaultsAndStartsRunner() {
        Long id = service.createTask(new CreateWorldGenTaskRequest(5L, "worldgen_build_v1",
                Map.of("premise", "废土之上", "genre", "科幻末世")));
        assertEquals(10L, id);
        // 输入冻结：language 默认「中文」被应用（解析式断言，兼容任意转义形式）
        tools.jackson.databind.JsonNode stored =
                new tools.jackson.databind.ObjectMapper().readTree(task.getInputJson());
        assertEquals("中文", stored.get("language").asText());
        assertEquals(WorldGenStatus.RUNNING, task.getStatus());
        verify(taskRunner).runAsync(10L);
    }

    @Test
    void createTaskRejectsMissingRequiredInput() {
        BizException e = assertThrows(BizException.class,
                () -> service.createTask(new CreateWorldGenTaskRequest(5L, "worldgen_build_v1",
                        Map.of("genre", "科幻末世"))));
        assertTrue(e.getMessage().contains("缺少必填输入"));
        verify(taskRunner, never()).runAsync(anyLong());
    }

    @Test
    void createTaskRejectsUnknownWorkflow() {
        BizException e = assertThrows(BizException.class,
                () -> service.createTask(new CreateWorldGenTaskRequest(5L, "ghost_wf",
                        Map.of("premise", "x"))));
        assertEquals(404, e.getCode());
    }

    @Test
    void createTaskRejectsMissingProject() {
        when(projectRepository.existsById(9L)).thenReturn(false);
        BizException e = assertThrows(BizException.class,
                () -> service.createTask(new CreateWorldGenTaskRequest(9L, "worldgen_build_v1",
                        Map.of("premise", "x", "genre", "y"))));
        assertEquals(404, e.getCode());
    }

    // ==================== 任务查询 ====================

    @Test
    void getTaskBuildsNodesInExecutionOrderWithTotals() {
        // 3 个成功尝试（token/duration 汇总）
        when(attemptRepository.findByTaskIdOrderByIdAsc(10L)).thenReturn(List.of(
                attempt(1L, "agent_a", 1, WorldGenStatus.SUCCEEDED, 0, 10, 100, "{\"a\":1}"),
                attempt(2L, "script_x", 2, WorldGenStatus.SUCCEEDED, 0, 5, 50, "{\"s\":1}"),
                attempt(3L, "agent_b", 3, WorldGenStatus.SUCCEEDED, 0, 20, 200, "{\"b\":1}")
        ));
        WorldGenTaskVO vo = service.getTask(10L);
        assertEquals("worldgen_build_v1", vo.getWorkflowCode());
        assertEquals(3, vo.getNodes().size());
        // 按执行顺序
        assertEquals(List.of("agent_a", "script_x", "agent_b"),
                vo.getNodes().stream().map(WorldGenNodeVO::getNodeId).toList());
        // 汇总
        assertEquals(35L, vo.getTokenOut());
        assertEquals(350L, vo.getDurationMs());
        // 节点产物预览
        assertNotNull(vo.getNodes().get(0).getOutput());
    }

    // ==================== retry / skip（CAS） ====================

    @Test
    void retryNodeSuccessResumesFromNodeOrder() {
        task.setStatus(WorldGenStatus.FAILED);
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(10L, "agent_b"))
                .thenReturn(List.of(attempt(1L, "agent_b", 3, WorldGenStatus.FAILED, 0, 0, 0, null)));
        service.retryNode(10L, "agent_b", 1);
        assertEquals(WorldGenStatus.RUNNING, task.getStatus());
        // 续跑起点 = 节点自身序号 3
        verify(taskRunner).resumeFromAsync(10L, 3);
    }

    @Test
    void retryNodeCasMismatchRejected() {
        task.setStatus(WorldGenStatus.FAILED);
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(10L, "agent_b"))
                .thenReturn(List.of(attempt(1L, "agent_b", 3, WorldGenStatus.FAILED, 0, 0, 0, null)));
        BizException e = assertThrows(BizException.class,
                () -> service.retryNode(10L, "agent_b", 2));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("CAS"));
        verify(taskRunner, never()).resumeFromAsync(anyLong(), anyInt());
    }

    @Test
    void retryNodeWhileRunningRejected() {
        task.setStatus(WorldGenStatus.RUNNING);
        BizException e = assertThrows(BizException.class,
                () -> service.retryNode(10L, "agent_b", 1));
        assertEquals(409, e.getCode());
    }

    @Test
    void retryNodeOnlyOnFailedNode() {
        task.setStatus(WorldGenStatus.FAILED);
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(10L, "agent_b"))
                .thenReturn(List.of(attempt(1L, "agent_b", 3, WorldGenStatus.SUCCEEDED, 0, 0, 0, "{\"b\":1}")));
        BizException e = assertThrows(BizException.class,
                () -> service.retryNode(10L, "agent_b", 1));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("仅可重试失败节点"));
    }

    @Test
    void skipNodeInsertsSkippedAttemptAndResumesFromNextOrder() {
        task.setStatus(WorldGenStatus.FAILED);
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(10L, "agent_b"))
                .thenReturn(List.of(attempt(1L, "agent_b", 3, WorldGenStatus.FAILED, 0, 0, 0, null)));
        service.skipNode(10L, "agent_b", 1);
        // 追加一行 skipped 尝试（retry_count=当前尝试数 1）
        ArgumentCaptor<ActorWorldgenNodeAttempt> captor = ArgumentCaptor.forClass(ActorWorldgenNodeAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertEquals(WorldGenStatus.SKIPPED, captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getRetryCount());
        assertEquals("agent_b", captor.getValue().getNodeId());
        assertEquals(WorldGenStatus.RUNNING, task.getStatus());
        // 续跑起点 = 节点序号 3 + 1 = 4
        verify(taskRunner).resumeFromAsync(10L, 4);
    }

    // ==================== 预览 / 确认（M2-3） ====================

    @Test
    void previewTaskDelegatesToPersistService() {
        var vo = new com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO();
        vo.setTaskId(10L);
        when(persistService.preview(10L)).thenReturn(vo);
        assertEquals(vo, service.previewTask(10L));
        verify(persistService).preview(10L);
    }

    @Test
    void confirmTaskDelegatesToPersistService() {
        when(persistService.confirm(10L)).thenReturn(Map.of("locationAdded", 2));
        Map<String, Object> out = service.confirmTask(10L);
        assertEquals(2, out.get("locationAdded"));
        verify(persistService).confirm(10L);
    }

    @Test
    void previewTaskMissingTaskRejected() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class, () -> service.previewTask(99L));
        assertEquals(404, e.getCode());
        verify(persistService, never()).preview(anyLong());
    }

    // ==================== 工具 ====================

    private static ActorWorldgenNodeAttempt attempt(Long id, String nodeId, int order, String status,
                                                    int tokenIn, int tokenOut, long duration, String output) {
        ActorWorldgenNodeAttempt a = new ActorWorldgenNodeAttempt();
        a.setId(id);
        a.setTaskId(10L);
        a.setNodeId(nodeId);
        a.setNodeOrder(order);
        a.setStepType(nodeId.startsWith("script") ? "script" : "agent");
        a.setStatus(status);
        a.setTokenIn(tokenIn);
        a.setTokenOut(tokenOut);
        a.setDurationMs(duration);
        a.setOutputJson(output);
        return a;
    }
}
