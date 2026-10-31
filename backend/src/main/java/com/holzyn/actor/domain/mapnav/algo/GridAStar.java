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

import java.util.ArrayList;
import java.util.List;

/**
 * 网格 A*（8 邻接、按弧长代价、结果确定）。
 *
 * <p><b>为什么自己写而不是引库</b>：GitHub 上能找到的 Java 网格寻路库几乎都是教学实现
 * （2026-10-30 检索，按 star 排序最高不到 10），没有一家支持「代价场 + 建筑净空 + 沿路加权」
 * 这套语义；而本项目的代价语义已经在 {@code RoadRouter} 里跑了几万次生成，重写一遍反而更稳。</p>
 *
 * <p><b>为什么先做直线可达判定</b>：院里走三步（3 米）也跑一遍 1024² 的 A* 是纯浪费；
 * 直线可走时直接返回两点，一格都不展开。这是最常见的一类移动（屋里到门口、田头到田尾）。</p>
 *
 * <p><b>为什么结果要拉直</b>：格子路径是锯齿阶梯，直接渲染就是「一格一格横着挪」。
 * 这里用「最远可视点」拉直（string pulling）：从当前点出发，找最远一个直线可走的路径点，
 * 中间全部丢掉。走出来的斜线贴着可走区域边缘，既像人走的路，也不穿墙。</p>
 *
 * <p><b>确定性</b>：开放表用稳定的小顶堆，同代价时按节点索引升序取——同输入必然同输出，
 * 这样「同一份地图、同一个角色、同一个目标」两次跑出来的轨迹可以逐点对比（验收要用）。</p>
 */
public final class GridAStar {

    /** 直线步代价下限（路格 0.35 × 距离 1）——启发函数必须不高估。 */
    private static final double MIN_STEP = CostField.ROAD_FACTOR;

    /**
     * 直线直连的距离上限（米）。
     *
     * <p>为什么要有上限：屋里走到门口（3 米）跑一遍全图 A* 是浪费；但 300 米的「直线可达」
     * 恰恰是要避免的——用户要的是「沿路走」。12 米以内当作院内短距，超出就必须让 A* 用代价说话。</p>
     */
    private static final double SHORT_DIRECT_M = 12.0;

    /**
     * 拉直时的代价容忍度。
     *
     * <p>只按「视线通不通」拉直会抄草地斜线（视线当然通，草地又不是墙）；所以拉直还要比代价：
     * 直线段的代价不得超过沿 A* 原路走该段的 1.15 倍。这样「沿路的弯」会被保留，
     * 「格子锯齿」会被拉平。</p>
     */
    private static final double PULL_TOLERANCE = 1.15;

    /** 对角步距离。 */
    private static final double SQRT2 = Math.sqrt(2.0);

    private final CostField field;

    private final int size;

    /** g 值（起点到该格的实际代价）。 */
    private final float[] g;

    /** 父节点（-1 = 未访问）。 */
    private final int[] came;

    /** 访问戳（避免每次清零 4 MB 数组）。 */
    private final int[] visitStamp;

    private int stamp = 0;

    // ---------- 开放表（int 节点 + float f 的二叉堆，惰性删除） ----------

    private int[] heapNode = new int[1 << 12];

    private float[] heapF = new float[1 << 12];

    private int heapSize = 0;

    /**
     * 构造（数组按需复用，避免每次寻路分配 12 MB）。
     *
     * @param field 代价场
     */
    public GridAStar(CostField field) {
        this.field = field;
        this.size = field.size();
        int n = size * size;
        this.g = new float[n];
        this.came = new int[n];
        this.visitStamp = new int[n];
    }

