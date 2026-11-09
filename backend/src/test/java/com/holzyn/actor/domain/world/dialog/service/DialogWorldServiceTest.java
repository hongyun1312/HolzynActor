/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.player.PlayerCharacterService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.dialog.model.DialogRound;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对话回写世界编排测试。
 *
 * <p>守护四件事：① 开关关闭时严格 no-op；② 玩家一轮只发一条 dialog 汇总事件、sourceKey 按
 * 最后消息 ID 幂等；③ 位置/情境/时间成本按冻结态分流；④ 自主群聊按 beat 幂等且不触发
 * 玩家 6 轮抽取。</p>
 */
class DialogWorldServiceTest {

    private WorldLoopProperties properties;
    private WorldEventBus eventBus;
    private PlayerCharacterService playerCharacterService;
    private ActorCharacterRepository characterRepository;
    private DialogPositionSyncService positionSyncService;
    private ConversationTimeCostService timeCostService;
    private DialogSituationBuffer situationBuffer;
    private WorldClockService worldClockService;
    private DialogWorldService service;

    @BeforeEach
    void setUp() {
        properties = new WorldLoopProperties();
        properties.setEnabled(true);
        properties.getDialog().setAnnounceToLocation(true);

        eventBus = mock(WorldEventBus.class);
        playerCharacterService = mock(PlayerCharacterService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        positionSyncService = mock(DialogPositionSyncService.class);
        timeCostService = mock(ConversationTimeCostService.class);
        situationBuffer = mock(DialogSituationBuffer.class);
        worldClockService = mock(WorldClockService.class);

        ActorEvent saved = new ActorEvent();
        saved.setId(77L);
        when(eventBus.publish(anyLong(), anyLong(), any(EventSpec.class))).thenReturn(saved);
        when(worldClockService.currentEngineGameSecond(anyLong())).thenReturn(1000L);
        when(characterRepository.findById(anyLong())).thenReturn(Optional.empty());

        ActorCharacter player = new ActorCharacter();
        player.setId(100L);
        when(playerCharacterService.ensurePlayerCharacter(anyLong(), anyLong())).thenReturn(player);

        service = new DialogWorldService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "worldEventBus", eventBus);
        ReflectionTestUtils.setField(service, "playerCharacterService", playerCharacterService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "positionSyncService", positionSyncService);
        ReflectionTestUtils.setField(service, "timeCostService", timeCostService);
        ReflectionTestUtils.setField(service, "situationBuffer", situationBuffer);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
    }

    @Test
    @DisplayName("总开关关闭：不落事件、不同步位置、不拨时间（严格回基线）")
    void disabledIsStrictNoOp() {
        properties.setEnabled(false);

        service.onPlayerRound(round(false, 55L));

        verifyNoInteractions(eventBus, positionSyncService, timeCostService, situationBuffer);
    }

    @Test
    @DisplayName("玩家一轮：一条 dialog 事件（location 可见）+ 位置同步 + 情境缓冲 + 逐轮拨钟")
    void playerRoundPublishesOneDialogEvent() {
        DialogRound round = round(false, 55L);

        service.onPlayerRound(round);

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus).publish(eq(1L), eq(7L), spec.capture());
        assertEquals("dialog", spec.getValue().getKind());
        assertEquals("dialog", spec.getValue().getSource());
        assertEquals("location", spec.getValue().getVisibility());
        assertEquals("dialog:round:dialogue:9:55", spec.getValue().getSourceKey(), "按最后消息 ID 幂等");
        assertEquals("对话 · 酒馆", spec.getValue().getTitle());
        assertEquals("你好呀", spec.getValue().getContent());
        assertTrue(spec.getValue().getCharacterRefs().contains("npc:2"));
        assertTrue(spec.getValue().getCharacterRefs().contains("npc:100"), "玩家 ref 补进参与者");

