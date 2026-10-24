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

import com.holzyn.actor.domain.mapgen.algo.Biomes;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.algo.TerrainGenerator;
import com.holzyn.actor.domain.mapgen.algo.TerrainPainter;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;

import java.io.File;
import java.util.Locale;

/**
 * S1 地形调试验证入口（开发期用，不进生产路径）。
 * <p>用法：{@code java ... TerrainPreviewMain <输出目录> [边长] [seed] [island|valley]}</p>
 * <p>产出：{@code terrain.png}（成品观感）、{@code biome.png}（分类平涂）、
 * {@code height.png}（高度+晕渲）以及统计打印。用于人眼验收地形算法，
 * 避免「先接前端才发现丑」。</p>
 */
public final class TerrainPreviewMain {

    private TerrainPreviewMain() {
    }

    /**
     * 入口。
     *
     * @param args 见类注释
     * @throws Exception 生成失败
     */
    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "tmp/terrain-preview";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20261016L;
        String profile = args.length > 3 ? args[3] : "island";

        TerrainParams p = "valley".equals(profile)
                ? TerrainParams.riverValley(seed, size)
                : TerrainParams.island(seed, size);

        long t0 = System.currentTimeMillis();
        TerrainData t = TerrainGenerator.generate(p);
        long t1 = System.currentTimeMillis();

        File dir = new File(outDir);
        TerrainPainter.write(TerrainPainter.render(t), new File(dir, profile + "-terrain.png"));
        TerrainPainter.write(TerrainPainter.renderBiomes(t), new File(dir, profile + "-biome.png"));
        TerrainPainter.write(TerrainPainter.renderHeight(t), new File(dir, profile + "-height.png"));

        System.out.printf(Locale.ROOT, "[%s] size=%d seed=%d 耗时=%dms%n", profile, size, seed, t1 - t0);
        System.out.printf(Locale.ROOT, "  陆地占比=%.3f 可建造占比=%.3f 高度=[%.3f,%.3f] 河流=%d 湖泊=%d%n",
                t.landFraction, t.buildableFraction, t.minHeight, t.maxHeight,
                t.rivers.size(), t.lakes.size());
        int cells = size * size;
        int[] count = new int[Biomes.COUNT];
        for (byte b : t.biome) {
            count[b & 0xFF]++;
        }
        for (int i = 0; i < Biomes.COUNT; i++) {
            if (count[i] > 0) {
                System.out.printf(Locale.ROOT, "    %-6s %6.2f%%%n", Biomes.name((byte) i),
                        100.0 * count[i] / cells);
            }
        }
    }
}
