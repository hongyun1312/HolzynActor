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

package com.holzyn.actor.domain.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索话题词提取器（知识检索质量深化， _04）。
 * <p>纯逻辑静态工具（可单测，无 Spring 依赖）：为多路召回提供「话题词」——
 * ① 从知识文档（标题 + 正文）构建项目话题词索引（2~4 字连续窗口高频词，标题窗口加权）；
 * ② 从检索 query 中提取命中的话题词（query 包含匹配、最长优先、去包含）。
 * 话题词独立向量检索可补全「长句 query 语义稀释」下实体/主题词的精确命中（如
 * query=「青禾村有哪些势力」→ 话题词「势力」→ 精确命中《世界设定·势力》文档）。</p>
 */
public final class QueryTopicExtractor {

    private QueryTopicExtractor() {
    }

    /**
     * 停用词字符（窗口<b>首/尾</b>为停用词即丢弃——中文无空格分词，边界过滤可保实体词
     * （如「云来茶馆」首尾均为实体字符）同时剔除「什么样的」「是什么」类叙述片段）
     */
    static final String STOP_CHARS =
            "的了呢吗啊呀吧么什怎哪谁为是如向在着有和与或而就把将被让对从到往去来出进过个这那它你我他她"
                    + "我们你们他们自己人地还都也要会能可以没不很最再才又只已正在刚等请想问告诉知道几多少来几许";

    /** 标题窗口计数加权（标题是文档主题的高置信来源，如「世界设定·势力」→「势力」优先入索引） */
    static final int TITLE_WEIGHT = 3;

    /** 窗口长度范围 [2, 4]（中文话题词常见 2~4 字；超长窗口噪音大） */
    static final int MIN_WINDOW = 2;
    static final int MAX_WINDOW = 4;

    /** 词频下限（低于该频次不进索引——单次出现多为噪音/叙述词） */
    static final int MIN_FREQ = 2;

    /** 索引词条数上限（词表膨胀保护；排序取高频前 N） */
    static final int MAX_TERMS = 300;

    /**
     * 构建话题词索引：标题 + 正文的 2~4 字高频窗口（标题 × {@link #TITLE_WEIGHT}）。
     * <p><b>保底策略（_04 冒烟驱动）</b>：频次 ≥ {@link #TITLE_WEIGHT} 的词
     * （单次标题出现即达标题权重——「世界设定·势力」→「势力」；或正文高频词「茶馆/沈砚秋」）
     * 无条件入索引（不受 {@link #MAX_TERMS} 淘汰）——否则文档量增大（如人物知识卡逐张入库）
     * 会把世界设定分段等<b>高价值标题锚词</b>挤出 top-N；剩余容量按频次降序补足。</p>
     *
     * @param titles 文档标题列表（可空；标题窗口加权）
     *
     * @param contents 文档正文列表（可空）
     *
     * @return 话题词 → 频次（保底词全入 + 其余按频次降序，上限 {@link #MAX_TERMS}）
     */
    static Map<String, Integer> buildTermIndex(List<String> titles, List<String> contents) {
        Map<String, Integer> freq = new HashMap<>();
        if (titles != null) {
            for (String t : titles) {
                countWindows(t, freq, TITLE_WEIGHT);
            }
        }
        if (contents != null) {
            for (String c : contents) {
                countWindows(c, freq, 1);
            }
        }
        // ① 保底词（频次 ≥ 标题权重）：高置信话题锚（标题词/高频实体词）无条件入索引
        Map<String, Integer> out = new LinkedHashMap<>();
        freq.entrySet().stream()
                .filter(e -> e.getValue() >= TITLE_WEIGHT)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> -e.getKey().length()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        // ② 剩余容量：频次 ≥ MIN_FREQ 的其余词按频次降序补足上限
        if (out.size() < MAX_TERMS) {
            freq.entrySet().stream()
                    .filter(e -> !out.containsKey(e.getKey()) && e.getValue() >= MIN_FREQ)
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(e -> -e.getKey().length()))
                    .limit(MAX_TERMS - out.size())
                    .forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * 从检索 query 提取命中的话题词（query 包含匹配、长度降序、去包含、上限 {@code maxTopics}）。
     *
     * @param query 检索文本（空 → 空列表）
     *
     * @param termIndex 话题词索引（{@link #buildTermIndex} 产物）
     *
     * @param maxTopics 返回话题词数量上限（≤0 → 空列表）
     *
     * @return 话题词列表（按 长度降序 → 频次降序；被更长候选包含的短词已剔除）
     */
    static List<String> extractTopics(String query, Map<String, Integer> termIndex, int maxTopics) {
        if (query == null || query.isBlank() || termIndex == null || termIndex.isEmpty() || maxTopics <= 0) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> e : termIndex.entrySet()) {
            String term = e.getKey();
            if (term.length() >= 2 && query.contains(term)) {
                candidates.add(term);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort((a, b) -> {
            int c = Integer.compare(b.length(), a.length());
            if (c != 0) {
                return c;
            }
            return Integer.compare(termIndex.get(b), termIndex.get(a));
        });
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            boolean contained = out.stream().anyMatch(o -> o.contains(c));
            if (contained) {
                continue;
            }
            out.add(c);
            if (out.size() >= maxTopics) {
                break;
            }
        }
        return out;
    }

    /** 对文本做 2~4 字滑动窗口计数（窗口须为纯中文/字母数字、非纯数字、不含停用词字符）。 */
    private static void countWindows(String text, Map<String, Integer> freq, int weight) {
        if (text == null || text.isBlank() || weight <= 0) {
            return;
        }
        int n = text.length();
        for (int i = 0; i < n; i++) {
            for (int len = MIN_WINDOW; len <= MAX_WINDOW && i + len <= n; len++) {
                String w = text.substring(i, i + len);
                if (!isValidWindow(w)) {
                    continue;
                }
                freq.merge(w, weight, Integer::sum);
            }
        }
    }

    /** 窗口有效性：全为 中文/字母数字、非纯数字、首尾不是停用词字符。 */
    private static boolean isValidWindow(String w) {
        boolean hasDigit = false;
        boolean hasNonDigit = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (isCjkOrAlphaNumeric(c)) {
                if (Character.isDigit(c)) {
                    hasDigit = true;
                } else {
                    hasNonDigit = true;
                }
            } else {
                return false; // 含标点/空格/其他字符 → 丢弃
            }
        }
        // 纯数字（如「2026」）无主题意义；至少一个非数字字符才有效
        if (!hasNonDigit) {
            return false;
        }
        // 首/尾为停用词 → 叙述片段（「什么样的」「是什么」）丢弃；实体词（「云来茶馆」）保留
        return STOP_CHARS.indexOf(w.charAt(0)) < 0 && STOP_CHARS.indexOf(w.charAt(w.length() - 1)) < 0;
    }

    /** 中文 CJK 或 ASCII 字母/数字（其余字符一律视为分隔）。 */
    private static boolean isCjkOrAlphaNumeric(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
