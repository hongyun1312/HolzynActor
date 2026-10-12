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
 * 阶段能力门控实体，对应表 actor_growth_stage_gate。
 * <p>一行 = 「某阶段 × 某能力」的门控声明：{@code allowed=1} 允许 / 0 禁止；{@code prompt_text}
 * 是注入 Prompt 的文案（如「此阶段不可进行重体力劳作」）。</p>
 * <p><b>存储形态</b>：建表 + 代码 seed（{@code GrowthStageGateService}
 * 启动时表为空写入默认行）；读取=表优先、代码常量兜底；调试页可热更新（不重启生效）。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_stage_gate", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stage_gate_stage_ability", columnNames = {"stage", "ability"})
}, indexes = {
        @Index(name = "idx_stage_gate_stage", columnList = "stage")
})
public class GrowthStageGate {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 阶段码（GrowthStage.name()） */
    @Column(length = 16, nullable = false)
    private String stage;

    /** 能力码（GrowthAbility.name()） */
    @Column(length = 32, nullable = false)
    private String ability;

    /** 是否允许：1=允许 / 0=禁止（硬门控，不可被 AI 覆盖） */
    private Integer allowed = 1;

    /** 注入 Prompt 的文案（如「此阶段不可进行重体力劳作」；空=不单独注入） */
    @Column(name = "prompt_text", length = 255)
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
        if (allowed == null) allowed = 1;
        if (sortOrder == null) sortOrder = 0;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
