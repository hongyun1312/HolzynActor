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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网格 A* 的四条硬门：不穿水/不穿房、沿路优先、不可达要说清、同输入同输出。
 *
 * <p>为什么这四条值得单独钉住：寻路错了不会崩，只会<b>看起来有点像</b>——
 * 人从湖面直着走过去、从墙里穿出来、所有人抄一条草地斜线。这类 bug 在图上要盯着看很久才发现，
 * 而单测里用一张 64×64 的手工地图，两秒就能证伪。</p>
 */
class GridAStarTest {

    /** 测试图边长。 */
    private static final int SIZE = 64;

    /** 空地代价场（全草地）。 */
    private static CostField flat() {
        return CostField.build(grid(new int[0][]), null, null);
    }

    /** 造一张只有指定墙格的地形。 */
    private static NavGrid grid(int[][] waterCells) {
        int n = SIZE * SIZE;
        boolean[] water = new boolean[n];
        boolean[] forest = new boolean[n];
        byte[] slope = new byte[n];
        short[] elev = new short[n];
        for (int[] c : waterCells) {
            water[c[1] * SIZE + c[0]] = true;
        }
        return new NavGrid(SIZE, 0.5, 1.0, 42L, water, slope, elev, forest);
    }

    /** 造一条 L 形路（横 10..60 在 z=10，纵 z=10..60 在 x=60）。 */
    private static boolean[] lRoad() {
        boolean[] road = new boolean[SIZE * SIZE];
        for (int x = 10; x <= 60; x++) {
            road[10 * SIZE + x] = true;
        }
        for (int z = 10; z <= 60; z++) {
            road[z * SIZE + 60] = true;
        }
        return road;
    }

    /** 采样路径，算「落在路上」的长度占比。 */
    private static double roadRatio(CostField field, List<double[]> pts) {
        double total = 0;
        double onRoad = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.ceil(len));
            for (int s = 0; s < steps; s++) {
                double t = (s + 0.5) / steps;
                double x = a[0] + (b[0] - a[0]) * t;
                double z = a[1] + (b[1] - a[1]) * t;
                total += len / steps;
                if (field.isRoadAtMeters(x, z)) {
                    onRoad += len / steps;
                }
            }
        }
        return total <= 0 ? 0 : onRoad / total;
    }

    @Test
    @DisplayName("水面 = 不可通行：路径必须绕开，绝不穿水")
    void neverCrossesWater() {
        int[][] water = new int[40][];
        for (int z = 0; z < 40; z++) {
            water[z] = new int[]{32, z};
        }
        CostField field = CostField.build(grid(water), null, null);
        NavPath p = new GridAStar(field).find(5, 5, 60, 60);
        assertTrue(p.reachable(), "隔着一条水带也必须能绕过去");
        for (double[] pt : p.points()) {
            int cx = field.grid().cellOf(pt[0]);
            int cz = field.grid().cellOf(pt[1]);
            assertFalse(field.grid().isWater(cx, cz), "路径点落在水里：" + cx + "," + cz);
        }
    }

    @Test
    @DisplayName("建筑 = 不可通行：围成一圈时目标不可达，返回空路径而不是乱穿")
    void unreachableReturnsNone() {
        java.util.List<int[]> wall = new java.util.ArrayList<>();
        for (int i = 22; i <= 42; i++) {
            wall.add(new int[]{i, 22});
            wall.add(new int[]{i, 42});
            wall.add(new int[]{22, i});
            wall.add(new int[]{42, i});
        }
        CostField field = CostField.build(grid(wall.toArray(new int[0][])), null, null);
        NavPath p = new GridAStar(field).find(10, 10, 32, 32);
        assertFalse(p.reachable(), "被围死的目标不能给出路径");
        assertEquals(0, p.points().size());
    }

    @Test
    @DisplayName("沿路优先：L 形路存在时，路径 90% 以上的长度落在路面")
    void prefersRoads() {
        CostField field = CostField.build(grid(new int[0][]), lRoad(), null);
        NavPath p = new GridAStar(field).find(12, 12, 58, 58);
        assertTrue(p.reachable());
        double ratio = roadRatio(field, p.points());
        assertTrue(ratio >= 0.9, "沿路率不足（" + ratio + "），A* 在抄草地斜线");
    }

    @Test
    @DisplayName("确定性：同输入两次寻路结果逐点相同")
    void deterministic() {
        CostField field = CostField.build(grid(new int[][]{{30, 30}, {31, 30}, {30, 31}}), lRoad(), null);
        GridAStar a = new GridAStar(field);
        NavPath p1 = a.find(5, 40, 55, 50);
        NavPath p2 = a.find(5, 40, 55, 50);
        assertEquals(p1.points().size(), p2.points().size());
        for (int i = 0; i < p1.points().size(); i++) {
            assertEquals(p1.points().get(i)[0], p2.points().get(i)[0], 1e-9);
            assertEquals(p1.points().get(i)[1], p2.points().get(i)[1], 1e-9);
        }
    }

    @Test
    @DisplayName("折线取点：按弧长求点，超出总长钳到终点")
    void pointAtClamps() {
        List<double[]> pts = List.of(new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 10});
        double[] mid = GridAStar.pointAt(pts, 15);
        assertEquals(10.0, mid[0], 1e-9);
        assertEquals(5.0, mid[1], 1e-9);
        double[] end = GridAStar.pointAt(pts, 999);
        assertEquals(10.0, end[0], 1e-9);
        assertEquals(10.0, end[1], 1e-9);
    }

    @Test
    @DisplayName("起终点落在墙里时先吸附到最近可站格")
    void snapsBlockedEndpoints() {
        // 只在水里埋一小段（不是整列，否则整张图会被切成两半，测的就不是吸附了）
        int[][] water = new int[9][];
        for (int i = 0; i < 9; i++) {
            water[i] = new int[]{10, 6 + i};
        }
        CostField field = CostField.build(grid(water), null, null);
        NavPath p = new GridAStar(field).find(10.2, 10.2, 40, 40);
        assertNotNull(p);
        assertTrue(p.reachable(), "起点在水中应吸附到岸边而不是判死");
    }
}
