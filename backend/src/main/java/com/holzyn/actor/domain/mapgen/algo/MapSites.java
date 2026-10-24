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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选址器（S3/S4 的「语义 → 坐标」桥）——把 AI 说的「镇中心 / 码头口 / 河边 / 高处」落到真实地形的米坐标。
 * <p>这是「布局符合逻辑」的关键一环：上一版 AI 直接吐坐标，结果是房子盖在河里、路穿过山脊；
 * 现在 AI 只给<b>语义角色</b>（role/prefer），本类在地形上找出「最像那个地方」的点，寻路与落位再围绕它展开。</p>
 * <p>全部评分都是可解释的地理量（平坦度、离海距离、滩涂宽度、离聚落距离），没有魔法常数靠调参硬凑。</p>
 */
public final class MapSites {

    /** 地点：镇中心广场（全图枢纽） */
    public static final String CORE = "core";

    /** 地点：对外门户（码头/渡口/村口） */
    public static final String GATE = "gate";

    /** 地点：临水（河畔/岸边） */
    public static final String WATERFRONT = "waterfront";

    /** 地点：民居群 */
    public static final String RESIDENTIAL = "residential";

    /** 地点：工坊区 */
    public static final String WORKSHOP = "workshop";

    /** 地点：生产区（农田/盐田/渔场） */
    public static final String FARM = "farm";

    /** 地点：高地/地标 */
    public static final String HIGH_GROUND = "high_ground";

    /** 一个落位点（米）。 */
    public record Site(String code, String name, String role, double x, double z, double score) {
    }

    /**
     * 「临水目的地」离镇心的最远距离（米）。
     *
     * <p>村里的洗衣埠/取水口是<b>日常生活半径</b>内的东西：真实村落不会为洗衣服修一条
     * 400 米长的专用路。超过这个距离的河口不再当作「河埠头」的目的地——宁可这条边退化掉，
     * 也不要在图上修一条尽头什么都没有的路。</p>
     */
    private static final double RIVER_SIDE_MAX_M = 200.0;

    private MapSites() {
    }

    /**
     * 在地形上求出一整套语义落位点。
     *
     * @param t 地形
     * @param p 参数
     * @return 语义点表（code → Site，含 core/gate/farm/... 及河流口、最高点、岬角）
     */
    public static Map<String, Site> resolve(TerrainData t, TerrainParams p) {
        Map<String, Site> out = new LinkedHashMap<>();
        int size = t.size;
        double mpc = Math.max(0.05, t.metersPerCell);

        // ---------- ① 镇中心：聚落中心附近「最平、最干、最靠中间」的一块 ----------
        double[] core = findCore(t, p, mpc);
        out.put(CORE, new Site(CORE, "镇中心", CORE, core[0] * mpc, core[1] * mpc, core[2]));

        // ---------- ② 门户：岸线上最像码头的一处（离中心不远、滩涂缓、水深够停船） ----------
        // 如果世界数据点名了「海湾」的方位，就先往那一侧找——天然良港永远在湾里，
        // 而不是在迎风的外海岸上。世界数据说渡口在湾内侧，图上就不能把码头修到岬角外海。
        double bayDeg = Double.NaN;
        if (p.regions != null) {
            for (TerrainRegion r : p.regions) {
                if (TerrainRegion.BAY.equals(r.kind())) {
                    bayDeg = r.bearingDeg();
                    break;
                }
            }
        }
        Site gate = findHarbor(t, core[0], core[1], mpc, bayDeg);
        if (gate != null) {
            out.put(GATE, gate);
        }

        // ---------- ③ 临水：最近河流的下游段（洗衣埠/水车） ----------
        Site wf = findRiverSide(t, core[0], core[1], mpc);
        if (wf != null) {
            out.put(WATERFRONT, wf);
        }

        // ---------- ④ 生产区：离中心 120~320 米、成片平坦（农田/盐田） ----------
        Site farm = findFlatBelt(t, core[0], core[1], mpc, 0.18, 0.32, "生产区");
        if (farm != null) {
            out.put(FARM, farm);
        }

        // ---------- ⑤ 工坊区：中心与生产区之间、靠主路一侧（烟尘/噪声要离开住宅核心） ----------
        Site shop = findFlatBelt(t, core[0], core[1], mpc, 0.08, 0.16, "工坊区");
        if (shop != null) {
            out.put(WORKSHOP, shop);
        }

        // ---------- ⑥ 民居群：中心外围一圈（取四个方向，保证镇子不是一条线） ----------
        String[] dirs = {"n", "e", "s", "w"};
        for (int i = 0; i < 4; i++) {
            double ang = Math.PI * 2 * i / 4 - Math.PI / 2;
            Site res = findInDirection(t, core[0], core[1], ang, mpc, 0.07, 0.16);
            if (res != null) {
                out.put(RESIDENTIAL + "_" + dirs[i],
                        new Site(RESIDENTIAL + "_" + dirs[i], "民居群" + dirs[i].toUpperCase(),
                                RESIDENTIAL, res.x(), res.z(), res.score()));
            }
        }

        // ---------- ⑦ 高地：全图最高点（灯塔/钟楼/议事厅） ----------
        int best = 0;
        for (int i = 1; i < size * size; i++) {
            if (t.height[i] > t.height[best]) {
                best = i;
            }
        }
        out.put(HIGH_GROUND, new Site(HIGH_GROUND, "高地", HIGH_GROUND,
                (best % size) * mpc, (best / size) * mpc, t.height[best]));

        // ---------- ⑧ 河流口：每条河的入海点（渡口/桥） ----------
        int idx = 0;
        for (double[] poly : t.rivers) {
            if (poly.length < 4) {
                continue;
            }
            double x = poly[poly.length - 2];
            double z = poly[poly.length - 1];
            out.put("river_mouth_" + idx, new Site("river_mouth_" + idx, "河口", WATERFRONT,
                    x * mpc, z * mpc, 1.0));
            idx++;
        }
        return out;
    }

