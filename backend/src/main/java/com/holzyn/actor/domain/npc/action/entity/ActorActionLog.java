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

package com.holzyn.actor.domain.npc.action.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 行动执行日志实体，对应表 actor_action_log。
 * <p>职责：记录行动模拟执行的每个时间线节点（摘要+详情），
 * 与行动决策一起构成「行动时间线」数据源；log_time 为行动发生时间。</p>
 */
@Data
@Entity
@Table(name = "actor_action_log")
public class ActorActionLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色 ID（关联 actor_character.id） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 关联行动决策 ID（关联 actor_action_plan.id，可空） */
    @Column(name = "plan_id")
    private Long planId;

    /** 行动摘要（时间线节点标题，如「前往城东市场采购药材」） */
    @Column(nullable = false, length = 255)
    private String summary;

    /** 行动详情（补充信息/结果） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 行动发生时间 */
    @Column(name = "log_time", nullable = false)
    private LocalDateTime logTime;

    /**
     * 决策来源（P0-03 决策源可追溯，VARCHAR(32) 存 {@code DecisionSource} 枚举名）：
     * AI 真实决策 / AI_RETRY 重试决策 / FALLBACK_PROGRAMMATIC 程序化兜底 /
     * BUDGET_EXHAUSTED 预算耗尽降级 / DECISION_MISSING 决策缺失。
     * 空值=旧数据/未打标路径（视为 AI，不隔离）。
     */
    @Column(name = "decision_source", length = 32)
    private String decisionSource;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (logTime == null) logTime = now;
    }
}