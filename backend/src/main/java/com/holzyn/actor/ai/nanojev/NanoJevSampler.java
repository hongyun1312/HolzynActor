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

package com.holzyn.actor.ai.nanojev;

import java.util.Map;
import java.util.Random;

/**
 * NanoJev 判定结果的采样器。
 * <p><b>为什么按分布采样（设计《N 轨道开发路径》§4.1）</b>：NanoJev 返回的显式概率分布承载
 * 「倾向强度」语义——按分布采样让低权重候选也有机会被选中（真人会做反常的事），单次行为不重复、
 * 长期统计符合人格权重，这是「机制层」的真实性；argmax 只用于测试与回放（确定性）。</p>
 * <p><b>⚠️ 概率未标定</b>（官方声明）：归一化分布 ≠ 已标定概率——本采样器只做排序/采样，
 * 不做置信度硬阈值。</p>
 * <p>确定性保证：同一分布 + 同一 {@code mode} + 同一 {@code seed} → 同一次采样结果可复现
 * （NanoJev 本身重复调用概率偏差=0，随机性只来自调用方采样动作）。</p>
 */
public final class NanoJevSampler {

    private NanoJevSampler() {
    }

    /**
     * 从分布中选一个候选/档位（choice 与 score 通用；按当前模式）。
     *
     * @param probabilities 完整分布（候选 → 概率；空=返回 null）
     *
     * @param mode 采样模式：sample（按分布加权随机）/ argmax（取概率最大项，确定性）
     *
     * @param rng 随机源（可注入固定种子）
     *
     * @return 选中的候选键/档位索引字符串（无分布返回 null）
     */
    public static String choose(Map<String, Double> probabilities, String mode, Random rng) {
        if (probabilities == null || probabilities.isEmpty()) {
            return null;
        }
        if (!"sample".equalsIgnoreCase(mode)) {
            // argmax：概率最大项（相等时取首个，与响应序一致，确定性）
            String best = null;
            double bestP = -1;
            for (Map.Entry<String, Double> e : probabilities.entrySet()) {
                double p = e.getValue() == null ? 0 : e.getValue();
                if (p > bestP) {
                    bestP = p;
                    best = e.getKey();
                }
            }
            return best;
        }
        // sample：归一化后加权随机（防御：概率和不为 1 时先归一）
        double total = 0;
        for (double p : probabilities.values()) {
            total += p;
        }
        if (total <= 0) {
            return probabilities.keySet().iterator().next();
        }
        double r = (rng == null ? new Random() : rng).nextDouble() * total;
        double acc = 0;
        for (Map.Entry<String, Double> e : probabilities.entrySet()) {
            acc += e.getValue() == null ? 0 : e.getValue();
            if (r <= acc) {
                return e.getKey();
            }
        }
        // 浮点尾部误差兜底：返回最后一项
        return probabilities.keySet().stream().reduce((a, b) -> b).orElse(null);
    }

    /**
     * boolean 判定采样（命题为真的概率 p_true）。
     *
     * @param pTrue 为真概率（0~1；越界钳制）
     *
     * @param mode 采样模式
     *
     * @param rng 随机源
     */
    public static boolean decide(double pTrue, String mode, Random rng) {
        double p = Math.max(0, Math.min(1, pTrue));
        if (!"sample".equalsIgnoreCase(mode)) {
            return p >= 0.5;
        }
        return (rng == null ? new Random() : rng).nextDouble() < p;
    }

    /**
     * score 档位采样（返回档位索引；与 {@link NanoJevAnswer#level} 的 argmax 不同，
     * sample 模式下低档位也有机会被选中）。
     *
     * @param probabilities 档位 → 概率（键为索引字符串）
     *
     * @param mode 采样模式
     *
     * @param rng 随机源
     *
     * @return 档位索引（无分布返回 -1）
     */
    public static int level(Map<String, Double> probabilities, String mode, Random rng) {
        String chosen = choose(probabilities, mode, rng);
        if (chosen == null) {
            return -1;
        }
        try {
            return Integer.parseInt(chosen);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 分布熵（香农熵，bit）：衡量分布尖锐度——采样回放/调试页展示「判定是否有倾向」；
     * 置信度升级候选机制（分布不尖锐 → 升 Qwen）的观测指标。
     *
     * @param probabilities 完整分布
     *
     * @return 熵值（0~log2(n)；无分布返回 0）
     */
    public static double entropy(Map<String, Double> probabilities) {
        if (probabilities == null || probabilities.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (double p : probabilities.values()) {
            total += p;
        }
        double h = 0;
        for (double p : probabilities.values()) {
            double q = total > 0 ? p / total : 0;
            if (q > 0) {
                h -= q * (Math.log(q) / Math.log(2));
            }
        }
        return h;
    }
}
