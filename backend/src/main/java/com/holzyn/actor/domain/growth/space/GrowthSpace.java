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
 * 此刻可发生空间实体，对应表 actor_growth_space。
 * <p><b>一行一 NPC</b>（project_id + npc_ref 唯一），承载该 NPC「此刻」的可发生空间：
 * {@code hard_gate_json}（程序化硬门控结果：允许能力码/禁止行为/生理越界/道德红线，由
 * {@code DevelopmentalStageService.gate} 唯一策略源产出）+ {@code space_json}（四段式 AI 空间：
 * inner 内心 / possible_states 可发生状态 / possible_actions 可发生行为 / forbidden 不可发生）。</p>
 * <p><b>生命周期</b>：事件驱动重算（触发码 {@link GrowthSpaceTrigger}）+ 定期兜底
 * （focus 每 1 游戏小时 / ambient 每 6 游戏小时）+ TTL 失效（{@code ttl_game_second}）；
 * 消费（决策/对话）时若 TTL 未过直接复用，过期/缺失才重算（对齐设计 §5.3「分层缓存 L3」）。
 * 降档（LOD dormant）时 {@code source=programmatic} 由程序化兜底空间工厂产出，零 AI。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_space", uniqueConstraints = {
        @UniqueConstraint(name = "uk_growth_space_ref", columnNames = {"project_id", "npc_ref"})
}, indexes = {
        @Index(name = "idx_growth_space_ttl", columnList = "ttl_game_second")
})
public class GrowthSpace {

    /** 空间来源：AI 生成 */
    public static final String SOURCE_AI = "ai";
    /** 空间来源：程序化兜底（LOD 降档 / AI 失败，零 AI） */
    public static final String SOURCE_PROGRAMMATIC = "programmatic";

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

    /** 重算触发码（GrowthSpaceTrigger.name()：stage_transition/physio_breach/…/periodic） */
    @Column(name = "trigger_code", length = 32)
    private String triggerCode;

    /** 程序化硬门控结果 JSON（DevelopmentalStageService.gate 产出） */
    @Column(name = "hard_gate_json", columnDefinition = "TEXT")
    private String hardGateJson;

    /** 四段式 AI 空间 JSON（inner/possible_states/possible_actions/forbidden；程序化兜底同构） */
    @Column(name = "space_json", columnDefinition = "TEXT")
    private String spaceJson;

    /** 生成时所处阶段（GrowthStage.name()，审计） */
    @Column(length = 16)
    private String stage;

    /** 生成时 PAD 快照 JSON（{pleasure,arousal,dominance}，审计；可空） */
    @Column(name = "pad_snapshot", columnDefinition = "TEXT")
    private String padSnapshot;

    /** 来源：ai / programmatic（LOD 降档时程序化） */
    @Column(length = 16)
    private String source = SOURCE_AI;

    /** 失效时刻（游戏秒；当前游戏时刻 ≥ 此值 → 消费前重算） */
    @Column(name = "ttl_game_second")
    private Long ttlGameSecond;

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
        if (source == null) source = SOURCE_AI;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
