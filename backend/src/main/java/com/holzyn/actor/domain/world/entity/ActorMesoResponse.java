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
 * 宏观社会演化 · 中观指令成员响应实体。
 * <p>职责：个体决策完成后回写「谁遵从/谁部分遵从/谁无视/谁违抗/谁个体化再解读」——中观层
 * 可见组织的指令被成员接受的程度，违抗累计可触发组内张力事件。
 * 仅特殊 NPC 响应回收（人群程序化决策无 AI 情境，不回收避免失真噪声）：</p>
 * <ul>
 * <li><b>response_type</b>：comply 遵从 / partial 部分遵从 / ignore 忽略 / defy 违抗 /
 * reinterpret 个体化再解读——由行动文本与指令三通道关键词的确定性匹配判定；</li>
 * <li><b>decision_trace_id</b>：贯通个体决策 trace（AgentRuntime/AiCallContext trace_id），
 * 调试面板可逐跳回放「指令 → 决策 → 响应」。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_meso_response",
        indexes = {
                @Index(name = "idx_meso_response_proj", columnList = "project_id"),
                @Index(name = "idx_meso_response_directive", columnList = "directive_id"),
                @Index(name = "idx_meso_response_actor", columnList = "project_id,actor_ref")
        })
public class ActorMesoResponse {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 指令 ID（actor_meso_directive.id） */
    @Column(name = "directive_id", nullable = false)
    private Long directiveId;

    /** 角色表主键 */
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 统一角色引用（special:{id}） */
    @Column(name = "actor_ref", nullable = false, length = 50)
    private String actorRef;

    /** 响应类型：comply 遵从 / partial 部分遵从 / ignore 忽略 / defy 违抗 / reinterpret 个体化再解读 */
    @Column(name = "response_type", nullable = false, length = 20)
    private String responseType = "ignore";

    /** 贯通个体决策 trace（AgentRuntime decision trace_id） */
    @Column(name = "decision_trace_id", length = 64)
    private String decisionTraceId;

    /** 响应游戏秒 */
    @Column(name = "game_second", nullable = false)
    private Long gameSecond;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (responseType == null || responseType.isBlank()) responseType = "ignore";
    }
}
