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

/**
 * 水力侵蚀（雨滴法 / droplet erosion）——让山谷成谷、平原成冲积扇的关键一步。
 * <p>算法本体（Job Talle 的 droplet 模型）：每个雨滴带水与泥沙，沿最陡坡下滑，
 * 按「携沙能力」决定侵蚀还是沉积；侵蚀强度随坡度与水量变化。跑完几十万滴后，
 * 高度场会出现自然的下切河谷与平滑的冲积平原——这是纯噪声堆不出来的「真山」。
 * 边界处雨滴直接消亡，避免把海岸啃出锯齿。</p>
 */
public final class HydraulicErosion {

    /** 惯性（0..1，越大越不容易被坡向掰弯）。 */
    private static final double INERTIA = 0.055;

    /** 携沙能力系数。 */
    private static final double CAPACITY = 6.0;

    /** 侵蚀速率。 */
    private static final double ERODE_SPEED = 0.30;

    /** 沉积速率。 */
    private static final double DEPOSIT_SPEED = 0.32;

    /** 蒸发率。 */
    private static final double EVAPORATE = 0.018;

    /** 重力。 */
    private static final double GRAVITY = 6.0;

    /** 最小携沙能力（平地也给一点，防完全不动）。 */
    private static final double MIN_CAPACITY = 0.005;

    /** 每滴最大步数。 */
    private static final int MAX_STEPS = 42;

    private HydraulicErosion() {
    }

    /**
     * 执行侵蚀（原地修改高度场）。
     *
     * @param t     地形数据
     * @param rng   确定性随机源
     * @param drops 水滴数（0 = 跳过）
     * @param strength 侵蚀强度倍率（0~1）
     */
    public static void erode(TerrainData t, Rng rng, int drops, double strength) {
        erode(t, rng, drops, strength, Float.NEGATIVE_INFINITY);
    }

    /**
     * 执行侵蚀，并给陆地一个「不许下切到的下限」。
     *
     * <p>为什么要下限：雨滴在盆地/谷底会反复下切，把内陆啃到海平面以下。之后不管怎么补救，
     * 都会留下「一块水平地台 + 一圈断崖」或「一个圆形大坑」——俯视图上像谁挖的基坑。
     * 真实小岛的内陆洼地不会低于海面，所以直接让侵蚀在接近海面时收手，从源头不产生坑。</p>
     *
     * @param t        地形数据
     * @param rng      确定性随机源
     * @param drops    水滴数（0 = 跳过）
     * @param strength 侵蚀强度倍率（0~1）
     * @param minLand  陆地格允许的最低高度（负无穷 = 不限制）
     */
    public static void erode(TerrainData t, Rng rng, int drops, double strength, float minLand) {
        if (drops <= 0 || strength <= 0) {
            return;
        }
        float[] h = t.height;
        int size = t.size;
        double erodeK = ERODE_SPEED * strength;
        double depositK = DEPOSIT_SPEED * strength;

        for (int d = 0; d < drops; d++) {
            double px = rng.nextDouble() * (size - 3) + 1;
            double pz = rng.nextDouble() * (size - 3) + 1;
            // 只侵蚀陆地：落在海里的雨滴直接丢弃（否则会把海底啃出坑）
            if (t.sampleHeight(px, pz) <= t.seaLevel) {
                continue;
            }
            double dx = 0;
            double dz = 0;
            double speed = 1;
            double water = 1;
            double sediment = 0;

            for (int step = 0; step < MAX_STEPS; step++) {
                int cx = (int) px;
                int cz = (int) pz;
                double fx = px - cx;
                double fz = pz - cz;
                // 双线性取样高度与梯度
                double h00 = heightAt(h, size, cx, cz);
                double h10 = heightAt(h, size, cx + 1, cz);
                double h01 = heightAt(h, size, cx, cz + 1);
                double h11 = heightAt(h, size, cx + 1, cz + 1);
                double gh = (h10 - h00) * (1 - fz) + (h11 - h01) * fz;
                double gv = (h01 - h00) * (1 - fx) + (h11 - h10) * fx;

                dx = dx * INERTIA - gh * (1 - INERTIA);
                dz = dz * INERTIA - gv * (1 - INERTIA);
                double len = Math.hypot(dx, dz);
                if (len < 1e-8) {
                    // 完全平地：随机微扰，防止雨滴滞留
                    double a = rng.nextDouble() * Math.PI * 2;
                    dx = Math.cos(a) * 0.05;
                    dz = Math.sin(a) * 0.05;
                    len = 0.05;
                }
                dx /= len;
                dz /= len;
                px += dx;
                pz += dz;

                if (px < 1 || pz < 1 || px >= size - 2 || pz >= size - 2) {
                    break;
                }

                double newH = t.sampleHeight(px, pz);
                double deltaH = newH - (h00 * (1 - fx) * (1 - fz) + h10 * fx * (1 - fz)
                        + h01 * (1 - fx) * fz + h11 * fx * fz);

                double capacity = Math.max(-deltaH * speed * water * CAPACITY, MIN_CAPACITY);
                if (sediment > capacity || deltaH > 0) {
                    // 上坡或携沙过载 → 沉积
                    double amount = deltaH > 0
                            ? Math.min(deltaH, sediment)
                            : (sediment - capacity) * depositK;
                    sediment -= amount;
                    // 沉积写回起点（雨滴离开的那格），天然形成冲积扇
                    writeBilinear(h, size, cx, cz, fx, fz, amount, minLand);
                } else {
                    // 下坡且未满载 → 侵蚀
                    double amount = Math.min((capacity - sediment) * erodeK, -deltaH);
                    // 不在最底处下切，沿路径起点侵蚀，形成 V 形谷
                    writeBilinear(h, size, cx, cz, fx, fz, -amount, minLand);
                    sediment += amount;
                }

                speed = Math.sqrt(Math.max(0, speed * speed + deltaH * GRAVITY));
                water *= (1 - EVAPORATE);
                if (water < 0.01) {
                    break;
                }
            }
        }
        clampAll(h);
    }

