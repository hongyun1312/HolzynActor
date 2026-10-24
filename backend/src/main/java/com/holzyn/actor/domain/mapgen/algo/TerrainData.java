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

import java.util.ArrayList;
import java.util.List;

/**
 * 地形数据（S1 产物）——一张地图的全部栅格事实，行主序一维数组。
 * <p>索引约定：{@code i = z * size + x}。所有数组长度恒为 {@code size*size}。</p>
 */
public class TerrainData {

    /** 边长（格）。 */
    public final int size;

    /** 海平面（归一化高度阈值）。 */
    public final double seaLevel;

    /** 高度场（0~1；海面以下 < seaLevel）。 */
    public final float[] height;

    /** 陆地遮罩（0~1；1=实心陆地，软边用于海岸平滑）。 */
    public final float[] mask;

    /** 水深（0~1；陆地为 0）。 */
    public final float[] depth;

    /** 汇流量（雨滴累计格数；越大越是主河道）。 */
    public float[] flow;

    /** 坡度（0~1，无量纲近似）。 */
    public final float[] slope;

    /** 平滑高度场（用于坡度/日照——1 米分辨率的原始高度太噪，直接算坡会把全岛判成陡坡）。 */
    public final float[] smooth;

    /** 河道掩码（由汇流提取，独立于 biome——渲染用矢量描边，不用逐格涂色）。 */
    public final boolean[] riverMask;

    /** 河道渲染遮罩（0~1，按汇流量做圆刷叠加——得到平滑河岸，而不是阶梯格）。 */
    public float[] riverAlpha;

    /** 连续场：归一化海拔（0=海平面，1=最高点）。 */
    public final float[] elevation;

    /** 连续场：湿度（0~1）。 */
    public final float[] moisture;

    /** 连续场：林地倾向（0~1.2）。 */
    public final float[] forest;

    /** 连续场：温度（0~1）。 */
    public final float[] temp;

    /** 连续场：离水距离（格，上限 255）。 */
    public final float[] distWater;

    /** 连续场：离海岸距离（格，上限 255；只算海，不算河湖——否则河岸会被画成沙滩）。 */
    public final float[] distSea;

    /** 连续场：离聚落距离（归一化，0=中心）。 */
    public final float[] settlement;

    /** 连续场：农田适宜度（0~1）。 */
    public final float[] farm;

    /** 生物群系编码（见 {@link Biomes}）。 */
    public final byte[] biome;

    /** 可建造度（0~1；S4 选址用）。 */
    public final float[] buildable;

    /** 河流折线（格坐标，首点在上游）。 */
    public List<double[]> rivers = new ArrayList<>();

    /** 湖泊折线/多边形中心（格坐标 + 面积）。 */
    public List<double[]> lakes = new ArrayList<>();

    /** 陆地占比（0~1）。 */
    public double landFraction;

    /** 可建造面积占比（0~1）。 */
    public double buildableFraction;

    /** 最高点高度。 */
    public double maxHeight;

    /** 最低点高度。 */
    public double minHeight;

    /** 每格米数（物理尺度换算用）。 */
    public double metersPerCell = 1.0;

    /** 高度单位 → 米 的换算系数（1.0 归一化高度 = 多少米；由题材档确定）。 */
    public double heightToMeters = 385.0;

    /**
     * 构造并分配数组。
     *
     * @param size     边长
     * @param seaLevel 海平面
     */
    public TerrainData(int size, double seaLevel) {
        this.size = size;
        this.seaLevel = seaLevel;
        int n = size * size;
        this.height = new float[n];
        this.mask = new float[n];
        this.depth = new float[n];
        this.flow = new float[n];
        this.slope = new float[n];
        this.smooth = new float[n];
        this.riverMask = new boolean[n];
        this.riverAlpha = new float[n];
        this.elevation = new float[n];
        this.moisture = new float[n];
        this.forest = new float[n];
        this.temp = new float[n];
        this.distWater = new float[n];
        this.distSea = new float[n];
        this.settlement = new float[n];
        this.farm = new float[n];
        this.biome = new byte[n];
        this.buildable = new float[n];
    }

    /**
     * 取格索引。
     *
     * @param x X（格）
     * @param z Z（格）
     * @return 一维索引
     */
    public int idx(int x, int z) {
        return z * size + x;
    }

    /**
     * 是否界内。
     *
     * @param x X
     * @param z Z
     * @return true=界内
     */
    public boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < size && z < size;
    }

    /**
     * 是否水面（高度低于海平面或标记为河湖）。
     *
     * @param x X
     * @param z Z
     * @return true=水
     */
    public boolean isWater(int x, int z) {
        if (!inside(x, z)) {
            return true;
        }
        return waterCell(idx(x, z));
    }

    /**
     * 是否水格（<b>按「水面」判，不按高程判</b>）。
     *
     * <p>为什么不直接用 {@code height <= seaLevel}：内陆湖的水面高程是填洼填出来的，
     * 往往<b>高于海平面</b>。只按高程判，湖会被当成旱地——于是道路笔直穿过湖面、
     * 渲染时湖被画成一片沙地（旧版「湖是棕色的」就是这么来的）。
     * 所以水面 = 低于海平面 ∨ 群系是湖/河 ∨ 河道遮罩。</p>
     *
     * @param i 格索引
     * @return true=水面
     */
    public boolean waterCell(int i) {
        return height[i] <= (float) seaLevel
                || biome[i] == Biomes.LAKE
                || biome[i] == Biomes.RIVER
                || riverMask[i];
    }

    /**
     * 是否水格（按坐标，越界视为水——界外不该修路盖房）。
     *
     * @param x X（格）
     * @param z Z（格）
     * @return true=水面
     */
    public boolean waterCell(int x, int z) {
        return !inside(x, z) || waterCell(idx(x, z));
    }

    /**
     * 是否<b>海</b>水格：只按「低于海平面」判，<b>不含</b>内陆湖与河道。
     *
     * <p>为什么要和 {@link #waterCell(int)} 分开：栈桥必须伸进能走船的海——内陆湖的水面高程
     * 是填洼填出来的，往往高于海平面；按 {@code waterCell} 判，码头会长到湖中央去。
     * 反过来，各处各写一遍 {@code height &lt;= seaLevel} 又会回到「水陆判定两套」的老路，
     * 所以这里给一个明确的入口，栈桥一类<b>只能在海里</b>的水工物走它。</p>
     *
     * @param i 格索引
     * @return true= seawater
     */
    public boolean seaCell(int i) {
        return height[i] <= (float) seaLevel;
    }

    /**
     * 高度采样（双线性，越界按边缘钳制）。
     *
     * @param x X（可为小数）
     * @param z Z（可为小数）
     * @return 高度
     */
    public double sampleHeight(double x, double z) {
        double cx = Math.max(0, Math.min(size - 1.001, x));
        double cz = Math.max(0, Math.min(size - 1.001, z));
        int x0 = (int) cx;
        int z0 = (int) cz;
        double fx = cx - x0;
        double fz = cz - z0;
        int x1 = Math.min(size - 1, x0 + 1);
        int z1 = Math.min(size - 1, z0 + 1);
        double h00 = height[idx(x0, z0)];
        double h10 = height[idx(x1, z0)];
        double h01 = height[idx(x0, z1)];
        double h11 = height[idx(x1, z1)];
        double a = h00 + (h10 - h00) * fx;
        double b = h01 + (h11 - h01) * fx;
        return a + (b - a) * fz;
    }
}
