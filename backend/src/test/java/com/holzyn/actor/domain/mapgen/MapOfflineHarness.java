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

import com.holzyn.actor.domain.mapgen.algo.MapCompositePainter;
import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainPainter;
import com.holzyn.actor.domain.mapgen.algo.TerrainSemantics;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import com.holzyn.actor.domain.mapgen.service.MapRoadPlanService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线全链路出图（开发期专用，不进产物）——<b>与线上 S1~S5 同一套算法、ai-enabled=false</b>。
 *
 * <p>为什么需要它：线上出图要起后端 + 等 3 次 AI（每次上百秒），调一次形态要等好几分钟；
 * 而形态问题（街道摆不摆、田成不成片、海岸像不像岛）本来就跟 AI 无关——参数与拓扑都是确定的。
 * 这个 main 用 {@link MapProfile#fallback} + {@link WorldMapBrief#genericFallback} 把 S0/S0b
 * 锁成确定性兜底，跑完 S1→S5 直接落 PNG，一次十几秒。</p>
 *
 * <p>用法：{@code java -cp <cp> MapOfflineHarness <outDir> <seed> [size] [ppm]}。</p>
 */
public final class MapOfflineHarness {

    private MapOfflineHarness() {
    }

    /**
     * 入口。
     *
     * @param args outDir seed [size] [ppm]
     * @throws Exception 写图失败
     */
    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "tmp/offline-preview";
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261016L;
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        double ppm = args.length > 3 ? Double.parseDouble(args[3]) : 2.0;

        long t0 = System.currentTimeMillis();
        MapGenProperties props = new MapGenProperties();
        props.setAiEnabled(false);
        props.setSize(size);
        props.setMetersPerCell(1.0);

        // ---------- S0 / S0b（确定性兜底，与线上同款） ----------
        MapProfile profile = MapProfile.fallback(seed, size);
        WorldMapBrief brief = WorldMapBrief.genericFallback(12);
        TerrainSemantics.apply(profile.params(), brief);

        // ---------- S1 地形 ----------
        TerrainData terrain = TerrainGenerator.generate(profile.params());
        long t1 = System.currentTimeMillis();
        System.out.printf("[S1] %d ms 陆地=%.3f 可建=%.3f 河=%d 湖=%d 最高=%.0fm%n",
                t1 - t0, terrain.landFraction, terrain.buildableFraction,
                terrain.rivers.size(), terrain.lakes.size(),
                terrain.maxHeight * terrain.heightToMeters);

        // ---------- 选址 ----------
        Map<String, MapSites.Site> sites = MapSites.resolve(terrain, profile.params());
        Map<String, MapSites.Site> allSites = profile.mergeLandmarks(sites);

        // ---------- S2 资产（确定性兜底目录） ----------
        MapAssetCatalog catalog = MapAssetCatalog.fallback();
        List<MapPlacer.AssetSpec> specs = new ArrayList<>();
        catalog.types().forEach(t -> specs.add(t.toPlacerSpec()));

        // ---------- S3 路网（骨架 + 兜底拓扑，与线上同一条路径） ----------
        float[] cost = RoadRouter.buildCost(terrain, null, null);
        double townRadius = MapPlacer.townRadiusMeters(terrain, profile.params(),
                MapPlacer.PlacementBudget.from(brief));
        MapRoadPlanService roadService = new MapRoadPlanService(null, null, props);
        MapRoadPlanService.Plan plan = roadService.build(1L, terrain, profile, catalog,
                allSites, seed, cost, townRadius);
        long t3 = System.currentTimeMillis();
        System.out.printf("[S3] %d ms 路=%d 条（骨架+目的）townRadius=%.0fm%n",
                t3 - t1, plan.roads().size(), townRadius);

        // ---------- S4 落位 ----------
        MapPlacer.Result placed = MapPlacer.place(terrain, profile.params(), seed * 31 + 7,
                specs, plan.roads(), allSites, brief);
        long t4 = System.currentTimeMillis();
        int buildings = 0;
        int fields = 0;
        int walls = 0;
        for (MapPlacer.PlacedFeature f : placed.features()) {
            if ("building".equals(f.kind())) {
                buildings++;
            } else if ("field".equals(f.kind())) {
                fields++;
            } else if ("wall".equals(f.kind())) {
                walls++;
            }
        }
        System.out.printf("[S4] %d ms 建筑=%d 田=%d 院墙=%d 路=%d 分量=%d%n",
                t4 - t3, buildings, fields, walls, placed.roads().size(),
                MapNetwork.groupCount(placed.roads()));
        System.out.println("     诊断：" + placed.diag());

        // ---------- S5 合成 ----------
        int out = (int) Math.round(size * ppm);
        var img = MapCompositePainter.render(terrain, placed.roads(), placed.features(), out, ppm);
        File dir = new File(outDir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        MapCompositePainter.write(img, new File(dir, "composite.png"));
        MapCompositePainter.write(TerrainPainter.renderHeight(terrain), new File(dir, "terrain-height.png"));
        MapCompositePainter.write(TerrainPainter.render(terrain, ppm), new File(dir, "terrain.png"));
        MapCompositePainter.write(TerrainPainter.renderBiomes(terrain), new File(dir, "biome.png"));
        MapSites.Site core = sites.get(MapSites.CORE);
        if (core != null) {
            MapCompositePainter.writeCrop(img, new File(dir, "zoom-town.png"),
                    (int) Math.round((core.x() - 200) * ppm), (int) Math.round((core.z() - 170) * ppm),
                    (int) Math.round(400 * ppm), (int) Math.round(340 * ppm), 1);
        }
        writeStats(dir, terrain, placed, buildings, fields, walls, townRadius);
        writeRoads(dir, placed.roads(), placed.features());
        writeFeatures(dir, placed.features(), placed.roads());
        System.out.printf("[S5] 输出 %s 共 %d ms%n", dir.getAbsolutePath(),
                System.currentTimeMillis() - t0);
    }

    /**
     * 落一份路网几何（code/等级/宽度/折线），外加"碎不碎"的三项度量。
     *
     * <p>为什么单独落：观感问题（悬空端、平行重复、一截一截的短线）没法从汇总数字看出来，
     * 但可以量化——悬空端数、短支线数、覆盖率重复对数。<b>改路网算法时先看这三个数，
     * 再去看图</b>，能避免"改完看着顺眼其实更碎了"。</p>
     */
    private static void writeRoads(File dir, List<MapPlacer.RoadSpec> roads,
                                   List<MapPlacer.PlacedFeature> feats) throws IOException {
        List<Object> rows = new ArrayList<>();
        List<List<double[]>> paths = new ArrayList<>();
        int dangling = 0;
        int shortSpur = 0;
        int dupPairs = 0;
        for (MapPlacer.RoadSpec r : roads) {
            List<double[]> p = r.pathMeters();
            if (p == null || p.size() < 2) {
                continue;
            }
            paths.add(p);
            double len = 0;
            for (int i = 1; i < p.size(); i++) {
                len += Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[1] - p.get(i - 1)[1]);
            }
            if (len < 40) {
                shortSpur++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("kind", r.kind());
            m.put("grade", r.grade());
            m.put("bridgeCells", r.bridgeCells());
            m.put("width", r.width());
            m.put("length", Math.round(len * 10) / 10.0);
            List<double[]> pts = new ArrayList<>();
            for (double[] q : p) {
                pts.add(new double[]{Math.round(q[0] * 10) / 10.0, Math.round(q[1] * 10) / 10.0});
            }
            m.put("path", pts);
            rows.add(m);
        }
        for (int i = 0; i < paths.size(); i++) {
            List<double[]> a = paths.get(i);
            for (int e = 0; e < 2; e++) {
                double[] pt = e == 0 ? a.get(0) : a.get(a.size() - 1);
                // 「悬空端」的定义要说清楚：真正刺眼的是<b>领不到任何东西</b>的断头——
                // 一头扎进林子/草地、既不到人家也不到田。而下面这些端头是正常的：
                //   · 入户小径终止在人家门口（门在 8 米内）；
                //   · 路终止在院落/禾场边（房子中心 12 米内）；
                //   · 田间小路终止在最后一垄田（田块 15 米内）。
                // 旧口径只判「离别的路多远」，把这三类全算成缺陷，读数虚高、也没法当判据用。
                boolean serves = false;
                for (MapPlacer.PlacedFeature f : feats) {
                    double d = Math.hypot(f.x() - pt[0], f.z() - pt[1]);
                    if ("building".equals(f.kind())) {
                        if (d <= 12.0 || Math.hypot(f.doorX() - pt[0], f.doorZ() - pt[1]) <= 8.0) {
                            serves = true;
                            break;
                        }
                    } else if ("pier".equals(f.kind()) || "quay".equals(f.kind())) {
                        // 码头口也是一个落点：栈桥根/货场边 12 米内算"有对象"
                        // （与线上 MapPlacer.endServes 的码头判据同口径）
                        if (Math.hypot(f.doorX() - pt[0], f.doorZ() - pt[1]) <= 12.0
                                || d <= 12.0 + Math.max(f.w(), f.d()) * 0.5) {
                            serves = true;
                            break;
                        }
                    } else if ("field".equals(f.kind())
                            && d <= 15.0 + Math.max(f.w(), f.d()) * 0.5) {
                        serves = true;
                        break;
                    }
                }
                if (serves) {
                    continue;
                }
                double best = Double.MAX_VALUE;
                for (int j = 0; j < paths.size(); j++) {
                    if (j == i) {
                        continue;
                    }
                    best = Math.min(best, MapNetwork.nearestOnPath(paths.get(j), pt[0], pt[1])[2]);
                }
                if (best > 2.2) {
                    dangling++;
                }
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                // 带方向判：垂直相交的进道不算重复路（与线上 MapPlacer.dedupeRoads 同口径）
                if (MapNetwork.coverage(paths.get(i), paths.get(j), 5.0, 4.0, 0.86) > 0.45) {
                    dupPairs++;
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("roads", rows);
        root.put("danglingEnds", dangling);
        root.put("shortSpurs", shortSpur);
        root.put("dupPairs", dupPairs);
        Files.writeString(new File(dir, "roads.json").toPath(),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /**
     * 落一份建筑/田块几何（含大门坐标与「门口到最近一条路」的距离）。
     *
     * <p>为什么要单独落这一份：用户的两个硬要求——「每一间房屋都有道路连接」和
     * 「连接要走最近的地方」——都没法从汇总数字看出来。有了门到路的距离，
     * 就能直接量化「最远的那户人家离路多少米」「有几户没接路」，而不只是看图猜。</p>
     */
    private static void writeFeatures(File dir, List<MapPlacer.PlacedFeature> feats,
                                      List<MapPlacer.RoadSpec> roads) throws IOException {
        List<List<double[]>> lines = new ArrayList<>();
        for (MapPlacer.RoadSpec r : roads) {
            if (r.pathMeters() != null && r.pathMeters().size() >= 2) {
                lines.add(r.pathMeters());
            }
        }
        List<Object> rows = new ArrayList<>();
        int unserved = 0;
        double worst = 0;
        for (MapPlacer.PlacedFeature f : feats) {
            // 水工件（栈桥/货场/桥）也落一份：检查"路有没有连到码头""码头有没有货场"
            // 这类问题，只看建筑和田是不够的——码头是把两者连起来的那一端。
            boolean water = "pier".equals(f.kind()) || "quay".equals(f.kind()) || "bridge".equals(f.kind());
            if (!"building".equals(f.kind()) && !"field".equals(f.kind()) && !water) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (List<double[]> line : lines) {
                best = Math.min(best, MapNetwork.nearestOnPath(line, f.doorX(), f.doorZ())[2]);
            }
            if ("building".equals(f.kind())) {
                // 只有「临街的门」才有"门口必须挨着路"的要求：院内的正屋/厢房/后院是从宅门进出的，
                // 它们的门到路十几米是正常的。判据必须和线上 MapPlacer.auditDoors 同口径，
                // 否则这份离线快照的读数会和 stats 里的「未接路房」对不上。
                if (isStreetAccess(f)) {
                    worst = Math.max(worst, best);
                    if (best > 8.0) {
                        unserved++;
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("kind", f.kind());
            m.put("category", f.category());
            m.put("x", Math.round(f.x() * 10) / 10.0);
            m.put("z", Math.round(f.z() * 10) / 10.0);
            m.put("w", Math.round(f.w() * 10) / 10.0);
            m.put("d", Math.round(f.d() * 10) / 10.0);
            m.put("rot", Math.round(f.rot() * 10) / 10.0);
            m.put("doorX", Math.round(f.doorX() * 10) / 10.0);
            m.put("doorZ", Math.round(f.doorZ() * 10) / 10.0);
            m.put("doorRoadDist", Math.round(best * 10) / 10.0);
            m.put("streetAccess", isStreetAccess(f));
            m.put("semantic", f.semanticJson());
            rows.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("features", rows);
        root.put("worstDoorRoadDist", Math.round(worst * 10) / 10.0);
        root.put("unservedBuildings", unserved);
        Files.writeString(new File(dir, "features.json").toPath(),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /** 落一份统计（回归比对用：数值 + 诊断计数）。 */
    private static void writeStats(File dir, TerrainData t, MapPlacer.Result placed,
                                   int buildings, int fields, int walls, double townRadius)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("landFraction", round(t.landFraction));
        m.put("buildableFraction", round(t.buildableFraction));
        m.put("rivers", t.rivers.size());
        m.put("lakes", t.lakes.size());
        m.put("maxHeightMeters", Math.round(t.maxHeight * t.heightToMeters));
        m.put("townRadius", Math.round(townRadius));
        m.put("buildings", buildings);
        m.put("fields", fields);
        m.put("walls", walls);
        m.put("roads", placed.roads().size());
        m.put("roadComponents", MapNetwork.groupCount(placed.roads()));
        double meters = 0;
        double maxRoad = 0;
        for (MapPlacer.RoadSpec r : placed.roads()) {
            double len = 0;
            List<double[]> p = r.pathMeters();
            for (int i = 1; i < p.size(); i++) {
                len += Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[1] - p.get(i - 1)[1]);
            }
            meters += len;
            maxRoad = Math.max(maxRoad, len);
        }
        m.put("roadMeters", Math.round(meters));
        m.put("longestRoadMeters", Math.round(maxRoad));
        m.put("diagnostics", placed.diag());
        Files.writeString(new File(dir, "stats.json").toPath(),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(m), StandardCharsets.UTF_8);
    }

    /**
     * 这栋建筑是不是「从街上进门」的那一类（口径与 {@code MapPlacer.auditDoors} 一致）。
     *
     * <p>院内后院、附属房、厢房都是从宅门进出的，门到路十几米属于正常形态；
     * 只有临街/独立房屋才要求门口 ≤8 米有路。离线快照按同一口径统计，
     * 才能直接和 {@code stats.diagnostics} 里的读数对齐。</p>
     *
     * @param f 建筑要素
     * @return true=按临街门统计
     */
    private static boolean isStreetAccess(MapPlacer.PlacedFeature f) {
        String sem = f.semanticJson();
        if (sem == null) {
            return true;
        }
        if (sem.contains("\"rear\":true") || sem.contains("\"annex\":true")) {
            return false;
        }
        if (sem.contains("\"compound\":true")) {
            return sem.contains("\"part\":2");   // 只有宅门本身是从街进出的
        }
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
