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

package com.holzyn.actor.domain.agent.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策与协调 决策 state delta 记录实体（表 actor_state_delta，）。
 * <p>职责：记录「决策驱动的显式状态变更」——一次决策（对应 actor_plan 行）执行产生的
 * 行动/位置/状态变更快照，幂等键=decision_id（actor_plan.id，uk），供打断恢复校验
 * （delta 未过期且目标状态未变 → 原计划仍有效）、链式/后续决策读取与调试回看。</p>
 * <p>语义：delta 仅记「决策驱动的变更」，不替代状态权威源
 * （actor_state/actor_character 仍是唯一真相）；changes_json 存 {action,type,target,
 */
@Data
@Entity
@Table(name = "actor_state_delta",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_state_delta_decision", columnNames = {"decision_id"}),
        indexes = @Index(name = "idx_state_delta_proj_ref", columnList = "project_id,npc_ref"))
public class ActorStateDelta {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 决策角色引用（special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 决策 ID（幂等键=actor_plan.id，uk） */
    @Column(name = "decision_id", nullable = false)
    private Long decisionId;

    /** 变更明细 JSON（action/type/target/reason/urgency/duration/locationTo/locationBefore/stateDeltas） */
    @Column(name = "changes_json", columnDefinition = "TEXT")
    private String changesJson;

    /** 决策产生时的游戏秒（引擎推进位置；恢复校验时效基准） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
