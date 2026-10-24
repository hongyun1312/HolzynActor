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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 水文分析——填洼（priority-flood）→ D8 流向 → 汇流累积 → 提取河网与湖泊。
 * <p>这一步保证「河一定往低处流、一定入海、不会爬山」：</p>
 * <ol>
 *   <li><b>填洼</b>：把地形里的局部凹坑抬平（Barnes 2014 priority-flood），得到「无坑」排水面，
 *       避免 D8 流向在坑里打转；</li>
 *   <li><b>D8 流向</b>：每格指向排水面上最低的邻居（等值加微抬，保证严格下降）；</li>
 *   <li><b>汇流累积</b>：按排水面高度降序传播雨量，得到每格的汇水面积（越大越是主河道）；</li>
 *   <li><b>河网</b>：汇水面积超过阈值且在海平面以上的连通格 → 河流；</li>
 *   <li><b>湖泊</b>：填洼抬高量显著且面积够大的洼地 → 湖（水面积 = 填洼后的水面）；</li>
 *   <li><b>河道折线</b>：从汇流极大的源头沿 D8 下行到海，得到可直接渲染的河流路径。</li>
 * </ol>
 */
public final class HydroFlow {

    /** 浮点键量化倍数（priority-flood 堆用）。 */
    private static final double KEY_SCALE = 100000.0;

    /** 填洼微抬量。 */
    private static final double EPS = 1e-5;

    /** 湖泊最小面积（格）：1 公里见方的岛上，小于 600 平方米的水面只是洼地积水，不配叫湖。 */
    private static final int MIN_LAKE_AREA = 600;

    /** 湖泊最大面积（占全图比例）：再大的洼地直接填平，否则会出现一块突兀的大水塘。 */
    private static final double MAX_LAKE_AREA_RATIO = 0.012;

    private HydroFlow() {
    }

