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
 * 对谈沉淀·确认卡片视图（M6 preview 输出）。
 * <p>职责：承载「沉淀将写入什么」的候选卡片——每位对谈角色的关系 delta（好感/信任/阶段/
 * 备注/状态摘要）与 NPC 记忆要点（与玩家的活动/共识/承诺）。AI 抽取后<b>不落库</b>，
 * 前端展示确认卡片；用户确认后原样回传（{@code apply}）落库。</p>
 */
@Data
public class DialogueConsolidationVO {

    /** 会话 ID */
    private Long sessionId;

    /** 会话类型（single / group） */
    private String type;

    /** 会话当前沉淀版本（player_relation_version，apply 后 +1） */
    private Integer playerRelationVersion;

    /** 每位对谈角色的沉淀候选（AI 判定） */
    private List<CharacterPreview> characters;

    /** 单角色沉淀候选（确认卡片行）。 */
    @Data
    public static class CharacterPreview {

        /** 对谈角色引用（npc:{id}） */
        private String characterRef;

        /** 角色名（冗余展示） */
        private String characterName;

        /** 当前好感（0~100；沉淀前） */
        private Integer currentAffinity;

        /** 当前信任（0~100；沉淀前） */
        private Integer currentTrust;

        /** 当前关系阶段（沉淀前） */
        private String currentStage;

        /** 好感 delta（AI 判定；钳制 -30~30） */
        private Integer affinityDelta;

        /** 信任 delta（AI 判定；钳制 -30~30） */
        private Integer trustDelta;

        /** 沉淀后好感（0~100，= 当前 + delta 钳制） */
        private Integer proposedAffinity;

        /** 沉淀后信任（0~100） */
        private Integer proposedTrust;

        /** 关系阶段（AI 判定；空=保持现状） */
        private String stage;

        /** 备注（AI 判定；可空） */
        private String note;

        /** 「关系状态摘要」（AI 维护；跨会话态度延续，可空） */
        private String stateSummary;

        /** NPC 记忆要点（该角色应记住的与玩家活动；≤5 条） */
        private List<String> memoryPoints;
    }
}
