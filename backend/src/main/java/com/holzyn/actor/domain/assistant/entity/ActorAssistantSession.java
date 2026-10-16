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
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 项目级 AI 助手会话实体，对应表 actor_assistant_session。
 * <p>职责：承载「某项目下的一次助手对话」——项目隔离 + 标题 + 默认回答模式偏好
 * （叙事/事实，前端可改）+ 时间戳。消息明细见 {@link ActorAssistantMessage}。</p>
 * <p><b>零 AI</b>：本表仅会话元信息，创建/列表/删除均为纯本地 DB 短事务，无 AI 调用。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_assistant_session")
public class ActorAssistantSession {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（对话按项目隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属用户 ID（本地单用户恒 1；保留扩展位） */
    @Column(name = "user_id")
    private Long userId = 1L;

    /** 会话标题（默认取首条用户消息截断；可后续修改） */
    @Column(length = 100)
    private String title;

    /** 默认回答模式：narrative 叙事（默认）/ fact 严格事实 */
    @Column(name = "default_mode", length = 16)
    private String defaultMode = "narrative";

    /**
     * 干预确认粒度（M4，能力域 F）：strict 严格（每次确认）/ standard 标准（默认，
     * 低风险首次确认后放行、高风险每次确认）/ trust 信任（自动执行仅结果通知）。
     */
    @Column(name = "confirm_mode", length = 16)
    private String confirmMode = "standard";

    /** 本会话已放行的低风险操作类型（JSON 文本数组，如 ["talk","weather"]；标准模式首次确认后累积） */
    @Column(name = "approved_ops", columnDefinition = "LONGTEXT")
    private String approvedOps;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间（列表按此倒序；每次新消息刷新） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
