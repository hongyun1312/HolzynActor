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

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆-知识桥接候选实体，对应表 knowledge_candidate。
 * <p>职责（P1 ②-3 记忆-知识桥接层）：承载从反思洞察中抽取的「可复用事实型知识候选」——
 * AI 判定（scene=knowledge_bridge）产出 → 全自动确认（规则：内容非空/长度合规/is_knowledge=true
 * + 去重）→ 写入角色级「经历沉淀」知识文档并置 promoted；重复=duplicate；非知识/不合规=rejected。
 * 全流程自动（用户 Q5 确认：无需人工确认），候选表留档审计（冒烟/观测）。</p>
 */
@Data
@Entity
@Table(name = "knowledge_candidate", indexes = {
        @Index(name = "idx_kc_project", columnList = "project_id"),
        @Index(name = "idx_kc_character", columnList = "character_ref")
})
public class KnowledgeCandidate {

    /** 候选状态：已确认并写入「经历沉淀」文档。 */
    public static final String STATUS_PROMOTED = "promoted";
    /** 候选状态：与既有知识重复（跳过写入）。 */
    public static final String STATUS_DUPLICATE = "duplicate";
    /** 候选状态：AI 判定非知识 / 规则不合规（拒绝）。 */
    public static final String STATUS_REJECTED = "rejected";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（数据隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属角色引用（统一身份 npc:{actor_profile.id}） */
    @Column(name = "character_ref", length = 64)
    private String characterRef;

    /** 归属角色 ID（冗余便于联查） */
    @Column(name = "character_id")
    private Long characterId;

    /** 来源反思记忆 ID（可空：手动桥接触发时无） */
    @Column(name = "source_reflection_id")
    private Long sourceReflectionId;

    /** 候选事实文本（可复用事实型知识） */
    @Column(nullable = false, length = 500)
    private String content;

    /** 重要度（1~5，AI 判定） */
    private Integer importance = 3;

    /** 候选状态：promoted / duplicate / rejected */
    @Column(length = 16)
    private String status = STATUS_PROMOTED;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 确认（写入）时间 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (status == null) status = STATUS_PROMOTED;
        if (importance == null) importance = 3;
    }
}
