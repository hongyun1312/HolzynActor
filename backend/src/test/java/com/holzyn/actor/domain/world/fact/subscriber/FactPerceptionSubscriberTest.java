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

package com.holzyn.actor.domain.world.fact.subscriber;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.service.FactKnowledgeService;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.service.InfoDiffusionService;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactPerceptionSubscriberTest {

    private FactPerceptionSubscriber subscriber;
    private WorldFactQuery factQuery;
    private FactKnowledgeService knowledge;
    private ActorCharacterRepository characters;
    private RealTimeDecisionService realtime;
    private InfoDiffusionService diffusion;
    private ActorInfoPacketRepository packets;

    @BeforeEach
    void setUp() {
        subscriber = new FactPerceptionSubscriber(mock(WorldEventBus.class));
        factQuery = mock(WorldFactQuery.class);
        knowledge = mock(FactKnowledgeService.class);
        characters = mock(ActorCharacterRepository.class);
        realtime = mock(RealTimeDecisionService.class);
        diffusion = mock(InfoDiffusionService.class);
        packets = mock(ActorInfoPacketRepository.class);
        ReflectionTestUtils.setField(subscriber, "worldFactQuery", factQuery);
        ReflectionTestUtils.setField(subscriber, "factKnowledgeService", knowledge);
        ReflectionTestUtils.setField(subscriber, "characterRepository", characters);
        ReflectionTestUtils.setField(subscriber, "realTimeDecisionService", realtime);
        ReflectionTestUtils.setField(subscriber, "infoDiffusionService", diffusion);
        ReflectionTestUtils.setField(subscriber, "infoPacketRepository", packets);
        WorldLoopProperties properties = new WorldLoopProperties();
        properties.setEnabled(true);
        ReflectionTestUtils.setField(subscriber, "properties", properties);
        when(factQuery.knows(anyLong(), anyString(), anyString())).thenReturn(false);
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("private 事实只给 actorRef + parties，不触发同地点刺激，并生成传闻包")
    void privateFactOnlyPartiesAndRumor() {
        ActorWorldFact fact = fact("contract:1", "private", "酒馆");
        fact.setAttributesJson("{\"parties\":[\"npc:2\",\"npc:3\"]}");
        when(factQuery.byKey(7L, "contract:1")).thenReturn(Optional.of(fact));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(7L, 0)).thenReturn(List.of(
                ch(2L, "酒馆"), ch(3L, "酒馆"), ch(4L, "酒馆")));

        subscriber.onEvent(factEvent("contract:1", "private", "npc:2", 36000L));

        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:2"), eq("own"), eq(null), eq(100),
                eq(null), eq(36000L));
        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:3"), eq("told"), eq("npc:2"), eq(100),
                eq(null), eq(36000L));
        verify(knowledge, never()).learnAt(anyLong(), anyLong(), eq("npc:4"), anyString(), any(),
                anyInt(), any(), anyLong());
        verify(realtime, never()).onFactChanged(anyLong(), any(), anyString(), anyLong());
        verify(diffusion).publishPacket(eq(7L), eq(9L), anyString(), eq("npc:2"), eq("private"),
                eq(0.8d), eq("酒馆"), eq("contract:1"), eq(36000L));
    }

    @Test
    @DisplayName("location 事实给同地点在场者 perceive，只刺激其他 NPC")
    void locationFactWitnesses() {
        ActorWorldFact fact = fact("facility:mill", "location", "磨坊");
        when(factQuery.byKey(7L, "facility:mill")).thenReturn(Optional.of(fact));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(7L, 0)).thenReturn(List.of(
                ch(2L, "磨坊"), ch(3L, "磨坊"), ch(4L, "集市")));

        subscriber.onEvent(factEvent("facility:mill", "location", "npc:2", 72000L));

        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:3"), eq("perceive"), eq(null), eq(100),
                eq(null), eq(72000L));
        verify(knowledge, never()).learnAt(anyLong(), anyLong(), eq("npc:4"), anyString(), any(),
                anyInt(), any(), anyLong());
        ArgumentCaptor<List<String>> refs = ArgumentCaptor.forClass(List.class);
        verify(realtime).onFactChanged(eq(7L), refs.capture(), anyString(), eq(72000L));
        assertEquals(List.of("npc:3"), refs.getValue());
        verify(diffusion, never()).publishPacket(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("public 事实写全项目 knowledge，但只刺激同地点其他 NPC")
    void publicFactAllKnowButLocalStimulus() {
        ActorWorldFact fact = fact("law:1", "public", "广场");
        when(factQuery.byKey(7L, "law:1")).thenReturn(Optional.of(fact));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(7L, 0)).thenReturn(List.of(
                ch(2L, "广场"), ch(3L, "广场"), ch(4L, "集市")));

        subscriber.onEvent(factEvent("law:1", "public", "npc:2", 108000L));

        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:3"), eq("public"), eq(null), eq(100),
                eq(null), eq(108000L));
        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:4"), eq("public"), eq(null), eq(100),
                eq(null), eq(108000L));
        ArgumentCaptor<List<String>> refs = ArgumentCaptor.forClass(List.class);
        verify(realtime).onFactChanged(eq(7L), refs.capture(), anyString(), eq(108000L));
        assertEquals(List.of("npc:3"), refs.getValue());
    }

    @Test
    @DisplayName("传闻到达事件给同区域 NPC 写 rumor knowledge（置信度随失真等级下降）")
    void rumorArrivalWritesRumorKnowledge() {
        ActorInfoPacket packet = new ActorInfoPacket();
        packet.setId(5L);
        packet.setFactKey("contract:1");
        when(packets.findById(5L)).thenReturn(Optional.of(packet));
        ActorWorldFact fact = fact("contract:1", "private", "码头");
        when(factQuery.byKey(7L, "contract:1")).thenReturn(Optional.of(fact));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(7L, 0)).thenReturn(List.of(
                ch(2L, "河湾"), ch(3L, "集市")));

        ActorEvent event = new ActorEvent();
        event.setId(12L);
        event.setProjectId(7L);
        event.setKind("world_frame");
        event.setSourceKey("diffusion:7:3:5:河湾");
        event.setLocationName("河湾");
        event.setGameHour(10L);
        event.setPayloadJson("{\"packetId\":5,\"region\":\"河湾\",\"distortionLevel\":2}");

        subscriber.onEvent(event);

        ArgumentCaptor<String> distortion = ArgumentCaptor.forClass(String.class);
        verify(knowledge).learnAt(eq(7L), eq(1L), eq("npc:2"), eq("rumor"), eq("diffusion:5"),
                eq(50), distortion.capture(), eq(36000L));
        assertTrue(distortion.getValue().contains("\"level\":2"));
        verify(knowledge, never()).learnAt(anyLong(), anyLong(), eq("npc:3"), anyString(), any(),
                anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("expire 事件不写 knowledge，也不生成传闻")
    void expireSkipped() {
        subscriber.onEvent(factEvent("contract:1", "private", "npc:2", 100L, "expire"));
        verify(knowledge, never()).learnAt(anyLong(), anyLong(), anyString(), anyString(), any(),
                anyInt(), any(), anyLong());
        verify(diffusion, never()).publishPacket(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("开关关闭时订阅者不处理")
    void disabledNoop() {
        WorldLoopProperties off = new WorldLoopProperties();
        off.setEnabled(false);
        ReflectionTestUtils.setField(subscriber, "properties", off);
        subscriber.onEvent(factEvent("contract:1", "private", "npc:2", 100L));
        verify(factQuery, never()).byKey(anyLong(), anyString());
    }

    private static ActorEvent factEvent(String factKey, String visibility, String actorRef,
                                        long gameSecond) {
        return factEvent(factKey, visibility, actorRef, gameSecond, "create");
    }

    private static ActorEvent factEvent(String factKey, String visibility, String actorRef,
                                        long gameSecond, String op) {
        ActorEvent event = new ActorEvent();
        event.setId(9L);
        event.setProjectId(7L);
        event.setKind("fact");
        event.setSource("fact");
        event.setGameHour(gameSecond / 3600L);
        event.setCharacterIds(actorRef);
        event.setPayloadJson("{\"factKey\":\"" + factKey + "\",\"op\":\"" + op
                + "\",\"visibility\":\"" + visibility + "\",\"actorRef\":\"" + actorRef + "\"}");
        return event;
    }

    private static ActorWorldFact fact(String key, String visibility, String location) {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(1L);
        fact.setProjectId(7L);
        fact.setFactKey(key);
        fact.setFactType("contract".equals(key.substring(0, key.indexOf(':'))) ? "contract" : "facility");
        fact.setName("测试事实");
        fact.setStateText("状态变了");
        fact.setVisibility(visibility);
        fact.setStatus("active");
        fact.setLocationName(location);
        return fact;
    }

    private static ActorCharacter ch(Long id, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(7L);
        ch.setName("角色" + id);
        ch.setLocation(location);
        ch.setDeleted(0);
        return ch;
    }
}
