package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WorldGenTaskRecoveryRunner（M5 任务恢复，2026-09-29_05 适配轮）单元测试。
 * <p>职责：验证——断点定位（从 current_node_id 起找第一个非成功节点）、
 * 全部已成功时判-1（应收尾）、续跑启动（resumeFromAsync 调用）、工作流缺失时从头续跑。</p>
 */
class WorldGenTaskRecoveryRunnerTest {

    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private WorldGenTaskRunner taskRunner;
    private WorldGenTaskRecoveryRunner recovery;

    /** 内存尝试列表 */
    private final List<ActorWorldgenNodeAttempt> attempts = new ArrayList<>();

    private static final String NODES_JSON = """
            [ {"id":"a","label":"A","nodeType":"agent","agentType":"wb-a","firstMessage":"{{premise}}","outputVariable":"out_a"},
              {"id":"b","label":"B","nodeType":"agent","agentType":"wb-b","firstMessage":"{{out_a}}","outputVariable":"out_b"},
              {"id":"c","label":"C","nodeType":"agent","agentType":"wb-c","firstMessage":"{{out_b}}","outputVariable":"out_c"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"a"},
              {"source":"a","target":"b"},
              {"source":"b","target":"c"},
              {"source":"c","target":"__end__"} ]
            """;

    @BeforeEach
    void setUp() {
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        taskRunner = Mockito.mock(WorldGenTaskRunner.class);
        attempts.clear();

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode("recover_wf");
        workflow.setName("恢复工作流");
        workflow.setVersion(1);
        workflow.setNodesJson(NODES_JSON);
        workflow.setEdgesJson(EDGES_JSON);
        workflow.setVariablesJson("[]");
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));

        when(attemptRepository.findByTaskIdOrderByIdAsc(anyLong())).thenAnswer(inv -> new ArrayList<>(attempts));
        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(anyLong(), anyString()))
                .thenAnswer(inv -> attempts.stream().filter(a -> a.getNodeId().equals(inv.getArgument(1))).toList());

        recovery = new WorldGenTaskRecoveryRunner(taskRepository, workflowRepository,
                attemptRepository, new WorldGenDefinitionParser(), taskRunner);
    }

    /** 构造任务（status/currentNodeId 可配）。 */
    private ActorWorldgenTask task(String status, String currentNodeId) {
        ActorWorldgenTask t = new ActorWorldgenTask();
        t.setId(10L);
        t.setWorkflowId(1L);
        t.setProjectId(5L);
        t.setStatus(status);
        t.setCurrentNodeId(currentNodeId);
        t.setInputJson("{\"premise\":\"废土之上\"}");
        return t;
    }

    /** 追加一次成功尝试。 */
    private void succeed(String nodeId) {
        ActorWorldgenNodeAttempt a = new ActorWorldgenNodeAttempt();
        a.setId((long) (attempts.size() + 1));
        a.setTaskId(10L);
        a.setNodeId(nodeId);
        a.setStatus(WorldGenStatus.SUCCEEDED);
        a.setOutputJson("{\"ok\":\"" + nodeId + "\"}");
        attempts.add(a);
    }

    @Test
    @DisplayName("断点定位：current_node_id 起第一个非成功节点")
    void resolveStartOrderFromBreakpoint() {
        succeed("a");
        ActorWorldgenTask t = task(WorldGenStatus.RUNNING, "b");
        assertEquals(2, recovery.resolveStartOrder(t), "b 未执行 → 应从序号 2 续跑");
    }

    @Test
    @DisplayName("断点定位：current_node_id 节点已成功 → 顺延到下一未成功节点")
    void resolveStartOrderSkipsSucceededAtBreakpoint() {
        succeed("a");
        succeed("b");
        ActorWorldgenTask t = task(WorldGenStatus.RUNNING, "b");
        assertEquals(3, recovery.resolveStartOrder(t), "b 已成功 → 应从 c（序号 3）续跑");
    }

    @Test
    @DisplayName("全部节点已成功 → 返回 -1（任务应收尾，不续跑）")
    void resolveStartOrderAllSucceededReturnsMinusOne() {
        succeed("a");
        succeed("b");
        succeed("c");
        ActorWorldgenTask t = task(WorldGenStatus.RUNNING, "c");
        assertEquals(-1, recovery.resolveStartOrder(t));
    }

    @Test
    @DisplayName("current_node_id 缺失/不在链中 → 从头（序号 1）续跑")
    void resolveStartOrderFallsBackToHead() {
        succeed("b"); // 中间成功但无断点定位 → 从头（a 未成功）
        ActorWorldgenTask t = task(WorldGenStatus.RUNNING, null);
        assertEquals(1, recovery.resolveStartOrder(t));
        ActorWorldgenTask t2 = task(WorldGenStatus.RUNNING, "ghost_node");
        assertEquals(1, recovery.resolveStartOrder(t2));
    }

    @Test
    @DisplayName("工作流定义缺失 → 从头续跑（runner 会提示定义缺失）")
    void resolveStartOrderWithoutWorkflow() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.empty());
        ActorWorldgenTask t = task(WorldGenStatus.RUNNING, "b");
        assertEquals(1, recovery.resolveStartOrder(t));
    }

    @Test
    @DisplayName("启动扫描：running 任务发起续跑（查询即限定 running，终态天然不恢复）")
    void runScansRunningTasksOnly() {
        ActorWorldgenTask running = task(WorldGenStatus.RUNNING, "b");
        when(taskRepository.findByStatusOrderByIdAsc(WorldGenStatus.RUNNING)).thenReturn(List.of(running));
        recovery.run(null);
        Mockito.verify(taskRunner).resumeFromAsync(10L, 2);
        // 终态任务不恢复：即使误入列表也被 isTaskTerminal 前置拒绝（runner 幂等兜底），此处验证查询口径
        Mockito.verify(taskRepository, Mockito.never()).findByStatusOrderByIdAsc(WorldGenStatus.FAILED);
        Mockito.verify(taskRepository, Mockito.never()).findByStatusOrderByIdAsc(WorldGenStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("running 但全部节点已成功 → 直接收尾（save 标记 succeeded），不发起续跑")
    void runFinishesTaskWhenAllNodesSucceeded() {
        succeed("a");
        succeed("b");
        succeed("c");
        ActorWorldgenTask running = task(WorldGenStatus.RUNNING, "c");
        when(taskRepository.findByStatusOrderByIdAsc(WorldGenStatus.RUNNING)).thenReturn(List.of(running));
        recovery.run(null);
        Mockito.verify(taskRunner, Mockito.never()).resumeFromAsync(Mockito.anyLong(), Mockito.anyInt());
        Mockito.verify(taskRepository).save(Mockito.argThat(t -> WorldGenStatus.SUCCEEDED.equals(t.getStatus())));
    }
}