        verify(positionSyncService).sync(7L, null, "酒馆", round.getParticipantRefs(), 1000L);
        verify(situationBuffer).remember(eq("conversation:9"), eq("你好呀"), any());
        verify(timeCostService).onPlayerRound(7L);
    }

    @Test
    @DisplayName("冻结态：轮中不拨钟，会话关闭按轮数一次补推")
    void frozenRoundDefersTimeCostToClose() {
        service.onPlayerRound(round(true, 55L));

        verify(timeCostService, never()).onPlayerRound(anyLong());
        verify(worldClockService, org.mockito.Mockito.atLeastOnce()).currentEngineGameSecond(7L);

        service.onSessionClosed(7L, "dialogue", 9L, 1L, true);

        verify(timeCostService).onSessionClosed(7L, 1, "dialogue");
    }

    @Test
    @DisplayName("announce-to-location=false：dialog 事件降为 private")
    void announceOffUsesPrivateVisibility() {
        properties.getDialog().setAnnounceToLocation(false);

        service.onPlayerRound(round(false, 55L));

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus).publish(anyLong(), anyLong(), spec.capture());
        assertEquals("private", spec.getValue().getVisibility());
    }

    @Test
    @DisplayName("自主群聊：按 beat 幂等、location 可见、不拨时间/不进玩家抽取")
    void autonomousRoundUsesBeatAndSkipsTimeCost() {
        DialogRound round = DialogRound.builder()
                .channel("conversation").projectId(7L).sessionId(9L)
                .sessionKey("autonomous:9").locationName("广场")
                .participantRefs(List.of("npc:2"))
                .summary("两人闲聊").roundIndex(3).autonomous(true)
                .gameSecond(3000L).build();

        service.onAutonomousRound(round);

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus).publish(any(), any(), spec.capture());
        assertEquals("dialog:auto:conversation:9:3", spec.getValue().getSourceKey());
        assertEquals("location", spec.getValue().getVisibility());
        verify(timeCostService, never()).onPlayerRound(anyLong());
        verify(positionSyncService, never()).sync(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("构造器默认：hasNewMessage=true（关闭补抽/6 轮抽取不被误判为空转）")
    void builderDefaultsHasNewMessageTrue() {
        assertTrue(DialogRound.builder().build().isHasNewMessage());
    }

    @Test
    @DisplayName("会话关闭补抽：必须带最近一轮参与者（否则 digest 因参与者为空跳过）")
    void closeDigestKeepsLastRoundParticipants() {
        DialogDigestService digestService = mock(DialogDigestService.class);
        ReflectionTestUtils.setField(service, "digestService", digestService);
        when(digestService.digest(any())).thenReturn(true);

        service.onPlayerRound(round(true, 55L));
        service.onSessionClosed(7L, "dialogue", 9L, 1L, true);

        ArgumentCaptor<com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest> req =
                ArgumentCaptor.forClass(com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest.class);
        verify(digestService, org.mockito.Mockito.timeout(3000)).digest(req.capture());
        assertTrue(req.getValue().getParticipantRefs().contains("npc:2"));
        assertTrue(req.getValue().getParticipantRefs().contains("npc:100"));
        assertEquals("dialogue:9", req.getValue().getSessionKey());
        // 关闭补抽必须沿用上一轮 lastMessageId，否则 dialog:{msgId}:op:{i} 会退化成 :0
        assertEquals(55L, req.getValue().getLastMessageId());
    }

    @Test
    @DisplayName("自主群聊剧集收尾：强制抽一次 digest（带参与者，不按玩家 6 轮）")
    void autonomousEpisodeEndForcesDigest() {
        DialogDigestService digestService = mock(DialogDigestService.class);
        ReflectionTestUtils.setField(service, "digestService", digestService);
        when(digestService.digest(any())).thenReturn(true);
        DialogRound round = DialogRound.builder()
                .channel("conversation").projectId(7L).sessionId(9L)
                .sessionKey("autonomous:9").locationName("广场")
                .participantRefs(List.of("npc:2", "npc:3"))
                .summary("剧集收尾").autonomous(true).gameSecond(3000L).build();

        service.onAutonomousEpisodeEnd(round);

        ArgumentCaptor<com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest> req =
                ArgumentCaptor.forClass(com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest.class);
        verify(digestService, org.mockito.Mockito.timeout(3000)).digest(req.capture());
        assertEquals("autonomous:9", req.getValue().getSessionKey());
        assertEquals(2, req.getValue().getParticipantRefs().size());
    }

    @Test
    @DisplayName("开关判定：总开关与 dialog 段同时为真才生效")
    void enabledRequiresBothSwitches() {
        assertTrue(service.enabled());
        properties.getDialog().setEnabled(false);
        assertFalse(service.enabled());
        properties.setEnabled(false);
        assertFalse(service.enabled());
    }

    private DialogRound round(boolean frozen, Long lastMessageId) {
        return DialogRound.builder()
                .channel("dialogue").projectId(7L).userId(1L).sessionId(9L)
                .sessionKey("dialogue:9").locationName("酒馆")
                .participantRefs(List.of("npc:2")).summary("你好呀")
                .lastMessageId(lastMessageId).frozen(frozen).build();
    }
}
