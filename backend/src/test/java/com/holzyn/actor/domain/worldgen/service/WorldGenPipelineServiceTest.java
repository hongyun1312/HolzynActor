package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.service.WorldInitService;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import com.holzyn.actor.domain.worldgen.dto.CreateWorldGenTaskRequest;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenPipelineService（M4 适配轮完整项目串接编排，2026-09-29_05）单元测试。
 * <p>职责：验证——startFullPipeline 创建 character 任务并返回 ID、character 成功后
 * 依次落库角色/跑初始化/写编年史/complete、character 失败中断（不落库不初始化）、
 * awaitTask 轮询终态。</p>
 */
class WorldGenPipelineServiceTest {

    private ActorWorldgenTaskRepository taskRepository;
    private WorldGenTaskService taskService;
    private WorldGenCharacterService characterService;
    private WorldInitService worldInitService;
    private WorldMemoryService worldMemoryService;
    private WorldGenTaskSseHub sseHub;
    private CurrentUserProvider currentUserProvider;
    private ActorWorldSettingRepository worldSettingRepository;
    private WorldGenPipelineService pipeline;

    private final AtomicLong charTaskId = new AtomicLong(42L);

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        taskService = Mockito.mock(WorldGenTaskService.class);
        characterService = Mockito.mock(WorldGenCharacterService.class);
        worldInitService = Mockito.mock(WorldInitService.class);
        worldMemoryService = Mockito.mock(WorldMemoryService.class);
        sseHub = Mockito.mock(WorldGenTaskSseHub.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(taskService.createTask(any(CreateWorldGenTaskRequest.class)))
                .thenAnswer(inv -> charTaskId.get());
        when(characterService.buildCharacterInputs(anyLong())).thenReturn(Map.of("worldSetting", "设定", "language", "中文"));
        when(characterService.persistCharacters(anyLong()))
                .thenReturn(Map.of("created", 5, "skipped", 0));
        when(worldInitService.runInit(anyLong(), anyLong(), anyBoolean(), any()))
                .thenReturn(new WorldInitResultVO(5L, 8, 5, 5, 15, "星历 1050 年", 7));
        ActorWorldSetting ws = new ActorWorldSetting();
        ws.setName("青禾村");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(anyLong())).thenReturn(Optional.of(ws));

