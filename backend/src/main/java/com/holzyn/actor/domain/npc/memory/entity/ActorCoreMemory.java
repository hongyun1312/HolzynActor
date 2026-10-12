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
 * L3 核心记忆实体，对应表 actor_memory_core。
 * <p>职责：存储<b>恒定在上下文的动态核心块</b>（Letta core memory blocks 风格）——角色核心信念/价值观/
 * 身份事实等；静态基线（角色卡 persona + brainProfile + 目标 + 关键关系）组合渲染不落此表。
 * agent 可自编辑（editable_by_agent=1，core_memory_update 模板）/ 开发者用户编辑（editable_by_agent=0 只读）；
 * 有界（≤400 字/块、≤8 块/角色）；版本化（version+1，旧块 supersedes_id→归档）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§7（L3 核心）。服务逻辑 落地， 仅建表/实体。</p>
 */
@Data
@Entity
@Table(name = "actor_memory_core")
public class ActorCoreMemory {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级归属 */
    @Column(name = "project_id")
    private Long projectId;

    /** 归属角色（special:{id}/crowd:{id}；NULL=项目级核心块，世界共性设定） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 块键：identity 身份 / personality 性格 / beliefs 核心信念 / values 价值观 / goals 长期目标 / relations 关键关系 / custom:* 自定义 */
    @Column(name = "block_key", length = 50)
    private String blockKey;

    /** 块显示名（如「核心信念」） */
    @Column(name = "block_name", length = 100)
    private String blockName;

    /** 核心块内容（有界，默认 ≤400 字/块，恒定注入预算控制） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 重要度（1~5，决定恒定注入顺序） */
    private Integer importance = 3;

    /** 1=agent 可自编辑（默认）/ 0=仅开发者或用户可改（只读块） */
    @Column(name = "editable_by_agent")
    private Integer editableByAgent = 1;

    /** 版本号（自编辑/升级 +1） */
    private Integer version = 1;

    /** 被本条取代的旧块 id（旧块→归档） */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /** 软删除标记：0正常/1已删 */
    private Integer deleted = 0;

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
        if (importance == null) importance = 3;
        if (editableByAgent == null) editableByAgent = 1;
        if (version == null) version = 1;
        if (deleted == null) deleted = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
