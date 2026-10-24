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

import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「世界→地图契约」的语义区域写进 {@link TerrainParams}（M2 地形语义化）。
 *
 * <p>旧管线只有全局标量，于是无论世界观怎么写，出来的都是一座浑圆绿岛——
 * 「岛形如弯月、凹面朝西抱海湾、北端海岬有灯塔」这类描述在图上完全看不出来。
 * 这一层做三件事：</p>
 * <ol>
 *   <li><b>海岸形状</b>：湾 → 内凹，岬 → 外凸，滩涂 → 岸线缓推。由此得到有机的月牙形；</li>
 *   <li><b>高程结构</b>：丘陵抬高、滩涂压平、聚落压成缓坡，让「北高南低」这类地势真的存在；</li>
 *   <li><b>聚落位置</b>：契约给的方位角决定平原/镇心落在岛的哪一侧，而不是写死 0.52/0.58。</li>
 * </ol>
 *
 * <p><b>铁律：不含任何世界观内容</b>。这里只认 {@code kind} 这类通用语义
 * （BAY/CAPE/HILLS/FOREST/MUDFLAT/FIELD/CREEK/SETTLEMENT），换任何世界观都成立。</p>
 */
public final class TerrainSemantics {

    /** 区域高程乘子的插值指数上限（超过就会把地形压成平地/糊成尖塔）。 */
    private static final double MAX_FACTOR = 2.4;
    private static final double MIN_FACTOR = 0.06;

    private TerrainSemantics() {
    }

    /**
     * 把契约的区域/聚落语义写进地形参数（幂等：全部是「赋值」而不是「累加」）。
     *
     * @param p     地形参数（就地修改）
     * @param brief 世界→地图契约（可为 null / 空）
     * @return 实际生效的区域数
     */
    public static int apply(TerrainParams p, WorldMapBrief brief) {
        if (p == null || brief == null) {
            return 0;
        }
        p.regions = toRegions(brief.regions());
        p.coastRegionDriven = hasCoastRegion(p.regions);
        if (p.coastRegionDriven) {
            // 契约已经给出湾/岬，就不能再让随机谐波生成一套——两套叠在一起会把岛切碎
            p.bays = 0;
        }
        // 岛半径上限：1 公里图幅里，基准半径超过 0.35 图宽时，
        // 「形状谐波 + 岬角外凸」会把陆地推到图边、被画布裁断（实测东侧顶到 x=510）。
        p.islandRadiusRatio = clamp(p.islandRadiusRatio, 0.16, 0.35);
        p.coastRoughness = clamp(p.coastRoughness, 0.18, 0.55);
        applySettlement(p, brief);
        return p.regions.size();
    }

    /**
     * 契约区域 → 地形区域（夹取到合法区间，丢弃无效项）。
     *
     * @param regions 契约区域
     * @return 地形区域（非 null）
     */
    private static List<TerrainRegion> toRegions(List<WorldMapBrief.Region> regions) {
        List<TerrainRegion> out = new ArrayList<>();
        if (regions == null) {
            return out;
        }
        for (WorldMapBrief.Region r : regions) {
            if (r == null) {
                continue;
            }
            String kind = r.kind() == null ? "" : r.kind().trim().toUpperCase(java.util.Locale.ROOT);
            if (!isKnownKind(kind)) {
                continue;
            }
            double from = clamp(r.radialFrom(), 0, 1);
            double to = clamp(r.radialTo(), 0, 1);
            if (to < from) {
                double tmp = from;
                from = to;
                to = tmp;
            }
            out.add(new TerrainRegion(
                    r.code() == null ? "" : r.code(),
                    r.name() == null ? "" : r.name(),
                    kind,
                    wrapDeg(r.bearingDeg()),
                    from,
                    Math.max(from + 0.05, to),
                    clamp(r.elevationBias(), -1, 1),
                    clamp(r.treeDensity(), 0, 1),
                    clamp(r.moistureBias(), -1, 1)));
        }
        return out;
    }

    /** 是否是程序认识的地貌类型（未知类型直接忽略，不能瞎猜形状）。 */
    private static boolean isKnownKind(String kind) {
        return switch (kind) {
            case TerrainRegion.BAY, TerrainRegion.CAPE, TerrainRegion.HILLS, TerrainRegion.FOREST,
                 TerrainRegion.MUDFLAT, TerrainRegion.FIELD, TerrainRegion.CREEK,
                 TerrainRegion.SETTLEMENT -> true;
            default -> false;
        };
    }

