package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EngineScheduler（D4 F9 统一调度壳）单元测试（与实现同包，domain/agent/runtime）。
 * <p>职责：验证世界推进扫描与计划行动到期执行的统一调度（收编原 WorldSimulationJob +
 * ActionScheduledJob）——暂停语义统一（paused 项目跳过计划执行）、开关门控、单计划异常隔离。</p>
 */
class EngineSchedulerTest {

    private WorldEngine worldEngine;
    private ActorActionPlanRepository planRepository;
    private AgentActionService agentActionService;
    private ActorCharacterRepository characterRepository;
    private ActorWorldClockRepository clockRepository;
    private EngineScheduler scheduler;

    @BeforeEach
    void setUp() {
        worldEngine = Mockito.mock(WorldEngine.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        agentActionService = Mockito.mock(AgentActionService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        clockRepository = Mockito.mock(ActorWorldClockRepository.class);
        scheduler = new EngineScheduler(worldEngine, planRepository, agentActionService,
                characterRepository, clockRepository);
        ReflectionTestUtils.setField(scheduler, "worldScanEnabled", true);
        ReflectionTestUtils.setField(scheduler, "actionExecuteEnabled", true);
    }

    private ActorActionPlan plan(long id, long characterId) {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(id);
        p.setCharacterId(characterId);
        p.setPlannedTime(LocalDateTime.now().minusMinutes(1));
        p.setStatus("planned");
        return p;
    }

    @Test
    void runDuePlansExecutesNonPaused() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(plan(10L, 1L)));
        scheduler.runDuePlans();
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
    }

    @Test
    void runDuePlansSkipsPausedProject() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(1);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(plan(10L, 1L)));
        scheduler.runDuePlans();
        verify(agentActionService, never()).execute(any(ActorActionPlan.class));
    }

    @Test
    void runDuePlansIsolatesSinglePlanFailure() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(anyLong())).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(plan(10L, 1L), plan(11L, 1L)));
        Mockito.doThrow(new RuntimeException("exec-fail")).when(agentActionService).execute(any(ActorActionPlan.class));
        scheduler.runDuePlans();
        verify(agentActionService, times(2)).execute(any(ActorActionPlan.class));
    }

    @Test
    void runDuePlansDisabledBySwitch() {
        ReflectionTestUtils.setField(scheduler, "actionExecuteEnabled", false);
        scheduler.runDuePlans();
        verify(planRepository, never()).findByStatusAndPlannedTimeLessThanEqual(any(), any());
        verify(agentActionService, never()).execute(any(ActorActionPlan.class));
    }

    @Test
    void worldScanDelegatesAndRespectsSwitch() {
        scheduler.worldScan();
        verify(worldEngine, times(1)).scanAndAdvance();
        ReflectionTestUtils.setField(scheduler, "worldScanEnabled", false);
        scheduler.worldScan();
        verify(worldEngine, times(1)).scanAndAdvance();
    }

    @Test
    void runDuePlansSkipsWaitPlan() {
        // ⚠️ M4 冒烟抓修（2026-09-07）：等待类计划（planned_game_second 非空，时间感行动排期）
        // 由 WorldEngine 游戏时刻到点统一处理（runDueWaitPlans），真实时间定时器不得抢先执行。
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        ActorActionPlan waitPlan = plan(10L, 1L);
        waitPlan.setPlannedGameSecond(123456L); // 等待计划标记
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(waitPlan));
        scheduler.runDuePlans();
        verify(agentActionService, never()).execute(any(ActorActionPlan.class));
    }

    @Test
    void runDuePlansExecutesMixedSkipsWaitKeepsNormal() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(anyLong())).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        ActorActionPlan waitPlan = plan(10L, 1L);
        waitPlan.setPlannedGameSecond(123456L); // 等待计划跳过
        ActorActionPlan normal = plan(11L, 1L); // 普通计划执行
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(waitPlan, normal));
        scheduler.runDuePlans();
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
    }

    @Test
    void nullCharacterIsNotPaused() {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(1L);
        p.setCharacterId(null);
        p.setPlannedTime(LocalDateTime.now());
        p.setStatus("planned");
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(p));
        scheduler.runDuePlans();
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
        assertTrue(true);
    }

    // ==================== M6 实时决策激活闸门（未激活项目不执行到期计划） ====================

    @Test
    void runDuePlansSkipsInactiveProject() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(scheduler, "realtimeDecisionActivationService", activationService);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(activationService.isActive(1L)).thenReturn(false);
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(plan(10L, 1L)));
        scheduler.runDuePlans();
        verify(agentActionService, never()).execute(any(ActorActionPlan.class));
    }

    @Test
    void runDuePlansExecutesWhenProjectActive() {
        RealtimeDecisionActivationService activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        ReflectionTestUtils.setField(scheduler, "realtimeDecisionActivationService", activationService);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(0);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(activationService.isActive(1L)).thenReturn(true);
        when(planRepository.findByStatusAndPlannedTimeLessThanEqual(eq("planned"), any(LocalDateTime.class)))
                .thenReturn(List.of(plan(10L, 1L)));
        scheduler.runDuePlans();
        verify(agentActionService, times(1)).execute(any(ActorActionPlan.class));
    }
}