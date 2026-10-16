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

package com.holzyn.actor.domain.dialogue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 玩家-角色关系实体，对应表 actor_talk_player_relation。
 * <p>职责：承载「角色对玩家的动态关系」——好感/信任/关系阶段/备注 + AI 维护的
 * 「关系状态摘要」（state_summary）。<b>跨会话态度延续的唯一档案</b>（决策 ⑧b）：
 * 对谈上下文按本表渲染「与玩家的关系」段；沉淀（M6）时由 AI 判定关系 delta 更新本表
 * （version 幂等），并写角色 L2 记忆「与玩家活动」。</p>
 * <p><b>表名说明（设计偏差 2026-10-12 M1）</b>：设计稿 §7 拟定 actor_player_relation，
 * 对谈表族统一 actor_talk_* 前缀（与旧世界联动对话表 actor_dialogue* 区分）。</p>
 * <p><b>M1 只读</b>：读段由 {@code DialogueRelationService} 提供；写=沉淀 M6，
 * 关系设定编辑=M3。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_player_relation", indexes = {
        @Index(name = "idx_talk_player_rel_project_char", columnList = "project_id,character_ref")
})
public class ActorPlayerRelation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 玩家档案 ID（关联 actor_player_profile.id） */
    @Column(name = "player_profile_id")
    private Long playerProfileId;

    /** 对谈角色引用（npc:{id}） */
    @Column(name = "character_ref", length = 32)
    private String characterRef;

    /** 好感（0~100；基线来自关系设定，沉淀时 AI 判定 delta） */
    private Integer affinity = 0;

    /** 信任（0~100） */
    private Integer trust = 0;

    /** 关系阶段（陌生/相识/熟络/…） */
    @Column(length = 32)
    private String stage = "陌生";

    /** 备注（用户可编辑） */
    @Column(length = 500)
    private String note;

    /** AI 维护的「关系状态摘要」（跨会话态度延续） */
    @Column(name = "state_summary", length = 500)
    private String stateSummary;

    /** 沉淀版本（幂等；每次沉淀 +1） */
    private Integer version = 0;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
