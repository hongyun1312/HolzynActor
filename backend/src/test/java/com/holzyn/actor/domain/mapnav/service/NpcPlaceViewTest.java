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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPositionRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地点读口：权威坐标 → 要素 → 归属住户，以及「空间是否已经过时」的判据。
 *
 * <p>这两件事直接决定「候选动作跟不跟身体走」，所以必须用可复现的单测钉住：
 * 坐标不动时判据为 false（不重算），到达新地点后为 true（重算）。</p>
 */
class NpcPlaceViewTest {

    private static final Long PROJECT_ID = 143L;
    private static final String NPC_REF = "npc:5";

    private ActorNpcPositionRepository positionRepository;
    private ActorNpcPathRepository pathRepository;
    private ActorWorldMapRepository worldMapRepository;
    private ActorMapFeatureRepository featureRepository;
    private ActorHomeMemberRepository homeMemberRepository;
    private NpcPlaceView view;

    @BeforeEach
    void setUp() {
        positionRepository = mock(ActorNpcPositionRepository.class);
        pathRepository = mock(ActorNpcPathRepository.class);
        worldMapRepository = mock(ActorWorldMapRepository.class);
        featureRepository = mock(ActorMapFeatureRepository.class);
        homeMemberRepository = mock(ActorHomeMemberRepository.class);
        view = new NpcPlaceView();
        ReflectionTestUtils.setField(view, "npcPositionRepository", positionRepository);
        ReflectionTestUtils.setField(view, "npcPathRepository", pathRepository);
        ReflectionTestUtils.setField(view, "worldMapRepository", worldMapRepository);
        ReflectionTestUtils.setField(view, "mapFeatureRepository", featureRepository);
        ReflectionTestUtils.setField(view, "homeMemberRepository", homeMemberRepository);
    }

    @Test
    @DisplayName("别人家：hasHome=true、mine=false")
    void atOthersHome() {
        stubPlace("f_home_62", "阿珍家", "民居", "62");
        when(homeMemberRepository.findByHomeIdOrderByIdAsc(62L)).thenReturn(List.of(member("npc:6")));
        NpcPlaceView.Place place = view.at(PROJECT_ID, NPC_REF);
        assertEquals("阿珍家", place.name());
        assertTrue(place.hasHome());
        assertTrue(place.atOthersHome());
        assertFalse(place.mine());
    }

    @Test
    @DisplayName("自己家：住户表命中 mine=true")
    void atOwnHome() {
        stubPlace("f_home_93", "老吴家", "民居", "93");
        when(homeMemberRepository.findByHomeIdOrderByIdAsc(93L)).thenReturn(List.of(member(NPC_REF)));
        NpcPlaceView.Place place = view.at(PROJECT_ID, NPC_REF);
        assertTrue(place.atOwnHome());
        assertTrue(place.mine());
    }

    @Test
    @DisplayName("坐标缺失：返回未知地点而不是 null（调用方不必判空）")
    void unknownWhenNoPosition() {
        when(positionRepository.findByProjectIdAndNpcRef(PROJECT_ID, NPC_REF)).thenReturn(Optional.empty());
        NpcPlaceView.Place place = view.at(PROJECT_ID, NPC_REF);
        assertFalse(place.known());
        assertFalse(place.hasHome());
    }

    @Test
    @DisplayName("到达时刻晚于空间生成时刻：判为位置变化")
    void arrivedAfterSpaceMeansStale() {
        ActorNpcPath path = new ActorNpcPath();
        path.setArriveGameSecond(1200L);
        when(pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(PROJECT_ID, NPC_REF, "arrived"))
                .thenReturn(List.of(path));
        assertTrue(view.arrivedAfter(PROJECT_ID, NPC_REF, 1000L));
    }

    @Test
    @DisplayName("到达时刻早于空间生成时刻：空间仍然对应当前地点")
    void arrivalBeforeSpaceIsNotStale() {
        ActorNpcPath path = new ActorNpcPath();
        path.setArriveGameSecond(800L);
        when(pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(PROJECT_ID, NPC_REF, "arrived"))
                .thenReturn(List.of(path));
        assertFalse(view.arrivedAfter(PROJECT_ID, NPC_REF, 1000L));
    }

    @Test
    @DisplayName("空间生成时刻未知（0）：不做重算，避免全量抖动")
    void zeroSpaceSecondNeverStale() {
        ActorNpcPath path = new ActorNpcPath();
        path.setArriveGameSecond(1200L);
        when(pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(PROJECT_ID, NPC_REF, "arrived"))
                .thenReturn(List.of(path));
        assertFalse(view.arrivedAfter(PROJECT_ID, NPC_REF, 0L));
    }

    //
    // 桩
    //

    private void stubPlace(String featureCode, String name, String category, String ownerHomeRef) {
        ActorNpcPosition pos = new ActorNpcPosition();
        pos.setFeatureCode(featureCode);
        pos.setX(500.0);
        pos.setZ(600.0);
        pos.setIndoor(0);
        when(positionRepository.findByProjectIdAndNpcRef(PROJECT_ID, NPC_REF)).thenReturn(Optional.of(pos));

        ActorWorldMap map = new ActorWorldMap();
        map.setId(7L);
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(PROJECT_ID)).thenReturn(Optional.of(map));

        ActorMapFeature feature = new ActorMapFeature();
        feature.setCode(featureCode);
        feature.setName(name);
        feature.setCategory(category);
        feature.setOwnerHomeRef(ownerHomeRef);
        when(featureRepository.findByMapIdOrderBySortOrderAsc(7L)).thenReturn(List.of(feature));
    }

    private static ActorHomeMember member(String characterRef) {
        ActorHomeMember m = new ActorHomeMember();
        m.setCharacterRef(characterRef);
        return m;
    }
}
