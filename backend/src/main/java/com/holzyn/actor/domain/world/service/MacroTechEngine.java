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

package com.holzyn.actor.domain.world.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 科技演进引擎。
 * <p>职责：科技子系统每月推进（设计 §4.5； 「A + 衰退失传机制」——缓慢累积
 * [时间+人才+资源投入] + 里程碑突破触发世界级事件 + 联动效果 + 衰退失传）：</p>
 * <ul>
 * <li><b>累积</b>：progress += 时间自然增长（0.15/月，恒生效——无投入也不归零，仅放缓） + 入账贡献（本月科技 delta 聚合，
 * ×0.5 封顶 +0.8）+ 人才加成（职业人数 × 系数，农农户→agriculture / 工匠→craft /
 * 医者→medicine / 塾师·其他→array）× 3，全部 × 平台期放缓（×(1 − level/100×0.4)）；</li>
 * <li><b>突破判定</b>：上月值 &lt; 里程碑 ≤ 本月值（50 / 70 / 90）→「技艺突破」重大事件
 * （阈值 AI 叙事）+ 联动效果（agriculture→产量基准 +5% / medicine→瘟疫概率 ×0.7 /
 * craft→税收 +5% / array→压制弱势势力影响力 −5）；</li>
 * <li><b>衰退失传</b>：无投入（入账+人才均为 0）连续 ≥3 月 → 每月净 −1.35（时间 +0.15 抵消后）；
 * 累积值 &lt; 10 →「技艺失传」重大事件（维度重置 5 并清投入记录）；</li>
 * <li>全部确定性（daySeed 哈希抖动），钳制 0..100。</li>
 * </ul>
 */
public final class MacroTechEngine {

    private MacroTechEngine() {
    }

    /** 科技月度 tick 输入 */
    public record TechInput(Map<String, Double> techLevels, Map<String, Double> techDeltas,
                            Map<String, Integer> techNoInputMonths, Map<String, Double> prevLevels,
                            Map<String, Long> occupationCounts, long daySeed) {
    }

    /** 科技月度 tick 结果（techLevels 原地演进 + 事件；事件码=通用语义码） */
    public record TechResult(Map<String, Double> techLevels, Map<String, Integer> techNoInputMonths,
                             List<String> events ) {
    }

    /** 里程碑（50/70/90：突破阈值档位） */
    private static final double[] MILESTONES = {50.0, 70.0, 90.0};

    /** 时间自然增长（/月） */
    private static final double TIME_GAIN = 0.15;
    /** 入账贡献强度（×0.5，封顶 +0.8/月） */
    private static final double DELTA_WEIGHT = 0.5;
    private static final double DELTA_CAP = 0.8;
    /** 人才加成系数（职业人数 × 系数 × 3） */
    private static final double TALENT_WEIGHT = 3.0;
    /** 平台期放缓（×(1 − level/100×0.4)） */
    private static final double PLATEAU_FACTOR = 0.4;
    /** 无投入月数阈值（≥3 月 → 衰退） */
    private static final int NO_INPUT_MONTHS = 3;
    /** 衰退速率（/月，无投入超过阈值后每超 1 月） */
    private static final double DECAY_RATE = 1.5;
    /** 失传阈值（累积值 < 10 → 技艺失传） */
    private static final double LOST_THRESHOLD = 10.0;
    /** 失传后重置值 */
    private static final double LOST_RESET = 5.0;

    /** 职业 → 科技维度（人才加成映射）。 */
    static String dimensionOfOccupation(String occupation) {
        if (occupation == null) {
            return null;
        }
        return switch (occupation) {
            case "农户", "渔夫", "樵夫", "采药人" -> "agriculture";
            case "工匠" -> "craft";
            case "医者" -> "medicine";
            case "塾师" -> "array";
            default -> null;
        };
    }

