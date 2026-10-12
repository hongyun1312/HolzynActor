package com.holzyn.actor.domain.growth.genetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 孟德尔重组 + 遗传度框架单元测试（O6 G4 验收①：孟德尔重组统计正确）。
 *
 * <p>统计口径：① 大样本下子代均值 ≈ 群体均值 0.5；② 子代与 midparent 的回归系数 ≈ h（0.7）；
 * ③ 每个位点恰 2 个等位基因且来自父母；④ 遗传度框架下子代不会越过 [0,1]；⑤ 程序化推断可复现。</p>
 */
class MendelianRecombinatorTest {

    private static final Random RND = new Random(42L);

    @Test
    @DisplayName("验收①：位点表现型计算——additive 取均值、dominant 取显性")
    void locusPhenotype() {
        // additive：A1/A3 = (0+1)/2 = 0.5；A1/A1 = 0；A3/A3 = 1
        assertEquals(0.5, GeneticLoci.locusPhenotype("OCEAN_O_1", "A1", "A3"), 0.0001);
        assertEquals(0.0, GeneticLoci.locusPhenotype("OCEAN_O_1", "A1", "A1"), 0.0001);
        assertEquals(1.0, GeneticLoci.locusPhenotype("OCEAN_O_1", "A3", "A3"), 0.0001);
        // dominant：A4/A5（有显性）→ 1；A4/A4 → 0
        assertEquals(1.0, GeneticLoci.locusPhenotype("OCEAN_O_2", "A4", "A5"), 0.0001);
        assertEquals(0.0, GeneticLoci.locusPhenotype("OCEAN_O_2", "A4", "A4"), 0.0001);
        // 未知位点/未知等位基因 → 群体均值 0.5（零阻断）
        assertEquals(0.5, GeneticLoci.locusPhenotype("UNKNOWN", "X1", "X2"), 0.0001);
        assertEquals(0.5, GeneticLoci.alleleEffect("OCEAN_O_1", "Z9"), 0.0001);
    }

    @Test
    @DisplayName("验收①：孟德尔重组——每个位点恰 2 个等位基因，且分别来自父母双方")
    void recombineAllelesFromBothParents() {
        GenomeData father = MendelianRecombinator.randomGenome(RND);
        GenomeData mother = MendelianRecombinator.randomGenome(RND);
        for (int i = 0; i < 200; i++) {
            GenomeData child = MendelianRecombinator.recombine(father, mother, 0.0, RND);
            assertEquals(15, child.getLoci().size(), "15 位点冻结");
            for (GenomeData.Locus locus : child.getLoci()) {
                assertEquals(2, locus.getAlleles().size(), "位点恒为二倍体");
                // 每个等位基因必须来自父母任一方（无突变时）
                GeneticLoci.LocusDef def = GeneticLoci.of(locus.getLocusId());
                assertTrue(def.alleles().contains(locus.getAlleles().get(0)));
                assertTrue(def.alleles().contains(locus.getAlleles().get(1)));
            }
        }
    }

