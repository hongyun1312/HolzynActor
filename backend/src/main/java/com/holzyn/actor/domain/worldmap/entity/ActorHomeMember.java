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

package com.holzyn.actor.domain.worldmap.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 住户实体（位置移动与地图生成系统 · M1，表 actor_home_member）——谁住在哪间住所。
 * <p>职责：记录「角色 → 住所」的居住关系（一间住所可住多人；每角色恰在一间住所的 members 中，
 * 唯一约束 (project_id, character_ref)）。role 表达家中身份（户主/配偶/子女/亲属/同住），
 * room_label 为个人房间（「正房东间」）。</p>
 * <p>后期端点：搬家 = 更新本行 home_id；新建住所 = 新住所行 + 新住户行。</p>
 */
@Data
@Entity
@Table(name = "actor_home_member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_home_member_project_ref", columnNames = {"project_id", "character_ref"})
})
public class ActorHomeMember {

    /** 户主标记：是 */
    public static final int OWNER_YES = 1;

    /** 户主标记：否 */
    public static final int OWNER_NO = 0;

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属住所 ID（actor_npc_home.id） */
    @Column(name = "home_id", nullable = false)
    private Long homeId;

    /** 角色统一引用（npc:{id}，特殊/普通同构；唯一 (project_id, character_ref)） */
    @Column(name = "character_ref", length = 50, nullable = false)
    private String characterRef;

    /** 是否户主（0/1；一间住所恰一个户主） */
    @Column(name = "is_owner")
    private Integer isOwner = OWNER_NO;

    /** 家中身份（户主 / 配偶 / 子女 / 亲属 / 同住） */
    @Column(length = 30)
    private String role;

    /** 个人房间（「正房东间」「西厢」） */
    @Column(name = "room_label", length = 100)
    private String roomLabel;

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
        if (isOwner == null) isOwner = OWNER_NO;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