    // ==================== 内部：各类选址 ====================

    /**
     * 镇中心：在聚落中心附近滑窗，取「半径 30 米内平均可建造度最高」的格。
     * <p>不是直接取参数里的 plainCenter——噪声会让那个点落在小坡或河沟里；
     * 滑窗相当于「人踩点选宅基地」，选的是<b>成片平地</b>而不是一个点。</p>
     *
     * <p><b>为什么还要判「开阔」</b>：只看 30 米窗的可建度，会把镇心选在「可建度很高的小树林」里
     * ——小树林（FOREST_LIGHT/DENSE）在可建度上完全合格，但 S3 的街道用地判定把林地排除了，
     * 于是严格口径下一个街区格都长不出来，整张图变成「ready 但 0 条路、0 栋房」（实测
     * seed=1825809105：镇心格 biome=FOREST_LIGHT，周边 17872 个可建格里没有一个能长街）。
     * 所以先在「聚落半径尺度」上要求开阔（{@link StreetNetwork#urbanEligible} 同口径），
     * 找不到开阔地才退回纯可建度最优——镇子不能建在树丛里，但也不能因为没有空地就不给镇心。</p>
     */
    private static double[] findCore(TerrainData t, TerrainParams p, double mpc) {
        int size = t.size;
        int cx = (int) Math.round(p.plainCenterX * size);
        int cz = (int) Math.round(p.plainCenterZ * size);
        int rWin = Math.max(24, (int) Math.round(30 / mpc));
        int searchR = Math.max(40, (int) Math.round(160 / mpc));
        // 镇能长多大：用 S0b 契约换算出的聚落半径（格），夹在 60~160 米
        int urbanR = (int) Math.round(Math.max(60 / mpc, Math.min(160 / mpc, p.settlementRadius * size)));
        int uStep = Math.max(3, urbanR / 8);
        // 近尺度（镇心脚下 40~80 米）单独再判一次开阔度：只看聚落半径尺度会被「中间一坨林子、
        // 四周全是空地」的岛骗过——空地占比照样过线，可镇心正好落在林子里，正街从林间空地上
        // 一步都走不出去（实测 seed=1825900203：聚落尺度开阔度 0.67，近尺度只有 0.2 左右，
        // 骨架为空 → 整张图 0 条路，只能判失败）。
        int nearR = (int) Math.round(Math.max(36 / mpc, Math.min(80 / mpc, p.settlementRadius * size * 0.55)));
        int nStep = Math.max(3, nearR / 8);
        double bestScore = -1;
        int bx = cx;
        int bz = cz;
        double bestOpenScore = -1;
        int ox = cx;
        int oz = cz;
        int step = Math.max(2, rWin / 8);
        for (int dz = -searchR; dz <= searchR; dz += step) {
            for (int dx = -searchR; dx <= searchR; dx += step) {
                int x = cx + dx;
                int z = cz + dz;
                if (x < Math.max(rWin, urbanR) || z < Math.max(rWin, urbanR)
                        || x >= size - Math.max(rWin, urbanR) || z >= size - Math.max(rWin, urbanR)) {
                    continue;
                }
                // 快速抽检 41 个点（滑窗全量太慢，抽样足够稳定）
                double sum = 0;
                int hit = 0;
                for (int k = 0; k < 41; k++) {
                    int ox2 = (int) Math.round(Math.cos(k * 0.153) * rWin * ((k % 7) / 7.0));
                    int oz2 = (int) Math.round(Math.sin(k * 0.211) * rWin * ((k % 5) / 5.0));
                    int a = (z + oz2) * size + (x + ox2);
                    sum += t.buildable[a];
                    if (t.buildable[a] > 0.25) {
                        hit++;
                    }
                }
                double score = sum / 41.0 + 0.004 * hit - 0.35 * (Math.hypot(dx, dz) / (double) searchR);
                if (score > bestScore) {
                    bestScore = score;
                    bx = x;
                    bz = z;
                }
                if (score > bestOpenScore
                        && StreetNetwork.urbanEligible(t, z * size + x, false)
                        && openFraction(t, x, z, urbanR, uStep) >= 0.50
                        && openFraction(t, x, z, nearR, nStep) >= 0.50) {
                    bestOpenScore = score;
                    ox = x;
                    oz = z;
                }
            }
        }
        if (bestOpenScore >= 0) {
            return new double[]{ox, oz, bestOpenScore};
        }
        return new double[]{bx, bz, bestScore};
    }

