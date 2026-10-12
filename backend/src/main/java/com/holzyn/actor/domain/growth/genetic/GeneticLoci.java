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

package com.holzyn.actor.domain.growth.genetic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 遗传位点规格定义。
 * <p><b>15 位点 = 10 个 OCEAN 位点 + 5 个气质/学习/天赋位点</b>：</p>
 * <ul>
 * <li>OCEAN 每个维度两个位点：additive（三等位基因 A1/A2/A3 → 效应 0/0.5/1.0）+ dominant
 * （二等位基因 A4/A5 → 效应 0/1.0，显性等位基因决定表现型）；两座位加权平均得该维「位点效应值」；</li>
 * <li>TEMPO_AROUSAL/TEMPO_RECOVERY → ③ 生理基线的情绪唤醒阈值/恢复速率；</li>
 * <li>LEARN_SOCIAL/LEARN_HABIT → 社交学习/习惯形成倾向（⑥ 技能习得与 ⑧ 习惯强度）；</li>
 * <li>APTITUDE_GENERAL → 天赋综合倾向（⑥ 技能天赋基底）。</li>
 * </ul>
 * <p><b>等位基因命名约定</b>：字母前缀区分位点家族（A/C/E/F/N/T/L/G），数字后缀为效应索引
 * （additive 三等位：1→0.0 / 2→0.5 / 3→1.0；dominant 二等位：4→0.0 / 5→1.0）。</p>
 * <p><b>位点效应 → 表现型的口径</b>（孟德尔重组算法消费）：</p>
 * <ul>
 * <li>additive：两个等位基因效应均值（A1/A3 = (0+1)/2 = 0.5）；</li>
 * <li>dominant：显性等位基因（索引 1，即后缀 5）决定表现型（A4/A5 = 1.0，A4/A4 = 0.0）；</li>
 * <li>维度值 = 0.5×(additive 位点表现型) + 0.5×(dominant 位点表现型)——两个位点对维度等权；</li>
 * <li>OCEAN 五维的「位点效应值」构成 derived_traits，是遗传度框架的 midparent 输入
 * （：child = 群体均值 + h×(midparent−均值) + 环境噪声，h=0.7）。</li>
 * </ul>
 */
public final class GeneticLoci {

    private GeneticLoci() {
    }

    /** 位点定义（不可变）。 */
    public record LocusDef(String locusId, String dimension, List<String> alleles, String effect) {
    }

    /** 效应类型：加性（两个等位基因取均值）。 */
    public static final String EFFECT_ADDITIVE = "additive";
    /** 效应类型：显性（显性等位基因决定表现型）。 */
    public static final String EFFECT_DOMINANT = "dominant";

    /** OCEAN 维度码（derived_traits 键的维度前缀，如 {@code OCEAN_OPENNESS}）。 */
    public static final String DIM_OPENNESS = "OPENNESS";
    public static final String DIM_CONSCIENTIOUSNESS = "CONSCIENTIOUSNESS";
    public static final String DIM_EXTRAVERSION = "EXTRAVERSION";
    public static final String DIM_AGREEABLENESS = "AGREEABLENESS";
    public static final String DIM_NEUROTICISM = "NEUROTICISM";

    /** derived_traits 键名（与参考文档 §4.1 一致）。 */
    public static final String KEY_OCEAN_OPENNESS = "OCEAN_OPENNESS";
    public static final String KEY_OCEAN_CONSCIENTIOUSNESS = "OCEAN_CONSCIENTIOUSNESS";
    public static final String KEY_OCEAN_EXTRAVERSION = "OCEAN_EXTRAVERSION";
    public static final String KEY_OCEAN_AGREEABLENESS = "OCEAN_AGREEABLENESS";
    public static final String KEY_OCEAN_NEUROTICISM = "OCEAN_NEUROTICISM";
    public static final String KEY_TEMPO_AROUSAL = "TEMPO_AROUSAL";
    public static final String KEY_TEMPO_RECOVERY = "TEMPO_RECOVERY";
    public static final String KEY_LEARN_SOCIAL = "LEARN_SOCIAL";
    public static final String KEY_LEARN_HABIT = "LEARN_HABIT";
    public static final String KEY_APTITUDE_GENERAL = "APTITUDE_GENERAL";

    /** 全部 15 位点。 */
    private static final Map<String, LocusDef> DEFS = new LinkedHashMap<>();

