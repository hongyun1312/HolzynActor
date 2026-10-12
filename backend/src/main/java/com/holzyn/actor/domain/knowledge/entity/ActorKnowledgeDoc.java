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

package com.holzyn.actor.domain.knowledge.entity;

import com.holzyn.actor.common.JsonColumnConverter;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文档实体，对应表 actor_knowledge_doc。
 * <p>职责：存储项目级/角色级知识文档（标题 + 全文）。<b>AI 接入层重构 起无 embedding 列</b>
 * （旧 JSON 向量列已物理删除，向量唯一存储 = 向量库文件 data/vector-store/&lt;project&gt;/embeddings.json，
 * <p>双粒度：character_id 为空 = 项目级知识（所有角色可检索）；非空 = 角色级知识（仅该角色）。</p>
 */
@Data
@Entity
@Table(name = "actor_knowledge_doc", indexes = {
        @Index(name = "idx_knowledge_project", columnList = "project_id"),
        @Index(name = "idx_knowledge_character", columnList = "character_id")
})
public class ActorKnowledgeDoc {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id，数据隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 关联角色 ID（可空 = 项目级知识；非空 = 角色级知识） */
    @Column(name = "character_id")
    private Long characterId;

    /** 文档标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 文档全文 */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 状态：0草稿/1生效 */
    private Integer status = 1;

    /** 生命周期状态（P1 ②-2 知识层生命周期）：hot（活跃）/warm（常规）/stale（沉寂）/archived（归档）。
     * 引用率（access_count + last_access_at）驱动衰减；访问复活；archived 不参与检索（可手动还原）。 */
    @Column(name = "lifecycle_state", length = 16)
    private String lifecycleState = "hot";

    /** 知识置信度（0~1，默认 0.5）：桥接确认提升、衰减降低；供衰减/复核/失败驱动更新消费 */
    private Double confidence = 0.5;

    /** 累计被检索引用次数（引用率驱动衰减的数据底座） */
    @Column(name = "access_count")
    private Integer accessCount = 0;

    /** 最后被检索引用时间（引用率驱动衰减的时间底座） */
    @Column(name = "last_access_at")
    private LocalDateTime lastAccessAt;

    /** 最后生命周期评估时间 */
    @Column(name = "last_eval_at")
    private LocalDateTime lastEvalAt;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = 1;
        if (lifecycleState == null) lifecycleState = "hot";
        if (confidence == null) confidence = 0.5;
        if (accessCount == null) accessCount = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
