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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 中观指令实体。
 * <p>职责：承载「宏观事件 → 组织 → 成员」的差异化指令——宏观事件发生后由
 * MesoCoordinationService 按组织×角色生成（档位联动：programmatic 全规则 / light·full
 * 规则+重要事件 AI / max 全 AI），指令以<b>三通道</b>（倾向/约束/资源变化）注入，不硬编码
 * 具体行为——个体如何响应由个体决策层消化：</p>
 * <pre>
 * "tendency": {"bias": "囤积粮食", "strength": 0.6, "reason": "大旱致粮价连涨"},
 * "constraint": {"forbidden": ["贱卖存粮"], "soft": true, "reason": "粮商不得低于成本抛售"},
 * "resourceDelta":{"grain": -0.05, "currency": 0.02, "hint": "组织调配"},
 * "note": "组织对你的期待：保住商队信誉，同时压低收购价"
 * </pre>
 * <ul>
 * <li><b>directive_type</b>：react 应对宏观事件 / routine 例行 / opportunity 机遇 / crisis 危机；</li>
 * <li><b>ttl</b>：有效期（游戏秒；到期失效——感知/决策注入时过滤）；</li>
 * <li><b>source</b>：rule 规则模板 / ai 全 AI 生成（max 档）；trace_id 贯通。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_meso_directive",
        indexes = {
                @Index(name = "idx_meso_directive_proj", columnList = "project_id"),
                @Index(name = "idx_meso_directive_group", columnList = "group_id,status")
        })
public class ActorMesoDirective {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 组织 ID（actor_meso_group.id；null=全组织广播，按组类型/标签路由） */
    @Column(name = "group_id")
    private Long groupId;

    /** 触发的宏观事件主键（actor_world_macro_event.id；可空=周期例行指令） */
    @Column(name = "source_macro_event_id")
    private Long sourceMacroEventId;

    /** 指令类型：react 应对宏观事件 / routine 例行 / opportunity 机遇 / crisis 危机 */
    @Column(name = "directive_type", nullable = false, length = 30)
    private String directiveType = "react";

    /** 三通道载荷 JSON（tendency/constraint/resourceDelta/note）+ 说明 */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** 发布游戏秒 */
    @Column(name = "issued_game_second", nullable = false)
    private Long issuedGameSecond;

    /** 有效期（游戏秒；到期失效；null=永不过期） */
    @Column(name = "ttl")
    private Long ttl;

    /** 状态：active 生效 / expired 过期 / fulfilled 已履行 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /** 来源：rule 规则模板 / ai 全 AI 生成（max 档） */
    @Column(name = "source", nullable = false, length = 10)
    private String source = "rule";

    /** 贯通 trace_id */
    @Column(name = "trace_id", length = 64)
    private String traceId;

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
        if (directiveType == null || directiveType.isBlank()) directiveType = "react";
        if (status == null || status.isBlank()) status = "active";
        if (source == null || source.isBlank()) source = "rule";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
