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
 * 二维梯度噪声（Perlin 改进版 + 分形叠加 + 域扭曲）——地形轮廓/高度/湿度的唯一噪声源。
 * <p>全部由 {@link Rng} 播种的置换表决定：同 seed 同结果。提供</p>
 * <ul>
 *   <li>{@link #noise}：单八度梯度噪声，输出 [-1,1]；</li>
 *   <li>{@link #fbm}：分形布朗运动（低频大形 + 高频细节），输出 [0,1]；</li>
 *   <li>{@link #ridged}：脊状分形（山脊锐利、谷地平滑），输出 [0,1]；</li>
 *   <li>{@link #warp}：域扭曲——把坐标先扭一扭，得到自然弯曲的海岸/山脊。</li>
 * </ul>
 */
public final class Noise2D {

    /** 置换表长度。 */
    private static final int PERM_SIZE = 512;

    /** 置换表（双倍长度避免取模）。 */
    private final int[] perm = new int[PERM_SIZE];

    /** 梯度表（8 方向）。 */
    private static final double[][] GRAD = {
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    /**
     * 构造：由种子洗牌置换表。
     *
     * @param rng 确定性随机源
     */
    public Noise2D(Rng rng) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < PERM_SIZE; i++) {
            perm[i] = p[i & 255];
        }
    }

    /**
     * 单八度梯度噪声。
     *
     * @param x X 坐标（任意实数）
     * @param y Y 坐标
     * @return [-1,1]
     */
    public double noise(double x, double y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        int x0 = xi & 255;
        int y0 = yi & 255;
        int x1 = (x0 + 1) & 255;
        int y1 = (y0 + 1) & 255;

        double n00 = dot(x0, y0, xf, yf);
        double n10 = dot(x1, y0, xf - 1, yf);
        double n01 = dot(x0, y1, xf, yf - 1);
        double n11 = dot(x1, y1, xf - 1, yf - 1);

        double u = fade(xf);
        double v = fade(yf);
        double nx0 = lerp(u, n00, n10);
        double nx1 = lerp(u, n01, n11);
        return lerp(v, nx0, nx1);
    }

    /**
     * 分形布朗运动（FBM）。
     *
     * @param x       X 坐标
     * @param y       Y 坐标
     * @param octaves 八度数（4~7 合适）
     * @param lac     频率倍增（通常 2.0）
     * @param gain    振幅衰减（通常 0.5）
     * @return [0,1]
     */
    public double fbm(double x, double y, int octaves, double lac, double gain) {
        double sum = 0;
        double amp = 1;
        double freq = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x * freq, y * freq);
            norm += amp;
            amp *= gain;
            freq *= lac;
        }
        return sum / (norm * 2) + 0.5;
    }

    /**
     * 脊状分形（山脊）：1-|noise| 叠加，产生连续的脊线。
     *
     * @param x       X 坐标
     * @param y       Y 坐标
     * @param octaves 八度数
     * @return [0,1]
     */
    public double ridged(double x, double y, int octaves) {
        double sum = 0;
        double amp = 0.5;
        double freq = 1;
        double prev = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise(x * freq, y * freq));
            n *= n;
            sum += n * amp * prev;
            prev = n;
            amp *= 0.5;
            freq *= 2.07;
        }
        return clamp01(sum * 1.6);
    }

    /**
     * 域扭曲：返回被噪声推挤过的坐标偏移。
     *
     * @param x        X 坐标
     * @param y        Y 坐标
     * @param strength 扭曲强度（格）
     * @param scale    扭曲尺度（越大越平缓）
     * @return 长度 2 的偏移数组 [dx,dy]
     */
    public double[] warp(double x, double y, double strength, double scale) {
        double wx = noise(x / scale + 11.3, y / scale + 7.7);
        double wy = noise(x / scale - 5.9, y / scale + 19.1);
        return new double[]{wx * strength, wy * strength};
    }

    private double dot(int xi, int yi, double xf, double yf) {
        int h = perm[(perm[xi] + yi) & 511] & 7;
        double[] g = GRAD[h];
        return g[0] * xf + g[1] * yf;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
