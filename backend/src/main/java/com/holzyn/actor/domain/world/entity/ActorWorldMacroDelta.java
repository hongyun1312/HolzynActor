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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · NPC 行动入账流水实体。
 * <p>职责：承载「微观 → 宏观」入账流水——NPC 行动（经商/打工/参战/布道/社交等）按
 * actionType 折算为宏观指标变化（targetMacro+indicator+delta），每日汇总进宏观快照。
 * 特殊 NPC 与普通 NPC 均入账。</p>
 * <p>数据语义（设计方案 §六 入账 Schema：{sourceNpcId, actionType, targetMacro, delta, worldTime}）：
 * ① target_macro 五态：economy/population/faction/culture/tech；
 * ② indicator 为宏观指标名（market_activity/grain_production/tax_revenue/currency_supply/
 * influence/issue_intensity/tech_medicine…）；
 * ③ ai_judged 0/1：该行是否由「重大行动 AI 判定」产出（0=规则映射）；
 * ④ source 两态：rule（规则映射）/ ai（AI 判定叠加）；行只增不改（审计流水），
 * 每日汇总按 game_day 聚合。</p>
 */
@Data
@Entity
@Table(name = "actor_world_macro_delta",
        indexes = {
                @Index(name = "idx_macro_delta_proj_day", columnList = "project_id,game_day"),
                @Index(name = "idx_macro_delta_proj_npc", columnList = "project_id,npc_ref")
        })
public class ActorWorldMacroDelta {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日序号（行动发生日，每日汇总依据） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 行动发生时的游戏秒（审计） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 行动者统一引用（special:{id} / crowd:{id}） */
    @Column(name = "npc_ref", length = 50)
    private String npcRef;

    /** 行动者名称快照（展示/审计兜底） */
    @Column(name = "npc_name", length = 100)
    private String npcName;

    /** 行动类型（决策 type 或人群驱动 actionType；custom=自由文本按关键词映射） */
    @Column(name = "action_type", length = 50)
    private String actionType;

    /** 行动文本（决策 action / 人群 lastAction，折算关键词匹配源） */
    @Column(name = "action_text", length = 255)
    private String actionText;

    /** 目标宏观子系统：economy/population/faction/culture/tech */
    @Column(name = "target_macro", nullable = false, length = 20)
    private String targetMacro;

    /**
     * 宏观指标名（market_activity/grain_production/tax_revenue/currency_supply/
     * influence/issue_intensity/tech_medicine…）
     */
    @Column(length = 50)
    private String indicator;

    /** 指标折算变化量（每日汇总后应用进快照） */
    @Column(nullable = false)
    private Double delta = 0.0;

    /** 是否 AI 判定产出（0=规则映射，1=重大行动 AI 判定叠加） */
    @Column(name = "ai_judged")
    private Integer aiJudged = 0;

    /** 来源：rule（规则映射）/ ai（AI 判定叠加） */
    @Column(nullable = false, length = 10)
    private String source = "rule";

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
        if (delta == null) delta = 0.0;
        if (aiJudged == null) aiJudged = 0;
        if (source == null || source.isBlank()) source = "rule";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
