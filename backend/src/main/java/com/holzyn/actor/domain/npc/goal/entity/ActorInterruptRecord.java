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

package com.holzyn.actor.domain.npc.goal.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 被打断任务记录实体。
 * <p>职责：承载「角色当前行动被刺激打断」的完整记录——打断时活动/地点/游戏时刻 + 恢复
 * 过期窗口 + 状态机（active/restored/adjusted/discarded）与消费时刻审计——供即时决策响应
 * 完成后按 {@code InterruptRecoveryService} 恢复判断（resume/adjust/abandon 三态）。</p>
 * <p>载体定稿：独立新表替代
 * 「actor_action_plan.status=paused + actor_sandbox_state 加列」的候选 A——因当前
 * actor_action_plan 生命周期为 planned→executing→done 同步完成，刺激到达时角色往往没有
 * 「执行中的计划行」，被打断的通常是 current_activity 文本 + 位置；独立表可承载
 * planId 可空场景 + 恢复条件/位置/过期/审计，语义完整（46→47 实体表）。</p>
 * <p>状态机：active（已记录待恢复）→ restored（AI 决定恢复原任务，已重新执行）/
 * adjusted（AI 决定调整为新任务，已执行）/ discarded（AI 决定放弃 / 共性框架兜底放弃 /
 * 过期替换）。consumed_at_game_second 记录处理时的游戏时刻（审计）。</p>
 */
@Data
@Entity
@Table(name = "actor_interrupt_record")
public class ActorInterruptRecord {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** NPC 类型：special / crowd */
    @Column(name = "npc_type", length = 20)
    private String npcType;

    /** NPC 引用：special:{id} / crowd:{id} */
    @Column(name = "npc_ref", length = 50, nullable = false)
    private String npcRef;

    /** 被打断计划 ID（可空：多数场景被打断的只是 current_activity 文本，无对应计划行） */
    @Column(name = "plan_id")
    private Long planId;

    /** 被打断时的活动描述（current_activity/action_text 快照） */
    @Column(name = "interrupted_activity", length = 255)
    private String interruptedActivity;

    /** 被打断时的地点（恢复时「回原地点继续原活动」的依据） */
    @Column(name = "interrupted_location", length = 100)
    private String interruptedLocation;

    /** 被打断时的游戏时刻（秒） */
    @Column(name = "interrupted_at_game_second")
    private Long interruptedAtGameSecond;

    /** 恢复过期游戏时刻（秒 = interruptedAt + expire 窗口；超期共性框架放弃恢复） */
    @Column(name = "expire_at_game_second")
    private Long expireAtGameSecond;

    /** 状态：active / restored / adjusted / discarded */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** 消费（处理）时的游戏时刻（秒，可空=未消费） */
    @Column(name = "consumed_at_game_second")
    private Long consumedAtGameSecond;

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
        if (status == null) status = "active";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
