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

package com.holzyn.actor.domain.mapnav.algo;

import java.util.ArrayDeque;

/**
 * 运行期行走代价场（静态地形 + 路面 + 建筑占位，1024² 逐格 float）。
 *
 * <p><b>为什么沿路不另建路网图</b>：道路已经是「代价更低的地面」——把路格代价乘 0.35，
 * 最短代价路径自然贴着路走，还顺手解决了「院门口到主街这几米怎么接」的问题（不需要接驳段）。
 * 真正的验收不是「有没有用图」，而是<b>沿路率</b>（设计文档 §3.3 A3：路径长度 90% 落在路格 2 m 内）。</p>
 *
 * <p><b>权重与生成期同源</b>：建筑 240/格、外扩 2.6 m 再 200/格，取自
 * {@code MapPlacer.costWithClearance()} 的同一套量级——生成期「不压房」和运行期「不穿房」
 * 必须是同一把尺子，否则会出现「图上房子明明挪开了、NPC 还绕着旧位置走」。</p>
 */
public final class CostField {

    /** 路面代价系数：踩在路上的格子只花 35% 的力气。 */
    public static final float ROAD_FACTOR = 0.35f;

    /** 坡度权重（代价 = 1 + 8 × slope²，与 S3 路网 A* 同量级）。 */
    public static final float SLOPE_WEIGHT = 8f;

    /** 林下权重（能走，但比草地费劲）。 */
    public static final float FOREST_WEIGHT = 4f;

    /** 建筑本体代价（等价于不可通行，但保留数值便于 A* 绕行而不是「无解」）。 */
    public static final float BUILDING_COST = 240f;

    /** 建筑外扩净空附加代价。 */
    public static final float CLEARANCE_COST = 200f;

    /** 净空半径（米）——与 {@code MapPlacer} 的 2.6 m 一致。 */
    public static final double CLEARANCE_METERS = 2.6;

    /** 不可通行格的代价（用大数而不是 Infinity：避免 A* 里 NaN 传播）。 */
    public static final float BLOCK = 1e9f;

    /** 边长。 */
    private final int size;

    /** 水 / 建筑 = 不可站。 */
    private final boolean[] blocked;

    /** 逐格代价。 */
    private final float[] cost;

    /** 路面标记（沿路率统计与「门口接路」判断用）。 */
    private final boolean[] road;

    private final NavGrid grid;

    private CostField(NavGrid grid, boolean[] blocked, float[] cost, boolean[] road) {
        this.grid = grid;
        this.size = grid.size;
        this.blocked = blocked;
        this.cost = cost;
        this.road = road;
    }

