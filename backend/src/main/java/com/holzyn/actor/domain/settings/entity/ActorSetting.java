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

package com.holzyn.actor.domain.settings.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局设置覆盖表实体，对应表 actor_setting。
 * <p>职责：以 key-value 形式承载<b>全局级</b>（不绑 project_id）运行时配置覆盖——当前用于
 * NPC 状态模块调参面板（NpcStateProperties 全量参数：衰减率/阈值/恢复量/情绪参数等）。
 * 生效值 = 覆盖值 ∪ application.yml 默认值（覆盖优先）；启动时从本表加载覆盖并应用到
 * {@code NpcStateProperties} bean，运行中保存即实时生效。</p>
 * <p>设计约定：键名 = 配置路径（如 {@code decay.hunger-per-hour}、
 * {@code urgency.threshold-strong}）；全局级；可单键重置 / 一键全部恢复默认。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§12 （衰减率/阈值调参）。
 * </p>
 */
@Data
@Entity
@Table(name = "actor_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uk_setting_key", columnNames = "setting_key")
})
public class ActorSetting {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设置键（唯一；配置路径即键名，如 decay.hunger-per-hour） */
    @Column(name = "setting_key", length = 120, nullable = false)
    private String settingKey;

    /** 设置值（字符串；按参数类型解析为 double/int/boolean） */
    @Column(name = "setting_value", length = 255)
    private String settingValue;

    /** 设置说明（调参面板展示用，可空） */
    @Column(name = "description", length = 255)
    private String description;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
