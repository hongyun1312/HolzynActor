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

package com.holzyn.actor.domain.evaluation.temporal.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 时间性审计的纯函数与传输契约。
 * <p><b>为什么把统计量集中在此</b>：JSD / 置换检验 / 坍缩度等指标必须可用单测固定口径，
 * 混在 Service 里会被 Spring 依赖拖住而无法纯测；这里全部 static、无状态、无 IO。</p>
 * <p><b>口径</b>：动作五型（移动/交谈/劳作/等待/延续）+ 其它；时段 6 桶（4 小时一桶）；
 * 昼夜按 06:00–17:59 昼、18:00–05:59 夜；JSD 以 2 为底、Laplace +1 平滑，取值 [0,1]。</p>
 */
public final class TemporalAuditSupport {

    private TemporalAuditSupport() {
    }

    // ==================== 传输契约 ====================

    /** 探针批次请求（字段全可空：空=全角色/全类型）。 */
    public record ProbeRequest(List<String> characterRefs, List<String> probeTypes, String runTag) {
    }

    /** 单条探针视图。 */
    public record ProbeView(Long id, String runTag, String probeType, String variant, String characterRef,
                            Long gameSecond, Long gameDay, String verdict, String question,
                            String evidence, String expected, String answerRaw, String assertionDetail,
                            Integer latencyMs, String traceId) {
    }

    /** 探针批次结果（计数与明细）。 */
    public record BatchResult(String runTag, long gameSecond, int total, int pass, int fail,
                              int inconclusive, int skip, int error, List<ProbeView> probes) {
    }

    /** 因果断言违规。 */
    public record Assertion(String rule, String characterRef, Long decisionId, Long gameSecond, String detail) {
    }

    /** 因果断言报告。 */
    public record CausalReport(int decisionsScanned, Map<String, Integer> countsByRule, List<Assertion> violations) {
    }

    /** 两分布差异（JSD + 置换检验 p 值）。 */
    public record DistPair(String dimension, String a, String b, double jsd, double pValue,
                           boolean significant, int nA, int nB) {
    }

    /** 单角色分布画像。 */
    public record CharDistribution(String characterRef, String characterName, int total,
                                   Map<String, Integer> actions, Map<String, Integer> buckets,
                                   double entropy, double collapseRatio, double maxShare,
                                   List<DistPair> pairs) {
    }

    /** 行为分布审计报告。 */
    public record DistributionReport(long fromGameSecond, long toGameSecond, int decisionCount,
                                     List<CharDistribution> characters, Map<String, Integer> actionCoverage) {
    }

    /** 衰减曲线单点。 */
    public record DecayPoint(double ageHours, double recency, double score) {
    }

    /** 一条衰减曲线（打分层）。 */
    public record DecayCurve(String basis, List<DecayPoint> points, double spearman) {
    }

    /** 记忆时距衰减报告（打分层 + 真实记忆刷新证据）。 */
    public record DecayReport(boolean monotonic, double eventSpearman, double accessSpearman,
                              List<DecayCurve> curves, Map<String, Integer> ageBuckets,
                              int memories, int refreshedOldMemories, double refreshRatio,
                              String verdict) {
    }

    /** 时间性总报告（数据页一次取全）。 */
    public record TemporalReport(long gameSecond, int weekday, String weekdayLabel, boolean weekend,
                                 String generatedAt, DistributionReport distribution, DecayReport decay,
                                 CausalReport causal, Map<String, Integer> probeVerdicts) {
    }

    // ==================== 文本/口径 ====================

    /** 动作五型之一。 */
    public static String classifyAction(String text) {
        if (text == null || text.isBlank()) {
            return "延续";
        }
        String t = text;
        if (containsAny(t, "说", "问", "聊", "告诉", "招呼", "喊", "谈", "答", "劝")) {
            return "交谈";
        }
        // 移动只认「位移意图」的动词短语：裸「去/进/出」会把「睡去」「过去」「出现」误判成移动，
        // 进而让（移动决策无目标）虚高（实测 15 条里多条是「迷糊睡去」这类小动作）。
        if (containsAny(t, "前往", "走到", "赶往", "赶到", "离开", "返回", "回到", "动身", "上路",
                "出门", "走进", "走出", "搬", "回家", "回去", "去往", "前去", "进了", "出了")) {
            return "移动";
        }
        if (containsAny(t, "做", "修", "耕", "卖", "煮", "打", "写", "整理", "挑", "扫", "种", "砍", "挖")) {
            return "劳作";
        }
        if (containsAny(t, "等", "歇", "休息", "睡", "坐", "发呆", "守")) {
            return "等待";
        }
        if (containsAny(t, "继续", "接着", "仍", "还是")) {
            return "延续";
        }
        return "其它";
    }

    /** 游戏秒所在时段桶（6 桶）。 */
    public static String bucketOf(long gameSecond) {
        int h = hourOf(gameSecond);
        int idx = h / 4;
        int start = idx * 4;
        return String.format("%02d-%02d", start, start + 4);
    }

    /** 游戏秒小时（0..23）。 */
    public static int hourOf(long gameSecond) {
        return (int) Math.floorMod(Math.floorDiv(gameSecond, 3600L), 24L);
    }

    /** 昼/夜（06:00–17:59 为昼）。 */
    public static String dayNight(long gameSecond) {
        int h = hourOf(gameSecond);
        return h >= 6 && h < 18 ? "day" : "night";
    }

    /** 工作日/周末。 */
    public static String weekdayKind(long gameSecond) {
        int w = com.holzyn.actor.domain.world.service.WorldClockService.weekdayIndex(gameSecond);
        return com.holzyn.actor.domain.world.service.WorldClockService.isWeekend(w) ? "weekend" : "weekday";
    }

