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
 * 对谈消息实体，对应表 actor_talk_message。
 * <p>职责：承载一次对谈中的单条消息——玩家（user）与角色（assistant）发言、
 * 群聊（group）逐角色发言、系统/事件叙述。只存于 {@code actor_talk_*} 表，
 * <b>不写世界事件/编年史/知识库</b>（对谈是元层之外的对话层，零 OOC）。</p>
 * <p><b>表名说明（设计偏差 2026-10-12 M1）</b>：设计稿 §7 拟定 actor_dialogue_message，
 * 但既有「对话」模块已占用该表（世界联动聊天）——对谈消息表用 actor_talk_message
 * 与旧世界联动对话消息表区分。</p>
 * <p><b>零 AI</b>：消息落库为纯本地 DB 短事务；AI 生成在 {@code DialogueChatService}/
 * {@code DialogueGreetingService}（无事务）内完成后再落库。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_message", indexes = {
        @Index(name = "idx_talk_msg_session_seq", columnList = "session_id,seq")
})
public class ActorDialogueMessage {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属会话 ID（关联 actor_dialogue_session.id） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 消息角色：user 玩家 / assistant 角色发言 / group 群聊 / system / event */
    @Column(length = 16)
    private String role = "user";

    /** 发言角色引用（npc:{id}；assistant/group 消息使用，user 消息为 NULL） */
    @Column(name = "character_ref", length = 32)
    private String characterRef;

    /** 正文（Markdown） */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 元数据 JSON：swipe 候选 / 来源标注 / 摘要标记（如 {"kind":"greeting"}） */
    @Column(name = "meta_json", columnDefinition = "LONGTEXT")
    private String metaJson;

    /** 会话内序号（从 1 递增，列表按此排序） */
    @Column
    private Integer seq;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
