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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 中观组织成员关系实体。
 * <p>职责：组织 ↔ 个体 的多对多成员关系——特殊 NPC 与普通 NPC 统一入组（actor_ref 兼容
 * special:{id} / crowd:{id}），含组内角色与组内影响力：</p>
 * <ul>
 * <li><b>role</b>：leader 首领 / elder 长老 / core 核心 / member 普通 / affiliate 外围；
 * 指令路由按角色分工差异化（首领读到「组织期待你牵头」、普通成员读到「跟随组织」）；</li>
 * <li><b>influence</b>：成员在组内的影响力（势力贡献累计口径，MacroFactionEngine 写回用）；</li>
 * <li><b>status</b>：active 在组 / left 离开 / expelled 被逐；响应回收与指令路由只看 active。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_meso_membership",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_meso_member", columnNames = {"group_id", "actor_ref"})
        },
        indexes = {
                @Index(name = "idx_meso_member_actor", columnList = "project_id,actor_ref"),
                @Index(name = "idx_meso_member_group", columnList = "group_id")
        })
public class ActorMesoMembership {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 组织 ID（actor_meso_group.id） */
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** 角色表主键（特殊/普通统一；actor_ref 兼容引用） */
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 统一角色引用（special:{id} / crowd:{id}） */
    @Column(name = "actor_ref", nullable = false, length = 50)
    private String actorRef;

    /** 组内角色：leader / elder / core / member / affiliate */
    @Column(name = "role", nullable = false, length = 30)
    private String role = "member";

    /** 成员在组内影响力（势力贡献累计口径，0-100） */
    @Column(name = "influence", nullable = false)
    private Double influence = 0.0;

    /** 入组游戏秒 */
    @Column(name = "joined_game_second")
    private Long joinedGameSecond;

    /** 状态：active 在组 / left 离开 / expelled 被逐 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (role == null || role.isBlank()) role = "member";
        if (influence == null) influence = 0.0;
        if (status == null || status.isBlank()) status = "active";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
