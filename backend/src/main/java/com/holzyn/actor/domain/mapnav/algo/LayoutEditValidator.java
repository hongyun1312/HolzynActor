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

package com.holzyn.actor.domain.mapnav.algo;

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;

import java.util.ArrayList;
import java.util.List;

/**
 * 布局编辑的几何闸门：把「用户想把这栋房子挪到这儿」翻译成「行 / 不行，为什么」。
 *
 * <p><b>为什么校验必须在服务端重做一遍</b>：前端拖拽时的红绿提示只是体验（它拿的是 128² 缩略位图），
 * 真正的判定要拿 1024² 的水/坡 + 精确道路宽度 + 全部建筑轮廓。前端判「能放」而服务端判「不能」，
 * 用户会觉得自己被耍；反过来（前端拦、服务端放）则是永久性困惑。所以两边用同一套规则，
 * 但**只有服务端的结果算数**。</p>
 *
 * <p>这里刻意做成无状态静态函数：不碰 Spring、不碰库，给定「水体/坡度/道路/其他建筑」就能算，
 * 单测里用一张手搓图即可覆盖全部七条规则。</p>
 */
public final class LayoutEditValidator {

    /** 违规项。 */
    public record Violation(String rule, String message) {
    }

    /** 待校验的候选位置（米；w/d 为未旋转尺寸，rot 为朝向度数）。 */
    public record Candidate(String code, double x, double z, double w, double d, double rot) {
    }

    /** 校验参数。 */
    public record Limits(double mapSize, double margin, double roadClearance, double buildingGap,
                         double maxSlope, double maxDoorToRoad) {
    }

    private LayoutEditValidator() {
    }

    /**
     * 校验一个候选位置（不含门到路距离——那条是「可自动修」的软规则）。
     *
     * @param c        候选
     * @param grid     导航栅格（可空：空则跳过水/坡，只做几何校验）
     * @param roads    道路
     * @param others   其他要素（自身除外）
     * @param limits   阈值
     * @return 违规列表；空 = 合法
     */
    public static List<Violation> validate(Candidate c, NavGrid grid, List<ActorMapRoad> roads,
                                           List<ActorMapFeature> others, Limits limits) {
        List<Violation> out = new ArrayList<>();
        double[][] corners = corners(c.x(), c.z(), c.w(), c.d(), c.rot());
        // ① 边距
        double margin = limits.margin();
        for (double[] p : corners) {
            if (p[0] < margin || p[1] < margin
                    || p[0] > limits.mapSize() - margin || p[1] > limits.mapSize() - margin) {
                out.add(new Violation("bounds", "超出地图边界"));
                break;
            }
        }
        if (grid != null) {
            // ② 水（按格中心是否落进旋转矩形；水陆只认 NavGrid.water，不重算高程）
            int hitWater = firstCellInside(grid, corners, (x, z) -> grid.isWater(x, z));
            if (hitWater >= 0) {
                out.add(new Violation("water", "与水面/河道相交"));
            }
            // ③ 坡度（四角 + 中心，坡度取栅格四舍五入值）
            double slope = 0;
            for (double[] p : corners) {
                slope = Math.max(slope, grid.slopeAt(grid.cellOf(p[0]), grid.cellOf(p[1])));
            }
            slope = Math.max(slope, grid.slopeAt(grid.cellOf(c.x()), grid.cellOf(c.z())));
            if (slope > limits.maxSlope()) {
                out.add(new Violation("slope", String.format("地面过陡（坡度 %.2f）", slope)));
            }
        }
        // ④ 压路（矩形到路中心线的最近距离 < 半路宽 + 净空）
        String roadHit = null;
        for (ActorMapRoad r : roads) {
            if (r.getPathJson() == null || r.getPathJson().isBlank()) {
                continue;
            }
            List<double[]> pts = parsePath(r.getPathJson());
            double halfW = (r.getWidth() == null ? 3.0 : r.getWidth()) / 2.0;
            double need = halfW + limits.roadClearance();
            for (int i = 1; i < pts.size(); i++) {
                if (rectSegmentDistance(corners, pts.get(i - 1), pts.get(i)) < need) {
                    roadHit = r.getName() == null || r.getName().isBlank() ? r.getCode() : r.getName();
                    break;
                }
            }
            if (roadHit != null) {
                break;
            }
        }
        if (roadHit != null) {
            out.add(new Violation("road", "压住 " + roadHit));
        }
        // ⑤ 重叠（旋转矩形分离轴；只跟建筑/围墙比，田/林可以挨着）
        double gap = limits.buildingGap();
        for (ActorMapFeature f : others) {
            if (!ActorMapFeature.KIND_BUILDING.equals(f.getKind())
                    && !ActorMapFeature.KIND_WALL.equals(f.getKind())) {
                continue;
            }
            if (c.code() != null && c.code().equals(f.getCode())) {
                continue;
            }
            double w = f.getSizeW() == null ? 6 : f.getSizeW();
            double d = f.getSizeD() == null ? 6 : f.getSizeD();
            double rot = f.getRotation() == null ? 0 : f.getRotation();
            if (obbOverlap(c.x(), c.z(), c.w() + gap, c.d() + gap, c.rot(),
                    f.getPosX(), f.getPosZ(), w + gap, d + gap, rot)) {
                out.add(new Violation("overlap", "与 " + nameOf(f) + " 重叠"));
                break;
            }
        }
        return out;
    }

