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

import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真地形端到端：S1 地形 → nav.bin 编解码 → 代价场 → 寻路。
 *
 * <p>为什么要有这一条（而不是只有手搓网格的用例）：手工地图里"水"是一条整齐的直线，
 * 真实地形的水是海湾、河口、内陆湖——{@code waterCell} 把湖判成水这件事，
 * 只有在真地形上跑一次才不会退化。这条测试跑的是和线上同一条链路，
 * 唯一的区别是没接数据库。</p>
 *
 * <p>用 512² 而不是 1024²：同样的地理结构，生成快 4 倍，测试不至于拖慢全量套件。</p>
 */
class MapPathRealismTest {

    /** 测试用边长（格）。 */
    private static final int SIZE = 512;

    private static NavGrid terrainNav() {
        TerrainParams p = new TerrainParams();
        p.size = SIZE;
        p.seed = 20261030L;
        TerrainData t = TerrainGenerator.generate(p);
        return NavGrid.fromTerrain(t, p.seed);
    }

    /** 收集所有可站格（陆地、坡度不极端）。 */
    private static List<int[]> landCells(NavGrid nav) {
        List<int[]> out = new ArrayList<>();
        for (int z = 4; z < nav.size - 4; z += 3) {
            for (int x = 4; x < nav.size - 4; x += 3) {
                if (!nav.isWater(x, z) && nav.slopeAt(x, z) <= 0.6) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("真地形：nav.bin 无损往返 + 两点可达 + 路径不穿水")
    void routesAcrossRealTerrain() {
        NavGrid nav = terrainNav();
        byte[] bytes = NavGridCodec.encode(nav);
        NavGrid back = NavGridCodec.decode(bytes);
        assertNotNull(back, "nav.bin 必须能解回来");
        assertEquals(nav.size, back.size);
        int waterMismatch = 0;
        for (int i = 0; i < nav.size * nav.size; i++) {
            if (nav.water[i] != back.water[i]) {
                waterMismatch++;
            }
        }
        assertEquals(0, waterMismatch, "水陆必须逐格无损（一格之差就是「人能走进湖里」）");

        List<int[]> land = landCells(back);
        assertTrue(land.size() > 1000, "真地形上可站格太少（" + land.size() + "），地形或水陆判定异常");

        CostField field = CostField.build(back, null, null);
        GridAStar astar = new GridAStar(field);

        // 挑几对相距较远的陆地点，至少要有一对能走通，且路径逐点不落水
        int reachable = 0;
        NavPath good = null;
        for (int[] a : land) {
            if (a[0] > SIZE / 3 || a[1] > SIZE / 3) {
                continue;
            }
            for (int[] b : land) {
                if (b[0] < SIZE * 2 / 3 && b[1] < SIZE * 2 / 3) {
                    continue;
                }
                NavPath p = astar.find(a[0] + 0.5, a[1] + 0.5, b[0] + 0.5, b[1] + 0.5);
                if (p.reachable()) {
                    reachable++;
                    if (good == null && p.lengthM() > 100) {
                        good = p;
                    }
                }
                if (reachable >= 2) {
                    break;
                }
            }
            if (reachable >= 2) {
                break;
            }
        }
        assertTrue(reachable >= 1, "真地形上跨半个岛必须能走通（陆地被水切碎了？）");
        assertNotNull(good, "需要一条像样的长路径来验收「不穿水」");
        for (double[] pt : good.points()) {
            int cx = back.cellOf(pt[0]);
            int cz = back.cellOf(pt[1]);
            assertFalse(back.isWater(cx, cz), "路径穿水：" + cx + "," + cz);
        }
        double straight = Math.hypot(
                good.points().get(good.points().size() - 1)[0] - good.points().get(0)[0],
                good.points().get(good.points().size() - 1)[1] - good.points().get(0)[1]);
        assertTrue(good.lengthM() < straight * 4 + 50,
                "路径绕得太离谱（" + good.lengthM() + " vs 直线 " + straight + "），代价场可能有问题");
    }

    @Test
    @DisplayName("真地形：低成本路面能显著缩短代价（沿路吸引生效的前提）")
    void roadCellsAreCheaperOnRealTerrain() {
        NavGrid nav = terrainNav();
        // 人为把一条直线标成路：代价必须显著低于旁边的野地，否则「沿路」根本不可能发生
        boolean[] road = new boolean[nav.size * nav.size];
        int row = nav.size / 2;
        for (int x = 20; x < nav.size - 20; x++) {
            if (!nav.isWater(x, row)) {
                road[row * nav.size + x] = true;
            }
        }
        CostField field = CostField.build(nav, road, null);
        int onRoad = -1;
        for (int x = 20; x < nav.size - 20; x++) {
            if (road[row * nav.size + x]) {
                onRoad = x;
                break;
            }
        }
        assertTrue(onRoad > 0, "该行没有可标路的陆地块");
        float c = field.costAt(row * nav.size + onRoad);
        // 真地形上基准代价不是 1（有坡度/林下），所以断言的是「同一个格：路面 = 基准 × 0.35」
        double slope = (nav.slope[row * nav.size + onRoad] & 0xFF) / 255.0;
        double base = 1.0 + CostField.SLOPE_WEIGHT * slope * slope
                + (nav.forest[row * nav.size + onRoad] ? CostField.FOREST_WEIGHT : 0);
        assertEquals((float) (base * CostField.ROAD_FACTOR), c, 1e-3,
                "路格代价必须等于基准 × ROAD_FACTOR（沿路吸引的前提）");
    }
}
