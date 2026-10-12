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

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界观设定实体，对应表 actor_world_setting。
 * <p>职责：存储项目的世界观设定——结构化字段（题材/时代/地理/势力/规则/文化/历史）
 * 与自由长文本（freeText，作为后续知识库注入源），支持按版本迭代。</p>
 */
@Data
@Entity
@Table(name = "actor_world_setting")
public class ActorWorldSetting {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 版本号（同一项目可多版本，保存时自增） */
    private Integer version = 1;

    /** 世界观名称 */
    @Column(length = 100)
    private String name;

    /** 题材（奇幻/科幻/都市/历史等） */
    @Column(length = 50)
    private String genre;

    /** 时代背景 */
    @Column(length = 50)
    private String era;

    /** 地理/地图设定 */
    @Column(columnDefinition = "TEXT")
    private String geography;

    /** 势力/阵营 */
    @Column(columnDefinition = "TEXT")
    private String factions;

    /** 规则体系（魔法/科技/规则） */
    @Column(name = "magic_system", columnDefinition = "TEXT")
    private String magicSystem;

    /** 文化/风俗 */
    @Column(columnDefinition = "TEXT")
    private String culture;

    /** 历史背景 */
    @Column(columnDefinition = "TEXT")
    private String history;

    /** 完整世界观自由文本（知识库注入源） */
    @Column(name = "free_text", columnDefinition = "LONGTEXT")
    private String freeText;

    /**
     * 世界常识摘要（AI 生成的 300-500 字精炼常识，V2.0 段 1A 稳定世界观注入源）。
     * <p>定位：该世界角色都知道的世界常识（世界格局/主要聚居地/势力与规则/日常文化风俗的
     * 精炼总览），不罗列全部设定、不截断原文——由 {@code WorldCommonKnowledgeService} 在
     * worldgen 落库 / 手动保存后 / 定期扫描时依最新世界观生成并更新；空=未生成，
     * {@code AiContextInjector.worldBrief} 回退结构化字段截断拼装。</p>
     */
    @Column(name = "common_knowledge", columnDefinition = "TEXT")
    private String commonKnowledge;

    /** 世界常识摘要生成时间（定期扫描据此判断世界观更新后是否需重新生成；空=从未生成） */
    @Column(name = "common_knowledge_at")
    private LocalDateTime commonKnowledgeAt;

    /** 状态：0草稿/1生效 */
    private Integer status = 0;

    /**
     * 来源 worldgen 任务 ID（：工作流世界观生成「确认落库」时写入，供世界详情展示
     * 「来源=worldgen + 可回看」标记；手动/解析/导入保存会清空——标记当前版本世界观来源）
     */
    @Column(name = "worldgen_task_id")
    private Long worldgenTaskId;

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
        if (version == null) version = 1;
        if (status == null) status = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}