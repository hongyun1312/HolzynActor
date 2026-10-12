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
 * L1 短期（工作记忆）实体，对应表 actor_memory_working。
 * <p>职责：存储<b>工作记忆/热缓冲</b>——会话内显著草稿（draft，待复核）、进行中任务/关注点（task）、
 * 上下文快照（context）、感知快照（perception）；会话结束/超时后<b>双缓冲巩固</b>进 L2（actor_memory），
 * TTL 过期清理（status=expired）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§5（L1 短期）/ §10（双缓冲巩固）。
 * 服务逻辑 落地， 仅建表/实体。</p>
 */
@Data
@Entity
@Table(name = "actor_memory_working")
public class ActorMemoryWorking {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级归属 */
    @Column(name = "project_id")
    private Long projectId;

    /** 归属角色（special:{id}/crowd:{id}；NULL=项目级工作项） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 工作项来源会话标识（conversation_id 或 "auto"；会话结束合并用） */
    @Column(name = "session_key", length = 64)
    private String sessionKey;

    /**
     * 工作项类型：task 进行中任务 / draft 待复核草稿 / context 上下文快照 / perception 感知快照 /
     * short_term 短期事件记忆
     */
    @Column(name = "item_type", length = 20)
    private String itemType;

    /** 工作项内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 记忆内核扩展：L1 短期事件记忆重要度（0~1，importance 1-5 /5 归一；
     * 模糊速率 fuzz_rate=base×(1−importance×0.7) 与睡眠清洗/蒸馏触发的依据；非 short_term 行可空）
     */
    @Column(name = "importance_score")
    private Double importanceScore;

    /**
     * 记忆内核扩展：记忆发生时游戏秒（actor_world_clock 权威；艾宾浩斯模糊/
     * 睡眠清洗/蒸馏的游戏时间基准；非 short_term 行可空）
     */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 来源（dialog/action/event/perception/planning/manual） */
    @Column(name = "source", length = 20)
    private String source;

    /** 状态：active 热 / consolidated 已巩固进 L2 / expired 已过期 */
    @Column(name = "status", length = 20)
    private String status = "active";

    /** 巩固进 L2 时的重要度建议（1~5，最终以复核/抽取为准） */
    @Column(name = "importance_hint")
    private Integer importanceHint;

    /** TTL 过期时间（会话结束/超时即合并或清理） */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 巩固时间（status=consolidated 时） */
    @Column(name = "consolidated_at")
    private LocalDateTime consolidatedAt;

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
