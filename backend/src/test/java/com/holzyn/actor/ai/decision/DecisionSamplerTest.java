package com.holzyn.actor.ai.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1 接入基建单测：分布采样器（N0 决策#5：按完整概率分布采样，保留 argmax 确定性开关）。
 *
 * <p>测试纪律：纯 JUnit5；分布级断言（argmax 项/统计占比区间）而非点断言；
 * sample 模式断言用固定种子 + 大样本统计（不依赖单次随机结果）。</p>
 *
 * <p>所属模块：ai/decision（N 轨道 N1）</p>
 */
class DecisionSamplerTest {

    @Test
    @DisplayName("argmax 模式确定性：取概率最大项，种子无关")
    void argmaxIsDeterministic() {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("rest", 0.145);
        probs.put("chat", 0.376);
        probs.put("work", 0.479);
        assertEquals("work", DecisionSampler.choose(probs, "argmax", new Random(1)));
        assertEquals("work", DecisionSampler.choose(probs, "argmax", new Random(999)));
        // 相等概率取首个（与响应序一致）
        Map<String, Double> tie = new LinkedHashMap<>();
        tie.put("a", 0.5);
        tie.put("b", 0.5);
        assertEquals("a", DecisionSampler.choose(tie, "argmax", new Random(1)));
    }

    @Test
    @DisplayName("sample 模式按分布加权：大样本统计占比符合概率（固定种子可复现）")
    void sampleFollowsDistribution() {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("a", 0.9);
        probs.put("b", 0.1);
        Random rng = new Random(42);
        int a = 0;
        for (int i = 0; i < 2000; i++) {
            if ("a".equals(DecisionSampler.choose(probs, "sample", rng))) {
                a++;
            }
        }
        assertTrue(a > 1600, "a 占比应 ≈90%（2000 次样本 a=" + a + "）");
        assertTrue(a < 1900, "b 也有机会被选中（真实优先：低权重项不消失，a=" + a + "）");
        // 同种子同模式同分布 → 同一次采样可复现
        Random r1 = new Random(7);
        Random r2 = new Random(7);
        boolean same = true;
        for (int i = 0; i < 50; i++) {
            if (!DecisionSampler.choose(probs, "sample", r1)
                    .equals(DecisionSampler.choose(probs, "sample", r2))) {
                same = false;
                break;
            }
        }
        assertTrue(same, "固定种子下采样序列可复现");
    }

    @Test
    @DisplayName("boolean 判定：argmax 阈值化 / sample 按 p_true 概率")
    void booleanDecide() {
        assertTrue(DecisionSampler.decide(0.8, "argmax", null), "argmax: p>=0.5 → true");
        assertTrue(!DecisionSampler.decide(0.4, "argmax", null), "argmax: p<0.5 → false");
        Random rng = new Random(5);
        int trues = 0;
        for (int i = 0; i < 2000; i++) {
            if (DecisionSampler.decide(0.9, "sample", rng)) {
                trues++;
            }
        }
        assertTrue(trues > 1600, "p_true=0.9 时 sample 多数为 true（trues=" + trues + "）");
        // 越界钳制
        assertTrue(DecisionSampler.decide(1.5, "argmax", null));
        assertTrue(!DecisionSampler.decide(-0.5, "argmax", null));
    }

    @Test
    @DisplayName("score 档位：argmax 取 level / sample 可选中低档位（反常行为合法）")
    void scoreLevel() {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("0", 0.1);
        probs.put("1", 0.9);
        assertEquals(1, DecisionSampler.level(probs, "argmax", new Random(1)));
        Random rng = new Random(11);
        int ones = 0;
        for (int i = 0; i < 2000; i++) {
            if (DecisionSampler.level(probs, "sample", rng) == 1) {
                ones++;
            }
        }
        assertTrue(ones > 1600, "档位 1 占比应 ≈90%（ones=" + ones + "）");
        assertEquals(-1, DecisionSampler.level(Map.of(), "argmax", new Random(1)), "空分布返回 -1");
    }

    @Test
    @DisplayName("熵：均匀分布最大 / 单峰趋零 / 空分布为 0")
    void entropyBounds() {
        Map<String, Double> uniform = new LinkedHashMap<>();
        uniform.put("a", 0.5);
        uniform.put("b", 0.5);
        assertTrue(Math.abs(DecisionSampler.entropy(uniform) - 1.0) < 1e-9, "均匀二元分布熵=1 bit");
        Map<String, Double> sharp = new LinkedHashMap<>();
        sharp.put("a", 0.999);
        sharp.put("b", 0.001);
        assertTrue(DecisionSampler.entropy(sharp) < 0.02, "单峰分布熵趋零");
        assertEquals(0, DecisionSampler.entropy(Map.of()), "空分布熵=0");
    }

    @Test
    @DisplayName("空分布 choose 返回 null")
    void emptyChoose() {
        assertNull(DecisionSampler.choose(Map.of(), "sample", new Random(1)));
        assertNull(DecisionSampler.choose(null, "argmax", new Random(1)));
    }
}
