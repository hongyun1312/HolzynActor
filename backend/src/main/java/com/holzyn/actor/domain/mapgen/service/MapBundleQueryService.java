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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.MapCompositePainter;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeatureGroup;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.model.MapAmbient;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureGroupRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapRoadRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorNpcRoutineRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapgen.vo.MapBundleVO;
import com.holzyn.actor.domain.mapgen.vo.WorldMapOverviewVO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图查询：概览 + 渲染包。
 *
 * <p>两条纪律：</p>
 * <ul>
 *   <li><b>只读、短事务</b>：所有方法都是 {@code @Transactional(readOnly = true)}——渲染包会把
 *       整张图的要素/道路读进内存，持有只读事务的时间就是一次序列化的时间，不会阻塞生成线程；</li>
 *   <li><b>坐标出库前不换算</b>：库里存米，包里给米。像素换算是前端的事（{@code metersPerCell} 随包下发），
 *       这样同一份数据既能给 PixiJS 也能给 Unity，不会出现「后端按 2 像素/米算好、前端换分辨率就糊了」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapBundleQueryService {

    /** 时间格式（前端直接显示） */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JSON 读写（path_json/polygon_json 都是约定的简单数组） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldMapRepository mapRepository;
    private final ActorMapRoadRepository roadRepository;
    private final ActorMapFeatureRepository featureRepository;
    private final ActorMapFeatureGroupRepository groupRepository;
    private final ActorNpcRoutineRepository routineRepository;
    private final ActorNpcHomeRepository homeRepository;
    private final ActorWorldClockRepository clockRepository;

    /**
     * 项目当前地图（最新一行；没有则返回 null）。
     *
     * @param projectId 项目 ID
     * @return 概览或 null
     */
    @Transactional(readOnly = true)
    public WorldMapOverviewVO overview(Long projectId) {
        return mapRepository.findFirstByProjectIdOrderByIdDesc(projectId)
                .map(this::toOverview).orElse(null);
    }

    /**
     * 项目当前地图的渲染包。
     *
     * @param projectId 项目 ID
     * @return 渲染包；无地图时返回 null
     */
    @Transactional(readOnly = true)
    public MapBundleVO bundle(Long projectId) {
        ActorWorldMap map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        if (map == null) {
            return null;
        }
        MapBundleVO bundle = new MapBundleVO();
        bundle.setOverview(toOverview(map));
        bundle.setPalette(palette());
        bundle.setRoads(roads(map.getId()));
        bundle.setFeatures(features(map.getId()));
        bundle.setGroups(groups(map.getId()));
        bundle.setDistricts(districts(map.getProfileJson()));
        MapAmbient ambient = ambientOf(map);
        bundle.setAmbient(ambient.toMap());
        bundle.setCharacters(characters(map, ambient));
        return bundle;
    }

    // ==================== 环境 / 角色 ====================

    /**
     * 当前环境：按世界时钟实时算（不是读生成时的快照）。
     *
     * <p>为什么查询时算：地图页开着的时候世界时钟还在走，读快照会让「白天」永远停在生成那一刻。
     * 生成时写 {@code ambient_json} 只是为了留档（验收报告里要写「这张图是秋·雨·高潮时生成的」）。</p>
     *
     * @param map 地图行
     * @return 环境快照
     */
    private MapAmbient ambientOf(ActorWorldMap map) {
        long sec = 0;
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(map.getProjectId()).orElse(null);
            if (clock != null && clock.getLastGameSecond() != null) {
                sec = clock.getLastGameSecond();
            }
        } catch (Exception e) {
            log.debug("[mapgen] 读世界时钟失败：{}", e.getMessage());
        }
        return MapAmbient.of(sec, map.getSeed() == null ? 0 : map.getSeed());
    }

    /**
     * 角色 + 日程（按角色分组）。
     *
     * <p>除了把锦点原样下发，还顺手算好「此刻在哪」：前端即使不插值也能立刻画出人，
     * 想让它走动再按世界时钟推进即可。</p>
     *
     * @param map     地图行
     * @param ambient 环境
     * @return 角色列表
     */
    private List<MapBundleVO.CharacterVO> characters(ActorWorldMap map, MapAmbient ambient) {
        List<ActorNpcRoutine> rows;
        try {
            rows = routineRepository.findByProjectIdAndMapIdOrderByNpcRefAscSeqAsc(
                    map.getProjectId(), map.getId());
        } catch (Exception e) {
            log.debug("[mapgen] 读日程失败：{}", e.getMessage());
            return new ArrayList<>();
        }
        Map<String, ActorMapFeature> featureByCode = new HashMap<>();
        for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
            featureByCode.put(f.getCode(), f);
        }
        Map<String, MapBundleVO.CharacterVO> byRef = new LinkedHashMap<>();
        for (ActorNpcRoutine r : rows) {
            if (r.getNpcRef() == null) {
                continue;
            }
            MapBundleVO.CharacterVO c = byRef.computeIfAbsent(r.getNpcRef(), k -> {
                MapBundleVO.CharacterVO vo = new MapBundleVO.CharacterVO();
                vo.setRef(k);
                vo.setName(r.getNpcName());
                vo.setSegments(new ArrayList<>());
                return vo;
            });
            MapBundleVO.RoutineVO seg = new MapBundleVO.RoutineVO();
            seg.setStartMinute(r.getStartMinute());
            seg.setEndMinute(r.getEndMinute());
            seg.setPlaceKind(r.getPlaceKind());
            seg.setPlaceRef(r.getPlaceRef());
            seg.setFeatureCode(r.getFeatureCode());
            seg.setX(r.getPosX());
            seg.setZ(r.getPosZ());
            seg.setActivity(r.getActivity());
            seg.setLabel(r.getLabel());
            seg.setIndoor(r.getIndoor() != null && r.getIndoor() == 1);
            c.getSegments().add(seg);
        }
        int minute = ambient.minuteOfDay();
        for (MapBundleVO.CharacterVO c : byRef.values()) {
            MapBundleVO.RoutineVO now = null;
            MapBundleVO.RoutineVO home = null;
            for (MapBundleVO.RoutineVO s : c.getSegments()) {
                if ("home".equals(s.getPlaceKind()) && home == null) {
                    home = s;
                }
                if (s.getStartMinute() != null && s.getEndMinute() != null
                        && minute >= s.getStartMinute() && minute < s.getEndMinute()) {
                    now = s;
                }
            }
            MapBundleVO.RoutineVO cur = now == null ? home : now;
            if (cur != null) {
                c.setX(cur.getX());
                c.setZ(cur.getZ());
                c.setActivity(cur.getActivity());
                c.setActivityLabel(cur.getLabel());
                c.setIndoor(Boolean.TRUE.equals(cur.getIndoor()));
                if ("home".equals(cur.getPlaceKind()) && cur.getFeatureCode() != null) {
                    ActorMapFeature f = featureByCode.get(cur.getFeatureCode());
                    if (f != null) {
                        c.setHomeRef(f.getOwnerHomeRef());
                        c.setHomeFeatureCode(f.getCode());
                    }
                }
            }
            if (c.getHomeRef() == null && home != null) {
                ActorMapFeature f = featureByCode.get(home.getFeatureCode());
                if (f != null) {
                    c.setHomeRef(f.getOwnerHomeRef());
                    c.setHomeFeatureCode(f.getCode());
                }
            }
        }
        return new ArrayList<>(byRef.values());
    }

    // ==================== 概览 ====================

    /**
     * 地图行 → 概览 VO。
     *
     * @param map 地图行
     * @return 概览
     */
    private WorldMapOverviewVO toOverview(ActorWorldMap map) {
        WorldMapOverviewVO vo = new WorldMapOverviewVO();
        vo.setId(map.getId());
        vo.setProjectId(map.getProjectId());
        vo.setName(map.getName());
        vo.setStatus(map.getStatus());
        vo.setStage(map.getStage());
        vo.setProgress(map.getProgress());
        vo.setSize(map.getSize());
        double mpc = map.getMetersPerCell() == null ? 1.0 : map.getMetersPerCell();
        vo.setMetersPerCell(mpc);
        double width = (map.getSize() == null ? 0 : map.getSize()) * mpc;
        vo.setWidthMeters(width);
        vo.setHeightMeters(width);
        vo.setGenreProfile(map.getGenreProfile());
        vo.setTerrainVersion(map.getTerrainVersion());
        vo.setTerrainUrl(map.getTerrainPath() == null ? null
                : String.format(com.holzyn.actor.domain.mapgen.controller.WorldMapGenController.TERRAIN_URL_TEMPLATE,
                map.getProjectId(), map.getTerrainVersion()));
        // 写实底图：三档 LOD 全部带版本号（版本号跟地形一起自增，重掷即破缓存）
        int version = map.getTerrainVersion() == null ? 1 : map.getTerrainVersion();
        if (map.getCompositePath() != null) {
            Map<String, String> lods = new LinkedHashMap<>();
            Map<String, String> nightLods = new LinkedHashMap<>();
            for (int px : new int[]{4096, 2048, 1024}) {
                lods.put(String.valueOf(px), String.format(
                        com.holzyn.actor.domain.mapgen.controller.WorldMapGenController.COMPOSITE_URL_TEMPLATE,
                        map.getProjectId(), version, px));
                nightLods.put(String.valueOf(px), String.format(
                        com.holzyn.actor.domain.mapgen.controller.WorldMapGenController.COMPOSITE_NIGHT_URL_TEMPLATE,
                        map.getProjectId(), version, px));
            }
            vo.setLods(lods);
            vo.setNightLods(nightLods);
            vo.setCompositeUrl(lods.get("4096"));
            vo.setCompositeNightUrl(nightLods.get("4096"));
            vo.setCompositePixels(4096);
        }
        vo.setPipelineVersion(map.getVersion());
        vo.setStats(readMap(map.getStatsJson()));
        vo.setProfile(readMap(map.getProfileJson()));
        vo.setUpdatedAt(map.getUpdatedAt() == null ? null : map.getUpdatedAt().format(TS));
        return vo;
    }

    // ==================== 要素 ====================

    /**
     * 道路（按等级升序，等价于绘制顺序）。
     */
    private List<MapBundleVO.RoadVO> roads(Long mapId) {
        List<MapBundleVO.RoadVO> out = new ArrayList<>();
        for (ActorMapRoad r : roadRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            MapBundleVO.RoadVO vo = new MapBundleVO.RoadVO();
            vo.setCode(r.getCode());
            vo.setName(r.getName());
            vo.setKind(r.getKind());
            int grade = RoadGrade.ofLevel(r.getLevel());
            vo.setLevel(RoadGrade.level(grade));
            vo.setGrade(grade);
            vo.setGradeLabel(RoadGrade.label(grade));
            vo.setWidth(r.getWidth());
            vo.setSurface(r.getSurface());
            vo.setPath(readPath(r.getPathJson()));
            vo.setLength(r.getLength());
            vo.setBridge(r.getBridgeOver() != null);
            out.add(vo);
        }
        out.sort((a, b) -> Integer.compare(a.getGrade(), b.getGrade()));
        return out;
    }

    /**
     * 要素（建筑/农田/林地/码头/桥/广场/围墙）。
     */
    private List<MapBundleVO.FeatureVO> features(Long mapId) {
        List<MapBundleVO.FeatureVO> out = new ArrayList<>();
        for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            MapBundleVO.FeatureVO vo = new MapBundleVO.FeatureVO();
            vo.setCode(f.getCode());
            vo.setName(f.getName());
            vo.setCategory(f.getCategory());
            vo.setKind(f.getKind());
            vo.setAssetCode(f.getAssetCode());
            vo.setX(f.getPosX());
            vo.setZ(f.getPosZ());
            vo.setW(f.getSizeW());
            vo.setD(f.getSizeD());
            vo.setRot(f.getRotation());
            vo.setHeight(f.getHeight());
            vo.setFloors(f.getFloors());
            vo.setWallColor(f.getWallColor());
            vo.setRoofColor(f.getRoofColor());
            vo.setRoofShape(f.getRoofShape());
            vo.setPolygon(readPath(f.getPolygonJson()));
            vo.setDoorX(f.getDoorX());
            vo.setDoorZ(f.getDoorZ());
            vo.setDoorDir(f.getDoorDir());
            vo.setResidency(f.getResidency());
            vo.setImportance(f.getImportance());
            vo.setLocationCode(f.getLocationCode());
            vo.setSemantic(f.getSemanticJson());
            vo.setHomeRef(f.getOwnerHomeRef());
            vo.setOwnerHomeRef(f.getOwnerHomeRef());
            vo.setAnchorLocation(f.getAnchorLocation());
            vo.setRegionCode(f.getRegionCode());
            vo.setFlags(readMap(f.getFlagsJson()));
            out.add(vo);
        }
        return out;
    }

    /**
     * 院落/街区/田片。
     */
    private List<MapBundleVO.GroupVO> groups(Long mapId) {
        List<MapBundleVO.GroupVO> out = new ArrayList<>();
        for (ActorMapFeatureGroup g : groupRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            MapBundleVO.GroupVO vo = new MapBundleVO.GroupVO();
            vo.setCode(g.getCode());
            vo.setName(g.getName());
            vo.setKind(g.getKind());
            vo.setCx(g.getCenterX());
            vo.setCz(g.getCenterZ());
            vo.setRadius(g.getRadius());
            vo.setRotation(g.getRotation());
            vo.setStyleCode(g.getStyleCode());
            vo.setDescription(g.getDescription());
            out.add(vo);
        }
        return out;
    }

    /**
     * 街区性格（从 profile_json 里带出来；S2 的 districts 存在统计里，见 {@link MapGenOrchestrator}）。
     */
    private List<Map<String, Object>> districts(String profileJson) {
        JsonNode root = read(profileJson);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = root.path("districts");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", n.path("code").asText(""));
                m.put("name", n.path("name").asText(""));
                m.put("character", n.path("character").asText(""));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 调色板：道路四级的颜色/宽度/中文名，全部取自算法层同一个函数。
     */
    private MapBundleVO.Palette palette() {
        MapBundleVO.Palette p = new MapBundleVO.Palette();
        Map<String, String> colors = new LinkedHashMap<>();
        Map<String, String> shoulders = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Double> widths = new LinkedHashMap<>();
        for (int grade = RoadGrade.TRACK; grade <= RoadGrade.TRUNK; grade++) {
            String key = String.valueOf(grade);
            colors.put(key, MapCompositePainter.roadColorHex(grade, RoadGrade.defaultSurface(grade)));
            shoulders.put(key, MapCompositePainter.shoulderColorHex(grade));
            labels.put(key, RoadGrade.label(grade));
            widths.put(key, RoadGrade.defaultWidth(grade));
        }
        p.setRoadColors(colors);
        p.setShoulderColors(shoulders);
        p.setRoadLabels(labels);
        p.setRoadWidths(widths);
        return p;
    }

    // ==================== JSON 读写 ====================

    /** 读折线/多边形（{@code [[x,z],...]}）；解析失败返回空表而不是 null，前端少一层判空。 */
    private List<double[]> readPath(String json) {
        List<double[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            double[][] arr = MAPPER.readValue(json, double[][].class);
            for (double[] p : arr) {
                out.add(p);
            }
        } catch (Exception e) {
            log.debug("[mapgen] 折线解析失败：{}", e.getMessage());
        }
        return out;
    }

    /** 读 JSON 对象为 Map（失败返回空 Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 读 JSON 树（失败返回空对象）。 */
    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
