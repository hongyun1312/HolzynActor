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

package com.holzyn.actor.domain.growth.milestone;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发育里程碑/人生大事实体，对应表 actor_growth_milestone。
 * <p>只增不改：阶段跨越（含阶段叙事）、初潮/变声等发育节点、成婚、生育、重病、丧偶、
 * 死亡等人生大事各记一行。承载：{@code kind}（stage_transition / death / birth / marker…）、
 * 标题与详情（AI 阶段叙事产物或程序化文案）、前后阶段。</p>
 * <p>范围内写入点：阶段跨越（{@code DevelopmentalStageService.checkTransitions}）、
 * 死亡（{@code DevelopmentalStageService.executeDeath}）；婚育等由 续写。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_milestone", indexes = {
        @Index(name = "idx_growth_milestone_ref", columnList = "project_id, npc_ref"),
        @Index(name = "idx_growth_milestone_game", columnList = "game_second")
})
public class GrowthMilestone {

    /** 里程碑类型：阶段跨越 */
    public static final String KIND_STAGE_TRANSITION = "stage_transition";
    /** 里程碑类型：死亡 */
    public static final String KIND_DEATH = "death";
    /** 里程碑类型：出生 */
    public static final String KIND_BIRTH = "birth";
    /** 里程碑类型：发育节点（初潮/变声等，程序化/AI 补） */
    public static final String KIND_MARKER = "marker";

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

    /** 类型：stage_transition / death / birth / marker */
    @Column(length = 24)
    private String kind;

    /** 标题（如「进入老年期」） */
    @Column(length = 100)
    private String title;

    /** 详情（AI 阶段叙事或程序化文案） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 阶段变更前（可空） */
    @Column(name = "stage_from", length = 16)
    private String stageFrom;

    /** 阶段变更后（可空） */
    @Column(name = "stage_to", length = 16)
    private String stageTo;

    /** 重要度 1-5（对齐事件重要度口径；阶段跨越 3、死亡 5） */
    private Integer importance = 3;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (kind == null) kind = KIND_MARKER;
        if (importance == null) importance = 3;
    }
}
