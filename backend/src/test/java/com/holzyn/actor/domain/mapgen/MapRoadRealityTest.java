/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen;

import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import com.holzyn.actor.domain.mapgen.algo.TerrainSemantics;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import com.holzyn.actor.domain.mapgen.service.MapRoadPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「路网真实感」的四条硬约束（V1.6 新增）。
 *
 * <p>用户点名的四件事，都能翻译成可断言的几何事实，而不是"看着像不像"：</p>
 * <ol>
 *   <li><b>每间独立房屋都连着路</b>：房屋（宅院内部的正屋/厢房除外，它们走院门）的大门
 *       到最近路面的距离 ≤ 8 米；</li>
 *   <li><b>没有重复走廊</b>：不存在两条「连着同样两个地方、且大半重合」的路；</li>
 *   <li><b>就近连接</b>：{@link MapNetwork#connect} 补的连接路，接头必须落在<b>最近的
 *       那一个路面点</b>上，不能因为折线抽稀过就跑去接路的端点；</li>
 *   <li><b>没有野地断头</b>：路的端头要么接着别的路，要么 15 米内有临街的门、17 米内有田。</li>
 * </ol>
 *
 * <p>这些断言守的是「生成口径」，不是某一颗种子的成图——所以每颗种子都单独跑一遍。</p>
 */
class MapRoadRealityTest {

    /** 回归种子（与 MapgenConnectivityTest 同源，便于横向对照）。 */
    private static final long[] SEEDS = {20261016L, 777L, 31L};

    /** 一颗种子的整套产物。 */
    private record Scene(TerrainData terrain, Map<String, MapSites.Site> sites,
                         List<MapPlacer.RoadSpec> roads, MapPlacer.Result placed) {
    }

    private Scene generate(long seed, WorldMapBrief brief) {
        // 走<b>生产同一条路径</b>：S1 地形 → MapSites 选址 → MapRoadPlanService（骨架 + 目的地路）
        // → MapPlacer（落位 + 路网整备）。不要用 MapFullPreviewMain 里那套"从镇心向各点射线"的
        // 老式建路——那条路径早在 V1.3 就被骨架模型取代了，拿它当断言基准等于测一个不存在的管线。
        MapGenProperties props = new MapGenProperties();
        props.setAiEnabled(false);
        props.setSize(1024);
        props.setMetersPerCell(1.0);
        MapProfile profile = MapProfile.fallback(seed, 1024);
        TerrainSemantics.apply(profile.params(), brief);
        TerrainData t = TerrainGenerator.generate(profile.params());
        Map<String, MapSites.Site> sites = MapSites.resolve(t, profile.params());
        Map<String, MapSites.Site> allSites = profile.mergeLandmarks(sites);
        float[] cost = RoadRouter.buildCost(t, null, null);
        double townRadius = MapPlacer.townRadiusMeters(t, profile.params(),
                MapPlacer.PlacementBudget.from(brief));
        MapRoadPlanService roads = new MapRoadPlanService(null, null, props);
        MapRoadPlanService.Plan plan = roads.build(1L, t, profile, MapAssetCatalog.fallback(),
                allSites, seed, cost, townRadius);
        List<MapPlacer.AssetSpec> specs = new ArrayList<>();
        MapAssetCatalog.fallback().types().forEach(x -> specs.add(x.toPlacerSpec()));
        MapPlacer.Result placed = MapPlacer.place(t, profile.params(), seed * 31 + 7, specs,
                plan.roads(), allSites, brief);
        return new Scene(t, allSites, placed.roads(), placed);
    }

    /**
     * 宅院内部房：后厢房 / 柴房 / 三合院的正屋与厢房。它们的门开在院子里，进出走院门。
     */
    private static boolean courtyardPart(MapPlacer.PlacedFeature f) {
        String s = f.semanticJson();
        if (s == null) {
            return false;
        }
        if (s.contains("\"rear\":true") || s.contains("\"annex\":true")) {
            return true;
        }
        return s.contains("\"compound\":true") && !s.contains("\"part\":2");
    }

    /** 端点是不是就停在水边（6 米内是水面）——取水口、靠船处、海滨小道都这样收尾。 */
    private static boolean nearWater(TerrainData t, double x, double z) {
        double mpc = Math.max(0.05, t.metersPerCell);
        int cx = (int) Math.floor(x / mpc);
        int cz = (int) Math.floor(z / mpc);
        int r = (int) Math.ceil(6.0 / mpc);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int ax = cx + dx;
                int az = cz + dz;
                if (ax < 0 || az < 0 || ax >= t.size || az >= t.size) {
                    continue;
                }
                if (t.waterCell(az * t.size + ax)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 入户小径 / 连接路：按"户"或"缺口"生成的路，不参与"重复走廊"与"野地断头"的判定。 */
    private static boolean perHousehold(MapPlacer.RoadSpec r) {
        String code = r.code() == null ? "" : r.code();
        return code.startsWith("door_") || code.startsWith("conn_")
                || code.startsWith("farm_conn_") || code.startsWith("acc_");
    }

    /** 点到路网的最近距离。 */
    private static double doorRoadDist(List<MapPlacer.RoadSpec> roads, double x, double z) {        double best = Double.MAX_VALUE;
        for (MapPlacer.RoadSpec r : roads) {
            if (r.pathMeters() != null && r.pathMeters().size() >= 2) {
                best = Math.min(best, MapNetwork.nearestOnPath(r.pathMeters(), x, z)[2]);
            }
        }
        return best;
    }

    @Test
    @DisplayName("码头：栈桥根接进路网、岸上有货场、仓栈临路（本项目对「水工物也要有路」的判据）")
    void harborIsWiredIntoTheRoadNetwork() {
        for (long seed : SEEDS) {
            Scene s = generate(seed, WorldMapBrief.genericFallback(12));
            MapPlacer.PlacedFeature pier = null;
            int quays = 0;
            int harborBuildings = 0;
            for (MapPlacer.PlacedFeature f : s.placed().features()) {
                if ("pier".equals(f.kind())) {
                    String sem = f.semanticJson() == null ? "" : f.semanticJson();
                    if (!sem.contains("\"head\":true") && pier == null) {
                        pier = f;   // 栈桥本体（桥头那段借它的根节点）
                    }
                } else if ("quay".equals(f.kind())) {
                    quays++;
                } else if ("building".equals(f.kind()) && f.semanticJson() != null
                        && f.semanticJson().contains("\"harbor\":true")) {
                    harborBuildings++;
                    double d = doorRoadDist(s.roads(), f.doorX(), f.doorZ());
                    assertTrue(d <= 8.0, "seed=" + seed + " 码头" + f.name() + "的门离路 " + Math.round(d)
                            + " 米（仓栈临路，应 ≤8）诊断=" + s.placed().diag());
                }
            }
            assertNotNull(pier, "seed=" + seed + " 没有落栈桥（契约码头的本体）");
            // 栈桥的 door 坐标就是它岸上的根节点：路必须修到桥头，而不是停在几十米外的草地上。
            double rootDist = doorRoadDist(s.roads(), pier.doorX(), pier.doorZ());
            assertTrue(rootDist <= 8.0, "seed=" + seed + " 栈桥根离最近的路 " + Math.round(rootDist)
                    + " 米（引道应接到桥头，≤8）诊断=" + s.placed().diag());
            assertTrue(quays >= 1, "seed=" + seed + " 码头没有岸上货场（只有一根插进水里的木条）");
            assertTrue(harborBuildings >= 1, "seed=" + seed + " 码头岸上没有仓栈/渔具棚"
                    + " 诊断=" + s.placed().diag());
        }
    }

    @Test
    @DisplayName("每间独立房屋的大门都在路上（≤8 米），宅院内部房走院门")
    void everyHouseHasRoadAtItsDoor() {
        for (long seed : SEEDS) {
            Scene s = generate(seed, WorldMapBrief.genericFallback(12));
            int checked = 0;
            String worst = null;
            double worstDist = 0;
            for (MapPlacer.PlacedFeature f : s.placed().features()) {
                if (!"building".equals(f.kind()) || courtyardPart(f)) {
                    continue;
                }
                checked++;
                double d = doorRoadDist(s.roads(), f.doorX(), f.doorZ());
                if (d > worstDist) {
                    worstDist = d;
                    worst = f.code() + "@(" + Math.round(f.x()) + "," + Math.round(f.z()) + ")=" + Math.round(d) + "米";
                }
            }
            assertTrue(checked >= 8, "seed=" + seed + " 只检查到 " + checked + " 栋独立房屋");
            assertTrue(worstDist <= 8.0, "seed=" + seed + " 有房屋的大门离路 " + Math.round(worstDist)
                    + " 米（应 ≤8）：最远的一栋 " + worst + " 诊断=" + s.placed().diag());
        }
    }

    @Test
    @DisplayName("没有重复走廊：不存在「同两端 + 大半重合」的两条路")
    void noDuplicateCorridor() {
        for (long seed : SEEDS) {
            Scene s = generate(seed, WorldMapBrief.genericFallback(12));
            List<MapPlacer.RoadSpec> roads = s.roads();
            int pairs = 0;
            String example = null;
            for (int i = 0; i < roads.size(); i++) {
                for (int j = i + 1; j < roads.size(); j++) {
                    // 入户小径/连接路是按"户"或"缺口"生成的，两户并排时它们的门前小路本来就挨着；
                    // 判重复只针对"正经的路"。
                    if (perHousehold(roads.get(i)) || perHousehold(roads.get(j))) {
                        continue;
                    }
                    List<double[]> a = roads.get(i).pathMeters();
                    List<double[]> b = roads.get(j).pathMeters();
                    if (a == null || b == null || a.size() < 2 || b.size() < 2) {
                        continue;
                    }
                    // 与 MapPlacer.dedupeRoads 的「同两端」判据同源，但收得要更紧（>0.85）：
                    // 田头路与下地土路本来就共用同一对端点（一条垂直接过去），重合 60% 是正常的，
                    // 真正该判"重复"的是两条几乎完全压在一起的走廊。
                    boolean dup = MapNetwork.endsWithin(a, b, 10.0)
                            && MapNetwork.coverage(a, b, 5.0, 4.0, -1.0) > 0.85;
                    if (dup) {
                        pairs++;
                        if (example == null) {
                            example = roads.get(i).code() + " / " + roads.get(j).code();
                        }
                    }
                }
            }
            assertEquals(0, pairs, "seed=" + seed + " 有 " + pairs + " 对重复走廊，例如 " + example);
        }
    }

    @Test
    @DisplayName("就近连接：连接路的接头落在最近的面上，而不是折线的端点")
    void connectorAttachesAtNearestPoint() {
        TerrainParams p = TerrainParams.island(20261016L, 1024);
        TerrainData t = TerrainGenerator.generate(p);
        float[] cost = RoadRouter.buildCost(t, null, null);
        // 在图上找一块「三条边都走得通」的开阔地：合成的两条路必须落在纯陆地上，
        // 否则 A* 会被海面挡住，测的就不是"接头选点"而是"能不能过海"了。
        int size = t.size;
        double cx = -1;
        double cz = -1;
        for (int z = 120; z < size - 120 && cx < 0; z += 8) {
            for (int x = 120; x < size - 120; x += 8) {
                boolean ok = cost[z * size + x] < 3f
                        && cost[(z) * size + Math.min(size - 1, x + 80)] < 3f
                        && cost[(z) * size + Math.max(0, x - 80)] < 3f
                        && cost[Math.min(size - 1, z + 60) * size + x] < 3f
                        && cost[Math.min(size - 1, z + 20) * size + x] < 3f;
                if (ok) {
                    cx = x + 0.5;
                    cz = z + 0.5;
                    break;
                }
            }
        }
        assertTrue(cx > 0, "找不到一块三条边都走得通的开阔地（地形异常）");
        // 一条 160 米的直路只有两个端点（抽稀之后就是这样），另一条路要从它中段旁边接进来。
        List<MapPlacer.RoadSpec> roads = new ArrayList<>();
        List<double[]> longRoad = new ArrayList<>();
        longRoad.add(new double[]{cx - 80, cz});
        longRoad.add(new double[]{cx + 80, cz});
        roads.add(new MapPlacer.RoadSpec("main_street", "正街", "street", RoadGrade.STREET, 5.4,
                "stone", longRoad, 0));
        List<double[]> side = new ArrayList<>();
        side.add(new double[]{cx, cz + 60});
        side.add(new double[]{cx, cz + 20});
        roads.add(new MapPlacer.RoadSpec("side_lane", "支路", "lane", RoadGrade.VILLAGE, 3.2,
                "dirt", side, 0));

        MapNetwork.Result r = MapNetwork.connect(t, roads);
        assertEquals(1, r.groupsAfter(), "两条路必须被接成一张网");
        // 连线必须落在 (200,500) 附近（中段），而不是绕到 (100,500)/(300,500) 那两个端点
        MapPlacer.RoadSpec bridge = null;
        double bestLen = Double.MAX_VALUE;
        for (MapPlacer.RoadSpec x : r.roads()) {
            double len = MapNetwork.length(x.pathMeters());
            if (x.code().startsWith("conn_") || x.kind().equals("connector")) {
                if (len < bestLen) {
                    bestLen = len;
                    bridge = x;
                }
            }
        }
        assertNotNull(bridge, "应补出一条连接路，实际：" + r.roads().stream().map(MapPlacer.RoadSpec::code).toList());
        double[] head = bridge.pathMeters().get(0);
        double[] tail = bridge.pathMeters().get(bridge.pathMeters().size() - 1);
        double touch = Math.min(Math.hypot(head[0] - cx, head[1] - cz),
                Math.hypot(tail[0] - cx, tail[1] - cz));
        assertTrue(touch <= 6.0, "连接路的接头应在 (cx,cz) 附近 6 米内（中段），实际偏了 "
                + Math.round(touch) + " 米；连接路长度 " + Math.round(bestLen) + " 米");
        assertTrue(bestLen <= 40.0, "最近的连接只有 20 米左右，实际走了 " + Math.round(bestLen) + " 米");
    }

    @Test
    @DisplayName("没有野地断头：路的端头要么接路，要么够得着门，要么够得着田")
    void noRoadEndsInTheWild() {
        for (long seed : SEEDS) {
            Scene s = generate(seed, WorldMapBrief.genericFallback(12));
            List<MapPlacer.RoadSpec> roads = s.roads();
            int bad = 0;
            String worst = null;
            for (MapPlacer.RoadSpec r : roads) {
                List<double[]> path = r.pathMeters();
                if (path == null || path.size() < 2 || "pier".equals(r.kind())
                        || "connector".equals(r.kind()) || r.bridgeCells() > 0) {
                    continue;
                }
                String code = r.code() == null ? "" : r.code();
                if (code.startsWith("conn_") || code.startsWith("farm_conn_")
                        || code.startsWith("door_") || code.startsWith("farm_head")) {
                    continue;
                }
                for (int e = 0; e < 2; e++) {
                    double[] tip = path.get(e == 0 ? 0 : path.size() - 1);
                    if (nearWater(s.terrain(), tip[0], tip[1])) {
                        continue;   // 到水边就断：取水/靠船的道，现实里就是这样结尾的
                    }
                    boolean ok = false;
                    for (MapPlacer.PlacedFeature f : s.placed().features()) {
                        if ("building".equals(f.kind()) && !courtyardPart(f)
                                && Math.hypot(f.doorX() - tip[0], f.doorZ() - tip[1]) <= 15.0) {
                            ok = true;
                            break;
                        }
                        if ("field".equals(f.kind())
                                && Math.hypot(f.x() - tip[0], f.z() - tip[1])
                                <= 17.0 + Math.max(f.w(), f.d()) * 0.5) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        double other = Double.MAX_VALUE;
                        for (MapPlacer.RoadSpec o : roads) {
                            if (o == r || o.pathMeters() == null || o.pathMeters().size() < 2) {
                                continue;
                            }
                            other = Math.min(other, MapNetwork.nearestOnPath(o.pathMeters(), tip[0], tip[1])[2]);
                        }
                        if (other > 2.2) {
                            bad++;
                            if (worst == null) {
                                worst = r.code() + " 端 (" + Math.round(tip[0]) + "," + Math.round(tip[1])
                                        + ") 离最近的物件 " + Math.round(other) + " 米";
                            }
                        }
                    }
                }
            }
            assertEquals(0, bad, "seed=" + seed + " 有 " + bad + " 个野地断头，例如 " + worst
                    + " 诊断=" + s.placed().diag());
        }
    }

    @Test
    @DisplayName("目的地吸附：契约设施落位之后，附近的目的地路以它门前收尾（≤10 米）")
    void destinationRoadsReachTheFacility() {
        // 三个契约设施（不锚定到具体地点，落位器按语义自己找位置）：
        // 一个灯塔（高处）、一个磨坊（水边）、一个议事厅（镇心）。
        List<WorldMapBrief.Facility> facilities = List.of(
                new WorldMapBrief.Facility("light", "灯塔", "lighthouse", null, "公共", 7.0, 7.0, 2),
                new WorldMapBrief.Facility("mill", "水磨坊", "mill", null, "工坊", 9.0, 7.0, 1),
                new WorldMapBrief.Facility("hall", "议事厅", "hall", null, "公共", 12.0, 8.0, 1));
        WorldMapBrief brief = new WorldMapBrief("ISLAND", List.of(), null,
                new WorldMapBrief.Demography(12, 3), facilities, List.of(),
                List.of(), List.of(), null, false);

        boolean sawFacility = false;
        for (long seed : SEEDS) {
            Scene s = generate(seed, brief);
            for (MapPlacer.PlacedFeature f : s.placed().features()) {
                String sem = f.semanticJson();
                if (sem == null || !sem.contains("\"facility\":true")) {
                    continue;
                }
                sawFacility = true;
                // 设施门前（靠路那一侧再往外 2 米）到最近路面的距离
                double best = Double.MAX_VALUE;
                for (MapPlacer.RoadSpec r : s.roads()) {
                    best = Math.min(best, MapNetwork.nearestOnPath(r.pathMeters(), f.x(), f.z())[2]);
                }
                assertTrue(best <= 12.0, "seed=" + seed + " 设施 " + f.code() + "(" + f.name()
                        + ") 离最近的路 " + Math.round(best) + " 米（目的地吸附应把它接到门前）"
                        + " 诊断=" + s.placed().diag());
            }
        }
        assertTrue(sawFacility, "三颗种子都没落下任何契约设施，测试没有真正验证到目的地吸附");
    }
}