    @Test
    @DisplayName("验收①：遗传度框架——极端父母经 childOcean 映射后向均值回归")
    void heritabilityFrameworkRegression() {
        // 极端父母（OCEAN 全取位点最高效应等位基因）：位点派生 ≈1.0（midparent），
        // 经 childOcean（h=0.7）映射后子代表现应回归向 0.5（期望 0.85）
        GenomeData extreme = new GenomeData();
        for (GeneticLoci.LocusDef def : GeneticLoci.all().values()) {
            String high = def.alleles().get(def.alleles().size() - 1); // 末位等位基因 = 最高效应
            extreme.getLoci().add(GenomeData.childLocus(def.locusId(), high, high));
        }
        extreme.setDerivedTraits(MendelianRecombinator.computeTraits(extreme));
        double h = 0.7;
        double sigma = 0.05;
        double sum = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            GenomeData child = MendelianRecombinator.recombine(extreme, extreme, 0.0, RND);
            double midparent = child.getDerivedTraits().getOrDefault(GeneticLoci.KEY_OCEAN_OPENNESS, 0.5);
            // 位点派生 ≈ 1.0（极端父母）；表现型经遗传度框架映射 → 回归向均值
            sum += MendelianRecombinator.childOcean(midparent, h, sigma, RND);
        }
        double mean = sum / n;
        assertTrue(mean < 0.9, "极端父母子代向均值回归：实测均值 " + mean);
        assertTrue(mean > 0.75, "遗传度保持：实测均值 " + mean);
        // 群体均值父母 → 子代均值 ≈ 0.5
        GenomeData mid = MendelianRecombinator.randomGenome(RND);
        GenomeData mid2 = MendelianRecombinator.randomGenome(RND);
        double sum2 = 0;
        for (int i = 0; i < 2000; i++) {
            GenomeData child = MendelianRecombinator.recombine(mid, mid2, 0.0, RND);
            double mp = child.getDerivedTraits().getOrDefault(GeneticLoci.KEY_OCEAN_NEUROTICISM, 0.5);
            sum2 += MendelianRecombinator.childOcean(mp, h, sigma, RND);
        }
        assertTrue(Math.abs(sum2 / 2000 - 0.5) < 0.05, "群体均值父母 → 子代均值 ≈0.5");
    }

    @Test
    @DisplayName("验收①：childOcean 遗传度映射——child = 0.5 + h×(midparent−0.5) + 噪声，恒在 [0,1]")
    void childOceanMapping() {
        double h = 0.7;
        double sigma = 0.05;
        for (double midparent : new double[]{0.0, 0.2, 0.5, 0.8, 1.0}) {
            double sum = 0;
            for (int i = 0; i < 1000; i++) {
                double v = MendelianRecombinator.childOcean(midparent, h, sigma, RND);
                assertTrue(v >= 0.0 && v <= 1.0, "钳制 [0,1]：" + v);
                sum += v;
            }
            double mean = sum / 1000;
            double expected = 0.5 + h * (midparent - 0.5);
            assertTrue(Math.abs(mean - expected) < 0.02, "期望 " + expected + " 实测 " + mean);
        }
    }

    @Test
    @DisplayName("验收①：从头突变率区间内生效——突变只换位点内等位基因")
    void mutationStaysWithinAlleleSet() {
        Random rnd = new Random(7L); // 独立随机源，不受共享 RND 消耗影响
        GenomeData a = MendelianRecombinator.randomGenome(rnd);
        GenomeData b = MendelianRecombinator.randomGenome(rnd);
        int mutated = 0;
        int total = 0;
        for (int i = 0; i < 200; i++) {
            GenomeData child = MendelianRecombinator.recombine(a, b, 0.005, rnd);
            for (GenomeData.Locus locus : child.getLoci()) {
                total++;
                GeneticLoci.LocusDef def = GeneticLoci.of(locus.getLocusId());
                for (String allele : locus.getAlleles()) {
                    assertTrue(def.alleles().contains(allele), "突变不越出位点等位基因集：" + allele);
                }
                GenomeData.Locus pa = a.getLoci().stream().filter(l -> l.getLocusId().equals(locus.getLocusId()))
                        .findFirst().orElseThrow();
                GenomeData.Locus pb = b.getLoci().stream().filter(l -> l.getLocusId().equals(locus.getLocusId()))
                        .findFirst().orElseThrow();
                boolean fromParents = pa.getAlleles().contains(locus.getAlleles().get(0))
                        || pb.getAlleles().contains(locus.getAlleles().get(0));
                if (!fromParents) {
                    mutated++;
                }
            }
        }
        // 200 次 × 15 位点 × 0.005 ≈ 15 次突变（宽松区间 1~60）
        assertTrue(mutated >= 1 && mutated <= 60, "突变计数 " + mutated + " 落在 0.1%~0.5% 概率期望内");
    }

    @Test
    @DisplayName("Q4：程序化基因推断——按既有 OCEAN 反推位点，再算 derived 与目标接近")
    void inferGenomeMatchesTarget() {
        Map<String, Double> ocean = new LinkedHashMap<>();
        ocean.put(GeneticLoci.KEY_OCEAN_OPENNESS, 0.9);
        ocean.put(GeneticLoci.KEY_OCEAN_CONSCIENTIOUSNESS, 0.2);
        ocean.put(GeneticLoci.KEY_OCEAN_EXTRAVERSION, 0.6);
        ocean.put(GeneticLoci.KEY_OCEAN_AGREEABLENESS, 0.75);
        ocean.put(GeneticLoci.KEY_OCEAN_NEUROTICISM, 0.3);
        GenomeData inferred = MendelianRecombinator.inferGenome(ocean, RND);
        // 推断位点 → 派生特征应与目标同向（误差 < 0.15）
        for (String key : GeneticLoci.oceanKeys()) {
            double target = ocean.get(key);
            double derived = inferred.getDerivedTraits().getOrDefault(key, 0.5);
            assertTrue(Math.abs(target - derived) < 0.15,
                    key + " 推断 " + derived + " 应接近目标 " + target);
        }
    }
}
