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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目级季节骨架配置实体。
 * <p>职责：承载「项目级四季骨架」——每季节的基准温度区间/天气倾向/日出日落微调/色调
 * （config_json，结构与 {@link com.holzyn.actor.domain.world.service.SeasonService} 的
 * SeasonSkeleton 一致）；uk(project_id) 每项目恰 1 行，无行=使用内置默认骨架（内置为权威基线，
 * 用户 决策#6「季节按游戏月固定绑定 + 骨架内波动」，项目可覆盖骨架但不改变季节归属）。</p>
 * <p>设计对齐：《HolzynActor_记忆系统重构与世界时间季节系统_设计方案》§5.1（配置骨架
 * 新表 actor_world_climate_config 或并入项目设置——本实现取新表）+ §7 数据库设计汇总。</p>
 */
@Data
@Entity
@Table(name = "actor_world_climate_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_climate_config_project", columnNames = {"project_id"}))
public class ActorWorldClimateConfig {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID（uk，每项目恰 1 行） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /** 配置版本（每次 PUT +1） */
    @Column(name = "version")
    private Integer version = 1;

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
        if (version == null) version = 1;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
