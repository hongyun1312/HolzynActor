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

package com.holzyn.actor.domain.agent.runtime;

import tools.jackson.databind.JsonNode;

/**
 * ToM（心智理论）推断深度参数计算器。
 * <p>职责：由角色 OCEAN 的<b>宜人性×神经质</b>计算「意图推断深度」档位，并生成
 * 【推断深度】注入段——约束 AI 在自主对话/角色扮演/主决策/单聊中推断他人意图的深度
 * （现状优化轨道 §7.2：高宜+高神→过度解读、低宜+低神→直接理解；对齐
 * Dynamic Personality in LLM Agents（ACL 2025 Findings）：BFI 五维与 agent 行为显著相关）。</p>
 * <p>档位（数值 1-5）：</p>
 * <ul>
 * <li>过度解读（over-interpret）：宜人性≥4 且神经质≥4——习惯深挖动机、疑心他人言行有弦外之音；</li>
 * <li>直接理解（literal）：宜人性≤2 且神经质≤2——就事论事、很少揣测隐含意图；</li>
 * <li>常规推断（normal）：其余——按常理适度推断。</li>
 * </ul>
 * <p>OCEAN 数据缺失/非法 → 常规推断（零阻断降级）。</p>
 */
public final class TomInference {

    /** 档位：过度解读 */
    public static final String TIER_OVER_INTERPRET = "over_interpret";
    /** 档位：常规推断 */
    public static final String TIER_NORMAL = "normal";
    /** 档位：直接理解 */
    public static final String TIER_LITERAL = "literal";

    /** 档位中文标签 */
    private static final java.util.Map<String, String> TIER_LABEL = java.util.Map.of(
            TIER_OVER_INTERPRET, "过度解读",
            TIER_NORMAL, "常规推断",
            TIER_LITERAL, "直接理解");

    private TomInference() {
    }

    /**
     * 由 OCEAN 五维数值计算推断深度档位（纯静态，可单测）。
     *
     * @param agreeableness 宜人性（1-5；<0 视为缺失）
     *
     * @param neuroticism 神经质（1-5；<0 视为缺失）
     *
     * @return 档位（over_interpret / normal / literal；任一缺失回退 normal）
     */
    public static String tierOf(int agreeableness, int neuroticism) {
        if (agreeableness < 1 || neuroticism < 1) {
            return TIER_NORMAL;
        }
        if (agreeableness >= 4 && neuroticism >= 4) {
            return TIER_OVER_INTERPRET;
        }
        if (agreeableness <= 2 && neuroticism <= 2) {
            return TIER_LITERAL;
        }
        return TIER_NORMAL;
    }

    /**
     * 从 v2 档案 persona_json 解析 OCEAN 并计算档位
     * （{@code dims.personality.ocean.agreeableness/neuroticism}，0-1 小数；解析失败/缺失回退 normal）。
     *
     * @param personaJson 档案 persona_json 文本（可空）
     *
     * @return 档位
     */
    public static String tierOf(String personaJson) {
        if (personaJson == null || personaJson.isBlank()) {
            return TIER_NORMAL;
        }
        try {
            // ====================：OCEAN 取值口径改为 v2 ====================
            // v1 角色卡：personality.agreeableness/neuroticism，**1-5 整数**；
            // v2 档案：dims.personality.ocean.agreeableness/neuroticism，**0-1 小数**。
            // 因此必须换路径 + 换量纲（1 + v*4 映射回 tierOf 期望的 1-5），
            // 否则 asInt(0-1 小数) 恒为 0 → 一直落到"缺失"分支 → ToM 深度恒为常规推断（静默失效）。
            JsonNode ocean = new tools.jackson.databind.ObjectMapper().readTree(personaJson)
                    .path("dims").path("personality").path("ocean");
            double agree = ocean.path("agreeableness").asDouble(-1);
            double neuro = ocean.path("neuroticism").asDouble(-1);
            if (agree < 0 || neuro < 0) {
                return TIER_NORMAL;
            }
            return tierOf((int) Math.round(1 + agree * 4), (int) Math.round(1 + neuro * 4));
        } catch (Exception e) {
            return TIER_NORMAL;
        }
    }

    /**
     * 生成【推断深度】注入段文本（纯静态）。
     *
     * @param personaJson 档案 persona_json 文本（可空=常规推断）
     *
     * @return 注入段文本（形如「【推断深度】你习惯……」，恒非空）
     */
    public static String segmentOf(String personaJson) {
        return segmentOf(tierOf(personaJson), null);
    }

    /**
     * 生成【推断深度】注入段文本（纯静态，按档位输出行为约束）。
     *
     * @param tier 档位（未知档位回退 normal 文本）
     *
     * @return 注入段文本（恒非空）
     */
    public static String segmentOf(String tier, String characterName) {
        String label = TIER_LABEL.getOrDefault(tier == null ? "" : tier, TIER_LABEL.get(TIER_NORMAL));
        String who = characterName == null || characterName.isBlank() ? "你" : characterName.trim();
        return switch (tier == null ? "" : tier) {
            case TIER_OVER_INTERPRET ->
                    "【推断深度】" + who + "惯于揣摩他人言行的弦外之音，容易把寻常举动解读为深层用意"
                            + "（过度解读）——推断他人意图时请按此深度，但不要完全脱离事实。";
            case TIER_LITERAL ->
                    "【推断深度】" + who + "就事论事、不喜猜度，很少揣测他人话语背后的隐含意图"
                            + "（直接理解）——推断他人意图时请按此深度，只依据明说的事实。";
            default ->
                    "【推断深度】" + who + "按常理适度推断他人意图，不过度揣测也不忽视明显信号"
                            + "（常规推断）——推断他人意图时请按此深度。";
        };
    }

    /** 档位中文标签（未知回退「常规推断」）。 */
    public static String labelOf(String tier) {
        return TIER_LABEL.getOrDefault(tier == null ? "" : tier, TIER_LABEL.get(TIER_NORMAL));
    }
}
