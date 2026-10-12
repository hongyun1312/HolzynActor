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

package com.holzyn.actor.domain.growth.stage;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阶段行为约束实体，对应表 actor_growth_stage_constraint。
 * <p>一行 = 「某阶段 × 一条行为约束」：{@code kind} 区分硬约束（HARD，绝对禁止，进硬门控）
 * 与软约束（SOFT，受限/提示，进 Prompt 注入段）；{@code content} 是约束原文（门控判定用），
 * {@code prompt_text} 是注入 Prompt 的措辞（两者允许不同粒度）。</p>
 * <p>示例（ELDER）：HARD「不得进行重体力劳作（挑担/垦荒/伐木）」；SOFT「感官退化，夜间视物不清，
 * 不宜赶夜路」。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_stage_constraint", indexes = {
        @Index(name = "idx_stage_constraint_stage", columnList = "stage")
})
public class GrowthStageConstraint {

    /** 硬约束：绝对禁止（进硬门控 forbidden，不可被 AI 覆盖） */
    public static final String KIND_HARD = "HARD";
    /** 软约束：受限/提示（进 Prompt 注入段，AI 可感知但可酌情） */
    public static final String KIND_SOFT = "SOFT";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 阶段码（GrowthStage.name()） */
    @Column(length = 16, nullable = false)
    private String stage;

    /** 约束类型：HARD 硬约束 / SOFT 软约束 */
    @Column(length = 8, nullable = false)
    private String kind = KIND_SOFT;

    /** 约束原文（门控判定与调试展示用） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 注入 Prompt 的措辞（可空；为空时渲染期取 content） */
    @Column(name = "prompt_text", columnDefinition = "TEXT")
    private String promptText;

    /** 排序（调试页展示顺序） */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

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
        if (kind == null) kind = KIND_SOFT;
        if (sortOrder == null) sortOrder = 0;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
