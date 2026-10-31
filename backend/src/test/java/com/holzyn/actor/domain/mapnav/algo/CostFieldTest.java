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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代价场的语义单测：水不可站、路更便宜、房子连净空一起加价、吸附与视线。
 *
 * <p>为什么逐条钉住权重：沿路率、绕房、门口可达这三个体验问题，最后都收敛到「哪个格子贵多少」。
 * 权重被谁顺手改一下，图上就是「人开始穿院子」或者「所有人绕大圈」，而测试是唯一能在 2 秒内
 * 告诉你「有人动了尺子」的地方。</p>
 */
class CostFieldTest {

    private static final int SIZE = 32;

    private static NavGrid grid(boolean[] water, byte[] slope, boolean[] forest) {
        int n = SIZE * SIZE;
        return new NavGrid(SIZE, 0.5, 1.0, 7L,
                water == null ? new boolean[n] : water,
                slope == null ? new byte[n] : slope,
                new short[n],
                forest == null ? new boolean[n] : forest);
    }

    @Test
    @DisplayName("水格不可站，代价为 BLOCK")
    void waterIsBlocked() {
        boolean[] water = new boolean[SIZE * SIZE];
        water[5 * SIZE + 6] = true;
        CostField f = CostField.build(grid(water, null, null), null, null);
        assertFalse(f.walkable(6, 5));
        assertEquals(CostField.BLOCK, f.costAt(5 * SIZE + 6), 1e-3);
    }

    @Test
    @DisplayName("路格代价约为草地 35%")
    void roadIsCheaper() {
        boolean[] road = new boolean[SIZE * SIZE];
        road[3 * SIZE + 3] = true;
        CostField f = CostField.build(grid(null, null, null), road, null);
        float grass = f.costAt(4 * SIZE + 4);
        float onRoad = f.costAt(3 * SIZE + 3);
        assertEquals(CostField.ROAD_FACTOR, onRoad, 1e-4);
        assertEquals(1.0f, grass, 1e-4);
        assertTrue(f.isRoadAtMeters(3.5, 3.5));
    }

    @Test
    @DisplayName("建筑本体不可站，外扩 2.6 m 内的邻格加净空代价")
    void buildingClearance() {
        boolean[] occupied = new boolean[SIZE * SIZE];
        occupied[10 * SIZE + 10] = true;
        CostField f = CostField.build(grid(null, null, null), null, occupied);
        assertFalse(f.walkable(10, 10), "建筑本体不可站");
        float near = f.costAt(10 * SIZE + 12);
        float far = f.costAt(10 * SIZE + 20);
        assertTrue(near >= 1 + CostField.CLEARANCE_COST, "净空内应加价，实际 " + near);
        assertEquals(1.0f, far, 1e-4, "净空外不受影响");
    }

    @Test
    @DisplayName("林下加价、陡坡加价（能走但更贵）")
    void forestAndSlopeCostMore() {
        boolean[] forest = new boolean[SIZE * SIZE];
        forest[2 * SIZE + 2] = true;
        byte[] slope = new byte[SIZE * SIZE];
        slope[3 * SIZE + 3] = (byte) 255;
        CostField f = CostField.build(grid(null, slope, forest), null, null);
        assertTrue(f.costAt(2 * SIZE + 2) > 4.9f);
        assertTrue(f.costAt(3 * SIZE + 3) >= 8.0f);
    }

    @Test
    @DisplayName("最近可站格：墙里吸附到相邻格；整块水返回 null")
    void nearestWalkable() {
        boolean[] occupied = new boolean[SIZE * SIZE];
        occupied[8 * SIZE + 8] = true;
        CostField f = CostField.build(grid(null, null, null), null, occupied);
        int[] snapped = f.nearestWalkable(8.4, 8.4, 4);
        assertNotNull(snapped);
        assertTrue(f.walkable(snapped[0], snapped[1]));
        assertTrue(Math.abs(snapped[0] - 8) <= 1 && Math.abs(snapped[1] - 8) <= 1);

        boolean[] allWater = new boolean[SIZE * SIZE];
        java.util.Arrays.fill(allWater, true);
        CostField sea = CostField.build(grid(allWater, null, null), null, null);
        assertNull(sea.nearestWalkable(3, 3, 6));
    }

    @Test
    @DisplayName("视线：隔水不可视，空地可视")
    void lineOfSight() {
        boolean[] water = new boolean[SIZE * SIZE];
        water[10 * SIZE + 10] = true;
        CostField f = CostField.build(grid(water, null, null), null, null);
        assertFalse(f.lineOfSight(5, 10, 15, 10));
        assertTrue(f.lineOfSight(5, 20, 15, 20));
    }
}
