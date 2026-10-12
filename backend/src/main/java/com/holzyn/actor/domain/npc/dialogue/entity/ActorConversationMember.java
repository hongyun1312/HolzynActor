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

package com.holzyn.actor.domain.npc.dialogue.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 群聊成员实体，对应表 actor_dialogue_member（九模块规范化重构 原 actor_conversation_member，B5 普通 NPC 参与通道扩展）。
 * <p>职责：记录一次会话包含哪些成员（单聊 1 个，群聊多个），供对话编排按成员加载角色卡与上下文。
 * <b>B5（成员类型化，方案 A）</b>：新增 member_type（special 特殊 NPC / crowd 普通 NPC）+
 * member_name（名称快照，crowd 无角色卡时展示/渲染用）；character_id 语义扩展为「成员对象 id」——
 * special → actor_character.id（存量兼容，member_type 缺省 special）、crowd → actor_ordinary_npc.id。</p>
 */
@Data
@Entity
@Table(name = "actor_dialogue_member")
public class ActorConversationMember {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（关联 actor_conversation.id） */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 成员类型（B5：special 特殊 NPC 缺省 / crowd 普通 NPC） */
    @Column(name = "member_type", length = 10)
    private String memberType = "special";

    /** 成员对象 ID（B5：special→actor_character.id / crowd→actor_ordinary_npc.id；存量缺省 special） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 成员名称快照（B5：crowd 渲染/展示用；special 可空=查角色表） */
    @Column(name = "member_name", length = 50)
    private String memberName;

    /** 加入时间 */
    @Column(name = "join_time", updatable = false)
    private LocalDateTime joinTime;

    /**
     * 插入前回调：填充加入时间与默认成员类型。
     */
    @PrePersist
    void prePersist() {
        if (joinTime == null) joinTime = LocalDateTime.now();
        if (memberType == null || memberType.isBlank()) memberType = "special";
    }
}