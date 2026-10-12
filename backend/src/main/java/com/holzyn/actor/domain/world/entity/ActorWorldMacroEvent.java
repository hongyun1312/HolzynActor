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
 * 宏观社会演化 · 宏观事件实体。
 * <p>职责：承载宏观层产生的结构化事件——触发时间/类型/指标变化/严重度/叙事/来源（规则或 AI）。
 * 与 actor_event（世界事件总线时间线）的关系：每次宏观事件落本表 + 经总线发布一条
 * kind=macro 的 actor_event（幂等键 macro:{projectId}:{gameDay}:{eventId}），后者进入
 * 时间线/SSE/编年史即时提炼/NPC 即时决策刺激链路（回灌侧复用既有设施）。</p>
 * <p>数据语义（设计方案 §六 回灌 Schema）：
 * ① macro_type 五态：population/economy/faction/culture/tech；
 * ② severity 严重度 1-5（阈值分级：重大变化才触发 AI 叙事，常规走规则模板）；
 * ③ old_value/new_value/magnitude 记录指标变化（粮价波动幅度等，阈值判定依据）；
 * ④ source 两态：rule（规则/模板描述）/ ai（阈值触发 LLM 叙事）；source_key 幂等。</p>
 */
@Data
@Entity
@Table(name = "actor_world_macro_event",
        indexes = {
                @Index(name = "idx_macro_event_proj_day", columnList = "project_id,game_day"),
                @Index(name = "idx_macro_event_proj_type", columnList = "project_id,macro_type")
        })
public class ActorWorldMacroEvent {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日序号（触发日） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 触发时的游戏秒（审计/回放） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 宏观子系统：population/economy/faction/culture/tech */
    @Column(name = "macro_type", nullable = false, length = 20)
    private String macroType;

    /**
     * 事件类型（丰年/歉收/粮价暴涨/商队到来/钱庄挤兑/瘟疫减员/流民涌入/迁徙潮/
     * 宗门大会/家族联姻/家族决裂/官府新政/势力易主/舆论转向…，规则引擎产出）
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** 事件标题（AI 叙事标题优先，规则兜底=事件类型；供时间线/日报展示） */
    @Column(length = 200)
    private String title;

    /** 严重度 1-5（阈值分级：重大变化触发 AI 叙事） */
    @Column(nullable = false)
    private Integer severity = 3;

    /** 受影响指标名（grain_price/market_activity/influence/population…） */
    @Column(length = 50)
    private String indicator;

    /** 指标旧值 */
    @Column(name = "old_value")
    private Double oldValue;

    /** 指标新值 */
    @Column(name = "new_value")
    private Double newValue;

    /** 变化幅度（|new-old|/old，阈值判定依据；无 old 时用 severity 直接定级） */
    private Double magnitude;

    /** 叙事文本（规则模板或 AI 生成） */
    @Column(columnDefinition = "TEXT")
    private String narrative;

    /** 来源：rule（规则/模板描述）/ ai（阈值触发 LLM 叙事） */
    @Column(nullable = false, length = 10)
    private String source = "rule";

    /** 幂等键（macro:{projectId}:{gameDay}:{eventType}:{seq}） */
    @Column(name = "source_key", length = 100)
    private String sourceKey;

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
        if (severity == null) severity = 3;
        if (source == null || source.isBlank()) source = "rule";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
