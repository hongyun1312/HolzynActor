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

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;

/**
 * 决策策略链上下文（P2-3 BrainStrategy）：一次「角色此刻做什么」决策的完整输入。
 *
 * @param projectId 项目 ID
 *
 * @param character 角色实体
 *
 * @param userId 归属用户 ID（特殊 NPC 决策链 AI 调用记账用；普通 NPC 链可空）
 *
 * @param kind 角色类型（SPECIAL=核心角色链 / COMMON=普通 NPC 链）
 *
 * @param brainLevel 脑力档位（full/light/programmatic；可空=解析失败按非 programmatic 宽松）
 *
 * @param situation 当前情境文本（特殊 NPC AI 深思输入；可空=服务自组装）
 *
 * @param gameSecond 游戏时刻（秒，引擎推进位置；普通 NPC 链用）
 *
 * @param environmentText 环境刺激文本（普通 NPC 快选择链用；可空=常规决策）
 *
 * @param hasStimulus 是否有外部刺激（O6 发育演化：真=刺激决策[事件/环境/靠近/紧急]，
 *                    阶段门控让路；false/null=周期主决策[引擎每小时]，阶段门控空闲接管。
 *                    由调用方按触发源判定——STIMULUS 触发为 true；引擎主决策即使带感知
 *                    情境文本也视为空闲，感知不是刺激）
 */
public record DecisionContext(Long projectId, ActorCharacter character, Long userId, NpcKind kind,
                              String brainLevel, String situation, Long gameSecond, String environmentText,
                              Boolean hasStimulus) {
}
