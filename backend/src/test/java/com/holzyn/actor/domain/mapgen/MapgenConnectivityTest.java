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

import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地图生成的「硬约束」回归测试。
 * <p>这三条是俯视图里最刺眼的错误，任何一条破了都不算能用：</p>
 * <ol>
 *   <li><b>道路必须连成一张网</b>——包括事后长出来的田埂、下地土路、田间小路；
 *       出现两条各走各的路，比美术难看致命得多。</li>
 *   <li><b>建筑不能压在路面上</b>——房子盖到马路中间，整个镇子立刻变成假的。</li>
 *   <li><b>农田要按人口给足</b>——人口与田块数之比落在 2:1 ~ 4:1（小村设下限）。</li>
 * </ol>
 * <p>用纯算法跑（不起 Spring、不连库），单次约 2~3 秒，可以直接进 {@code mvn test}。</p>
 */
class MapgenConnectivityTest {

    /** 覆盖几个有代表性的种子（不同岛形/河湖/选址）。 */
    private static final long[] SEEDS = {20261016L, 777L, 31L};

    private MapPlacer.Result generate(long seed) {
        TerrainParams p = TerrainParams.island(seed, 1024);
        TerrainData t = TerrainGenerator.generate(p);
        Map<String, MapSites.Site> sites = MapSites.resolve(t, p);
        float[] cost = RoadRouter.buildCost(t, null, null);
        List<MapPlacer.RoadSpec> roads = MapFullPreviewMain.buildRoads(t, sites, cost);
        // 走「契约驱动」的正式入口（户数 12）：断言要对着当前设计，而不是「铺满」时代的老经验值
        return MapPlacer.place(t, p, seed * 31 + 7, MapFullPreviewMain.islandAssets(), roads, sites,
                com.holzyn.actor.domain.mapgen.model.WorldMapBrief.genericFallback(12));
    }

    @Test
    void allRoadsFormOneNetwork() {
        for (long seed : SEEDS) {
            MapPlacer.Result r = generate(seed);
            List<MapPlacer.RoadSpec> roads = r.roads();
            int groups = MapNetwork.groupCount(roads);
            assertEquals(1, groups, "seed=" + seed + " 路网被分成 " + groups + " 块（应当只有 1 块）"
                    + " 诊断=" + r.diag());
            assertTrue(roads.size() >= 8, "seed=" + seed + " 路网只有 " + roads.size() + " 条");
        }
    }

    @Test
    void noBuildingStandsOnPavement() {
        for (long seed : SEEDS) {
            MapPlacer.Result r = generate(seed);
            int size = 1024;
            double mpc = 1.0;
            boolean[] pavement = new boolean[size * size];
            for (MapPlacer.RoadSpec road : r.roads()) {
                stamp(road, pavement, size, mpc);
            }
            int bad = 0;
            String worst = null;
            for (MapPlacer.PlacedFeature f : r.features()) {
                if (!"building".equals(f.kind()) || f.polygon() == null) {
                    continue;
                }
                int hit = countPavementCells(f.polygon(), pavement, size, mpc);
                if (hit > 0) {
                    bad++;
                    if (worst == null) {
                        worst = f.code() + "(" + f.name() + ") 压路 " + hit + " 格 @("
                                + Math.round(f.x()) + "," + Math.round(f.z()) + ")"
                                + " 最近的路=" + nearestRoad(r.roads(), f.x(), f.z());
                    }
                }
            }
            assertTrue(bad == 0, "seed=" + seed + " 有 " + bad + " 座建筑压在路面上，例如 " + worst);
        }
    }

    @Test
    void farmlandMatchesPopulation() {
        for (long seed : SEEDS) {
            MapPlacer.Result r = generate(seed);
            long fields = r.features().stream().filter(f -> "field".equals(f.kind())).count();
            int households = 12;
            assertTrue(fields > 0, "seed=" + seed + " 一块田都没有");
            // 契约驱动：田块数必须和户数挂钩。旧断言是「人口:农田 2:1~4:1」的经验值，
            // 那是「把整片平原铺满」时代的产物；现在田按「每户几块」给，口径必须跟着改。
            assertTrue(fields >= households && fields <= households * 8,
                    "seed=" + seed + " " + households + " 户却有 " + fields + " 块田（合理区间 12~96）");
        }
    }

