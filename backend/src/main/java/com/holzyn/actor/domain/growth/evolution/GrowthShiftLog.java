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

package com.holzyn.actor.domain.growth.evolution;

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
 * 人格偏移日志实体，对应表 actor_growth_shift_log。
 * <p><b>只增不改</b>：每次人格偏移（④ OCEAN / ⑤ 价值权重 / ⑪ 面具强度）落一行，
 * 记录<b>证据链</b>（evidence_json = 触发本次评估的记忆摘要/事件 + 证据强度）与
 * <b>AI 判定理由</b>（ai_reason），保证验收②「偏移日志可反查证据链」。</p>
 * <p><b>触发码</b>（{@code triggerCode}）：{@code cycle}（30 游戏日周期评估）/
 * {@code stage_transition}（阶段跨越强制）/ {@code major_event}（重大事件）/ {@code manual}（测试接口）。</p>
 * <p><b>源</b>（{@code source}）：{@code ai}（AI 判定偏移）/{@code programmatic}（AI 失败程序化兜底）/
 * {@code breach}（面具破绽程序化演化）。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_shift_log", indexes = {
        @Index(name = "idx_growth_shift_ref", columnList = "project_id, npc_ref"),
        @Index(name = "idx_growth_shift_game", columnList = "game_second")
})
public class GrowthShiftLog {

    /** 偏移维度：④ 气质与人格（OCEAN） */
    public static final String DIM_OCEAN = "ocean";
    /** 偏移维度：⑤ 价值观与动机（valueWeights） */
    public static final String DIM_VALUES = "values";
    /** 偏移维度：⑪ 社会面具（面具强度演化） */
    public static final String DIM_MASK = "mask";

    /** 触发码：30 游戏日周期评估 */
    public static final String TRIGGER_CYCLE = "cycle";
    /** 触发码：阶段跨越强制评估 */
    public static final String TRIGGER_STAGE_TRANSITION = "stage_transition";
    /** 触发码：重大事件（importance≥4 且涉及本人） */
    public static final String TRIGGER_MAJOR_EVENT = "major_event";
    /** 触发码：测试接口手动触发 */
    public static final String TRIGGER_MANUAL = "manual";
    /** 触发码：面具破绽程序化演化 */
    public static final String TRIGGER_BREACH = "breach";

    /** 偏移来源：AI 判定 */
    public static final String SOURCE_AI = "ai";
    /** 偏移来源：AI 失败程序化兜底 */
    public static final String SOURCE_PROGRAMMATIC = "programmatic";
    /** 偏移来源：面具破绽程序化演化 */
    public static final String SOURCE_BREACH = "breach";

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

    /** 发生时的世界时刻（游戏秒） */
    @Column(name = "game_second", nullable = false)
    private Long gameSecond;

    /** 触发码：cycle / stage_transition / major_event / manual / breach */
    @Column(name = "trigger_code", length = 24)
    private String triggerCode;

    /** 偏移维度：ocean / values / mask */
    @Column(length = 16)
    private String dim;

    /** 偏移字段（如 OCEAN 五维键 openness / valueWeights 键 宗族 / mask 情境码 before_authority） */
    @Column(name = "field", length = 64)
    private String field;

    /** 变更前值（0-1 数值序列化字符串，如 "0.6200"） */
    @Column(name = "old_value", length = 32)
    private String oldValue;

    /** 变更后值 */
    @Column(name = "new_value", length = 32)
    private String newValue;

    /** 偏移量（Δ = new − old；负数=下降） */
    private Double delta;

    /** 本次允许的阶段上限（该阶段单次偏移上限；截断后实际值 ≤ 此限） */
    @Column(name = "stage_cap", length = 16)
    private String stageCap;

    /** 证据链（JSON：触发时点记忆/事件摘要 + 证据强度 + 引用记忆 id 列表，验收② 反查用） */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    /** AI 判定理由（growth_persona_shift_eval 输出的 why；程序化来源为兜底说明） */
    @Column(name = "ai_reason", columnDefinition = "TEXT")
    private String aiReason;

    /** 来源：ai / programmatic / breach */
    @Column(length = 16)
    private String source;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (triggerCode == null) triggerCode = TRIGGER_CYCLE;
        if (source == null) source = SOURCE_AI;
    }
}