    /**
     * 门到最近道路的距离（超过阈值时由调用方决定「自动补接入路」还是拒绝）。
     *
     * @param doorX 门 X
     * @param doorZ 门 Z
     * @param roads 道路
     * @return 最小距离（米）；没有道路返回 Double.MAX_VALUE
     */
    public static double nearestRoadDistance(double doorX, double doorZ, List<ActorMapRoad> roads) {
        double best = Double.MAX_VALUE;
        for (ActorMapRoad r : roads) {
            if (r.getPathJson() == null || r.getPathJson().isBlank()) {
                continue;
            }
            List<double[]> pts = parsePath(r.getPathJson());
            double halfW = (r.getWidth() == null ? 3.0 : r.getWidth()) / 2.0;
            for (int i = 1; i < pts.size(); i++) {
                double d = pointSegment(pts.get(i - 1), pts.get(i), doorX, doorZ) - halfW;
                best = Math.min(best, Math.max(0, d));
            }
        }
        return best;
    }

    /**
     * 由中心/尺寸/朝向算四个角（与前端 {@code rectCorners} 同一约定：rot 顺时针、0=北）。
     *
     * @param cx  中心 X
     * @param cz  中心 Z
     * @param w   面宽
     * @param d   进深
     * @param rot 朝向（度）
     * @return 四角 {{x,z}…}
     */
    public static double[][] corners(double cx, double cz, double w, double d, double rot) {
        double r = Math.toRadians(rot);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        double hw = w / 2;
        double hd = d / 2;
        double[][] local = {{-hw, -hd}, {hw, -hd}, {hw, hd}, {-hw, hd}};
        double[][] out = new double[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = cx + local[i][0] * cos + local[i][1] * sin;
            out[i][1] = cz - local[i][0] * sin + local[i][1] * cos;
        }
        return out;
    }

    /** 旋转矩形是否与另一旋转矩形相交（分离轴定理，四个轴取最小重叠）。 */
    public static boolean obbOverlap(double ax, double az, double aw, double ad, double arot,
                                     double bx, double bz, double bw, double bd, double brot) {
        double[][] a = corners(ax, az, aw, ad, arot);
        double[][] b = corners(bx, bz, bw, bd, brot);
        return !separated(a, b) && !separated(b, a);
    }

    /** 沿某多边形的两条边做分离测试：存在分离轴则不相交。 */
    private static boolean separated(double[][] poly, double[][] other) {
        for (int i = 0; i < 4; i++) {
            double[] p1 = poly[i];
            double[] p2 = poly[(i + 1) % 4];
            double nx = -(p2[1] - p1[1]);
            double nz = p2[0] - p1[0];
            double len = Math.hypot(nx, nz);
            if (len < 1e-9) {
                continue;
            }
            nx /= len;
            nz /= len;
            double minA = Double.MAX_VALUE;
            double maxA = -Double.MAX_VALUE;
            for (double[] p : poly) {
                double proj = p[0] * nx + p[1] * nz;
                minA = Math.min(minA, proj);
                maxA = Math.max(maxA, proj);
            }
            double minB = Double.MAX_VALUE;
            double maxB = -Double.MAX_VALUE;
            for (double[] p : other) {
                double proj = p[0] * nx + p[1] * nz;
                minB = Math.min(minB, proj);
                maxB = Math.max(maxB, proj);
            }
            if (maxA < minB || maxB < minA) {
                return true;
            }
        }
        return false;
    }

    /** 找第一个满足条件的格（在矩形包围盒内、且格中心在矩形内）。 */
    private static int firstCellInside(NavGrid grid, double[][] corners,
                                       java.util.function.BiPredicate<Integer, Integer> test) {
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] p : corners) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        int x0 = grid.cellOf(minX);
        int x1 = grid.cellOf(maxX);
        int z0 = grid.cellOf(minZ);
        int z1 = grid.cellOf(maxZ);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (pointInPolygon(x + 0.5, z + 0.5, corners) && test.test(x, z)) {
                    return z * grid.size + x;
                }
            }
        }
        return -1;
    }

    /** 点是否在多边形内（射线法）。 */
    public static boolean pointInPolygon(double px, double pz, double[][] poly) {
        boolean inside = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0];
            double zi = poly[i][1];
            double xj = poly[j][0];
            double zj = poly[j][1];
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 矩形到线段的最近距离（四角 + 边中点采样近似，够用于「是否压路」判定）。 */
    private static double rectSegmentDistance(double[][] corners, double[] a, double[] b) {
        double best = Double.MAX_VALUE;
        for (double[] p : corners) {
            best = Math.min(best, pointSegment(a, b, p[0], p[1]));
        }
        for (int i = 0; i < 4; i++) {
            double[] p1 = corners[i];
            double[] p2 = corners[(i + 1) % 4];
            double[] mid = new double[]{(p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2};
            best = Math.min(best, pointSegment(a, b, mid[0], mid[1]));
        }
        // 线段端点落进矩形 → 距离 0（路从房子中间穿过）
        if (pointInPolygon(a[0], a[1], corners) || pointInPolygon(b[0], b[1], corners)) {
            return 0;
        }
        return best;
    }

    /** 点到线段距离。 */
    private static double pointSegment(double[] a, double[] b, double px, double pz) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-12) {
            return Math.hypot(px - a[0], pz - a[1]);
        }
        double t = Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / len2));
        return Math.hypot(px - (a[0] + dx * t), pz - (a[1] + dz * t));
    }

    /** 解析折线 JSON（容错：坏数据当空）。 */
    static List<double[]> parsePath(String json) {
        List<double[]> out = new ArrayList<>();
        try {
            tools.jackson.databind.JsonNode arr =
                    new tools.jackson.databind.ObjectMapper().readTree(json);
            for (tools.jackson.databind.JsonNode p : arr) {
                out.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
            }
        } catch (Exception ignored) {
            // 坏数据 → 空折线，交给上层当作「没有这条路」
        }
        return out;
    }

    private static String nameOf(ActorMapFeature f) {
        return f.getName() == null || f.getName().isBlank() ? f.getCode() : f.getName();
    }
}