    /**
     * 寻路（米坐标）。
     *
     * @param sx 起点 X（米）
     * @param sz 起点 Z（米）
     * @param tx 终点 X（米）
     * @param tz 终点 Z（米）
     * @return 路径；不可达返回 {@link NavPath#none()}
     */
    public synchronized NavPath find(double sx, double sz, double tx, double tz) {
        int[] start = field.nearestWalkable(sx, sz, 8);
        int[] goal = field.nearestWalkable(tx, tz, 8);
        if (start == null || goal == null) {
            return NavPath.none();
        }
        int sx0 = start[0];
        int sz0 = start[1];
        int gx = goal[0];
        int gz = goal[1];
        if (sx0 == gx && sz0 == gz) {
            List<double[]> one = new ArrayList<>(2);
            one.add(new double[]{sx, sz});
            one.add(new double[]{tx, tz});
            return new NavPath(one, Math.hypot(tx - sx, tz - sz), 0, true);
        }
        // 直线可走且够近 → 不用展开（院内走动）
        if (Math.hypot(tx - sx, tz - sz) <= SHORT_DIRECT_M
                && field.lineOfSight(sx0, sz0, gx, gz)) {
            double len = Math.hypot(gx - sx0, gz - sz0);
            List<double[]> pts = new ArrayList<>(3);
            pts.add(new double[]{sx, sz});
            pts.add(new double[]{gx + 0.5, gz + 0.5});
            pts.add(new double[]{tx, tz});
            return new NavPath(dedupe(pts), len + Math.hypot(sx - (sx0 + 0.5), sz - (sz0 + 0.5)), 0, true);
        }
        return search(sx0, sz0, gx, gz, sx, sz, tx, tz);
    }

