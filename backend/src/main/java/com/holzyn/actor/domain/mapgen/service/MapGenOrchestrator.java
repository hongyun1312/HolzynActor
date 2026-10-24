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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainPainter;
import com.holzyn.actor.domain.mapgen.algo.TerrainSemantics;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.model.MapAmbient;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapAssetType;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 地图管线编排器：S0 世界档案 → S1 地形 → S2 资产 → S3 路网 → S4 落位 → S5 合成落库。
 *
 * <p><b>为什么必须异步 + 无事务</b>：整条管线里有 3 次 AI 调用（秒级~分钟级）与数秒的
 * 侵蚀/寻路计算。放进 HTTP 请求线程会让前端转圈几分钟；包进一个事务会让 H2 行锁被持有几分钟
 * （见 {@code AiCallOutsideTransactionTest} 的三轮现场证据）。所以请求只做
 * 「建行 + 提交异步任务 + 立刻返回 mapId」，进度经 {@link MapGenProgressHub} 的 SSE 推给前端。</p>
 *
 * <p><b>阶段失败怎么办</b>：每个阶段都在同一个 try 里推进，失败时 {@code stage} 停在失败点
 * （{@code status=failed}），原因写进 SSE 与 {@code stats_json}。因为每个阶段都<b>原子落库</b>，
 * 单阶段重跑（{@link #rerunStage}）只重算失败点之后的部分——不必从头再花 3 次 AI 调用。</p>
 *
 * <p><b>幂等锁</b>：同一项目同一时刻只允许一个 run（{@link #RUNNING}）。重复点「生成」不会
 * 产生两条流水线互相覆盖数据——第二次点击直接返回当前 mapId。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapGenOrchestrator {

    /** 异步执行线程池（2 个守护线程：一张图一个，不占请求线程） */
    private static final ExecutorService RUNNER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mapgen-runner");
        t.setDaemon(true);
        return t;
    });

    /** 正在跑的项目（幂等锁） */
    private static final Set<Long> RUNNING = ConcurrentHashMap.newKeySet();

    /** 阶段中文名（SSE 与 DB 用同一套文案） */
    private static final Map<String, String> STAGE_NAMES = Map.of(
            "S0", "世界档案", "S1", "地形", "S2", "建筑资产",
            "S3", "路网", "S4", "落位", "S5", "合成");

    /** 阶段起始进度（整轮百分比） */
    private static final Map<String, Integer> STAGE_PERCENT = Map.of(
            "S0", 0, "S1", 16, "S2", 30, "S3", 44, "S4", 60, "S5", 84);

    private final MapGenProperties props;
    private final WorldMapProfileService profileService;
    private final MapBriefService briefService;
    private final MapAssetPolicyService assetService;
    private final MapRoadPlanService roadService;
    private final MapPersistService persist;
    private final MapArtifactStore artifacts;
    private final MapGenProgressHub hub;
    private final MapBundleQueryService query;
    private final MapRoutineService routineService;
    private final ActorWorldClockRepository clockRepository;

    // ==================== 对外入口 ====================

    /**
     * 启动一次完整生成（建行 + 异步跑，立刻返回）。
     *
     * @param projectId 项目 ID
     * @param seed      种子（null=由项目 ID 与当前时间派生，即「重掷」；给了就是复现某张图）
     * @return 地图 ID
     */
    public Long generate(Long projectId, Long seed) {
        if (!props.isEnabled()) {
            throw new BizException(400, "地图生成已关闭（holzyn.actor.mapgen.enabled=false）");
        }
        var current = query.overview(projectId);
        if (RUNNING.contains(projectId)) {
            log.info("[mapgen] 项目 {} 已有生成任务在跑，忽略重复请求（map={}）", projectId,
                    current == null ? null : current.getId());
            return current == null ? null : current.getId();
        }
        long useSeed = seed != null ? seed : deriveSeed(projectId);
        ActorWorldMap map = persist.createRun(projectId, useSeed, props.getPipelineVersion(),
                props.getSize(), props.getMetersPerCell(), null);
        hub.beginRun(projectId);
        hub.log(projectId, "[S0] 开始生成地图（seed=" + useSeed + "）");
        submit(map.getId(), projectId, 0);
        return map.getId();
    }

    /**
     * 单阶段重跑（自动失效下游；越靠前的阶段重跑越贵，因为要重算更多东西）。
     *
     * @param projectId 项目 ID
     * @param stage     阶段码（S0..S5；大小写不敏感）
     * @return 地图 ID
     */
    public Long rerunStage(Long projectId, String stage) {
        if (!props.isEnabled()) {
            throw new BizException(400, "地图生成已关闭（holzyn.actor.mapgen.enabled=false）");
        }
        var overview = query.overview(projectId);
        if (overview == null) {
            throw new BizException(404, "项目还没有地图，请先完整生成一次");
        }
        String st = stage == null ? "S0" : stage.trim().toUpperCase(Locale.ROOT);
        if (!STAGE_NAMES.containsKey(st)) {
            throw new BizException(400, "未知阶段：" + stage);
        }
        if (RUNNING.contains(projectId)) {
            throw new BizException(409, "地图正在生成中，请等当前任务结束");
        }
        Long mapId = overview.getId();
        persist.invalidateFrom(mapId, st);
        hub.beginRun(projectId);
        hub.log(projectId, "[" + st + "] 单阶段重跑开始");
        submit(mapId, projectId, MapPersistService.stageIndex(st));
        log.info("[mapgen] 单阶段重跑 {}（project={}, map={}）", st, projectId, mapId);
        return mapId;
    }

    /** 某项目是否正在生成（前端按钮态/测试用）。 */
    public boolean isRunning(Long projectId) {
        return RUNNING.contains(projectId);
    }

    // ==================== 异步调度 ====================

    /** 提交异步任务（幂等锁在 finally 释放，异常也不会把项目永久锁死）。 */
    private void submit(Long mapId, Long projectId, int fromStage) {
        if (!RUNNING.add(projectId)) {
            return;
        }
        RUNNER.submit(() -> {
            try {
                run(mapId, projectId, fromStage);
            } catch (Throwable t) {
                log.error("[mapgen] 生成线程异常退出（map={}）", mapId, t);
                hub.fail(projectId, t.getMessage());
            } finally {
                RUNNING.remove(projectId);
            }
        });
    }

    /**
     * 跑管线（<b>无事务</b>：AI 与重算都在这里，落库交给 {@link MapPersistService} 的短事务）。
     *
     * @param mapId     地图 ID
     * @param projectId 项目 ID
     * @param fromStage 从哪个阶段开始（0=S0 全流程）
     */
    void run(Long mapId, Long projectId, int fromStage) {
        long t0 = System.currentTimeMillis();
        ActorWorldMap row = persist.findMap(mapId);
        long seed = row.getSeed();
        String stage = "S0";
        try {
            // ---------- S0 世界档案 ----------
            stage = "S0";
            MapProfile profile;
            if (fromStage <= 0) {
                beginStage(projectId, mapId, "S0");
                profile = profileService.build(projectId, seed);
                persist.saveProfile(mapId, profile, seed);
                hub.log(projectId, "[S0] 档案：" + profile.mapName() + "（" + profile.genreProfile()
                        + (profile.aiGenerated() ? "，AI 生成" : "，确定性兜底") + "）");
            } else {
                profile = persist.loadProfile(mapId);
                if (profile == null) {
                    throw new BizException(500, "读回世界档案失败，请重跑 S0");
                }
                hub.log(projectId, "[S0] 沿用已落库档案：" + profile.mapName());
            }

            // ---------- S0b 世界→地图契约（区域 / 设施 / 人口 / 数量预算） ----------
            // 这张契约决定「图上该有多少栋房、有哪些设施、聚落多大」——旧管线完全没有这一层，
            // 于是 12 户的岛被铺成 485 栋房。契约失败会回落「只含户数的通用契约」，不会阻断出图。
            // S0b 属于 S0 阶段，不另开一个 stage：否则进度会「S0 → S0 → S1」地倒退一格，
            // 单阶段重跑 S1 时更是把 stage 打回 S0，前端进度条会闪回。
            var brief = briefService.build(projectId, seed);
            persist.saveBrief(mapId, brief);
            hub.log(projectId, String.format("[S0b] 契约：%d 区域 / %d 设施 / %d 户 / 预算 %d 栋%s",
                    brief.regions().size(), brief.facilityTarget(),
                    brief.demography().households(), brief.buildingBudget(),
                    brief.aiGenerated() ? "" : "（确定性兜底）"));

            // ---------- S1 地形（确定性：同参数同图，重跑必然一致） ----------
            stage = "S1";
            beginStage(projectId, mapId, "S1");
            long t1 = System.currentTimeMillis();
            // M2 地形语义化：把契约的区域方位/高程/植被写进地形参数，
            // 让「新月形海湾、北端海岬、南岸滩涂、东侧雾林」真的长在地形上。
            int regionCount = TerrainSemantics.apply(profile.params(), brief);
            TerrainData terrain = TerrainGenerator.generate(profile.params());
            String terrainPath = artifacts.writeTerrain(mapId, TerrainPainter.render(terrain));
            Map<String, Object> terrainStats = terrainStats(terrain);
            persist.saveTerrain(mapId, terrainPath, terrainStats);
            hub.log(projectId, String.format(
                    "[S1] 地形 %d ms：陆地 %.1f%% / 可建 %.1f%% / 河 %d / 湖 %d / 语义区域 %d",
                    System.currentTimeMillis() - t1, terrain.landFraction * 100,
                    terrain.buildableFraction * 100, terrain.rivers.size(), terrain.lakes.size(),
                    regionCount));

            // ---------- 选址（S3/S4 的公共输入，无 AI） ----------
            Map<String, MapSites.Site> sites = MapSites.resolve(terrain, profile.params());
            Map<String, MapSites.Site> allSites = profile.mergeLandmarks(sites);

            // ---------- S2 建筑资产 ----------
            stage = "S2";
            beginStage(projectId, mapId, "S2");
            MapAssetCatalog catalog;
            if (fromStage <= 2) {
                catalog = assetService.build(projectId, profile, brief);
                persist.saveAssets(mapId, projectId, catalog);
                hub.log(projectId, "[S2] 资产类型 " + catalog.types().size() + " 个 / "
                        + catalog.districts().size() + " 个街区"
                        + (catalog.aiGenerated() ? "" : "（确定性兜底）"));
            } else {
                catalog = new MapAssetCatalog(persist.loadAssetTypes(mapId), List.of(), null, false);
                hub.log(projectId, "[S2] 沿用已落库资产类型 " + catalog.types().size() + " 个");
            }

            // ---------- S3 路网 ----------
            stage = "S3";
            beginStage(projectId, mapId, "S3");
            long t3 = System.currentTimeMillis();
            float[] cost = RoadRouter.buildCost(terrain, null, null);
            // 聚落半径必须与 S4 落位同源：街铺到哪儿、房排到哪儿是同一件事
            double townRadius = MapPlacer.townRadiusMeters(terrain, profile.params(),
                    MapPlacer.PlacementBudget.from(brief));
            MapRoadPlanService.Plan plan = roadService.build(projectId, terrain, profile, catalog,
                    allSites, seed, cost, townRadius);
            hub.log(projectId, String.format("[S3] 路网 %d ms：%d 条（分量 %d）",
                    System.currentTimeMillis() - t3, plan.roads().size(), plan.components()));

            // ---------- S4 落位 ----------
            stage = "S4";
            beginStage(projectId, mapId, "S4");
            long t4 = System.currentTimeMillis();
            List<MapPlacer.AssetSpec> specs = new ArrayList<>();
            for (MapAssetType t : catalog.types()) {
                specs.add(t.toPlacerSpec());
            }
            MapPlacer.Result placed = MapPlacer.place(terrain, profile.params(), seed * 31 + 7,
                    specs, plan.roads(), allSites, brief);
            persist.savePlacement(mapId, placed);
            int buildings = countKind(placed, "building");
            // 人口口径（P0-3）：actor_map_feature.residency 语义是「户」，人口 = 户数 × 户均。
            // 两者分开落库，前端与报告都不再把「户数」当「人口」展示（旧图 255 户显示成 255 人）。
            int households = brief.demography().households();
            long residents = brief.demography().residents();
            // 农田按「块」计（features 里 kind=field 的地块），不是按「田片」计——
            // 一个田片（group）里有几十块田，用 group 数会让人以为这个镇子只有两亩地。
            long fields = placed.features().stream().filter(f -> "field".equals(f.kind())).count();
            hub.log(projectId, String.format("[S4] 落位 %d ms：建筑 %d / 户 %d / 人口 %d / 农田 %d / 路 %d",
                    System.currentTimeMillis() - t4, buildings, households, residents, fields,
                    placed.roads().size()));

            // ---------- S5 合成 ----------
            stage = "S5";
            beginStage(projectId, mapId, "S5");
            // 环境快照：季节/天气/潮汐/昼夜——地图不是一张静态照片，出图这一刻的世界状态要留档，
            // 同时用它给底图做季节色/雨湿/潮位分级（否则田永远是同一个黄，滩涂永远是同一个宽）。
            MapAmbient ambient = ambientOf(projectId, seed);
            persist.saveAmbient(mapId, ambient);
            try {
                MapArtifactStore.CompositePaths paths = artifacts.renderAndStoreComposite(
                        mapId, terrain, placed.roads(), placed.features(), ambient);
                persist.saveCompositePaths(mapId, paths.day(), paths.night());
            } catch (Exception e) {
                // 合成图只是「给人看的自检产物」，画失败不该让整张图变 failed
                log.warn("[mapgen] 写实底图生成失败（不影响地图数据）：{}", e.getMessage());
            }
            // 角色日程（M7）：家 → 工作地 → 集市 → 夜归。让图上的人真的在走动。
            long tRoutine = System.currentTimeMillis();
            int routineCount = 0;
            try {
                var routines = routineService.build(projectId, mapId, placed.features());
                persist.saveRoutines(mapId, routines);
                routineCount = routines.size();
            } catch (Exception e) {
                log.warn("[mapgen] 日程生成失败（不影响地图数据）：{}", e.getMessage());
            }
            hub.log(projectId, String.format("[S5] 底图 4096² + 三档 LOD / 环境 %s%s / 日程 %d 段（%d ms）",
                    ambient.seasonName(), ambient.weatherName(), routineCount,
                    System.currentTimeMillis() - tRoutine));
            Map<String, Object> finalStats = new LinkedHashMap<>(terrainStats);
            finalStats.put("buildings", buildings);
            finalStats.put("households", households);
            finalStats.put("residents", residents);
            finalStats.put("fields", fields);
            finalStats.put("roads", placed.roads().size());
            finalStats.put("roadComponents", MapNetwork.groupCount(placed.roads()));
            finalStats.put("routines", routineCount);
            finalStats.put("ambient", ambient.toMap());
            // 诊断计数只有调参期需要（P2）：关掉之后 stats 只剩业务指标，前端面板/报告不必再过滤
            if (props.isDiagnostics()) {
                finalStats.put("diagnostics", placed.diag());
            }
            finalStats.put("elapsedMs", System.currentTimeMillis() - t0);
            persist.markReady(mapId, finalStats);
            hub.log(projectId, String.format("[S5] 完成，总耗时 %d ms", System.currentTimeMillis() - t0));
            hub.complete(projectId, query.overview(projectId));
            log.info("[mapgen] 生成完成：map={}, project={}, 总耗时 {}ms", mapId, projectId,
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("[mapgen] 生成失败（map={}, stage={}）：{}", mapId, stage, e.getMessage(), e);
            persist.markFailed(mapId, stage, e.getMessage());
            hub.log(projectId, "[" + stage + "] 失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            hub.fail(projectId, e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /** 广播阶段开始并把进度落库（页面刷新后靠 DB 里的 stage/progress 恢复）。 */
    private void beginStage(Long projectId, Long mapId, String stage) {
        int index = MapPersistService.stageIndex(stage) + 1;
        int percent = STAGE_PERCENT.getOrDefault(stage, 0);
        String name = STAGE_NAMES.getOrDefault(stage, stage);
        hub.stage(projectId, stage, name, index, percent);
        hub.progress(projectId, percent, name + "…");
        persist.updateProgress(mapId, stage, percent);
    }

    /** 种子派生（重掷：项目 + 秒级时间；同一秒内连点两次会被幂等锁挡住）。 */
    private long deriveSeed(Long projectId) {
        return Math.abs(projectId * 1_000_003L + System.currentTimeMillis() / 1000L);
    }

    /**
     * 当前世界环境（季节/天气/潮汐/昼夜）。
     *
     * <p>取世界时钟的 {@code lastGameSecond}；项目还没开时钟时从 0 起算
     * （= 世界纪元的第 1 天春天清晨），宁可给一个确定值，也不要随机。</p>
     *
     * @param projectId 项目 ID
     * @param seed      地图种子（天气抽取盐）
     * @return 环境快照
     */
    private MapAmbient ambientOf(Long projectId, long seed) {
        long sec = 0;
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
            if (clock != null && clock.getLastGameSecond() != null) {
                sec = clock.getLastGameSecond();
            }
        } catch (Exception e) {
            log.debug("[mapgen] 读世界时钟失败，环境从纪元起算：{}", e.getMessage());
        }
        return MapAmbient.of(sec, seed);
    }

    /** 地形统计（前端参数面板 + 质量门展示）。 */
    private Map<String, Object> terrainStats(TerrainData t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("landFraction", t.landFraction);
        m.put("buildableFraction", t.buildableFraction);
        m.put("rivers", t.rivers.size());
        m.put("lakes", t.lakes.size());
        m.put("size", t.size);
        m.put("metersPerCell", t.metersPerCell);
        m.put("seaLevel", t.seaLevel);
        m.put("biomes", biomeCensus(t));
        return m;
    }

    /**
     * 群系面积占比（陆地内占比，海不算）。
     *
     * <p>为什么要单独统计：地图"好不好看"最直接的量化指标就是**地表够不够丰富**——
     * 只有草地（林地占比 0%）的岛，不管怎么调色都是一块绿抹布。这份普查既是参数面板的展示项，
     * 也是调整植被参数的唯一依据（否则只能凭肉眼猜"是不是少了点树"）。</p>
     *
     * @param t 地形
     * @return 群系中文名 → 占比（0~1），按占比降序
     */
    private Map<String, Object> biomeCensus(TerrainData t) {
        Map<String, Integer> count = new LinkedHashMap<>();
        int land = 0;
        int cells = t.size * t.size;
        for (int i = 0; i < cells; i++) {
            byte b = t.biome[i];
            if (com.holzyn.actor.domain.mapgen.algo.Biomes.isSea(b)) {
                continue;
            }
            land++;
            String name = com.holzyn.actor.domain.mapgen.algo.Biomes.name(b);
            count.merge(name, 1, Integer::sum);
        }
        final int landCells = land;
        Map<String, Object> out = new LinkedHashMap<>();
        count.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.put(e.getKey(),
                        Math.round(e.getValue() * 10000.0 / Math.max(1, landCells)) / 10000.0));
        return out;
    }

    /** 数某类要素的个数。 */
    private int countKind(MapPlacer.Result r, String kind) {
        int n = 0;
        for (MapPlacer.PlacedFeature f : r.features()) {
            if (kind.equals(f.kind())) {
                n++;
            }
        }
        return n;
    }
}
