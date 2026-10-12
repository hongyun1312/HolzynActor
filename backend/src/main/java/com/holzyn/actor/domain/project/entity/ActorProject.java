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

package com.holzyn.actor.domain.project.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目（作品）实体，对应表 actor_project。
 * <p>职责：承载用户创建的 NPC 角色世界项目（如某部小说/游戏世界观），
 * 包含项目名称、概要、封面与状态，按 userId 归属用户（数据隔离）。</p>
 */
@Data
@Entity
@Table(name = "actor_project")
public class ActorProject {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 UUID（跨电脑唯一，创建时生成永不改变；.holzyn 导入幂等检测依据） */
    @Column(name = "project_uid", length = 36)
    private String projectUid;

    /** 归属用户 ID（关联 sys_user.id，用于数据隔离） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 项目名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 项目编码（可选） */
    @Column(length = 50)
    private String code;

    /** 封面图 URL（可选） */
    @Column(name = "cover_url", length = 255)
    private String coverUrl;

    /** 项目概要（长文本） */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 状态：0草稿/1已生成角色卡/2进行中 */
    private Integer status = 0;

    /** 软删除标记：0正常/1已删除 */
    private Integer deleted = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充创建/更新时间、项目 UUID 与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = 0;
        if (deleted == null) deleted = 0;
        if (projectUid == null || projectUid.isBlank()) {
            projectUid = java.util.UUID.randomUUID().toString();
        }
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}