    /** 真正的 A*。 */
    private NavPath search(int sx0, int sz0, int gx, int gz,
                           double startX, double startZ, double endX, double endZ) {
        stamp++;
        heapSize = 0;
        int start = sz0 * size + sx0;
        int goal = gz * size + gx;
        visitStamp[start] = stamp;
        g[start] = 0f;
        came[start] = -1;
        push(start, (float) heuristic(sx0, sz0, gx, gz));
        int expanded = 0;
        boolean found = false;
        while (heapSize > 0) {
            int node = popNode();
            if (visitStamp[node] != stamp) {
                continue;
            }
            if (node == goal) {
                found = true;
                break;
            }
            expanded++;
            int x = node % size;
            int z = node / size;
            float gn = g[node];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!field.walkable(nx, nz)) {
                        continue;
                    }
                    // 斜走不许「切角」：两侧正交格必须都能站，否则人会从房角穿过去
                    if (dx != 0 && dz != 0
                            && (!field.walkable(x + dx, z) || !field.walkable(x, z + dz))) {
                        continue;
                    }
                    int nb = nz * size + nx;
                    boolean fresh = visitStamp[nb] != stamp;
                    if (fresh) {
                        visitStamp[nb] = stamp;
                        g[nb] = Float.MAX_VALUE;
                        came[nb] = -1;
                    }
                    double step = (dx != 0 && dz != 0) ? SQRT2 : 1.0;
                    float tentative = (float) (gn + step * (field.costAt(node) + field.costAt(nb)) * 0.5);
                    if (tentative < g[nb]) {
                        g[nb] = tentative;
                        came[nb] = node;
                        push(nb, (float) (tentative + heuristic(nx, nz, gx, gz)));
                    }
                }
            }
        }
        if (!found) {
            return NavPath.none();
        }
        List<int[]> cells = new ArrayList<>();
        for (int n = goal; n >= 0; n = came[n]) {
            cells.add(new int[]{n % size, n / size});
        }
        java.util.Collections.reverse(cells);
        List<int[]> pulled = stringPull(cells);
        List<double[]> pts = new ArrayList<>(pulled.size() + 2);
        pts.add(new double[]{startX, startZ});
        for (int[] c : pulled) {
            pts.add(new double[]{c[0] + 0.5, c[1] + 0.5});
        }
        pts.add(new double[]{endX, endZ});
        List<double[]> clean = dedupe(pts);
        return new NavPath(clean, polylineLength(clean), expanded, false);
    }

    /** 八方向距离启发（不高估：每格至少 0.35 代价）。 */
    private double heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return (max - min) * MIN_STEP + min * MIN_STEP * SQRT2;
    }

    /**
     * 最远可视点拉直。
     *
     * @param cells 逐格路径（含起终点）
     * @return 拉直后的关键点
     */
    private List<int[]> stringPull(List<int[]> cells) {
        List<int[]> out = new ArrayList<>();
        if (cells.isEmpty()) {
            return out;
        }
        int i = 0;
        out.add(cells.get(0));
        while (i < cells.size() - 1) {
            int j = cells.size() - 1;
            while (j > i + 1 && !canPull(cells.get(i), cells.get(j))) {
                j--;
            }
            out.add(cells.get(j));
            i = j;
        }
        return out;
    }

    /**
     * 能否把 i→j 之间整段删掉（只留两端）。
     *
     * <p>两个条件同时成立才行：① 视线可通；② 直线段的代价不比原路贵太多。
     * 只有①会抄草地斜线，只有②会保留锯齿，两条一起才既像人走的路、又贴着路网。</p>
     */
    private boolean canPull(int[] a, int[] b) {
        if (!field.lineOfSight(a[0], a[1], b[0], b[1])) {
            return false;
        }
        double straight = straightCost(a, b);
        int ia = a[1] * size + a[0];
        int ib = b[1] * size + b[0];
        if (visitStamp[ia] != stamp || visitStamp[ib] != stamp) {
            return true;
        }
        double along = g[ib] - g[ia];
        if (along <= 1e-6) {
            return false;
        }
        return straight <= along * PULL_TOLERANCE + 0.5;
    }

    /** 直线段的近似代价（按 1 米采样平均格代价 × 长度）。 */
    private double straightCost(int[] a, int[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double len = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(len));
        double cost = 0;
        for (int s = 0; s < steps; s++) {
            double t = (s + 0.5) / steps;
            int x = Math.max(0, Math.min(size - 1, (int) Math.round(a[0] + dx * t)));
            int z = Math.max(0, Math.min(size - 1, (int) Math.round(a[1] + dz * t)));
            cost += field.costAt(z * size + x) * (len / steps);
        }
        return cost;
    }

    /** 去掉重合点（< 1 mm 视为同一点）。 */
    private static List<double[]> dedupe(List<double[]> pts) {
        List<double[]> out = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            if (!out.isEmpty()) {
                double[] last = out.get(out.size() - 1);
                if (Math.abs(last[0] - p[0]) < 0.001 && Math.abs(last[1] - p[1]) < 0.001) {
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    /** 折线长度。 */
    public static double polylineLength(List<double[]> pts) {
        double sum = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            sum += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return sum;
    }

    /**
     * 沿折线按弧长取点（移动 tick 用）。
     *
     * @param pts    折线
     * @param along  已走距离（米，自动钳制到 [0, 总长]）
     * @return {x, z}
     */
    public static double[] pointAt(List<double[]> pts, double along) {
        if (pts == null || pts.isEmpty()) {
            return new double[]{0, 0};
        }
        if (pts.size() == 1) {
            return new double[]{pts.get(0)[0], pts.get(0)[1]};
        }
        double remain = Math.max(0, along);
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (seg <= 1e-9) {
                continue;
            }
            if (remain <= seg) {
                double t = remain / seg;
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            remain -= seg;
        }
        double[] last = pts.get(pts.size() - 1);
        return new double[]{last[0], last[1]};
    }

    /**
     * 诊断：某格的 g 值（未访问返回 {@code NaN}）。
     *
     * <p>只给单测/排障用——「沿路率不达标」时第一个要看的就是「A* 到底认不认这条路」。</p>
     *
     * @param x X
     * @param z Z
     * @return g 值
     */
    float gOf(int x, int z) {
        int i = z * size + x;
        return visitStamp[i] == stamp && stamp > 0 ? g[i] : Float.NaN;
    }

    // ==================== 二叉小顶堆（稳定：f 相同按节点升序） ====================

    private void push(int node, float f) {
        if (heapSize == heapNode.length) {
            int cap = heapNode.length << 1;
            heapNode = java.util.Arrays.copyOf(heapNode, cap);
            heapF = java.util.Arrays.copyOf(heapF, cap);
        }
        int i = heapSize++;
        heapNode[i] = node;
        heapF[i] = f;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (less(heapF[i], heapNode[i], heapF[parent], heapNode[parent])) {
                swap(i, parent);
                i = parent;
            } else {
                break;
            }
        }
    }

    private int popNode() {
        int top = heapNode[0];
        heapSize--;
        if (heapSize > 0) {
            heapNode[0] = heapNode[heapSize];
            heapF[0] = heapF[heapSize];
            int i = 0;
            while (true) {
                int l = (i << 1) + 1;
                int r = l + 1;
                int best = i;
                if (l < heapSize && less(heapF[l], heapNode[l], heapF[best], heapNode[best])) {
                    best = l;
                }
                if (r < heapSize && less(heapF[r], heapNode[r], heapF[best], heapNode[best])) {
                    best = r;
                }
                if (best == i) {
                    break;
                }
                swap(i, best);
                i = best;
            }
        }
        return top;
    }

    private static boolean less(float fa, int na, float fb, int nb) {
        return fa < fb || (fa == fb && na < nb);
    }

    private void swap(int a, int b) {
        int tn = heapNode[a];
        heapNode[a] = heapNode[b];
        heapNode[b] = tn;
        float tf = heapF[a];
        heapF[a] = heapF[b];
        heapF[b] = tf;
    }
}