    static {
        def("OCEAN_O_1", DIM_OPENNESS, List.of("A1", "A2", "A3"), EFFECT_ADDITIVE);
        def("OCEAN_O_2", DIM_OPENNESS, List.of("A4", "A5"), EFFECT_DOMINANT);
        def("OCEAN_C_1", DIM_CONSCIENTIOUSNESS, List.of("C1", "C2", "C3"), EFFECT_ADDITIVE);
        def("OCEAN_C_2", DIM_CONSCIENTIOUSNESS, List.of("C4", "C5"), EFFECT_DOMINANT);
        def("OCEAN_E_1", DIM_EXTRAVERSION, List.of("E1", "E2", "E3"), EFFECT_ADDITIVE);
        def("OCEAN_E_2", DIM_EXTRAVERSION, List.of("E4", "E5"), EFFECT_DOMINANT);
        def("OCEAN_A_1", DIM_AGREEABLENESS, List.of("F1", "F2", "F3"), EFFECT_ADDITIVE);
        def("OCEAN_A_2", DIM_AGREEABLENESS, List.of("F4", "F5"), EFFECT_DOMINANT);
        def("OCEAN_N_1", DIM_NEUROTICISM, List.of("N1", "N2", "N3"), EFFECT_ADDITIVE);
        def("OCEAN_N_2", DIM_NEUROTICISM, List.of("N4", "N5"), EFFECT_DOMINANT);
        def("TEMPO_AROUSAL", "TEMPO", List.of("T1", "T2"), EFFECT_ADDITIVE);
        def("TEMPO_RECOVERY", "TEMPO", List.of("T3", "T4"), EFFECT_ADDITIVE);
        def("LEARN_SOCIAL", "LEARN", List.of("L1", "L2"), EFFECT_ADDITIVE);
        def("LEARN_HABIT", "LEARN", List.of("L3", "L4"), EFFECT_ADDITIVE);
        def("APTITUDE_GENERAL", "APTITUDE", List.of("G1", "G2", "G3"), EFFECT_ADDITIVE);
    }

    private static void def(String locusId, String dimension, List<String> alleles, String effect) {
        DEFS.put(locusId, new LocusDef(locusId, dimension, alleles, effect));
    }

    /** 按位点 ID 取定义（未知位点返回 null）。 */
    public static LocusDef of(String locusId) {
        return DEFS.get(locusId);
    }

    /** 全部位点定义。 */
    public static Map<String, LocusDef> all() {
        return new LinkedHashMap<>(DEFS);
    }

    /**
     * 单个等位基因的效应值。
     * <p>additive：后缀 1→0.0 / 2→0.5 / 3→1.0；dominant：后缀 4→0.0 / 5→1.0。</p>
     *
     * @param locusId 位点 ID
     *
     * @param allele 等位基因（如 A1 /）
     *
     * @return 效应值 0~1；未知格式回退 0.5（零阻断）
     */
    public static double alleleEffect(String locusId, String allele) {
        LocusDef def = DEFS.get(locusId);
        if (def == null || allele == null || allele.isBlank()) {
            return 0.5;
        }
        String digit = allele.replaceAll("[^0-9]", "");
        if (digit.isEmpty()) {
            return 0.5;
        }
        int idx;
        try {
            idx = Integer.parseInt(digit);
        } catch (NumberFormatException e) {
            return 0.5;
        }
        if (EFFECT_ADDITIVE.equals(def.effect())) {
            // 1/2/3 → 0/0.5/1.0；未知索引按 0.5
            return switch (idx) {
                case 1 -> 0.0;
                case 2 -> 0.5;
                case 3 -> 1.0;
                default -> 0.5;
            };
        }
        // dominant：4/5 → 0/1.0
        return switch (idx) {
            case 4 -> 0.0;
            case 5 -> 1.0;
            default -> 0.5;
        };
    }

    /**
     * 一个位点的表现型（两个等位基因对 → 0~1）。
     * <ul>
     * <li>additive：两等位基因效应均值；</li>
     * <li>dominant：显性等位基因（效应 1.0 者）决定表现型——两者任一为显性即 1.0，否则 0.0。</li>
     * </ul>
     *
     * @param locusId 位点 ID
     *
     * @param a 等位基因 1
     *
     * @param b 等位基因 2
     *
     * @return 表现型 0~1
     */
    public static double locusPhenotype(String locusId, String a, String b) {
        LocusDef def = DEFS.get(locusId);
        if (def == null) {
            return 0.5;
        }
        if (EFFECT_DOMINANT.equals(def.effect())) {
            double ea = alleleEffect(locusId, a);
            double eb = alleleEffect(locusId, b);
            return Math.max(ea, eb);
        }
        return (alleleEffect(locusId, a) + alleleEffect(locusId, b)) / 2.0;
    }

    /**
     * OCEAN 维度码 → derived_traits 键。
     *
     * @param dimension 维度码（OPENNESS…）
     *
     * @return 键（OCEAN_OPENNESS…）；未知返回 null
     */
    public static String oceanKeyOf(String dimension) {
        return switch (dimension == null ? "" : dimension) {
            case DIM_OPENNESS -> KEY_OCEAN_OPENNESS;
            case DIM_CONSCIENTIOUSNESS -> KEY_OCEAN_CONSCIENTIOUSNESS;
            case DIM_EXTRAVERSION -> KEY_OCEAN_EXTRAVERSION;
            case DIM_AGREEABLENESS -> KEY_OCEAN_AGREEABLENESS;
            case DIM_NEUROTICISM -> KEY_OCEAN_NEUROTICISM;
            default -> null;
        };
    }

    /** 全部 OCEAN derived_traits 键（遗传度框架遍历用）。 */
    public static List<String> oceanKeys() {
        return List.of(KEY_OCEAN_OPENNESS, KEY_OCEAN_CONSCIENTIOUSNESS, KEY_OCEAN_EXTRAVERSION,
                KEY_OCEAN_AGREEABLENESS, KEY_OCEAN_NEUROTICISM);
    }
}