        pipeline = new WorldGenPipelineService(taskRepository, taskService, characterService,
                worldInitService, worldMemoryService, sseHub, currentUserProvider, worldSettingRepository);
        // 轮询间隔调小加速测试（实例字段注入）
        ReflectionTestUtils.setField(pipeline, "pollIntervalMs", 1L);
    }

    @Test
    @DisplayName("startFullPipeline：创建 character 任务并返回 ID（串接异步启动）")
    void startFullPipelineCreatesCharacterTask() {
        // character 任务立即成功（stub 轮询结果），异步串接线程可跑完整链路
        ActorWorldgenTask succeeded = new ActorWorldgenTask();
        succeeded.setId(42L);
        succeeded.setStatus(WorldGenStatus.SUCCEEDED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(succeeded));

        Long id = pipeline.startFullPipeline(5L, 7L);
        assertEquals(42L, id);
        // character 输入自动注入世界设定
        var captor = org.mockito.ArgumentCaptor.forClass(CreateWorldGenTaskRequest.class);
        verify(taskService).createTask(captor.capture());
        assertEquals(WorldGenCharacterService.CODE_CHARACTER_V1, captor.getValue().workflowCode());
        assertEquals("设定", captor.getValue().inputs().get("worldSetting"));
        // 异步串接线程完成 → 角色落库 + 初始化 + 编年史 + complete（timeout 等待）
        Mockito.verify(sseHub, Mockito.timeout(3000)).complete(eq(7L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(characterService).persistCharacters(42L);
    }

    @Test
    @DisplayName("串接主体：character 成功 → 角色落库 → 初始化 6 步 → 编年史 → complete")
    void runFullPipelineSucceeds() {
        // character 任务已成功（awaitTask 轮询命中）
        ActorWorldgenTask succeeded = new ActorWorldgenTask();
        succeeded.setId(42L);
        succeeded.setStatus(WorldGenStatus.SUCCEEDED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(succeeded));

        // 同步执行主体（不启异步线程）
        pipeline.runFullPipeline(1L, 5L, 7L, 42L);

        verify(characterService).persistCharacters(42L);
        verify(worldInitService).runInit(eq(1L), eq(5L), eq(false), any());
        verify(worldMemoryService).recordWorldGenChronicle(eq(5L), any(), any());
        verify(sseHub).complete(eq(7L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(sseHub, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("串接主体：character 失败 → 中断（不落库不初始化，SSE fail）")
    void runFullPipelineStopsWhenCharacterFailed() {
        ActorWorldgenTask failed = new ActorWorldgenTask();
        failed.setId(42L);
        failed.setStatus(WorldGenStatus.FAILED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(failed));

        pipeline.runFullPipeline(1L, 5L, 7L, 42L);

        verify(characterService, never()).persistCharacters(anyLong());
        verify(worldInitService, never()).runInit(anyLong(), anyLong(), anyBoolean(), any());
        verify(worldMemoryService, never()).recordWorldGenChronicle(anyLong(), any(), any());
        verify(sseHub).fail(eq(7L), org.mockito.ArgumentMatchers.contains("角色生成失败"));
    }

    @Test
    @DisplayName("串接主体（M4 story-plan 2026-09-29_06）：注入 storyPlanService → story 任务成功 → 初始化照常 + 编年史")
    void runFullPipelineWithStorySucceeds() {
        WorldGenStoryPlanService storyService = Mockito.mock(WorldGenStoryPlanService.class);
        ReflectionTestUtils.setField(pipeline, "storyPlanService", storyService);
        when(storyService.buildStoryInputs(5L)).thenReturn(Map.of("worldSetting", "设定", "language", "中文"));
        // character 任务 42 成功；story 任务 43 成功
        AtomicLong storyTaskId = new AtomicLong(43L);
        when(taskService.createTask(any(CreateWorldGenTaskRequest.class)))
                .thenAnswer(inv -> {
                    CreateWorldGenTaskRequest req = inv.getArgument(0);
                    return WorldGenCharacterService.CODE_CHARACTER_V1.equals(req.workflowCode())
                            ? charTaskId.get() : storyTaskId.get();
                });
        ActorWorldgenTask charOk = new ActorWorldgenTask();
        charOk.setId(42L);
        charOk.setStatus(WorldGenStatus.SUCCEEDED);
        ActorWorldgenTask storyOk = new ActorWorldgenTask();
        storyOk.setId(43L);
        storyOk.setStatus(WorldGenStatus.SUCCEEDED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(charOk));
        when(taskRepository.findById(43L)).thenReturn(Optional.of(storyOk));

        pipeline.runFullPipeline(1L, 5L, 7L, 42L);

        // runFullPipeline 直调：character 任务在 startFullPipeline 已建，此处只新建 story 任务（1 次 createTask）
        var captor = org.mockito.ArgumentCaptor.forClass(CreateWorldGenTaskRequest.class);
        verify(taskService, Mockito.times(1)).createTask(captor.capture());
        CreateWorldGenTaskRequest storyReq = captor.getValue();
        assertEquals(WorldGenStoryPlanService.CODE_STORY_PLAN_V1, storyReq.workflowCode());
        assertEquals("设定", storyReq.inputs().get("worldSetting"));
        // 初始化/编年史照常完成，无 fail
        verify(worldInitService).runInit(eq(1L), eq(5L), eq(false), any());
        verify(worldMemoryService).recordWorldGenChronicle(eq(5L), any(), any());
        verify(sseHub).complete(eq(7L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(sseHub, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("串接主体（M4 story-plan）：story 任务失败 → 不阻塞，初始化照常 + 无 fail")
    void runFullPipelineStoryFailureDoesNotBlock() {
        WorldGenStoryPlanService storyService = Mockito.mock(WorldGenStoryPlanService.class);
        ReflectionTestUtils.setField(pipeline, "storyPlanService", storyService);
        when(storyService.buildStoryInputs(5L)).thenReturn(Map.of("worldSetting", "设定", "language", "中文"));
        AtomicLong storyTaskId = new AtomicLong(43L);
        when(taskService.createTask(any(CreateWorldGenTaskRequest.class)))
                .thenAnswer(inv -> {
                    CreateWorldGenTaskRequest req = inv.getArgument(0);
                    return WorldGenCharacterService.CODE_CHARACTER_V1.equals(req.workflowCode())
                            ? charTaskId.get() : storyTaskId.get();
                });
        ActorWorldgenTask charOk = new ActorWorldgenTask();
        charOk.setId(42L);
        charOk.setStatus(WorldGenStatus.SUCCEEDED);
        ActorWorldgenTask storyFail = new ActorWorldgenTask();
        storyFail.setId(43L);
        storyFail.setStatus(WorldGenStatus.FAILED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(charOk));
        when(taskRepository.findById(43L)).thenReturn(Optional.of(storyFail));

        pipeline.runFullPipeline(1L, 5L, 7L, 42L);

        // story 失败仅提示，不 fail 流水线；初始化/编年史照常
        verify(worldInitService).runInit(eq(1L), eq(5L), eq(false), any());
        verify(worldMemoryService).recordWorldGenChronicle(eq(5L), any(), any());
        verify(sseHub).complete(eq(7L), eq(SseLifecycle.EVENT_RESULT), any());
        verify(sseHub, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("awaitTask：轮询至终态返回状态")
    void awaitTaskPollsToTerminal() {
        ActorWorldgenTask running = new ActorWorldgenTask();
        running.setId(42L);
        running.setStatus(WorldGenStatus.RUNNING);
        ActorWorldgenTask succeeded = new ActorWorldgenTask();
        succeeded.setId(42L);
        succeeded.setStatus(WorldGenStatus.SUCCEEDED);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(running), Optional.of(succeeded));

        assertEquals(WorldGenStatus.SUCCEEDED, pipeline.awaitTask(42L));
    }

    @Test
    @DisplayName("awaitTask：任务不存在返回 null")
    void awaitTaskReturnsNullWhenMissing() {
        when(taskRepository.findById(42L)).thenReturn(Optional.empty());
        assertEquals(null, pipeline.awaitTask(42L));
    }
}
