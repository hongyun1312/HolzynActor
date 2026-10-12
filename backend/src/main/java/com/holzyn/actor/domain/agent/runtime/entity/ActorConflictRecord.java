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
 * 决策与协调 意图冲突检测记录实体（表 actor_conflict_record，）。
 * <p>职责：记录每次冲突检测命中与处置——双方（当前行动者 vs 占用者/目标角色）、资源/地点键、
 * 冲突类型（同地点占用/目标角色正忙/资源占用）、双方优先级（重要度×urgency）、处置结果
 * （强占/协商/等待/改道）与协商产出，供调试前端回放与行为质量评估采样。</p>
 * <p>语义：C 层确定性冲突检测优先（同对象/同地点/同资源占用），
 * 冲突 → importance×urgency 优先级排序，高者先执行；预算内 LLM 二次协商（scene=negotiate），
 * 失败回退确定性等待/改道。</p>
 */
@Data
@Entity
@Table(name = "actor_conflict_record",
        indexes = @Index(name = "idx_conflict_proj_ref", columnList = "project_id,npc_ref"))
public class ActorConflictRecord {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 触发游戏秒 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 当前行动角色引用（special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 当前行动角色名快照 */
    @Column(name = "npc_name", length = 64)
    private String npcName;

    /** 冲突资源键（归一化目标地点/摆放名；目标角色时=目标角色名） */
    @Column(name = "resource_key", length = 200)
    private String resourceKey;

    /** 冲突资源展示名（目标地点/对象原文） */
    @Column(name = "resource_name", length = 200)
    private String resourceName;

    /** 冲突类型：location_occupied / object_busy / resource_occupied */
    @Column(name = "conflict_type", length = 30)
    private String conflictType;

    /** 对手角色引用（占用者/目标角色，special:{id}；可空） */
    @Column(name = "opponent_ref", length = 64)
    private String opponentRef;

    /** 对手角色名快照（可空） */
    @Column(name = "opponent_name", length = 64)
    private String opponentName;

    /** 当前行动方优先级（重要度×urgency；0=未算） */
    @Column(name = "my_priority")
    private Integer myPriority = 0;

    /** 对手优先级（重要度×urgency；0=未算） */
    @Column(name = "opponent_priority")
    private Integer opponentPriority = 0;

    /** 处置结果：takeover 强占 / negotiated 协商让步 / fallback_wait 确定性等待 / fallback_redirect 确定性改道 / proceed 不冲突 */
    @Column(name = "resolution", length = 30)
    private String resolution;

    /** 协商产出 JSON（scene=negotiate 的 AI 输出 {decision,action,target,reason}；未协商=空） */
    @Column(name = "negotiate_json", columnDefinition = "TEXT")
    private String negotiateJson;

    /** 行动变更前文本（原决策 action，截断） */
    @Column(name = "action_before", columnDefinition = "TEXT")
    private String actionBefore;

    /** 行动变更后文本（协商/兜底调整后的 action，截断） */
    @Column(name = "action_after", columnDefinition = "TEXT")
    private String actionAfter;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