    /**
     * 某点周围「能长街的格子」占比（0~1）。
     *
     * <p>半径与步长都由调用方给（格）：镇心选址在聚落半径尺度上判开阔度，
     * 用的判定与 {@code StreetNetwork.urbanMask} 完全一致（同一条 {@code urbanEligible}）。</p>
     *
     * @param t    地形
     * @param cx   中心 X（格）
     * @param cz   中心 Z（格）
     * @param r    采样半径（格）
     * @param step 采样步长（格）
     * @return 开阔度（0~1）
     */
    private static double openFraction(TerrainData t, int cx, int cz, int r, int step) {
        int size = t.size;
        int sum = 0;
        int n = 0;
        for (int dz = -r; dz <= r; dz += step) {
            for (int dx = -r; dx <= r; dx += step) {
                if (Math.hypot(dx, dz) > r) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (x < 2 || z < 2 || x >= size - 2 || z >= size - 2) {
                    continue;
                }
                n++;
                if (StreetNetwork.urbanEligible(t, z * size + x, false)) {
                    sum++;
                }
            }
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    /**
     * 码头：岸线上「离中心不太远 + 岸前水深适中 + 岸上成片可建」的一处。
     * <p>为什么用「离海 2~6 格的陆地」当候选：真实渔港的房子离水线有几步距离，
     * 水线本身是沙滩（不可建）。这里先找临水的陆地再往内看一圈可建度，
     * 得到的是<b>能站人的岸</b>，而不是浪打的那一格。</p>
     *
     * <p><b>为什么改成「湾口硬窗口 + 单套评分」</b>：旧实现是三档阈值（岸上可建 0.22 / 0.10 / 0.00），
     * 逐档扫描、<b>某一档只要凑到一个候选就返回</b>。第一档的门槛恰好卡在湾心的岸边可建度之上
     * （湾心 0.177~0.213，湾缘 0.222），于是「湾缘那一格勉强够 0.22」会把正港挤掉——实测
     * map101/map104 渡口因此偏出契约湾方位 42°~44°，而湾心候选的得分（1.47~1.49）比湾缘
     * （−0.06~0.12）高出一大截，只是它在第二档才被看到。<b>分档不该做成「谁先凑够门槛谁赢」</b>，
     * 该做成「在湾里的硬窗口」：窗口内所有候选同一套评分，谁分高谁上；湾里真找不到才逐步放宽窗口。</p>
     */
    private static Site findHarbor(TerrainData t, double coreX, double coreZ, double mpc,
                                   double preferDeg) {
        // 湾口窗口由窄到宽（度）。契约点名海湾时，泊位优先落在湾心 ±30° 内；
        // 湾里确实没有合格泊位（全是礁石/深水）才退到 45°/70°，最后才是全岸线兜底。
        double[] windows = Double.isNaN(preferDeg)
                ? new double[]{180.0}
                : new double[]{30.0, 45.0, 70.0, 180.0};
        for (double win : windows) {
            Site s = scanHarbor(t, coreX, coreZ, mpc, preferDeg, win);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** 泊位前理想水深（海面以下，归一化高程）。 */
    private static final double HARBOR_IDEAL_DEPTH = 0.05;

    /** 泊位水深容差：偏离理想水深超过它就当不了好泊位。 */
    private static final double HARBOR_DEPTH_TOL = 0.12;

    /** 岸上最小可行可建度：低于它就是礁石/湿滩，修不了码头。 */
    private static final double HARBOR_MIN_LAND = 0.06;

    /**
     * 按一个湾口窗口扫描岸线，返回得分最高的泊位点。
     *
     * <p>评分四项（全部可解释的地理量，没有魔法常数）：岸前水深越接近理想值越好、
     * 岸上越平越好、离镇心越近越好、偏离契约湾方位越少越好。四项连续加权，
     * 不再用「某档门槛过了就收」的硬切分。</p>
     *
     * @param t            地形
     * @param coreX        镇心 X（格）
     * @param coreZ        镇心 Z（格）
     * @param mpc          米/格
     * @param preferDeg    世界数据点名的海湾方位（罗盘角；NaN=世界没点名）
     * @param bayWindowDeg 只在这个角距内找泊位（度；180=全岸线兜底）
     * @return 泊位点；该窗口内没有合格候选返回 null
     */
    private static Site scanHarbor(TerrainData t, double coreX, double coreZ, double mpc,
                                   double preferDeg, double bayWindowDeg) {
        int size = t.size;
        double sea = t.seaLevel;
        double best = -1;
        int bx = -1;
        int bz = -1;
        int probe = 10;
        for (int z = 2; z < size - 2; z++) {
            for (int x = 2; x < size - 2; x++) {
                int i = z * size + x;
                if (t.height[i] <= sea) {
                    continue;
                }
                int ds = (int) t.distSea[i];
                if (ds < 2 || ds > 6) {
                    continue;
                }
                // 岸上要成片可建（半径 14 米的平均可建度）
                double land = meanBuildable(t, x, z, 14);
                if (land < HARBOR_MIN_LAND) {
                    continue;
                }
                // 岸前水深：往外探 10 格的平均深度（浅滩停不了船）
                double sumDepth = 0;
                int cnt = 0;
                for (int d = 1; d <= probe; d++) {
                    for (int k = 0; k < 8; k++) {
                        int ax = x + (int) Math.round(Math.cos(k * Math.PI / 4) * d);
                        int az = z + (int) Math.round(Math.sin(k * Math.PI / 4) * d);
                        if (ax < 0 || az < 0 || ax >= size || az >= size) {
                            continue;
                        }
                        if (t.height[az * size + ax] <= sea) {
                            sumDepth += sea - t.height[az * size + ax];
                            cnt++;
                        }
                    }
                }
                if (cnt < 10) {
                    continue;
                }
                double depth = sumDepth / cnt;
                // 泊位深度「够但不过深」最好；离中心越近越好；岸上越平越好
                double depthScore = 1.0 - Math.abs(depth - HARBOR_IDEAL_DEPTH) / HARBOR_DEPTH_TOL;
                if (depthScore <= 0) {
                    continue;
                }
                double dist = Math.hypot(x - coreX, z - coreZ) * mpc;
                // 40~700 米：太近是河口/礁盘，太远村民天天走不动。距离项在 460 米处封顶。
                if (dist < 40 || dist > 700) {
                    continue;
                }
                double score = depthScore * 1.4 + land * 2.0
                        - 0.9 * Math.min(1.0, dist / 460.0);
                // 湾内优先：偏离世界数据点名的海湾方位越远，扣分越狠；
                // 超出本窗口的角距直接不参与（窄窗口是「先保证在湾里」的硬约束）。
                if (!Double.isNaN(preferDeg)) {
                    double screenAng = Math.toDegrees(Math.atan2(z - size * 0.5, x - size * 0.5));
                    double want = preferDeg - 90;
                    double diff = Math.abs(((screenAng - want) % 360 + 540) % 360 - 180);
                    if (diff > bayWindowDeg) {
                        continue;
                    }
                    score -= Math.min(1.4, diff / 45.0 * 0.9);
                }
                if (score > best) {
                    best = score;
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bx < 0) {
            return null;
        }
        return new Site(GATE, "码头口", GATE, bx * mpc, bz * mpc, best);
    }

    /** 半径 r 格内的平均可建造度（水面记 0）。 */
    private static double meanBuildable(TerrainData t, int cx, int cz, int r) {
        int size = t.size;
        double sum = 0;
        int n = 0;
        for (int dz = -r; dz <= r; dz += 3) {
            for (int dx = -r; dx <= r; dx += 3) {
                int x = cx + dx;
                int z = cz + dz;
                if (x < 0 || z < 0 || x >= size || z >= size) {
                    continue;
                }
                sum += t.buildable[z * size + x];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 临水点：离中心最近、且岸上成片可建的河道格（洗衣埠/水车/小渡口）。 */
    private static Site findRiverSide(TerrainData t, double coreX, double coreZ, double mpc) {
        int size = t.size;
        double best = -1;
        int bx = -1;
        int bz = -1;
        for (int z = 2; z < size - 2; z++) {
            for (int x = 2; x < size - 2; x++) {
                int i = z * size + x;
                if (!t.riverMask[i] || t.height[i] <= t.seaLevel) {
                    continue;
                }
                double bank = meanBuildable(t, x, z, 8);
                if (bank < 0.2) {
                    continue;
                }
                double dist = Math.hypot(x - coreX, z - coreZ) * mpc;
                if (dist > 320) {
                    continue;
                }
                double score = bank * 2.0 - dist / 320.0;
                if (score > best) {
                    best = score;
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bx < 0) {
            return null;
        }
        return new Site(WATERFRONT, "河埠头", WATERFRONT, bx * mpc, bz * mpc, best);
    }

    /**
     * 成片平地：在「离中心 minR~maxR」的环带里，找窗口内平均可建造度最高的一块。
     * <p>用来放农田（远一点）与工坊（近一点）——这就是「生产用地在聚落外围、
     * 商业服务近核心」这条现实规律在算法上的落地。</p>
     */
    private static Site findFlatBelt(TerrainData t, double coreX, double coreZ, double mpc,
                                     double minR, double maxR, String name) {
        int size = t.size;
        int rWin = Math.max(10, (int) Math.round(18 / mpc));
        double best = -1;
        int bx = -1;
        int bz = -1;
        int step = Math.max(2, rWin / 2);
        for (int z = rWin; z < size - rWin; z += step) {
            for (int x = rWin; x < size - rWin; x += step) {
                double d = Math.hypot(x - coreX, z - coreZ) * mpc / size;
                if (d < minR || d > maxR) {
                    continue;
                }
                int i = z * size + x;
                if (t.buildable[i] < 0.2) {
                    continue;
                }
                double sum = 0;
                for (int dz = -rWin; dz <= rWin; dz += Math.max(1, rWin / 2)) {
                    for (int dx = -rWin; dx <= rWin; dx += Math.max(1, rWin / 2)) {
                        sum += t.buildable[(z + dz) * size + (x + dx)];
                    }
                }
                if (sum > best) {
                    best = sum;
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bx < 0) {
            return null;
        }
        return new Site(name, name, FARM, bx * mpc, bz * mpc, best);
    }

    /** 沿某一方向（弧度）找一块可建地（四个方向的民居群用）。 */
    private static Site findInDirection(TerrainData t, double coreX, double coreZ, double angle,
                                        double mpc, double minR, double maxR) {
        int size = t.size;
        double best = -1;
        int bx = -1;
        int bz = -1;
        for (double r = minR; r <= maxR; r += 0.012) {
            for (int off = -3; off <= 3; off++) {
                double a2 = angle + off * 0.12;
                int x = (int) Math.round(coreX + Math.cos(a2) * r * size);
                int z = (int) Math.round(coreZ + Math.sin(a2) * r * size);
                if (x < 8 || z < 8 || x >= size - 8 || z >= size - 8) {
                    continue;
                }
                int i = z * size + x;
                double score = t.buildable[i] + 0.3 * (1 - Math.abs(off) / 4.0);
                if (score > best) {
                    best = score;
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bx < 0 || best < 0.1) {
            return null;
        }
        return new Site("residential", "民居群", RESIDENTIAL, bx * mpc, bz * mpc, best);
    }

    /**
     * 离某点最近的河口（只在 maxDist 米内找）。
     *
     * @param sites   语义点表
     * @param x       参照 X（米）
     * @param z       参照 Z（米）
     * @param maxDist 最大距离（米）
     * @return 最近的河口；没有够近的返回 null
     */
    public static Site nearestRiverMouth(Map<String, Site> sites, double x, double z, double maxDist) {
        Site best = null;
        double bd = maxDist;
        for (Map.Entry<String, Site> e : sites.entrySet()) {
            if (!e.getKey().startsWith("river_mouth_")) {
                continue;
            }
            Site s = e.getValue();
            double d = Math.hypot(s.x() - x, s.z() - z);
            if (d <= bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * 把 AI 给的 prefer 语义映射到本图实际可用的点（寻路端点用）。
     *
     * @param sites  已求出的语义点
     * @param prefer AI 的放置偏好
     * @param fallback 兜底点 code
     * @return 落位点
     */
    public static Site pick(Map<String, Site> sites, String prefer, String fallback) {
        if (prefer != null) {
            switch (prefer) {
                case "settlement-center" -> {
                    return sites.get(CORE);
                }
                case "coast" -> {
                    Site g = sites.get(GATE);
                    if (g != null) {
                        return g;
                    }
                    // 没有可用泊位时退到河口（也是"水边、能上船"的地方），
                    // 而不是掉进 default 分支拿到高地——语义点错了，整条路的走向就全错了。
                    for (Map.Entry<String, Site> e : sites.entrySet()) {
                        if (e.getKey().startsWith("river_mouth_")) {
                            return e.getValue();
                        }
                    }
                }
                case "river" -> {
                    Site w = sites.get(WATERFRONT);
                    if (w != null) {
                        return w;
                    }
                    // 只认「镇边上的河口」：四百米外的河口不是村里的洗衣埠。
                    // 旧实现无条件拿第一个 river_mouth 兜底，实测修出一条 416 米长、
                    // 尽头什么都没有的"埠头路"（确定性路径上唯一一处真断头）。
                    Site c = sites.get(CORE);
                    Site mouth = c == null ? null
                            : nearestRiverMouth(sites, c.x(), c.z(), RIVER_SIDE_MAX_M);
                    if (mouth != null) {
                        return mouth;
                    }
                    // 附近没有临水点：退回镇心，调用方会把这条退化边丢掉
                    return c;
                }
                case "settlement-edge" -> {
                    for (String k : List.of("residential_n", "residential_e", "residential_s", "residential_w")) {
                        if (sites.containsKey(k)) {
                            return sites.get(k);
                        }
                    }
                }
                case "high-ground" -> {
                    return sites.get(HIGH_GROUND);
                }
                case "farmland" -> {
                    Site f = sites.get(FARM);
                    if (f != null) {
                        return f;
                    }
                }
                default -> {
                    // forest / 其它：落到最近的可建点
                }
            }
        }
        Site s = sites.get(fallback);
        if (s != null) {
            return s;
        }
        Site core = sites.get(CORE);
        if (core != null) {
            return core;
        }
        List<Site> all = new ArrayList<>(sites.values());
        return all.isEmpty() ? new Site(CORE, "镇中心", CORE, 0, 0, 0) : all.get(0);
    }
}
