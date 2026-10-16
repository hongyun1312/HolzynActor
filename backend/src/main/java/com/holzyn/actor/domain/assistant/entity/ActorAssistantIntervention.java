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

package com.holzyn.actor.domain.assistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 助手世界干预计划实体，对应表 actor_assistant_intervention（M4，能力域 F）。
 * <p>职责：承载一次「助手经确认的世界干预」——操作类型（talk/action/event/clock/weather）+ 参数 +
 * 人类可读详情 + 影响范围 + 风险等级 + 生命周期状态（pending 待确认 / executed 已执行 /
 * cancelled 已取消 / failed 失败），执行结果存 result_json。写操作<b>不直接落库由助手编排执行</b>，
 * 一律经本表留痕 + 确认拦截后执行（与沙盘干预走同一批既有服务）。</p>
 * <p><b>零 AI</b>：本表纯持久化留痕，无 AI 调用。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_assistant_intervention", indexes = {
        @Index(name = "idx_assistant_intervention_session", columnList = "session_id")
})
public class ActorAssistantIntervention {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属会话 ID（确认粒度从会话读取） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 归属用户 ID */
    @Column(name = "user_id")
    private Long userId = 1L;

    /** 操作类型：talk 插话 / action 触发行动 / event 注入事件 / clock 控制时间 / weather 切换天气 */
    @Column(length = 16, nullable = false)
    private String operation;

    /** 操作参数 JSON（模型声明 + 校验归一后的参数） */
    @Column(name = "params_json", columnDefinition = "LONGTEXT")
    private String paramsJson;

    /** 操作详情（人类可读：如「插话给沈守拙：我们去云来茶馆」） */
    @Column(columnDefinition = "LONGTEXT")
    private String detail;

    /** 影响范围（人类可读：如「青禾村全境」/「角色 npc:1」） */
    @Column(name = "impact", length = 200)
    private String impact;

    /** 风险等级：low 低 / medium 中 / high 高 */
    @Column(name = "risk", length = 16)
    private String risk = "low";

    /** 生命周期状态：pending 待确认 / executed 已执行 / cancelled 已取消 / failed 执行失败 */
    @Column(length = 16, nullable = false)
    private String status = "pending";

    /** 执行结果 JSON（执行服务返回；失败含错误信息） */
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null || status.isBlank()) status = "pending";
        if (risk == null || risk.isBlank()) risk = "low";
    }
}
