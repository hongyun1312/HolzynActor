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

package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import com.holzyn.actor.domain.worldmap.vo.WorldMapLocationVO;
import com.holzyn.actor.domain.worldmap.vo.WorldMapVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界地图只读查询服务（位置移动与地图生成系统 · M1~M3）——新首页 2D 地图 / 图谱 /
 * 感知空间段的数据门面。
 * <p>数据源：actor_sandbox_scene.layout_json（regions/roads/placements）+ actor_npc_home/
 * actor_home_member（住所与住户）+ actor_world_location（地点坐标区）。纯只读零写零 AI。</p>
 * <p><b>M3 感知联动扩展（只读门面）</b>：区域反查 {@link #regionAt}（坐标→区域口语描述）、
 * 八方位 {@link #bearing}（东/南/西/北/东南…）、路程口语化 {@link #distanceText}（步/里）、
 * 距离分级 {@link #distanceGrade}（近/中/远）、附近角色 {@link #nearbyRefs}（途中可见用）。
 * 坐标域对齐：沙盘中心化坐标（posX/posZ）↔ 地图坐标 [0, scene_size) 换算见
 * {@link MovementService#sandboxToMap}/{@link MovementService#mapToSandbox}（同包包级可见）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldMapQueryService {

    private final ActorSandboxSceneRepository sceneRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorNpcHomeRepository homeRepository;
    private final ActorHomeMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    private final ObjectMapper objectMapper;

    /** 场景边长缺省 */
    private static final int DEFAULT_SCENE_SIZE = 500;

    /**
     * 沙盘状态仓库（M3 附近角色查询——途中可见/途中遭遇用；字段注入保持构造器不变，
     * 存量单测零改动；null=未装配跳过附近角色查询）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorSandboxStateRepository sandboxStateRepository;

    /**
     * 沙盘状态服务（M3 哈希派生兜底——坐标反查/附近角色查询无沙盘行时派生兜底；
     * 字段注入保持构造器不变，null=未装配跳过兜底）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /** 场景边长（无场景/非法用默认 500）。 */
    private int sceneSizeOf(Long projectId) {
        try {
            ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
            return scene == null || scene.getSceneSize() == null || scene.getSceneSize() <= 0
                    ? DEFAULT_SCENE_SIZE : scene.getSceneSize();
        } catch (Exception e) {
            return DEFAULT_SCENE_SIZE;
        }
    }

    /**
     * 地点名反查地点 ID（M3 决策情境路程远近：候选 target=地点名 → locationId；精确匹配，
     * 无命中返回 null；失败返回 null 零阻断）。
     *
     * @param projectId 项目 ID
     *
     * @param locationName 地点名（精确匹配）
     *
     * @return 地点 ID；无命中/异常返回 null
     */
    public Long locationIdByName(Long projectId, String locationName) {
        if (projectId == null || locationName == null || locationName.isBlank()) {
            return null;
        }
        try {
            String target = locationName.trim();
            for (ActorWorldLocation loc : locationRepository.findByProjectId(projectId)) {
                if (loc != null && loc.getName() != null && target.equalsIgnoreCase(loc.getName().trim())) {
                    return loc.getId();
                }
            }
        } catch (Exception e) {
            log.warn("[世界地图] 地点名反查失败（跳过）: {} : {}", locationName, e.getMessage());
        }
        return null;
    }

    /**
     * 目标路程远近（M3 §4.5 ③，决策情境路程远近）：角色当前沙盘坐标 → 目标地点（区域中心，
     * 地图坐标转中心化比较）→ 距离分级「近(≤50m)/中(≤200m)/远(&gt;200m)」+ 口语化路程。
     * <p>数据源：actor_world_location 区域中心；地点无区域中心/仓库未装配 → 返回 null
     * （调用方不附加距离标注）。坐标统一中心化域比较。</p>
     *
     * @param projectId 项目 ID
     *
     * @param sandboxX 角色当前沙盘坐标 X
     *
     * @param sandboxZ 角色当前沙盘坐标 Z
     *
     * @param targetLocationId 目标地点 ID（可空=无法按地点判定距离）
     *
     * @return 距离描述（如「近，约 30 步」「远，约三里」）；无法判定返回 null
     */
    public String distanceToTarget(Long projectId, double sandboxX, double sandboxZ,
                                   Long targetLocationId) {
        if (projectId == null || targetLocationId == null) {
            return null;
        }
        try {
            ActorWorldLocation loc = locationRepository.findById(targetLocationId).orElse(null);
            if (loc == null || loc.getCenterX() == null || loc.getCenterZ() == null) {
                return null;
            }
            int size = sceneSizeOf(projectId);
            double locSandboxX = loc.getCenterX() - size / 2.0;
            double locSandboxZ = loc.getCenterZ() - size / 2.0;
            double meters = Math.hypot(locSandboxX - sandboxX, locSandboxZ - sandboxZ);
            return distanceGrade(meters) + "，" + distanceText(meters);
        } catch (Exception e) {
            log.warn("[世界地图] 目标路程远近计算失败（跳过）: loc={} : {}", targetLocationId, e.getMessage());
            return null;
        }
    }

    // ==================== M3 感知联动：只读门面纯函数 ====================

    /**
     * 区域反查（M3）：沙盘中心化坐标 → 命中区域（地图坐标 [0, scene_size) 域比较）。
     * <p>坐标换算：沙盘中心化 + size/2 → 地图坐标；遍历该项目全部地点区域，
     * 命中条件 = 坐标到区域中心直线距离 ≤ 区域半径（半径缺省 12）。返回区域口语描述
     * （regionDesc 优先，无则地点名）。无命中返回 null（感知回退「在{地点名}」）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param sandboxX 沙盘中心化坐标 X
     *
     * @param sandboxZ 沙盘中心化坐标 Z
     *
     * @return 区域口语描述（如「村西口」「河畔渡口」）；无命中返回 null
     */
    public String regionAt(Long projectId, double sandboxX, double sandboxZ) {
        if (projectId == null) {
            return null;
        }
        int size = sceneSizeOf(projectId);
        double mapX = sandboxX + size / 2.0;
        double mapZ = sandboxZ + size / 2.0;
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (ActorWorldLocation loc : locationRepository.findByProjectId(projectId)) {
            if (loc == null || loc.getCenterX() == null || loc.getCenterZ() == null) {
                continue;
            }
            double d = Math.hypot(mapX - loc.getCenterX(), mapZ - loc.getCenterZ());
            double r = loc.getRadius() == null ? 12.0 : loc.getRadius();
            if (d <= r && d < bestDist) {
                bestDist = d;
                best = loc.getRegionDesc() != null && !loc.getRegionDesc().isBlank()
                        ? loc.getRegionDesc().trim() : loc.getName();
            }
        }
        return best;
    }

    /**
     * 八方位（M3，纯静态）：从 (fromX, fromZ) 指向 (toX, toZ) 的方位中文（东/东南/南/西南/西/西北/北/东北）。
     *
     * @param fromX 起点 X
     *
     * @param fromZ 起点 Z
     *
     * @param toX 终点 X
     *
     * @param toZ 终点 Z
     *
     * @return 方位中文（同点/极近回退「前方」）
     */
    public static String bearing(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.hypot(dx, dz) < 1e-6) {
            return "前方";
        }
        double deg = Math.toDegrees(Math.atan2(dz, dx));
        // 坐标系：x 向右=东，z 向下=南（atan2(dz,dx) 以 x 轴正方向为 0°，正角=朝 z 正方向=南）
        if (deg < 0) {
            deg += 360;
        }
        // 8 个扇区，每 45°：东(0°)→东南(45°)→南(90°)→西南(135°)→西(180°)→西北(225°)→北(270°)→东北(315°)
        String[] dirs = {"东", "东南", "南", "西南", "西", "西北", "北", "东北"};
        int idx = (int) Math.round(deg / 45.0) % 8;
        return dirs[idx];
    }

    /**
     * 路程口语化（M3，纯静态）：米 → 步/里（距离 ÷ 0.6m 取整为步，≥100 步进里）。
     * <p>对齐感知纪律「不给精确坐标，只给方位/路程口语」——感知/对话素材用。</p>
     *
     * @param meters 距离（米；&lt;=0 回退「几步路」）
     *
     * @return 口语化路程（「约 30 步」「约百步」「约三里」）
     */
    public static String distanceText(double meters) {
        if (meters <= 0) {
            return "几步路";
        }
        long steps = Math.round(meters / 0.6);
        if (steps <= 0) {
            return "几步路";
        }
        if (steps >= 500) {
            long li = Math.round(steps / 1000.0);
            return "约" + li + "里";
        }
        if (steps >= 100) {
            // 百余步/二百步/三百余步
            long hundred = steps / 100;
            long rem = steps % 100;
            if (hundred == 1 && rem == 0) {
                return "约百步";
            }
            return (rem >= 50 ? "约" + (hundred + 1) : "约" + hundred) + "百步";
        }
        return "约" + steps + "步";
    }

    /**
     * 距离分级（M3，纯静态）：近(≤50m)/中(≤200m)/远(&gt;200m)——决策情境路程远近（§4.5 ③）。
     *
     * @param meters 距离（米）
     *
     * @return 分级中文（近/中/远）
     */
    public static String distanceGrade(double meters) {
        if (meters <= 50) {
            return "近";
        }
        if (meters <= 200) {
            return "中";
        }
        return "远";
    }

    /**
     * 附近角色（M3 途中可见/途中遭遇）：以沙盘中心化坐标为中心，返回距离 ≤ 阈值（格）的
     * 其他角色 ref 列表（排除 excludeRef）。
     * <p>数据源：沙盘状态行（posX/posZ 权威坐标，含移动插值中角色）；无沙盘行/仓库未装配
     * → 空列表（fail-open，感知不因途中可见失败而阻断）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param sandboxX 中心 X（沙盘中心化）
     *
     * @param sandboxZ 中心 Z
     *
     * @param excludeRef 排除的角色 ref（自身，可空）
     *
     * @param radiusGrids 半径（格；&lt;=0 用默认 30 格=6 米）
     *
     * @return 附近角色 ref 列表（按距离近→远）
     */
    public List<String> nearbyRefs(Long projectId, double sandboxX, double sandboxZ,
                                   String excludeRef, double radiusGrids) {
        if (projectId == null || sandboxStateRepository == null) {
            return List.of();
        }
        double r = radiusGrids > 0 ? radiusGrids : 30.0;
        List<String> out = new ArrayList<>();
        try {
            for (ActorSandboxState st : sandboxStateRepository.findByProjectIdOrderByIdAsc(projectId)) {
                if (st == null || st.getNpcRef() == null || st.getNpcRef().isBlank()) {
                    continue;
                }
                if (excludeRef != null && excludeRef.equals(st.getNpcRef())) {
                    continue;
                }
                if (st.getPosX() == null || st.getPosZ() == null) {
                    continue;
                }
                if (Math.hypot(st.getPosX() - sandboxX, st.getPosZ() - sandboxZ) <= r) {
                    out.add(st.getNpcRef());
                }
            }
            out.sort((a, b) -> Double.compare(dist(projectId, sandboxX, sandboxZ, a),
                    dist(projectId, sandboxX, sandboxZ, b)));
        } catch (Exception e) {
            log.warn("[世界地图] 附近角色查询失败（返回空）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
        return out;
    }

    /** 沙盘中心到某角色坐标的距离（附近角色排序用；无行/异常回退 Max）。 */
    private double dist(Long projectId, double x, double z, String npcRef) {
        try {
            if (sandboxStateRepository == null) {
                return Double.MAX_VALUE;
            }
            ActorSandboxState st = sandboxStateRepository
                    .findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            if (st == null || st.getPosX() == null || st.getPosZ() == null) {
                return Double.MAX_VALUE;
            }
            return Math.hypot(st.getPosX() - x, st.getPosZ() - z);
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }


    /**
     * 项目世界地图概览（GET /world-map）。
     *
     * @param projectId 项目 ID
     *
     * @return 地图 VO（无地图时返回空 regions/homes，不抛错）
     */
    public WorldMapVO worldMap(Long projectId) {
        WorldMapVO vo = new WorldMapVO();
        vo.setProjectId(projectId);
        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
        if (scene == null) {
            return vo;
        }
        vo.setSceneId(scene.getId());
        vo.setSceneSize(scene.getSceneSize() == null ? DEFAULT_SCENE_SIZE : scene.getSceneSize());
        vo.setStyle(scene.getStyleCode());
        vo.setSource(scene.getSource());
        fillFromLayout(vo, scene.getLayoutJson());
        fillHomes(vo, projectId);
        return vo;
    }

    /**
     * 单地点区域详情（GET /world-map/location/{locationId}）：区域 + 摆放 + 住所 + 在场角色。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID
     *
     * @return 地点地图详情
     */
    public WorldMapLocationVO locationDetail(Long projectId, Long locationId) {
        ActorWorldLocation loc = locationRepository.findById(locationId)
                .filter(l -> projectId.equals(l.getProjectId()))
                .orElseThrow(() -> new BizException(404, "地点不存在或不属于该项目"));
        WorldMapLocationVO vo = new WorldMapLocationVO();
        vo.setLocationId(loc.getId());
        vo.setName(loc.getName());
        vo.setType(loc.getType());
        vo.setIntro(loc.getIntro());
        vo.setCenterX(loc.getCenterX());
        vo.setCenterZ(loc.getCenterZ());
        vo.setRadius(loc.getRadius());
        vo.setRegionDesc(loc.getRegionDesc());

        ActorSandboxScene scene = sceneRepository.findFirstByProjectIdOrderByIdAsc(projectId).orElse(null);
        if (scene != null) {
            try {
                JsonNode root = objectMapper.readTree(scene.getLayoutJson());
                JsonNode regions = root.path("regions");
                for (JsonNode r : regions) {
                    if (r.path("locationId").asLong(-1) == locationId) {
                        vo.setShape(r.path("shape").asText("circle"));
                        break;
                    }
                }
                for (JsonNode p : root.path("placements")) {
                    if (p.path("locationId").asLong(-1) == locationId) {
                        WorldMapVO.PlacementVO pv = new WorldMapVO.PlacementVO();
                        pv.setId(p.path("id").asLong(0));
                        pv.setName(p.path("name").asText(null));
                        pv.setCategory(p.path("category").asText(null));
                        pv.setAssetId(p.path("assetId").asText(null));
                        pv.setPosX(p.path("posX").asDouble());
                        pv.setPosZ(p.path("posZ").asDouble());
                        pv.setRotY(p.path("rotY").asDouble());
                        pv.setLocationId(locationId);
                        vo.getPlacements().add(pv);
                    }
                }
            } catch (Exception e) {
                log.warn("[世界地图] 地点详情 layout 解析失败（忽略）: {}", e.getMessage());
            }
        }
        // 住所（在该地点）
        Map<Long, ActorNpcHome> homes = new LinkedHashMap<>();
        for (ActorNpcHome h : homeRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (locationId.equals(h.getLocationId())) {
                homes.put(h.getId(), h);
            }
        }
        for (Map.Entry<Long, ActorNpcHome> e : homes.entrySet()) {
            vo.getHomes().add(toHomeVO(e.getValue(), projectId));
        }
        // 在场角色（按 actor_profile.location_id）
        for (ActorCharacter ch : characterRepository
                .findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(projectId, locationId, 0)) {
            vo.getCharacters().add(ch.getName());
        }
        return vo;
    }

    /**
     * 单角色住所（GET /npc/{ref}/home）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（npc:{id}）
     *
     * @return 住所 VO（无住所返回 null）
     */
    public WorldMapVO.HomeVO npcHome(Long projectId, String characterRef) {
        ActorHomeMember member = memberRepository.findByProjectIdAndCharacterRef(projectId, characterRef)
                .orElse(null);
        if (member == null) {
            return null;
        }
        ActorNpcHome home = homeRepository.findById(member.getHomeId()).orElse(null);
        if (home == null) {
            return null;
        }
        return toHomeVO(home, projectId);
    }

    // ==================== 内部 ====================

    private void fillFromLayout(WorldMapVO vo, String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(layoutJson);
            for (JsonNode r : root.path("regions")) {
                WorldMapVO.RegionVO rv = new WorldMapVO.RegionVO();
                rv.setLocationId(r.path("locationId").asLong(0));
                rv.setName(r.path("name").asText(null));
                rv.setCenterX(r.path("centerX").asDouble());
                rv.setCenterZ(r.path("centerZ").asDouble());
                rv.setRadius(r.path("radius").asDouble());
                rv.setShape(r.path("shape").asText("circle"));
                rv.setRegionDesc(r.path("regionDesc").asText(null));
                vo.getRegions().add(rv);
            }
            for (JsonNode rd : root.path("roads")) {
                WorldMapVO.RoadVO rv = new WorldMapVO.RoadVO();
                rv.setName(rd.path("name").asText(null));
                rv.setWidth(rd.path("width").asDouble(3.0));
                List<double[]> path = new ArrayList<>();
                for (JsonNode pt : rd.path("path")) {
                    if (pt.isArray() && pt.size() >= 2) {
                        path.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                    }
                }
                rv.setPath(path);
                vo.getRoads().add(rv);
            }
            for (JsonNode p : root.path("placements")) {
                WorldMapVO.PlacementVO pv = new WorldMapVO.PlacementVO();
                pv.setId(p.path("id").asLong(0));
                pv.setName(p.path("name").asText(null));
                pv.setCategory(p.path("category").asText(null));
                pv.setAssetId(p.path("assetId").asText(null));
                pv.setPosX(p.path("posX").asDouble());
                pv.setPosZ(p.path("posZ").asDouble());
                pv.setRotY(p.path("rotY").asDouble());
                pv.setLocationId(p.path("locationId").asLong(0));
                vo.getPlacements().add(pv);
            }
        } catch (Exception e) {
            log.warn("[世界地图] layout 解析失败（返回空地图）: {}", e.getMessage());
        }
    }

    private void fillHomes(WorldMapVO vo, Long projectId) {
        Map<Long, ActorWorldLocation> locations = new LinkedHashMap<>();
        for (ActorWorldLocation loc : locationRepository.findByProjectId(projectId)) {
            locations.put(loc.getId(), loc);
        }
        for (ActorNpcHome h : homeRepository.findByProjectIdOrderByIdAsc(projectId)) {
            WorldMapVO.HomeVO hv = toHomeVO(h, projectId, locations);
            vo.getHomes().add(hv);
        }
    }

    private WorldMapVO.HomeVO toHomeVO(ActorNpcHome h, Long projectId) {
        Map<Long, ActorWorldLocation> locations = new LinkedHashMap<>();
        for (ActorWorldLocation loc : locationRepository.findByProjectId(projectId)) {
            locations.put(loc.getId(), loc);
        }
        return toHomeVO(h, projectId, locations);
    }

    private WorldMapVO.HomeVO toHomeVO(ActorNpcHome h, Long projectId, Map<Long, ActorWorldLocation> locations) {
        WorldMapVO.HomeVO hv = new WorldMapVO.HomeVO();
        hv.setId(h.getId());
        hv.setName(h.getName());
        hv.setLocationId(h.getLocationId());
        ActorWorldLocation loc = locations.get(h.getLocationId());
        hv.setLocationName(loc == null ? null : loc.getName());
        hv.setPosX(h.getPosX() == null ? 0d : h.getPosX());
        hv.setPosZ(h.getPosZ() == null ? 0d : h.getPosZ());
        hv.setRoomLabel(h.getRoomLabel());
        hv.setRegion(h.getRegion());
        hv.setNote(h.getNote());
        hv.setStatus(h.getStatus());
        hv.setDecorationLevel(h.getDecorationLevel());
        Map<Long, ActorCharacter> chars = new LinkedHashMap<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdOrderByIdAsc(projectId)) {
            chars.put(ch.getId(), ch);
        }
        for (ActorHomeMember m : memberRepository.findByHomeIdOrderByIdAsc(h.getId())) {
            WorldMapVO.MemberVO mv = new WorldMapVO.MemberVO();
            mv.setCharacterRef(m.getCharacterRef());
            ActorCharacter ch = chars.get(com.holzyn.actor.common.NpcRefs.idOf(m.getCharacterRef()));
            mv.setCharacterName(ch == null ? null : ch.getName());
            mv.setIsOwner(m.getIsOwner());
            mv.setRole(m.getRole());
            mv.setRoomLabel(m.getRoomLabel());
            hv.getMembers().add(mv);
        }
        return hv;
    }
}
