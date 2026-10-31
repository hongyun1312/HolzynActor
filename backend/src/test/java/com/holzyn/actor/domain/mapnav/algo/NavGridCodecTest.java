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
 * 导航栅格编解码：逐格无损、坏文件返回 null、缩略位图按块多数投票。
 *
 * <p>为什么「无损」是硬要求：这份数据是算法输入，不是给人看的图。一格水被抹掉，
 * 表现就是「NPC 从湖面直着走过去」——而且只在那一个位置、那一个角色身上发生，
 * 极难复现。所以 roundtrip 必须逐格断言，不做抽样。</p>
 */
class NavGridCodecTest {

    private static final int SIZE = 64;

    /** 造一张有规律的栅格（斜向水带 + 坡度梯度 + 一片林）。 */
    private static NavGrid sample() {
        int n = SIZE * SIZE;
        boolean[] water = new boolean[n];
        boolean[] forest = new boolean[n];
        byte[] slope = new byte[n];
        short[] elev = new short[n];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                water[i] = (x + z) % 17 == 0;
                forest[i] = x > 40 && z < 20 && (x * z) % 3 == 0;
                slope[i] = (byte) ((x * 4 + z) % 256);
                elev[i] = (short) ((z - 32) * 10);
            }
        }
        return new NavGrid(SIZE, 0.42, 1.0, 20261030L, water, slope, elev, forest);
    }

    @Test
    @DisplayName("编码 → 解码：水/林/坡/海拔逐格相等")
    void roundTripIsLossless() {
        NavGrid src = sample();
        byte[] bytes = NavGridCodec.encode(src);
        assertEquals(SIZE * SIZE / 8 * 2 + SIZE * SIZE * 3 + 36, bytes.length);
        NavGrid back = NavGridCodec.decode(bytes);
        assertNotNull(back);
        assertEquals(SIZE, back.size);
        assertEquals(src.seaLevel, back.seaLevel, 1e-12);
        assertEquals(src.seed, back.seed);
        for (int i = 0; i < SIZE * SIZE; i++) {
            assertEquals(src.water[i], back.water[i], "水格 " + i);
            assertEquals(src.forest[i], back.forest[i], "林格 " + i);
            assertEquals(src.slope[i], back.slope[i], "坡度 " + i);
            assertEquals(src.elevation[i], back.elevation[i], "海拔 " + i);
        }
    }

    @Test
    @DisplayName("坏数据返回 null：不抛异常、不返回半张图")
    void corruptReturnsNull() {
        assertNull(NavGridCodec.decode(new byte[]{1, 2, 3}));
        byte[] junk = new byte[200];
        assertNull(NavGridCodec.decode(junk), "魔数不对必须拒绝");
        byte[] ok = NavGridCodec.encode(sample());
        byte[] truncated = java.util.Arrays.copyOf(ok, ok.length - 500);
        assertNull(NavGridCodec.decode(truncated), "截断文件必须拒绝而不是给出半张图");
    }

    @Test
    @DisplayName("缩略位图：按块多数投票降采样，长度可算、可解出平面格")
    void maskDownsample() {
        int n = SIZE * SIZE;
        boolean[] src = new boolean[n];
        // 左半水、右半陆
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                src[z * SIZE + x] = x < SIZE / 2;
            }
        }
        String b64 = NavGridCodec.encodeMask(src, SIZE, 8, true);
        assertFalse(b64.isEmpty());
        assertTrue(NavGridCodec.maskSize(SIZE, 8) >= 8);
        byte[] packed = java.util.Base64.getDecoder().decode(b64);
        int outSize = NavGridCodec.maskSize(SIZE, 8);
        assertEquals((outSize * outSize + 7) / 8, packed.length);
        // 第一列是水，最后一列是陆
        int leftBit = (packed[0] >> 0) & 1;
        int rightBit = (packed[(outSize - 1) >> 3] >> ((outSize - 1) & 7)) & 1;
        assertEquals(1, leftBit, "左半应是水");
        assertEquals(0, rightBit, "右半应是陆");
    }

    @Test
    @DisplayName("从 TerrainData 切栅格：水面来自 waterCell（含内陆湖），不是 height<=seaLevel")
    void fromTerrainUsesWaterCell() {
        com.holzyn.actor.domain.mapgen.algo.TerrainData t =
                new com.holzyn.actor.domain.mapgen.algo.TerrainData(16, 0.4);
        // 默认高度 0 会低于海平面 → 先把整张图抬到陆地，再单独造一个湖
        java.util.Arrays.fill(t.height, 0.9f);
        t.biome[5 * 16 + 5] = com.holzyn.actor.domain.mapgen.algo.Biomes.LAKE;
        NavGrid g = NavGrid.fromTerrain(t, 1L);
        assertTrue(g.isWater(5, 5), "内陆湖必须判成水");
        assertFalse(g.isWater(6, 6));
    }
}
