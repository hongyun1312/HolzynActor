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

package com.holzyn.actor.domain.mapgen.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * 街巷网络（S3）——把「AI 给的目的地拓扑」补成一座真的村镇。
 * <p><b>为什么必须由程序定骨架？</b>AI 只会给「码头 → 镇中心 → 农田 → 林地」这种拓扑骨架：
 * 每条边都以镇中心为端点。如果直接按这些边求线，得到的就是十几条从广场射出去的放射线——
 * 航拍上是一个蜘蛛，<b>没有任何真实村镇长这样</b>（用户的原话：越开发越乱）。</p>
 * <p>真实村镇的形态规律，本类按四条依次复现（{@link #frame}）：</p>
 * <ol>
 *   <li><b>正街定朝向</b>：一条贯通全镇的主街，走向取「能连续通行最长的走廊」；</li>
 *   <li><b>后街平行</b>：主街两侧各一条平行后街，间距 30~40 米（一户院落的进深）；</li>
 *   <li><b>横巷切块</b>：每隔 40~56 米横切一道，把条带切成街区，遇街即停（T 字口）；</li>
 *   <li><b>尽端胡同</b>：{@link #alleys} 从街边伸进街区 12~25 米，尽端就是院门。</li>
 * </ol>
 * <p><b>没有环线</b>：旧实现在镇中心套一圈 20~26 米的「广场环巷」，它与正街相交两次、
 * 在航拍上就是一个圆圈压着街，用户已明确判定为 BUG。真实村镇只有两种路：
 * 贯通的路（正街/后街）和接在它们边上的支路（横巷/胡同/目的地路），<b>不存在绕中心的闭合环路</b>，
 * 因此本类不再生成任何环形路。广场改为靠正街一侧铺开（{@code MapPlacer#placePlaza}）。</p>
 * <p>每一条街都是「沿可建区行走」的结果（{@link #walk}）：遇到陡坡/水面/林地就缓缓拐弯绕过去，
 * 走不通就断在那里，所以绝不会横穿山脊、跳进海里或压着湖面走。</p>
 * <p>AI 的目的地路（码头、灯塔、林道、田区）由 {@code MapRoadPlanService} 用
 * {@link #nearestOn} 接到骨架的边上，形成 T 字口——<b>没有一条路是直接接到广场上的</b>。</p>
 */
public final class StreetNetwork {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreetNetwork.class);

    /** 行走步长（米）。 */
    private static final double STEP = 2.0;

    /** 走不通时尝试的偏转角（弧度）——从小到大，先小拐再大拐。 */
    private static final double[] DEVIATIONS = {0.18, -0.18, 0.38, -0.38, 0.66, -0.66, 1.0, -1.0};

    /** 与已有道路重合度超过它，就认为这条巷子是重复的。 */
    private static final double DUP_TOL = 3.2;

    private static final double DUP_FRAC = 0.55;

    private StreetNetwork() {
    }

    /**
     * 生成村镇的<b>街巷骨架</b>——这是 S3 真正的主体。
     *
     * <p><b>为什么骨架必须由程序定，而不是听 AI 的</b>：AI 给的路网拓扑本质是「目的地 + 连线」，
     * 每条边都以镇中心为端点，于是十几条路挤在同一个点上放射——航拍就是一个蜘蛛，
     * <b>没有任何真实村镇长这样</b>。真实聚落的主体是<b>街</b>：一条贯通的正街定朝向，
     * 一两条平行后街分担入户，横巷把两者连成梯子，相遇处自然成 T 字口。</p>
     *
     * <p>骨架只依赖地形（可建区），不依赖 AI，因此每次重生成都稳定可复现。
     * AI 的目的地路（码头、灯塔、林道、田区）由 {@code MapRoadPlanService}
     * <b>接到骨架的边上</b>（T 字口），而不是接到广场。</p>
     *
     * @param t      地形
     * @param seed   种子
     * @param coreX  聚落中心 X（米）
     * @param coreZ  聚落中心 Z（米）
     * @param radiusMeters 聚落半径（米）
     * @return 街巷（正街 / 后街 / 横巷），不含任何环线
     */
    public static List<MapPlacer.RoadSpec> frame(TerrainData t, long seed,
                                                 double coreX, double coreZ, double radiusMeters) {
        Rng rng = Rng.derive(seed, 0x51ee_7a11L);
        Noise2D noise = new Noise2D(rng);
        int size = t.size;
        double mpc = Math.max(0.05, t.metersPerCell);
        double radius = Math.max(70, Math.min(radiusMeters, 300));

        List<MapPlacer.RoadSpec> out = new ArrayList<>();
        List<List<double[]>> lines = new ArrayList<>();

        // 可建区按 1.25 倍半径算：街要走到镇边才有「镇口」，但不该长到林子里去
        boolean[] urban = urbanMask(t, size, mpc, coreX, coreZ, radius * 1.25, false);
        // 兜底①：镇心周边一个「合格街区格」都没有（整片林地/湿地/陡坡）时，放宽口径重算一次。
        // 为什么不在严格口径下直接交空表：S3 的空骨架会让整张图变成「ready 但 0 条路、0 栋房」，
        // 用户看到的是生成成功却什么都没有——这比「村里的街从林间空地穿过」糟得多。
        // 实测：seed=1825809105（AI 关闭）镇心落在 FOREST_LIGHT 小树丛里，严格口径 17872 格里
        // 一个都不合格，整张图没有一条路。
        if (urban == null || countUrban(urban) < 50) {
            boolean[] relaxed = urbanMask(t, size, mpc, coreX, coreZ, radius * 1.25, true);
            if (relaxed != null) {
                urban = relaxed;
                log.info("[S3] 镇心周边没有合格街区格（林/湿/陡），放宽口径重算街道用地：{} 格",
                        countUrban(urban));
            }
        }
        if (urban == null) {
            return out;
        }
        // 兜底②：镇心那一格本身可能不可走（小树丛/礁石/河沿），是「可建度够但生物群系被排除」的那种。
        // walk 的第一步就要求起点可走，起点不可走 → 正街直接判失败 → 整座镇一条街都长不出来。
        // 把街道起点挪到 20 米内最近的可走格：镇子还是同一个镇，只是街从旁边的空地起头。
        double startX = coreX;
        double startZ = coreZ;
        if (!urbanAt(urban, size, mpc, coreX, coreZ)) {
            double[] snapped = nearestUrban(urban, size, mpc, coreX, coreZ, 20.0 / mpc);
            if (snapped == null) {
                log.info("[S3] 镇心 {} 米内没有可走街区格，放弃街巷骨架", Math.round(20.0));
                return out;
            }
            startX = snapped[0];
            startZ = snapped[1];
            log.info("[S3] 镇心格不可走（林/湿/礁），街道起点外移 ({}, {}) → ({}, {})",
                    Math.round(coreX), Math.round(coreZ), Math.round(startX), Math.round(startZ));
        }

        // ---------- ① 定朝向：能连续通行的最长走廊 ----------
        double b0 = bestAxis(urban, size, mpc, startX, startZ, radius, rng);
        double nx = -Math.sin(b0);
        double nz = Math.cos(b0);

        // ---------- ② 正街：贯通全镇，5.4 米宽，铺石 ----------
        // wander 0.012 → 0.004、maxDrift 0.26 → 0.13：真实正街在 100 米内的横向偏移是「几米」级，
        // 而 0.012 的每步摆动在 2 米步长上会累积成肉眼可见的蛇形（这不是"自然弯曲"，是"喝醉了"）。
        List<double[]> main = walkBoth(urban, size, mpc, noise, startX, startZ, b0, radius * 1.10,
                0.004, 0.13, null);
        // 兜底③：正街从镇心走不出 30 米（镇心落在小林间空地/礁盘/一条窄带上，而镇域里其实有成片
        // 可走地）时，在镇域内粗扫一遍，改从「能走出最长正街」的格子起头。
        // 为什么值得多走这一步：S3 骨架为空会让整张图变成 0 路（甚至只能判失败），
        // 而真实村落遇到这种地形会直接长在空地上——镇子还是这个镇，只是街从旁边长出来。
        if (main.size() < 3 || pathLength(main) < 30) {
            double[] sal = salvageStreet(urban, size, mpc, noise, coreX, coreZ, radius, rng, b0);
            if (sal != null) {
                startX = sal[0];
                startZ = sal[1];
                b0 = sal[2];
                main = walkBoth(urban, size, mpc, noise, startX, startZ, b0, radius * 1.10,
                        0.004, 0.13, null);
                log.info("[S3] 镇心 ({}, {}) 走不出正街，改从 ({}, {}) 起头（能走 {} 米）",
                        Math.round(coreX), Math.round(coreZ), Math.round(startX), Math.round(startZ),
                        Math.round(pathLength(main)));
            }
        }
        if (!accept(out, lines, "main_street", "正街", "street", RoadGrade.STREET,
                5.4, "stone", main, 30, noise, rng)) {
            // 连一条贯通的正街都长不出来，就不该硬铺后街：没有主街的「镇」不成立
            return out;
        }

        // ---------- ③ 后街：小村只一条，大镇才两条 ----------
        // 间距 30~40 米 = 一户院落进深（前院+屋身+后院），这是村镇街区最稳定的尺度。
        // 为什么按半径分档：两条后街 + 横巷会围出一个「口」字，只有 12 户的村子根本填不满中间那块，
        // 航拍上就是一圈没有房子的环路（用户点名的"多余道路"）。半径 ≤90 米（≈12 户）的村子
        // 只有一条后街，路网退化成「一条正街 + 一条后街 + 几条横巷」的梳子形——这才是小村的真实形态。
        double laneGap = 30 + rng.nextDouble() * 10;
        int laneBudget = radiusMeters <= 90 ? 1 : 2;
        int backSeq = 0;
        boolean[] hasBack = new boolean[2];
        int lanesMade = 0;
        for (int si = 0; si < 2 && lanesMade < laneBudget; si++) {
            int side = si == 0 ? -1 : 1;
            for (int k = 0; k < 3; k++) {
                double off = (laneGap + k * 7) * side;
                // 后街比正街更容易被院子/巷口挤弯一点：0.006 / 0.20
                List<double[]> pts = walkBoth(urban, size, mpc, noise,
                        coreX + nx * off, coreZ + nz * off, b0, radius * 0.92, 0.006, 0.20, lines);
                if (accept(out, lines, "back_lane_" + (++backSeq),
                        side < 0 ? "后街一" : "后街二", "lane", RoadGrade.VILLAGE,
                        3.2, "dirt", pts, 26, noise, rng)) {
                    hasBack[si] = true;
                    lanesMade++;
                    break;
                }
            }
        }

        // ---------- ④ 横巷：从正街横切到后街，遇街即停（成 T 字口） ----------
        // 只朝「确实有后街」的那一侧切：另一侧没有街可接，切出去就是一条伸进草地的断头路
        // （旧实现在 t3 里就留下了两条 40 米长的悬空支路，航拍上极像"多余的道路"）。
        double[] cum = cumulative(main);
        double total = cum[cum.length - 1];
        int cross = 0;
        for (double s = 18; s < total - 16; s += 40 + rng.nextDouble() * 16) {
            double[] p = pointAt(main, cum, s);
            for (int si = 0; si < 2; si++) {
                if (!hasBack[si]) {
                    continue;
                }
                int side = si == 0 ? -1 : 1;
                double ang = Math.atan2(nz * side, nx * side);
                // 横巷短、要求"横平竖直地切过去"：wander 0.010 + maxDrift 0.22
                List<double[]> pts = walk(urban, size, mpc, noise, p[0], p[1], ang,
                        laneGap + 22, 0.010, 0.22, lines);
                // 必须真的接到后街/别的巷子上：走完还悬在空地里就丢掉（兜底，别留断头路）
                double[] tail = pts.isEmpty() ? null : pts.get(pts.size() - 1);
                if (tail != null && minDistToLines(lines, tail[0], tail[1]) > 6.5) {
                    continue;
                }
                accept(out, lines, "cross_lane_" + (++cross), "横巷" + cn(cross), "lane",
                        RoadGrade.VILLAGE, 2.6, "dirt", pts, 14, noise, rng);
            }
        }

        return out;
    }

    /**
     * 找骨架上离某点最近的位置（外部道路就接在这里，形成 T 字口）。
     *
     * @param roads 骨架
     * @param x     X（米）
     * @param z     Z（米）
     * @return {x, z, 距离}；骨架为空返回 null
     */
    public static double[] nearestOn(List<MapPlacer.RoadSpec> roads, double x, double z) {
        double best = Double.MAX_VALUE;
        double bx = 0;
        double bz = 0;
        for (MapPlacer.RoadSpec r : roads) {
            List<double[]> path = r.pathMeters();
            if (path == null || path.size() < 2) {
                continue;
            }
            for (int i = 1; i < path.size(); i++) {
                double[] a = path.get(i - 1);
                double[] b = path.get(i);
                double vx = b[0] - a[0];
                double vz = b[1] - a[1];
                double vv = vx * vx + vz * vz;
                double t2 = vv < 1e-9 ? 0 : ((x - a[0]) * vx + (z - a[1]) * vz) / vv;
                t2 = Math.max(0, Math.min(1, t2));
                double qx = a[0] + vx * t2;
                double qz = a[1] + vz * t2;
                double d = Math.hypot(x - qx, z - qz);
                if (d < best) {
                    best = d;
                    bx = qx;
                    bz = qz;
                }
            }
        }
        return best == Double.MAX_VALUE ? null : new double[]{bx, bz, best};
    }

    /**
     * 主街朝向：在可建区里找「从中心向两侧都能连续走通」的最长方位。
     * <p>不做 PCA：PCA 会被大块可建区拉偏；这里直接模拟人挑路——哪边走得远，街就往哪边铺。</p>
     */
    private static double bestAxis(boolean[] urban, int size, double mpc,
                                   double coreX, double coreZ, double radius, Rng rng) {
        double bestScore = -1;
        double bestAng = 0;
        for (int k = 0; k < 24; k++) {
            double ang = k * Math.PI / 24.0;
            double run = 0;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (double r = 2; r <= radius * 1.15; r += 4) {
                    double x = coreX + Math.cos(ang) * r * dir;
                    double z = coreZ + Math.sin(ang) * r * dir;
                    if (!urbanAt(urban, size, mpc, x, z)) {
                        break;
                    }
                    run += 4;
                }
            }
            // 轻微抖动只用来打破对称平手；rng 调用顺序固定，所以可复现
            double score = run + rng.nextDouble() * 5;
            if (score > bestScore) {
                bestScore = score;
                bestAng = ang;
            }
        }
        return bestAng;
    }

    /** 折线长度（米）。 */
    private static double pathLength(List<double[]> pts) {
        double len = 0;
        for (int i = 1; i < pts.size(); i++) {
            len += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        return len;
    }

    /**
     * 在镇域内粗扫一遍，找「能走出最长正街」的起点与朝向。
     *
     * <p>什么时候用：镇心那一格周边只有一小块可走地（林间空地、礁盘、沙嘴上的窄条），
     * 正街从镇心一步都走不出去；但镇域（1.25 倍聚落半径）里其实有成片的可建地。
     * 这时按 10 米网格扫描可走格，逐个试朝向、真的走一遍，取最长的那条——镇子还是这个镇，
     * 只是街从空地上长出来，而不是让整张图变成 0 条路。</p>
     *
     * @param urban  可走掩膜
     * @param size   图幅
     * @param mpc    米/格
     * @param noise  噪声（街的缓弯用）
     * @param coreX  聚落中心 X（米）
     * @param coreZ  聚落中心 Z（米）
     * @param radius 聚落半径（米）
     * @param rng    确定性随机（与 frame 同一条流：扫描顺序固定，结果可复现）
     * @param fallbackAng 全都没走通时返回 null（由调用方沿用原起点）
     * @return {x, z, angle} 或 null
     */
    private static double[] salvageStreet(boolean[] urban, int size, double mpc, Noise2D noise,
                                          double coreX, double coreZ, double radius,
                                          Rng rng, double fallbackAng) {
        int ccx = (int) Math.round(coreX / mpc);
        int ccz = (int) Math.round(coreZ / mpc);
        int ri = (int) Math.ceil(radius / mpc);
        int step = Math.max(4, (int) Math.round(10 / mpc));
        double bestLen = 0;
        double bx = 0;
        double bz = 0;
        double bang = fallbackAng;
        for (int dz = -ri; dz <= ri; dz += step) {
            for (int dx = -ri; dx <= ri; dx += step) {
                if (Math.hypot(dx, dz) * mpc > radius) {
                    continue;
                }
                int x = ccx + dx;
                int z = ccz + dz;
                if (x < 2 || z < 2 || x >= size - 2 || z >= size - 2) {
                    continue;
                }
                if (!urban[z * size + x]) {
                    continue;
                }
                double sx = (x + 0.5) * mpc;
                double sz = (z + 0.5) * mpc;
                double ang = bestAxis(urban, size, mpc, sx, sz, radius, rng);
                double len = pathLength(walkBoth(urban, size, mpc, noise, sx, sz, ang,
                        radius * 1.10, 0.004, 0.13, null));
                if (len > bestLen) {
                    bestLen = len;
                    bx = sx;
                    bz = sz;
                    bang = ang;
                }
            }
        }
        return bestLen >= 30 ? new double[]{bx, bz, bang} : null;
    }

    /** 折线累计弧长。 */
    private static double[] cumulative(List<double[]> pts) {        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cum[i] = cum[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0],
                    pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        return cum;
    }

    /** 折线上按弧长取点。 */
    private static double[] pointAt(List<double[]> pts, double[] cum, double s) {
        for (int i = 1; i < pts.size(); i++) {
            if (cum[i] >= s) {
                double seg = Math.max(1e-9, cum[i] - cum[i - 1]);
                double t2 = (s - cum[i - 1]) / seg;
                return new double[]{
                        pts.get(i - 1)[0] + (pts.get(i)[0] - pts.get(i - 1)[0]) * t2,
                        pts.get(i - 1)[1] + (pts.get(i)[1] - pts.get(i - 1)[1]) * t2};
            }
        }
        return pts.get(pts.size() - 1);
    }

    /**
     * 尽端胡同：从街巷边往里伸 12~25 米的小巷，尽端就是院门。
     *
     * <p><b>为什么只在「街」上长胡同</b>：胡同是入户的最后一段，它必须挂在能走车的街上；
     * 挂在田埂上就成了野地里的车辙。所以只挑 {@link RoadGrade#VILLAGE} 及以上的路。</p>
     *
     * <p>胡同允许是断头路——现实里的巷子本来就常常是断头路，这正是街区内部的样子。</p>
     *
     * @param t            地形
     * @param seed         种子
     * @param streets      已有的街巷（骨架 + 外部路）
     * @param coreX        聚落中心 X（米）
     * @param coreZ        聚落中心 Z（米）
     * @param radiusMeters 聚落半径（米）
     * @return 新增胡同
     */
    public static List<MapPlacer.RoadSpec> alleys(TerrainData t, long seed,
                                                 List<MapPlacer.RoadSpec> streets,
                                                 double coreX, double coreZ, double radiusMeters) {
        Rng rng = Rng.derive(seed, 0x7a11_3311L);
        Noise2D noise = new Noise2D(rng);
        int size = t.size;
        double mpc = Math.max(0.05, t.metersPerCell);
        // 聚落不能无限大：岛上小镇 80~260 米半径，再大就不是「镇」了
        double radius = Math.max(80, Math.min(radiusMeters, 260));

        boolean[] urban = urbanMask(t, size, mpc, coreX, coreZ, radius);
        if (urban == null) {
            return new ArrayList<>();
        }

        List<MapPlacer.RoadSpec> out = new ArrayList<>();
        List<List<double[]>> lines = new ArrayList<>();
        for (MapPlacer.RoadSpec r : streets) {
            if (r.pathMeters() != null && r.pathMeters().size() >= 2) {
                lines.add(r.pathMeters());
            }
        }

        // ---------- 尽端胡同：伸进街区，通往院门 ----------
        int made = 0;
        int alleySeq = 0;
        List<MapPlacer.RoadSpec> lanesOnly = new ArrayList<>();
        for (MapPlacer.RoadSpec r : streets) {
            if (r.grade() >= RoadGrade.VILLAGE) {
                lanesOnly.add(r);
            }
        }
        for (MapPlacer.RoadSpec lane : lanesOnly) {
            List<double[]> path = lane.pathMeters();
            // 胡同不能太密：真实村庄的尽端胡同是"一段街 2~4 条"，不是每隔 10 米一条。
            // 旧口径 i+=5（约 10 米）+ 30% 概率 + 上限 34 条，镇里会长出一圈"梳齿"，
            // 俯视图上就是一团杂乱的小路。
            for (int i = 9; i < path.size() - 9; i += 9) {
                if (made >= 12 || rng.nextDouble() > 0.22) {
                    continue;
                }
                double[] a = path.get(i);
                double[] b = path.get(Math.min(path.size() - 1, i + 1));
                double ux = b[0] - a[0];
                double uz = b[1] - a[1];
                double len = Math.hypot(ux, uz);
                if (len < 1e-6) {
                    continue;
                }
                ux /= len;
                uz /= len;
                for (int side = -1; side <= 1; side += 2) {
                    if (rng.nextDouble() > 0.5) {
                        continue;
                    }
                    double ang = Math.atan2(uz * side, ux * side) + (rng.nextDouble() < 0.5 ? 0 : Math.PI);
                    // 出发点：巷子侧边外 2 米
                    double sx = a[0] - uz * side * 2.0;
                    double sz = a[1] + ux * side * 2.0;
                    List<double[]> alley = walk(urban, size, mpc, noise, sx, sz, ang,
                            12 + rng.nextDouble() * 13, 0.05, 0.9, lines);
                    if (alley.size() < 5) {
                        continue;
                    }
                    if (accept(out, lines, "alley_" + out.size(), "胡同" + cn(++alleySeq),
                            "alley", RoadGrade.VILLAGE, 1.7, "dirt", alley, 9, noise, rng)) {
                        made++;
                    }
                }
            }
        }
        return out;
    }

    // ==================== 可建区 ====================

    /** 聚落可生长区（可建、不淹水、不在河道、坡度可住人）。 */
    private static boolean[] urbanMask(TerrainData t, int size, double mpc,
                                      double coreX, double coreZ, double radius) {
        return urbanMask(t, size, mpc, coreX, coreZ, radius, false);
    }

    /**
     * 聚落可生长区（可建、不淹水、不在河道、坡度可住人）。
     *
     * @param relaxed true=宽口径（只排除水/河/不可建/陡坡，不排除林地湿地）。
     *                只在严格口径一个格都长不出街道时用作兜底——村里的街穿过林间空地是真实的，
     *                但正常情况下仍然不该让街笔直穿进密林（旧版就是这么长出"松鼠村"的）。
     */
    private static boolean[] urbanMask(TerrainData t, int size, double mpc,
                                      double coreX, double coreZ, double radius,
                                      boolean relaxed) {
        boolean[] urban = new boolean[size * size];
        int ccx = (int) Math.round(coreX / mpc);
        int ccz = (int) Math.round(coreZ / mpc);
        int ri = (int) Math.ceil(radius / mpc) + 3;
        boolean any = false;
        for (int dz = -ri; dz <= ri; dz++) {
            for (int dxx = -ri; dxx <= ri; dxx++) {
                int x = ccx + dxx;
                int z = ccz + dz;
                if (x < 2 || z < 2 || x >= size - 2 || z >= size - 2) {
                    continue;
                }
                if (Math.hypot(dxx, dz) * mpc > radius) {
                    continue;
                }
                int i = z * size + x;
                if (!urbanEligible(t, i, relaxed)) {
                    continue;
                }
                urban[i] = true;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        // 清掉孤立可建点（3×3 内少于 5 个可建）：这些是礁石/小土包，不该长巷子
        boolean[] clean = urban.clone();
        for (int dz = -ri; dz <= ri; dz++) {
            for (int dxx = -ri; dxx <= ri; dxx++) {
                int x = ccx + dxx;
                int z = ccz + dz;
                if (x < 2 || z < 2 || x >= size - 2 || z >= size - 2) {
                    continue;
                }
                int i = z * size + x;
                if (!urban[i]) {
                    continue;
                }
                int cnt = 0;
                for (int oz = -1; oz <= 1; oz++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (urban[(z + oz) * size + (x + ox)]) {
                            cnt++;
                        }
                    }
                }
                if (cnt < 5) {
                    clean[i] = false;
                }
            }
        }
        return clean;
    }

    /**
     * 这一格能不能长街（<b>街道用地的唯一判定入口</b>）。
     *
     * <p>为什么单独抽出来：{@code MapSites.findCore} 选镇心时也必须按同一套口径判「这一带是不是
     * 能长出一座镇的地」，否则会出现「镇心落在生物群系被排除的小树丛里」——S3 的严格口径一个
     * 街区格都长不出来，整张图变成 0 条路、0 栋房的 ready 地图（实测 seed=1825809105）。</p>
     *
     * @param t       地形
     * @param i       格索引
     * @param relaxed 是否宽口径（不排除林地/湿地/岩石）
     * @return true=可作街区
     */
    static boolean urbanEligible(TerrainData t, int i, boolean relaxed) {
        if (t.height[i] <= t.seaLevel + 0.004 || t.riverMask[i]) {
            return false;
        }
        if (relaxed) {
            return t.buildable[i] >= 0.08 && t.slope[i] <= 0.45;
        }
        if (t.buildable[i] < 0.15 || t.slope[i] > 0.32) {
            return false;
        }
        // 街不能长在林子里：可建造度在林地上也可能合格，但真实村镇的街一定在开阔地上。
        // 旧版只卡可建度，于是巷子笔直穿进密林，两侧还排上了房子（松鼠村的既视感）。
        byte b = t.biome[i];
        return b != Biomes.FOREST_DENSE && b != Biomes.FOREST_LIGHT && b != Biomes.ROCK
                && b != Biomes.CLIFF && b != Biomes.WETLAND && b != Biomes.SNOW;
    }

    /** 城市掩膜里可走格的数量。 */
    private static int countUrban(boolean[] urban) {
        int n = 0;
        for (boolean b : urban) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * 离某点最近的可走格中心（只在 r 格内找，找不到返回 null）。
     *
     * @param urban 可走掩膜
     * @param size  图幅
     * @param mpc   米/格
     * @param x0    目标 X（米）
     * @param z0    目标 Z（米）
     * @param r     搜索半径（格）
     * @return {x, z}（米）或 null
     */
    private static double[] nearestUrban(boolean[] urban, int size, double mpc,
                                         double x0, double z0, double r) {
        int cx = (int) Math.floor(x0 / mpc);
        int cz = (int) Math.floor(z0 / mpc);
        int ri = (int) Math.ceil(r);
        double best = Double.MAX_VALUE;
        double bx = 0;
        double bz = 0;
        for (int dz = -ri; dz <= ri; dz++) {
            for (int dx = -ri; dx <= ri; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                if (x < 1 || z < 1 || x >= size - 1 || z >= size - 1) {
                    continue;
                }
                if (!urban[z * size + x]) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (d <= r && d < best) {
                    best = d;
                    bx = (x + 0.5) * mpc;
                    bz = (z + 0.5) * mpc;
                }
            }
        }
        return best == Double.MAX_VALUE ? null : new double[]{bx, bz};
    }

    // ==================== 行走 ====================

    /** 点到一组折线的最短距离（用来判断一条新巷子是否真的接到了既有路网上）。 */
    private static double minDistToLines(List<List<double[]>> lines, double x, double z) {
        double best = Double.MAX_VALUE;
        for (List<double[]> line : lines) {
            best = Math.min(best, minDist(line, x, z));
        }
        return best;
    }

    /** 从种子点向正反两个方向各走一遍，拼成一条完整的巷子。 */
    private static List<double[]> walkBoth(boolean[] urban, int size, double mpc, Noise2D noise,
                                           double sx, double sz, double ang, double maxLen,
                                           double wander, double maxDrift, List<List<double[]>> lines) {
        List<double[]> fwd = walk(urban, size, mpc, noise, sx, sz, ang, maxLen, wander, maxDrift, lines);
        List<double[]> back = walk(urban, size, mpc, noise, sx, sz, ang + Math.PI, maxLen, wander, maxDrift, lines);
        List<double[]> all = new ArrayList<>();
        for (int i = back.size() - 1; i >= 1; i--) {
            all.add(back.get(i));
        }
        all.addAll(fwd);
        return all;
    }

    /**
     * 沿可建区行走，得到一条「顺着地形弯」的街巷折线。
     * <p>走不动就依次试更大的偏转角（人找路也是这样）；实在走不通就断在那里。
     * <b>撞到别的街就停</b>——这样巷子不会穿过十字路口继续往前冲，而是在街区之间接成 T 字口。</p>
     *
     * @param maxDrift 相对起始方向允许的最大偏角（弧度）。街=0.26（笔直、只是缓弯）；
     *                 胡同=0.9（可以拐弯钻进街区）
     */
    private static List<double[]> walk(boolean[] urban, int size, double mpc, Noise2D noise,
                                       double x0, double z0, double ang, double maxLen, double wander,
                                       double maxDrift, List<List<double[]>> lines) {
        List<double[]> pts = new ArrayList<>();
        double x = x0;
        double z = z0;
        if (!urbanAt(urban, size, mpc, x, z)) {
            return pts;
        }
        pts.add(new double[]{x, z});
        double baseAng = ang;
        double len = 0;
        int tick = 0;
        while (len < maxLen) {
            double tx = x + Math.cos(ang) * STEP;
            double tz = z + Math.sin(ang) * STEP;
            if (urbanAt(urban, size, mpc, tx, tz) && Math.abs(angDiff(ang, baseAng)) < maxDrift) {
                // 轻微摆动：巷子不该是尺子画出来的直线
                // 轻微摆动：巷子不该是尺子画出来的直线。但摆动必须是「有界的相对偏移」——
                // 旧实现对角度做累加，几十步之后就是一次随机游走，整条街会自己拐弯：
                // 这就是「路越生成越乱」的直接原因。所以每步把相对基准角的偏移夹回 maxDrift 内。
                ang += wander * (noise.noise(x * 0.045 + 3.1, z * 0.045 - 7.3));
                double drift = angDiff(ang, baseAng);
                if (drift > maxDrift) {
                    ang = baseAng + maxDrift;
                } else if (drift < -maxDrift) {
                    ang = baseAng - maxDrift;
                }
                x = tx;
                z = tz;
                len += STEP;
                pts.add(new double[]{x, z});
            } else {
                boolean moved = false;
                for (double dev : DEVIATIONS) {
                    double ta = ang + dev;
                    if (Math.abs(angDiff(ta, baseAng)) > maxDrift * 1.5) {
                        continue;
                    }
                    double ax = x + Math.cos(ta) * STEP;
                    double az = z + Math.sin(ta) * STEP;
                    if (!urbanAt(urban, size, mpc, ax, az)) {
                        continue;
                    }
                    ang = ta;
                    x = ax;
                    z = az;
                    len += STEP;
                    pts.add(new double[]{x, z});
                    moved = true;
                    break;
                }
                if (!moved) {
                    break;
                }
            }
            // 每隔一步检查是否已经抵到别的街口
            if (lines != null && (++tick & 1) == 0 && len > 10) {
                double best = Double.MAX_VALUE;
                for (List<double[]> line : lines) {
                    best = Math.min(best, minDist(line, x, z));
                    if (best <= 1.8) {
                        break;
                    }
                }
                if (best <= 1.8) {
                    break;
                }
            }
        }
        return pts;
    }

    private static double angDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= Math.PI * 2;
        }
        while (d < -Math.PI) {
            d += Math.PI * 2;
        }
        return d;
    }

    private static boolean urbanAt(boolean[] urban, int size, double mpc, double x, double z) {
        int cx = (int) Math.floor(x / mpc);
        int cz = (int) Math.floor(z / mpc);
        if (cx < 1 || cz < 1 || cx >= size - 1 || cz >= size - 1) {
            return false;
        }
        return urban[cz * size + cx];
    }

    // ==================== 去重 / 收编 ====================

    /** 一条巷子若太短或与已有路重合，丢弃；否则拉直圆角后收入路网。 */
    private static boolean accept(List<MapPlacer.RoadSpec> out, List<List<double[]>> lines,
                                  String code, String name, String kind, int grade,
                                  double width, String surface, List<double[]> pts, double minLen,
                                  Noise2D noise, Rng rng) {
        if (pts.size() < 3) {
            return false;
        }
        double len = 0;
        for (int i = 1; i < pts.size(); i++) {
            len += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        if (len < minLen) {
            return false;
        }
        if (fracNear(lines, pts, DUP_TOL) > DUP_FRAC) {
            return false;
        }
        List<double[]> thin = RoadRouter.simplify(pts, 1.0);
        out.add(new MapPlacer.RoadSpec(code, name, kind, RoadGrade.clamp(grade), width, surface, thin, 0));
        lines.add(thin);
        return true;
    }

    private static double fracNear(List<List<double[]>> lines, List<double[]> cand, double tol) {
        if (lines.isEmpty() || cand.isEmpty()) {
            return 0;
        }
        int near = 0;
        for (double[] pt : cand) {
            double best = Double.MAX_VALUE;
            for (List<double[]> line : lines) {
                best = Math.min(best, minDist(line, pt[0], pt[1]));
                if (best <= tol) {
                    break;
                }
            }
            if (best <= tol) {
                near++;
            }
        }
        return (double) near / cand.size();
    }

    private static double minDist(List<double[]> line, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double wx = x - a[0];
            double wz = z - a[1];
            double vv = vx * vx + vz * vz;
            double t = vv < 1e-9 ? 0 : (wx * vx + wz * vz) / vv;
            t = Math.max(0, Math.min(1, t));
            double dx2 = wx - vx * t;
            double dz2 = wz - vz * t;
            best = Math.min(best, Math.hypot(dx2, dz2));
            if (best < 0.5) {
                return best;
            }
        }
        return best;
    }

    private static String cn(int n) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (n >= 0 && n <= 10) {
            return digits[n];
        }
        if (n < 20) {
            return "十" + digits[n - 10];
        }
        return String.valueOf(n);
    }
}
