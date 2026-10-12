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

import java.util.List;
import java.util.Map;

/**
 * 决策策略链产物（P2-3 BrainStrategy）。
 *
 * @param decision 命中的内部规范决策 Map（特殊链=ReAct 规范 Map；普通链=
 *                 {@code {actionText, actionType, target, reason}} 人群行动 Map）
 *
 * @param hitStrategy 命中的策略名（trace/日志可观测「哪个策略产出了这次决策」）
 *
 * @param priority 命中策略的链内优先级
 *
 * @param postProcess 是否执行调用方后处理（护栏/前瞻/想法；程序化兜底=false）
 *
 * @param steps 链尝试步骤记录（「策略名×未命中/✓命中」序列，决策链路可视化）
 */
public record DecisionOutcome(Map<String, Object> decision, String hitStrategy, int priority,
                              boolean postProcess, List<String> steps) {
}
