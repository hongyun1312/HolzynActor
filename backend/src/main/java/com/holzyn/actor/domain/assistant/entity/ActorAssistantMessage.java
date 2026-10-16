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

package com.holzyn.actor.domain.assistant.entity;

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
 * 项目级 AI 助手消息实体，对应表 actor_assistant_message。
 * <p>职责：承载助手会话内的单条消息——角色（user/assistant）、正文、结构化附属载荷
 * （数据卡片 card_json / 来源标注 sources_json / 工具调用记录 tool_calls_json / 快速追问
 * quick_follows_json，均为 JSON 文本）与回答模式 mode。供「会话持久化 + 跨页面保持」读取。</p>
 * <p><b>零 AI</b>：消息落库为纯本地 DB 短事务（chat 编排里 AI 调用与落库分层）。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_assistant_message", indexes = {
        @Index(name = "idx_assistant_msg_session", columnList = "session_id")
})
public class ActorAssistantMessage {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属会话 ID（关联 actor_assistant_session.id） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 归属项目 ID（冗余便于按项目清理） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 消息角色：user / assistant */
    @Column(length = 16, nullable = false)
    private String role;

    /** 消息正文 */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 回答模式（assistant 消息）：narrative / fact */
    @Column(length = 16)
    private String mode;

    /** 数据卡片 JSON（结构见 AssistantCardVO；非卡片消息为空） */
    @Column(name = "card_json", columnDefinition = "LONGTEXT")
    private String cardJson;

    /** 来源标注 JSON（来源数组，见 AssistantSourceVO；可空） */
    @Column(name = "sources_json", columnDefinition = "LONGTEXT")
    private String sourcesJson;

    /** 工具调用记录 JSON（本次回答实际执行过的工具列表；可空） */
    @Column(name = "tool_calls_json", columnDefinition = "LONGTEXT")
    private String toolCallsJson;

    /** 快速追问 JSON（建议追问数组；可空） */
    @Column(name = "quick_follows_json", columnDefinition = "LONGTEXT")
    private String quickFollowsJson;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
