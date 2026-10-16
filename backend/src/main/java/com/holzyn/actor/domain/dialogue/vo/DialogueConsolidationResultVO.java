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

package com.holzyn.actor.domain.dialogue.vo;

import lombok.Data;

import java.util.List;

/**
 * 对谈沉淀·应用结果视图（M6 apply 输出）。
 * <p>职责：承载「沉淀已写入什么」的结果——每位角色的关系行新值（好感/信任/阶段/备注/
 * 状态摘要/版本）+ 写入的 NPC 记忆条数 + 会话沉淀版本（player_relation_version）。</p>
 */
@Data
public class DialogueConsolidationResultVO {

    /** 会话 ID */
    private Long sessionId;

    /** 会话沉淀版本（apply 后 = 本轮最高角色关系版本） */
    private Integer version;

    /** 每位角色的沉淀结果 */
    private List<CharacterResult> characters;

    /** 单角色沉淀结果。 */
    @Data
    public static class CharacterResult {

        /** 对谈角色引用（npc:{id}） */
        private String characterRef;

        /** 角色名（冗余展示） */
        private String characterName;

        /** 沉淀后好感（0~100） */
        private Integer affinity;

        /** 沉淀后信任（0~100） */
        private Integer trust;

        /** 沉淀后关系阶段 */
        private String stage;

        /** 沉淀后备注 */
        private String note;

        /** 沉淀后「关系状态摘要」 */
        private String stateSummary;

        /** 沉淀后关系版本（幂等；每次 +1） */
        private Integer version;

        /** 写入该角色的 NPC 记忆条数 */
        private Integer memoriesWritten;
    }
}