    private static double heightAt(float[] h, int size, int x, int z) {
        int cx = x < 0 ? 0 : (x >= size ? size - 1 : x);
        int cz = z < 0 ? 0 : (z >= size ? size - 1 : z);
        return h[cz * size + cx];
    }

    /** 把增量按双线性权重摊到四角（防锯齿）。 */
    private static void writeBilinear(float[] h, int size, int x, int z, double fx, double fz,
                                       double amount, float minLand) {
        int x1 = Math.min(size - 1, x + 1);
        int z1 = Math.min(size - 1, z + 1);
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return;
        }
        // 侵蚀段的「下限」用**软**方式实现：越接近下限，下切量越小，直到完全停下。
        // 早前用的是硬夹取（低于 minLand 就写回 minLand），结果整条海岸线被夹出一圈
        // 高度完全相同（= sea + 6mm）的格子，渲染出来是一圈锯齿状的白色毛边。
        double a00 = scale(amount, h[z * size + x], minLand);
        double a10 = scale(amount, h[z * size + x1], minLand);
        double a01 = scale(amount, h[z1 * size + x], minLand);
        double a11 = scale(amount, h[z1 * size + x1], minLand);
        h[z * size + x] += (float) (a00 * (1 - fx) * (1 - fz));
        h[z * size + x1] += (float) (a10 * fx * (1 - fz));
        h[z1 * size + x] += (float) (a01 * (1 - fx) * fz);
        h[z1 * size + x1] += (float) (a11 * fx * fz);
    }

    /**
     * 按「离下限还有多少余量」缩放侵蚀量（只对下切生效）。
     *
     * @param amount  增量（负=侵蚀）
     * @param h       该格当前高度
     * @param minLand 下限
     * @return 缩放后的增量
     */
    private static double scale(double amount, float h, float minLand) {
        if (amount >= 0 || minLand == Float.NEGATIVE_INFINITY) {
            return amount;
        }
        double k = (h - minLand) / 0.035;
        k = k < 0 ? 0 : (k > 1 ? 1 : k);
        return amount * k * k * (3 - 2 * k);
    }

    private static void clampAll(float[] h) {
        for (int i = 0; i < h.length; i++) {
            if (h[i] < 0) {
                h[i] = 0;
            } else if (h[i] > 1.25f) {
                h[i] = 1.25f;
            }
        }
    }
}