    /** 人才加成：维度 → 本月人才点数（职业人数 × 0.5，仅正向维度）。 */
    static Map<String, Double> talentByDimension(Map<String, Long> occupations) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (occupations == null) {
            return out;
        }
        for (Map.Entry<String, Long> e : occupations.entrySet()) {
            String dim = dimensionOfOccupation(e.getKey());
            if (dim != null && e.getValue() != null) {
                out.merge(dim, e.getValue() * 0.5, Double::sum);
            }
        }
        return out;
    }

    /**
     * 科技月度推进（确定性；techLevels/techNoInputMonths 原地演进）。
     * <p>⚠️ 前置修复（月度 tick NPE）：输入空值防御——techLevels/techDeltas/
     * techNoInputMonths/prevLevels/occupationCounts 为 null 或个别值为 null 时归一为
     * 安全空值（null 值按 0.0 计），引擎永不抛 NPE（空值解包防御）。</p>
     *
     * @return 事件列表（元素格式「技艺突破:agriculture」/「技艺失传:array」）
     */
    public static TechResult step(TechInput in) {
        List<String> events = new ArrayList<>();
        Map<String, Double> techLevels = in.techLevels() == null ? new LinkedHashMap<>() : in.techLevels();
        Map<String, Double> techDeltas = in.techDeltas() == null ? new LinkedHashMap<>() : in.techDeltas();
        Map<String, Integer> noInputMonths = in.techNoInputMonths() == null
                ? new LinkedHashMap<>() : in.techNoInputMonths();
        Map<String, Double> prevLevels = in.prevLevels() == null ? new LinkedHashMap<>() : in.prevLevels();
        Map<String, Double> talent = talentByDimension(in.occupationCounts());

        for (Map.Entry<String, Double> e : techLevels.entrySet()) {
            String dim = e.getKey();
            double prev = e.getValue() == null ? 0.0 : e.getValue();
            // null 值防御：getOrDefault 在「键存在但值为 null」时返回 null，直接赋 double 会解包 NPE
            Double prevMBox = prevLevels.get(dim);
            double prevM = prevMBox == null ? prev : prevMBox;

            Double deltaBox = techDeltas.get(dim);
            double delta = Math.min(DELTA_CAP, (deltaBox == null ? 0.0 : deltaBox) * DELTA_WEIGHT);
            double talentPoint = talent.getOrDefault(dim, 0.0) * TALENT_WEIGHT;
            double input = delta + talentPoint;
            Integer noInputBox = noInputMonths.get(dim);
            int noInput = noInputBox == null ? 0 : noInputBox;

            double gain;
            if (input <= 0.0) {
                // 无投入：连续月数 +1；≥3 月 → 衰退（净 −1.35/月；时间自然增长恒生效）
                noInput++;
                gain = TIME_GAIN - (noInput >= NO_INPUT_MONTHS ? DECAY_RATE : 0.0);
            } else {
                noInput = 0;
                gain = TIME_GAIN + input;
            }
            double plateau = 1.0 - prev / 100.0 * PLATEAU_FACTOR;
            double next = clamp(prev + gain * plateau, 0.0, 100.0);

            // 失传判定（衰退到 <10 → 技艺失传，重置；事件码=通用语义码 tech_lost:{dim}）
            if (next < LOST_THRESHOLD && noInput >= NO_INPUT_MONTHS) {
                events.add("tech_lost:" + dim);
                next = LOST_RESET;
                noInput = 0;
            }
            // 突破判定（跨里程碑；事件码=通用语义码 tech_breakthrough:{dim}）
            for (double ms : MILESTONES) {
                if (prevM < ms && next >= ms) {
                    events.add("tech_breakthrough:" + dim);
                    break;
                }
            }
            techLevels.put(dim, round2(next));
            noInputMonths.put(dim, noInput);
        }
        return new TechResult(techLevels, noInputMonths, events);
    }

    /** 钳制到 [min, max]。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 两位小数舍入。 */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