    /**
     * 执行水文分析（会写入 biome/flow/rivers/lakes，并抬高湖面）。
     *
     * @param t 地形
     * @param p 参数
     */
    public static void analyze(TerrainData t, TerrainParams p) {
        int size = t.size;
        int n = size * size;
        float[] h = t.height;
        float sea = (float) t.seaLevel;

        // ---------- ① 填洼 ----------
        float[] filled = new float[n];
        boolean[] done = new boolean[n];
        LongHeap heap = new LongHeap(n / 4 + 64);
        int[] order = new int[n];
        int orderSize = 0;

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                boolean border = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                int i = z * size + x;
                if (border || h[i] <= sea) {
                    filled[i] = h[i];
                    done[i] = true;
                    heap.push(pack(h[i], i));
                }
            }
        }

        int[] nx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] nz = {0, 0, 1, -1, 1, -1, 1, -1};

        while (!heap.isEmpty()) {
            int c = unpackIndex(heap.pop());
            order[orderSize++] = c;
            int cx = c % size;
            int cz = c / size;
            for (int k = 0; k < 8; k++) {
                int ax = cx + nx[k];
                int az = cz + nz[k];
                if (ax < 0 || az < 0 || ax >= size || az >= size) {
                    continue;
                }
                int a = az * size + ax;
                if (done[a]) {
                    continue;
                }
                done[a] = true;
                float f = Math.max(h[a], filled[c] + (float) EPS);
                filled[a] = f;
                heap.push(pack(f, a));
            }
        }

        // ---------- ② D8 流向 ----------
        int[] down = new int[n];
        java.util.Arrays.fill(down, -1);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (h[i] <= sea) {
                    continue;
                }
                double best = filled[i];
                int bestIdx = -1;
                for (int k = 0; k < 8; k++) {
                    int ax = x + nx[k];
                    int az = z + nz[k];
                    if (ax < 0 || az < 0 || ax >= size || az >= size) {
                        continue;
                    }
                    int a = az * size + ax;
                    double f = filled[a] + (k >= 4 ? 1e-7 : 0);
                    if (f < best) {
                        best = f;
                        bestIdx = a;
                    }
                }
                down[i] = bestIdx;
            }
        }

        // ---------- ③ 汇流累积（MFD：多流向，按坡度幂权分散） ----------
        // 为什么不用 D8：1 米格网上的缓坡平原几乎是「等高的斜平面」，D8 每格都指向同一个
        // 最陡邻居 → 一整片平行直线，到下游再合成一个扇形，看起来像犁过的田（典型假地图）。
        // 多流向把每格的产流按坡度^1.15 分摊给所有下坡邻居：斜平面上分摊均匀 → 不出现直线；
        // 只有在真正的沟谷（一条邻谷显著更低）才会收敛成河道，于是河网自然变成树枝状。
        float[] acc = new float[n];
        java.util.Arrays.fill(acc, 0f);
        double[] wts = new double[8];
        for (int oi = orderSize - 1; oi >= 0; oi--) {
            int i = order[oi];
            int cx = i % size;
            int cz = i / size;
            acc[i] += 1f;
            if (h[i] <= sea) {
                // 水面不再往下游传播：河口就是汇流的终点，否则整片海都会「攒」出天大汇流量
                continue;
            }
            double sum = 0;
            java.util.Arrays.fill(wts, 0);
            for (int k = 0; k < 8; k++) {
                int ax = cx + nx[k];
                int az = cz + nz[k];
                if (ax < 0 || az < 0 || ax >= size || az >= size) {
                    continue;
                }
                int a = az * size + ax;
                double dh = (double) filled[i] - filled[a];
                if (dh <= 0) {
                    continue;
                }
                double slope = dh / (k >= 4 ? 1.4142135623730951 : 1.0);
                double w = Math.pow(slope, 1.15);
                wts[k] = w;
                sum += w;
            }
            if (sum <= 0) {
                continue;
            }
            double out = acc[i] / sum;
            for (int k = 0; k < 8; k++) {
                if (wts[k] <= 0) {
                    continue;
                }
                int a = (cz + nz[k]) * size + (cx + nx[k]);
                acc[a] += (float) (out * wts[k]);
            }
        }
        t.flow = acc;

        // ---------- ④ 河网 ----------
        // 阈值按「汇水面积占全图比例」定，但这个比例必须小到能长出**整条**河。
        // 早期用 0.62%（1024² → 6501 格），而实测汇流量分布是极度偏态的：
        // p90=193、p99=1740、p100=96363——也就是说 6501 这条线只有河口那几个格子过得了，
        // 于是每条河都只剩 90 米的"末梢"戳在海边，全岛四条河加起来 360 格，
        // 在 1024² 图上就是几个看不见的点。
        // 0.04%（≈419 格）能让河道从海岸一路回溯到山坡上的浅沟，长度落到
        // 300~600 米的常识范围；再靠 maxRivers 控制"只画主河"。
        int threshold = Math.max(250, (int) (size * (double) size * 0.0004 / Math.max(0.2, p.riverDensity)));

        // 沿汇流树「从河口往上游」抽主干，得到连续折线。
        // 不能只把「汇流量超阈值的格子」染色：平原上汇流是渐变的，超阈值的格子会断成
        // 一段一段浮在草地上的蓝线（之前那张图就是这样）。走汇流树才能保证「源头 → 河口」不断。
        // 河口 = **最后一格陆地**（它下游就是海），不是"水面里有汇流量的格子"。
        // 早期写成 (h <= sea || down < 0)，把整片近岸海水都算成了河口：海水格虽然不再往下游
        // 传播汇流量（见 ③ 的 continue），但它身上挂着整个流域的 acc，于是"汇流量最大的 5 个
        // 河口"其实是同一个湾里相邻的 5 格海水——5 条河全是同一处的碎末。
        List<Integer> outlets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (acc[i] < threshold || h[i] <= sea) {
                continue;
            }
            int d = down[i];
            if (d < 0 || h[d] <= sea) {
                outlets.add(i);
            }
        }
        outlets.sort((a, b) -> Float.compare(acc[b], acc[a]));
        // 主河条数：真实小岛只有 1~2 条主河 + 若干支流，不是五条各走各的"排水沟"。
        // 旧口径 1+riverCount*2（默认 5）在 1 公里的岛上会抽出 5 条互不相连的河，
        // 每条都是"从半山腰插进海里"的一段，图上读起来像五道蓝色胶带。
        int maxRivers = Math.max(1, Math.min(3, p.riverCount));
        List<double[]> polys = new ArrayList<>();
        boolean[] used = new boolean[n];
        // 河口之间至少隔 48 米，否则两条"河"会是同一条的上下游片段
        List<int[]> mouths = new ArrayList<>();
        for (int oi2 = 0; oi2 < outlets.size() && polys.size() < maxRivers; oi2++) {
            int mouth = outlets.get(oi2);
            if (used[mouth]) {
                continue;
            }
            int mx = mouth % size;
            int mz = mouth / size;
            boolean tooClose = false;
            for (int[] m : mouths) {
                if (Math.hypot(m[0] - mx, m[1] - mz) < 48) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            // 上游回溯：沿着**最陡上升**的方向走（等价于逆着水流走回分水岭）。
            // 三种写法都试过，只有这个稳：
            //   ① 用 down[] 反查 bestUp[]：MFD 把每格产流分摊给 8 个下坡邻居，而 down[] 只记
            //      一个，反查出的"最佳上游"只承接当前格百分之一的流量，走两步就掉到阈值以下
            //      （实测最长 85 米）；
            //   ② 在上游邻格里挑 acc 最大的：平坦坡面上单个上游邻格的 acc 常常**大于**当前格
            //      （当前格的流量被摊薄了），"acc 必须比当前格小"这条过滤直接把路堵死
            //      （实测最长 19 米）；
            //   ③ 现在这个：只看填充高程，每步取高程最高的邻格。单调上升保证不绕圈，
            //      且天然沿着谷底主槽回溯，河长才落到 300~600 米的常识范围。
            List<double[]> pts = new ArrayList<>();
            int cur = mouth;
            while (cur >= 0 && pts.size() < 900) {
                used[cur] = true;
                pts.add(new double[]{cur % size + 0.5, cur / size + 0.5});
                int cx = cur % size;
                int cz = cur / size;
                int up = -1;
                double bestH = filled[cur];
                for (int k = 0; k < 8; k++) {
                    int ax = cx + nx[k];
                    int az = cz + nz[k];
                    if (ax < 0 || az < 0 || ax >= size || az >= size) {
                        continue;
                    }
                    int a = az * size + ax;
                    if (h[a] <= sea || used[a] || filled[a] <= bestH) {
                        continue;
                    }
                    bestH = filled[a];
                    up = a;
                }
                if (up < 0) {
                    break;
                }
                cur = up;
            }
            if (pts.size() >= 8) {
                java.util.Collections.reverse(pts);
                polys.add(meander(pts, p.seed + polys.size() * 31L));
                mouths.add(new int[]{mx, mz});
            }
        }
        // ---------- ④b 支流：河网必须有分叉 ----------
        // 只抽主干会得到一条「从山脚直插海里」的单笔画，看起来像排水沟。
        // 真实河网的辨识特征是 **Y 形分叉**：两条支沟在半山腰汇入主槽，水量翻倍、河宽加大。
        // 做法：找贴着主槽（8 邻接 used 格）、汇流量仍 >= 阈值 35% 的格子当"沟口"，
        // 沿最陡上升回溯成一条短折线，末端自然落在主槽上——于是图上就是支流汇入主干。
        int branchBudget = Math.max(2, maxRivers * 2);
        int branches = 0;
        for (int i = 0; i < n && branches < branchBudget; i++) {
            if (used[i] || h[i] <= sea || acc[i] < threshold * 0.35f) {
                continue;
            }
            int cx = i % size;
            int cz = i / size;
            boolean touchesStem = false;
            for (int k = 0; k < 8 && !touchesStem; k++) {
                int ax = cx + nx[k];
                int az = cz + nz[k];
                if (ax < 0 || az < 0 || ax >= size || az >= size) {
                    continue;
                }
                touchesStem = used[az * size + ax];
            }
            if (!touchesStem) {
                continue;
            }
            List<double[]> pts = new ArrayList<>();
            int cur = i;
            while (cur >= 0 && pts.size() < 320) {
                pts.add(new double[]{cur % size + 0.5, cur / size + 0.5});
                if (used[cur]) {
                    break;
                }
                int bx = cur % size;
                int bz = cur / size;
                int up = -1;
                double bestH = filled[cur];
                for (int k = 0; k < 8; k++) {
                    int ax = bx + nx[k];
                    int az = bz + nz[k];
                    if (ax < 0 || az < 0 || ax >= size || az >= size) {
                        continue;
                    }
                    int a = az * size + ax;
                    if (h[a] <= sea || filled[a] <= bestH) {
                        continue;
                    }
                    bestH = filled[a];
                    up = a;
                }
                if (up < 0) {
                    break;
                }
                cur = up;
            }
            if (pts.size() >= 14) {
                java.util.Collections.reverse(pts);
                // 支流只标记自己走过的格子（不能标记主槽），宽度由 brushChannels 按 acc 决定
                polys.add(meander(pts, p.seed + 991 + branches * 37L));
                ++branches;
            }
        }
        brushChannels(t, polys, acc, threshold);
        // 河道轻微下切（水往低处走，也让渲染的湿地带自然）
        for (int i = 0; i < n; i++) {
            if (t.riverMask[i]) {
                h[i] = Math.max(sea + 0.002f, h[i] - 0.006f);
            }
        }

        // ---------- ⑤ 湖泊 ----------
        // 侵蚀/填洼会在山间留下大量小洼地；只有「够大且够深」的才配当湖，
        // 且数量受 lakeCount 约束（多出来的直接填平，避免地图上全是水泡）。
        List<double[]> lakes = new ArrayList<>();
        List<int[]> lakeCells = new ArrayList<>();
        List<Double> lakeAreas = new ArrayList<>();
        List<Double> lakeCx = new ArrayList<>();
        List<Double> lakeCz = new ArrayList<>();
        boolean[] lakeVisited = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (lakeVisited[i] || h[i] <= sea) {
                continue;
            }
            if (filled[i] - h[i] <= 1e-4) {
                continue;
            }
            List<Integer> comp = new ArrayList<>();
            queue.clear();
            queue.add(i);
            lakeVisited[i] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll();
                comp.add(c);
                int cx = c % size;
                int cz = c / size;
                for (int k = 0; k < 8; k++) {
                    int ax = cx + nx[k];
                    int az = cz + nz[k];
                    if (ax < 0 || az < 0 || ax >= size || az >= size) {
                        continue;
                    }
                    int a = az * size + ax;
                    if (lakeVisited[a] || h[a] <= sea || filled[a] - h[a] <= 1e-4) {
                        continue;
                    }
                    lakeVisited[a] = true;
                    queue.add(a);
                }
            }
            if (comp.size() >= MIN_LAKE_AREA && comp.size() <= MAX_LAKE_AREA_RATIO * n) {
                // 湖必须是"盆"：一条蜿蜒河谷被填满水时，它的外接矩形利用率很低（0.1~0.2），
                // 俯视图上是一条发黑的窄水带——那是"被水淹的沟"，不是湖。
                // 真正的湖是盆地，水体基本填满外接矩形的 30% 以上。
                int minX = Integer.MAX_VALUE;
                int maxX = 0;
                int minZ = Integer.MAX_VALUE;
                int maxZ = 0;
                for (int c : comp) {
                    int cx = c % size;
                    int cz = c / size;
                    if (cx < minX) {
                        minX = cx;
                    }
                    if (cx > maxX) {
                        maxX = cx;
                    }
                    if (cz < minZ) {
                        minZ = cz;
                    }
                    if (cz > maxZ) {
                        maxZ = cz;
                    }
                }
                double box = (double) (maxX - minX + 1) * (maxZ - minZ + 1);
                double compact = comp.size() / Math.max(1.0, box);
                if (compact < 0.28) {
                    for (int c : comp) {
                        t.height[c] = filled[c];
                    }
                    continue;
                }
                int[] arr = new int[comp.size()];
                double sx = 0;
                double sz = 0;
                for (int j = 0; j < comp.size(); j++) {
                    arr[j] = comp.get(j);
                    sx += comp.get(j) % size;
                    sz += comp.get(j) / size;
                }
                lakeCells.add(arr);
                lakeAreas.add((double) comp.size());
                lakeCx.add(sx / comp.size());
                lakeCz.add(sz / comp.size());
            } else {
                for (int c : comp) {
                    t.height[c] = filled[c];
                }
            }
        }
        // 选湖：<b>面积大 + 离聚落远</b>。
        // 只按面积排序时，最大的洼地常常正好落在聚落所在的缓坡盆地里——于是村子紧贴着一块水塘，
        // 路网绕着水塘拐弯、房子排到湖边就断，读起来像"池塘压着村口"。
        // 真实聚落与水体的关系是「近水但不压水」：湖在山脚或谷底，离村 100 米以上。
        // 所以排序分 = 面积 × min(1, 离聚落距离/120m)，距离近的大湖仍可能入选，但不会被小水塘挤掉。
        double settlePx = p.settlementX * size;
        double settlePz = p.settlementZ * size;
        double[] lakeScore = new double[lakeCells.size()];
        for (int i = 0; i < lakeScore.length; i++) {
            double d = Math.hypot(lakeCx.get(i) - settlePx, lakeCz.get(i) - settlePz) * t.metersPerCell;
            lakeScore[i] = lakeAreas.get(i) * Math.min(1.0, d / 120.0);
        }
        Integer[] orderIdx = new Integer[lakeCells.size()];
        for (int i = 0; i < orderIdx.length; i++) {
            orderIdx[i] = i;
        }
        java.util.Arrays.sort(orderIdx, (a, b) -> Double.compare(lakeScore[b], lakeScore[a]));
        int keep = Math.max(0, p.lakeCount);
        for (int rank = 0; rank < orderIdx.length; rank++) {
            int[] cells = lakeCells.get(orderIdx[rank]);
            if (rank < keep) {
                double sx = 0;
                double sz = 0;
                for (int c : cells) {
                    t.height[c] = filled[c];
                    t.biome[c] = Biomes.LAKE;
                    sx += c % size;
                    sz += c / size;
                }
                lakes.add(new double[]{sx / cells.length, sz / cells.length, cells.length});
            } else {
                for (int c : cells) {
                    t.height[c] = filled[c];
                }
            }
        }
        t.lakes = lakes;

        // ---------- ⑥ 河道折线（供前端画渐变线 / 后续桥梁选址） ----------
        List<double[]> simplified = new ArrayList<>();
        for (double[] poly : polys) {
            simplified.add(simplify(toPoints(poly), 0.45));
        }
        t.rivers = simplified;
    }

    /**
     * 河道蜿蜒：真实河流在平原上会左右摆（meander），基本上是正弦状的弯。
     * <p>D8/MFD 抽出来的主干在平缓区几乎是一条直线，加一层沿弧长的正弦横向偏移就自然了；
     * 两端按 12 格收窄，避免河口/源头漂到水里去。</p>
     */
    private static double[] meander(List<double[]> pts, long salt) {
        int n = pts.size();
        double[] out = new double[n * 2];
        Rng rng = Rng.derive(salt, 91);
        double phase = rng.nextDouble() * Math.PI * 2;
        double lambda = 70 + 60 * rng.nextDouble();
        double amp = 3.0 + 2.4 * rng.nextDouble();
        double arc = 0;
        for (int i = 0; i < n; i++) {
            double x = pts.get(i)[0];
            double z = pts.get(i)[1];
            if (i > 0) {
                arc += Math.hypot(x - pts.get(i - 1)[0], z - pts.get(i - 1)[1]);
            }
            int a = Math.max(0, i - 1);
            int b = Math.min(n - 1, i + 1);
            double tx = pts.get(b)[0] - pts.get(a)[0];
            double tz = pts.get(b)[1] - pts.get(a)[1];
            double tl = Math.hypot(tx, tz);
            if (tl < 1e-6) {
                out[i * 2] = x;
                out[i * 2 + 1] = z;
                continue;
            }
            double px = -tz / tl;
            double pz = tx / tl;
            double taper = Math.min(1.0, Math.min(i, n - 1 - i) / 12.0);
            double off = amp * Math.sin(arc / lambda * Math.PI * 2 + phase) * taper;
            out[i * 2] = x + px * off;
            out[i * 2 + 1] = z + pz * off;
        }
        return out;
    }

    /** 河道折线 → 平滑遮罩（圆刷沿折线连续盖印，河岸是曲线、上游细下游宽）。 */
    private static void brushChannels(TerrainData t, List<double[]> polys, float[] acc, int threshold) {
        int size = t.size;
        for (double[] poly : polys) {
            int pts = poly.length / 2;
            for (int k = 0; k + 1 < pts; k++) {
                double x0 = poly[k * 2];
                double z0 = poly[k * 2 + 1];
                double x1 = poly[(k + 1) * 2];
                double z1 = poly[(k + 1) * 2 + 1];
                double len = Math.hypot(x1 - x0, z1 - z0);
                int steps = Math.max(1, (int) Math.ceil(len * 2));
                for (int s = 0; s <= steps; s++) {
                    double tt = s / (double) steps;
                    double px = x0 + (x1 - x0) * tt;
                    double pz = z0 + (z1 - z0) * tt;
                    int cx = (int) Math.floor(px);
                    int cz = (int) Math.floor(pz);
                    if (cx < 0 || cz < 0 || cx >= size || cz >= size) {
                        continue;
                    }
                    double a = acc[cz * size + cx];
                    double w = Math.log10(Math.max(1.0, a / (double) threshold)) / 0.9;
                    double radius = 1.05 + 2.10 * Math.min(1.0, Math.max(0.0, w));
                    stamp(t, px, pz, radius);
                }
            }
        }
    }

    private static void stamp(TerrainData t, double px, double pz, double radius) {
        int size = t.size;
        int r = (int) Math.ceil(radius) + 1;
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        for (int dz = -r; dz <= r; dz++) {
            int az = cz + dz;
            if (az < 0 || az >= size) {
                continue;
            }
            for (int dx = -r; dx <= r; dx++) {
                int ax = cx + dx;
                if (ax < 0 || ax >= size) {
                    continue;
                }
                double d = Math.hypot(ax + 0.5 - px, az + 0.5 - pz);
                double a = 1.0 - d / (radius + 0.9);
                if (a <= 0) {
                    continue;
                }
                a = a * a * (3 - 2 * a);
                int idx = az * size + ax;
                if (t.riverAlpha[idx] < a) {
                    t.riverAlpha[idx] = (float) a;
                }
                if (t.height[idx] > t.seaLevel) {
                    t.riverMask[idx] = true;
                }
            }
        }
    }

    private static List<double[]> toPoints(double[] flat) {
        List<double[]> out = new ArrayList<>(flat.length / 2);
        for (int i = 0; i + 1 < flat.length; i += 2) {
            out.add(new double[]{flat[i], flat[i + 1]});
        }
        return out;
    }

    private static double[] simplify(List<double[]> pts, double tol) {
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        dp(pts, 0, pts.size() - 1, tol, keep);
        int cnt = 0;
        for (boolean b : keep) {
            if (b) {
                cnt++;
            }
        }
        double[] out = new double[cnt * 2];
        int p = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) {
                out[p++] = pts.get(i)[0];
                out[p++] = pts.get(i)[1];
            }
        }
        return out;
    }

    private static void dp(List<double[]> pts, int a, int b, double tol, boolean[] keep) {
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
            double d;
            if (len < 1e-9) {
                d = Math.hypot(p[0] - pa[0], p[1] - pa[1]);
            } else {
                d = Math.abs(vz * (p[0] - pa[0]) - vx * (p[1] - pa[1])) / len;
            }
            if (d > maxD) {
                maxD = d;
                maxI = i;
            }
        }
        if (maxD > tol) {
            keep[maxI] = true;
            dp(pts, a, maxI, tol, keep);
            dp(pts, maxI, b, tol, keep);
        }
    }

    private static long pack(double key, int index) {
        long q = (long) (key * KEY_SCALE);
        if (q < 0) {
            q = 0;
        }
        return (q << 20) | (index & 0xFFFFFL);
    }

    private static int unpackIndex(long v) {
        return (int) (v & 0xFFFFFL);
    }

    /**
     * 极简二叉最小堆（long 打包键），比 {@code PriorityQueue<Long>} 快一个数量级，
     * 用于 100 万格的填洼遍历。
     */
    private static final class LongHeap {

        private long[] data;

        private int size;

        LongHeap(int cap) {
            data = new long[Math.max(16, cap)];
        }

        void push(long v) {
            if (size == data.length) {
                long[] d2 = new long[data.length * 2];
                System.arraycopy(data, 0, d2, 0, size);
                data = d2;
            }
            int i = size++;
            data[i] = v;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (data[p] <= data[i]) {
                    break;
                }
                long t = data[p];
                data[p] = data[i];
                data[i] = t;
                i = p;
            }
        }

        long pop() {
            long top = data[0];
            data[0] = data[--size];
            int i = 0;
            while (true) {
                int l = i * 2 + 1;
                if (l >= size) {
                    break;
                }
                int r = l + 1;
                int m = (r < size && data[r] < data[l]) ? r : l;
                if (data[i] <= data[m]) {
                    break;
                }
                long t = data[i];
                data[i] = data[m];
                data[m] = t;
                i = m;
            }
            return top;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
