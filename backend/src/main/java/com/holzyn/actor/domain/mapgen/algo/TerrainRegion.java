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
 * 地形区域偏置——「世界→地图契约」里的一个地理区块（海湾/岬角/丘陵/林子/滩涂…）。
 *
 * <p>为什么需要这一层：旧管线只有一组全局标量（岛多圆、山脊朝哪、几条河），
 * 于是无论世界观怎么写，生成的都是一座「浑圆的绿岛」。而真实海岛是有<b>结构</b>的——
 * 凹面朝海的湾、伸出去的岬、北坡的松林、南岸的泥滩。这些在叙事里本来就是
 * 「相对聚落的方向 + 远近」，所以这里用<b>极坐标（方位角 + 半径区间）</b>描述，
 * 既不需要 AI 给坐标，也能让程序把形状真正捏出来。</p>
 *
 * <p>方位角约定：<b>0=北、90=东、180=南、270=西</b>（罗盘角，顺时针）。
 * 换算到地图屏幕坐标时 {@code x=+cos(θ)、z=+sin(θ)}，其中 z 向南增长，
 * 所以 {@link #bearingToRad(double)} 减 90°。</p>
 *
 * @param code           区域编码（ASCII，供落位/调试追索引）
 * @param name           区域名（世界观里的叫法，仅展示用）
 * @param kind           区域类型（BAY/CAPE/HILLS/FOREST/MUDFLAT/FIELD/CREEK/SETTLEMENT）
 * @param bearingDeg     中心方位角（罗盘角，0=北）
 * @param radialFrom     内半径（相对岛屿半径的归一化比例，0=中心）
 * @param radialTo       外半径（同上）
 * @param elevationBias  高程偏置（-1=压到近海面，+1=明显抬高）
 * @param treeDensity    植被密度偏置（0=光秃，1=浓密）
 * @param moistureBias   湿度偏置（-1=干，+1=湿）
 */
public record TerrainRegion(String code, String name, String kind,
                            double bearingDeg, double radialFrom, double radialTo,
                            double elevationBias, double treeDensity, double moistureBias) {

    /** 海湾（向陆内凹，形成新月形海岸）。 */
    public static final String BAY = "BAY";
    /** 岬角（向海突出）。 */
    public static final String CAPE = "CAPE";
    /** 丘陵/山地。 */
    public static final String HILLS = "HILLS";
    /** 林地。 */
    public static final String FOREST = "FOREST";
    /** 滩涂/潮间泥滩。 */
    public static final String MUDFLAT = "MUDFLAT";
    /** 农田/缓坡台地。 */
    public static final String FIELD = "FIELD";
    /** 溪谷。 */
    public static final String CREEK = "CREEK";
    /** 聚落。 */
    public static final String SETTLEMENT = "SETTLEMENT";

    /** 罗盘方位角（度，0=北顺时针）→ 地图极坐标弧度（0=东，顺时针向 z+）。 */
    public static double bearingToRad(double bearingDeg) {
        return Math.toRadians(bearingDeg - 90.0);
    }

    /**
     * 该类型的方位角半宽（弧度，标准差）。
     * <p>湾和滩涂是「大尺度地理」，角宽必须够宽，否则会切出一道刀口；
     * 岬角和溪谷是「细长地貌」，窄一些才有尖角。</p>
     *
     * @return 角半宽（弧度）
     */
    public double angularSigma() {
        return switch (kind == null ? "" : kind) {
            case BAY -> 0.80;
            case CAPE -> 0.24;
            case MUDFLAT -> 0.52;
            case HILLS -> 0.62;
            case FOREST -> 0.58;
            case FIELD -> 0.40;
            case CREEK -> 0.20;
            case SETTLEMENT -> 0.60;
            default -> 0.45;
        };
    }

    /**
     * 方位角权重（高斯，0~1）。
     *
     * @param bearingRad 待测点的极坐标弧度
     * @return 0~1
     */
    public double angularWeight(double bearingRad) {
        double d = angleDiff(bearingRad, bearingToRad(bearingDeg));
        double s = angularSigma();
        return StrictMath.exp(-(d * d) / (2 * s * s));
    }

    /**
     * 半径权重（0~1）。
     *
     * <p>海岸类区域（湾/岬/滩涂）用的是「以内半径起始、向外一直生效」的单调权重——
     * 湾是海岸线往陆地里凹进去，它不该只作用在一圈环带上；如果内外都软，
     * 湾心会留下一座「湾中间的孤岛」。而陆地区域（丘陵/林/田/聚落）用环带权重，
     * 严格落在 [radialFrom, radialTo] 之间。</p>
     *
     * @param radialFrac 待测点相对岛屿半径的归一化半径（0=中心）
     * @return 0~1
     */
    public double radialWeight(double radialFrac) {
        double rf = Math.max(0, radialFrac);
        double from = Math.max(0, Math.min(1, radialFrom));
        double to = Math.max(from + 0.05, Math.min(1.02, radialTo));
        String k = kind == null ? "" : kind;
        if (BAY.equals(k) || CAPE.equals(k) || MUDFLAT.equals(k)) {
            // 海岸类：从 from 起向内到「岸」都生效，向外不衰减（岸线本身在外面）
            return sstep(from * 0.55, from * 0.55 + 0.28, rf);
        }
        return sstep(from, from + 0.16, rf) * (1.0 - sstep(to - 0.16, to, rf));
    }

    /**
     * 综合权重（方位 × 半径）。
     *
     * @param bearingRad 极坐标弧度
     * @param radialFrac 归一化半径
     * @return 0~1
     */
    public double weight(double bearingRad, double radialFrac) {
        return angularWeight(bearingRad) * radialWeight(radialFrac);
    }

    /**
     * 高程乘子——按类型给出「这块地该有多高/多平」的系数。
     * <p>用乘子而不是加数：高度场本身有噪声起伏，加一个常数会让高处更高、低处倒挂；
     * 乘法保证「整体压低/整体抬高」，形状仍然由噪声决定。</p>
     *
     * @return 乘子（0.06~2.2）
     */
    public double elevationFactor() {
        return switch (kind == null ? "" : kind) {
            case BAY -> 0.26;
            case CAPE -> 1.75;
            case HILLS -> 1.95;
            case FOREST -> 0.78;
            case MUDFLAT -> 0.16;
            case FIELD -> 0.55;
            case CREEK -> 0.72;
            case SETTLEMENT -> 0.42;
            default -> 1.0;
        };
    }

    /** 方位角差（归一化到 [-π, π]）。 */
    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= Math.PI * 2;
        }
        while (d < -Math.PI) {
            d += Math.PI * 2;
        }
        return d;
    }

    /** 平滑阶跃（v 从 a 到 b 平滑升到 1）。 */
    private static double sstep(double a, double b, double v) {
        if (b <= a) {
            return v < a ? 0 : 1;
        }
        double t = (v - a) / (b - a);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }
}
