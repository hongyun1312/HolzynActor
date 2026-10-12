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

package com.holzyn.actor.domain.npc.goal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色目标实体，对应表 actor_goal（九模块规范化重构 原 actor_character_goal，阶段 D 目标系统）。
 * <p>职责：承载特殊 NPC 的<b>长期目标</b>——系统框架 §4.3 / 六组件设计 §3.3：
 * agent_ref（统一角色引用 special:{id}）+ title/description + status（active 进行中/done 已完成/
 * abandoned 已放弃）+ priority（优先级）+ created_at/finished_at。
 * 目标由 AI 全自动生成（跨日/新建角色时，预算内失败静默）或手动 CRUD；状态流转 = 反思 AI 判断
 * （goal_updates）+ 手动 CRUD 双通道。目标注入感知【当前目标】段（active 目标 top2），
 * 使行动决策/对话有方向性（对齐 Smallville 层级规划）。</p>
 */
@Data
@Entity
@Table(name = "actor_goal", indexes = {
        @Index(name = "idx_goal_agent_ref", columnList = "agent_ref"),
        @Index(name = "idx_goal_project", columnList = "project_id")
})
public class ActorCharacterGoal {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID（归属校验） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 统一角色引用（special:{id}， 目标仅特殊 NPC；crowd 无目标表不建模） */
    @Column(name = "agent_ref", nullable = false, length = 50)
    private String agentRef;

    /** 目标标题（一句话，如「修复与旧友的关系」） */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 目标描述（详细说明：背景/动机/成功标准） */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 状态：active 进行中 / done 已完成 / abandoned 已放弃 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /** 优先级（1-5，默认 3；高优先级目标优先注入感知） */
    private Integer priority = 3;

    /** 完成/放弃时间（status 非 active 时刷新） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

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
        if (status == null) status = "active";
        if (priority == null) priority = 3;
        if (deleted == null) deleted = 0;
    }

    /**
     * 前回调：刷新更新时间；状态由 active 变更为非 active 时记录完成时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (status != null && !"active".equals(status) && finishedAt == null) {
            finishedAt = LocalDateTime.now();
        }
    }
}
