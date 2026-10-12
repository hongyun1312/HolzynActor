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

package com.holzyn.actor.domain.agent.runtime.entity;

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
 * 决策与协调 轻量前瞻推演记录实体（表 actor_foresight_record，）。
 * <p>职责：记录每次「前瞻推演」触发与产出——触发判定（刺激类型/事件重要度/紧急度/场景关键词）、
 * 注入的宏观快照摘要、AI 输出的候选行动（candidates[]）与最终行动（final），供调试前端回放与
 * 未来 行为质量评估采样。</p>
 * <p>语义：一行=一次前瞻会话（STIMULUS 即时决策触发且判定命中）；AI 未按要求输出候选时
 * success=false 记录（失败可观测）。</p>
 */
@Data
@Entity
@Table(name = "actor_foresight_record",
        indexes = @Index(name = "idx_foresight_proj_ref", columnList = "project_id,npc_ref"))
public class ActorForesightRecord {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 触发角色引用（special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 触发角色名快照（可空） */
    @Column(name = "npc_name", length = 64)
    private String npcName;

    /** 触发游戏秒（引擎推进位置） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 触发原因文本（如「事件重要度=5」/「场景关键词=摊位」/「urgency≥2」） */
    @Column(name = "trigger_reason", length = 200)
    private String triggerReason;

    /** 刺激文本摘录（触发当时的情境刺激，截断） */
    @Column(name = "situation_excerpt", columnDefinition = "TEXT")
    private String situationExcerpt;

    /** 注入的宏观快照摘要（粮价/库存/民怨/议题/势力，截断；无宏观数据=空） */
    @Column(name = "macro_summary", columnDefinition = "TEXT")
    private String macroSummary;

    /** 候选行动 JSON（candidates:[{action,consequence}]，AI 输出解析后落库；未输出=[]） */
    @Column(name = "candidates_json", columnDefinition = "TEXT")
    private String candidatesJson;

    /** 最终行动文本（决策 action；AI 未输出候选时仍记最终行动） */
    @Column(name = "final_action", columnDefinition = "TEXT")
    private String finalAction;

    /** 是否产出候选（true=AI 按要求输出 candidates；false=失败/未输出——可观测） */
    @Column(name = "success")
    private Boolean success = false;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (success == null) success = false;
        if (candidatesJson == null || candidatesJson.isBlank()) candidatesJson = "[]";
    }
}
