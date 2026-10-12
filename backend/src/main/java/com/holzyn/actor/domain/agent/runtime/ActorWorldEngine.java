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

package com.holzyn.actor.domain.agent.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 世界引擎运行时实体，对应表 actor_world_engine（B3 世界推进引擎，domain/agent/runtime）。
 * <p>职责：承载每项目的世界引擎配置（每项目唯一行，懒创建）——
 * budgetPerTick（AI 预算，缺省沿用 5=每 tick AI 调用上限，用尽程序化不冻结，修）、
 * maxStepHours（单次步长封顶，缺省 24，防长时间离线后雪崩）、
 * lastAiBudgetResetAt（预算重置时刻，预留）与 flagsJson（扩展预留）。</p>
 * <p>预算/步长既是成本控制的唯一口径（替代旧「活跃窗口」冻结），也是 W-3 用量图可观测的
 * 项目级预算来源（actor_ai_call_log 已具备消耗记录）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_engine")
public class ActorWorldEngine {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID（唯一，uk_world_engine_project） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 每 tick AI 预算（缺省沿用 5：角色行动 + 世界事件润色的 AI 调用上限，用尽程序化兜底不冻结） */
    @Column(name = "budget_per_tick")
    private Integer budgetPerTick = 5;

    /** 单次推进步长封顶（游戏小时，缺省 24，防长时间离线后雪崩） */
    @Column(name = "max_step_hours")
    private Integer maxStepHours = 24;

    /** 世界运行模式（/ 新增，项目级三档：minimal 极简[省成本]/standard 普通[默认]/ultimate 极致[全量 AI]） */
    @Column(name = "run_mode", length = 20)
    private String runMode = "standard";

    /**
     * AI 强度（新增，项目级四档独立维度——与 runMode 正交可叠加[runMode 管引擎节奏、
     * 本列管智能体 AI 使用密度，设计 §8.2]）：programmatic 纯规则[零 AI]/light 轻 AI[关键点
     * 低预算]/full 标准双通道[默认]/max 全 AI 必调[无预算限制]；宏观层 AI 决策点全接入，
     * 微观层接入列为 阶段）
     */
    @Column(name = "ai_intensity", length = 20)
    private String aiIntensity = "full";

    /**
     * 实时决策激活：0=实时行为决策系统未激活——引擎跳过该项目
     * （世界暂停、不耗 AI，等同 world_running=0 语义）；1=手动常开（手动开关优先于页面自动激活）；
     * 页面自动激活态（沙盘页打开/演化会话进行中）由 RealtimeDecisionActivationService 内存态承载，
     * 本列仅持久化「手动常开」覆盖。存量行（NULL）视为 0=关（用户需求：后端启动不自动运行实时决策）。
     */
    @Column(name = "realtime_decision_enabled")
    private Integer realtimeDecisionEnabled = 0;

    /** 最近一次 AI 预算重置的真实时刻（预留：按 tick/按日重置策略审计用） */
    @Column(name = "last_ai_budget_reset_at")
    private LocalDateTime lastAiBudgetResetAt;

    /** 扩展预留 JSON（flags_json，如未来导演锁/节律参数） */
    @Column(name = "flags_json", columnDefinition = "TEXT")
    private String flagsJson;

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
        if (budgetPerTick == null) budgetPerTick = 5;
        if (maxStepHours == null) maxStepHours = 24;
        if (runMode == null) runMode = "standard";
        if (realtimeDecisionEnabled == null) realtimeDecisionEnabled = 0;
        if (aiIntensity == null) aiIntensity = "full";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
