package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.world.config.EvolutionDramatizeProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重大世界帧事件触发演绎单元测试（世界演化与游戏时间系统 M6，domain/world/service，同包）。
 * <p>职责：验证——① 总开关（关=不注册订阅者/不触发）；② 触发类别限定（kind=world_frame +
 * source=engine，evolution 归档排除防「演绎的演绎」）；③ 重要度阈值；④ 实时决策激活闸门；
 * ⑤ 幂等去重（同 source_key 只演绎一次）；⑥ 每日限频（每项目每游戏日 ≤ maxPerDay）；
 * ⑦ 独立每日预算（2026-09-07 用户确认：不依赖 tick 预算，触发即异步创建演绎会话——保证引擎活跃
 * 时重大事件一定被演绎，成本由开关默认关+每日限频约束）。</p>
 * <p>设计依据：《HolzynActor_世界演化与游戏时间系统_开发路径_V1.0》§4.5 + 2026-09-07 用户决策。</p>
 */
class EvolutionDramatizeTriggerTest {

    private EvolutionDramatizeProperties props;
    private WorldEventBus worldEventBus;
    private WorldEvolutionMechanismService evolutionMechanismService;
    private RealtimeDecisionActivationService activationService;
    private EvolutionDramatizeTrigger trigger;

    @BeforeEach
    void setUp() {
        props = new EvolutionDramatizeProperties();
        props.setEnabled(true);
        props.setImportanceThreshold(4);
        props.setMaxPerDay(1);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        evolutionMechanismService = Mockito.mock(WorldEvolutionMechanismService.class);
        activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        trigger = new EvolutionDramatizeTrigger(props, worldEventBus, evolutionMechanismService,
                activationService);
        // 默认项目已激活
        when(activationService.isActive(any())).thenReturn(true);
    }

    private ActorEvent frameEvent(Long projectId, String source, Integer importance, Long gameHour,
                                  String sourceKey, Long id) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setProjectId(projectId);
        e.setKind("world_frame");
        e.setSource(source);
        e.setImportance(importance);
        e.setGameHour(gameHour);
        e.setSourceKey(sourceKey);
        e.setTitle("灵脉封印告急");
        e.setContent("枯井深处封印再度松动，异响彻夜不绝。");
        return e;
    }

    @Test
    void registerOnlyWhenEnabled() {
        props.setEnabled(false);
        trigger.register();
        verify(worldEventBus, never()).subscribe(any());
        props.setEnabled(true);
        trigger.register();
        verify(worldEventBus, times(1)).subscribe(any());
    }

    @Test
    void disabledDoesNothing() {
        props.setEnabled(false);
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L));
        verify(evolutionMechanismService, never()).startDramatization(any(), any());
    }

    @Test
    void kindMustBeWorldFrame() {
        ActorEvent e = frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L);
        e.setKind("world");
        trigger.onEvent(e);
        verify(evolutionMechanismService, never()).startDramatization(any(), any());
    }

    @Test
    void evolutionArchiveExcludedToPreventLoop() {
        // 归档事件 kind=world_frame 但 source=evolution（M6 起）→ 不触发（防「演绎的演绎」循环）
        ActorEvent e = frameEvent(1L, "evolution", 5, 100L, "evolution:9", 1L);
        trigger.onEvent(e);
        verify(evolutionMechanismService, never()).startDramatization(any(), any());
    }

    @Test
    void importanceBelowThresholdSkipped() {
        // 扩散到达事件 importance=1 天然低于阈值 4
        ActorEvent e = frameEvent(1L, "engine", 1, 100L, "diffusion:1:4:1:茶馆", 1L);
        trigger.onEvent(e);
        verify(evolutionMechanismService, never()).startDramatization(any(), any());
    }

    @Test
    void inactiveProjectSkipped() {
        when(activationService.isActive(eq(1L))).thenReturn(false);
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L));
        verify(evolutionMechanismService, never()).startDramatization(any(), any());
    }

    @Test
    void eligibleEventCreatesDramatizationAsync() {
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L));
        // 异步虚拟线程创建演化会话（独立每日预算，不依赖 tick 预算）
        verify(evolutionMechanismService, timeout(3000)).startDramatization(eq(1L), any(ActorEvent.class));
    }

    @Test
    void sameSourceKeyDramatizedOnce() {
        ActorEvent e = frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L);
        trigger.onEvent(e);
        trigger.onEvent(e); // 重复发布同事件 → 幂等跳过
        verify(evolutionMechanismService, timeout(3000).times(1)).startDramatization(eq(1L), any(ActorEvent.class));
    }

    @Test
    void dailyLimitCapsPerProjectPerDay() {
        // 同一天两件不同事件 → 仅第一件演绎（maxPerDay=1）
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L));
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-002", 2L));
        verify(evolutionMechanismService, timeout(3000).times(1)).startDramatization(eq(1L), any(ActorEvent.class));
    }

    @Test
    void differentDayAllowsAgain() {
        // 跨游戏日 → 再次允许（限频按游戏日）
        trigger.onEvent(frameEvent(1L, "engine", 5, 100L, "frame:1:4:E-4-001", 1L));
        trigger.onEvent(frameEvent(1L, "engine", 5, 124L, "frame:1:5:E-5-001", 2L));
        verify(evolutionMechanismService, timeout(3000).times(2)).startDramatization(eq(1L), any(ActorEvent.class));
    }
}
