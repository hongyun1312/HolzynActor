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
 * 宏观社会演化 · 二阶涌现事件实体。
 * <p>职责：承载二阶涌现检测（MacroEmergenceService）检出的「同类行为聚集」事件——同一游戏
 * 日内 3 个以上不同角色出现同类经济/社交/迁徙/囤积/抗议行为 → 集体现象（涌现事件），
 * 赋予比单点入账更高的宏观影响权重（weight = memberCount × typeBase），触发 AI 叙事并进入
 * 世界帧/编年史/信息扩散/回灌链路：</p>
 * <ul>
 * <li><b>emergence_type</b>：hoarding 囤积 / migration 迁徙 / protest 抗议 /
 * trading_spree 交易潮 / social_wave 社交潮 / fleeing 逃离 / rest_break 集体休整…；</li>
 * <li><b>behavior_class</b>：行为类别（按 action_type/target_macro+indicator 归一）；</li>
 * <li><b>evidence_json</b>：参与行为摘要（npcRef+delta+day 数组，可审计）；</li>
 * <li><b>cooled_until_day</b>：冷却至某游戏日（同行为类冷却 5 日，防重复误报）；</li>
 * <li><b>source</b>：rule 规则文本 / ai AI 叙事（scene=macro_emergence，预算门控失败回退）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_macro_emergence_event",
        indexes = {
                @Index(name = "idx_emergence_proj", columnList = "project_id"),
                @Index(name = "idx_emergence_proj_day", columnList = "project_id,game_day"),
                @Index(name = "idx_emergence_type", columnList = "project_id,emergence_type")
        })
public class ActorMacroEmergenceEvent {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日（检测日=聚集行为发生日） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 游戏秒（检测时刻） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 涌现类型（hoarding/migration/protest/trading_spree/social_wave/fleeing/rest_break…） */
    @Column(name = "emergence_type", nullable = false, length = 30)
    private String emergenceType;

    /** 行为类别（按 action_type/target_macro+indicator 归一） */
    @Column(name = "behavior_class", nullable = false, length = 30)
    private String behaviorClass;

    /** 参与成员数 */
    @Column(name = "member_count", nullable = false)
    private Integer memberCount = 0;

    /** 影响权重（≥1：参与人数 × 基础权重） */
    @Column(name = "weight", nullable = false)
    private Double weight = 1.0;

    /** 参与行为摘要 JSON（npcRef+delta+day 数组） */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    /** AI 叙事（source=ai）或规则文本（source=rule） */
    @Column(name = "narrative", columnDefinition = "TEXT")
    private String narrative;

    /** 来源：rule 规则文本 / ai AI 叙事 */
    @Column(name = "source", nullable = false, length = 10)
    private String source = "rule";

    /** 严重度（3-5，按规模/类型） */
    @Column(name = "severity", nullable = false)
    private Integer severity = 3;

    /** 冷却至某游戏日（同行为类防重复误报） */
    @Column(name = "cooled_until_day")
    private Long cooledUntilDay;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (memberCount == null) memberCount = 0;
        if (weight == null) weight = 1.0;
        if (severity == null) severity = 3;
        if (source == null || source.isBlank()) source = "rule";
    }
}
