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
import java.util.Random;

/**
 * 孟德尔重组 + 遗传度框架算法。
 * <p><b></b>：在参考文档 15 位点孟德尔重组的骨架上，把「位点效应 →
 * OCEAN」的映射升级为<b>遗传度框架</b>（双生子研究人格遗传度 ≈40-50%）：</p>
 * <pre>
 * child_dim = 群体均值(0.5) + h × (midparent_dim − 0.5) + N(0, σ)
 * </pre>
 * <ul>
 * <li>{@code h} = 遗传度系数（默认 0.7 ⇒ h²≈0.49，遗传度解释约一半的表型方差）；</li>
 * <li>{@code midparent_dim} = 父母双方位点效应值的均值（「向均值回归」：极端父母的孩子
 * 向群体均值回归，不再极端）；</li>
 * <li>{@code N(0, σ)} = 环境/表达噪声（σ=0.05，对齐参考文档 §4.2 高斯噪声）。</li>
 * </ul>
 * <p><b>流程</b>：</p>
 * <ol>
 * <li>{@link #recombine}：每个位点从父随机取 1 个等位基因 + 从母随机取 1 个等位基因 → 子代位点；
 * 按 {@code mutationRate} 对每个位点以 0.1%~0.5% 概率从头突变（随机换一个新等位基因）；</li>
 * <li>{@link #computeTraits}：位点表现型 → derived_traits（OCEAN 五维 + TEMPO/LEARN/APTITUDE 五维）；</li>
 * <li>{@link #childOcean}：遗传度框架映射（midparent → 子代 OCEAN）。</li>
 * </ol>
 * <p><b>统计性质（单测断言）</b>：① 大样本下子代均值 ≈ 群体均值 0.5；② 子代与 midparent 的
 * 回归系数 ≈ h；③ 父母双方各贡献 1 个等位基因（位点等位基因对恒 2 个）；④ 突变率在配置区间内。</p>
 */
public final class MendelianRecombinator {

    /** 群体均值（位点效应以 0.5 为中心）。 */
    public static final double POPULATION_MEAN = 0.5;

    private MendelianRecombinator() {
    }

