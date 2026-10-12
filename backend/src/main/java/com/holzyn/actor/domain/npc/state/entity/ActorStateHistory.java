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

package com.holzyn.actor.domain.npc.state.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NPC 状态历史快照实体，对应表 actor_state_history。
 * <p>职责：在引擎逐游戏小时推进（{@code NpcStateService.advance}）与人群作息挂钩
 * （{@code onCrowdSchedule}）时，对每个 NPC 的状态行做<b>全字段快照</b>落库——
 * 为调试前端状态页/主前端角色页的「随时间曲线」提供可回溯历史（重启不丢）。</p>
 * <p>设计约定：</p>
 * <ul>
 * <li><b>采样时机</b>：逐游戏小时推进时采样（复用 advance/onCrowdSchedule 统一入口，
 * 与衰减节奏对齐；世界暂停 game_second 不推进 → 自然不采样）；</li>
 * <li><b>快照内容</b>：全内部字段（需求 9 项 + 心情 + 情绪 PAD/主导/强度 + urgency + 健康），
 * 与 actor_state 同构；</li>
 * <li><b>保留策略</b>：每项目每 NPC 最近 {@code history.max-per-npc}（默认 168 ≈ 7 游戏日）
 * 条，超出滚动删除最旧（由 NpcStateHistoryService 维护）；</li>
 * <li><b>幂等</b>：同一 (project_id, npc_ref, game_second) 唯一——同秒推进重复采样不产生重复行。</li>
 * </ul>
 * <p>本表为<b>只读历史</b>：不回写决策/衰减（actor_state 仍是唯一状态权威），仅供展示与曲线。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§10（前端展示-调试前端曲线）/ §12。
 * </p>
 */
@Data
@Entity
@Table(name = "actor_state_history", uniqueConstraints = {
        @UniqueConstraint(name = "uk_state_history_ref_second", columnNames = {"project_id", "npc_ref", "game_second"})
}, indexes = {
        @Index(name = "idx_state_history_ref", columnList = "project_id, npc_ref, game_second")
})
public class ActorStateHistory {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目归属（关联 actor_project.id，必填） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** NPC 类型：special / crowd（快照冗余，便于过滤） */
    @Column(name = "npc_type", length = 20)
    private String npcType;

    /** 统一角色引用：special:{id} / crowd:{id} */
    @Column(name = "npc_ref", length = 50)
    private String npcRef;

    // ==================== 生理需求（0-100，与 actor_state 同构） ====================

    /** 饥饿 0-100 */
    private Integer hunger;

    /** 口渴 0-100 */
    private Integer thirst;

    /** 精力 0-100 */
    private Integer energy;

    /** 膀胱/尿意 0-100 */
    private Integer bladder;

    /** 健康 0-100 */
    private Integer health;

    /** 心情 0-100 */
    private Integer mood;

    // ==================== 情绪（PAD + 主导 + 强度） ====================

    /** 情绪 PAD-愉悦（-1.00~1.00） */
    @Column(name = "emotion_pleasure")
    private Double emotionPleasure;

    /** 情绪 PAD-唤醒（-1.00~1.00） */
    @Column(name = "emotion_arousal")
    private Double emotionArousal;

    /** 情绪 PAD-支配（-1.00~1.00） */
    @Column(name = "emotion_dominance")
    private Double emotionDominance;

    /** 主导情绪标签（calm/happy/…/custom:*） */
    @Column(name = "dominant_emotion", length = 20)
    private String dominantEmotion;

    /** 情绪强度 0-100 */
    @Column(name = "emotion_intensity")
    private Integer emotionIntensity;

    // ==================== 扩展需求（0-100） ====================

    /** 清洁 0-100 */
    private Integer hygiene;

    /** 社交 0-100 */
    private Integer social;

    /** 趣味 0-100 */
    private Integer fun;

    /** 舒适 0-100 */
    private Integer comfort;

    /** 环境心情修正 -30~+30 */
    @Column(name = "env_modifier")
    private Integer envModifier;

    //

    /** 紧急级 0/1/2/3 */
    @Column(name = "urgency_level")
    private Integer urgencyLevel;

    /** 最紧急需求描述 */
    @Column(name = "urgency_text", length = 120)
    private String urgencyText;

    /** 快照对应的游戏时刻（秒，与 actor_state.game_second 同源；唯一约束成员） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 创建时间（采样落库时刻，真实时间） */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充创建时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
