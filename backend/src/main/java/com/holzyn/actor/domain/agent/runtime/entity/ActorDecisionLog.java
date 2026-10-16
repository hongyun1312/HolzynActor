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

package com.holzyn.actor.domain.agent.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策点台账实体，对应表 actor_decision_log（P0-03 决策源可追溯 · 度量基座）。
 * <p>职责：<b>每个决策点恰好一行</b>（角色 × 游戏小时 × 触发源的决策机会）——无论产出是
 * AI 决策 / 程序化兜底 / 预算耗尽降级 / 决策缺失，都按 {@code DecisionSource} 落一行，
 * 使「任意游戏日 AI/兜底/缺失 三类数量与占比之和 = 该日决策点总数」可精确统计（审计
 * P0-02 验收 1）。也是 P0-02 根治（决策 job 化作业队列）的未来基表。</p>
 * <p>写入方：{@code AgentRuntime} 决策循环（含 triggerAction 手动/评估路径）写 AI/兜底/
 * 预算降级；{@code WorldEngine} 决策失败写决策缺失。游戏时刻=决策感知时刻（gameSecond）。</p>
 */
@Data
@Entity
@Table(name = "actor_decision_log", indexes = {
        @Index(name = "idx_decision_log_project_day", columnList = "project_id, game_day"),
        @Index(name = "idx_decision_log_project_source", columnList = "project_id, decision_source")
})
public class ActorDecisionLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 角色 ID（决策当事人，可空=项目级决策） */
    @Column(name = "character_id")
    private Long characterId;

    /** 统一角色引用（special:{id}/crowd:{id}，可空） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 决策感知时刻（游戏秒，自纪元起；可空=无时刻上下文） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 游戏日（决策日，自纪元起 floorDiv(gameSecond,86400)；可空） */
    @Column(name = "game_day")
    private Long gameDay;

    /** 决策来源（VARCHAR(32) 存 {@code DecisionSource} 枚举名，非空） */
    @Column(name = "decision_source", nullable = false, length = 32)
    private String decisionSource;

    /** 决策行动文本摘要（AI 决策=行动描述；兜底=「继续当前行为」；缺失=空/原因摘要） */
    @Column(name = "action_text", length = 255)
    private String actionText;

    /** 触发源（engine_tick/manual/after_dialog/event/stimulus 等，可空） */
    @Column(name = "trigger_type", length = 20)
    private String triggerType;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
