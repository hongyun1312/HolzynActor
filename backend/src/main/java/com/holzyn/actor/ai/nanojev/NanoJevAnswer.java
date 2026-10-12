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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NanoJev 单个问题的判定结果（完整概率分布 + 便捷字段）。
 * <p>官方响应 每个 answer 都返回完整概率分布，
 * 并按题型附便捷字段：</p>
 * <ul>
 * <li>choice → {@code choice}/{@code value} = 概率最大项（分布采样见 {@link NanoJevSampler}）；</li>
 * <li>boolean → {@code p_true} 与阈值化 {@code value}；</li>
 * <li>score → {@code score = Σ(i × p_i)} 期望值、{@code level} = argmax 档位。</li>
 * </ul>
 * <p><b>⚠️ 归一化分布 ≠ 已标定概率</b>（官方声明）：概率只用于排序/采样，不作置信度硬阈值
 * 。</p>
 *
 * @param type 题型（choice/boolean/score）
 *
 * @param probabilities 完整概率分布（候选/档位 → 概率；保持服务端返回序）
 *
 * @param choice choice 的概率最大项（choice 题型；可空）
 *
 * @param pTrue boolean 为真概率（boolean 题型；可空）
 *
 * @param score score 期望值 Σ(i×p_i)（score 题型；可空）
 *
 * @param level score 的 argmax 档位索引（score 题型；可空）
 *
 * @param value 原始值字段（choice=字符串 / boolean=Boolean / score=期望值 Double）
 */
public record NanoJevAnswer(String type, Map<String, Double> probabilities,
                            String choice, Double pTrue, Double score, Integer level, Object value) {

    /** 空答案（解析失败时占位；调用方按零阻断处理）。 */
    public static NanoJevAnswer empty(String type) {
        return new NanoJevAnswer(type, new LinkedHashMap<>(), null, null, null, null, null);
    }

    /** 分布是否归一（和 ≈1；抽样人工核验用）。 */
    public boolean normalized() {
        if (probabilities == null || probabilities.isEmpty()) {
            return false;
        }
        double sum = 0;
        for (double p : probabilities.values()) {
            sum += p;
        }
        return Math.abs(sum - 1.0) < 0.01;
    }

    /** 取指定候选概率（缺省 0）。 */
    public double probabilityOf(String key) {
        if (probabilities == null) {
            return 0;
        }
        Double p = probabilities.get(key);
        return p == null ? 0 : p;
    }
}
