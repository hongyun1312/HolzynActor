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

package com.holzyn.actor.domain.mapgen.algo;

import java.util.ArrayDeque;

/**
 * 生物群系与可建造度赋值——把「高度/坡度/离水距离/温度/湿度/离聚落距离」变成一张语义地表图。
 * <p>规则顺序即优先级：水面 → 沙滩 → 湿地 → 雪线 → 岩石/断崖 → 荒地 → 农田 → 林地 → 草甸/草地。
 * 所有阈值都用低频噪声扰动过，避免出现「等高线一样整齐」的色带（那是 AI 地图最典型的假）。</p>
 */
public final class BiomeAssigner {

    private static final int[] NX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private BiomeAssigner() {
    }

    /**
     * 赋值（写入 biome / depth / buildable / 统计字段）。
     *
     * @param t 地形
     * @param p 参数
     * @param n 噪声源（同一 seed）
     */
    public static void assign(TerrainData t, TerrainParams p, Noise2D n) {
        int size = t.size;
        int cells = size * size;
        float sea = (float) t.seaLevel;

        computeSlope(t);

        // ---------- 离水距离 / 离海岸距离（陆地上到最近水面的格数，上限 127） ----------
        byte[] distWater = distance(size, i -> t.height[i] <= sea || t.riverMask[i] || t.biome[i] == Biomes.LAKE);
        byte[] distSea = distance(size, i -> t.height[i] <= sea);
        for (int i = 0; i < cells; i++) {
            t.distWater[i] = distWater[i] & 0xFF;
            t.distSea[i] = distSea[i] & 0xFF;
        }

        int landCount = 0;
        int buildableCount = 0;
        double maxH = 0;
        double minH = 1;

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                float h = t.height[i];
                // 水面：按深度分深海/海洋/浅海（河湖已在上一步标好，跳过）
                if (h <= sea) {
                    double depth = (sea - h) / 0.55;
                    t.depth[i] = (float) Math.min(1.0, depth);
                    if (t.biome[i] != Biomes.RIVER && t.biome[i] != Biomes.LAKE) {
                        t.biome[i] = depth > 0.62 ? Biomes.DEEP_OCEAN
                                : (depth > 0.20 ? Biomes.OCEAN : Biomes.SHALLOW);
                    }
                    t.buildable[i] = 0;
                    continue;
                }
                landCount++;
                maxH = Math.max(maxH, h);
                minH = Math.min(minH, h);

                if (t.riverMask[i] || t.biome[i] == Biomes.LAKE) {
                    t.depth[i] = 0.12f;
                    t.buildable[i] = 0;
                    if (t.biome[i] != Biomes.LAKE) {
                        t.biome[i] = Biomes.WETLAND;
                    }
                    continue;
                }

                double e = (h - sea) / Math.max(0.05, p.reliefHeight);
                double slope = t.slope[i];
                int dw = distWater[i] & 0xFF;
                int ds = distSea[i] & 0xFF;

                // 噪声扰动：让植被边界「毛」起来，而不是刀切
                // 注意 Perlin fbm 的实际分布高度集中在 0.5 附近（很少出现极值），
                // 直接拿 ±0.5 的偏差当权重会让「林地/荒地」几乎永远触发不了（整座岛一片草绿），
                // 所以这里先中心化再放大 2.4 倍，把稀疏的 0.3/0.7 变成 ±0.5 的有效对比。
                double jitter = (n.fbm(x / 42.0, z / 42.0, 4, 2.0, 0.5) - 0.5) * 1.6;
                double clump = (n.fbm(x / 74.0 + 17.3, z / 74.0 - 5.1, 3, 2.0, 0.5) - 0.5) * 4.0;
                // 大尺度林区遮罩：真实温带海岛是「成片林 + 成片开阔草坡」的拼布，
                // 只有中频 clump 时每格都"有一点点树"，平均下来整岛就是一块均匀浅绿
                // （实测 1024² 的岛疏林占比几乎为 0，主因就是这个）。这里再加一层 ~165 格的
                // 低频遮罩，把地表明确分成林区/牧场两态。
                double woodland = (n.fbm(x / 165.0 + 61.3, z / 165.0 - 23.7, 3, 2.0, 0.5) - 0.5) * 2.0;
                double temp = p.temperature - 0.58 * e + 0.10 * jitter;
                double moist = p.moisture + 0.22 * Math.exp(-dw / 30.0) - 0.20 * e + 0.26 * jitter;
                double forest = Math.max(0, Math.min(1.2,
                        moist * p.forestBias * 0.62 + 0.30 * clump + 1.15 * woodland + 0.15));

                // ---- 语义区域气候（M2）：东边雾林、北边松林、南边泥滩 ----
                // 全局 p.moisture/forestBias 只能描述「整座岛偏湿」，描述不了
                // 「东侧低洼终年海雾、北坡长松、南岸光秃秃的泥滩」这种同一座岛上的差异。
                double mudW = 0;
                if (p.regions != null && !p.regions.isEmpty()) {
                    double bdx = x - size * 0.5;
                    double bdz = z - size * 0.5;
                    double bAng = Math.atan2(bdz, bdx);
                    double bRf = Math.hypot(bdx, bdz)
                            / Math.max(1.0, size * Math.max(0.1, p.islandRadiusRatio));
                    double[] cb = TerrainSemantics.climateBias(p.regions, bAng, bRf);
                    moist = Math.max(0, Math.min(1.2, moist + 0.20 * cb[1]));
                    forest = Math.max(0, Math.min(1.35, forest + 0.55 * cb[0]));
                    mudW = TerrainSemantics.kindWeight(p.regions, TerrainRegion.MUDFLAT, bAng, bRf);
                }
                double nz2 = n.fbm(x / 130.0 + 31.7, z / 130.0 - 11.3, 3, 2.0, 0.5) - 0.5;
                // 露岩斑块遮罩（0~1）：把"坡度够了"的整条带切成一段段露头。
                // 12 米基线 + 单一坡度阈值 ⇒ 山脊侧面出现一条连绵灰色板岩带（实测占全岛 7.3%），
                // 而真实海岛是「陡坡上长着树，只在局部鼓包处露岩」。
                double rockPatch = sstep(0.36, 0.70,
                        n.fbm(x / 57.0 + 83.1, z / 57.0 + 41.9, 3, 2.0, 0.5));

                // 聚落距离（归一化 0~1，0=聚落中心）
                double sdx = (x / (double) size - p.settlementX) / Math.max(0.05, p.settlementRadius);
                double sdz = (z / (double) size - p.settlementZ) / Math.max(0.05, p.settlementRadius);
                double sd = Math.hypot(sdx, sdz);

                // 农田适宜度。真实耕地是**成片**的：先由 ~92 格低频噪声圈出「农区」，
                // 只有落在农区里的地块才可能开垦；26 格噪声只负责把田块边界咬出毛边。
                // 早期版本直接拿 26 格噪声当阈值，等于在整座岛上随机撒 26 米色斑——
                // 卫星图上看是一层"霉点"，而且农田会跑到离家 300 米的山坡上（实测农田
                // 3.97% 却散成几十块孤立斑点，配套的田路吸附也一个都吸不上）。
                double farmRegion = sstep(0.45, 0.57, n.fbm(x / 92.0 - 11.7, z / 92.0 + 5.9, 3, 2.0, 0.5));
                double farmNoise = n.fbm(x / 26.0 - 7.1, z / 26.0 + 3.3, 3, 2.0, 0.5);
                double farmW = (1 - sstep(0.56, 0.94, sd))
                        * (1 - sstep(0.62, 0.88, e))
                        * (1 - sstep(0.09, 0.24, slope))
                        * farmRegion
                        * sstep(0.30, 0.46, farmNoise);

                byte biome;
                // 沙滩判定只看「离海多近 + 高出海面多少」。
                // 这里**不能**再用坡度当条件：1 米分辨率的坡度场在岸线上必然饱和（陆地与海面之间
                // 那一步高差就够 1.0 了），带上坡度等于"沙滩永不成立"——早期实测沙滩占比 0.00%，
                // 岸线因此变成硬切边。
                if (ds <= p.beachWidth && e < 0.06) {
                    biome = Biomes.BEACH;
                } else if (mudW > 0.42 && e < 0.30 && slope < 0.55) {
                    // 契约里明确写了「滩涂」而且在低处缓坡上 → 潮间泥滩/盐沼。
                    // 不能只靠 dw<=3 判湿地：泥滩可以伸进岛内一两百米（潮沟纵横），
                    // 靠离水距离判会把整片滩涂漏成草地。
                    biome = Biomes.WETLAND;
                } else if (dw <= 3 && moist > 0.58 && slope < 0.08 && nz2 > -0.18) {
                    biome = Biomes.WETLAND;
                } else if (p.elevationMeters >= 600 && e > 0.88 && temp < 0.30) {
                    biome = Biomes.SNOW;
                } else if (slope > 1.55 && ds > 6 && rockPatch > 0.45) {
                    // 崖壁：12 米基线上持续 57° 以上（tan57°≈1.54），而且必须是"露岩带"里的一段。
                    // 加 rockPatch 是因为纯坡度阈值一定会沿着山脊画出一条连续等值带，
                    // 真实崖壁是断断续续的露头，中间夹着贴坡生长的灌丛。
                    biome = Biomes.CLIFF;
                } else if (((e > 0.55 && slope > 1.15) || slope > 1.35) && ds > 8 && rockPatch > 0.28) {
                    // 裸岩：49° 以上的陡壁。45° 以下的坡在温带湿岛上一定长满植被。
                    biome = Biomes.ROCK;
                } else if (moist < 0.34 && slope < 0.50) {
                    biome = Biomes.SCRUB;
                } else if (farmW > 0.42) {
                    biome = Biomes.FARMLAND;
                } else if (forest > 0.72 && e < 0.86) {
                    biome = Biomes.FOREST_DENSE;
                } else if (forest > 0.56 && e < 0.94) {
                    biome = Biomes.FOREST_LIGHT;
                } else if (moist > 0.60) {
                    biome = Biomes.MEADOW;
                } else {
                    biome = Biomes.GRASS;
                }
                t.biome[i] = biome;
                t.elevation[i] = (float) Math.max(0, Math.min(1, e));
                t.moisture[i] = (float) Math.max(0, Math.min(1, moist));
                t.forest[i] = (float) Math.max(0, Math.min(1.2, forest));
                t.temp[i] = (float) Math.max(0, Math.min(1, temp));
                t.settlement[i] = (float) Math.min(1, sd);
                t.farm[i] = (float) Math.max(0, Math.min(1, farmW));

                // 可建造度：坡度越缓、离水越近但不贴水、海拔适中 → 越适合盖房
                double build = 1.0;
                build *= clamp01(1.0 - slope / 0.62);
                build *= clamp01((dw - p.beachWidth) / 4.0 + 0.35);
                build *= clamp01(1.0 - Math.abs(e - 0.22) / 0.72);
                if (biome == Biomes.BEACH || biome == Biomes.WETLAND
                        || biome == Biomes.CLIFF || biome == Biomes.ROCK
                        || biome == Biomes.SNOW || biome == Biomes.RIVER || biome == Biomes.LAKE) {
                    build = 0;
                }
                t.buildable[i] = (float) build;
                if (build > 0.28) {
                    buildableCount++;
                }
            }
        }

        t.landFraction = landCount / (double) cells;
        t.buildableFraction = buildableCount / (double) cells;
        t.maxHeight = maxH;
        t.minHeight = minH;
    }

    /**
     * 多源 BFS 距离场（8 邻接，格数，上限 127）。
     *
     * @param size  边长
     * @param isSource 源判定（true=距离 0）
     * @return 每格到最近源的格数
     */
    private static byte[] distance(int size, java.util.function.IntPredicate isSource) {
        int cells = size * size;
        byte[] dist = new byte[cells];
        java.util.Arrays.fill(dist, (byte) 127);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < cells; i++) {
            if (isSource.test(i)) {
                dist[i] = 0;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int c = queue.poll();
            int cd = dist[c];
            if (cd >= 126) {
                continue;
            }
            int cx = c % size;
            int cz = c / size;
            for (int k = 0; k < 8; k++) {
                int ax = cx + NX[k];
                int az = cz + NZ[k];
                if (ax < 0 || az < 0 || ax >= size || az >= size) {
                    continue;
                }
                int a = az * size + ax;
                if (dist[a] > cd + 1) {
                    dist[a] = (byte) (cd + 1);
                    queue.add(a);
                }
            }
        }
        return dist;
    }

    /**
     * 坡度：归一化高度差换算成「米/米」的真实梯度，即 <b>tan(坡度角)</b>。
     * <p>{@code 1.0 = 45°}，<b>不设上限</b>——这是关键。早期版本 clamp 到 1.0，于是所有
     * 45° 以上的坡面全被压成同一个值 1.0，坡度场在 5% 的陆地上"饱和"；任何低于 1.0 的
     * 阈值都会把整片饱和区一起选中，"断崖"因此沿着西北山脊糊成一条占全岛 7.3% 的
     * 灰色板岩带，而且怎么调阈值都调不动（0.82 和 0.95 选出来的是同一片）。
     * 放开上限之后，「45° 的陡坡」和「70° 的崖壁」才终于能区分开。</p>
     *
     * @param t 地形
     */
    public static void computeSlope(TerrainData t) {
        int size = t.size;
        // 坡度必须按**有意义的基线**度量，不能取相邻两格差分。
        // 1 米分辨率下，中心差分量的其实是噪声残余：平滑高度场里 ±1 米/格 的小起伏，
        // 换算成坡度就是 1.0（45°）。真实坡度的最小有意义尺度是十几米（一条林道、
        // 一栋房子的进深），所以统一改用 12 米基线做中心差分。这同时让
        // 「可建造度 / 农田适宜度 / 道路选线」全部建立在真实坡度上，而不是建立在噪声上。
        int radius = (int) Math.max(1, Math.round(12.0 / Math.max(0.25, t.metersPerCell)));
        float[] h = t.smooth;
        double k = t.heightToMeters / Math.max(0.05, t.metersPerCell);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                int xl = Math.max(0, x - radius);
                int xr = Math.min(size - 1, x + radius);
                int zl = Math.max(0, z - radius);
                int zr = Math.min(size - 1, z + radius);
                // 水侧的邻格不参与差分：海平面与沙滩之间那一步高差会让岸线整圈坡度饱和，
                // 于是「断崖」沿整条海岸画出来一圈深褐色的墙。坡度描述的是**陆地表面**，
                // 所以把水下邻格替换成中心格自身高度（等价于把海当成陆地的延伸）。
                double hc = h[i];
                double hlx = t.mask[z * size + xl] > 0.5f ? h[z * size + xl] : hc;
                double hrx = t.mask[z * size + xr] > 0.5f ? h[z * size + xr] : hc;
                double hlz = t.mask[zl * size + x] > 0.5f ? h[zl * size + x] : hc;
                double hrz = t.mask[zr * size + x] > 0.5f ? h[zr * size + x] : hc;
                double gx = (hrx - hlx) / Math.max(1, xr - xl) * k;
                double gz = (hrz - hlz) / Math.max(1, zr - zl) * k;
                t.slope[i] = (float) Math.hypot(gx, gz);
            }
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 平滑阶跃：v 从 a 到 b 平滑过渡到 1。 */
    private static double sstep(double a, double b, double v) {
        if (a == b) {
            return v < a ? 0 : 1;
        }
        double t = (v - a) / (b - a);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }
}
