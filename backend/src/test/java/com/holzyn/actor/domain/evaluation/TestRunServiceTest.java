package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.agent.roleplay.AutonomousChatService;
import com.holzyn.actor.domain.evaluation.entity.ActorTestRun;
import com.holzyn.actor.domain.evaluation.repository.ActorTestRunEventRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorTestRunRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.TimelineService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestRunService 单元测试（O2.5 专门测试接口门面：test.enabled 门控 / 阶段控制命令分发 /
 * 干预驱动 / 完整回放落库）。
 */
class TestRunServiceTest {

    private ActorTestRunRepository runRepository;
    private ActorTestRunEventRepository eventRepository;
    private WorldClockService worldClockService;
    private WorldEngine worldEngine;
    private AiIntensityGate aiIntensityGate;
    private TimelineService timelineService;
    private AgentRuntime agentRuntime;
    private AutonomousChatService autonomousChatService;
    private TestStateAggregator stateAggregator;
    private EvaluationProperties properties;
    private CurrentUserProvider currentUserProvider;
    private ActorCharacterRepository characterRepository;
    private TestRunService service;

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(ActorTestRunRepository.class);
        eventRepository = Mockito.mock(ActorTestRunEventRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldEngine = Mockito.mock(WorldEngine.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        timelineService = Mockito.mock(TimelineService.class);
        agentRuntime = Mockito.mock(AgentRuntime.class);
        autonomousChatService = Mockito.mock(AutonomousChatService.class);
        stateAggregator = Mockito.mock(TestStateAggregator.class);
        properties = new EvaluationProperties();
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        service = new TestRunService(runRepository, eventRepository, worldClockService, worldEngine,
                aiIntensityGate, timelineService, agentRuntime, autonomousChatService, stateAggregator,
                properties, currentUserProvider, characterRepository);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(eventRepository.countByRunId(any())).thenReturn(0L);
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** test.enabled=false（Q8 默认）→ 创建 run 抛 403。 */
    @Test
    void gateBlocksWhenTestDisabled() {
        ReflectionTestUtils.setField(properties, "testEnabled", false);
        BizException e = assertThrows(BizException.class, () -> service.createRun(1L, "x", "y"));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("测试接口未启用"));
    }

    /** 创建 run：status=active + 起始游戏位置落库。 */
    @Test
    void createRunLifecycle() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(86400L);
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> {
            ActorTestRun r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        var vo = service.createRun(1L, "冒烟", "验证五能力");
        assertEquals("active", vo.status());
        assertEquals(86400L, vo.startGameSecond());
    }

    /** phase_pause：委托 updateClock(paused=true) + 落 command 事件 + 自动快照。 */
    @Test
    void phasePauseDispatchesAndLogs() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(100L);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stateAggregator.aggregate(eq(1L), any())).thenReturn(Map.of("engine", Map.of()));

        service.executeCommand(1L, 1L, "phase_pause", Map.of());

        ArgumentCaptor<WorldClockDTO> dto = ArgumentCaptor.forClass(WorldClockDTO.class);
        verify(worldClockService).updateClock(eq(1L), eq(1L), dto.capture());
        assertEquals(true, dto.getValue().paused());
        // 命令事件 + 快照事件各 1 条
        ArgumentCaptor<com.holzyn.actor.domain.evaluation.entity.ActorTestRunEvent> ev =
                ArgumentCaptor.forClass(com.holzyn.actor.domain.evaluation.entity.ActorTestRunEvent.class);
        verify(eventRepository, Mockito.times(2)).save(ev.capture());
        assertEquals("command", ev.getAllValues().get(0).getEventType());
        assertEquals("phase_pause", ev.getAllValues().get(0).getAction());
        assertEquals("snapshot", ev.getAllValues().get(1).getEventType());
    }

    /** phase_fastforward：参数透传（targetGameSecond），计数+1。 */
    @Test
    void phaseFastForwardDispatches() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(100L);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stateAggregator.aggregate(eq(1L), any())).thenReturn(Map.of());
        when(worldClockService.fastForward(eq(1L), eq(999999L), eq(null), eq(null), eq(null)))
                .thenReturn(new com.holzyn.actor.domain.world.vo.WorldClockVO());

        service.executeCommand(1L, 1L, "phase_fastforward", Map.of("targetGameSecond", 999999L));
        verify(worldClockService).fastForward(eq(1L), eq(999999L), eq(null), eq(null), eq(null));
    }

    /** phase_fastforward：无任何目标参数 → 400。 */
    @Test
    void phaseFastForwardRequiresParams() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        BizException e = assertThrows(BizException.class,
                () -> service.executeCommand(1L, 1L, "phase_fastforward", Map.of()));
        assertEquals(400, e.getCode());
    }

    /** phase_intensity：委托 AiIntensityGate.setIntensity。 */
    @Test
    void phaseIntensityDispatches() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(100L);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stateAggregator.aggregate(eq(1L), any())).thenReturn(Map.of());
        service.executeCommand(1L, 1L, "phase_intensity", Map.of("level", "max"));
        verify(aiIntensityGate).setIntensity(1L, "max");
    }

    /** trigger_chat：委托 AutonomousChatService 剧集触发（③ 干预驱动 群聊）。 */
    @Test
    void triggerChatDispatches() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(100L);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stateAggregator.aggregate(eq(1L), any())).thenReturn(Map.of());
        when(autonomousChatService.triggerEpisodeForTest(eq(1L), eq(1L), eq(null), eq("青禾集市")))
                .thenReturn(Map.of("status", "started", "locationName", "青禾集市"));
        service.executeCommand(1L, 1L, "trigger_chat", Map.of("locationName", "青禾集市"));
        verify(autonomousChatService).triggerEpisodeForTest(eq(1L), eq(1L), eq(null), eq("青禾集市"));
    }

    /** 未知 action → 400。 */
    @Test
    void unknownActionRejected() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        when(runRepository.findById(1L)).thenReturn(Optional.of(activeRun(1L)));
        BizException e = assertThrows(BizException.class,
                () -> service.executeCommand(1L, 1L, "fly_to_moon", Map.of()));
        assertEquals(400, e.getCode());
    }

    /** 收尾：status=finished + 汇总 JSON 含计数。 */
    @Test
    void finishRunSummarizes() {
        ReflectionTestUtils.setField(properties, "testEnabled", true);
        ActorTestRun run = activeRun(1L);
        run.setStartRealMs(1000L);
        run.setStartGameSecond(100L);
        run.setCommandCount(3);
        run.setInterveneCount(1);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(200L);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(runRepository.save(any(ActorTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.countByRunId(1L)).thenReturn(5L);
        var vo = service.finishRun(1L, 1L);
        assertEquals("finished", vo.status());
        assertTrue(vo.summaryJson().contains("\"commandCount\":3"));
        assertTrue(vo.summaryJson().contains("\"eventCount\":5"));
        assertTrue(vo.summaryJson().contains("\"advancedGameSeconds\":100"));
    }

    private ActorTestRun activeRun(Long id) {
        ActorTestRun r = new ActorTestRun();
        r.setId(id);
        r.setProjectId(1L);
        r.setStatus("active");
        return r;
    }
}
