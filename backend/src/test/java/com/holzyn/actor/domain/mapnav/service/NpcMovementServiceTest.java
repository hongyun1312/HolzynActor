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

import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorNpcRoutineRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.algo.NavPath;
import com.holzyn.actor.domain.mapnav.config.MapNavProperties;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 移动引擎的三条硬门：首次落位不「集体从原点出发」、走远路会生成轨迹、按点到达后复位。
 *
 * <p>为什么这三条：它们分别对应三个真会发生的体验事故——世界一重启全场 NPC 从左上角跑出来、
 * 日程切换却原地不动、走到一半永远停在「在途」状态（再也不会动）。这三件事在图上都很隐蔽，
 * 只有对账 DB 才发现。</p>
 */
class NpcMovementServiceTest {

    private static final Long PROJECT = 1L;

    private static final Long MAP = 2L;

    private ActorNpcPathRepository pathRepository;

    private ActorNpcRoutineRepository routineRepository;

    private ActorMapFeatureRepository featureRepository;

    private ActorWorldMapRepository mapRepository;

    private NpcNavigationService navigationService;

    private NpcPositionService positionService;

    private ActorNpcPositionRepository positionRepository;

    private NpcMovementService service;

    /** 存下来的轨迹（模拟 save 自增主键）。 */
    private final List<ActorNpcPath> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pathRepository = mock(ActorNpcPathRepository.class);
        routineRepository = mock(ActorNpcRoutineRepository.class);
        featureRepository = mock(ActorMapFeatureRepository.class);
        mapRepository = mock(ActorWorldMapRepository.class);
        navigationService = mock(NpcNavigationService.class);
        positionRepository = mock(ActorNpcPositionRepository.class);
        positionService = new NpcPositionService(positionRepository);

