package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈冻结服务单元测试（零 AI）。
 * <p>职责：验证——运行中自动暂停+快照（冻结游戏秒/角色状态/在场）/已静止只快照不暂停/
 * 暂停失败 fail-open 不阻断/时钟缺失降级（gameSecond null 仍返回快照）。</p>
 */
class DialogueFreezeServiceTest {

    private WorldClockService worldClockService;
    private AiContextInjector contextInjector;
    private ActorCharacterRepository characterRepository;
    private NpcStateService npcStateService;
    private DialogueFreezeService service;

    @BeforeEach
    void setUp() {
        worldClockService = mock(WorldClockService.class);
        contextInjector = mock(AiContextInjector.class);
        characterRepository = mock(ActorCharacterRepository.class);
        npcStateService = mock(NpcStateService.class);
        service = new DialogueFreezeService();
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(service, "contextInjector", contextInjector);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
    }

    private WorldClockVO runningClock() {
        WorldClockVO v = new WorldClockVO();
        v.setPaused(0);
        v.setWorldRunning(1);
        return v;
    }

    private void stubWorld(Long gameSecond) {
        when(worldClockService.getClock(1L, 1L)).thenReturn(runningClock());
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(gameSecond);
        when(contextInjector.worldDynamicsLine(1L)).thenReturn("青禾村历 0420-05-15 06时00分 · 初夏季 · 天气晴");
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈守拙");
        ch.setProjectId(1L);
        ch.setLocation("村东水田");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        ActorCharacter other = new ActorCharacter();
        other.setId(2L);
        other.setProjectId(1L);
        other.setLocation("村东水田");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(ch, other));
        ActorNpcState state = new ActorNpcState();
        state.setDominantEmotion("平静");
        when(npcStateService.stateFor(1L, "npc:1")).thenReturn(state);
    }

    @Test
    void freezeRunningWorldAutoPausesAndSnapshots() {
        stubWorld(13044175200L);
        DialogueFreezeService.FreezeSnapshot snap = service.freeze(1L, 1L, "npc:1");
        assertTrue(snap.wasRunning());
        assertEquals(13044175200L, snap.gameSecond());
        assertTrue(snap.worldDynamics().contains("青禾村历"));
        assertEquals("沈守拙", snap.characterName());
        assertEquals("村东水田", snap.characterLocation());
        assertEquals("平静", snap.characterMood());
        assertEquals(List.of("npc:2"), snap.presentRefs());
        // 自动暂停：updateClock paused=true
        verify(worldClockService).updateClock(eq(1L), eq(1L), any(WorldClockDTO.class));
    }

    @Test
    void freezeAlreadyPausedOnlySnapshots() {
        WorldClockVO v = runningClock();
        v.setPaused(1); // 已暂停
        when(worldClockService.getClock(1L, 1L)).thenReturn(v);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(13044175200L);
        DialogueFreezeService.FreezeSnapshot snap = service.freeze(1L, 1L, "npc:1");
        assertTrue(!snap.wasRunning());
        assertEquals(13044175200L, snap.gameSecond());
        verify(worldClockService, never()).updateClock(anyLong(), anyLong(), any());
    }

    @Test
    void freezePauseFailureFailOpen() {
        stubWorld(13044175200L);
        when(worldClockService.updateClock(eq(1L), eq(1L), any(WorldClockDTO.class)))
                .thenThrow(new RuntimeException("暂停失败"));
        DialogueFreezeService.FreezeSnapshot snap = service.freeze(1L, 1L, "npc:1");
        // fail-open：不阻断，快照仍返回
        assertEquals(13044175200L, snap.gameSecond());
        assertEquals("沈守拙", snap.characterName());
    }

    @Test
    void freezeClockMissingDegrades() {
        ReflectionTestUtils.setField(service, "worldClockService", null);
        ReflectionTestUtils.setField(service, "contextInjector", null);
        ReflectionTestUtils.setField(service, "npcStateService", null);
        DialogueFreezeService.FreezeSnapshot snap = service.freeze(1L, 1L, "npc:1");
        assertNull(snap.gameSecond());
        assertEquals("", snap.worldDynamics());
        assertNull(snap.characterMood());
    }
}
