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

import java.util.Map;

/**
 * 决策来源打标助手（P0-03 决策源可追溯）。
 * <p>决策 Map（决策链产物，序列化进 {@code actor_action_plan.action_json}）内以隐藏键
 * {@link #KEY} 携带来源（沿用 {@code _chainReaction} 隐藏键先例）；落库侧
 * （AgentActionService 写动作日志/事件/记忆）从计划 JSON 读回本键 → 写
 * {@code decision_source} 列。空值语义：旧数据/未打标路径视为 {@code AI}
 * （向后兼容——历史数据不因缺标记被当作兜底隔离）。</p>
 * <p>审计对应：P0-03（决策源不可追溯）；P0-02（程序化产出必须打标）。</p>
 */
public final class DecisionSources {

    /** 决策 Map 内携带来源的隐藏键（与 {@code _chainReaction} 同风格，序列化进 action_json） */
    public static final String KEY = "_decisionSource";

    private DecisionSources() {
    }

    /**
     * 给决策 Map 打上来源标记（不可变安全：若 Map 不可变则拷贝后 put；返回实际生效的 Map）。
     * <p>决策链产物可能是不可变 Map（{@code Map.of/List.of} 派生），直接 put 会抛
     * {@code UnsupportedOperationException}——一律走拷贝路径保证零副作用。</p>
     *
     * @param decision 决策 Map（可空）
     *
     * @param source 决策来源（可空=不打标原样返回）
     *
     * @return 打标后的 Map（不可变时为新拷贝；调用方必须使用返回值）
     */
    public static Map<String, Object> tag(Map<String, Object> decision, DecisionSource source) {
        if (decision == null || source == null) {
            return decision;
        }
        Object existing = decision.get(KEY);
        if (existing != null && source.name().equals(String.valueOf(existing))) {
            return decision;
        }
        Map<String, Object> mutable = new java.util.LinkedHashMap<>(decision);
        mutable.put(KEY, source.name());
        return mutable;
    }

    /** 从决策 Map 读来源（无标记/未知值 → null）。 */
    public static DecisionSource of(Map<String, Object> decision) {
        if (decision == null) {
            return null;
        }
        return parse(decision.get(KEY));
    }

    /** 从计划 action_json 决策节点读来源（无标记/未知值 → null）。 */
    public static DecisionSource of(JsonNode decision) {
        if (decision == null || decision.isMissingNode() || decision.isNull()) {
            return null;
        }
        JsonNode v = decision.get(KEY);
        return v == null || v.isMissingNode() || v.isNull() ? null : parse(v.asText());
    }

    /**
     * 解析枚举名（大小写不敏感；非法/空 → null）。
     *
     * @param raw 存储值（枚举名）
     *
     * @return 枚举值或 null
     */
    public static DecisionSource parse(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        if (s.isBlank()) {
            return null;
        }
        try {
            return DecisionSource.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 决策是否可被学习管线吸收（反思/学习/巩固/关系动力学）。
     * <p><b>向后兼容</b>：{@code null}=旧数据/未打标路径 → 视为 AI（不因缺标记被隔离）。</p>
     *
     * @param source 决策来源（可空=旧数据）
     *
     * @return true=真实 AI 产出，可作学习证据；false=兜底/缺失，必须隔离
     */
    public static boolean isAbsorbableByLearning(DecisionSource source) {
        return source == null || source.isRealAi();
    }

    /**
     * 决策策略名 → 决策来源（BrainStrategyRouter 链命中后由策略名判定）。
     * <p>映射口径：{@code programmatic_*}（程序化兜底链尾）与 {@code physio_danger}
     * （生理危险区程序化强制）→ {@code FALLBACK_PROGRAMMATIC}（0 AI 产出，必须打标隔离）；
     * 其余 AI 策略（deliberate/familiarity/space_choice/developmental_gating 等）→ {@code AI}。</p>
     *
     * @param strategyName 命中策略名（{@code BrainStrategy.name()}）
     *
     * @return 决策来源（null=未知策略名）
     */
    public static DecisionSource fromStrategy(String strategyName) {
        if (strategyName == null) {
            return null;
        }
        String n = strategyName.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("programmatic") || n.contains("physio")) {
            return DecisionSource.FALLBACK_PROGRAMMATIC;
        }
        return DecisionSource.AI;
    }
}
