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

package com.holzyn.actor.domain.memory.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆文本去重静态工具。
 * <p>职责：Dice 二元组重叠度 / 新事实与已有记忆重复判定 / 子句切分——供 L2 写入（AgentMemoryService）
 * 与 L1 巩固提升（MemoryWorkingService）共用，避免两处重复实现导致算法漂移。</p>
 * <p>算法来源：AgentMemoryService 既有实现（去重 / relevance 降级），行为不变。</p>
 */
public final class MemoryDedup {

    private MemoryDedup() {
    }

    /**
     * 文本重叠度（Dice 二元组系数 0~1）：用于服务端二次去重与检索 relevance 降级分。
     *
     * @param a 文本 a
     *
     * @param b 文本 b
     *
     * @return 重叠度（空文本返回 0）
     */
    public static double overlapScore(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return 0;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String bg : bigramsA) {
            if (bigramsB.contains(bg)) {
                common++;
            }
        }
        return 2.0 * common / (bigramsA.size() + bigramsB.size());
    }

    /**
     * 新事实与已有记忆是否重复：任一条已有记忆达到阈值即判定重复。
     * <p>去重策略：① 整条文本重叠（Dice 二元组）；② 子句命中率——新事实按标点切分子句，
     * 若某子句的核心二元组大部分（≥70% 且至少 3 个）已出现在某条已有记忆中，判重。</p>
     *
     * @param newContent 新事实内容
     *
     * @param existing 已有记忆内容列表
     *
     * @param threshold 整条重叠阈值（0~1）
     *
     * @return true 表示重复应丢弃
     */
    public static boolean isDuplicate(String newContent, List<String> existing, double threshold) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        for (String e : existing) {
            if (overlapScore(newContent, e) >= threshold) {
                return true;
            }
        }
        for (String clause : splitClauses(newContent)) {
            Set<String> clauseBigrams = bigrams(clause);
            if (clauseBigrams.size() < 3) {
                continue;
            }
            for (String e : existing) {
                Set<String> memBigrams = bigrams(e);
                int common = 0;
                for (String bg : clauseBigrams) {
                    if (memBigrams.contains(bg)) {
                        common++;
                    }
                }
                double hitRate = (double) common / clauseBigrams.size();
                if (common >= 3 && hitRate >= 0.7) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将文本切分为子句（按中文/英文标点：。，！？；英文句号逗号分号）。
     *
     * @param text 文本
     *
     * @return 子句列表（空文本返回空列表）
     */
    public static List<String> splitClauses(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if ("。；，、！？.!;".indexOf(c) >= 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /**
     * 提取文本的二元组集合（中文无空格分词，二元组近似语义重叠）。
     */
    private static Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        String t = text.trim();
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }
}
