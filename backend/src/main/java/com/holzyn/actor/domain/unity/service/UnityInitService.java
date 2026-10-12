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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.sandbox.service.ScenePlacementService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService;
import com.holzyn.actor.domain.sandbox.vo.SandboxOverviewVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxSceneVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxStateVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePlacementVO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity 全量初始化服务（U 轨道 U1 服务端对接层，）——REST GET /init 载荷组装。
 * <p>用途：Unity 客户端连接后拉取世界全量基线用于场景构建——时钟基准（引擎推进位置/速率/
 * 暂停态）、坐标换算基准（1 格=N 米+原点）、场景（含布局 layout_json）、资产摆放（含
 * 资产名/GLB 路径）、全部 NPC 沙盘状态（HA 网格坐标 + 已换算 Unity 世界坐标）、地点清单。</p>
 * <p>消费方：U2 Unity demo 客户端 scene_init 场景构建（《HolzynActor_Unity对接_开发路径 .md》
 * §3.3 契约 v1 scene_init 全量初始化）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnityInitService {

    private final WorldClockService worldClockService;
    private final WorldSandboxStateService sandboxStateService;
    private final ScenePlacementService scenePlacementService;
    private final WorldLocationRepository locationRepository;
    private final CoordinateTransformer coordinateTransformer;

    /**
     * 组装全量初始化载荷。
     *
     * @param projectId 项目 ID
     */
    public Map<String, Object> init(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("protocol", "v1");
        out.put("serverTime", System.currentTimeMillis());

        // ① 时钟基准（引擎推进位置=世界实际时间口径）
        out.put("clock", clockMap(projectId));

        // ② 坐标换算基准（U2 端换算参考）
        Map<String, Object> coord = new LinkedHashMap<>();
        coord.put("meterPerCell", coordinateTransformer.meterPerCell());
        coord.put("originOffsetX", coordinateTransformer.originOffsetX());
        coord.put("originOffsetZ", coordinateTransformer.originOffsetZ());
        coord.put("summary", coordinateTransformer.configSummary());
        out.put("coordinate", coord);

        // ③ 场景 + ④ 摆放（overview 场景列表 + 每场景摆放）
        SandboxOverviewVO overview = sandboxStateService.overview(projectId);
        out.put("scenes", overview.getScenes() == null ? List.of() : overview.getScenes());
        List<Map<String, Object>> placements = new ArrayList<>();
        if (overview.getScenes() != null) {
            for (SandboxSceneVO scene : overview.getScenes()) {
                try {
                    List<ScenePlacementVO> list = scenePlacementService.list(projectId, scene.getId());
                    for (ScenePlacementVO p : list) {
                        placements.add(placementMap(p));
                    }
                } catch (Exception e) {
                    log.warn("[Unity init] 场景摆放读取失败（隔离继续）: scene={} : {}", scene.getId(), e.getMessage());
                }
            }
        }
        out.put("placements", placements);

        // ⑤ NPC 沙盘状态（HA 网格坐标 + Unity 世界坐标）
        List<Map<String, Object>> npcs = new ArrayList<>();
        if (overview.getStates() != null) {
            for (SandboxStateVO s : overview.getStates()) {
                npcs.add(npcMap(s));
            }
        }
        out.put("npcs", npcs);

        // ⑥ 地点清单
        List<Map<String, Object>> locations = new ArrayList<>();
        try {
            for (ActorWorldLocation l : locationRepository.findByProjectId(projectId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.getId());
                m.put("name", l.getName());
                m.put("type", l.getType());
                m.put("importance", l.getImportance());
                locations.add(m);
            }
        } catch (Exception e) {
            log.warn("[Unity init] 地点读取失败（隔离继续）: {}", e.getMessage());
        }
        out.put("locations", locations);

        out.put("counts", Map.of(
                "scenes", overview.getSceneCount(),
                "placements", placements.size(),
                "npcs", npcs.size(),
                "locations", locations.size()));
        return out;
    }

    /** 时钟基准 Map（引擎推进位置口径，同沙盘 clock 载荷）。 */
    private Map<String, Object> clockMap(Long projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            WorldClockVO vo = worldClockService.toVO(clock);
            m.put("rate", vo.getRate());
            m.put("paused", vo.getPaused());
            m.put("worldRunning", vo.getWorldRunning());
            m.put("lastGameSecond", vo.getLastGameSecond());
            m.put("engineGameSecond", vo.getEngineGameSecond());
            m.put("gameDay", vo.getDay());
            m.put("gameHour", vo.getGameHour());
            m.put("gameTimeText", vo.getGameTimeText());
        } catch (Exception e) {
            log.warn("[Unity init] 时钟读取失败: {}", e.getMessage());
        }
        return m;
    }

    /** 摆放 Map（含资产名/GLB 路径供 Unity 加载）。 */
    private Map<String, Object> placementMap(ScenePlacementVO p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("sceneId", p.getSceneId());
        m.put("assetId", p.getAssetId());
        m.put("assetName", p.getAssetName());
        m.put("name", p.getName());
        m.put("category", p.getCategory());
        m.put("filePath", p.getFilePath());
        m.put("pos", List.of(p.getPosX(), p.getPosY(), p.getPosZ()));
        m.put("scale", List.of(p.getScaleX(), p.getScaleY(), p.getScaleZ()));
        m.put("rot", List.of(p.getRotX(), p.getRotY(), p.getRotZ()));
        m.put("sortOrder", p.getSortOrder());
        return m;
    }

    /** NPC 状态 Map（HA 网格坐标 + Unity 世界坐标）。 */
    private Map<String, Object> npcMap(SandboxStateVO s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", s.getNpcRef());
        m.put("name", s.getNpcName());
        m.put("npcType", s.getNpcType());
        m.put("moveState", s.getMoveState());
        m.put("actionText", s.getActionText());
        m.put("statusText", s.getStatusText());
        m.put("thoughtText", s.getThoughtText());
        double px = s.getPosX() == null ? 0d : s.getPosX();
        double py = s.getPosY() == null ? 0d : s.getPosY();
        double pz = s.getPosZ() == null ? 0d : s.getPosZ();
        m.put("pos", List.of(px, py, pz));
        double[] unity = coordinateTransformer.toUnity(new double[]{px, py, pz});
        m.put("unityPos", List.of(unity[0], unity[1], unity[2]));
        m.put("heading", s.getHeading());
        m.put("gameSecond", s.getGameSecond());
        return m;
    }
}
