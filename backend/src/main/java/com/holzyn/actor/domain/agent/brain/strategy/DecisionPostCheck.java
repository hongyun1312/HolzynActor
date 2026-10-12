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

package com.holzyn.actor.domain.agent.brain.strategy;

import java.util.Map;

/**
 * 决策旁路校验器（自省-行为一致性校验·候选 A）。
 * <p>与 {@link BrainStrategy}（产出决策）正交：旁路校验器<b>不产出、不覆盖决策</b>，
 * 只在 {@link BrainStrategyRouter} 链命中产出决策后，对「已产出决策」做一次旁路检查
 * （如自省漂移检测：决策是否与最近反思结论一致），结果写 trace 供审计与反思闭环，
 * 任何情况下不改变主决策流程（失败/异常一律隔离）。</p>
 * <p><b>可插拔</b>：实现本接口注册为 Spring Bean 即被 {@link BrainStrategyRouter}
 * 自动收集并在链命中后调用（与 BrainStrategy 同构的注入点）。</p>
 */
public interface DecisionPostCheck {

    /**
     * 校验器名（trace/日志标识；恒唯一）。
     */
    String name();

    /**
     * 对已产出决策执行旁路校验。
     *
     * @param ctx 决策上下文（含角色/项目/触发源语义）
     *
     * @param decision 已产出决策 Map（内部规范：特殊链=ReAct 键集 / 普通链=CrowdAction 键集）
     */
    void check(DecisionContext ctx, Map<String, Object> decision);
}