    /** 游戏秒的「第 N 日 HH:mm」短标签。 */
    public static String timeLabel(long gameSecond) {
        long day = Math.floorDiv(gameSecond, 86400L) + 1;
        int h = hourOf(gameSecond);
        int m = (int) Math.floorMod(Math.floorDiv(gameSecond, 60L), 60L);
        return "第" + day + "日 " + String.format("%02d:%02d", h, m);
    }

    /** 计数（保序）。 */
    public static Map<String, Integer> counts(List<String> labels) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String l : labels) {
            m.merge(l == null ? "其它" : l, 1, Integer::sum);
        }
        return m;
    }

    /** 香农熵（bit）。 */
    public static double entropy(Map<String, Integer> counts) {
        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (n <= 0) {
            return 0.0;
        }
        double e = 0.0;
        for (int c : counts.values()) {
            if (c <= 0) {
                continue;
            }
            double p = (double) c / n;
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }

    /** 坍缩度 = 1 - 归一化熵（0=完全均匀，1=单点坍缩）。 */
    public static double collapseRatio(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0.0;
        }
        double e = entropy(counts);
        double max = Math.log(counts.size()) / Math.log(2);
        return max <= 0 ? 0.0 : Math.max(0.0, 1.0 - e / max);
    }

    /** 最大单类占比。 */
    public static double maxShare(Map<String, Integer> counts) {
        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (n <= 0) {
            return 0.0;
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return (double) max / n;
    }

    /** Jensen-Shannon 散度（bit，[0,1]；Laplace +1 平滑）。 */
    public static double jsd(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        if (keys.isEmpty()) {
            return 0.0;
        }
        double sa = a.values().stream().mapToInt(Integer::intValue).sum() + keys.size();
        double sb = b.values().stream().mapToInt(Integer::intValue).sum() + keys.size();
        double jsd = 0.0;
        for (String k : keys) {
            double p = (a.getOrDefault(k, 0) + 1) / sa;
            double q = (b.getOrDefault(k, 0) + 1) / sb;
            double m = (p + q) / 2;
            jsd += 0.5 * p * (Math.log(p / m) / Math.log(2));
            jsd += 0.5 * q * (Math.log(q / m) / Math.log(2));
        }
        return Math.max(0.0, Math.min(1.0, jsd));
    }

    /**
     * 置换检验：分组标签是否显著改变动作分布（JSD）。
     *
     * @param labels 每条决策的动作类别
     * @param inGroup 同一顺序的「属于 B 组」标记（如周末）
     * @param iterations 置换次数（默认调用方给 10000）
     * @param seed 随机种子（同数据可复现）
     *
     * @return p 值（双侧近似：置换 JSD ≥ 观测值的比例）
     */
    public static double permutationP(List<String> labels, List<Boolean> inGroup, int iterations, long seed) {
        int n = labels.size();
        if (n < 8 || iterations <= 0) {
            return 1.0;
        }
        Map<String, Integer> a = new LinkedHashMap<>();
        Map<String, Integer> b = new LinkedHashMap<>();
        partition(labels, inGroup, a, b);
        if (a.values().stream().mapToInt(Integer::intValue).sum() < 3
                || b.values().stream().mapToInt(Integer::intValue).sum() < 3) {
            return 1.0;
        }
        double observed = jsd(a, b);
        List<Boolean> flags = new ArrayList<>(inGroup);
        Random rnd = new Random(seed);
        int ge = 0;
        for (int i = 0; i < iterations; i++) {
            Collections.shuffle(flags, rnd);
            Map<String, Integer> ra = new LinkedHashMap<>();
            Map<String, Integer> rb = new LinkedHashMap<>();
            partition(labels, flags, ra, rb);
            if (jsd(ra, rb) >= observed - 1e-12) {
                ge++;
            }
        }
        return (ge + 1.0) / (iterations + 1.0);
    }

    private static void partition(List<String> labels, List<Boolean> inGroup,
                                  Map<String, Integer> a, Map<String, Integer> b) {
        for (int i = 0; i < labels.size(); i++) {
            Map<String, Integer> target = Boolean.TRUE.equals(inGroup.get(i)) ? b : a;
            String key = labels.get(i) == null ? "其它" : labels.get(i);
            target.merge(key, 1, Integer::sum);
        }
    }

    /**
     * Spearman 秩相关。
     *
     * @param x 自变量（如年龄小时）
     * @param y 因变量（如检索得分）
     *
     * @return ρ（样本不足或零方差返回 0）
     */
    public static double spearman(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 3) {
            return 0.0;
        }
        double[] rx = ranks(Arrays.copyOf(x, n));
        double[] ry = ranks(Arrays.copyOf(y, n));
        double mx = Arrays.stream(rx).average().orElse(0);
        double my = Arrays.stream(ry).average().orElse(0);
        double num = 0.0;
        double dx = 0.0;
        double dy = 0.0;
        for (int i = 0; i < n; i++) {
            num += (rx[i] - mx) * (ry[i] - my);
            dx += (rx[i] - mx) * (rx[i] - mx);
            dy += (ry[i] - my) * (ry[i] - my);
        }
        if (dx <= 1e-12 || dy <= 1e-12) {
            return 0.0;
        }
        return num / Math.sqrt(dx * dy);
    }

    private static double[] ranks(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Math.abs(v[idx[j + 1]] - v[idx[i]]) < 1e-12) {
                j++;
            }
            double rank = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = rank;
            }
            i = j + 1;
        }
        return r;
    }

    /** 中文/字母二元组 Jaccard 相似度（0..1；用于回答与真值动作的粗匹配）。 */
    public static double bigramJaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new LinkedHashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        String t = s.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        if (t.length() == 1) {
            out.add(t);
        }
        return out;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
