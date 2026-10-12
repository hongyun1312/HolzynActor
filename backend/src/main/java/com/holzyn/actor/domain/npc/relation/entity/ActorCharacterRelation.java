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

package com.holzyn.actor.domain.npc.relation.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色社会关系图实体，对应表 actor_relation（九模块规范化重构 原 actor_character_relation）。
 * <p>职责：记录角色之间 from→to 的社会关系（亲属/师徒/敌对等），
 * 用于角色卡生成时的「社会关系」输入与后续对话编排。</p>
 * <p>阶段 D （动态关系，）扩列：新增动态字段
 * affinity（好感 -100~100）/trust（信任 0~100）/relation_state（关系状态机）/
 * updated_at/last_interaction_at + from_ref/to_ref（正式 characterRef：
 * special:{id}/crowd:{id}，修 幽灵歧义——重名普通 NPC 可区分到具体 id）；
 * 互动（对话/行动/事件）经 learning 组件增减并发布 relation 事件（拓扑实时反映）。</p>
 */
@Data
@Entity
@Table(name = "actor_relation")
public class ActorCharacterRelation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 关系发起方角色 ID（special 角色 ID；普通型 NPC/幽灵端点存 0） */
    @Column(name = "from_character_id", nullable = false)
    private Long fromCharacterId;

    /** 关系目标角色 ID（同上） */
    @Column(name = "to_character_id", nullable = false)
    private Long toCharacterId;

    /** 发起方正式 characterRef（special:{id} / crowd:{id}； 起新增，幽灵名称端点存 null） */
    @Column(name = "from_ref", length = 50)
    private String fromRef;

    /** 目标方正式 characterRef（同上） */
    @Column(name = "to_ref", length = 50)
    private String toRef;

    /** 关系发起方角色名（角色不存在时 id=0 仅存名称的「幽灵端点」兜底；存在时冗余存储便于展示/按名关联） */
    @Column(name = "from_name", length = 100)
    private String fromName;

    /** 关系目标角色名（同上，名称兜底） */
    @Column(name = "to_name", length = 100)
    private String toName;

    /** 关系类型（亲属/师徒/敌对等） */
    @Column(name = "relation_type", length = 50, nullable = false)
    private String relationType;

    /** 关系描述 */
    @Column(length = 255)
    private String description;

    /** 好感度（：-100~100，互动增减；负数=厌恶） */
    @Column
    private Integer affinity;

    /** 信任度（：0~100，正互动增加/负互动减少） */
    @Column
    private Integer trust;

    /** 关系状态（状态机：stranger/acquaintance/friend/intimate/rival/hostile） */
    @Column(name = "relation_state", length = 30)
    private String relationState;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 关系动态字段最近更新时间（，互动/手动/AI 精修时刷新） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 最近一次互动时间（：对话/行动/事件触发时刷新） */
    @Column(name = "last_interaction_at")
    private LocalDateTime lastInteractionAt;

    /**
     * 插入前回调：填充创建时间 + 动态字段初始值（好感 0/信任 0/状态陌生/时间戳）。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (affinity == null) affinity = 0;
        if (trust == null) trust = 0;
        if (relationState == null) relationState = "stranger";
        updatedAt = now;
        if (lastInteractionAt == null) lastInteractionAt = now;
    }

    /**
     * 前回调：动态字段修改时刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}