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

package com.holzyn.actor.domain.world.entity;

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
 * 宏观社会演化 · 二阶涌现参与成员实体。
 * <p>职责：涌现事件的参与成员明细——谁在什么游戏日做了什么行为（actionType/indicator/delta）
 * 构成证据（GET /macro/emergence/{eid} 回放参与角色明细）；与涌现事件多对一。</p>
 */
@Data
@Entity
@Table(name = "actor_macro_emergence_member",
        indexes = {
                @Index(name = "idx_emergence_member_evt", columnList = "emergence_id"),
                @Index(name = "idx_emergence_member_actor", columnList = "project_id,actor_ref")
        })
public class ActorMacroEmergenceMember {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 涌现事件 ID（actor_macro_emergence_event.id） */
    @Column(name = "emergence_id", nullable = false)
    private Long emergenceId;

    /** 统一角色引用（special:{id} / crowd:{id}） */
    @Column(name = "actor_ref", nullable = false, length = 50)
    private String actorRef;

    /** 行动类型 */
    @Column(name = "action_type", length = 50)
    private String actionType;

    /** 宏观指标名 */
    @Column(name = "indicator", length = 50)
    private String indicator;

    /** 折算变化量 */
    @Column(name = "delta")
    private Double delta;

    /** 游戏日 */
    @Column(name = "game_day")
    private Long gameDay;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
