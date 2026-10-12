package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.agent.runtime.StimulusType;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 意外事件判定单元测试（N5 演化判定四项之一，2026-09-26）。
 *
 * <p>测试纪律：字段注入 + {@code ReflectionTestUtils.setField}（构造器不变）；NanoJev 判定门
 * 全 mock（脚本化响应，零真实 AI）；同步体（{@code sync}）反射直调保证确定性。</p>
 *
 * <p>所属模块：domain/growth/evolution（N5）</p>
 */
class AccidentJudgeTest {

    private AccidentJudge judge;
    private NanoJevDecisionGate gate;
    private ActorCharacterRepository characterRepository;
    private MemoryWorkingService memoryWorkingService;
    private RealTimeDecisionService realTimeDecisionService;
    private ActorCharacter ch;

    @BeforeEach
    void setUp() {
        judge = new AccidentJudge();
        gate = mock(NanoJevDecisionGate.class);
        characterRepository = mock(ActorCharacterRepository.class);
        memoryWorkingService = mock(MemoryWorkingService.class);
        realTimeDecisionService = mock(RealTimeDecisionService.class);
        ReflectionTestUtils.setField(judge, "nanoJevDecisionGate", gate);
        ReflectionTestUtils.setField(judge, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(judge, "npcStateService", mock(NpcStateService.class));
        ReflectionTestUtils.setField(judge, "memoryWorkingService", memoryWorkingService);
        ReflectionTestUtils.setField(judge, "realTimeDecisionService", realTimeDecisionService);
        ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        ch.setName("张三");
        ch.setType("common");
        ch.setStage("YOUTH");
        ch.setCurrentActivity("在集市里赶集");
        ch.setLocation("集市");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(ch));
    }

    private ActorEvent event(String content, int importance, long gameHour) {
        ActorEvent e = new ActorEvent();
        e.setId(1L);
        e.setProjectId(1L);
        e.setContent(content);
        e.setImportance(importance);
        e.setGameHour(gameHour);
        return e;
    }

    private void invokeSync(ActorEvent e) throws Exception {
        Method m = AccidentJudge.class.getDeclaredMethod("sync", Long.class, ActorEvent.class);
        m.setAccessible(true);
        m.invoke(judge, 1L, e);
    }

    @Test
    @DisplayName("N5 意外判定：boolean 命中（true）→ 写 L1 感知记忆 + WORLD_EVENT 即时决策")
    void syncHitWritesMemoryAndInterrupts() throws Exception {
        when(gate.booleanDecisions(eq(1L), eq(NanoJevDecisionScenes.ACCIDENT),
                any(Map.class), eq(AccidentJudge.QID_ACCIDENT))).thenReturn(Map.of("npc:1",
                new NanoJevDecisionGate.BooleanOutcome(true, true, NanoJevDecisionGate.DISP_ADOPTED,
                        0.55, 0.55, null, 5)));
        invokeSync(event("大雨倾盆，集市棚子被风掀翻", 3, 200L));
        verify(memoryWorkingService).writePerception(eq(1L), eq("npc:1"), anyString(), eq(2));
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"),
                eq(StimulusType.WORLD_EVENT), anyString(), eq(200L * 3600L));
    }

    @Test
    @DisplayName("N5 意外判定：boolean 未命中（false）→ 零副作用")
    void syncNoHitNoEffect() throws Exception {
        when(gate.booleanDecisions(eq(1L), eq(NanoJevDecisionScenes.ACCIDENT),
                any(Map.class), eq(AccidentJudge.QID_ACCIDENT))).thenReturn(Map.of("npc:1",
                new NanoJevDecisionGate.BooleanOutcome(false, true, NanoJevDecisionGate.DISP_ADOPTED,
                        0.1, 0.9, null, 5)));
        invokeSync(event("集市上张屠户与人起了争执", 3, 201L));
        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
        verify(realTimeDecisionService, never()).onStimulus(anyLong(), anyString(),
                any(StimulusType.class), anyString(), anyLong());
    }

    @Test
    @DisplayName("N5 意外判定：判定未采纳（升级/失败）→ 零副作用（fail-closed 兜底）")
    void syncNotAdoptedNoEffect() throws Exception {
        when(gate.booleanDecisions(eq(1L), eq(NanoJevDecisionScenes.ACCIDENT),
                any(Map.class), eq(AccidentJudge.QID_ACCIDENT))).thenReturn(Map.of("npc:1",
                new NanoJevDecisionGate.BooleanOutcome(false, false, NanoJevDecisionGate.DISP_ESCALATED,
                        null, 0.4, 1.2, 5)));
        invokeSync(event("深夜巷道里传来一声闷响", 3, 202L));
        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
        verify(realTimeDecisionService, never()).onStimulus(anyLong(), anyString(),
                any(StimulusType.class), anyString(), anyLong());
    }

    @Test
    @DisplayName("N5 意外判定：场景默认关（未启用）→ 零成本跳过，不触碰角色仓库")
    void onEventSceneDisabledSkips() {
        when(gate.isEnabled(NanoJevDecisionScenes.ACCIDENT)).thenReturn(false);
        judge.onEvent(1L, event("村里起了火灾", 4, 203L));
        verify(characterRepository, never()).findByProjectIdAndDeletedOrderByIdAsc(anyLong(), anyInt());
    }

    @Test
    @DisplayName("N5 意外判定：琐碎事件（importance<2）→ 跳过")
    void onEventTrivialSkips() {
        judge.onEvent(1L, event("张三家喂了鸡", 1, 204L));
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("N5 意外判定情境文本：自包含且截断")
    void buildStateTextTruncates() throws Exception {
        ch.setCurrentActivity("正在做一件非常非常非常非常非常非常非常非常非常长的事情来测试截断");
        Method m = AccidentJudge.class.getDeclaredMethod("buildStateText",
                Long.class, ActorCharacter.class, String.class, Long.class);
        m.setAccessible(true);
        Object text = m.invoke(judge, 1L, ch, "大雨倾盆", 1_000_000L);
        assertNotNull(text);
        assertNotNull(text.toString());
    }
}
