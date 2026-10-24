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
 * 地形生成参数——AI（S0 世界档案）输出的语义参数 + 用户覆盖，全部是「可解释的物理/地理量」。
 * <p>设计原则：<b>没有一个字段是坐标</b>。AI 只描述「岛多狭长、山脊朝哪、海岸多碎、河有几条」，
 * 具体几何由 {@link TerrainGenerator} 确定性推导。所有字段都有合理默认值，
 * 缺项时按题材档兜底，保证「AI 挂掉也能出图」。</p>
 */
public class TerrainParams {

    // ==================== 尺度 ====================

    /** 种子（决定论：同种子同图）。 */
    public long seed = 20261016L;

    /** 边长（格）。 */
    public int size = 1024;

    /** 每格米数。 */
    public double metersPerCell = 1.0;

    /** 题材档：island / river_valley_village / coastal_town / mountain_hamlet / plain_city。 */
    public String genreProfile = "island";

    // ==================== 海陆 ====================

    /** 海平面（归一化高度，0~1；低于它即水面）。 */
    public double seaLevel = 0.30;

    /** 陆地半径占比（相对地图边长；0.32 → 岛直径约 0.64×地图宽）。 */
    public double islandRadiusRatio = 0.34;

    /** 狭长度（>1 沿主轴拉长）。 */
    public double elongation = 1.18;

    /** 主轴旋转角（度）。 */
    public double rotationDeg = 18;

    /** 海岸破碎度（0=浑圆，1=犬牙交错）。 */
    public double coastRoughness = 0.62;

    /** 海湾/岬角数量（2~4 自然）。 */
    public int bays = 3;

    /** 近岸浅海棚架宽度（格）。 */
    public double coastShelf = 54;

    // ==================== 地形 ====================

    /** 起伏强度（整体放大/压缩高度差）。 */
    public double reliefStrength = 1.0;

    /** 陆地最大抬升（归一化高度，相对海平面；0.52 ≈ 千米级高差在小岛上的观感）。 */
    public double reliefHeight = 0.52;

    /** 最高点海拔（米）——把归一化高度换算成真实海拔，用于坡度/日照/雪线判断。 */
    public double elevationMeters = 220;

    /** 山峰数量（沿山脊排布）。 */
    public int peakCount = 3;

    /** 山峰高度（归一化增量）。 */
    public double peakHeight = 0.34;

    /** 山脊走向（度，0=北）。 */
    public double ridgeBearingDeg = 32;

    /** 平原占比（0~1；越大越平，用于村镇/农田）。 */
    public double plainFraction = 0.38;

    /** 平原中心（归一化 0~1，通常是聚落所在）。 */
    public double plainCenterX = 0.52;

    /** 平原中心 Z。 */
    public double plainCenterZ = 0.58;

    /** 平原半径（归一化，相对地图边长）。 */
    public double plainRadius = 0.26;

    // ==================== 水系 ====================

    /** 期望河流条数（主河 + 支流）。 */
    public int riverCount = 2;

    /** 湖泊数量。 */
    public int lakeCount = 1;

    /** 河流密度（越小河网越密；乘在汇流阈值上）。 */
    public double riverDensity = 1.0;

    // ==================== 气候/植被 ====================

    /** 温度（0=寒，1=热）。 */
    public double temperature = 0.62;

    /** 湿度（0=干，1=湿）。 */
    public double moisture = 0.66;

    /** 森林偏置（1=常态，>1 更密）。 */
    public double forestBias = 1.0;

    /** 聚落中心 X（归一化，用于农田/林地分布）。 */
    public double settlementX = 0.52;

    /** 聚落中心 Z。 */
    public double settlementZ = 0.58;

    /** 聚落影响半径（归一化）。 */
    public double settlementRadius = 0.22;

    // ==================== 算法强度 ====================

    /** 水力侵蚀水滴数（0=不侵蚀）。 */
    public int erosionDroplets = 50000;

    /** 侵蚀强度（0~1）。 */
    public double erosionStrength = 0.35;

    /** 海滩宽度（格）。 */
    public double beachWidth = 3.0;

    // ==================== 语义区域（M2：世界→地图契约驱动） ====================

