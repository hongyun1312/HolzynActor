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

package com.holzyn.actor.domain.growth.space;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间历史日志实体，对应表 actor_growth_space_log。
 * <p>只增不改：每次空间生成（AI 或程序化）追加一行，承载「触发码 + 来源 + 产物」——
 * 供审计/回放/「空间内行动率」统计口径与调试页历史查看。历史行不做截断策略（空间生成
 * 频率受事件驱动 + 定期兜底约束，量级可控；如后续膨胀再补保留策略）。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_space_log", indexes = {
        @Index(name = "idx_growth_space_log_ref", columnList = "project_id, npc_ref"),
        @Index(name = "idx_growth_space_log_game", columnList = "game_second")
})
public class GrowthSpaceLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目归属（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 统一角色引用：npc:{actor_profile.id} */
    @Column(name = "npc_ref", length = 50, nullable = false)
    private String npcRef;

    /** 生成时的世界时刻（游戏秒） */
    @Column(name = "game_second", nullable = false)
    private Long gameSecond;

    /** 重算触发码（GrowthSpaceTrigger.name()） */
    @Column(name = "trigger_code", length = 32)
    private String triggerCode;

    /** 来源：ai / programmatic */
    @Column(length = 16)
    private String source;

    /** 生成时所处阶段（审计） */
    @Column(length = 16)
    private String stage;

    /** 四段式空间 JSON 全文（回放/审计用） */
    @Column(name = "space_json", columnDefinition = "TEXT")
    private String spaceJson;

    /** 硬门控结果 JSON（回放/审计用） */
    @Column(name = "hard_gate_json", columnDefinition = "TEXT")
    private String hardGateJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
