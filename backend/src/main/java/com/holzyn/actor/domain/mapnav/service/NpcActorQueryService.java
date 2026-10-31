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

import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.vo.ActorSnapshotVO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色快照查询（只读）：地图页与 AI 上下文读同一份坐标。
 *
 * <p>「同一份」是这一层的全部意义：{@code /map/actors} 给前端渲染的坐标，
 * 就是决策层注入 prompt 的坐标，也是验收对账要差 ≤0.5 m 的那个数。
 * 如果这里为渲染「美化」一下（插值、取整、加随机抖动），三处就再也对不上了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcActorQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NpcPositionService positionService;

    private final ActorNpcPathRepository pathRepository;

    private final ActorWorldClockRepository clockRepository;

    /**
     * 某项目的全部角色快照。
     *
     * @param projectId 项目
     * @return 快照（按角色引用排序）
     */
    public List<ActorSnapshotVO> actors(Long projectId) {
        List<ActorNpcPosition> positions = positionService.list(projectId);
        Map<String, ActorNpcPath> paths = walkingPaths(projectId);
        List<ActorSnapshotVO> out = new ArrayList<>(positions.size());
        for (ActorNpcPosition p : positions) {
            ActorSnapshotVO vo = new ActorSnapshotVO();
            vo.setRef(p.getNpcRef());
            vo.setName(p.getNpcName());
            vo.setX(p.getX());
            vo.setZ(p.getZ());
            vo.setHeading(p.getHeading());
            vo.setStatus(p.getStatus());
            vo.setActivity(p.getActivity());
            vo.setIndoor(p.getIndoor() != null && p.getIndoor() == 1);
            vo.setFeatureCode(p.getFeatureCode());
            vo.setGameSecond(p.getGameSecond());
            ActorNpcPath path = paths.get(p.getNpcRef());
            if (path != null) {
                vo.setPath(toPathVO(path));
                vo.setStatus("walking");
            }
            out.add(vo);
        }
        return out;
    }

    /**
     * 世界时钟快照（前端据此推进本地时钟；暂停即冻结）。
     *
     * @param projectId 项目
     * @return {gameSecond, paused, rate}
     */
    public Map<String, Object> clock(Long projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        long gs = 0;
        boolean paused = false;
        int rate = 24;
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
            if (clock != null) {
                gs = clock.getLastGameSecond() == null ? 0 : clock.getLastGameSecond();
                paused = clock.getPaused() != null && clock.getPaused() == 1;
                rate = clock.getRate() == null ? 24 : clock.getRate();
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读世界时钟失败：{}", e.getMessage());
        }
        m.put("gameSecond", gs);
        m.put("paused", paused);
        m.put("rate", rate);
        return m;
    }

    /** 在途轨迹（一次查完，别按角色逐个查）。 */
    private Map<String, ActorNpcPath> walkingPaths(Long projectId) {
        Map<String, ActorNpcPath> map = new HashMap<>();
        try {
            for (ActorNpcPath p : pathRepository.findByProjectIdAndStatusOrderByIdAsc(
                    projectId, NpcMovementService.WALKING)) {
                map.put(p.getNpcRef(), p);
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读在途轨迹失败：{}", e.getMessage());
        }
        return map;
    }

    /** 轨迹行 → VO。 */
    private ActorSnapshotVO.PathVO toPathVO(ActorNpcPath path) {
        ActorSnapshotVO.PathVO vo = new ActorSnapshotVO.PathVO();
        vo.setId(path.getId());
        vo.setPoints(parsePoints(path.getPointsJson()));
        vo.setLengthM(path.getLengthM());
        vo.setDepartGameSecond(path.getDepartGameSecond());
        vo.setArriveGameSecond(path.getArriveGameSecond());
        vo.setToRef(path.getToRef());
        return vo;
    }

    /** 折线解析（坏数据给空表，不让整页 500）。 */
    static List<double[]> parsePoints(String json) {
        List<double[]> pts = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return pts;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            for (JsonNode p : arr) {
                pts.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
            }
        } catch (Exception e) {
            log.debug("[mapnav] 轨迹解析失败：{}", e.getMessage());
        }
        return pts;
    }
}
