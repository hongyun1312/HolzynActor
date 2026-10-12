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

package com.holzyn.actor.domain.growth.mask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社会面具运行态实体，对应表 actor_growth_mask。
 * <p><b>运行态面具权威位</b>（对齐门控表「表优先 + 档案兜底」范式）：档案 ⑪ 维
 * （{@code persona_json.dims.mask}）是<b>预设基线</b>（开发者/AI 建档），本表承载<b>运行期演化值</b>——
 * 面具强度随破绽事件松动（−0.05）或高频社交无破绽固化（+0.05，封顶预设值）；
 * 消费方（{@code MaskService}）取「本表优先、档案兜底」的生效值。</p>
 * 一行一 NPC 一情境码（before_authority / before_partner / before_stranger / before_peer /
 * before_child / before_rival / public_place / private_place）。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_mask", uniqueConstraints = {
        @UniqueConstraint(name = "uk_growth_mask_ref", columnNames = {"project_id", "npc_ref", "context_code"})
}, indexes = {
        @Index(name = "idx_growth_mask_ref", columnList = "project_id, npc_ref")
})
public class ActorGrowthMask {

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

    /**
     * 情境码（before_authority / before_partner / before_stranger / before_peer / before_child /
     */
    @Column(name = "context_code", length = 24, nullable = false)
    private String contextCode;

    /** 预设抑制强度（0-1，档案 ⑪ 维基线；演化固化时作为封顶值） */
    @Column(name = "preset_suppression")
    private Double presetSuppression;

    /** 当前生效抑制强度（0-1，本表权威；演化只改本列） */
    @Column(name = "effective_suppression")
    private Double effectiveSuppression;

    /** 允许表达清单（JSON 数组字符串；演化可扩充/收窄） */
    @Column(name = "allowed_json", columnDefinition = "TEXT")
    private String allowedJson;

    /** 禁止表达清单（JSON 数组字符串） */
    @Column(name = "forbidden_json", columnDefinition = "TEXT")
    private String forbiddenJson;

    /** 最近一次破绽命中时刻（游戏秒；破绽演化冷却基准） */
    @Column(name = "last_breach_game_second")
    private Long lastBreachGameSecond;

    /** 最近一次演化时刻（游戏秒） */
    @Column(name = "last_shift_game_second")
    private Long lastShiftGameSecond;

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
        if (effectiveSuppression == null) effectiveSuppression = presetSuppression;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
