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

package com.holzyn.actor.domain.mapgen;

import com.holzyn.actor.domain.mapgen.algo.MapCompositePainter;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainPainter;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路离线自检（S1 地形 → 选址 → 路网 → 落位 → 合成）。
 * <p>不依赖 Spring / 数据库：直接跑算法类，把结果画成 PNG 供人眼判断。
 * 用法：{@code java -cp ... MapFullPreviewMain <outDir> <size> <seed>}。</p>
 */
public final class MapFullPreviewMain {

    private MapFullPreviewMain() {
    }

    /**
     * 入口。
     *
     * @param args outDir size seed
     * @throws Exception 写图失败
     */
    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "tmp/map-preview";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20261016L;

        long t0 = System.currentTimeMillis();
        // ---------- S1 地形 ----------
        TerrainParams p = TerrainParams.island(seed, size);
        TerrainData t = TerrainGenerator.generate(p);
        long t1 = System.currentTimeMillis();
        System.out.printf("[S1] 地形 %d ms  陆地=%.3f 可建=%.3f 河=%d 湖=%d%n",
                t1 - t0, t.landFraction, t.buildableFraction, t.rivers.size(), t.lakes.size());

        // ---------- 选址 ----------
        Map<String, MapSites.Site> sites = MapSites.resolve(t, p);
        for (Map.Entry<String, MapSites.Site> e : sites.entrySet()) {
            MapSites.Site s = e.getValue();
            System.out.printf("   site %-16s (%7.1f, %7.1f) role=%s%n", e.getKey(), s.x(), s.z(), s.role());
        }

        // ---------- S2 资产库（离线版：模拟 AI 产出） ----------
        List<MapPlacer.AssetSpec> assets = islandAssets();

        // ---------- S3 路网 ----------
        float[] cost = RoadRouter.buildCost(t, null, null);
        List<MapPlacer.RoadSpec> roads = buildRoads(t, sites, cost);
        long t2 = System.currentTimeMillis();
        System.out.printf("[S3] 路网 %d ms：%d 条%n", t2 - t1, roads.size());

        // ---------- S4 落位 ----------
        MapPlacer.Result placed = MapPlacer.place(t, p, seed * 31 + 7, assets, roads, sites);
        long t3 = System.currentTimeMillis();
        System.out.printf("[S4] 落位 %d ms：组=%d 要素=%d 地块=%d%n",
                t3 - t2, placed.groups().size(), placed.features().size(), placed.parcels().size());
        int buildings = 0;
        for (MapPlacer.PlacedFeature f : placed.features()) {
            if ("building".equals(f.kind())) {
                buildings++;
            }
        }
        System.out.printf("     建筑=%d（民居类住人=%d）  路网=%d 条%n", buildings,
                placed.features().stream().filter(f -> f.residency() == 1).count(),
                placed.roads().size());
        System.out.println("     落位诊断：" + placed.diag());
        for (MapPlacer.PlacedFeature f : placed.features()) {
            if ("pier".equals(f.kind()) || "bridge".equals(f.kind()) || "wall".equals(f.kind())) {
                if ("wall".equals(f.kind())) {
                    continue;
                }
                System.out.printf("     %-6s (%7.1f,%7.1f) w=%.1f d=%.1f rot=%.0f%n",
                        f.kind(), f.x(), f.z(), f.w(), f.d(), f.rot());
            }
        }