    /** 是否给出至少一个海岸类区域（湾/岬/滩涂）——有的话海岸线由契约驱动。 */
    private static boolean hasCoastRegion(List<TerrainRegion> regions) {
        for (TerrainRegion r : regions) {
            if (TerrainRegion.BAY.equals(r.kind()) || TerrainRegion.CAPE.equals(r.kind())
                    || TerrainRegion.MUDFLAT.equals(r.kind())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 聚落 → 平原中心 + 聚落半径。
     *
     * <p>契约只给「聚落在岛的哪个方位、多大」，具体落到哪个格由 {@code MapSites.findCore}
     * 在附近滑窗挑「最平的一块」——所以这里只负责把搜索起点放到正确的一侧。</p>
     *
     * @param p     地形参数
     * @param brief 契约
     */
    private static void applySettlement(TerrainParams p, WorldMapBrief brief) {
        double bearing = 180;
        boolean hasBearing = false;
        if (brief.settlement() != null) {
            bearing = brief.settlement().centerBearingDeg();
            hasBearing = true;
        }
        // 半径：优先用契约给的实际距离（米），没有就用约 1/3 岛半径
        double radial = 0.34;
        for (TerrainRegion r : p.regions) {
            if (TerrainRegion.SETTLEMENT.equals(r.kind())) {
                if (!hasBearing) {
                    bearing = r.bearingDeg();
                }
                radial = (r.radialFrom() + Math.min(r.radialTo(), r.radialFrom() + 0.35)) * 0.5;
                break;
            }
        }
        double ang = TerrainRegion.bearingToRad(bearing);
        double rn = clamp(radial, 0.05, 0.62) * clamp(p.islandRadiusRatio, 0.1, 1.0);
        p.plainCenterX = clamp(0.5 + Math.cos(ang) * rn, 0.16, 0.84);
        p.plainCenterZ = clamp(0.5 + Math.sin(ang) * rn, 0.16, 0.84);
        p.settlementX = p.plainCenterX;
        p.settlementZ = p.plainCenterZ;

        // 聚落半径：契约的 radiusM 换算成归一化，再用「建筑预算反推的合理半径」当上下限
        double sizeM = p.size * Math.max(0.05, p.metersPerCell);
        double wantM = brief.settlement() == null || brief.settlement().radiusM() <= 0
                ? 0 : brief.settlement().radiusM();
        double wantNorm = wantM <= 0 ? 0 : wantM / sizeM;
        double byCountNorm = brief.suggestedTownRadiusM(sizeM * 0.12) / sizeM;
        double radiusNorm = wantNorm > 0
                ? clamp(Math.min(Math.max(wantNorm * 0.55, byCountNorm), wantNorm), 0.05, 0.45)
                : clamp(byCountNorm * 1.5, 0.05, 0.45);
        p.plainRadius = clamp(radiusNorm * 1.15, 0.06, 0.48);
        p.settlementRadius = clamp(radiusNorm * 1.35, 0.08, 0.55);
        // 平原占比：小聚落不需要一整片大平原，压到 0.4 左右才有「山脚下几户人家」的感觉
        p.plainFraction = clamp(0.30 + 0.012 * brief.dwellingTarget(), 0.30, 0.62);
    }

    /**
     * 某点的高程乘子（供 {@link TerrainGenerator} 使用）。
     *
     * @param regions  区域表
     * @param bearing  该点极坐标弧度
     * @param radialFrac 该点相对岛屿半径的归一化半径
     * @return 乘子（0.06~2.4）
     */
    public static double elevationFactor(List<TerrainRegion> regions, double bearing, double radialFrac) {
        if (regions == null || regions.isEmpty()) {
            return 1.0;
        }
        // 几何插值：多个区域重叠时按权重取乘子的对数平均，避免「两个丘陵重叠 → 四倍高」
        double logSum = 0;
        double wSum = 0;
        for (TerrainRegion r : regions) {
            double w = r.weight(bearing, radialFrac);
            if (w <= 0.001) {
                continue;
            }
            logSum += w * Math.log(r.elevationFactor());
            wSum += w;
        }
        if (wSum <= 1e-6) {
            return 1.0;
        }
        double f = StrictMath.exp(logSum / wSum);
        return clamp(f, MIN_FACTOR, MAX_FACTOR);
    }

    /**
     * 某点的植被/湿度偏置（供 {@link BiomeAssigner} 使用）。
     *
     * @param regions    区域表
     * @param bearing    极坐标弧度
     * @param radialFrac 归一化半径
     * @return {植被偏置, 湿度偏置}（-1~1）
     */
    public static double[] climateBias(List<TerrainRegion> regions, double bearing, double radialFrac) {
        if (regions == null || regions.isEmpty()) {
            return new double[]{0, 0};
        }
        double tree = 0;
        double moist = 0;
        double wSum = 0;
        for (TerrainRegion r : regions) {
            double w = r.weight(bearing, radialFrac);
            if (w <= 0.001) {
                continue;
            }
            // 滩涂的 treeDensity 本来就很低，"没有树"应该由高湿度 + 低高程去表达，
            // 不能让它把整片区域的 forest 值也拉负（负值会让荒地/灌木抢走滩涂）
            tree += w * clamp(r.treeDensity() * 2.0 - 0.55, -0.6, 0.9);
            moist += w * r.moistureBias();
            wSum += w;
        }
        if (wSum <= 1e-6) {
            return new double[]{0, 0};
        }
        return new double[]{clamp(tree / wSum, -1, 1), clamp(moist / wSum, -1, 1)};
    }

    /**
     * 某类区域的综合权重（0~1）。
     *
     * <p>用来问「这个格子在不在滩涂里」这类问题——{@link #climateBias} 混了植被与湿度，
     * 表达不了「这是泥滩，该是湿地而不是草甸」这种<b>类型</b>判断。</p>
     *
     * @param regions    区域表
     * @param kind       区域类型（如 {@link TerrainRegion#MUDFLAT}）
     * @param bearing    极坐标弧度
     * @param radialFrac 归一化半径
     * @return 0~1
     */
    public static double kindWeight(List<TerrainRegion> regions, String kind, double bearing, double radialFrac) {
        if (regions == null || regions.isEmpty() || kind == null) {
            return 0;
        }
        double w = 0;
        for (TerrainRegion r : regions) {
            if (kind.equals(r.kind())) {
                w = Math.max(w, r.weight(bearing, radialFrac));
            }
        }
        return w;
    }

    /** 归一化方位角到 [0,360)。 */
    private static double wrapDeg(double d) {
        double v = d % 360.0;
        return v < 0 ? v + 360.0 : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
