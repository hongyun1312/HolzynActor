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
 * 对谈群聊成员实体，对应表 actor_talk_group_member（M5 群聊新增）。
 * <p>职责：承载群聊会话的成员名单——<b>在场</b>（source=present：冻结时刻同一地点
 * 在场的角色，换场时重算）与<b>拉人</b>（source=manual：用户临时加入、换场保留）。
 * 成员写入<b>唯一入口</b>为 {@code DialogueGroupService}（零 AI 短事务），会话行本身
 * 仍由 {@code DialogueSessionService.create} 创建（对谈会话写入口不变）。</p>
 * <p><b>场景化语义（设计 §6.4/§4）</b>：默认成员 = 冻结时刻同地点在场的角色
 * （读冻结场景：location_id 相同且在冻结时刻位于该地点）；用户可换场（切地点重新
 * 计算在场，manual 成员保留）或拉人（临时加入，manual 标记）。</p>
 * <p><b>零 AI</b>：纯本地 DB 短事务，无 AI 调用。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_group_member", indexes = {
        @Index(name = "idx_talk_group_member_session", columnList = "session_id"),
        @Index(name = "uk_talk_group_member_session_ref", columnList = "session_id,character_ref", unique = true)
})
public class ActorTalkGroupMember {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属群聊会话 ID（关联 actor_talk_session.id） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 成员角色引用（npc:{id}） */
    @Column(name = "character_ref", length = 32, nullable = false)
    private String characterRef;

    /** 成员角色名（冗余存名，成员列表免关联查询） */
    @Column(name = "character_name", length = 64)
    private String characterName;

    /** 成员来源：present 在场（换场重算）/ manual 拉人（换场保留） */
    @Column(length = 16)
    private String source = "present";

    /** 加入时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
