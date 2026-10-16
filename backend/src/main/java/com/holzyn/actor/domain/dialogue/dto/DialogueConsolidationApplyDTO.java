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

package com.holzyn.actor.domain.dialogue.dto;

import java.util.List;

/**
 * 对谈沉淀·应用入参（M6 apply）。
 * <p>语义：用户在对谈页确认卡片后，把 preview 产出的候选<b>原样回传</b>（客户端回显确认，
 * 服务端再次校验归属与边界后落库——AI 抽取只在 preview 发生一次，apply 为确定性写阶段）。</p>
 *
 * @param characters 候选角色列表（preview 卡片回传）
 */
public record DialogueConsolidationApplyDTO(List<CharacterCandidate> characters) {

    /**
     * 单角色沉淀候选（与 preview 卡片行同构）。
     *
     * @param characterRef  对谈角色引用（npc:{id}）
     * @param affinityDelta 好感 delta（钳制 -30~30）
     * @param trustDelta    信任 delta（钳制 -30~30）
     * @param stage         关系阶段（空=保持现状）
     * @param note          备注（可空）
     * @param stateSummary  关系状态摘要（可空）
     * @param memoryPoints  NPC 记忆要点（≤5 条）
     */
    public record CharacterCandidate(String characterRef, Integer affinityDelta, Integer trustDelta,
                                     String stage, String note, String stateSummary, List<String> memoryPoints) {
    }
}
