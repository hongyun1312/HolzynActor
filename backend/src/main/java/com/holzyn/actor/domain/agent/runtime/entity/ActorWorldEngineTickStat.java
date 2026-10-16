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
 * 世界引擎 tick 预算统计实体，对应表 actor_world_engine_tick_stat（P0-03 度量基座）。
 * <p>职责：<b>每轮引擎推进持久化一条预算统计</b>——本 tick 的预算总量 / 实际 AI 消耗 /
 * 预算耗尽降级的决策数 / 各来源决策数。修「BudgetGuard 预算耗尽只在内存
 * （{@code endTick} 即 {@code remove}）→ 预算耗尽事实不可追溯」（审计 P0-03 修法 2：
 * 「持久化到 actor_world_engine 或新表 actor_world_engine_tick_stat」）。</p>
 * <p>写入方：{@code WorldEngine} 每轮推进（simulate 后、预算窗口关闭前）经
 * {@code DecisionLedgerService#persistTickStat}；gameSecond=本轮推进到的游戏时刻。</p>
 */
@Data
@Entity
@Table(name = "actor_world_engine_tick_stat", indexes = {
        @Index(name = "idx_tick_stat_project_day", columnList = "project_id, game_second")
})
public class ActorWorldEngineTickStat {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 本轮推进到的游戏时刻（游戏秒，自纪元起） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 本 tick AI 预算总量 */
    @Column(name = "budget_total")
    private Integer budgetTotal;

    /** 本 tick 实际 AI 预算消耗数（BudgetGuard.used，持久化防 endTick 后丢失） */
    @Column(name = "budget_used")
    private Integer budgetUsed;

    /** 本 tick 预算耗尽降级（BUDGET_EXHAUSTED）决策数（来自决策台账） */
    @Column(name = "exhausted_decisions")
    private Integer exhaustedDecisions;

    /** 本 tick AI 决策数（决策台账 AI/AI_RETRY 计数） */
    @Column(name = "ai_decisions")
    private Integer aiDecisions;

    /** 本 tick 程序化兜底决策数（FALLBACK_PROGRAMMATIC 计数，不含预算降级） */
    @Column(name = "fallback_decisions")
    private Integer fallbackDecisions;

    /** 本 tick 决策缺失数（DECISION_MISSING 计数） */
    @Column(name = "missing_decisions")
    private Integer missingDecisions;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
