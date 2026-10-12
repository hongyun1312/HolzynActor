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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 行为多样性注入记录实体。
 * <p>职责：承载行为多样性注入器（BehaviorDiversityService）每次注入的 diversity_context
 * 记录——三类噪声（偏好波动 OCEAN / 情绪干扰 PAD / 社会参照组+关系）作用于特殊 NPC 的
 * AI 决策路径（AgentRuntime.resolveDecision 行动选择前），记录可回放可关闭：</p>
 * <ul>
 * <li><b>channel</b>：preference 偏好波动 / emotion 情绪干扰 / social 社会参照（可逗号多通道）；</li>
 * <li><b>strength</b>：注入强度（0~1；幅度受人格稳定度钳制 + 衰减归零）；</li>
 * <li><b>source</b>：可解释来源（如「OCEAN openness 高+今日饥饿」「PAD 愉悦 -0.4 偏离基线」
 * 「同组多数囤粮从众」）；</li>
 * <li><b>expires_game_second</b>：噪声到期（随游戏时间衰减回落 0，λ 可配）；</li>
 * <li><b>context_json</b>：实际注入决策情境的段文本快照（调试面板回放用）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_macro_diversity",
        indexes = {
                @Index(name = "idx_macro_diversity_proj", columnList = "project_id"),
                @Index(name = "idx_macro_diversity_actor", columnList = "project_id,actor_ref")
        })
public class ActorMacroDiversity {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 统一角色引用（special:{id}） */
    @Column(name = "actor_ref", nullable = false, length = 50)
    private String actorRef;

    /** 游戏日（记录日） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 噪声通道（preference/emotion/social，逗号分隔多通道） */
    @Column(name = "channel", nullable = false, length = 50)
    private String channel;

    /** 注入强度（0~1） */
    @Column(name = "strength", nullable = false)
    private Double strength = 0.0;

    /** 可解释来源文本 */
    @Column(name = "source", length = 255)
    private String source;

    /** 噪声到期游戏秒 */
    @Column(name = "expires_game_second")
    private Long expiresGameSecond;

    /** 实际注入决策情境的段文本快照 */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /** 贯通决策 trace_id */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (strength == null) strength = 0.0;
        if (channel == null || channel.isBlank()) channel = "preference";
    }
}