    /**
     * 组装代价场。
     *
     * @param grid     导航栅格（水/坡/林）
     * @param road     路面标记（可空 = 没有路）
     * @param occupied 建筑/围墙占位（可空 = 空地）；本方法会就地做 2.6 m 净空外扩
     * @return 代价场
     */
    public static CostField build(NavGrid grid, boolean[] road, boolean[] occupied) {
        int n = grid.size * grid.size;
        boolean[] blocked = new boolean[n];
        float[] cost = new float[n];
        boolean[] roadCells = road != null && road.length == n ? road : new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean water = grid.water[i];
            boolean occ = occupied != null && occupied.length == n && occupied[i];
            blocked[i] = water || occ;
            if (blocked[i]) {
                cost[i] = BLOCK;
                continue;
            }
            double slope = (grid.slope[i] & 0xFF) / 255.0;
            float c = (float) (1.0 + SLOPE_WEIGHT * slope * slope);
            if (grid.forest[i]) {
                c += FOREST_WEIGHT;
            }
            if (roadCells[i]) {
                c *= ROAD_FACTOR;
            }
            cost[i] = c;
        }
        // 建筑净空：外扩 2.6 m 加价（不做逐格距离变换，7×7 窗口足够且更好读）
        if (occupied != null && occupied.length == n) {
            int r = (int) Math.ceil(CLEARANCE_METERS);
            for (int z = 0; z < grid.size; z++) {
                for (int x = 0; x < grid.size; x++) {
                    int i = z * grid.size + x;
                    if (!occupied[i]) {
                        continue;
                    }
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (dx * dx + dz * dz > r * r) {
                                continue;
                            }
                            int nx = x + dx;
                            int nz = z + dz;
                            if (!grid.inside(nx, nz)) {
                                continue;
                            }
                            int j = nz * grid.size + nx;
                            if (!blocked[j] && occupied[j] == false) {
                                cost[j] += CLEARANCE_COST;
                            }
                        }
                    }
                }
            }
        }
        return new CostField(grid, blocked, cost, roadCells);
    }

    /** 边长。 */
    public int size() {
        return size;
    }

    /** 底层栅格（只读）。 */
    public NavGrid grid() {
        return grid;
    }

    /** 该格是否能站。 */
    public boolean walkable(int i) {
        return !blocked[i];
    }

    /** 该格是否能站（坐标）。 */
    public boolean walkable(int x, int z) {
        return grid.inside(x, z) && !blocked[z * size + x];
    }

    /** 该格代价。 */
    public float costAt(int i) {
        return cost[i];
    }

    /** 该格是否路面。 */
    public boolean isRoad(int i) {
        return road[i];
    }

    /** 该点（米）是否路面。 */
    public boolean isRoadAtMeters(double x, double z) {
        int cx = grid.cellOf(x);
        int cz = grid.cellOf(z);
        return road[cz * size + cx];
    }

    /**
     * 找最近的合法站立足点（起终点落在墙里/水里时用）。
     *
     * @param x         米
     * @param z         米
     * @param maxRadius 最大搜索半径（格）
     * @return 格坐标 {x,z}；找不到返回 null
     */
    public int[] nearestWalkable(double x, double z, int maxRadius) {
        int cx = grid.cellOf(x);
        int cz = grid.cellOf(z);
        if (walkable(cx, cz)) {
            return new int[]{cx, cz};
        }
        // 由内向外逐圈扫描：第一圈命中就是欧氏最近（同圈内取几何距离最小的那个）
        int max = Math.max(1, maxRadius);
        for (int r = 1; r <= max; r++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (!walkable(nx, nz)) {
                        continue;
                    }
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = nz * size + nx;
                    }
                }
            }
            if (best >= 0) {
                return new int[]{best % size, best / size};
            }
        }
        return null;
    }

    /**
     * 两点之间是否「直线可走」（超覆盖 Bresenham，遇 blocked 即失败）。
     *
     * <p>用于路径拉直：格子 A* 出来的是锯齿阶梯，拉直后才像人走的路。</p>
     *
     * @param x0 起点格 X
     * @param z0 起点格 Z
     * @param x1 终点格 X
     * @param z1 终点格 Z
     * @return true=全程可走
     */
    public boolean lineOfSight(int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = Integer.compare(x1, x0);
        int sz = Integer.compare(z1, z0);
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            if (!walkable(x, z)) {
                return false;
            }
            if (x == x1 && z == z1) {
                return true;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    /**
     * 从某格出发的连通体积（只用于测试/诊断：确认目的地可达性）。
     *
     * @param startX 起点 X
     * @param startZ 起点 Z
     * @param limit  最多数多少格（防全图遍历）
     * @return 可达格数
     */
    public int reachableCount(int startX, int startZ, int limit) {
        if (!walkable(startX, startZ)) {
            return 0;
        }
        boolean[] seen = new boolean[size * size];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int start = startZ * size + startX;
        queue.add(start);
        seen[start] = true;
        int count = 0;
        while (!queue.isEmpty() && count < limit) {
            int i = queue.poll();
            count++;
            int x = i % size;
            int z = i / size;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!walkable(nx, nz)) {
                        continue;
                    }
                    int j = nz * size + nx;
                    if (!seen[j]) {
                        seen[j] = true;
                        queue.add(j);
                    }
                }
            }
        }
        return count;
    }
}
