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

package com.holzyn.actor.domain.mapgen.algo;

import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2「地形语义化」守门测试：契约定下的方位，地形上必须真的长成那个样子。
 *
 * <p>为什么必须有这个测试：地形生成的参数链很长（AI 契约 → TerrainSemantics → TerrainParams →
 * TerrainGenerator），任何一环把「方位角」解释错（比如用了旋转后的局部坐标系、把罗盘角当成
 * 屏幕角）都会让「凹面朝西的月牙湾」变成「东边一个湾」——而图上看起来依然是个岛，光靠肉眼
 * 几乎发现不了。所以这里用<b>固定契约</b>把几何断言死：</p>
 * <ul>
 *   <li>湾的方位（270°=西）陆地半径必须显著小于对侧（90°=东）——这才叫「凹面朝西」；</li>
 *   <li>岬的方位（0°=北）陆地半径不小于基准半径——岬是伸出去的，不是缩进来的；</li>
 *   <li>陆地不许顶到图边（否则岛会被画布裁断，出现一条直线的海岸）；</li>
 *   <li>陆地内部不许有「低于海面的坑」（会渲染成蓝斑或陨石坑圈）。</li>
 * </ul>
 *
 * <p>同时把出图落到 {@code target/terrain-preview/semantic-terrain.png}，改参数可直接看图。</p>
 */
class TerrainSemanticsTest {

    private static final int SIZE = 1024;

    private static final Path OUT = Path.of("target", "terrain-preview");

    /**
     * 构造一份「通用海岛」契约——注意：这里<b>没有任何世界观内容</b>，
     * 只有方位/半径/偏置这些通用语义（真实运行时由 S0b 从世界数据里抽）。
     *
     * @param bayBearing   湾的方位（罗盘角）
     * @param settlementBearing 聚落方位
     * @return 契约
     */
    private static WorldMapBrief brief(double bayBearing, double settlementBearing) {
        List<WorldMapBrief.Region> regions = List.of(
                new WorldMapBrief.Region("cape", "北岬", "CAPE", 0, 0.55, 0.95, 0.45, 0.15, 0.30),
                new WorldMapBrief.Region("hills", "北丘", "HILLS", 340, 0.15, 0.80, 0.60, 0.75, 0.10),
                new WorldMapBrief.Region("forest", "东林", "FOREST", 90, 0.20, 0.90, -0.20, 0.85, 0.60),
                new WorldMapBrief.Region("mudflat", "南滩", "MUDFLAT", 180, 0.40, 1.00, -0.50, 0.05, 0.80),
                new WorldMapBrief.Region("bay", "西湾", "BAY", bayBearing, 0.50, 1.00, -0.60, 0.05, 0.90),
                new WorldMapBrief.Region("settle", "镇", "SETTLEMENT", settlementBearing,
                        0.10, 0.55, 0.15, 0.25, 0.20),
                new WorldMapBrief.Region("field", "田", "FIELD", 140, 0.35, 0.70, 0.25, 0.10, 0.30),
                new WorldMapBrief.Region("creek", "溪", "CREEK", 60, 0.30, 0.85, -0.10, 0.50, 0.70));
        return new WorldMapBrief("ISLAND", regions,
                new WorldMapBrief.Settlement(settlementBearing, 150, 0.18),
                new WorldMapBrief.Demography(12, 3), List.of(), List.of(),
                List.of(), List.of(), null, true);
    }

    @Test
    @DisplayName("契约里「凹面朝西的湾」必须真的把西侧海岸拉进岛内")
    void bayFacesWest() throws IOException {
        TerrainParams p = TerrainParams.island(20261021L, SIZE);
        int n = TerrainSemantics.apply(p, brief(270, 200));
        TerrainData t = TerrainGenerator.generate(p);

        double r0 = SIZE * p.islandRadiusRatio;
        double west = coastRadius(t, 270);
        double east = coastRadius(t, 90);
        double north = coastRadius(t, 0);

        System.out.printf("[terrain-semantics] 区域=%d r0=%.0f 海岸半径：西=%.0f 东=%.0f 北=%.0f%n",
                n, r0, west, east, north);
        System.out.println("[terrain-semantics] 陆地占比=" + t.landFraction
                + " 群系=" + topBiomes(t, 6));

        writePreview(t);

        assertTrue(n == 8, "8 个契约区域都应写进地形参数，实际 " + n);
        assertTrue(p.coastRegionDriven, "给了湾/岬，海岸线必须由契约驱动");
        assertTrue(west < east * 0.72,
                "湾所在的西侧海岸没有明显内凹（西=" + Math.round(west) + " 东=" + Math.round(east) + "）");
        assertTrue(north > west * 1.15,
                "北侧岬角没有伸出去（北=" + Math.round(north) + " 西=" + Math.round(west) + "）");
    }

