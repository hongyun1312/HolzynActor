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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事实认知写入口（§4.6，版在场判定用 actor_profile.location）。
 */
class FactKnowledgeServiceImplTest {

    private ActorWorldFactKnowledgeRepository knowledgeRepository;
    private ActorCharacterRepository characterRepository;
    private FactKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeRepository = mock(ActorWorldFactKnowledgeRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        service = new FactKnowledgeServiceImpl(knowledgeRepository, characterRepository);
        when(knowledgeRepository.save(any(ActorWorldFactKnowledge.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(knowledgeRepository.findByProjectIdAndFactIdAndKnowerRef(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("learn 新建：sourceType 缺省 perceive、confidence 钳制 0~100")
    void learnCreatesKnowledge() {
        service.learn(1L, 100L, "npc:3", null, "npc:9", 150, null);

        ArgumentCaptor<ActorWorldFactKnowledge> captor =
                ArgumentCaptor.forClass(ActorWorldFactKnowledge.class);
        verify(knowledgeRepository).save(captor.capture());
        ActorWorldFactKnowledge row = captor.getValue();
        assertEquals(Long.valueOf(1L), row.getProjectId());
        assertEquals(Long.valueOf(100L), row.getFactId());
        assertEquals("npc:3", row.getKnowerRef());
        assertEquals("perceive", row.getSourceType(), "sourceType 缺省 perceive");
        assertEquals(Integer.valueOf(100), row.getConfidence(), "confidence 上限 100");
        assertEquals("npc:9", row.getSourceRef());
    }

    @Test
    @DisplayName("learnAt 记录获知时刻（M4 感知回流按新鲜度排序）")
    void learnAtRecordsGameSecond() {
        service.learnAt(1L, 100L, "npc:3", "perceive", null, 100, null, 500L);

        ArgumentCaptor<ActorWorldFactKnowledge> captor =
                ArgumentCaptor.forClass(ActorWorldFactKnowledge.class);
        verify(knowledgeRepository).save(captor.capture());
        assertEquals(Long.valueOf(500L), captor.getValue().getLearnedGameSecond());
    }

    @Test
    @DisplayName("learn 已存在：置信度取较大值（不降级），来源可更新")
    void learnKeepsHighestConfidence() {
        ActorWorldFactKnowledge existing = new ActorWorldFactKnowledge();
        existing.setProjectId(1L);
        existing.setFactId(100L);
        existing.setKnowerRef("npc:3");
        existing.setSourceType("perceive");
        existing.setConfidence(80);
        when(knowledgeRepository.findByProjectIdAndFactIdAndKnowerRef(1L, 100L, "npc:3"))
                .thenReturn(Optional.of(existing));

        service.learn(1L, 100L, "npc:3", "TOLD", "npc:9", 30, "{\"bias\":1}");

        assertEquals(Integer.valueOf(80), existing.getConfidence(), "低置信来源不得覆盖高置信");
        assertEquals("told", existing.getSourceType(), "sourceType 小写归一化");
        assertEquals("npc:9", existing.getSourceRef());
        assertEquals("{\"bias\":1}", existing.getDistortionJson());
    }

    @Test
    @DisplayName("learn 非法入参：直接返回，不写库")
    void learnIgnoresInvalidInput() {
        service.learn(null, 100L, "npc:3", "own", null, 100, null);
        service.learn(1L, null, "npc:3", "own", null, 100, null);
        service.learn(1L, 100L, " ", "own", null, 100, null);
        verify(knowledgeRepository, never()).save(any(ActorWorldFactKnowledge.class));
    }

    @Test
    @DisplayName("在场广播：同 locationId 或同地点名者获知，排除当事人，异地不获知")
    void broadcastPerceiveLearnsBystandersAtSameLocation() {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(100L);
        fact.setProjectId(1L);
        fact.setLocationId(7L);
        fact.setLocationName("城东");
        fact.setStateText("失火");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(
                character(1L, 7L, "别处"),
                character(2L, null, "城东"),
                character(3L, 7L, "城东"),
                character(4L, 8L, "城西")));

        int learned = service.broadcastPerceive(1L, fact, "城东", "npc:3", 500L);

        assertEquals(2, learned, "只有同地点且非当事人的 2 人获知");
        ArgumentCaptor<ActorWorldFactKnowledge> captor =
                ArgumentCaptor.forClass(ActorWorldFactKnowledge.class);
        verify(knowledgeRepository, times(2)).save(captor.capture());
        assertEquals(List.of("npc:1", "npc:2"),
                captor.getAllValues().stream().map(ActorWorldFactKnowledge::getKnowerRef).toList());
        assertEquals("perceive", captor.getAllValues().get(0).getSourceType());
    }

    @Test
    @DisplayName("在场广播：缺地点名/事实无 ID 时不动角色表")
    void broadcastSkipsWhenLocationOrFactMissing() {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(100L);
        assertEquals(0, service.broadcastPerceive(1L, fact, " ", null, 1L));
        assertEquals(0, service.broadcastPerceive(1L, null, "城东", null, 1L));
        assertEquals(0, service.broadcastPerceive(null, fact, "城东", null, 1L));
        verify(characterRepository, never()).findByProjectIdAndDeletedOrderByIdAsc(anyLong(), any());
    }

    // ==================== 辅助 ====================

    private static ActorCharacter character(Long id, Long locationId, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setLocationId(locationId);
        ch.setLocation(location);
        return ch;
    }
}
