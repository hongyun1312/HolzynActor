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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 玩家档案实体，对应表 actor_talk_player_profile。
 * <p>职责：承载「对谈中的玩家身份」——<b>与特殊 NPC 档案同构</b>的 persona 结构
 * （身份/背景/性格/说话风格/目标/能力等，persona_json），但<b>无记忆、无状态、
 * 无自主行动</b>（玩家=用户本人，不是被模拟对象）。relation_settings_json 存每角色
 * 「与我的关系」设定（过往/身份关系/态度，决策 ⑦），注入对谈上下文。</p>
 * <p><b>表名说明（设计偏差 2026-10-12 M1）</b>：设计稿 §7 拟定 actor_player_profile，
 * 对谈表族统一 actor_talk_* 前缀（与旧世界联动对话表 actor_dialogue* 区分）。</p>
 * <p><b>M1 只建表</b>：编辑流程在 M3（重构账户档案创建流程）落地；
 * M1 阶段 `DialogueRelationService.playerNameOf` 兜底账户昵称。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_player_profile", uniqueConstraints = {
        @UniqueConstraint(name = "uk_talk_player_profile_user", columnNames = "user_id")
})
public class ActorPlayerProfile {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 ID（本地单用户恒 1；唯一约束） */
    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;

    /** 玩家名 */
    @Column(length = 64)
    private String name;

    /** 玩家 persona（与特殊 NPC 档案同构，11 维裁剪；无记忆/无状态） */
    @Column(name = "persona_json", columnDefinition = "LONGTEXT")
    private String personaJson;

    /** 每角色关系设定 JSON（过往/身份关系/态度；决策 ⑦） */
    @Column(name = "relation_settings_json", columnDefinition = "LONGTEXT")
    private String relationSettingsJson;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
