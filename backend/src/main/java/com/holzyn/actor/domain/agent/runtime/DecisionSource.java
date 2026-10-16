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

/**
 * 决策来源（P0-03 决策源可追溯；P0-02 诚实化止血的度量基座）。
 * <p>目标：任何一条写入世界的行为/记忆/编年史/事件记录都能回答「这是谁想出来的」——
 * AI 真实决策 / AI 重试后决策 / 程序化兜底（AI 失败或纯程序化档）/ 预算耗尽降级 / 决策缺失。
 * 落库为 {@code decision_source} 列（VARCHAR(32)，存枚举名），四类记录共用同一枚举。</p>
 * <p><b>隔离语义</b>：{@code AI/AI_RETRY} 是真实产出，进反思/学习/巩固等学习管线；
 * {@code FALLBACK_PROGRAMMATIC/BUDGET_EXHAUSTED/DECISION_MISSING} 是兜底或缺失产出，
 * 学习管线<b>不吸收</b>（见 {@code DecisionSources#isAbsorbableByLearning} 与
 * {@code ReflectionService} 证据过滤）——不得基于兜底数据学「经历」。</p>
 * <p>审计对应：{@code output_docs/HolzynActor_v1.1.0-beta.1_缺陷审计与真实性优化明细.md}
 * P0-02（程序化兜底冒充决策并计成功）/ P0-03（决策源不可追溯）。</p>
 */
public enum DecisionSource {

    /** AI 真实决策：一次 AI 调用成功产出（含快通道/空间候选/策略链 AI 深思） */
    AI,

    /** AI 重试后决策：AI 首次失败、重试（≤2 次）后成功产出 */
    AI_RETRY,

    /** 程序化兜底：AI 失败/不合规/纯程序化档（brainProfile=programmatic 或 AI 强度 programmatic）
     *  时，由程序化规则「继续当前行为」或模板兜底产出——0 AI，不伪造新文本 */
    FALLBACK_PROGRAMMATIC,

    /** 预算耗尽降级：AI 预算（BudgetGuard tick/批次命名空间）用尽后走程序化决策——0 AI，
     *  事实随本枚举落库（修「预算耗尽只在内存、endTick 即 remove」的不可追溯） */
    BUDGET_EXHAUSTED,

    /** 决策缺失：本时段该角色<b>没有</b>决策（AI 异常/中断/提交失败等），current_activity
     *  保持上一小时不变（不伪造新活动文本），编年史写显式「该时段无决策」记录 */
    DECISION_MISSING;

    /** 是否为真实 AI 产出（进学习管线）。 */
    public boolean isRealAi() {
        return this == AI || this == AI_RETRY;
    }

    /** 是否为兜底/缺失产出（学习管线必须隔离）。 */
    public boolean isFallbackOrMissing() {
        return !isRealAi();
    }

    /** 中文标签（日志/统计面板/前端「（机械）」标记的口径来源）。 */
    public String label() {
        return switch (this) {
            case AI -> "AI 决策";
            case AI_RETRY -> "AI 重试决策";
            case FALLBACK_PROGRAMMATIC -> "程序化兜底";
            case BUDGET_EXHAUSTED -> "预算耗尽降级";
            case DECISION_MISSING -> "决策缺失";
        };
    }
}
