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
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 对谈世界书条目实体，对应表 actor_talk_worldbook（M4 记忆/世界书等价物）。
 * <p>职责：承载「关键词 → 知识片段」的<b>会话级</b>世界书条目（对齐 SillyTavern Lorebook
 * 关键词激活的轻量版）：关键词命中最近 N 条消息 → 注入对应片段到对谈上下文 L1 动态段
 * （【世界书片段】，标注触发关键词〔来源〕）。与既有知识库（actor_knowledge_doc）互补——
 * 检索式世界书走 {@code KnowledgeRetrievalService} RAG 多路召回（项目级知识），
 * 本表承载用户手工维护的会话级「关键词触发」映射，二者互不干扰。</p>
 * <p><b>零 AI</b>：本表纯配置（会话级），CRUD/命中扫描均为纯本地 DB 短事务，无 AI 调用。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_worldbook", uniqueConstraints = {
        @UniqueConstraint(name = "uk_talk_worldbook_session_keyword", columnNames = {"session_id", "keyword"})
}, indexes = {
        @Index(name = "idx_talk_worldbook_session", columnList = "session_id")
})
public class ActorTalkWorldbook {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属会话 ID（对谈会话级世界书） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 归属项目 ID（会话隔离冗余，便于审计/清理） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 触发关键词（命中最近 N 条消息正文即激活；同会话内唯一） */
    @Column(name = "keyword", nullable = false, length = 100)
    private String keyword;

    /** 注入的知识片段内容（命中后以【世界书片段】注入 L1 动态段） */
    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    /** 启用标记（false=停用，不再参与命中扫描） */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /** 命中排序权重（同轮多条命中时按 priority 升序注入；默认 0） */
    @Column(name = "priority")
    private Integer priority = 0;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
