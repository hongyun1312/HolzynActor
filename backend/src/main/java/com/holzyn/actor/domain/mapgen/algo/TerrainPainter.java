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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 地形渲染器（S1 调试图 / S5 地形纹理来源）——把栅格数据画成「卫星俯视风」的地表图。
 * <p>关键观感要素：</p>
 * <ul>
 *   <li><b>坡向晕渲</b>：西北向平行光（按真实海拔换算的梯度），山脊受光、山谷阴影；</li>
 *   <li><b>海深梯度</b>：近岸青绿 → 远海深蓝，海面有极轻的波纹；</li>
 *   <li><b>岸线</b>：陆地一侧压暗、水面一侧提亮（浪花），轮廓才利落；</li>
 *   <li><b>河流</b>：用矢量平滑描边（宽度由汇流量决定），而不是逐格涂色——避免锯齿阶梯；</li>
 *   <li><b>地表纹理</b>：农田垄行、林地树冠团块、草地杂色。</li>
 * </ul>
 */
public final class TerrainPainter {

    /** 调色板（RGB）。 */
    private static final int[][] PALETTE = {
            {8, 34, 62},      // DEEP_OCEAN
            {15, 58, 96},     // OCEAN
            {46, 122, 152},   // SHALLOW
            {226, 212, 170},  // BEACH
            {112, 138, 114},  // WETLAND
            {146, 178, 104},  // GRASS
            {172, 196, 120},  // MEADOW
            {200, 190, 126},  // FARMLAND
            {96, 132, 74},    // FOREST_LIGHT
            {58, 86, 48},     // FOREST_DENSE
            {156, 154, 116},  // SCRUB
            {128, 124, 112},  // ROCK
            {106, 100, 92},   // CLIFF
            {238, 241, 243},  // SNOW
            {56, 128, 156},   // RIVER
            {50, 116, 144}    // LAKE
    };

    /** 海深四段色：湿沙 → 浅滩青 → 中层青 → 远海墨蓝。 */
    private static final int[] SEA_WET_SAND = {124, 138, 122};
    private static final int[] SEA_SHOAL = {74, 126, 128};
    private static final int[] SEA_MID = {38, 92, 116};
    private static final int[] SEA_DEEP = {9, 30, 52};

    /** 静水三段色：比海更绿更浑（湖/河的水里是泥和藻，不是珊瑚砂）。 */
    private static final int[] STILL_SHOAL = {96, 120, 106};
    private static final int[] STILL_MID = {64, 96, 98};
    private static final int[] STILL_DEEP = {38, 62, 66};

    /** 海底最大深度（归一化高度）。 */
    private static final double MAX_DEPTH = 0.55;

    /** 裸岩色：偏中性的暖灰。红分量不能太高——和陡坡的橄榄绿一混就成"酱紫色泥浆"。 */
    private static final int[] ROCK_RGB = {134, 130, 119};

    /** 崖壁色：更深的暖褐灰，用来压出岩面的体积。 */
    private static final int[] CLIFF_RGB = {113, 106, 94};

    /** 陡坡植被色：深橄榄绿（土层薄、受光少），作为草坡与岩壁之间的过渡。 */
    private static final int[] STEEP_VEG_RGB = {88, 112, 68};

    private TerrainPainter() {
    }

    /**
     * 渲染为图片。
     *
     * @param t 地形
     * @return RGB 图
     */
    /**
     * 渲染为图片（1 格 = 1 像素；带缓存的快速路径）。
     *
     * @param t 地形
     * @return RGB 图
     */
    public static BufferedImage render(TerrainData t) {
        return render(t, 1.0);
    }

    /**
     * 渲染为图片（可指定输出分辨率）。
     *
     * <p><b>为什么要能超高分辨率渲染</b>：写实底图是 4096²（4 像素/米）。如果先在 1024² 的地形上
     * 画完再双线性放大到 4096²，草地的颗粒、树冠的边缘、浪花线全都被插值糊成一片水彩——
     * 这正是「写实底图看起来像水彩而不是航拍」的根本原因。所以连续字段（高度/湿度/林地/农田/坡度）
     * 按输出像素做双线性采样，植被颗粒与树冠按输出分辨率现画。</p>
     *
     * @param t     地形
     * @param scale 每格多少输出像素（1 = 与格数一致，4 = 写实底图）
     * @return RGB 图
     */
    public static BufferedImage render(TerrainData t, double scale) {
        return render(t, scale, null);
    }

    /**
     * 渲染（可带「不长树」掩膜）。
     *
     * <p><b>为什么需要遮树</b>：树冠是在地表画完之后叠上去的，它不认识建筑与农田——
     * 于是宅基地上、条田里照样长出一片树，俯视图上就是「房子埋在树叶底下」「田里长出森林」。
     * 真实村落的宅基地是清出来的、田里要除草，所以这里接受一张「此处不许长树」的掩膜
     * （由 {@code MapCompositePainter} 按地块/建筑外扩几米光栅化得到）。</p>
     *
     * @param t        地形
     * @param scale    每格输出像素
     * @param noCanopy 「不长树」掩膜（与格数同长；null=不遮）
     * @return RGB 图
     */
    public static BufferedImage render(TerrainData t, double scale, boolean[] noCanopy) {
        if (scale <= 1.05) {
            return renderCells(t, noCanopy);
        }
        return renderScaled(t, scale, noCanopy);
    }

    private static BufferedImage renderCells(TerrainData t) {
        return renderCells(t, null);
    }