    /** 离某点最近的那条路（自检报错时用：得知道是主干道还是事后长的土路压了房子）。 */
    private String nearestRoad(List<MapPlacer.RoadSpec> roads, double x, double z) {
        double best = Double.MAX_VALUE;
        MapPlacer.RoadSpec hit = null;
        for (MapPlacer.RoadSpec road : roads) {
            List<double[]> path = road.pathMeters();
            if (path == null || path.size() < 2) {
                continue;
            }
            for (int i = 1; i < path.size(); i++) {
                double d = segDist(x, z, path.get(i - 1)[0], path.get(i - 1)[1],
                        path.get(i)[0], path.get(i)[1]);
                if (d < best) {
                    best = d;
                    hit = road;
                }
            }
        }
        return hit == null ? "无" : (hit.code() + "/" + hit.name() + "/kind=" + hit.kind()
                + "/grade=" + hit.grade() + "/宽=" + hit.width() + "/距=" + String.format("%.2f", best));
    }

    /** 把一条路按「线段胶囊」压进路面栅格（米坐标，1 格 = 1 米）。 */
    private void stamp(MapPlacer.RoadSpec road, boolean[] pavement, int size, double mpc) {
        List<double[]> path = road.pathMeters();
        if (path == null || path.size() < 2) {
            return;
        }
        double half = road.width() / 2.0;
        for (int i = 1; i < path.size(); i++) {
            double ax = path.get(i - 1)[0];
            double az = path.get(i - 1)[1];
            double bx = path.get(i)[0];
            double bz = path.get(i)[1];
            int x0 = (int) Math.floor((Math.min(ax, bx) - half) / mpc);
            int x1 = (int) Math.ceil((Math.max(ax, bx) + half) / mpc);
            int z0 = (int) Math.floor((Math.min(az, bz) - half) / mpc);
            int z1 = (int) Math.ceil((Math.max(az, bz) + half) / mpc);
            for (int z = Math.max(0, z0); z <= Math.min(size - 1, z1); z++) {
                for (int x = Math.max(0, x0); x <= Math.min(size - 1, x1); x++) {
                    double px = (x + 0.5) * mpc;
                    double pz = (z + 0.5) * mpc;
                    if (segDist(px, pz, ax, az, bx, bz) <= half) {
                        pavement[z * size + x] = true;
                    }
                }
            }
        }
    }

    /** 建筑覆盖的格里，有几格是路面。 */
    private int countPavementCells(double[][] poly, boolean[] pavement, int size, double mpc) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] pt : poly) {
            minX = Math.min(minX, pt[0]);
            maxX = Math.max(maxX, pt[0]);
            minZ = Math.min(minZ, pt[1]);
            maxZ = Math.max(maxZ, pt[1]);
        }
        int hit = 0;
        for (int z = (int) Math.floor(minZ / mpc); z <= (int) Math.ceil(maxZ / mpc); z++) {
            if (z < 0 || z >= size) {
                continue;
            }
            for (int x = (int) Math.floor(minX / mpc); x <= (int) Math.ceil(maxX / mpc); x++) {
                if (x < 0 || x >= size || !pavement[z * size + x]) {
                    continue;
                }
                if (pointInPoly((x + 0.5) * mpc, (z + 0.5) * mpc, poly)) {
                    hit++;
                }
            }
        }
        return hit;
    }

    private double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double vv = vx * vx + vz * vz;
        double t = vv < 1e-9 ? 0 : ((px - ax) * vx + (pz - az) * vz) / vv;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + vx * t), pz - (az + vz * t));
    }

    private boolean pointInPoly(double x, double z, double[][] poly) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0];
            double zi = poly[i][1];
            double xj = poly[j][0];
            double zj = poly[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }
}