        // ---------- S5 合成 ----------
        var img = MapCompositePainter.render(t, placed.roads(), placed.features(), size, 1.0);
        File dir = new File(outDir);
        dir.mkdirs();
        MapCompositePainter.write(img, new File(dir, "map-composite.png"));
        MapCompositePainter.write(TerrainPainter.render(t), new File(dir, "map-terrain.png"));
        MapCompositePainter.write(TerrainPainter.renderBiomes(t), new File(dir, "map-biome.png"));
        // 自检放大图：整图看着还行不代表贴街关系对，必须放大看
        MapSites.Site core = sites.get(MapSites.CORE);
        if (core != null) {
            MapCompositePainter.writeCrop(img, new File(dir, "zoom-town.png"),
                    (int) core.x() - 210, (int) core.z() - 180, 420, 360, 3);
        }
        // 自检放大图：农田成片、贴路、颜色——整图看不清这些
        for (MapPlacer.PlacedGroup grp : placed.groups()) {
            if ("field".equals(grp.kind())) {
                MapCompositePainter.writeCrop(img, new File(dir, "zoom-field.png"),
                        (int) grp.cx() - 110, (int) grp.cz() - 90, 220, 180, 5);
                break;
            }
        }
        for (MapPlacer.PlacedFeature f : placed.features()) {
            if ("plaza".equals(f.kind())) {
                MapCompositePainter.writeCrop(img, new File(dir, "zoom-plaza.png"),
                        (int) f.x() - 55, (int) f.z() - 45, 110, 90, 7);
                break;
            }
        }
        MapSites.Site gate = sites.get(MapSites.GATE);
        if (gate != null) {
            MapCompositePainter.writeCrop(img, new File(dir, "zoom-harbor.png"),
                    (int) gate.x() - 70, (int) gate.z() - 70, 140, 140, 7);
        }
        System.out.printf("[S5] 输出 %s  总耗时 %d ms%n", dir.getAbsolutePath(), System.currentTimeMillis() - t0);
    }

    // ==================== 路网（模拟 S3 AI 拓扑 + A* 走线） ====================

    static List<MapPlacer.RoadSpec> buildRoads(TerrainData t, Map<String, MapSites.Site> sites,
                                                       float[] cost) {
        List<MapPlacer.RoadSpec> out = new ArrayList<>();
        MapSites.Site core = sites.get(MapSites.CORE);
        if (core == null) {
            return out;
        }
        String[] resKeys = {"residential_n", "residential_e", "residential_s", "residential_w"};
        // 主干：码头 → 镇中心（门户路）
        MapSites.Site gate = sites.get(MapSites.GATE);
        if (gate != null) {
            out.add(route(t, cost, "main_street", "正街", "street", RoadGrade.STREET, 5.0, "stone",
                    gate, core, 0.35));
        }
        // 主干：镇中心 → 生产区（农田路）
        MapSites.Site farm = sites.get(MapSites.FARM);
        if (farm != null) {
            out.add(route(t, cost, "field_road", "田间大道", "path", RoadGrade.VILLAGE, 2.6, "dirt",
                    core, farm, 0.8));
        }
        // 巷道：中心 → 四个民居群
        int i = 0;
        for (String k : resKeys) {
            MapSites.Site s = sites.get(k);
            if (s == null) {
                continue;
            }
            out.add(route(t, cost, "lane_" + (++i), "巷子" + i, "lane", RoadGrade.VILLAGE, 3.0, "dirt",
                    core, s, 0.6));
        }
        // 巷道：河埠头 → 中心（洗衣服的路）
        MapSites.Site wf = sites.get(MapSites.WATERFRONT);
        if (wf != null) {
            out.add(route(t, cost, "lane_water", "埠头路", "lane", RoadGrade.VILLAGE, 2.6, "dirt", wf, core, 0.7));
        }
        // 主干：高地 → 中心（上看守所/灯塔的路；没有码头时的门户）
        MapSites.Site high = sites.get(MapSites.HIGH_GROUND);
        if (high != null && gate == null) {
            out.add(route(t, cost, "main_street", "正街", "street", RoadGrade.STREET, 4.5, "stone", high, core, 0.4));
        }
        // 巷道：工坊区
        MapSites.Site shop = sites.get(MapSites.WORKSHOP);
        if (shop != null) {
            out.add(route(t, cost, "lane_shop", "工坊巷", "lane", RoadGrade.VILLAGE, 3.0, "gravel", shop, core, 0.5));
        }
        // 去掉走不通的
        out.removeIf(r -> r.pathMeters().size() < 2);
        return out;
    }

    private static MapPlacer.RoadSpec route(TerrainData t, float[] cost, String code, String name,
                                           String kind, int grade, double width, String surface,
                                           MapSites.Site from, MapSites.Site to, double bend) {
        double mpc = t.metersPerCell;
        int sx = (int) Math.round(from.x() / mpc);
        int sz = (int) Math.round(from.z() / mpc);
        int gx = (int) Math.round(to.x() / mpc);
        int gz = (int) Math.round(to.z() / mpc);
        RoadRouter.Route r = RoadRouter.route(t, cost, sx, sz, gx, gz, bend);
        List<double[]> path = new ArrayList<>();
        if (r != null) {
            for (double[] pt : r.path()) {
                path.add(new double[]{pt[0] * mpc, pt[1] * mpc});
            }
        }
        return new MapPlacer.RoadSpec(code, name, kind, grade, width, surface, path,
                r == null ? 0 : r.bridgeCells());
    }

    // ==================== 资产库（模拟 S2 输出：青屿镇海岛小镇） ====================

    static List<MapPlacer.AssetSpec> islandAssets() {
        List<MapPlacer.AssetSpec> a = new ArrayList<>();
        // 民居
        a.add(house("house_fisher", "渔家小院", 9.0, 7.5, 1, 2, "#d9c9a8", "#8a5a3c", "gable", 16));
        a.add(house("house_kelp", "海带棚屋", 7.5, 6.5, 1, 1, "#c9b79a", "#6f7a52", "thatch", 14));
        a.add(house("house_stone", "石砌民居", 10.0, 8.0, 1, 2, "#b8b2a6", "#5f5c58", "hip", 14));
        a.add(house("house_timber", "木构瓦房", 9.5, 8.0, 2, 2, "#c8a882", "#5c5145", "gable", 12));
        // 商铺
        a.add(house("shop_grocery", "杂货铺", 7.2, 6.4, 1, 2, "#d2b98e", "#7a4a33", "gable", 10));
        a.add(house("shop_tea", "海边茶摊", 6.4, 5.6, 1, 1, "#d8c39a", "#6a4632", "shed", 8));
        a.add(house("shop_fish", "鱼行", 8.4, 7.0, 1, 2, "#c9b088", "#5a4a3a", "gable", 8));
        // 工坊
        a.add(house("work_net", "织网坊", 11.0, 8.0, 1, 1, "#c1a97f", "#6b5a3e", "gable", 6));
        a.add(house("work_salt", "盐作坊", 12.0, 9.0, 1, 1, "#bfb49c", "#5b5348", "flat", 5));
        // 公共
        a.add(house("pub_hall", "镇议事厅", 16.0, 11.0, 1, 2, "#cfc3aa", "#4e5a68", "hip", 3));
        a.add(house("pub_well", "井亭", 4.4, 4.4, 1, 1, "#c6b79a", "#5d6b52", "dome", 3));
        a.add(house("pub_school", "蒙学", 13.0, 9.0, 1, 1, "#d5c8ac", "#6a5240", "gable", 2));
        a.add(house("pub_lamp", "灯塔", 6.0, 6.0, 3, 4, "#e2e0d8", "#7a3f39", "spire", 1));
        // 生产
        a.add(kind("prod_granary", "谷仓", "生产", "building", 10.0, 7.0, "#c8b590", "#6a5a3c", "gable", 4));
        a.add(kind("prod_dry", "晒场棚", "生产", "building", 9.0, 7.0, "#bfae8c", "#7a6a4a", "shed", 4));
        a.add(kind("farm_plot", "田垄", "生产", "field", 30.0, 10.0, "#b6ab7a", "#9a8f5e", "flat", 4));
        a.add(kind("wood_lot", "林地", "自然", "forest", 40.0, 34.0, "#3f5c34", "#35502c", "flat", 3));
        // 水工
        a.add(kind("water_pier", "码头", "水工", "pier", 3.2, 14.0, "#8a6b4a", "#7a5c3e", "flat", 3));
        a.add(kind("water_boat", "船棚", "水工", "building", 8.0, 6.0, "#b79b78", "#6a5238", "shed", 2));
        return a;
    }

    private static MapPlacer.AssetSpec house(String code, String name, double w, double d,
                                             int f0, int f1, String wall, String roof,
                                             String shape, int weight) {
        return new MapPlacer.AssetSpec(code, name, "民居", "building", w, d, f0, f1,
                wall, roof, shape, "wood", weight);
    }

    private static MapPlacer.AssetSpec kind(String code, String name, String cat, String kind,
                                            double w, double d, String wall, String roof,
                                            String shape, int weight) {
        String category = switch (cat) {
            case "民居" -> "民居";
            case "商铺" -> "商铺";
            case "工坊" -> "工坊";
            case "公共" -> "公共";
            case "生产" -> "生产";
            default -> "水工";
        };
        return new MapPlacer.AssetSpec(code, name, category, kind, w, d, 1, 1,
                wall, roof, shape, "wood", weight);
    }

    /** 供 main 之外复用：输出统计。 */
    static Map<String, Object> stats(TerrainData t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("land", t.landFraction);
        m.put("buildable", t.buildableFraction);
        return m;
    }
}