    @Test
    @DisplayName("岛屿不许被画布裁断，陆地内部不许有低于海面的坑")
    void islandStaysInsideCanvasWithoutPits() {
        TerrainParams p = TerrainParams.island(20261021L, SIZE);
        TerrainSemantics.apply(p, brief(270, 200));
        TerrainData t = TerrainGenerator.generate(p);

        int size = t.size;
        int borderLand = 0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (x > 3 && x < size - 4 && z > 3 && z < size - 4) {
                    continue;
                }
                if (t.mask[z * size + x] > 0.5f) {
                    borderLand++;
                }
            }
        }
        int pits = 0;
        for (int i = 0; i < size * size; i++) {
            if (t.mask[i] > 0.5f && t.height[i] < t.seaLevel) {
                pits++;
            }
        }
        // 诊断：被软填到「海面地板」的格子数。数量大说明侵蚀确实啃到了下限，
        // 图上会出现一块平坦低地（沿岸/盆地），要结合高度剖面看是不是伪影。
        float floor = (float) (t.seaLevel + 0.004);
        int atFloor = 0;
        for (int i = 0; i < size * size; i++) {
            if (t.mask[i] > 0.5f && t.height[i] <= floor + 1e-4f) {
                atFloor++;
            }
        }
        StringBuilder prof = new StringBuilder();
        for (int x = 380; x <= 700; x += 20) {
            prof.append(Math.round(t.height[512 * size + x] * 1000)).append(' ');
        }
        System.out.println("[terrain-semantics] 贴地板格=" + atFloor + " z=512 高度剖面(‰)："
                + prof.toString().trim());
        ascii(t, 430, 400, 690, 640);
        System.out.println("[terrain-semantics] 图边陆地格=" + borderLand + " 内陆坑格=" + pits);
        assertTrue(borderLand == 0, "陆地顶到了图边，岛会被画布裁断（" + borderLand + " 格）");
        assertTrue(pits == 0, "陆地内部还有低于海面的坑，会渲染成蓝斑/坑状伪影（" + pits + " 格）");
    }

    /**
     * 某方位上的陆地半径（格）。
     *
     * @param t     地形
     * @param bearingDeg 罗盘角（0=北）
     * @return 最外侧陆地格到图心的距离
     */
    private static double coastRadius(TerrainData t, double bearingDeg) {
        double c = t.size * 0.5;
        double ang = Math.toRadians(bearingDeg - 90);
        double last = 0;
        for (double r = 4; r < t.size * 0.5; r += 2) {
            int x = (int) Math.round(c + Math.cos(ang) * r);
            int z = (int) Math.round(c + Math.sin(ang) * r);
            if (x < 0 || z < 0 || x >= t.size || z >= t.size) {
                break;
            }
            if (t.mask[z * t.size + x] > 0.5f) {
                last = r;
            }
        }
        return last;
    }

    /** 群系占比前三/前 n（诊断用）。 */
    private static String topBiomes(TerrainData t, int n) {
        int[] count = new int[Biomes.COUNT];
        int land = 0;
        for (byte b : t.biome) {
            if (Biomes.isSea(b)) {
                continue;
            }
            count[b & 0xFF]++;
            land++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < count.length; i++) {
                if (count[i] > 0 && (best < 0 || count[i] > count[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            sb.append(Biomes.name((byte) best)).append('=')
                    .append(Math.round(count[best] * 1000.0 / Math.max(1, land)) / 10.0).append("% ");
            count[best] = 0;
        }
        return sb.toString().trim();
    }

    /**
     * 把一块区域的高度场打成 ASCII 图（诊断环形/台阶类伪影用）。
     *
     * <p>光看渲染图很难判断「这块到底是地形还是渲染错」——把高度按 10 级字符画出来，
     * 台阶、环形坎、孤峰立刻就能看出来。</p>
     *
     * @param t 地形
     * @param x0 左边界
     * @param z0 上边界
     * @param x1 右边界
     * @param z1 下边界
     */
    private static void ascii(TerrainData t, int x0, int z0, int x1, int z1) {
        String ramp = " .:-=+*#%@";
        double sea = t.seaLevel;
        StringBuilder sb = new StringBuilder("\n[ascii] x=" + x0 + ".." + x1 + " z=" + z0 + ".." + z1 + "\n");
        for (int z = z0; z < z1; z += 10) {
            for (int x = x0; x < x1; x += 6) {
                int i = z * t.size + x;
                float h = t.height[i];
                if (h <= sea) {
                    sb.append('~');
                } else {
                    int lv = (int) Math.round((h - sea) / Math.max(1e-6, 0.30) * (ramp.length() - 1));
                    sb.append(ramp.charAt(Math.max(0, Math.min(ramp.length() - 1, lv))));
                }
            }
            sb.append('\n');
        }
        System.out.print(sb);
    }

    private static void writePreview(TerrainData t) {        try {
            BufferedImage img = TerrainPainter.render(t);
            Files.createDirectories(OUT);
            ImageIO.write(img, "png", OUT.resolve("semantic-terrain.png").toFile());
            System.out.println("[terrain-semantics] 预览图：" + OUT.resolve("semantic-terrain.png").toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[terrain-semantics] 预览图写出失败（不影响断言）：" + e.getMessage());
        }
    }
}
