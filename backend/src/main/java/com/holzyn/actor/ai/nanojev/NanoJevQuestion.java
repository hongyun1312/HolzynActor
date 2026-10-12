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
import java.util.List;
import java.util.Map;

/**
 * NanoJev 单个问题（三题型之一：choice / boolean / score）。
 * <p>官方契约：每个 question 只允许
 * {@code type / instructions / criteria} 三个字段，多一个字段即报错。</p>
 * <ul>
 * <li><b>choice</b>：2–255 个动态候选，{@code criteria} 为 {@code Map&lt;候选, 语义描述&gt;}
 * （返回各候选概率，softmax）→「选哪个」；</li>
 * <li><b>boolean</b>：命题为真的概率（sigmoid），{@code criteria} 可选且只允许
 * {@code false/true} 两键 →「是否/允不允许」；</li>
 * <li><b>score</b>：2–10 个有序档位，{@code criteria} 为 {@code List&lt;档位描述&gt;}
 * （索引即档位，返回分布 + 期望值 + argmax 档位）→「打分/分级」。</li>
 * </ul>
 * <p><b>⚠️ 档位描述必须自包含</b>（官方声明）：改写某个档位的描述会改变它被报告的索引——
 * 接入方把档位描述当作「Prompt」管理。</p>
 *
 * @param type 题型：choice / boolean / score
 *
 * @param instructions 指令文本（三题型必填、非空）
 *
 * @param criteria choice 候选表 / boolean 的 false·true 判据表（可空=boolean 无显式判据）
 *
 * @param scoreCriteria score 有序档位描述（仅 score 用；数组索引即档位）
 */
public record NanoJevQuestion(String type, String instructions,
                              Map<String, String> criteria, List<String> scoreCriteria) {

    /** choice 题型常量 */
    public static final String TYPE_CHOICE = "choice";
    /** boolean 题型常量 */
    public static final String TYPE_BOOLEAN = "boolean";
    /** score 题型常量 */
    public static final String TYPE_SCORE = "score";

    /** 官方 choice 候选数下限 */
    public static final int CHOICE_MIN = 2;
    /** 官方 choice 候选数上限 */
    public static final int CHOICE_MAX = 255;
    /** 官方 score 档位数下限 */
    public static final int SCORE_MIN = 2;
    /** 官方 score 档位数上限 */
    public static final int SCORE_MAX = 10;

    /** 构造校验（非法即抛 IllegalArgumentException，调用方按编码错误处理）。 */
    public NanoJevQuestion {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("NanoJev 问题缺少 type（choice/boolean/score）");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("NanoJev 问题 instructions 必须非空：" + type);
        }
        switch (type) {
            case TYPE_CHOICE -> {
                if (criteria == null || criteria.size() < CHOICE_MIN || criteria.size() > CHOICE_MAX) {
                    throw new IllegalArgumentException(
                            "NanoJev choice 候选数必须为 " + CHOICE_MIN + "~" + CHOICE_MAX + "，实际 "
                                    + (criteria == null ? 0 : criteria.size()));
                }
                criteria.forEach((k, v) -> {
                    if (k == null || k.isBlank() || v == null || v.isBlank()) {
                        throw new IllegalArgumentException("NanoJev choice 候选键值必须都非空");
                    }
                });
            }
            case TYPE_BOOLEAN -> {
                if (criteria != null) {
                    if (criteria.size() != 2 || !criteria.containsKey("false") || !criteria.containsKey("true")) {
                        throw new IllegalArgumentException("NanoJev boolean criteria 只允许 false/true 两键");
                    }
                    if (criteria.get("false") == null || criteria.get("false").isBlank()
                            || criteria.get("true") == null || criteria.get("true").isBlank()) {
                        throw new IllegalArgumentException("NanoJev boolean criteria 值必须非空");
                    }
                }
            }
            case TYPE_SCORE -> {
                if (scoreCriteria == null || scoreCriteria.size() < SCORE_MIN || scoreCriteria.size() > SCORE_MAX) {
                    throw new IllegalArgumentException(
                            "NanoJev score 档位数必须为 " + SCORE_MIN + "~" + SCORE_MAX + "，实际 "
                                    + (scoreCriteria == null ? 0 : scoreCriteria.size()));
                }
                scoreCriteria.forEach(s -> {
                    if (s == null || s.isBlank()) {
                        throw new IllegalArgumentException("NanoJev score 档位描述必须非空（描述须自包含）");
                    }
                });
            }
            default -> throw new IllegalArgumentException("NanoJev 未知题型：" + type);
        }
    }

    /** 构造 choice 题（候选表 → 语义描述）。 */
    public static NanoJevQuestion choice(String instructions, Map<String, String> criteria) {
        return new NanoJevQuestion(TYPE_CHOICE, instructions, new LinkedHashMap<>(criteria), null);
    }

    /** 构造 boolean 题（无显式判据；命题即 instructions）。 */
    public static NanoJevQuestion bool(String instructions) {
        return new NanoJevQuestion(TYPE_BOOLEAN, instructions, null, null);
    }

    /** 构造 boolean 题（带 false/true 判据表）。 */
    public static NanoJevQuestion bool(String instructions, String criteriaFalse, String criteriaTrue) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("false", criteriaFalse);
        c.put("true", criteriaTrue);
        return new NanoJevQuestion(TYPE_BOOLEAN, instructions, c, null);
    }

    /** 构造 score 题（有序档位描述，索引即档位）。 */
    public static NanoJevQuestion score(String instructions, List<String> criteria) {
        return new NanoJevQuestion(TYPE_SCORE, instructions, null, List.copyOf(criteria));
    }

    /**
     * 该题的候选路径数（choice=候选数；boolean=1；score=档位数—— 网关实测
     * candidate_paths=7 = choice3 + boolean1 + score3 档，官方文档未明示 score 口径，
     * 按服务端实际计数对齐，避免批量拆分低估路径数撞 256 上限）。
     */
    public int pathCount() {
        if (TYPE_CHOICE.equals(type)) {
            return criteria == null ? 0 : criteria.size();
        }
        if (TYPE_SCORE.equals(type)) {
            return scoreCriteria == null ? 0 : scoreCriteria.size();
        }
        return 1;
    }
}
