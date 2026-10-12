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

package com.holzyn.actor.domain.crowd.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 普通型 NPC 项目级调度运行时实体，对应表 actor_crowd_runtime。
 * <p>职责：承载「普通人群重构后」的<b>项目级</b>调度状态（不再有人群组概念）——
 * 每个项目一行：是否参与定时调度（enabled）、上次调度时间、最近一次集体调度/环境快照
 * （latest_summary，供对话/行动环境注入）。
 * <b>分类体系重构</b>：新增主/次分类字段（primary_field/secondary_field）——
 * AI 依据世界观从 归属/职业/种族 中选出 2 个最适合作人群分类的字段（一主一次），
 * 供 AI 调度的「人群分组」聚合与统计展示使用。</p>
 * <p>唯一约束 (project_id)：一项目一行；定时任务按 enabled=1 推进该项目全部普通型 NPC 状态机。</p>
 * <p><b>口径</b>：本表只承载<b>项目级调度配置与快照</b>（开关/主次分类字段/
 * 上次调度时刻/环境摘要），<b>不承载</b>任何 NPC 运行态——"普通型 NPC"已是统一主表
 * {@code actor_profile} 中 {@code type='common'} 的行，作息运行态（state/last_action/daily_plan_json）
 * 权威在 {@code actor_state}（访问器 {@code NpcActivityStateService}）。本表字段与实体无需随统一主表改动。</p>
 */
@Data
@Entity
@Table(name = "actor_crowd_runtime",
        uniqueConstraints = @UniqueConstraint(name = "uk_crowd_runtime_project", columnNames = "project_id"))
public class ActorCrowdRuntime {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id，数据隔离，一项目一行） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 是否参与定时集体调度：0否/1是 */
    private Integer enabled = 0;

    /**
     * 主分类字段（AI 选出：race/affiliation/occupation，用于人群分组/统计/调度）
     * <p><b>阶段④ 加宽</b>：20 → 30。理由同 {@code ActorNpcFieldDict.field}——
     * {@code HolzynImportService} 导入 {@code actor/crowd_runtime.json} 时原样落库、无归一无截断。</p>
     */
    @Column(name = "primary_field", length = 30)
    private String primaryField;

    /**
     * 次分类字段（AI 选出：race/affiliation/occupation，与主字段不同）
     * <p><b>阶段④ 加宽</b>：20 → 30，同 {@link #primaryField}。</p>
     */
    @Column(name = "secondary_field", length = 30)
    private String secondaryField;

    /** 上次集体行动调度时间（定时任务幂等依据） */
    @Column(name = "last_schedule_at")
    private LocalDateTime lastScheduleAt;

    /** 最近一次集体行动快照/环境摘要（供对话/行动注入） */
    @Column(name = "latest_summary", columnDefinition = "TEXT")
    private String latestSummary;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充创建/更新时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
