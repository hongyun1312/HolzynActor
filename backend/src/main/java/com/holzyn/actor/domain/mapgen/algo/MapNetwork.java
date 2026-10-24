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
 * 路网连通性整备。
 * <p><b>为什么必须有这一步？</b>真实聚落里没有「一条悬在半空的断头路」——
 * 路要么连到别处，要么终止在人家院子里。而我们的路是分开生成的：
 * AI 拓扑走 A* 出来是几条主干，程序化街网再从主干上长出巷子，
 * 巷子为了不横穿马路会「差几米停下」，于是图上就出现两条明明该接上、
 * 中间却空着的路。这在俯视图里非常刺眼，比任何美术问题都致命。</p>
 * <p>所以这里做两件事：</p>
 * <ol>
 *   <li><b>端点吸附</b>：端点离别的路不到 10 米，就把端点拉到那条路上（真正相交）；</li>
 *   <li><b>连通分量合并</b>：整张图若还分成几块互不相连，就用 A* 补一条连接路，
 *       直到全图连成一张网（连接路一样吃地形代价，会自己绕开山和水）。</li>
 * </ol>
 */
public final class MapNetwork {

    /** 两条路距离小于它就算「连着」（路面真的挨上了，不是「看着差不多」）。 */
    private static final double TOUCH = 2.2;

    /** 端点吸附：小于这个距离就不动（已经贴上了）。 */
    private static final double SNAP_MIN = 0.7;

    /** 端点吸附：不超过这个距离才拉过去（再远就该走连接路）。 */
    private static final double SNAP_MAX = 14.0;

    /** 判「走向」时取多长的弦（米）。太短抓不住整条路的走向，太长会把拐弯抹平。 */
    private static final double WINDOW_METERS = 14.0;

    /** 最多补几条连接路。 */
    private static final int MAX_CONNECTORS = 10;

    private MapNetwork() {
    }

    /**
     * 整备结果。
     *
     * @param roads      整备后的路网（可能多了连接路）
     * @param snapped    被吸附的端点数
     * @param connectors 补出的连接路数
     * @param groups     整备前的连通分量数
     * @param groupsAfter 整备后的连通分量数（必须为 1，否则还有断网）
     */
    public record Result(List<MapPlacer.RoadSpec> roads, int snapped, int connectors, int groups,
                         int groupsAfter) {
    }

    /**
     * 让整张路网连成一片（连接路按村庄道路 1 级补）。
     *
     * @param t     地形
     * @param roads 原始路网
     * @return 整备结果
     */
    public static Result connect(TerrainData t, List<MapPlacer.RoadSpec> roads) {
        return connect(t, roads, RoadGrade.VILLAGE, 2.4, MAX_CONNECTORS);
    }

    /**
     * 让整张路网连成一片，并指定补出来的连接路用什么等级。
     * <p>田埂、下地土路这类 0 级路补连接时也该补成 0 级——
     * 不能为了让图连上，在两块田之间修一条能过马车的村道。</p>
     *
     * @param t            地形
     * @param roads        原始路网
     * @param connGrade    连接路等级（{@link RoadGrade}）
     * @param connWidth    连接路宽度（米）
     * @param maxConnectors 最多补几条
     * @return 整备结果
     */
    public static Result connect(TerrainData t, List<MapPlacer.RoadSpec> roads, int connGrade,
                                 double connWidth, int maxConnectors) {
        return connect(t, roads, connGrade, connWidth, maxConnectors, null);
    }

    /**
     * 同 {@link #connect(TerrainData, List, int, double, int)}，但用调用方给的代价场。
     * <p>落位之后再补连接路时，代价场里必须带上「建筑净空」——否则补出来的路会从
     * 房子角上穿过去（事后修的路不知道房子已经在那儿了）。</p>
     *
     * @param cost 代价场（{@link RoadRouter#buildCost}）；{@code null} 则按纯地形建
     * @return 整备结果
     */
    public static Result connect(TerrainData t, List<MapPlacer.RoadSpec> roads, int connGrade,
                                 double connWidth, int maxConnectors, float[] cost) {
        return connect(t, roads, connGrade, connWidth, maxConnectors, cost, SNAP_MAX);
    }