    /**
     * 孟德尔重组：父母各出一个等位基因 → 子代位点对，并叠加从头突变。
     *
     * @param parentA 父方基因组（可空=单亲/孤儿时按随机初始化回退）
     *
     * @param parentB 母方基因组（可空同上）
     *
     * @param mutationRate 单次从头突变率（0~1；调用方取配置区间随机值）
     *
     * @param random 随机源
     *
     * @return 子代基因组（含全部 15 位点 + derived_traits）
     */
    public static GenomeData recombine(GenomeData parentA, GenomeData parentB, double mutationRate, Random random) {
        GenomeData child = new GenomeData();
        for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
            String a = pickAllele(def, parentA, random);
            String b = pickAllele(def, parentB, random);
            // 从头突变：以 mutationRate 概率把其中一个等位基因随机换成位点内的新等位基因
            if (random.nextDouble() < mutationRate) {
                if (random.nextBoolean()) {
                    a = randomAllele(def, random);
                } else {
                    b = randomAllele(def, random);
                }
            }
            child.getLoci().add(GenomeData.childLocus(def.locusId(), a, b));
        }
        child.setDerivedTraits(computeTraits(child));
        return child;
    }

    /** 从父母基因组取该位点的一个等位基因（父母缺该位点时按定义随机生成——孤儿兜底）。 */
    private static String pickAllele(GeneticLoci.LocusDef def, GenomeData parent, Random random) {
        if (parent == null) {
            return randomAllele(def, random);
        }
        GenomeData.Locus locus = parent.getLoci().stream()
                .filter(l -> l != null && def.locusId().equals(l.getLocusId()))
                .findFirst()
                .orElse(null);
        if (locus == null || locus.getAlleles() == null || locus.getAlleles().isEmpty()) {
            return randomAllele(def, random);
        }
        return locus.getAlleles().get(random.nextInt(locus.getAlleles().size()));
    }

    /** 位点定义内随机一个等位基因。 */
    private static String randomAllele(GeneticLoci.LocusDef def, Random random) {
        return def.alleles().get(random.nextInt(def.alleles().size()));
    }

    /**
     * 由位点表现型计算派生特征（derived_traits）。
     * <p>OCEAN 每维 = 0.5×(additive 位点表现型) + 0.5×(dominant 位点表现型)；
     * TEMPO/LEARN/APTITUDE = 各自单座位 additive 表现型。</p>
     *
     * @param genome 基因组（含 loci）
     *
     * @return derived_traits（10 键：OCEAN 五维 + TEMPO/LEARN/APTITUDE 五维）
     */
    public static Map<String, Double> computeTraits(GenomeData genome) {
        Map<String, Double> traits = new LinkedHashMap<>();
        // OCEAN 每维两座位（additive + dominant）等权平均
        for (String dim : List.of("OPENNESS", "CONSCIENTIOUSNESS", "EXTRAVERSION", "AGREEABLENESS", "NEUROTICISM")) {
            double sum = 0;
            int count = 0;
            for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
                if (!dim.equals(def.dimension())) {
                    continue;
                }
                GenomeData.Locus locus = findLocus(genome, def.locusId());
                sum += locusPhenotypeOf(locus);
                count++;
            }
            double dimValue = count == 0 ? POPULATION_MEAN : sum / count;
            traits.put(GeneticLoci.oceanKeyOf(dim), round(dimValue));
        }
        // TEMPO/LEARN/APTITUDE 独立键（单座位维度）
        for (String locusId : List.of("TEMPO_AROUSAL", "TEMPO_RECOVERY", "LEARN_SOCIAL", "LEARN_HABIT",
                "APTITUDE_GENERAL")) {
            GenomeData.Locus locus = findLocus(genome, locusId);
            traits.put(switch (locusId) {
                case "TEMPO_AROUSAL" -> GeneticLoci.KEY_TEMPO_AROUSAL;
                case "TEMPO_RECOVERY" -> GeneticLoci.KEY_TEMPO_RECOVERY;
                case "LEARN_SOCIAL" -> GeneticLoci.KEY_LEARN_SOCIAL;
                case "LEARN_HABIT" -> GeneticLoci.KEY_LEARN_HABIT;
                default -> GeneticLoci.KEY_APTITUDE_GENERAL;
            }, round(locusPhenotypeOf(locus)));
        }
        return traits;
    }

    /** 按位点 ID 取基因组中的位点（缺失返回 null）。 */
    private static GenomeData.Locus findLocus(GenomeData genome, String locusId) {
        if (genome == null || genome.getLoci() == null) {
            return null;
        }
        return genome.getLoci().stream()
                .filter(l -> l != null && locusId.equals(l.getLocusId()))
                .findFirst().orElse(null);
    }

    /** 位点对象的表现型（缺位点/异常 → 群体均值 0.5）。 */
    private static double locusPhenotypeOf(GenomeData.Locus locus) {
        if (locus == null || locus.getAlleles() == null || locus.getAlleles().size() < 2) {
            return POPULATION_MEAN;
        }
        return GeneticLoci.locusPhenotype(locus.getLocusId(), locus.getAlleles().get(0), locus.getAlleles().get(1));
    }

    /**
     * 遗传度框架映射：midparent OCEAN → 子代 OCEAN。
     * <pre>child = 0.5 + h × (midparent − 0.5) + N(0, σ)，再钳到 [0,1]</pre>
     *
     * @param midparent 父母双方 OCEAN 均值（0~1）
     *
     * @param h 遗传度系数（0~1）
     *
     * @param sigma 环境噪声 σ
     *
     * @param random 随机源
     *
     * @return 子代 OCEAN（0~1）
     */
    public static double childOcean(double midparent, double h, double sigma, Random random) {
        double noise = random.nextGaussian() * sigma;
        double v = POPULATION_MEAN + h * (midparent - POPULATION_MEAN) + noise;
        return clamp01(v);
    }

    /**
     * 父母的 midparent 向量（OCEAN 五维的位点效应均值）。
     *
     * @param parentA 父方基因组
     *
     * @param parentB 母方基因组
     *
     * @return OCEAN 五维 → midparent 值（父母缺 derived_traits 时按群体均值 0.5 兜底）
     */
    public static Map<String, Double> midparentOcean(GenomeData parentA, GenomeData parentB) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : GeneticLoci.oceanKeys()) {
            double a = traitOf(parentA, key);
            double b = traitOf(parentB, key);
            out.put(key, (a + b) / 2.0);
        }
        return out;
    }

    /** 取基因组某 derived_trait（缺失回退群体均值）。 */
    private static double traitOf(GenomeData genome, String key) {
        if (genome == null || genome.getDerivedTraits() == null) {
            return POPULATION_MEAN;
        }
        Double v = genome.getDerivedTraits().get(key);
        return v == null ? POPULATION_MEAN : v;
    }

    /**
     * 随机基因组（新建世界角色/孤儿：随机初始化全部 15 位点 + derived_traits）。
     *
     * @param random 随机源
     *
     * @return 基因组
     */
    public static GenomeData randomGenome(Random random) {
        GenomeData g = new GenomeData();
        for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
            g.getLoci().add(GenomeData.childLocus(def.locusId(), randomAllele(def, random), randomAllele(def, random)));
        }
        g.setDerivedTraits(computeTraits(g));
        return g;
    }

    /**
     * 程序化基因推断：按其既有 OCEAN 反向拟合位点——
     * 每维两座位直接以「该维目标值」反推等位基因（additive 座取目标×2 摊到两等位、dominant 座按
     * 目标是否 ≥0.5 取显性/隐性对），保证"任何角色都可成为父母"，且子代从父母真实人格遗传
     * （而非凭空随机）。
     *
     * @param ocean OCEAN 五维（0~1，可含 null——缺失维按 0.5）
     *
     * @param random 随机源
     *
     * @return 拟合基因组
     */
    public static GenomeData inferGenome(Map<String, Double> ocean, Random random) {
        // 显性座表现型由目标完全决定（≥0.5 → 1.0，否则 0.0）——先预计算，供加性座补偿
        // （加性座在 DEFS 中先于显性座出现，逐座处理时显性座结果尚不可知，故先扫一遍）。
        Map<String, Double> dominantPheno = new LinkedHashMap<>();
        for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
            if (GeneticLoci.EFFECT_DOMINANT.equals(def.effect())) {
                String key = GeneticLoci.oceanKeyOf(def.dimension());
                double target = key == null ? POPULATION_MEAN : clamp01(ocean.getOrDefault(key, POPULATION_MEAN));
                dominantPheno.put(def.dimension(), target >= 0.5 ? 1.0 : 0.0);
            }
        }
        GenomeData g = new GenomeData();
        for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
            String key = GeneticLoci.oceanKeyOf(def.dimension());
            double target = key == null ? POPULATION_MEAN : clamp01(ocean.getOrDefault(key, POPULATION_MEAN));
            // 加性座补偿显性座已定的表现型：add 目标 = 2×维度目标 − dom 表现型
            double adjusted = target;
            if (GeneticLoci.EFFECT_ADDITIVE.equals(def.effect())) {
                double d = dominantPheno.getOrDefault(def.dimension(), POPULATION_MEAN);
                adjusted = clamp01(2.0 * target - d);
            }
            g.getLoci().add(inferLocus(def, adjusted, random));
        }
        g.setDerivedTraits(computeTraits(g));
        return g;
    }

    /** 按目标值拟合单个位点的等位基因对（目标值 → 最接近表现型的等位基因对，并列随机）。 */
    private static GenomeData.Locus inferLocus(GeneticLoci.LocusDef def, double target, Random random) {
        if (GeneticLoci.EFFECT_DOMINANT.equals(def.effect())) {
            // 显性座：目标 ≥0.5 → 表现型 1.0；否则 0.0（显隐基因型混合不影响表现型）
            if (target >= 0.5) {
                return random.nextDouble() < 0.5
                        ? GenomeData.childLocus(def.locusId(), def.alleles().get(1), def.alleles().get(1))
                        : GenomeData.childLocus(def.locusId(), def.alleles().get(1), def.alleles().get(0));
            }
            return random.nextDouble() < 0.2
                    ? GenomeData.childLocus(def.locusId(), def.alleles().get(1), def.alleles().get(0))
                    : GenomeData.childLocus(def.locusId(), def.alleles().get(0), def.alleles().get(0));
        }
        // 加性座（三等位 0/0.5/1）：枚举全部等位基因对，选表现型最接近 target 的一对
        double best = Double.MAX_VALUE;
        List<GenomeData.Locus> bestPairs = new java.util.ArrayList<>();
        for (String a : def.alleles()) {
            for (String b : def.alleles()) {
                double p = GeneticLoci.locusPhenotype(def.locusId(), a, b);
                double d = Math.abs(p - target);
                if (d < best) {
                    best = d;
                    bestPairs.clear();
                    bestPairs.add(GenomeData.childLocus(def.locusId(), a, b));
                } else if (d == best) {
                    bestPairs.add(GenomeData.childLocus(def.locusId(), a, b));
                }
            }
        }
        return bestPairs.get(random.nextInt(bestPairs.size()));
    }

    /** 四位小数舍入（derived_traits 存储精度，稳定断言用）。 */
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
