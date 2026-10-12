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

package com.holzyn.actor.domain.npc.memory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 归档（冷存储）实体，对应表 actor_memory_archive。
 * <p>职责：存储<b>归档记忆</b>——超龄归档（age）/ 被覆盖旧版本（superseded）/ 反思降级证据（downgraded）/
 * 预算淘汰（evicted）/ 手动归档（manual）；<b>原文保留</b>（不摘要化，保 raw 记录）；
 * 按需检索（项目/ref/type/关键词/游戏日）+ 导出；<b>不参与日常注入</b>。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§8（归档）。服务逻辑 落地， 仅建表/实体。</p>
 */
@Data
@Entity
@Table(name = "actor_memory_archive")
public class ActorMemoryArchive {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级归属 */
    @Column(name = "project_id")
    private Long projectId;

    /** 归属角色（可 NULL=项目级/无归属） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 来源表：actor_memory / actor_core_memory / actor_memory_working */
    @Column(name = "source_table", length = 30)
    private String sourceTable;

    /** 原记忆 id（可 NULL=无来源，如纯手动存档） */
    @Column(name = "source_memory_id")
    private Long sourceMemoryId;

    /** 归档时类型（semantic/episodic/reflection/plan/core/working…） */
    @Column(name = "type", length = 20)
    private String type;

    /** 归档内容（原文保留） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 归档时上下文快照（可选） */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /** 归档时重要度 */
    @Column(name = "importance_at_archive")
    private Integer importanceAtArchive;

    /** 归档原因：age 超龄 / superseded 被覆盖 / downgraded 反思降级 / evicted 预算淘汰 / manual 手动 */
    @Column(name = "archive_reason", length = 20)
    private String archiveReason;

    /** 归档时间 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** 归档时游戏日（floorDiv(game_hour,24)，按游戏时间检索用） */
    @Column(name = "game_day_at_archive")
    private Long gameDayAtArchive;

    /** 软删除标记（归档表内软删，二次清理） */
    private Integer deleted = 0;

    /** 还原标记 */
    private Integer restored = 0;

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
        if (archivedAt == null) archivedAt = now;
        if (deleted == null) deleted = 0;
        if (restored == null) restored = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