    /**
     * 同 {@link #connect(TerrainData, List, int, double, int, float[])}，但可指定端点吸附距离。
     *
     * <p><b>为什么要能调</b>：收尾（剪完断头路之后）剩下的缺口往往是 15~20 米——
     * 按默认 14 米吸附不到，只能补一条连接路；而补出来的连接路在俯视图上就是一段
     * 又多又碎的短线。收尾这一遍放宽到 22 米，缺口直接被"接上"，图上不再有半截悬空的路。</p>
     *
     * @param snapMax 端点吸附的最大距离（米）
     * @return 整备结果
     */
    public static Result connect(TerrainData t, List<MapPlacer.RoadSpec> roads, int connGrade,
                                 double connWidth, int maxConnectors, float[] cost, double snapMax) {
        List<MapPlacer.RoadSpec> specs = new ArrayList<>();
        List<List<double[]>> paths = new ArrayList<>();
        for (MapPlacer.RoadSpec r : roads) {
            if (r.pathMeters() == null || r.pathMeters().size() < 2) {
                continue;
            }
            specs.add(r);
            paths.add(new ArrayList<>(r.pathMeters()));
        }
        if (paths.size() < 2) {
            return new Result(new ArrayList<>(roads), 0, 0, paths.size(), paths.size());
        }
        int snapped;
        int before;
        int connectors = 0;
        float[] useCost = cost != null ? cost : RoadRouter.buildCost(t, null, null);
        // 先算代价场，再吸附：吸附是「把断头延伸去够最近的路」，延伸段必须避开已建房屋，
        // 否则一条村道会从人家屋里横穿出去（实测：6 米长的巷子从房角切进去 4 格）。
        snapped = snapEnds(paths, useCost, t.size, Math.max(0.05, t.metersPerCell), snapMax);
        before = components(paths);
        int guard = 0;
        // cap 允许为 0：收尾那一遍只做"吸附缝合"，一条新路都不许补（补出来就是碎线）
        int cap = Math.max(0, maxConnectors);
        while (components(paths) > 1 && connectors < cap && guard++ < cap + 2) {
            MapPlacer.RoadSpec conn = bridgeComponents(t, useCost, specs, paths, connectors, connGrade,
                    connWidth);
            if (conn == null) {
                break;
            }
            specs.add(conn);
            paths.add(new ArrayList<>(conn.pathMeters()));
            connectors++;
        }
        List<MapPlacer.RoadSpec> out = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            MapPlacer.RoadSpec r = specs.get(i);
            out.add(new MapPlacer.RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                    r.surface(), paths.get(i), r.bridgeCells()));
        }
        return new Result(out, snapped, connectors, before, components(paths));
    }

    /** 当前路网被分成几块（自检用；1 才是合格）。 */
    public static int groupCount(List<MapPlacer.RoadSpec> roads) {
        List<List<double[]>> paths = new ArrayList<>();
        for (MapPlacer.RoadSpec r : roads) {
            if (r.pathMeters() != null && r.pathMeters().size() >= 2) {
                paths.add(r.pathMeters());
            }
        }
        return components(paths);
    }

    // ==================== ① 端点吸附 ====================


    /** 把悬空的端点拉到最近的一条路上，返回被吸附的端点数。 */
    private static int snapEnds(List<List<double[]>> paths, float[] cost, int size, double mpc,
                                double snapMax) {
        int snapped = 0;
        for (int i = 0; i < paths.size(); i++) {
            List<double[]> path = paths.get(i);
            if (path.size() < 2) {
                continue;
            }
            snapped += snapOne(paths, i, path, 0, true, cost, size, mpc, snapMax);
            snapped += snapOne(paths, i, path, path.size() - 1, false, cost, size, mpc, snapMax);
        }
        return snapped;
    }

    private static int snapOne(List<List<double[]>> paths, int self, List<double[]> path,
                               int idx, boolean atStart, float[] cost, int size, double mpc,
                               double snapMax) {
        double[] pt = path.get(idx);
        double best = Double.MAX_VALUE;
        double[] bestPt = null;
        int bestJ = -1;
        for (int j = 0; j < paths.size(); j++) {
            if (j == self) {
                continue;
            }
            double[] q = nearestOnPath(paths.get(j), pt[0], pt[1]);
            if (q[2] < best) {
                best = q[2];
                bestPt = q;
                bestJ = j;
            }
        }
        if (bestPt == null || best > snapMax || best < SNAP_MIN) {
            return 0;
        }
        double[] joint = new double[]{bestPt[0], bestPt[1]};
        // 延伸段穿过已建房屋就不吸附：宁可留个断头让后面的连接路去接，
        // 也不能让一条巷子从人家屋里穿过去。
        if (crossesBuilt(pt, joint, cost, size, mpc)) {
            return 0;
        }
        // 接头点也要落进「被接的那条路」里：只把端点挪过去的话，两条路的几何只是
        // 数值上重合 0 米，渲染时两条粗线在 T 字口各画各的圆头，接缝处会缺一小块。
        // 插一个顶点进去，两边共用同一个坐标，路口才是真的路口。
        insertVertex(paths.get(bestJ), joint[0], joint[1]);
        if (atStart) {
            path.add(0, joint);
        } else {
            path.add(joint);
        }
        return 1;
    }

    /**
     * 把点插进折线上离它最近的那一段（已经压在上面就不插）。
     *
     * @param path 折线（就地修改）
     * @param x    X（米）
     * @param z    Z（米）
     */
    private static void insertVertex(List<double[]> path, double x, double z) {
        if (path == null || path.size() < 2) {
            return;
        }
        int bestI = -1;
        double bestT = 0;
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double vv = vx * vx + vz * vz;
            double t = vv < 1e-9 ? 0 : ((x - a[0]) * vx + (z - a[1]) * vz) / vv;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(x - (a[0] + vx * t), z - (a[1] + vz * t));
            if (d < best) {
                best = d;
                bestI = i;
                bestT = t;
            }
        }
        if (bestI < 0 || best > 2.2) {
            return;
        }
        // 离已有顶点不到 1 米就不插：插了只会得到一串几乎重合的顶点
        double[] a = path.get(bestI - 1);
        double[] b = path.get(bestI);
        if (Math.hypot(x - a[0], z - a[1]) < 1.0 || Math.hypot(x - b[0], z - b[1]) < 1.0) {
            return;
        }
        if (bestT <= 0.02 || bestT >= 0.98) {
            return;
        }
        path.add(bestI, new double[]{x, z});
    }

    /**
     * 线段是否穿过「已建」格（代价场里被重罚的格子 = 房屋及其 2.6 米净空）。
     *
     * <p>为什么吸附也要查这个：吸附是把断头<b>延伸</b>到最近的路，延伸段是事后才出现的，
     * 完全可能从一栋已经盖好的房子身上穿过去——俯视图上就是「一条巷子穿墙」，
     * 比留下断头刺眼得多。</p>
     *
     * @param a    起点（米）
     * @param b    终点（米）
     * @param cost 代价场（可为 null）
     * @param size 图边长（格）
     * @param mpc  每格米数
     * @return true=穿过了已建区
     */
    private static boolean crossesBuilt(double[] a, double[] b, float[] cost, int size, double mpc) {
        if (cost == null || cost.length < size * size) {
            return false;
        }
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(2, (int) Math.ceil(len / Math.max(0.5, mpc * 0.5)));
        for (int s = 0; s <= steps; s++) {
            double tt = (double) s / steps;
            int x = (int) Math.floor((a[0] + (b[0] - a[0]) * tt) / mpc);
            int z = (int) Math.floor((a[1] + (b[1] - a[1]) * tt) / mpc);
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return true;
            }
            if (cost[z * size + x] > 150f) {
                return true;
            }
        }
        return false;
    }

    // ==================== ② 连通分量 ====================

    /** 连通分量数（并查集，按折线两两距离判断是否接触）。 */
    private static int components(List<List<double[]>> paths) {
        int n = paths.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (find(parent, i) == find(parent, j)) {
                    continue;
                }
                if (pathDist(paths.get(i), paths.get(j)) <= TOUCH) {
                    union(parent, i, j);
                }
            }
        }
        int groups = 0;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == i) {
                groups++;
            }
        }
        return groups;
    }

    /** 找出最大分量与其余各分量之间最近的一对点，A* 补一条连接路。 */
    private static MapPlacer.RoadSpec bridgeComponents(TerrainData t, float[] cost,
                                                      List<MapPlacer.RoadSpec> specs,
                                                      List<List<double[]>> paths, int seq,
                                                      int connGrade, double connWidth) {
        int n = paths.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (find(parent, i) == find(parent, j)) {
                    continue;
                }
                if (pathDist(paths.get(i), paths.get(j)) <= TOUCH) {
                    union(parent, i, j);
                }
            }
        }
        // 最大分量（主网）
        int[] size = new int[n];
        for (int i = 0; i < n; i++) {
            size[find(parent, i)]++;
        }
        int mainRoot = 0;
        int bestSize = -1;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == i && size[i] > bestSize) {
                bestSize = size[i];
                mainRoot = i;
            }
        }
        // 最近的一对点：<b>必须按线段投影求</b>，不能按顶点求。
        // 旧实现拿「两条折线的顶点」两两比距离，再用最近的顶点当接头——而折线是抽稀过的：
        // 一条 300 米的直路只有两个顶点（两端），于是「接在这条路中段」在数学上根本取不到，
        // 连接路只能绕到它的端点去接，图上就是「明明旁边就有路，偏要绕到最远的地方才接上」。
        // 换成线段与线段的最近点之后，接头落在真正的最近位置。
        double best = Double.MAX_VALUE;
        int bi = -1;
        int bj = -1;
        double[] pair = null;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == mainRoot) {
                continue;
            }
            for (int j = 0; j < n; j++) {
                if (find(parent, j) != mainRoot) {
                    continue;
                }
                double[] pr = nearestPair(paths.get(i), paths.get(j));
                if (pr != null && pr[4] < best) {
                    best = pr[4];
                    bi = i;
                    bj = j;
                    pair = pr;
                }
            }
        }
        if (bi < 0 || bj < 0 || pair == null) {
            return null;
        }
        double[] pa = new double[]{pair[0], pair[1]};
        double[] pb = new double[]{pair[2], pair[3]};
        // 接头处两边共用同一个顶点（否则 T 字口在渲染上是两条各自收头的粗线，缝一个口子）
        insertVertex(paths.get(bi), pa[0], pa[1]);
        insertVertex(paths.get(bj), pb[0], pb[1]);
        if (pa == null || pb == null) {
            return null;
        }
        MapPlacer.RoadSpec fromSpec = specs.get(bi);
        double mpc = Math.max(0.05, t.metersPerCell);
        List<double[]> path = new ArrayList<>();
        int bridgeCells = 0;
        // 两个分量之间的连接：短距离优先走直线。旧口径只有 ≤8 米才走直线，
        // 于是 10~50 米的近邻连接也全交给 A*，A* 为了躲林/坡会绕出一大圈——
        // 用户看到的就是「本来很近却绕到很远才接上」。直线必须满足：不穿建筑、不下水。
        if (best <= 55 && clearLine(cost, pa, pb, mpc)) {
            path.add(new double[]{pa[0], pa[1]});
            path.add(new double[]{pb[0], pb[1]});
        } else {
            RoadRouter.Route r = RoadRouter.route(t, cost,
                    (int) Math.round(pa[0] / mpc), (int) Math.round(pa[1] / mpc),
                    (int) Math.round(pb[0] / mpc), (int) Math.round(pb[1] / mpc), 0.55);
            if (r == null) {
                return null;
            }
            for (double[] pt : r.path()) {
                path.add(new double[]{pt[0] * mpc, pt[1] * mpc});
            }
            bridgeCells = r.bridgeCells();
        }
        List<double[]> thin = RoadRouter.simplify(path, 1.0);
        if (thin.size() < 2) {
            return null;
        }
        // 只把「真的跨了一小段水」的连接路标成桥。
        // 旧实现把整条连接路都带着 bridge 标记，于是图上出现 107 / 189 米长的「桥」——
        // 那是把绕水的一段长路当成了桥面。真实村镇的桥就是 6~20 米。
        if (bridgeCells > 18) {
            bridgeCells = 0;
        }
        boolean field = connGrade <= RoadGrade.TRACK;
        String code = (field ? "farm_conn_" : "conn_") + fromSpec.code() + "_" + seq;
        String name = field ? "田间小路" + (seq + 1) : "接路" + (seq + 1);
        return new MapPlacer.RoadSpec(code, name, field ? "track" : "connector",
                RoadGrade.clamp(connGrade), connWidth, "dirt", thin, bridgeCells);
    }

    /**
     * 直线连接是否干净（不穿建筑、不下水）。
     * <p>短连接（≤8 米）为了自然走直线，但直线可能从房子角上切过去——
     * 房价代价场里带 240/格，所以只要沿线采样到高代价格子，就改用 A* 绕。</p>
     */
    private static boolean clearLine(float[] cost, double[] a, double[] b, double mpc) {
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(2, (int) Math.ceil(len / (mpc * 0.5)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round((a[0] + (b[0] - a[0]) * t) / mpc);
            int z = (int) Math.round((a[1] + (b[1] - a[1]) * t) / mpc);
            int size = (int) Math.round(Math.sqrt(cost.length));
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return false;
            }
            if (cost[z * size + x] > 100f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 两条折线之间<b>最近的一对点</b>（线段投影，不是顶点）。
     *
     * <p>为什么不能用顶点：折线是 Douglas-Peucker 抽稀过的，一条 300 米的直路只有两个顶点。
     * 按顶点找「最近」，这条路的整条中段都取不到——连接路只能绕到它的端点去接。
     * 用户看到的就是「明明旁边就有路，偏偏绕到最远的地方才接上」。</p>
     *
     * @param a 折线 A（米）
     * @param b 折线 B（米）
     * @return {ax, az, bx, bz, dist}；任一条不足两点返回 null
     */
    public static double[] nearestPair(List<double[]> a, List<double[]> b) {
        if (a == null || b == null || a.size() < 2 || b.size() < 2) {
            return null;
        }
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (int i = 1; i < a.size(); i++) {
            double[] p1 = a.get(i - 1);
            double[] p2 = a.get(i);
            for (int j = 1; j < b.size(); j++) {
                double[] r = segSegClosest(p1, p2, b.get(j - 1), b.get(j));
                if (r[4] < best) {
                    best = r[4];
                    out = r;
                }
            }
        }
        return out;
    }

    /** 两条线段的最近点对（Ericson《Real-Time Collision Detection》5.1.9 的夹取解法）。 */
    private static double[] segSegClosest(double[] p1, double[] p2, double[] q1, double[] q2) {
        double ux = p2[0] - p1[0];
        double uz = p2[1] - p1[1];
        double vx = q2[0] - q1[0];
        double vz = q2[1] - q1[1];
        double wx = p1[0] - q1[0];
        double wz = p1[1] - q1[1];
        double a = ux * ux + uz * uz;
        double b = ux * vx + uz * vz;
        double c = vx * vx + vz * vz;
        double d = ux * wx + uz * wz;
        double e = vx * wx + vz * wz;
        double den = a * c - b * b;
        if (den < 1e-12 || a < 1e-12 || c < 1e-12) {
            // 平行（含共线）或退化线段：夹取解的分母为 0，<b>不能</b>硬取 sc=0 再夹 tc——
            // 两条共线、首尾正好相接的路段会被算成「相距一整段长度」。实测：一条支路
            // 从主街中段垂直下来、接头共点，距离被判成 40 米，于是整张路网被判成两块，
            // connect() 连补 10 条零长度连接路都合不上。平行线段的最近距离必然落在
            // 某条线段的端点上，逐个端点投影取最小即可。
            return endpointPair(p1, p2, q1, q2);
        }
        double sc = (b * e - c * d) / den;
        double tc = (a * e - b * d) / den;
        if (sc < 0) {
            sc = 0;
            tc = c > 1e-12 ? e / c : 0;
        } else if (sc > 1) {
            sc = 1;
            tc = c > 1e-12 ? (e + b) / c : 0;
        }
        if (tc < 0) {
            tc = 0;
            sc = a > 1e-12 ? -d / a : 0;
        } else if (tc > 1) {
            tc = 1;
            sc = a > 1e-12 ? (b - d) / a : 0;
        }
        sc = Math.max(0, Math.min(1, sc));
        tc = Math.max(0, Math.min(1, tc));
        double px = p1[0] + ux * sc;
        double pz = p1[1] + uz * sc;
        double qx = q1[0] + vx * tc;
        double qz = q1[1] + vz * tc;
        return new double[]{px, pz, qx, qz, Math.hypot(px - qx, pz - qz)};
    }

    /**
     * 线段最近点对的「端点投影」兜底解（平行 / 退化线段专用）。
     *
     * <p>平行线段的最近距离一定落在四个端点之一到对面线段的正交投影上，
     * 逐个比一遍就得到精确值；共线首尾相接时结果是 0。</p>
     */
    private static double[] endpointPair(double[] p1, double[] p2, double[] q1, double[] q2) {
        double[] best = null;
        double[][] cand = {
                pointSegClosest(p1[0], p1[1], q1, q2, true),
                pointSegClosest(p2[0], p2[1], q1, q2, true),
                pointSegClosest(q1[0], q1[1], p1, p2, false),
                pointSegClosest(q2[0], q2[1], p1, p2, false)
        };
        for (double[] c : cand) {
            if (best == null || c[4] < best[4]) {
                best = c;
            }
        }
        return best;
    }

    /**
     * 点到线段的最近点。
     *
     * @param pointIsP true 表示输入点是折线 P 上的点（返回 {p, q, dist} 的顺序要保住）
     */
    private static double[] pointSegClosest(double x, double z, double[] s1, double[] s2,
                                            boolean pointIsP) {
        double vx = s2[0] - s1[0];
        double vz = s2[1] - s1[1];
        double vv = vx * vx + vz * vz;
        double t = vv < 1e-9 ? 0 : ((x - s1[0]) * vx + (z - s1[1]) * vz) / vv;
        t = Math.max(0, Math.min(1, t));
        double px = s1[0] + vx * t;
        double pz = s1[1] + vz * t;
        double d = Math.hypot(x - px, z - pz);
        return pointIsP ? new double[]{x, z, px, pz, d} : new double[]{px, pz, x, z, d};
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    // ==================== 几何 ====================

    /**
     * 「同走向」度量：折线 {@code a} 的采样点里，有多大比例落在折线 {@code b} 的 {@code tol} 米内。
     *
     * <p><b>为什么要有这个公共口径</b>：S3 的目的地路和 S4 的田埂路都要判「这条路是不是
     * 和已有的某条重复」，两边各写一套阈值就会左右不一致——S3 放过去的重复路，S4 也未必认得出来。
     * 判据统一在这里，两处只调参数（容差、覆盖率门槛）。</p>
     *
     * @param a    被检查的折线
     * @param b    参照折线
     * @param tol  认定「压在上面」的距离（米）
     * @param step 采样步长（米，0.5~6 之间取值；越小越准也越慢）
     * @return 0~1 的覆盖率；任一条折线不足以判定时返回 0
     */
    public static double coverage(List<double[]> a, List<double[]> b, double tol, double step) {
        return coverage(a, b, tol, step, -1.0);
    }

    /**
     * 「同走向」度量（带方向）——<b>平行才算重复，横过来的短进道不算</b>。
     *
     * <p><b>为什么必须有方向</b>：只按「离得近」判重复，会把<b>T 字口的短进道</b>误判成重复路。
     * 一条 10 米的入户支路垂直接到一条 40 米的村道上，它自己 100% 都落在村道的 5 米内，
     * 于是被当成「同走向重复」整条删掉——用户看到的就是「明明有一户人家，门前那条小路却没画」。
     * 真实世界里平行的两条路才是重复，垂直相交是<b>路口</b>，不是重复。</p>
     *
     * @param minCos 允许的最小方向一致度（1=完全同向；0.86≈30°；&lt;0 表示不判方向）
     * @return 0~1 的覆盖率
     */
    public static double coverage(List<double[]> a, List<double[]> b, double tol, double step,
                                  double minCos) {
        if (a == null || b == null || a.size() < 2 || b.size() < 2) {
            return 0;
        }
        double useStep = step <= 0 ? 4.0 : step;
        int hit = 0;
        int total = 0;
        for (int i = 1; i < a.size(); i++) {
            double[] p = a.get(i - 1);
            double[] q = a.get(i);
            double seg = Math.hypot(q[0] - p[0], q[1] - p[1]);
            if (seg < 1e-9) {
                continue;
            }
            double ax = (q[0] - p[0]) / seg;
            double az = (q[1] - p[1]) / seg;
            int n = Math.max(1, (int) Math.ceil(seg / useStep));
            for (int k = 0; k < n; k++) {
                double tt = (double) k / n;
                double x = p[0] + (q[0] - p[0]) * tt;
                double z = p[1] + (q[1] - p[1]) * tt;
                total++;
                double[] hitPt = nearestOnPath(b, x, z);
                if (hitPt[2] > tol) {
                    continue;
                }
                if (minCos > 0) {
                    double[] dir = dirAt(b, hitPt[0], hitPt[1]);
                    if (dir == null || Math.abs(dir[0] * ax + dir[1] * az) < minCos) {
                        continue;   // 横着交过去的：那是路口/进道，不是重复路
                    }
                }
                hit++;
            }
        }
        return total == 0 ? 0 : (double) hit / (double) total;
    }

    /**
     * 折线上离给定点最近那一带的<b>走向</b>（单位向量）。
     *
     * <p><b>为什么取 ±14 米窗口的弦，而不是那一段的切向</b>：折线抽稀之后，一条路上会留下
     * 许多十来米的碎段，段与段之间的方向差可能很大（S 弯、绕树、绕田角）。用单段切向判
     * 「同走向」，两条明显重合在一起的短路会因为各自的小折点而被判成"不同走向"——
     * 两条二十米上下、几乎压在一起的支线，方向余弦可以低到 0.13，去重完全失效。取窗口弦之后，判的是"这条路往哪儿去"，而不是"这一米往哪儿拐"。</p>
     *
     * @param path 折线
     * @param x    参考点 X
     * @param z    参考点 Z
     * @return 单位走向；折线不足两点返回 null
     */
    private static double[] dirAt(List<double[]> path, double x, double z) {
        int bi = -1;
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double len = Math.hypot(vx, vz);
            if (len < 1e-9) {
                continue;
            }
            double t = Math.max(0, Math.min(1,
                    ((x - a[0]) * vx + (z - a[1]) * vz) / (len * len)));
            double d = Math.hypot(x - (a[0] + vx * t), z - (a[1] + vz * t));
            if (d < best) {
                best = d;
                bi = i;
            }
        }
        if (bi < 0) {
            return null;
        }
        int lo = bi - 1;
        int hi = bi;
        while (lo > 0 && Math.hypot(path.get(hi)[0] - path.get(lo - 1)[0],
                path.get(hi)[1] - path.get(lo - 1)[1]) <= WINDOW_METERS) {
            lo--;
        }
        while (hi < path.size() - 1 && Math.hypot(path.get(hi + 1)[0] - path.get(lo)[0],
                path.get(hi + 1)[1] - path.get(lo)[1]) <= WINDOW_METERS) {
            hi++;
        }
        double dx = path.get(hi)[0] - path.get(lo)[0];
        double dz = path.get(hi)[1] - path.get(lo)[1];
        double len = Math.hypot(dx, dz);
        return len < 1e-9 ? null : new double[]{dx / len, dz / len};
    }

    /**
     * 两条折线的<b>两个端点是否各自挨在一起</b>（正反两个方向取较好的一对）。
     *
     * <p>用途：判「同一条路的两份走线」。真实世界里两条重复的路几乎总是<b>连着同样两个地方</b>
     * ——镇口到渡口、街梢到码头。它们中间怎么弯不重要（一条绕树、一条绕田角，局部切向完全不同），
     * 但两端的落点一定重合。只按方向判会把这种"同一目的地、不同小折点"的重复路放过去
     * （两条二十米上下、方向余弦 0.84 的短支线，刚好卡在相似度阈值之外）。</p>
     *
     * @param a   折线 A
     * @param b   折线 B
     * @param max 允许的单端距离（米）
     * @return true=两端都挨在一起
     */
    public static boolean endsWithin(List<double[]> a, List<double[]> b, double max) {
        if (a == null || b == null || a.size() < 2 || b.size() < 2) {
            return false;
        }
        double[] a0 = a.get(0);
        double[] a1 = a.get(a.size() - 1);
        double[] b0 = b.get(0);
        double[] b1 = b.get(b.size() - 1);
        double straight = Math.hypot(a0[0] - b0[0], a0[1] - b0[1])
                + Math.hypot(a1[0] - b1[0], a1[1] - b1[1]);
        double cross = Math.hypot(a0[0] - b1[0], a0[1] - b1[1])
                + Math.hypot(a1[0] - b0[0], a1[1] - b0[1]);
        return Math.min(straight, cross) <= max * 2.0;
    }

    /** 折线长度（米）。 */
    public static double length(List<double[]> path) {
        if (path == null || path.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            sum += Math.hypot(path.get(i)[0] - path.get(i - 1)[0],
                    path.get(i)[1] - path.get(i - 1)[1]);
        }
        return sum;
    }

    private static double pathDist(List<double[]> a, List<double[]> b) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < a.size(); i++) {
            for (int j = 1; j < b.size(); j++) {
                double d = segSegDist(a.get(i - 1), a.get(i), b.get(j - 1), b.get(j));
                if (d < best) {
                    best = d;
                    if (best <= 0.05) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    private static double segSegDist(double[] p1, double[] p2, double[] q1, double[] q2) {
        // 只有一份几何实现：这里的距离与 nearestPair 取点必须同源，
        // 否则「判定是否相接」和「找最近接头」会给出互相矛盾的答案。
        return segSegClosest(p1, p2, q1, q2)[4];
    }

    /** 折线上离给定点最近的点（返回 {x, z, 距离}）。 */
    public static double[] nearestOnPath(List<double[]> path, double x, double z) {
        double best = Double.MAX_VALUE;
        double bx = x;
        double bz = z;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double vv = vx * vx + vz * vz;
            double t = vv < 1e-9 ? 0 : ((x - a[0]) * vx + (z - a[1]) * vz) / vv;
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + vx * t;
            double pz = a[1] + vz * t;
            double d = Math.hypot(x - px, z - pz);
            if (d < best) {
                best = d;
                bx = px;
                bz = pz;
            }
        }
        return new double[]{bx, bz, best};
    }
}
