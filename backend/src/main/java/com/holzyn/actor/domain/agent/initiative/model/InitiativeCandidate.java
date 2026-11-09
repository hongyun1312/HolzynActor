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

package com.holzyn.actor.domain.agent.initiative.model;

/**
 * 主动性候选。
 *
 * <p>只承载规则扫描产物，不落库；{@link com.holzyn.actor.domain.agent.initiative.service.NpcInitiativeService}
 * 按分数排序后为每个候选建一条 {@code actor_plan(trigger_type=initiative)}。</p>
 *
 * @param npcId        发起 NPC 的 actor_profile.id
 * @param npcRef       发起 NPC 的统一引用
 * @param kind         contract / rumor / absence
 * @param urgency      1-5 紧急度（契约逾期 5、临近 4、传闻 4、久别 3）
 * @param causeEventId 因果父事件（契约事实的 source_event_id / 传闻事实事件 / 最近互动事件）
 * @param factKey      相关事实键（无则空）
 * @param reason       上门理由（给玩家看的短句）
 * @param score        规则排序分
 */
public record InitiativeCandidate(Long npcId, String npcRef, String kind, int urgency,
                                  Long causeEventId, String factKey, String reason, double score) {
}
