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
import java.util.Arrays;
import java.util.List;

/**
 * 道路寻路（最小代价 A*）——把「谁连谁」变成在真实地形上走得通的折线。
 * <p>代价场（每一格走进去要付多少代价）：</p>
 * <ul>
 *   <li><b>坡度</b>：{@code 26 * slope^1.6}——陡坡极贵，所以路会自动盘山而不是直着爬；</li>
 *   <li><b>水面</b>：河要架桥（+28/格）、海不可通行（视为墙）——所以路一定在窄处过河；</li>
 *   <li><b>林地/湿地</b>：+2.2 / +3.5（开路要砍树排水）；</li>
 *   <li><b>已有道路</b>：<b>负</b>代价（-0.8）——后修的路会主动并到已有路上，
 *       于是自然长出「一条主街 + 若干支路接入」的树状路网，而不是一堆各走各的平行线；</li>
 *   <li><b>建筑占地</b>：+240（基本不可通行，保证路不穿房子）。</li>
 * </ul>
 * <p>这是「布局符合逻辑」的第二个关键：路不是画出来的，是<b>在地形上求出来的</b>。</p>
 */
public final class RoadRouter {

    /** 海（不可通行）。 */
    private static final float WALL = 1e9f;

    /** 河（可架桥）。 */
    private static final float RIVER_COST = 28f;

    /** 建筑占地。 */
    private static final float BUILDING_COST = 240f;

    /** 允许被「拉直」的最长直线距离（米）：超过这个尺度，弯是地形给的，不该抹掉。 */
    private static final double MAX_STRAIGHT_METERS = 140.0;

    private RoadRouter() {
    }

    /**
     * 一次寻路的结果。
     *
     * @param path       折线（格坐标，首尾为起终点）
     * @param bridgeCells 跨水格数（>0 说明要架桥）
     * @param length     折线长度（米）
     */
    public record Route(List<double[]> path, int bridgeCells, double length) {
    }

    /**
     * 构建代价场（可复用：一张图建一次，多条路共用）。
     *
     * @param t     地形
     * @param occ   占用场（0=空，1=建筑；可为 null）
     * @param avoid 额外禁行（可为 null）
     * @return 代价场
     */
    public static float[] buildCost(TerrainData t, float[] occ, boolean[] avoid) {
        int n = t.size * t.size;
        float[] cost = new float[n];
        double sea = t.seaLevel;
        for (int i = 0; i < n; i++) {
            if (t.height[i] <= sea) {
                // 海不可走；河（riverMask 且高于海面）可架桥
                cost[i] = t.riverMask[i] && t.height[i] > sea ? RIVER_COST : WALL;
                continue;
            }
            // 湖面同样不可通行：湖的水面高程是填洼填出来的，可能高于海平面，
            // 只按高程判会让 A* 直接把路拉过湖心——航拍上就是一条压在水上的路。
            if (t.biome[i] == Biomes.LAKE) {
                cost[i] = WALL;
                continue;
            }
            double slope = t.slope[i];
            double c = 1.0 + 26.0 * Math.pow(Math.min(1.0, slope), 1.6);
            if (t.riverMask[i]) {
                c += RIVER_COST;
            }
            byte b = t.biome[i];
            if (b == Biomes.FOREST_DENSE) {
                c += 2.2;
            } else if (b == Biomes.FOREST_LIGHT) {
                c += 1.4;
            } else if (b == Biomes.WETLAND) {
                c += 3.5;
            } else if (b == Biomes.BEACH) {
                c += 0.6;
            }
            if (occ != null && occ[i] > 0) {
                c += BUILDING_COST;
            }
            if (avoid != null && avoid[i]) {
                c += 60;
            }
            cost[i] = (float) c;
        }
        return cost;
    }

