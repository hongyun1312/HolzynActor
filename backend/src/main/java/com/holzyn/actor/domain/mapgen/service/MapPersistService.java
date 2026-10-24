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

import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import com.holzyn.actor.domain.mapgen.entity.ActorMapAssetType;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeatureGroup;
import com.holzyn.actor.domain.mapgen.entity.ActorMapParcel;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.model.MapAmbient;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapAssetType;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import com.holzyn.actor.domain.mapgen.repository.ActorMapAssetTypeRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureGroupRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapParcelRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapRoadRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorNpcRoutineRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import com.holzyn.actor.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图管线的<b>落库层</b>——所有写库动作都在这里，且每个方法都是一个短事务。
 *
 * <p>为什么单独拆出来（不只是「为了整洁」）：编排器 {@link MapGenOrchestrator} 全程含
 * 秒级 AI 调用与数秒的算法计算，<b>绝不能</b>被一个事务包住（H2 行锁会一直被持有，
 * 见 {@code AiCallOutsideTransactionTest} 的三轮现场证据）。所以纪律是：
 * 算完 → 交一块结果 → 这里开一个几十毫秒的事务写下去 → 事务立刻结束。中途失败时，
 * 已落库的阶段是可恢复的（{@code stage} 列指向失败点，单阶段重跑从那里继续）。</p>
 *
 * <p>另一个纪律：<b>同一张图的同一类数据整体替换</b>（先 delete by mapId 再批量 insert）。
 * 单阶段重跑时不会留下「上一版多出来的三条路」这种幽灵数据——地图数据是快照，不是增量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapPersistService {

    /** JSON 序列化器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldMapRepository mapRepository;
    private final ActorMapAssetTypeRepository assetTypeRepository;
    private final ActorMapFeatureGroupRepository groupRepository;
    private final ActorMapFeatureRepository featureRepository;
    private final ActorMapRoadRepository roadRepository;
    private final ActorMapParcelRepository parcelRepository;
    private final ActorNpcRoutineRepository routineRepository;
    private final ActorNpcHomeRepository homeRepository;

    // ==================== 地图行 ====================

    /**
     * 新建一张地图（状态 running，stage=S0）。同一项目已有未完成的地图会被置为 failed，
     * 保证「一个项目同时只有一张在跑/可用」。
     *
     * @param projectId     项目 ID
     * @param seed          种子
     * @param version       管线版本
     * @param size          边长（格）
     * @param metersPerCell 每格米数
     * @param genreProfile  题材档
     * @return 已落库的地图行
     */
    @Transactional
    public ActorWorldMap createRun(Long projectId, long seed, int version, int size,
                                   double metersPerCell, String genreProfile) {
        for (ActorWorldMap old : mapRepository.findByProjectIdAndStatusOrderByIdDesc(
                projectId, ActorWorldMap.STATUS_RUNNING)) {
            old.setStatus(ActorWorldMap.STATUS_FAILED);
            old.setStage("aborted");
            mapRepository.save(old);
            log.info("[mapgen] 旧地图 {} 标记为 failed（被新一轮生成取代）", old.getId());
        }
        ActorWorldMap map = new ActorWorldMap();
        map.setProjectId(projectId);
        map.setSeed(seed);
        map.setVersion(version);
        map.setSize(size);
        map.setMetersPerCell(metersPerCell);
        map.setGenreProfile(genreProfile);
        map.setStatus(ActorWorldMap.STATUS_RUNNING);
        map.setStage("S0");
        map.setProgress(0);
        return mapRepository.save(map);
    }

    /**
     * 落库 S0b 世界→地图契约（V5 起主存 {@code brief_json}，同时保留 {@code params_json.brief} 兼容旧读法）。
     *
     * <p>为什么两处都写：{@code /map/brief}、单阶段重跑、报告脚本都按 {@code params_json.brief} 读；
     * 而 M6 之后「世界 → 地图」的绑定关系越来越多引用契约（住所/设施/锚点），
     * 把整份契约独立成一列，查询时不必解析整棵地形参数树。等旧读法全部迁完再删旧键。</p>
     *
     * @param mapId 地图 ID
     * @param brief 契约
     */
    public void saveBrief(Long mapId, WorldMapBrief brief) {
        if (brief == null) {
            return;
        }
        ActorWorldMap map = requireMap(mapId);
        try {
            Map<String, Object> params = new HashMap<>();
            if (map.getParamsJson() != null && !map.getParamsJson().isBlank()) {
                params.putAll(MAPPER.readValue(map.getParamsJson(), Map.class));
            }
            params.put("brief", brief.toMap());
            map.setParamsJson(MAPPER.writeValueAsString(params));
            map.setBriefJson(MAPPER.writeValueAsString(brief.toMap()));
        } catch (Exception e) {
            throw new BizException(500, "地图契约序列化失败：" + e.getMessage());
        }
        mapRepository.save(map);
    }

    /**
     * 落库环境快照（V5）。
     *
     * <p>为什么生成时就要写一份：bundle 查询时会按世界时钟实时重算，但「这张图生成那一刻
     * 的世界是什么季节/天气/潮位」必须在图里留档——否则事后无法解释「为什么这张验收图
     * 的田是黄的、那张是绿的」。</p>
     *
     * @param mapId   地图 ID
     * @param ambient 环境快照
     */
    @Transactional
    public void saveAmbient(Long mapId, MapAmbient ambient) {
        if (ambient == null) {
            return;
        }
        ActorWorldMap map = requireMap(mapId);
        try {
            map.setAmbientJson(MAPPER.writeValueAsString(ambient.toMap()));
        } catch (Exception e) {
            log.warn("[mapgen] 环境快照序列化失败（不影响出图）：{}", e.getMessage());
            return;
        }
        mapRepository.save(map);
    }

    /**
     * 落库写实底图路径（V5）。
     *
     * @param mapId    地图 ID
     * @param dayPath  白天 4096² 主图相对路径
     * @param nightPath 夜景相对路径
     */
    @Transactional
    public void saveCompositePaths(Long mapId, String dayPath, String nightPath) {
        ActorWorldMap map = requireMap(mapId);
        map.setCompositePath(dayPath);
        map.setCompositeNightPath(nightPath);
        mapRepository.save(map);
    }

    /**
     * 落库角色日程（整体替换：同一张图的日程是一次快照，不做增量）。
     *
     * @param mapId    地图 ID
     * @param routines 日程段
     */
    @Transactional
    public void saveRoutines(Long mapId, List<ActorNpcRoutine> routines) {
        routineRepository.deleteByMapId(mapId);
        routineRepository.flush();
        if (routines == null || routines.isEmpty()) {
            return;
        }
        routineRepository.saveAll(routines);
        log.info("[mapgen] 日程落库（map={}）：{} 段", mapId, routines.size());
    }

    /**
     * 取地图行（不存在 404）。异步线程里没有事务上下文，所以用只读事务包一层拿实体。
     *
     * @param mapId 地图 ID
     * @return 地图行
     */
    @Transactional(readOnly = true)
    public ActorWorldMap findMap(Long mapId) {
        return requireMap(mapId);
    }

    /**
     * 读回已落库的资产类型（单阶段重跑 S3/S4 时用，不必再花一次 AI 调用）。
     *
     * @param mapId 地图 ID
     * @return 资产类型（按 sortOrder）
     */
    @Transactional(readOnly = true)
    public List<MapAssetType> loadAssetTypes(Long mapId) {
        List<MapAssetType> out = new ArrayList<>();
        for (ActorMapAssetType e : assetTypeRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            List<String> tags = new ArrayList<>();
            if (e.getStyleTags() != null && !e.getStyleTags().isBlank()) {
                for (String s : e.getStyleTags().split(",")) {
                    if (!s.isBlank()) {
                        tags.add(s.trim());
                    }
                }
            }
            out.add(new MapAssetType(e.getCode(), e.getName(), e.getCategory(), e.getKind(),
                    e.getBaseW() == null ? 9.0 : e.getBaseW(),
                    e.getBaseD() == null ? 7.5 : e.getBaseD(),
                    e.getFloorsMin() == null ? 1 : e.getFloorsMin(),
                    e.getFloorsMax() == null ? 1 : e.getFloorsMax(),
                    e.getWallColor(), e.getRoofColor(),
                    e.getRoofShape() == null ? "gable" : e.getRoofShape(),
                    e.getMaterial(), tags, e.getWeight() == null ? 10 : e.getWeight(),
                    e.getDescription()));
        }
        return out;
    }

    /**
     * 推进阶段/进度（短事务；SSE 与 DB 同一个事实来源，页面刷新后仍能显示进度）。
     *
     * @param mapId    地图 ID
     * @param stage    阶段码
     * @param progress 整轮百分比
     */
    @Transactional
    public void updateProgress(Long mapId, String stage, int progress) {
        mapRepository.findById(mapId).ifPresent(map -> {
            map.setStage(stage);
            map.setProgress(Math.max(0, Math.min(100, progress)));
            mapRepository.save(map);
        });
    }

    /**
     * 保存 S0 档案（params_json + profile_json + 地图名 + 题材档）。
     * <p>{@code params_json} 存<b>完整</b>的 {@link com.holzyn.actor.domain.mapgen.algo.TerrainParams}
     * 而不是「摘要中的几个字段」：单阶段重跑（S3/S4/S5）时必须能原样复算出同一张地形，
     * 摘要字段不够用；而完整参数只有二十几个 double，存全量的代价可以忽略。</p>
     *
     * @param mapId   地图 ID
     * @param profile 世界档案
     * @param seed    种子（写进 params 便于复算）
     */
    @Transactional
    public void saveProfile(Long mapId, MapProfile profile, long seed) {
        ActorWorldMap map = requireMap(mapId);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("seed", seed);
            params.put("aiGenerated", profile.aiGenerated());
            params.put("params", profile.params());
            map.setParamsJson(MAPPER.writeValueAsString(params));
            Map<String, Object> prof = new HashMap<>();
            prof.put("mapName", profile.mapName());
            prof.put("genreProfile", profile.genreProfile());
            prof.put("climate", profile.climate());
            prof.put("narrative", profile.narrative());
            prof.put("landmarks", profile.landmarks());
            prof.put("forbidden", profile.forbidden());
            prof.put("allowedCategories", profile.allowedCategories());
            prof.put("aiGenerated", profile.aiGenerated());
            map.setProfileJson(MAPPER.writeValueAsString(prof));
        } catch (Exception e) {
            throw new BizException(500, "地图档案序列化失败：" + e.getMessage());
        }
        map.setName(profile.mapName());
        map.setGenreProfile(profile.genreProfile());
        map.setStage("S1");
        map.setProgress(16);
        mapRepository.save(map);
    }

    /**
     * 读回 S0 档案（单阶段重跑用）。
     *
     * @param mapId 地图 ID
     * @return 档案；参数缺失时返回 null（调用方据此退回全流程重跑）
     */
    @Transactional(readOnly = true)
    public MapProfile loadProfile(Long mapId) {
        ActorWorldMap map = requireMap(mapId);
        if (map.getParamsJson() == null || map.getProfileJson() == null) {
            return null;
        }
        try {
            JsonNode paramsRoot = MAPPER.readTree(map.getParamsJson());
            JsonNode pNode = paramsRoot.path("params");
            if (pNode.isMissingNode() || pNode.isNull()) {
                return null;
            }
            TerrainParams p = MAPPER.treeToValue(pNode, TerrainParams.class);
            JsonNode root = MAPPER.readTree(map.getProfileJson());
            List<MapProfile.Landmark> marks = new ArrayList<>();
            for (JsonNode n : root.path("landmarks")) {
                marks.add(new MapProfile.Landmark(n.path("name").asText(""),
                        n.path("kind").asText("landmark"), n.path("sit").asText(""),
                        n.path("note").asText("")));
            }
            return new MapProfile(root.path("mapName").asText(map.getName()),
                    root.path("genreProfile").asText(map.getGenreProfile()),
                    root.path("climate").asText(null),
                    root.path("narrative").asText(null), p, marks,
                    strings(root.path("forbidden")), strings(root.path("allowedCategories")),
                    null, root.path("aiGenerated").asBoolean(false));
        } catch (Exception e) {
            log.warn("[mapgen] 读回 S0 档案失败（map={}）：{}", mapId, e.getMessage());
            return null;
        }
    }

    /**
     * 保存地形贴图路径与地形统计。
     *
     * @param mapId       地图 ID
     * @param terrainPath 产物根目录下的相对路径（如 {@code 12/terrain.png}）
     * @param stats       统计（陆地占比/可建占比/河流数…）
     */
    @Transactional
    public void saveTerrain(Long mapId, String terrainPath, Map<String, Object> stats) {
        ActorWorldMap map = requireMap(mapId);
        map.setTerrainPath(terrainPath);
        map.setTerrainVersion((map.getTerrainVersion() == null ? 0 : map.getTerrainVersion()) + 1);
        try {
            map.setStatsJson(MAPPER.writeValueAsString(stats));
        } catch (Exception e) {
            throw new BizException(500, "地形统计序列化失败：" + e.getMessage());
        }
        map.setStage("S2");
        map.setProgress(30);
        mapRepository.save(map);
    }

    // ==================== S2 资产类型 ====================

    /**
     * 保存 S2 资产类型库（整体替换）。
     *
     * @param mapId     地图 ID
     * @param projectId 项目 ID
     * @param catalog   资产目录
     */
    @Transactional
    public void saveAssets(Long mapId, Long projectId, MapAssetCatalog catalog) {
        actorDeleteAssets(mapId);
        int order = 0;
        List<ActorMapAssetType> rows = new ArrayList<>();
        for (MapAssetType t : catalog.types()) {
            ActorMapAssetType e = new ActorMapAssetType();
            e.setMapId(mapId);
            e.setProjectId(projectId);
            e.setCode(t.code());
            e.setName(t.name());
            e.setCategory(t.category());
            e.setKind(t.kind());
            e.setBaseW(t.baseW());
            e.setBaseD(t.baseD());
            e.setFloorsMin(t.floorsMin());
            e.setFloorsMax(t.floorsMax());
            e.setWallColor(t.wallColor());
            e.setRoofColor(t.roofColor());
            e.setRoofShape(t.roofShape());
            e.setMaterial(t.material());
            e.setStyleTags(t.styleTagsText());
            e.setWeight(t.weight());
            e.setForbidden(0);
            e.setDescription(t.description());
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            rows.add(e);
        }
        assetTypeRepository.saveAll(rows);
        ActorWorldMap map = requireMap(mapId);
        // 街区性格不属于「要素」，但前端要用它做区域名/悬浮说明——挂在 profile_json 里最省事，
        // 且与 S0 的档案是同一份语义数据（同样是「没有坐标的描述」）。
        try {
            Map<String, Object> prof = map.getProfileJson() == null ? new HashMap<>()
                    : MAPPER.readValue(map.getProfileJson(), Map.class);
            prof.put("districts", catalog.districts());
            prof.put("assetCount", rows.size());
            prof.put("assetsAiGenerated", catalog.aiGenerated());
            map.setProfileJson(MAPPER.writeValueAsString(prof));
        } catch (Exception e) {
            log.warn("[mapgen] 街区性格写入 profile_json 失败（不影响落位）：{}", e.getMessage());
        }
        map.setStage("S3");
        map.setProgress(44);
        mapRepository.save(map);
        log.info("[mapgen] S2 落库 {} 个资产类型（map={}）", rows.size(), mapId);
    }

    /** 删除该图的资产类型（短事务内部用）。 */
    private void actorDeleteAssets(Long mapId) {
        assetTypeRepository.deleteByMapId(mapId);
        assetTypeRepository.flush();
    }

    // ==================== S4 落位结果 ====================

    /**
     * 保存落位结果（院落组 / 要素 / 道路 / 地块，各自整体替换）。
     *
     * @param mapId  地图 ID
     * @param result 落位结果
     * @return 要素 code → 主键（供地块的 feature_id 回填）
     */
    @Transactional
    public Map<String, Long> savePlacement(Long mapId, MapPlacer.Result result) {
        ActorWorldMap map = requireMap(mapId);
        Long projectId = map.getProjectId();
        // 顺序：先删子表（地块引用要素），再删父表，避免残留悬挂引用
        parcelRepository.deleteByMapId(mapId);
        parcelRepository.flush();
        featureRepository.deleteByMapId(mapId);
        featureRepository.flush();
        groupRepository.deleteByMapId(mapId);
        groupRepository.flush();
        roadRepository.deleteByMapId(mapId);
        roadRepository.flush();

        // ① 院落/街区/田片
        Map<String, Long> groupIdByCode = new HashMap<>();
        int order = 0;
        List<ActorMapFeatureGroup> groups = new ArrayList<>();
        for (MapPlacer.PlacedGroup g : result.groups()) {
            ActorMapFeatureGroup e = new ActorMapFeatureGroup();
            e.setMapId(mapId);
            e.setProjectId(projectId);
            e.setCode(g.code());
            e.setName(g.name());
            e.setKind(g.kind());
            e.setCenterX(g.cx());
            e.setCenterZ(g.cz());
            e.setRadius(g.radius());
            e.setRotation(g.rotation());
            e.setStyleCode(g.styleCode());
            e.setDescription(g.description());
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            groups.add(e);
        }
        for (ActorMapFeatureGroup saved : groupRepository.saveAll(groups)) {
            groupIdByCode.put(saved.getCode(), saved.getId());
        }

        // ② 要素（建筑/农田/林地/码头/桥/广场/围墙）
        Map<String, Long> assetIdByCode = assetIds(mapId);
        Map<String, Long> featureIdByCode = new HashMap<>();
        List<ActorMapFeature> features = new ArrayList<>();
        order = 0;
        for (MapPlacer.PlacedFeature f : result.features()) {
            ActorMapFeature e = new ActorMapFeature();
            e.setMapId(mapId);
            e.setProjectId(projectId);
            e.setAssetTypeId(assetIdByCode.get(f.assetCode()));
            e.setAssetCode(f.assetCode());
            e.setCode(f.code());
            e.setName(f.name());
            e.setCategory(f.category());
            e.setKind(f.kind());
            e.setPosX(f.x());
            e.setPosZ(f.z());
            e.setSizeW(f.w());
            e.setSizeD(f.d());
            e.setRotation(f.rot());
            e.setHeight(f.height());
            e.setFloors(f.floors());
            e.setBaseElevation(f.baseElevation());
            e.setWallColor(f.wallColor());
            e.setRoofColor(f.roofColor());
            e.setRoofShape(f.roofShape());
            e.setDoorX(f.doorX());
            e.setDoorZ(f.doorZ());
            e.setDoorDir(f.doorDir());
            e.setPolygonJson(json(f.polygon()));
            e.setSemanticJson(f.semanticJson());
            // V5：语义 JSON 里已经是结构化的归属信息（homeRef/anchor/owner）。
            // 这里拆成独立列——报告要按锚点核对选址、前端要按 homeRef 反查房子、
            // 日程要按 owner 找田，都不该在每次查询时重新解析一段字符串。
            JsonNode sem = semantic(f.semanticJson());
            e.setAnchorLocation(textOf(sem, "anchor"));
            e.setRegionCode(textOf(sem, "region"));
            String homeRef = textOf(sem, "homeRef");
            if (homeRef == null || homeRef.isBlank()) {
                homeRef = textOf(sem, "owner");
            }
            e.setOwnerHomeRef(homeRef == null || homeRef.isBlank() ? null : homeRef);
            e.setFlagsJson(flagsOf(sem));
            e.setLocationCode(f.locationCode());
            e.setResidency(f.residency());
            e.setImportance(f.importance());
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            features.add(e);
        }
        for (ActorMapFeature saved : featureRepository.saveAll(features)) {
            featureIdByCode.put(saved.getCode(), saved.getId());
        }

        // ③ 道路（顺序即绘制顺序：低等级先画，高等级压在上面）
        List<ActorMapRoad> roads = new ArrayList<>();
        order = 0;
        for (MapPlacer.RoadSpec r : result.roads()) {
            ActorMapRoad e = new ActorMapRoad();
            e.setMapId(mapId);
            e.setProjectId(projectId);
            e.setCode(r.code());
            e.setName(r.name());
            e.setKind(r.kind());
            e.setLevel(RoadGrade.level(r.grade()));
            e.setWidth(r.width());
            e.setSurface(r.surface());
            e.setPathJson(json(r.pathMeters()));
            e.setLength(pathLength(r.pathMeters()));
            e.setBridgeOver(r.bridgeCells() > 0 ? "water" : null);
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            roads.add(e);
        }
        roadRepository.saveAll(roads);

        // ④ 地块
        List<ActorMapParcel> parcels = new ArrayList<>();
        order = 0;
        for (MapPlacer.PlacedParcel p : result.parcels()) {
            ActorMapParcel e = new ActorMapParcel();
            e.setMapId(mapId);
            e.setProjectId(projectId);
            e.setCode(p.code());
            e.setKind(p.kind());
            e.setCenterX(p.cx());
            e.setCenterZ(p.cz());
            e.setArea(p.area());
            e.setBuildable(p.buildable());
            e.setPolygonJson(json(p.polygon()));
            e.setFeatureId(featureIdByCode.get(p.featureCode()));
            e.setRoadCode(p.roadCode());
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            parcels.add(e);
        }
        parcelRepository.saveAll(parcels);

        // M6：把「图上的主屋」写回「世界数据里的住所」。
        // 顺序必须在要素落库之后：要拿到 feature 主键，角色才能顺着 id 找到那栋房子。
        backfillHomeAnchors(mapId, projectId, result.features(), featureIdByCode);

        map.setStage("S5");
        map.setProgress(88);
        mapRepository.save(map);
        log.info("[mapgen] S4 落库（map={}）：组 {} / 要素 {} / 路 {} / 地块 {}",
                mapId, groups.size(), features.size(), roads.size(), parcels.size());
        return featureIdByCode;
    }

    // ==================== 收尾 ====================

    /**
     * 标记生成完成（status=ready + generatedAt + 最终统计）。
     *
     * @param mapId 地图 ID
     * @param stats 统计（建筑/人口/农田/路网分量…）
     */
    @Transactional
    public void markReady(Long mapId, Map<String, Object> stats) {
        ActorWorldMap map = requireMap(mapId);
        map.setStatus(ActorWorldMap.STATUS_READY);
        map.setStage("S5");
        map.setProgress(100);
        map.setGeneratedAt(LocalDateTime.now());
        try {
            map.setStatsJson(MAPPER.writeValueAsString(stats));
        } catch (Exception e) {
            throw new BizException(500, "地图统计序列化失败：" + e.getMessage());
        }
        mapRepository.save(map);
    }

    /**
     * 标记生成失败（status=failed，stage 保留失败点，便于单阶段重跑）。
     *
     * @param mapId   地图 ID
     * @param stage   失败阶段
     * @param message 失败原因（写进 stats_json 的 error 字段，前端概览里显示）
     */
    @Transactional
    public void markFailed(Long mapId, String stage, String message) {
        mapRepository.findById(mapId).ifPresent(map -> {
            map.setStatus(ActorWorldMap.STATUS_FAILED);
            if (stage != null) {
                map.setStage(stage);
            }
            try {
                Map<String, Object> stats = new HashMap<>();
                stats.put("error", message == null ? "未知错误" : message);
                map.setStatsJson(MAPPER.writeValueAsString(stats));
            } catch (Exception ignored) {
                // 统计写失败不影响失败标记
            }
            mapRepository.save(map);
        });
    }

    /**
     * 单阶段重跑前的下游失效（S3 重跑就把路网/落位/贴图全清掉，从该阶段重新长出来）。
     *
     * @param mapId 地图 ID
     * @param stage 要从哪个阶段重跑（S3/S4/S5）
     */
    @Transactional
    public void invalidateFrom(Long mapId, String stage) {
        int from = stageIndex(stage);
        if (from <= 2) {
            // S2 及之前重跑：资产库要重新生成
            actorDeleteAssets(mapId);
        }
        if (from <= 4) {
            parcelRepository.deleteByMapId(mapId);
            featureRepository.deleteByMapId(mapId);
            groupRepository.deleteByMapId(mapId);
            roadRepository.deleteByMapId(mapId);
        }
        ActorWorldMap map = requireMap(mapId);
        map.setStatus(ActorWorldMap.STATUS_RUNNING);
        map.setStage(stage);
        map.setProgress(0);
        mapRepository.save(map);
        log.info("[mapgen] 单阶段重跑 {}：已失效下游数据（map={}）", stage, mapId);
    }

    // ==================== M6：住所坐标回写 ====================

    /**
     * 把落位结果里的主屋坐标写回 {@code actor_npc_home}。
     *
     * <p><b>这一步是整个 M6 的目的</b>：世界数据里有 12 个住所（名字/住户/所属区域），
     * 图上有 12 栋主屋（坐标/朝向/门位）。不写回的话，角色侧只知道「我家叫渔家小院」，
     * 不知道它在图上的哪个米数——「回家」这条路就断在第一米。</p>
     *
     * <p>写回哪些字段：{@code map_id}（这张图）、{@code map_feature_id}（那栋房子）、
     * {@code pos_x/pos_z}（占地中心，米）、{@code door_x/door_z}（门口，米）、
     * {@code room_label}（正屋）、{@code region}（契约里的所属地点名）。</p>
     *
     * <p>匹配键是 {@code homeRef}——契约里它由 {@code actor_npc_home.id} 的字符串形式给出，
     * 所以这里按 id 精确匹配，不做名字模糊匹配（同名住所会串户）。</p>
     *
     * @param mapId          地图 ID
     * @param projectId      项目 ID
     * @param features       落位要素
     * @param featureIdByCode 要素 code → 主键
     * @return 成功绑定的户数
     */
    private int backfillHomeAnchors(Long mapId, Long projectId,
                                    List<MapPlacer.PlacedFeature> features,
                                    Map<String, Long> featureIdByCode) {
        Map<String, ActorNpcHome> byRef = new HashMap<>();
        try {
            for (ActorNpcHome h : homeRepository.findByProjectIdOrderByIdAsc(projectId)) {
                byRef.put(String.valueOf(h.getId()), h);
            }
        } catch (Exception e) {
            log.warn("[mapgen] 回写住所坐标时读取住所失败：{}", e.getMessage());
            return 0;
        }
        if (byRef.isEmpty()) {
            return 0;
        }
        int bound = 0;
        for (MapPlacer.PlacedFeature f : features) {
            JsonNode sem = semantic(f.semanticJson());
            String ref = textOf(sem, "homeRef");
            if (ref == null || ref.isBlank()) {
                continue;
            }
            ActorNpcHome home = byRef.get(ref.trim());
            if (home == null) {
                // 契约里可能有「合成住所」（homeRef 不是 DB id），不报错，跳过即可
                continue;
            }
            home.setMapId(mapId);
            home.setMapFeatureId(featureIdByCode.get(f.code()));
            home.setPosX(f.x());
            home.setPosZ(f.z());
            home.setDoorX(f.doorX());
            home.setDoorZ(f.doorZ());
            home.setRoomLabel("正屋");
            String anchor = textOf(sem, "anchor");
            if (anchor != null && !anchor.isBlank()) {
                home.setRegion(anchor);
            }
            home.setStatus(ActorNpcHome.STATUS_ACTIVE);
            homeRepository.save(home);
            bound++;
        }
        log.info("[mapgen] M6 住所回写（map={}）：{}/{} 户绑定到主屋",
                mapId, bound, byRef.size());
        return bound;
    }

    /** 解析要素语义 JSON（失败返回 null，绝不抛出——落库不能因为一段语义串失败）。 */
    private static JsonNode semantic(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从语义树取字符串字段（缺失/空白 → null）。 */
    private static String textOf(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 从语义树提取「渲染/筛选用的布尔标记」（garden/strip/ridge/rear/annex/dwelling/facility…）。
     *
     * <p>为什么单独一列：前端与报告经常要筛「所有菜畦」「所有条田」，语义 JSON 里的
     * 布尔项散落在不同结构里，抽成一列 {@code flags_json} 之后一句 SQL 就能筛。</p>
     */
    private static String flagsOf(JsonNode sem) {
        if (sem == null) {
            return null;
        }
        List<String> keys = List.of("garden", "strip", "ridge", "rear", "annex", "dwelling",
                "facility", "compound", "infill");
        Map<String, Object> flags = new HashMap<>();
        for (String k : keys) {
            JsonNode v = sem.path(k);
            if (!v.isMissingNode() && v.asBoolean(false)) {
                flags.put(k, true);
            }
        }
        String kind = textOf(sem, "part");
        if (kind != null) {
            flags.put("part", kind);
        }
        if (flags.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(flags);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /** 取地图行（不存在直接 404）。 */
    private ActorWorldMap requireMap(Long mapId) {
        return mapRepository.findById(mapId)
                .orElseThrow(() -> new BizException(404, "地图不存在：" + mapId));
    }

    /** 资产 code → 主键。 */
    private Map<String, Long> assetIds(Long mapId) {
        Map<String, Long> out = new HashMap<>();
        for (ActorMapAssetType t : assetTypeRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            out.put(t.getCode(), t.getId());
        }
        return out;
    }

    /** 折线 JSON（{@code [[x,z],...]}，单位米）。 */
    private String json(double[][] poly) {
        if (poly == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(poly);
        } catch (Exception e) {
            return null;
        }
    }

    /** 折线 JSON（List 形态，{@code [[x,z],...]}）。 */
    private String json(List<double[]> path) {
        if (path == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** 折线长度（米）。 */
    private double pathLength(List<double[]> path) {
        if (path == null || path.size() < 2) {
            return 0;
        }
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            len += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return len;
    }

    /** 阶段 → 序号（S0=0 … S5=5；非法按 0）。 */
    static int stageIndex(String stage) {
        if (stage == null || stage.length() < 2) {
            return 0;
        }
        char c = stage.charAt(1);
        return c >= '0' && c <= '9' ? c - '0' : 0;
    }

    /** 取字符串数组（JSON 数组 → List<String>）。 */
    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