    /**
     * 语义区域表（湾/岬/丘陵/林/滩涂/聚落…）。
     * <p>空表 = 退回「全局标量 + 随机谐波」的旧行为；非空 = 海岸形状与高程结构
     * 由这些区域决定（见 {@link TerrainSemantics}）。</p>
     */
    public List<TerrainRegion> regions = new ArrayList<>();

    /** 海岸线是否由语义区域驱动（true 时忽略 {@link #bays} 的随机谐波）。 */
    public boolean coastRegionDriven = false;

    /**
     * 海岛小镇默认参数（通用海岛档：低丘、缓坡、多湾，不含任何具体世界的地名）。
     *
     * <p>具体世界的特征（湾在哪、岬多长、林地多密）由 {@link TerrainSemantics} 从世界契约里覆盖，
     * 这里只负责给出一套「没契约也能看」的基线。</p>
     *
     * @param seed 种子
     * @param size 边长
     * @return 参数
     */
    public static TerrainParams island(long seed, int size) {
        TerrainParams p = new TerrainParams();
        p.seed = seed;
        p.size = size;
        p.genreProfile = "island";
        p.seaLevel = 0.30;
        p.islandRadiusRatio = 0.36;
        p.elongation = 1.14;
        p.rotationDeg = 18;
        p.coastRoughness = 0.58;
        p.bays = 3;
        // 近岸棚架：52 米宽会在环岛铺出一圈 50 米宽的荧光青"光晕"（俯视图上像给岛描了发光边）。
        // 真实海岛的浅滩带通常只有 10~30 米，之后迅速转深；32 米在 1 公里图上刚好是"看得见、不抢眼"。
        p.coastShelf = 32;
        // 起伏与海拔：1 公里见方的岛，110 米 + 0.58 的起伏强度会在西北面堆出一大片
        // >43° 的陡坡（断崖群系实测占 9.7%），从空中看是一道灰色的"山体滑坡疤"。
        // 降到 96 米 / 0.50 之后山形仍在，但坡面回到常识范围。
        p.reliefStrength = 0.50;
        p.reliefHeight = 0.30;
        p.elevationMeters = 96;
        p.peakCount = 2;
        p.peakHeight = 0.26;
        p.ridgeBearingDeg = 148;
        p.plainFraction = 0.55;
        p.plainRadius = 0.34;
        p.plainCenterX = 0.47;
        p.plainCenterZ = 0.67;
        p.settlementX = 0.47;
        p.settlementZ = 0.67;
        p.settlementRadius = 0.34;
        p.riverCount = 2;
        p.lakeCount = 1;
        p.temperature = 0.68;
        p.moisture = 0.60;
        p.forestBias = 0.85;
        // 沙滩宽度：3 米太窄，实测只长出十几个沙格（≈0%），岸线变成陆地与海的硬切边。
        // 1 公里的岛，7 米沙滩是"能走人、能看见"的真实尺度。
        p.beachWidth = 7.0;
        p.erosionDroplets = 60000;
        p.erosionStrength = 0.18;
        return p;
    }

    /**
     * 河谷村落默认参数（青禾村题材档）：无海，四周环山，一条河从谷中穿过。
     *
     * @param seed 种子
     * @param size 边长
     * @return 参数
     */
    public static TerrainParams riverValley(long seed, int size) {
        TerrainParams p = new TerrainParams();
        p.seed = seed;
        p.size = size;
        p.genreProfile = "river_valley_village";
        p.seaLevel = 0.06;
        p.islandRadiusRatio = 1.06;
        p.elongation = 1.0;
        p.rotationDeg = 0;
        p.coastRoughness = 0.30;
        p.bays = 0;
        p.coastShelf = 10;
        p.reliefStrength = 1.15;
        p.reliefHeight = 0.62;
        p.peakCount = 4;
        p.peakHeight = 0.50;
        p.ridgeBearingDeg = 0;
        p.plainFraction = 0.52;
        p.plainRadius = 0.30;
        p.riverCount = 3;
        p.lakeCount = 0;
        p.temperature = 0.55;
        p.moisture = 0.58;
        p.forestBias = 1.15;
        p.erosionDroplets = 45000;
        return p;
    }
}