    /**
     * A* 寻路。
     *
     * @param t     地形
     * @param cost  代价场（{@link #buildCost}）
     * @param sx    起点 X（格）
     * @param sz    起点 Z（格）
     * @param gx    终点 X（格）
     * @param gz    终点 Z（格）
     * @param bend  弯曲倾向 0~1（越大越愿意绕远求缓）
     * @return 结果（找不到返回 null）
     */
    public static Route route(TerrainData t, float[] cost, int sx, int sz, int gx, int gz, double bend) {
        int size = t.size;
        int n = size * size;
        sx = clamp(sx, 1, size - 2);
        sz = clamp(sz, 1, size - 2);
        gx = clamp(gx, 1, size - 2);
        gz = clamp(gz, 1, size - 2);
        int start = sz * size + sx;
        int goal = gz * size + gx;

        float[] dist = new float[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        boolean[] closed = new boolean[n];
        // 弯曲代价：直线靠近目标的路线代价略低，但不要压过地形代价
        double stiff = 1.0 + 3.0 * (1.0 - Math.max(0, Math.min(1, bend)));

        IntHeap open = new IntHeap(Math.max(1024, n / 16));
        dist[start] = 0;
        open.push(start, 0);
        int[] nx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] nz = {0, 0, 1, -1, 1, -1, 1, -1};
        int guard = 0;
        while (!open.isEmpty() && guard++ < n * 3) {
            int cur = open.pop();
            if (closed[cur]) {
                continue;
            }
            closed[cur] = true;
            if (cur == goal) {
                break;
            }
            int cx = cur % size;
            int cz = cur / size;
            for (int k = 0; k < 8; k++) {
                int ax = cx + nx[k];
                int az = cz + nz[k];
                if (ax < 1 || az < 1 || ax >= size - 1 || az >= size - 1) {
                    continue;
                }
                int a = az * size + ax;
                if (closed[a] || cost[a] >= WALL) {
                    continue;
                }
                // 斜走不许「切角」穿过两个墙格
                if (k >= 4) {
                    if (cost[cz * size + ax] >= WALL || cost[az * size + cx] >= WALL) {
                        continue;
                    }
                }
                double step = (k >= 4 ? 1.41421356 : 1.0) * (0.5 * (cost[cur] + cost[a]) * 0.5);
                if (cost[cur] >= WALL) {
                    continue;
                }
                double nd = dist[cur] + step;
                if (nd < dist[a]) {
                    dist[a] = (float) nd;
                    prev[a] = cur;
                    double h = Math.hypot(ax - gx, az - gz);
                    // 轻微偏好直线：h 的权重 < 1，避免把 A* 变成纯贪心
                    open.push(a, (float) (nd + h * stiff * 0.35));
                }
            }
        }
        if (prev[goal] < 0 && goal != start) {
            return null;
        }
        List<double[]> path = new ArrayList<>();
        int cur = goal;
        int guard2 = 0;
        while (cur >= 0 && guard2++ < n) {
            path.add(new double[]{cur % size + 0.5, cur / size + 0.5});
            if (cur == start) {
                break;
            }
            cur = prev[cur];
        }
        if (path.isEmpty() || (path.get(path.size() - 1)[0] != sx + 0.5)) {
            return null;
        }
        java.util.Collections.reverse(path);
        int bridges = 0;
        for (double[] p : path) {
            int i = ((int) p[1]) * size + (int) p[0];
            if (i >= 0 && i < n && t.riverMask[i]) {
                bridges++;
            }
        }
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
        }
        return new Route(simplify(path, 1.2), bridges, len * t.metersPerCell);
    }

    /**
     * 把 A* 的逐格阶梯折线拉直 + 圆角，得到可以直接渲染的道路。
     * <p>分两步：Douglas-Peucker 抽稀（去掉共线点）→ Chaikin 圆角（去掉 90 度硬折）。</p>
     *
     * @param pts 原始点列（格）
     * @param tol 抽稀容差
     * @return 处理后的点列
     */
    public static List<double[]> simplify(List<double[]> pts, double tol) {
        if (pts.size() <= 2) {
            return new ArrayList<>(pts);
        }
        List<double[]> thin = dp(pts, tol);
        List<double[]> out = new ArrayList<>(thin);
        for (int it = 0; it < 2; it++) {
            List<double[]> next = new ArrayList<>();
            next.add(out.get(0));
            for (int i = 0; i < out.size() - 1; i++) {
                double[] a = out.get(i);
                double[] b = out.get(i + 1);
                next.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                next.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
            next.add(out.get(out.size() - 1));
            out = next;
        }
        return out;
    }

    /**
     * 把「绕远」的路拉直：A* 会为了避开林/坡/水绕一大圈，但现实里两个近邻点之间的路
     * 往往是直接踩出来的直路。这里做一次后处理：
     *
     * <ul>
     *   <li>直线距离 &lt; 路径长度的 70%（说明绕得明显），且</li>
     *   <li>直线长度 ≤ {@value #MAX_STRAIGHT_METERS} 米（长路的弯是地形给的，不许抹平），且</li>
     *   <li>这条直线沿途不下水、不穿建筑（代价场 &gt; 100 视为挡住）</li>
     * </ul>
     *
     * 满足就把整条路替换成两点直线；否则原样返回。**只处理「不该绕」的情况**：
     * 真正需要绕的（中间是湖、是宅子）仍然走 A*，不会把路画到水里或房里。
     *
     * @param terrain 地形
     * @param cost    代价场（可为 null，null 时只查水陆）
     * @param path    路径（米）
     * @return 拉直后的路径（米）；不满足条件时返回原对象
     */
    public static List<double[]> straighten(TerrainData terrain, float[] cost, List<double[]> path) {
        if (path == null || path.size() < 3) {
            return path;
        }
        double[] a = path.get(0);
        double[] b = path.get(path.size() - 1);
        double straight = Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (straight < 6.0) {
            return path;
        }
        // 长路不许拉直：一条 300 米的完美直线在航拍上比绕远更假（实测：镇上到码头那条 321 米
        // 的路被拉成一把尺子，直接横穿整片林子的中线）。真正需要"就近接上"的是几十米的近邻
        // 连接，那才是 A* 会绕出圈的尺度；长路绕开密林、绕开湖面本身是真实的。
        if (straight > MAX_STRAIGHT_METERS) {
            return path;
        }
        double walked = 0;
        for (int i = 1; i < path.size(); i++) {
            walked += Math.hypot(path.get(i)[0] - path.get(i - 1)[0],
                    path.get(i)[1] - path.get(i - 1)[1]);
        }
        // 绕行比例不够大就不动：贴着地形走的缓弯是"自然",不是"绕远"
        if (straight > walked * 0.70) {
            return path;
        }
        double mpc = Math.max(0.05, terrain.metersPerCell);
        if (!lineClear(terrain, cost, a, b, mpc)) {
            return path;
        }
        // 别拉成一把尺子：几十米尺度上真实的路也有缓弯（沿等高线、绕一棵树、贴着地界）。
        // 取原折线相对弦的横向偏移，按 45% 保留（上限 5 米），在 1/3、2/3 处各放一个点。
        double[] p1 = bowedPoint(path, walked, 0.34, a, b);
        double[] p2 = bowedPoint(path, walked, 0.67, a, b);
        if (p1 != null && p2 != null && lineClear(terrain, cost, a, p1, mpc)
                && lineClear(terrain, cost, p1, p2, mpc) && lineClear(terrain, cost, p2, b, mpc)) {
            List<double[]> bowed = new ArrayList<>();
            bowed.add(new double[]{a[0], a[1]});
            bowed.add(p1);
            bowed.add(p2);
            bowed.add(new double[]{b[0], b[1]});
            return bowed;
        }
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{a[0], a[1]});
        out.add(new double[]{b[0], b[1]});
        return out;
    }

    /** 两点之间的直线能不能修路（不下水、不穿建筑）。 */
    private static boolean lineClear(TerrainData terrain, float[] cost, double[] a, double[] b, double mpc) {
        double straight = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int size = terrain.size;
        int steps = Math.max(2, (int) Math.ceil(straight / Math.max(0.5, mpc)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = a[0] + (b[0] - a[0]) * t;
            double z = a[1] + (b[1] - a[1]) * t;
            int cx = (int) Math.floor(x / mpc);
            int cz = (int) Math.floor(z / mpc);
            if (cx < 1 || cz < 1 || cx >= size - 1 || cz >= size - 1) {
                return false;
            }
            if (terrain.waterCell(cz * size + cx)) {
                return false;
            }
            if (cost != null && cost.length >= size * size && cost[cz * size + cx] > 100f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 折线在给定弧长比例处的「缓弯点」：弦上该比例的位置 + 原折线的横向偏移（打折后）。
     *
     * @param path    原折线
     * @param walked  原折线总长
     * @param frac    弦上的比例（0~1）
     * @param a       弦起点
     * @param b       弦终点
     * @return 缓弯点；偏移太小（&lt;1.2 米，本来就走得直）返回 null
     */
    private static double[] bowedPoint(List<double[]> path, double walked, double frac,
                                       double[] a, double[] b) {
        double vx = b[0] - a[0];
        double vz = b[1] - a[1];
        double len = Math.hypot(vx, vz);
        if (len < 1e-9) {
            return null;
        }
        double target = walked * frac;
        double acc = 0;
        double[] hit = null;
        for (int i = 1; i < path.size(); i++) {
            double seg = Math.hypot(path.get(i)[0] - path.get(i - 1)[0],
                    path.get(i)[1] - path.get(i - 1)[1]);
            if (seg > 1e-9 && acc + seg >= target) {
                double t = (target - acc) / seg;
                hit = new double[]{path.get(i - 1)[0] + (path.get(i)[0] - path.get(i - 1)[0]) * t,
                        path.get(i - 1)[1] + (path.get(i)[1] - path.get(i - 1)[1]) * t};
                break;
            }
            acc += seg;
        }
        if (hit == null) {
            return null;
        }
        double nx = -vz / len;
        double nz = vx / len;
        double off = (hit[0] - a[0]) * nx + (hit[1] - a[1]) * nz;
        if (Math.abs(off) < 1.2) {
            return null;
        }
        double keep = Math.max(-5.0, Math.min(5.0, off * 0.45));
        return new double[]{a[0] + vx * frac + nx * keep, a[1] + vz * frac + nz * keep};
    }

    private static List<double[]> dp(List<double[]> pts, double tol) {
        int n = pts.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        dpRec(pts, 0, n - 1, tol, keep);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void dpRec(List<double[]> pts, int a, int b, double tol, boolean[] keep) {
        if (b <= a + 1) {
            return;
        }
        double[] pa = pts.get(a);
        double[] pb = pts.get(b);
        double vx = pb[0] - pa[0];
        double vz = pb[1] - pa[1];
        double len = Math.hypot(vx, vz);
        double maxD = -1;
        int maxI = a;
        for (int i = a + 1; i < b; i++) {
            double[] p = pts.get(i);
            double d = len < 1e-9 ? Math.hypot(p[0] - pa[0], p[1] - pa[1])
                    : Math.abs(vz * (p[0] - pa[0]) - vx * (p[1] - pa[1])) / len;
            if (d > maxD) {
                maxD = d;
                maxI = i;
            }
        }
        if (maxD > tol) {
            keep[maxI] = true;
            dpRec(pts, a, maxI, tol, keep);
            dpRec(pts, maxI, b, tol, keep);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 极简二叉堆（keyed by float priority）。 */
    private static final class IntHeap {

        private int[] items;

        private float[] keys;

        private int size;

        IntHeap(int cap) {
            items = new int[Math.max(16, cap)];
            keys = new float[Math.max(16, cap)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int item, float key) {
            if (size == items.length) {
                int[] i2 = new int[size * 2];
                float[] k2 = new float[size * 2];
                System.arraycopy(items, 0, i2, 0, size);
                System.arraycopy(keys, 0, k2, 0, size);
                items = i2;
                keys = k2;
            }
            int i = size++;
            items[i] = item;
            keys[i] = key;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (keys[p] <= keys[i]) {
                    break;
                }
                swap(i, p);
                i = p;
            }
        }

        int pop() {
            int top = items[0];
            size--;
            items[0] = items[size];
            keys[0] = keys[size];
            int i = 0;
            while (true) {
                int l = i * 2 + 1;
                if (l >= size) {
                    break;
                }
                int r = l + 1;
                int m = (r < size && keys[r] < keys[l]) ? r : l;
                if (keys[i] <= keys[m]) {
                    break;
                }
                swap(i, m);
                i = m;
            }
            return top;
        }

        private void swap(int a, int b) {
            int ti = items[a];
            items[a] = items[b];
            items[b] = ti;
            float tk = keys[a];
            keys[a] = keys[b];
            keys[b] = tk;
        }
    }
}