        ActorWorldMap map = new ActorWorldMap();
        map.setId(MAP);
        when(mapRepository.findFirstByProjectIdOrderByIdDesc(PROJECT)).thenReturn(Optional.of(map));
        when(routineRepository.findByProjectIdAndMapIdOrderByNpcRefAscSeqAsc(PROJECT, MAP))
                .thenReturn(List.of(routine("npc:1", 100, 100)));
        when(featureRepository.findByMapIdOrderBySortOrderAsc(MAP)).thenReturn(List.of());
        when(positionRepository.findByProjectId(PROJECT)).thenAnswer(inv -> new ArrayList<>());
        when(pathRepository.findByProjectIdAndStatusOrderByIdAsc(PROJECT, "walking"))
                .thenAnswer(inv -> new ArrayList<>(saved.stream().filter(p -> "walking".equals(p.getStatus())).toList()));
        when(pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(PROJECT, "npc:1", "walking"))
                .thenAnswer(inv -> saved.stream().filter(p -> "walking".equals(p.getStatus())).toList());
        when(pathRepository.save(any(ActorNpcPath.class))).thenAnswer(inv -> {
            ActorNpcPath p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId((long) (saved.size() + 1));
                saved.add(p);
            }
            return p;
        });
        when(positionRepository.save(any(ActorNpcPosition.class))).thenAnswer(inv -> inv.getArgument(0));
        // 默认：任意两点都给出 100 米的直线路径
        when(navigationService.route(anyLong(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new NavPath(List.of(new double[]{0, 0}, new double[]{100, 100}), 141.4, 0, false));

        service = new NpcMovementService(pathRepository, routineRepository, featureRepository,
                mapRepository, navigationService, positionService, props());
    }

    private static MapNavProperties props() {
        MapNavProperties p = new MapNavProperties();
        p.setWalkSpeed(20.0);
        p.setMinTravelMinutes(1);
        p.setMaxTravelMinutes(45);
        return p;
    }

    private static ActorNpcRoutine routine(String ref, double x, double z) {
        ActorNpcRoutine r = new ActorNpcRoutine();
        r.setProjectId(PROJECT);
        r.setMapId(MAP);
        r.setNpcRef(ref);
        r.setNpcName("沈砚");
        r.setSeq(0);
        r.setStartMinute(0);
        r.setEndMinute(1440);
        r.setActivity("work");
        r.setIndoor(0);
        r.setPosX(x);
        r.setPosZ(z);
        return r;
    }

    @Test
    @DisplayName("首次进世界：直接落位到本段落点，不生成轨迹（不让人从原点走过来）")
    void firstTickPlacesWithoutPath() {
        service.tick(PROJECT, 600);
        ActorNpcPosition pos = positionService.find(PROJECT, "npc:1").orElse(null);
        assertNotNull(pos, "首次 tick 必须建立坐标");
        assertEquals(100.0, pos.getX(), 1e-6);
        assertEquals(100.0, pos.getZ(), 1e-6);
        assertEquals("static", pos.getStatus());
        assertTrue(saved.isEmpty(), "落位不该产生轨迹");
    }

    @Test
    @DisplayName("离落点很远：生成一条在途轨迹，时长 = 长度 / 步速")
    void createsWalkingPath() {
        seedPosition(0, 0);
        service.tick(PROJECT, 600);
        assertEquals(1, saved.size(), "应生成一条轨迹");
        ActorNpcPath path = saved.get(0);
        assertEquals("walking", path.getStatus());
        assertEquals("routine", path.getReason());
        assertEquals(141.4, path.getLengthM(), 0.01);
        long span = path.getArriveGameSecond() - path.getDepartGameSecond();
        // 141.4 / 20 ≈ 7.07 游戏分钟 ≈ 424 游戏秒
        assertEquals(424, span, 2);
        assertEquals("walking", positionService.find(PROJECT, "npc:1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("按游戏秒推进到点：坐标落到终点、状态复位、轨迹转 arrived")
    void advancesAndArrives() {
        seedPosition(0, 0);
        service.tick(PROJECT, 600);
        ActorNpcPath path = saved.get(0);
        long mid = (path.getDepartGameSecond() + path.getArriveGameSecond()) / 2;
        service.tick(PROJECT, mid);
        ActorNpcPosition half = positionService.find(PROJECT, "npc:1").orElseThrow();
        assertTrue(half.getX() > 40 && half.getX() < 60, "中途应在半程附近，实际 " + half.getX());
        assertEquals("walking", half.getStatus());

        service.tick(PROJECT, path.getArriveGameSecond());
        ActorNpcPosition done = positionService.find(PROJECT, "npc:1").orElseThrow();
        assertEquals(100.0, done.getX(), 0.5);
        assertEquals(100.0, done.getZ(), 0.5);
        assertEquals("static", done.getStatus());
        assertEquals("arrived", path.getStatus());
    }

    @Test
    @DisplayName("主动移动打断旧轨迹：旧路径置 aborted，新的从当前位置起步")
    void startMoveAbortsPrevious() {
        seedPosition(5, 5);
        service.tick(PROJECT, 600);
        assertEquals(1, saved.size());
        ActorNpcPath second = service.startMove(PROJECT, "npc:1", 300, 300, "f_x", "decision", 700);
        assertNotNull(second);
        assertEquals("aborted", saved.get(0).getStatus(), "旧轨迹必须置 aborted");
        assertEquals("decision", second.getReason());
    }

    @Test
    @DisplayName("开关关闭时 tick 完全不做事")
    void disabledIsNoop() {
        MapNavProperties off = props();
        off.setEnabled(false);
        NpcMovementService svc = new NpcMovementService(pathRepository, routineRepository,
                featureRepository, mapRepository, navigationService, positionService, off);
        svc.tick(PROJECT, 600);
        assertNull(positionService.find(PROJECT, "npc:1").orElse(null));
    }

    /** 直接在内存里塞一个「已经在 (x,z)」的坐标。 */
    private void seedPosition(double x, double z) {
        positionService.upsert(PROJECT, MAP, "npc:1", "沈砚", x, z, 0, false,
                "work", null, "static", 500);
    }
}
