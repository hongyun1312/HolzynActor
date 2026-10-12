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
 * Prompt 模板实体，对应表 actor_prompt_template。
 * <p>职责：存储可编辑的 AI Prompt 模板（角色卡生成/对话系统/群聊编排/世界事件/行动生成），
 * 支持项目级/用户级覆盖：user_id=0 表示内置模板（种子初始化），user_id>0 表示用户覆盖，
 * project_id 非空表示项目级覆盖（随 .holzyn 包导入导出）；
 * 解析规则：项目覆盖 > 用户覆盖 > 内置；重置即删除覆盖行回退低一级。
 * 唯一约束 (user_id, project_id, code) 保证同一归属同一模板编码只有一条。</p>
 */
@Data
@Entity
@Table(name = "actor_prompt_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_template_user_project_code", columnNames = {"user_id", "project_id", "code"}))
public class ActorPromptTemplate {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户：0=内置模板，>0=用户覆盖（关联 sys_user.id） */
    @Column(name = "user_id", nullable = false)
    private Long userId = 0L;

    /** 项目 ID（NULL=用户覆盖/内置；非空=项目级覆盖） */
    @Column(name = "project_id")
    private Long projectId;

    /** 模板编码（character_card_gen / dialog_system / group_orchestrator / world_event / action_gen） */
    @Column(nullable = false, length = 50)
    private String code;

    /** 模板名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 模板内容（占位符 {{world_setting}} {{character_json}} 等） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String template;

    /** 系统提示词（随模板落库，代码不再硬编码 Prompt 文本；可空=无系统提示词） */
    @Column(name = "system_message", columnDefinition = "TEXT")
    private String systemMessage;

    /** 模板版本 */
    private Integer version = 1;

    /** 是否启用：0否/1是 */
    private Integer enabled = 1;

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
        if (userId == null) userId = 0L;
        if (version == null) version = 1;
        if (enabled == null) enabled = 1;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}