    private static BufferedImage renderCells(TerrainData t, boolean[] noCanopy) {
        int size = t.size;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        float sea = (float) t.seaLevel;
        double[] light = normalize(-0.60, -0.70, 0.62);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                byte b = t.biome[i];
                double r;
                double g;
                double bl;

                if (Biomes.isSea(b)) {
                    double d = Math.min(1.0, (sea - t.height[i]) / MAX_DEPTH);
                    double wave = 0.5 + 0.5 * Math.sin(x * 0.035 + z * 0.028
                            + hash(x >> 3, z >> 3, 7) * 4.0);
                    // 海深四段：湿沙 → 浅滩青 → 中层青 → 远海墨蓝。
                    // 早期版本把「浅滩青」铺到 d<0.09（≈20 米带宽），在 1024² 图上就是围着岛
                    // 一圈高饱和青色光晕，像给陆地描了个荧光边。真实卫星图的近岸浅色**很窄**，
                    // 之后迅速转深，所以浅色段压到 d<0.02。
                    double[] c;
                    if (d < 0.02) {
                        c = mix(SEA_WET_SAND, SEA_SHOAL, d / 0.02);
                    } else if (d < 0.09) {
                        c = mix(SEA_SHOAL, SEA_MID, (d - 0.02) / 0.07);
                    } else {
                        c = mix(SEA_MID, SEA_DEEP, Math.min(1.0, (d - 0.09) / 0.40));
                    }
                    // 波纹只压极轻的暗纹，避免海面像塑料
                    double w = 1.0 - 0.030 * wave;
                    r = c[0] * w;
                    g = c[1] * w;
                    bl = c[2] * w;
                } else if (b == Biomes.RIVER || b == Biomes.LAKE) {
                    // 湖/河：靠岸一圈必须是「浑水 + 沙底」的浅色，向心逐渐转深。
                    // 旧版是高饱和青蓝（96,160,172），在俯视图上像一块贴在草地上的蓝色塑料板，
                    // 而且和海水的深蓝完全两种材质。真实静水的水色几乎全由水深 + 悬浮物决定：
                    // 近岸是灰绿浑水，湖心是深青灰，和海一样「越深越暗越冷」。
                    double rim = waterRim(t, x, z, 3);
                    double[] shallow = {104, 126, 112};
                    double[] mid = {62, 92, 96};
                    double[] deep = {34, 56, 62};
                    double[] c = rim > 0.55
                            ? mixColor(mid, shallow, (rim - 0.55) / 0.45)
                            : mixColor(deep, mid, rim / 0.55);
                    r = c[0];
                    g = c[1];
                    bl = c[2];
                    // 极轻的水面噪声：完全平色的湖面像塑料，一点低频起伏就够
                    double ripple = 1.0 - 0.018 * (0.5 + 0.5 * Math.sin(x * 0.06 + z * 0.05
                            + hash(x >> 3, z >> 3, 11) * 3.0));
                    r *= ripple;
                    g *= ripple;
                    bl *= ripple;
                    double shade = shade(t, x, z, light);
                    double k = 0.86 + 0.14 * shade;
                    r *= k;
                    g *= k;
                    bl *= k;
                } else {
                    double[] c = landColor(t, x, z);
                    double shade = shade(t, x, z, light);
                    double tex = texture(b, x, z, t, i);
                    r = c[0] * shade * tex;
                    g = c[1] * shade * tex;
                    bl = c[2] * shade * tex;
                    // 河道：用平滑遮罩贴水色（上游细、下游宽，岸线是曲线不是阶梯）。
                    // 色必须和湖/海同一套「灰绿浑水 → 深青灰」：旧版这里是亮青蓝（62,144,172），
                    // 溪流看上去像一条贴在草坡上的蓝色塑料带，与旁边那片灰绿湖水完全两种材质。
                    double ra = t.riverAlpha[i];
                    if (ra > 0.01) {
                        double k = Math.min(1.0, ra * 1.15);
                        r = lerp(r, 78, k);
                        g = lerp(g, 104, k);
                        bl = lerp(bl, 98, k);
                    }
                }

                // 岸线：陆地一侧压暗，水面一侧提亮（浪花）
                if (t.height[i] > sea && Biomes.isSea(neighborBiome(t, x, z))) {
                    r *= 0.86;
                    g *= 0.86;
                    bl *= 0.86;
                } else if (!Biomes.isWater(b) && neighborIsLakeOrRiver(t, x, z)) {
                    // 湖/河岸：2~3 格「湿泥 + 浅滩草」过渡带。
                    // 旧版湖岸只有硬边，湖像一块贴纸；真实岸线总有一圈颜色更深的湿土。
                    r = r * 0.90 + 84 * 0.10;
                    g = g * 0.92 + 88 * 0.08;
                    bl = bl * 0.92 + 78 * 0.08;
                } else if (Biomes.isSea(b) && neighborIsLand(t, x, z)) {
                    double d = Math.min(1.0, (sea - t.height[i]) / MAX_DEPTH);
                    // 浪花只留贴岸一两像素（0.012 ≈ 1 米水深），宽了就又变成一圈白边
                    double foam = 1.0 - Math.min(1.0, d / 0.012);
                    if (foam > 0) {
                        r = lerp(r, 208, foam * 0.38);
                        g = lerp(g, 230, foam * 0.38);
                        bl = lerp(bl, 228, foam * 0.38);
                    }
                }

                img.setRGB(x, z, rgb(clamp255(r), clamp255(g), clamp255(bl)));
            }
        }

        drawCanopy(img, t);
        return img;
    }

    /**
     * 高分辨率渲染（写实底图专用）。
     *
     * <p>与逐格版本的区别只在采样方式：连续字段按输出像素双线性采样、水色按「离岸距离」分带、
     * 植被颗粒在输出分辨率上现画。三者合起来，4 像素/米的底图才有航拍该有的清晰度与颗粒感。</p>
     *
     * @param t     地形
     * @param scale 每格输出像素
     * @return RGB 图
     */
    private static BufferedImage renderScaled(TerrainData t, double scale, boolean[] noCanopy) {
        int size = t.size;
        int out = Math.max(16, (int) Math.round(size * Math.max(0.25, scale)));
        double k = (double) out / size;
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        float sea = (float) t.seaLevel;
        double[] light = normalize(-0.60, -0.70, 0.62);
        float[] shore = waterShoreDistance(t);
        // 水陆软掩膜：把 1 格分辨率的水陆判断先模糊再双线性采样。
        // 为什么必须这么做：写实底图是 4 像素/米，直接用「最近格是水还是陆」上色，
        // 岸线就是 4 像素一级的台阶（放大看像马赛克）。模糊之后水陆按比例混合，
        // 岸线变成连续的湿沙过渡——这是「航拍感」与「像素图」的分界线。
        float[] wet = waterMask(t);

        for (int pz = 0; pz < out; pz++) {
            double sz = (pz + 0.5) / k - 0.5;
            for (int px = 0; px < out; px++) {
                double sx = (px + 0.5) / k - 0.5;
                double m = sample(wet, size, sx, sz);
                double r;
                double g;
                double bl;
                if (m >= 0.78) {
                    double[] c = waterPixel(t, sx, sz, sea, shore);
                    r = c[0];
                    g = c[1];
                    bl = c[2];
                } else if (m <= 0.22) {
                    double[] c = landPixel(t, sx, sz, light, px, pz);
                    r = c[0];
                    g = c[1];
                    bl = c[2];
                } else {
                    // 岸带：水和陆各算一次再按掩膜混合。只在 m∈(0.22,0.78) 的窄带上多花一次计算。
                    double[] wc = waterPixel(t, sx, sz, sea, shore);
                    double[] lc = landPixel(t, sx, sz, light, px, pz);
                    double f = sstep(0.22, 0.78, m);
                    r = lc[0] + (wc[0] - lc[0]) * f;
                    g = lc[1] + (wc[1] - lc[1]) * f;
                    bl = lc[2] + (wc[2] - lc[2]) * f;
                }
                img.setRGB(px, pz, rgb(clamp255(r), clamp255(g), clamp255(bl)));
            }
        }
        drawCanopy(img, t, k, noCanopy);
        return img;
    }

    /**
     * 水陆软掩膜（0=陆，1=水，边界连续）。
     *
     * <p>种子取自 {@link TerrainData#waterCell(int)}（水陆唯一入口，包含内陆湖与河道），
     * 再叠上河面遮罩的半透明度，最后做三次盒模糊（半径 1/2/2 ≈ 5 格）。
     * 模糊半径是按「4 像素/米下不出现台阶」定的：太小学生会看到 4 像素的锯齿，
     * 太大整条海岸会糊成一条发光带。</p>
     *
     * @param t 地形
     * @return 与格数同长的掩膜
     */
    private static float[] waterMask(TerrainData t) {
        int size = t.size;
        int n = size * size;
        float[] m = new float[n];
        for (int i = 0; i < n; i++) {
            float w = t.waterCell(i) ? 1f : 0f;
            if (t.riverAlpha[i] > 0f) {
                w = Math.max(w, Math.min(1f, t.riverAlpha[i] * 1.15f));
            }
            m[i] = w;
        }
        boxBlur(m, size, 1);
        boxBlur(m, size, 2);
        boxBlur(m, size, 2);
        return m;
    }

    /** 可分离盒式模糊（就地）。 */
    private static void boxBlur(float[] m, int size, int r) {
        float[] tmp = new float[m.length];
        for (int z = 0; z < size; z++) {
            int row = z * size;
            for (int x = 0; x < size; x++) {
                double sum = 0;
                int cnt = 0;
                for (int d = -r; d <= r; d++) {
                    int xx = x + d;
                    if (xx < 0 || xx >= size) {
                        continue;
                    }
                    sum += m[row + xx];
                    cnt++;
                }
                tmp[row + x] = (float) (sum / Math.max(1, cnt));
            }
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                double sum = 0;
                int cnt = 0;
                for (int d = -r; d <= r; d++) {
                    int zz = z + d;
                    if (zz < 0 || zz >= size) {
                        continue;
                    }
                    sum += tmp[zz * size + x];
                    cnt++;
                }
                m[z * size + x] = (float) (sum / Math.max(1, cnt));
            }
        }
    }

    /**
     * 一个输出像素的水色（按「静水/海」分两套色阶，静水更绿更浑）。
     *
     * @param t     地形
     * @param sx    格坐标 X（可为小数）
     * @param sz    格坐标 Z
     * @param sea   海平面
     * @param shore 离岸距离场
     * @return RGB（0~255 浮点）
     */
    private static double[] waterPixel(TerrainData t, double sx, double sz, float sea, float[] shore) {
        double ra = sample(t.riverAlpha, t.size, sx, sz);
        boolean still = ra > 0.30 || nearestBiome(t, sx, sz) == Biomes.LAKE;
        double dl = Math.max(0, sample(shore, t.size, sx, sz));
        if (still) {
            double d = Math.max(0, dl + (fbm(sx / 5.0, sz / 5.0, 2, 9103) - 0.5) * 1.2);
            double[] c = waterRamp(d, 0.25, true);
            // 静水也有一点点微高光，否则整片湖像一块死黑的洞
            double rip = 0.5 + 0.5 * Math.sin(sx * 0.21 + sz * 0.17 + fbm(sx / 2.5, sz / 2.5, 2, 4111) * 6.0);
            double kk = 1.0 + 0.035 * (rip - 0.5);
            return new double[]{c[0] * kk, c[1] * kk, c[2] * kk};
        }
        double d = Math.max(0, dl + (fbm(sx / 9.0, sz / 9.0, 2, 5501) - 0.5) * 1.8);
        // 深度取平滑高度场：原始高度是 1 米栅格，直接拿它算深度会在水下留下网格状的
        // "等深线台阶"（低频缓坡上尤其明显，像一片格栅）。
        double h = sample(t.smooth, t.size, sx, sz);
        double depthFrac = Math.min(1.0, Math.max(0.0, (sea - h) / MAX_DEPTH));
        double[] c = waterRamp(d, depthFrac, false);
        double wave = 0.5 + 0.5 * Math.sin(sx * 0.13 + sz * 0.11
                + fbm(sx / 3.0, sz / 3.0, 2, 71) * 6.0);
        double w = 1.0 - 0.030 * wave;
        return new double[]{c[0] * w, c[1] * w, c[2] * w};
    }

    /**
     * 一个输出像素的陆色（连续字段着色 + 晕渲 + 纹理 + 岸线压暗）。
     *
     * @param t     地形
     * @param sx    格坐标 X（可为小数）
     * @param sz    格坐标 Z
     * @param light 光照方向
     * @param px    输出像素 X（细碎颗粒用）
     * @param pz    输出像素 Z
     * @return RGB（0~255 浮点）
     */
    private static double[] landPixel(TerrainData t, double sx, double sz, double[] light, int px, int pz) {
        double[] c = landColorAt(t, sx, sz);
        double sh = shadeAt(t, sx, sz, light);
        double tx = textureAt(t, sx, sz, px, pz);
        double r = c[0] * sh * tx;
        double g = c[1] * sh * tx;
        double bl = c[2] * sh * tx;
        // 岸线：陆地一侧压暗（湿土），让轮廓利落
        double ds = sample(t.distSea, t.size, sx, sz);
        if (ds < 2.2) {
            double wl = 1.0 - sstep(0.4, 2.2, ds);
            r *= 1 - 0.13 * wl;
            g *= 1 - 0.11 * wl;
            bl *= 1 - 0.09 * wl;
        }
        return new double[]{r, g, bl};
    }

    /**
     * 水色分带：离岸 d 格 → 颜色。
     *
     * @param d         离岸格数（已加噪声抖动）
     * @param depthFrac 归一化水深（0=刚没过，1=最深）
     * @param still     true=湖/河（更绿更浑），false=海
     * @return RGB
     */
    private static double[] waterRamp(double d, double depthFrac, boolean still) {
        // 静水（溪/湖）必须重新标定「离岸距离」：一条 3 米宽的溪，中心离岸最多 1.5 米，
        // 直接套海的距离分带会把整条溪画成「湿沙」——看上去就是一条干河床。
        // 所以静水把距离放大 3.2 倍，只有最外 0.4 米才是沙色。
        double dd = still ? d * 3.2 : d;
        int[] shallow = still ? STILL_SHOAL : SEA_SHOAL;
        int[] mid = still ? STILL_MID : SEA_MID;
        int[] deep = still ? STILL_DEEP : SEA_DEEP;
        double[] c;
        // 分带之间用 smoothstep 过渡而不是线性：线性混色在相邻两带交界处会留下一条
        // 看得见的"等深线"（航拍上的水色是连续的，不该有带边）。
        if (dd < 1.2) {
            c = mix(SEA_WET_SAND, shallow, sstep(0.2, 1.2, dd));
        } else if (dd < 5.0) {
            c = mix(shallow, mid, sstep(1.2, 5.0, dd));
        } else if (dd < 26.0) {
            c = mix(mid, deep, sstep(5.0, 26.0, dd));
        } else {
            c = new double[]{deep[0], deep[1], deep[2]};
        }
        // 浪花：只在贴岸 0.9 格内，而且是断续的（连续白边会变成给岛描了个荧光笔）
        if (dd < 0.95) {
            double foam = (1.0 - sstep(0.10, 0.95, dd)) * (0.35 + 0.65 * dd);
            c = mixColor(c, new int[]{206, 220, 214}, foam * 0.34);
        }
        // 深水压暗：越深越冷越暗
        double dimK = 0.92 + 0.08 * (1.0 - depthFrac);
        return new double[]{c[0] * dimK, c[1] * dimK, c[2] * dimK};
    }

    private static double[] mixColor(double[] a, int[] b, double w) {
        double k = w < 0 ? 0 : (w > 1 ? 1 : w);
        return new double[]{
                a[0] + (b[0] - a[0]) * k,
                a[1] + (b[1] - a[1]) * k,
                a[2] + (b[2] - a[2]) * k
        };
    }

    /**
     * 每个水格「离最近的陆地有多少格」（陆地格为 0）。
     *
     * @param t 地形
     * @return 与格数同长的距离场（格）
     */
    private static float[] waterShoreDistance(TerrainData t) {
        int size = t.size;
        int n = size * size;
        float[] dist = new float[n];
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            // 陆地的边界是「水面」而不是「高程低于海平面」：内陆湖的水面高于海平面，
            // 只按高程判会把湖当成旱地，湖水的离岸距离全是 0 → 整片湖被涂成湿沙色（棕色）。
            if (!t.waterCell(i)) {
                dist[i] = 0;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % size;
            int z = i / size;
            float nd = dist[i] + 1;
            if (x > 0 && dist[i - 1] > nd) {
                dist[i - 1] = nd;
                queue[tail++] = i - 1;
            }
            if (x < size - 1 && dist[i + 1] > nd) {
                dist[i + 1] = nd;
                queue[tail++] = i + 1;
            }
            if (z > 0 && dist[i - size] > nd) {
                dist[i - size] = nd;
                queue[tail++] = i - size;
            }
            if (z < size - 1 && dist[i + size] > nd) {
                dist[i + size] = nd;
                queue[tail++] = i + size;
            }
        }
        for (int i = 0; i < n; i++) {
            if (dist[i] > 4000) {
                dist[i] = 200;
            }
        }
        return dist;
    }

    /** 双线性采样（连续字段）。坐标按格中心定义，越界夹到边缘。 */
    private static double sample(float[] a, int size, double x, double z) {
        double cx = Math.max(0, Math.min(size - 1, x));
        double cz = Math.max(0, Math.min(size - 1, z));
        int x0 = (int) Math.floor(cx);
        int z0 = (int) Math.floor(cz);
        int x1 = Math.min(size - 1, x0 + 1);
        int z1 = Math.min(size - 1, z0 + 1);
        double fx = cx - x0;
        double fz = cz - z0;
        double a00 = a[z0 * size + x0];
        double a10 = a[z0 * size + x1];
        double a01 = a[z1 * size + x0];
        double a11 = a[z1 * size + x1];
        double top = a00 + (a10 - a00) * fx;
        double bot = a01 + (a11 - a01) * fx;
        return top + (bot - top) * fz;
    }

    /** 最近格群系（离散量，不做插值）。 */
    private static byte nearestBiome(TerrainData t, double x, double z) {
        int cx = (int) Math.round(Math.max(0, Math.min(t.size - 1, x)));
        int cz = (int) Math.round(Math.max(0, Math.min(t.size - 1, z)));
        return t.biome[cz * t.size + cx];
    }

    /** 该点是否静水（湖，或河道遮罩很强）。 */
    private static boolean stillWater(TerrainData t, double x, double z) {
        return nearestBiome(t, x, z) == Biomes.LAKE
                || sample(t.riverAlpha, t.size, x, z) > 0.45;
    }

    /**
     * 树冠斑点（后处理）。
     *
     * <p>为什么必须画"团块"而不是只混颜色：1024² 图上 1 像素 = 1 米，一棵树 4~8 米，也就是
     * 4~8 像素。只把底色往深绿混，得到的是"一块深绿的草地"；画出高低错落、边缘毛糙、带受光
     * 面的冠层，才读得出"这里是林子"。这一步是整张图从"绿抹布"变成"地貌"的关键。</p>
     *
     * <p>密度、半径、偏移都用同一套 hash 派生（同种子必得同图），逐格用 3 像素步长采样，
     * 保证 1024² 下 tree 覆盖不重叠成一张实心绿毯。</p>
     *
     * @param img 已画好的地表图（就地叠画）
     * @param t   地形
     */
    private static void drawCanopy(BufferedImage img, TerrainData t) {
        drawCanopy(img, t, 1.0, null);
    }

    /**
     * 树冠（按输出分辨率画）。
     *
     * <p>k=1 时就是旧的逐格版本；k&gt;1（写实底图）时按输出像素步进、冠幅按米换算，
     * 树冠边缘才锐利、冠间阴影才看得出层次。直接在 1024 上画再放大，得到的是「绿棉花」。</p>
     *
     * @param img      底图（就地叠画）
     * @param t        地形
     * @param k        输出像素/格
     * @param noCanopy 「不长树」掩膜（null=不遮）
     */
    private static void drawCanopy(BufferedImage img, TerrainData t, double k, boolean[] noCanopy) {
        int size = t.size;
        // 步长按「米」而不是按「输出像素」定：写实底图 4 px/m 时步长 1.4 格 ≈ 5.6 像素，
        // 冠幅 3.6~7.8 米（≈14~31 像素）——冠层才会互相咬合成一片林冠。
        // 步长如果跟着分辨率一起放大（2.0×k），树心之间就拉开 8 像素的空隙，
        // 整片林子会变成「撒了一把芝麻」的波点布。
        double step = k >= 2.0 ? 1.4 : 2.0;
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 步长 2 格（k=1 时是 2 像素）：1024² 下树冠只有 3~5 像素，步长 3 会让相邻树心隔 3 像素、
        // 冠幅 2~4 像素 → 冠层互相碰不到，整片林子看起来是"撒了一把绿豆"。
        // 步长 2 + 冠幅 2.2~4.4 像素，冠层才咬合成连续林冠，只在林缘散开。
        for (double z = 2; z < size - 2; z += step) {
            for (double x = 2; x < size - 2; x += step) {
                int i = (int) Math.round(z) * size + (int) Math.round(x);
                // 宅基地/田块/广场/码头是「清出来的」：这些格子上不长树（由 S4 的要素外扩得到）
                if (noCanopy != null && noCanopy[i]) {
                    continue;
                }
                float f = t.forest[i];
                if (f < 0.52) {
                    continue;
                }
                byte b = t.biome[i];
                if (Biomes.isWater(b) || b == Biomes.CLIFF || b == Biomes.SNOW
                        || b == Biomes.BEACH || b == Biomes.FARMLAND) {
                    continue;
                }
                // 河/湖是「水面」，树不能长在水里：biome 只标了湖心和主河道，
                // 河岸过渡格（riverAlpha 有值）仍可能是陆地群系，不额外拦就会画出一片
                // 浮在水面上的树冠——航拍上看就是「树压着河」。水陆只认 waterCell()。
                if (t.waterCell(i) || t.riverAlpha[i] > 0.2F) {
                    continue;
                }
                double density = Math.min(1.0, (f - 0.52) / 0.30);
                // 成因噪声：林缘的树是**成群**的，不是等距离的圆点。
                density *= 0.30 + 0.70 * sstep(0.28, 0.56, fbm(x / 13.0, z / 13.0, 2, 3307));
                if (density < 0.04) {
                    continue;
                }
                double h = hash((int) x, (int) z, 4241);
                if (h > density * 0.95) {
                    continue;
                }
                double h2 = hash((int) x, (int) z, 911);
                double r = (1.1 + 1.1 * h2 + 0.6 * density) * k;
                int cx = (int) Math.round((x + (h - 0.5) * 2.2) * k);
                int cz = (int) Math.round((z + (h2 - 0.5) * 2.2) * k);
                int alpha = (int) Math.round(120 + 105 * density);
                // 冠层投影（光从西北来，影子落东南）：先铺一层更深的暗斑，
                // 树与树之间才有"缝隙里的暗"，而不是一片均匀墨绿。
                g2.setColor(new Color(26, 40, 22, Math.min(200, alpha - 20)));
                g2.fillOval((int) (cx - r + 0.8 * k), (int) (cz - r + 1.0 * k),
                        (int) (r * 2), (int) (r * 2));
                // 冠层本体
                g2.setColor(new Color(44, 70, 38, Math.min(235, alpha)));
                g2.fillOval((int) (cx - r), (int) (cz - r), (int) (r * 2), (int) (r * 2));
                // 受光面：西北向光，高光偏左上，树才有立体感
                if (density > 0.34) {
                    g2.setColor(new Color(104, 138, 70, Math.min(150, alpha - 40)));
                    g2.fillOval((int) (cx - r * 0.66), (int) (cz - r * 0.84),
                            (int) (r * 1.10), (int) (r * 0.98));
                }
            }
        }
        g2.dispose();
    }

    /**
     * 纯群系平涂（调试用：看分类是否正确）。
     *
     * @param t 地形
     * @return RGB 图
     */
    public static BufferedImage renderBiomes(TerrainData t) {
        int size = t.size;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int[] c = PALETTE[t.biome[z * size + x] & 0xFF];
                img.setRGB(x, z, rgb(c[0], c[1], c[2]));
            }
        }
        return img;
    }

    /**
     * 高度灰度图 + 晕渲（调试用：看山形是否自然）。
     *
     * @param t 地形
     * @return RGB 图
     */
    public static BufferedImage renderHeight(TerrainData t) {
        int size = t.size;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double[] light = normalize(-0.60, -0.70, 0.62);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                double v = Math.max(0, t.height[i] - t.seaLevel) / 0.6;
                double shade = shade(t, x, z, light);
                int c = clamp255(v * 210 * shade + 28);
                img.setRGB(x, z, rgb(c, c, c));
            }
        }
        return img;
    }

    /**
     * 写 PNG。
     *
     * @param img  图片
     * @param file 目标文件
     * @throws IOException 写失败
     */
    public static void write(BufferedImage img, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        ImageIO.write(img, "png", file);
    }

    // ==================== 内部 ====================

    /**
     * 河网矢量描边：路径经过中点平滑，线宽由汇流量决定，末端收细。
     */
    private static void drawRivers(BufferedImage img, TerrainData t) {
        if (t.rivers == null || t.rivers.isEmpty()) {
            return;
        }
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(58, 140, 168, 235));
        int size = t.size;
        for (double[] poly : t.rivers) {
            int pts = poly.length / 2;
            if (pts < 2) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                g2.setColor(pass == 0 ? new Color(40, 104, 132) : new Color(64, 150, 178));
                Path2D.Double path = new Path2D.Double();
                path.moveTo(poly[0], poly[1]);
                for (int i = 1; i < pts - 1; i++) {
                    double cx = poly[i * 2];
                    double cz = poly[i * 2 + 1];
                    double nx2 = poly[(i + 1) * 2];
                    double nz2 = poly[(i + 1) * 2 + 1];
                    path.quadTo(cx, cz, (cx + nx2) * 0.5, (cz + nz2) * 0.5);
                }
                path.lineTo(poly[(pts - 1) * 2], poly[(pts - 1) * 2 + 1]);
                // 线宽：按中段汇流量估算
                float w = widthOf(t, poly, pts);
                g2.setStroke(new BasicStroke(pass == 0 ? w + 1.6f : w,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(path);
            }
        }
        g2.dispose();
    }

    private static float widthOf(TerrainData t, double[] poly, int pts) {
        double acc = 0;
        int samples = 0;
        for (int i = 0; i < pts; i += Math.max(1, pts / 6)) {
            int x = (int) Math.max(0, Math.min(t.size - 1, poly[i * 2]));
            int z = (int) Math.max(0, Math.min(t.size - 1, poly[i * 2 + 1]));
            acc += t.flow[z * t.size + x];
            samples++;
        }
        acc = samples > 0 ? acc / samples : 400;
        double v = Math.log10(Math.max(200.0, acc) / 200.0) / 1.6;
        return (float) Math.max(1.6, Math.min(7.5, 1.6 + 5.6 * Math.max(0, Math.min(1, v))));
    }

    private static double shade(TerrainData t, int x, int z, double[] light) {
        return shadeAt(t, x, z, light);
    }

    /**
     * 坡向晕渲（支持亚格坐标：平滑高度场双线性采样）。
     */
    private static double shadeAt(TerrainData t, double x, double z, double[] light) {
        int size = t.size;
        float[] hs = t.smooth;
        double k = t.heightToMeters / Math.max(0.05, t.metersPerCell) * 2.4;
        double hc = sample(hs, size, x, z);
        int xi = (int) Math.round(x);
        int zi = (int) Math.round(z);
        int xl = Math.max(0, xi - 1);
        int xr = Math.min(size - 1, xi + 1);
        int zl = Math.max(0, zi - 1);
        int zr = Math.min(size - 1, zi + 1);
        // 水下邻格不参与差分（与 BiomeAssigner.computeSlope 同口径）：
        // 否则海岸线会被涂成一圈"悬崖"的深褐色，整座岛像镶了道铁边。
        double hlx = t.mask[zi * size + xl] > 0.5f ? hs[zi * size + xl] : hc;
        double hrx = t.mask[zi * size + xr] > 0.5f ? hs[zi * size + xr] : hc;
        double hlz = t.mask[zl * size + xi] > 0.5f ? hs[zl * size + xi] : hc;
        double hrz = t.mask[zr * size + xi] > 0.5f ? hs[zr * size + xi] : hc;
        double gx = (hrx - hlx) * 0.5 * k;
        double gz = (hrz - hlz) * 0.5 * k;
        double[] nrm = normalize(-gx, -gz, 1.0);
        double dot = nrm[0] * light[0] + nrm[1] * light[1] + nrm[2] * light[2];
        // 反差比旧值（0.46 + 0.74·dot，夹到 0.34~1.12）更大一档：
        // 旧值在 1024² 全图上看，起伏只有 1.1 倍明暗差，山脊和缓坡糊成一片"绿雾"——
        // 用户说"地形主体不自然"，很大一部分是没有可读的脊线。0.40 + 0.86·dot 让背光坡
        // 落到 0.40、迎光脊到 1.20，人眼才能顺着明暗读出山形（仍夹在 0.30~1.18，不会压黑）。
        double s = 0.40 + 0.86 * Math.max(0, dot);
        return Math.max(0.30, Math.min(1.12, s));
    }

    /** 群系纹理：农田垄行、树冠斑点、草地杂色。 */
    private static double texture(byte b, int x, int z, TerrainData t, int i) {
        return textureAt(t, x, z, x, z);
    }

    /**
     * 群系纹理（支持亚格坐标；px/pz 是输出像素坐标，用于细碎杂色）。
     */
    private static double textureAt(TerrainData t, double x, double z, int px, int pz) {
        int size = t.size;
        int h = (int) (hashReal(x, z, 1337) * 1000);
        double forest = sample(t.forest, size, x, z);
        double farm = sample(t.farm, size, x, z);
        double slope = sample(t.slope, size, x, z);
        // 树冠团块：多层正弦叠加，随林地倾向加权
        double canopy = Math.sin(x * 0.16 + hashReal(x * 0.25, z * 0.25, 5) * 3.0)
                * Math.sin(z * 0.15 + hashReal(x * 0.25, z * 0.25, 9) * 3.0);
        double tex = 1.0 + 0.10 * canopy * Math.min(1, forest);
        // 农田垄行（幅度压得很低：真正的田块由 MapCompositePainter 的矢量地块画在上面，
        // 地形层只要一层"翻耕过的地"的底色，正弦幅度大了会变成满屏斜条纹）
        if (farm > 0.3) {
            tex += 0.028 * Math.sin(x * 0.62 + z * 0.26) * Math.min(1, farm);
        }
        // 植被斑驳：卫星图上草坡绝不是一块纯色，而是 5~20 米尺度的深浅交错
        // （土壤湿度、放牧、灌丛）。这一层是"像照片"和"像色块"的分水岭。
        tex += (fbm(x / 17.0, z / 17.0, 3, 6101) - 0.5) * 0.15;
        tex += (fbm(x / 5.0, z / 5.0, 2, 8803) - 0.5) * 0.085;
        // 岩面纹理：陡坡上叠一层不规则斑驳 + 颗粒，否则灰块是"一块平板"。
        // 这里**绝不能用 sin(x*a)*sin(z*b)**：两条正弦在规则栅格上相乘会得到一张
        // 完美的菱形网格，1024² 图上就是一层"渔网布"，比灰块更假。
        // 用值噪声才有岩石的碎裂感：中频做节理斑块，高频做颗粒。
        if (slope > 0.50) {
            double k = Math.min(1.0, (slope - 0.50) / 0.70);
            double n1 = fbm(x / 6.5, z / 6.5, 3, 3301);
            double n2 = fbm(x / 2.2, z / 2.2, 2, 9109);
            tex += k * ((n1 - 0.5) * 0.40 + (n2 - 0.5) * 0.22);
        }
        // 细碎杂色（草/岩/雪）：高分辨率下按输出像素抖，才有「叶面/砂砾」的颗粒感
        tex += ((h % 13) - 6) * 0.0035;
        tex += (hashReal(px * 0.91, pz * 1.07, 227) - 0.5) * 0.035;
        return tex;
    }

    /**
     * 陆地连续着色：不再「整格套一个群系颜色」，而是按海拔/湿度/林地倾向/坡度/离水距离
     * 做多层平滑混合——这才是卫星图上「一片林子渐渐过渡到草坡」的样子，
     * 而不是一张迷彩补丁。
     */
    private static double[] landColor(TerrainData t, int x, int z) {
        return landColorAt(t, x, z);
    }

    /**
     * 陆地连续着色（支持亚格坐标：高分辨率渲染时按输出像素采样连续字段）。
     */
    private static double[] landColorAt(TerrainData t, double x, double z) {
        int size = t.size;
        double e = sample(t.elevation, size, x, z);
        double m = sample(t.moisture, size, x, z);
        double f = sample(t.forest, size, x, z);
        double tp = sample(t.temp, size, x, z);
        double s = sample(t.slope, size, x, z);
        double dw = sample(t.distWater, size, x, z);
        double ds = sample(t.distSea, size, x, z);
        double farm = sample(t.farm, size, x, z);

        double[] c = mix(PALETTE[Biomes.GRASS & 0xFF], PALETTE[Biomes.MEADOW & 0xFF], sstep(0.44, 0.72, m));
        c = mix(c, PALETTE[Biomes.FOREST_LIGHT & 0xFF], sstep(0.48, 0.62, f));
        c = mix(c, PALETTE[Biomes.FOREST_DENSE & 0xFF], sstep(0.62, 0.84, f));
        c = mix(c, PALETTE[Biomes.SCRUB & 0xFF], sstep(0.40, 0.24, m) * 0.85);
        // 农田底色只做「土壤偏干」的暗示，不刷成整片田色：
        // farm 是「适耕程度」的连续场，不是「这里真的有田」。旧口径按 0.95 权重混 FARMLAND 色，
        // 于是适耕的整片缓坡都被涂成浅黄——航拍上像一块 400 米宽的田，可上面一块田也没种
        // （用户看到的"地图到处是黄地"就是这个）。真正的田块由 MapCompositePainter 的矢量地块画，
        // 地形层只留 0.30 的土壤色倾向。
        c = mix(c, PALETTE[Biomes.FARMLAND & 0xFF], Math.min(1, farm) * 0.30);
        c = mix(c, PALETTE[Biomes.WETLAND & 0xFF],
                sstep(7, 1.5, dw) * sstep(0.55, 0.75, m) * (1 - sstep(0.06, 0.22, s)));
        // 陡坡植被：坡度上来之后植被会变深、变灰、变薄（受光少 + 土层薄），
        // 这是"草坡 → 岩壁"之间必须有的过渡态。没有这一层，坡度一过门槛就直接跳到
        // 中性灰，"山体滑坡疤"就是这么来的。
        c = mix(c, STEEP_VEG_RGB, sstep(0.46, 1.10, s) * 0.62);
        // 裸岩/崖壁：一定要"斑块化 + 让位给林冠"。
        // 旧规则 rock = sstep(0.62,0.90,s)*sstep(0.34,0.58,e) 有两个致命问题：
        //   ① 0.62 的门槛（32°）在湿岛上几乎处处成立，于是整条山脊侧面被刷成一片浅灰；
        //   ② 没考虑植被：真实陡坡上树照样长，只有明显鼓包和擦痕处露岩。
        // 阈值按 tan 给：1.15 = 49°，1.55 = 57°。45° 以下一律留给植被——
        // 温带湿岛上 45° 的坡一定长满树，把 32°~45° 画成裸岩是"山体滑坡疤"的根源。
        double rockPatch = sstep(0.34, 0.70, fbm(x / 41.0 + 83.1, z / 41.0 + 41.9, 3, 7717));
        double rock = sstep(1.15, 1.60, s) * sstep(0.20, 0.46, e) * sstep(4, 8, ds)
                * (0.20 + 1.00 * rockPatch)
                * (1 - 0.72 * sstep(0.52, 0.80, f));
        c = mix(c, ROCK_RGB, Math.min(1, rock));
        // 崖壁：只在最陡处露出一小条深色岩面（真实崖壁是"线"不是"面"）
        double cliff = sstep(1.55, 2.20, s) * sstep(4, 8, ds) * (0.40 + 0.70 * rockPatch);
        c = mix(c, CLIFF_RGB, Math.min(1, cliff));
        // 雪线必须用真实海拔判断（90 米的小岛不该有雪），否则最高点会被刷成一块死白
        double snowLine = t.maxHeight * t.heightToMeters >= 600 ? 1 : 0;
        c = mix(c, PALETTE[Biomes.SNOW & 0xFF], snowLine * sstep(0.84, 0.96, e) * sstep(0.42, 0.26, tp));
        c = mix(c, PALETTE[Biomes.BEACH & 0xFF],
                (1 - sstep(2.0, 7.5, ds)) * (1 - sstep(0.030, 0.085, e)));
        return c;
    }

    private static double[] mix(int[] a, int[] b, double w) {
        double k = w < 0 ? 0 : (w > 1 ? 1 : w);
        return new double[]{
                a[0] + (b[0] - a[0]) * k,
                a[1] + (b[1] - a[1]) * k,
                a[2] + (b[2] - a[2]) * k
        };
    }

    private static double[] mix(double[] a, int[] b, double w) {
        double k = w < 0 ? 0 : (w > 1 ? 1 : w);
        return new double[]{
                a[0] + (b[0] - a[0]) * k,
                a[1] + (b[1] - a[1]) * k,
                a[2] + (b[2] - a[2]) * k
        };
    }

    /** 两个 double 颜色分量数组之间插值（水色分带用）。 */
    private static double[] mixColor(double[] a, double[] b, double w) {
        double k = w < 0 ? 0 : (w > 1 ? 1 : w);
        return new double[]{
                a[0] + (b[0] - a[0]) * k,
                a[1] + (b[1] - a[1]) * k,
                a[2] + (b[2] - a[2]) * k
        };
    }

    /** 邻格是否静水（湖/河）——湖岸过渡带用。 */
    private static boolean neighborIsLakeOrRiver(TerrainData t, int x, int z) {
        int size = t.size;
        if (x > 0 && isStillWater(t.biome[z * size + x - 1])) {
            return true;
        }
        if (z > 0 && isStillWater(t.biome[(z - 1) * size + x])) {
            return true;
        }
        if (x < size - 1 && isStillWater(t.biome[z * size + x + 1])) {
            return true;
        }
        return z < size - 1 && isStillWater(t.biome[(z + 1) * size + x]);
    }

    private static boolean isStillWater(byte b) {
        return b == Biomes.LAKE || b == Biomes.RIVER;
    }

    /** 平滑阶跃：v 从 a 到 b 平滑过渡到 1（a 可大于 b，表示反向过渡）。 */
    private static double sstep(double a, double b, double v) {
        if (a == b) {
            return v < a ? 0 : 1;
        }
        double t = (v - a) / (b - a);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    private static byte neighborBiome(TerrainData t, int x, int z) {
        int size = t.size;
        if (x > 0 && Biomes.isWater(t.biome[z * size + x - 1])) {
            return t.biome[z * size + x - 1];
        }
        if (z > 0 && Biomes.isWater(t.biome[(z - 1) * size + x])) {
            return t.biome[(z - 1) * size + x];
        }
        if (x < size - 1 && Biomes.isWater(t.biome[z * size + x + 1])) {
            return t.biome[z * size + x + 1];
        }
        if (z < size - 1 && Biomes.isWater(t.biome[(z + 1) * size + x])) {
            return t.biome[(z + 1) * size + x];
        }
        return -1;
    }

    private static boolean neighborIsLand(TerrainData t, int x, int z) {
        int size = t.size;
        float sea = (float) t.seaLevel;
        if (x > 0 && t.height[z * size + x - 1] > sea) {
            return true;
        }
        if (z > 0 && t.height[(z - 1) * size + x] > sea) {
            return true;
        }
        if (x < size - 1 && t.height[z * size + x + 1] > sea) {
            return true;
        }
        return z < size - 1 && t.height[(z + 1) * size + x] > sea;
    }

    /**
     * 静水「离岸多远」的近似值（0 = 贴岸浅水，1 = 湖心深水）。
     *
     * <p>静水没有像海那样可以直接用「低于海平面的深度」，但水色在真实航拍里同样由水深决定：
     * 岸边浅得发绿、湖心蓝得发黑。这里用半径 r 内的「水格占比」当代理——
     * 岸边一圈总有一半是陆地，湖心全是水，够用了。</p>
     *
     * @param t 地形
     * @param x 列
     * @param z 行
     * @param r 采样半径（格）
     * @return 0~1
     */
    private static double waterRim(TerrainData t, int x, int z, int r) {
        int size = t.size;
        int wet = 0;
        int all = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                all++;
                if (Biomes.isWater(t.biome[nz * size + nx])) {
                    wet++;
                }
            }
        }
        if (all == 0) {
            return 0;
        }
        double k = ((double) wet / all - 0.55) / 0.40;
        return Math.max(0, Math.min(1, k));
    }

    private static double hash(int x, int z, int seed) {
        long k = x * 374761393L + z * 668265263L + seed * 2246822519L;
        int h = (int) (k ^ (k >>> 29));
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return ((h & 0x7FFFFFFF) / (double) 0x7FFFFFFF);
    }

    /**
     * 亚格坐标的伪随机（高分辨率渲染用）：按最近的格取同一个值，保证颗粒不会随采样点漂移。
     */
    private static double hashReal(double x, double z, int seed) {
        return hash((int) Math.floor(x), (int) Math.floor(z), seed);
    }

    /** 平滑值噪声（格点 hash + smoothstep 双线性），用于斑块遮罩。 */
    private static double vnoise(double x, double z, int seed) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        double u = fx * fx * (3 - 2 * fx);
        double v = fz * fz * (3 - 2 * fz);
        double a = hash(xi, zi, seed);
        double b = hash(xi + 1, zi, seed);
        double c = hash(xi, zi + 1, seed);
        double d = hash(xi + 1, zi + 1, seed);
        double top = a + (b - a) * u;
        double bot = c + (d - c) * u;
        return top + (bot - top) * v;
    }

    /** 分形值噪声（oct 层，值域 0~1）。 */
    private static double fbm(double x, double z, int oct, int seed) {
        double sum = 0;
        double amp = 0.5;
        double norm = 0;
        double fx = x;
        double fz = z;
        for (int o = 0; o < oct; o++) {
            sum += vnoise(fx, fz, seed + o * 131) * amp;
            norm += amp;
            fx *= 2.0;
            fz *= 2.0;
            amp *= 0.5;
        }
        return norm > 0 ? sum / norm : 0;
    }

    private static double[] normalize(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) {
            return new double[]{0, 0, 1};
        }
        return new double[]{x / len, y / len, z / len};
    }

    private static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    private static int clamp255(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }
}
