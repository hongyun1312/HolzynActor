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

package com.holzyn.actor.domain.npc.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脑力配置实体，对应表 actor_profile_brain（九模块规范化重构 原 actor_agent_profile，阶段 C BrainProfile，档案模块）。
 * <p>职责：承载每个智能体（特殊 NPC / 普通 NPC）的<b>脑力配置</b>——系统框架 §2.4
 * 权威字段：agent_type（special/ordinary）+ agent_ref_id（特殊=actor_character.id / 普通=
 * actor_ordinary_npc.id）构成唯一键；brain_level 决定跑什么管线（full 全六组件 / light 感知+
 * 思考[廉价]+行动、关 planning/reflection / programmatic 纯程序化 0 AI 成本）；model_tier 决定
 * 模型档位（premium/standard/auto， 映射 API 配置分组）；decision_frequency 决定决策频率
 * （realtime/per_day/per_interval）；组件级开关 thinking/planning/reflection_enabled；
 * memory_budget / ai_budget_per_tick 为 null 时继承全局/项目预算。</p>
 * <p>默认值：特殊→full/auto(premium)/realtime（planning/reflection 开）、普通→light/auto(standard)/
 * per_interval（关），存量数据启动幂等补行（{@code BrainProfileBackfillRunner}）。 只建表与补行，
 * 作为<b>权威配置源</b>在 / 接入六组件分派（WorldEngine 注释已预留「脑力分层留 接入」）。</p>
 */
@Data
@Entity
@Table(name = "actor_profile_brain", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_profile_ref", columnNames = {"agent_type", "agent_ref_id"})
})
public class ActorAgentProfile {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 智能体类型：special 特殊 / ordinary 普通（唯一键之一） */
    @Column(name = "agent_type", nullable = false, length = 10)
    private String agentType;

    /** 智能体引用 ID：特殊=actor_character.id / 普通=actor_ordinary_npc.id（唯一键之二） */
    @Column(name = "agent_ref_id", nullable = false)
    private Long agentRefId;

    /** 脑力档位：full 全六组件 / light 感知+思考[廉价]+行动（关 planning/reflection）/ programmatic 纯程序化 0 AI 成本 */
    @Column(name = "brain_level", nullable = false, length = 20)
    private String brainLevel = "full";

    /** 模型档位：premium / standard / auto（映射 API 配置分组；默认 auto=按 agent_type 解析） */
    @Column(name = "model_tier", nullable = false, length = 20)
    private String modelTier = "auto";

    /** 决策频率：realtime / per_day / per_interval */
    @Column(name = "decision_frequency", nullable = false, length = 20)
    private String decisionFrequency = "realtime";

    /** 思考组件开关（0/1；light 档默认 1=仍走廉价思考） */
    @Column(name = "thinking_enabled")
    private Integer thinkingEnabled = 1;

    /** 规划组件开关（0/1；light 档默认 0=关 planning） */
    @Column(name = "planning_enabled")
    private Integer planningEnabled = 0;

    /** 反思组件开关（0/1；light 档默认 0=关 reflection） */
    @Column(name = "reflection_enabled")
    private Integer reflectionEnabled = 0;

    /** 反思节奏计数（/R4 新增）：最近一次反思所在的游戏日编号（floorDiv(gameHour,24)）；用于跨日重置计数 */
    @Column(name = "last_reflect_game_day")
    private Long lastReflectGameDay;

    /** 反思节奏计数（/R4 新增）：当前游戏日已反思次数（每角色每游戏日 ≤2 次，防自强化叙事，R4） */
    @Column(name = "reflect_count_today")
    private Integer reflectCountToday = 0;

    /** 记忆预算覆盖（null=继承全局 holzyn.actor.memory.budget） */
    @Column(name = "memory_budget")
    private Integer memoryBudget;

    /** 个人每 tick AI 预算（null=继承项目/全局 holzyn.actor.agent.budget-per-tick） */
    @Column(name = "ai_budget_per_tick")
    private Integer aiBudgetPerTick;

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
        if (brainLevel == null) brainLevel = "full";
        if (modelTier == null) modelTier = "auto";
        if (decisionFrequency == null) decisionFrequency = "realtime";
        if (thinkingEnabled == null) thinkingEnabled = 1;
        if (planningEnabled == null) planningEnabled = 0;
        if (reflectionEnabled == null) reflectionEnabled = 0;
        if (reflectCountToday == null) reflectCountToday = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
