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

import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.service.NpcPositionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话位置同步测试。
 *
 * <p>写坐标必须走 {@link NpcPositionService}；同步保留旧行的 mapId/朝向/室内/活动/特征，
 * 地点解析不到时零写入（绝不把人物丢到 0,0）。</p>
 */
class DialogPositionSyncServiceTest {

    private WorldLocationRepository locationRepository;
    private NpcPositionService positionService;
    private ActorCharacterRepository characterRepository;
    private DialogPositionSyncService service;

    @BeforeEach
    void setUp() {
        locationRepository = mock(WorldLocationRepository.class);
        positionService = mock(NpcPositionService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        service = new DialogPositionSyncService();
        ReflectionTestUtils.setField(service, "locationRepository", locationRepository);
        ReflectionTestUtils.setField(service, "positionService", positionService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
    }

    @Test
    @DisplayName("按地点 ID 解析：写到地点中心，保留旧 mapId/朝向/室内/活动/特征")
    void syncsToLocationCenterAndKeepsOldAttributes() {
        ActorWorldLocation location = location(5L, "酒馆", 10.0, 20.0);
        when(locationRepository.findById(5L)).thenReturn(Optional.of(location));
        ActorNpcPosition old = new ActorNpcPosition();
        old.setMapId(3L);
        old.setHeading(1.5);
        old.setIndoor(1);
        old.setActivity("巡逻");
        old.setFeatureCode("f1");
        when(positionService.find(7L, "npc:2")).thenReturn(Optional.of(old));
        ActorCharacter ch = new ActorCharacter();
        ch.setName("张三");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(ch));

        int n = service.sync(7L, 5L, "酒馆", List.of("npc:2"), 1000L);

        assertEquals(1, n);
        verify(positionService).upsert(7L, 3L, "npc:2", "张三", 10.0, 20.0, 1.5, true,
                "巡逻", "f1", "static", 1000L);
    }

    @Test
    @DisplayName("地点 ID 缺失：按名字精确匹配")
    void resolvesByNameWhenIdMissing() {
        when(locationRepository.findByProjectId(7L))
                .thenReturn(List.of(location(6L, "广场", 1.0, 2.0)));
        when(positionService.find(7L, "npc:2")).thenReturn(Optional.empty());

        int n = service.sync(7L, null, "广场", List.of("npc:2"), 1000L);

        assertEquals(1, n);
        verify(positionService).upsert(eq(7L), eq(0L), eq("npc:2"), any(), eq(1.0), eq(2.0),
                eq(0.0), eq(false), eq("交谈"), any(), eq("static"), eq(1000L));
    }

    @Test
    @DisplayName("地点解析不到：零写入并保留原坐标")
    void unresolvedLocationWritesNothing() {
        when(locationRepository.findByProjectId(7L)).thenReturn(List.of());

        int n = service.sync(7L, null, "不存在的地方", List.of("npc:2"), 1000L);

        assertEquals(0, n);
        verify(positionService, never()).upsert(anyLong(), anyLong(), anyString(), any(), anyDouble(),
                anyDouble(), anyDouble(), anyBoolean(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("地点缺少中心坐标：保留原坐标，零写入（旧世界不许被写成 0.5,0.5）")
    void missingCenterKeepsOriginalPosition() {
        ActorWorldLocation location = location(5L, "酒馆", 0.0, 0.0);
        location.setCenterX(null);
        location.setCenterZ(null);
        when(locationRepository.findById(5L)).thenReturn(Optional.of(location));

        int n = service.sync(7L, 5L, "酒馆", List.of("npc:2"), 1000L);

        assertEquals(0, n);
        verify(positionService, never()).upsert(anyLong(), anyLong(), anyString(), any(), anyDouble(),
                anyDouble(), anyDouble(), anyBoolean(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("单个参与者写失败：跳过该人继续，返回成功人数")
    void continuesWhenOneParticipantFails() {
        when(locationRepository.findById(5L)).thenReturn(Optional.of(location(5L, "酒馆", 10.0, 20.0)));
        when(positionService.find(7L, "npc:2")).thenReturn(Optional.empty());
        when(positionService.find(7L, "npc:3")).thenThrow(new RuntimeException("DB down"));

        int n = service.sync(7L, 5L, "酒馆", List.of("npc:2", "npc:3"), 1000L);

        assertEquals(1, n);
        verify(positionService).upsert(eq(7L), eq(0L), eq("npc:2"), any(), anyDouble(), anyDouble(),
                anyDouble(), anyBoolean(), anyString(), any(), eq("static"), eq(1000L));
    }

    private ActorWorldLocation location(Long id, String name, double x, double z) {
        ActorWorldLocation location = new ActorWorldLocation();
        location.setId(id);
        location.setProjectId(7L);
        location.setName(name);
        location.setCenterX(x);
        location.setCenterZ(z);
        return location;
    }
}
