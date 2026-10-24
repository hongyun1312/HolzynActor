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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形"好不好看"的量化守门 + 离线出图工具。
 *
 * <p>为什么需要这个测试：地图观感的头号病症是「一整块绿抹布」——不是配色错，而是地表上
 * <b>除了草地什么都没有</b>（疏林/密林占比接近 0、没有林冠纹理、海岸线是一圈荧光青）。
 * 这类问题没法靠断言像素值守护，但可以守护<b>地表构成</b>：温带海岛应该有成片的林、成片的
 * 开阔草坡、一圈沙滩、少量岩石/湿地，而不是 90% 草地。</p>
 *
 * <p>同时把图落到 {@code target/terrain-preview/*.png}（含同图的高度晕渲与群系平涂），
 * 这样调植被参数不必每次跑 70 秒的整条管线再开前端——直接看这三张图判断。</p>
 */
class TerrainLookTest {

    /** 出图边长（与线上一致：1024 格 = 1 米/格 = 1 公里见方）。 */
    private static final int SIZE = 1024;

    /** 预览图输出目录（mvn 的 target 下，不污染仓库）。 */
    private static final Path OUT = Path.of("target", "terrain-preview");

    @Test
    @DisplayName("海岛地表构成：林/草/沙/岩都要有，不能是一整块绿地")
    void islandSurfaceHasVariety() throws IOException {
        TerrainParams p = TerrainParams.island(20261021L, SIZE);
        TerrainData t = TerrainGenerator.generate(p);

        Map<String, Double> census = census(t);
        System.out.println("[terrain-look] 陆地上各群系占比：" + census);
        System.out.println("[terrain-look] 陆地坡度分位（1.0=45°）：" + slopeStats(t));
        System.out.println("[terrain-look] 水系：" + waterStats(t));

        // 先出图再断言：断言失败时也要能看到图，否则调参只能盲猜
        BufferedImage terrain = TerrainPainter.render(t);
        BufferedImage biomes = TerrainPainter.renderBiomes(t);
        BufferedImage height = TerrainPainter.renderHeight(t);
        try {
            Files.createDirectories(OUT);
            ImageIO.write(terrain, "png", OUT.resolve("island-terrain.png").toFile());
            ImageIO.write(biomes, "png", OUT.resolve("island-biomes.png").toFile());
            ImageIO.write(height, "png", OUT.resolve("island-height.png").toFile());
            System.out.println("[terrain-look] 预览图已写出：" + OUT.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[terrain-look] 预览图写出失败（不影响断言）：" + e.getMessage());
        }

        double forest = share(census, "疏林", "密林");
        double open = share(census, "草地", "草甸");
        double beach = share(census, "沙滩");
        double rock = share(census, "岩石", "断崖");
        double farm = share(census, "农田");

        assertTrue(t.landFraction > 0.25 && t.landFraction < 0.75,
                "海岛陆地占比应适中（0.25~0.75），实际 " + round(t.landFraction));
        assertTrue(forest >= 0.18,
                "林地占比过低（地图会成一块绿抹布），实际 " + round(forest) + "：" + census);
        assertTrue(forest <= 0.72,
                "林地占比过高（岛会被树盖满、看不见草坡与农田），实际 " + round(forest) + "：" + census);
        assertTrue(open >= 0.08,
                "开阔草地/草甸太少（没有牧场与空地），实际 " + round(open) + "：" + census);
        assertTrue(beach >= 0.005, "沙滩几乎不存在（岸线会变成硬切边），实际 " + round(beach));
        assertTrue(farm >= 0.01, "聚落周边没有农田（小镇没有耕地），实际 " + round(farm));
        // 温带 96 米的小岛不该出现雪，也不该大面积裸岩。
        // 裸岩/断崖超过 6% 时，整座岛会有一道灰色"沥青带"（实测 7.3% 时观感极差），
        // 所以这里把上限压到 6%——真实海岛的裸岩是破碎的露头，不是连绵的板岩。
        assertTrue(rock <= 0.06, "裸岩/断崖过多（像采石场），实际 " + round(rock));
        assertTrue(!census.containsKey("雪地"), "低海拔海岛不该有雪线：" + census);
    }

    /** 陆地内各群系占比（与 MapGenOrchestrator.biomeCensus 同口径）。 */
    private static Map<String, Double> census(TerrainData t) {
        Map<String, Integer> count = new LinkedHashMap<>();
        int land = 0;
        for (byte b : t.biome) {
            if (Biomes.isSea(b)) {
                continue;
            }
            land++;
            count.merge(Biomes.name(b), 1, Integer::sum);
        }
        final int landCells = land;
        Map<String, Double> out = new LinkedHashMap<>();
        count.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.put(e.getKey(), e.getValue() / (double) Math.max(1, landCells)));
        return out;
    }

    /**
     * 陆地坡度分位数（诊断用）。
     *
     * <p>为什么要打印它：断崖/裸岩的阈值（0.72 / 0.92 / 0.95）全是硬编码的坡度值，而坡度分布
     * 随「起伏强度 × 海拔米数」整体平移——改一个参数，阈值对应的实际面积可能差 10 倍。
     * 有了分位数，调阈值就不用再靠"跑一遍看灰块多大"来猜。</p>
     */
    private static String slopeStats(TerrainData t) {
        double[] v = new double[t.size * t.size];
        int n = 0;
        for (int i = 0; i < v.length; i++) {
            if (!Biomes.isSea(t.biome[i])) {
                v[n++] = t.slope[i];
            }
        }
        java.util.Arrays.sort(v, 0, n);
        StringBuilder sb = new StringBuilder();
        int[] qs = {50, 75, 90, 95, 98, 99, 100};
        for (int q : qs) {
            double x = v[Math.min(n - 1, (int) Math.round((n - 1) * q / 100.0))];
            sb.append('p').append(q).append('=').append(String.format("%.3f", x)).append(' ');
        }
        return sb.toString().trim();
    }

    /** 水系诊断：河道格数 / 河面遮罩强度 —— 河"看不见"是最容易漏掉的地图缺陷。 */
    private static String waterStats(TerrainData t) {
        int river = 0;
        int alpha = 0;
        int lake = 0;
        int polylines = t.rivers == null ? 0 : t.rivers.size();
        double total = 0;
        double longest = 0;
        if (t.rivers != null) {
            for (double[] poly : t.rivers) {
                double len = 0;
                for (int i = 0; i + 3 < poly.length; i += 2) {
                    len += Math.hypot(poly[i + 2] - poly[i], poly[i + 3] - poly[i + 1]);
                }
                total += len;
                longest = Math.max(longest, len);
            }
        }
        for (int i = 0; i < t.riverMask.length; i++) {
            if (t.riverMask[i]) {
                river++;
            }
            if (t.riverAlpha[i] > 0.01f) {
                alpha++;
            }
            if (t.biome[i] == Biomes.LAKE) {
                lake++;
            }
        }
        return "河折线=" + polylines + " 河长合计=" + Math.round(total) + "m 最长=" + Math.round(longest)
                + "m 河格=" + river + " 河面遮罩=" + alpha + " 湖格=" + lake
                + " 汇流分位=" + flowStats(t);
    }

    /** 汇流量分位数：河网阈值（按格数）就是拿这些值去卡的，必须先看清分布。 */
    private static String flowStats(TerrainData t) {
        double[] v = new double[t.size * t.size];
        int n = 0;
        for (int i = 0; i < v.length; i++) {
            if (t.height[i] > t.seaLevel) {
                v[n++] = t.flow[i];
            }
        }
        java.util.Arrays.sort(v, 0, n);
        StringBuilder sb = new StringBuilder();
        int[] qs = {50, 90, 99, 100};
        for (int q : qs) {
            double x = v[Math.min(n - 1, (int) Math.round((n - 1) * q / 100.0))];
            sb.append('p').append(q).append('=').append(Math.round(x)).append(' ');
        }
        return sb.toString().trim();
    }

    private static double share(Map<String, Double> census, String... names) {
        double sum = 0;
        for (String n : names) {
            sum += census.getOrDefault(n, 0.0);
        }
        return sum;
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 1000) / 1000.0);
    }
}
