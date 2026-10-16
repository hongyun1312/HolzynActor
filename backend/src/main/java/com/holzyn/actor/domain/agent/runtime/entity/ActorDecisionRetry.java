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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策重试作业实体，对应表 actor_decision_retry（P0-02 根治 · 可重试性）。
 * <p>职责：<b>一次失败的决策点 = 一行可重试作业</b>——AI 决策抛异常时不再「一次调用 +
 * 立即缺失留痕」，而是入队本作业（PENDING），按指数退避重排，由引擎扫描到点重跑决策并
 * <b>回填到原定 gameSecond</b>（时间可以等作业，世界不停下来也不编造，审计 P0-02 根治）。
 * 重试成功 → SUCCEEDED（决策点最终以 AI/AI_RETRY 来源落台账）；重试耗尽/档位不允许 →
 * ABANDONED（随后落显式决策缺失记录）。</p>
 * <p>状态机：{@code PENDING} →（claim 原子认领）→ {@code RETRYING} → 成功
 * {@code SUCCEEDED} / 失败重排回 {@code PENDING} / 耗尽 {@code ABANDONED}。
 * {@code RETRYING} 崩溃残留由引擎扫描按 {@code updatedAt} 过期回收回 {@code PENDING}
 * （reclaimStale），避免单机重启后作业永久卡死。</p>
 * <p>去重键：{@code (project_id, character_id, game_second)}——同一决策点恰好一行作业，
 * 与决策台账 {@code actor_decision_log} 的「每决策点一行」口径对齐。</p>
 */
@Data
@Entity
@Table(name = "actor_decision_retry", indexes = {
        @Index(name = "idx_decision_retry_project_due", columnList = "project_id, status, next_retry_at"),
        @Index(name = "idx_decision_retry_project_char_second", columnList = "project_id, character_id, game_second")
})
public class ActorDecisionRetry {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 角色 ID（决策当事人） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 统一角色引用（npc:{id}，冗余便于排查） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 归属用户 ID（重跑决策上下文透传；可空） */
    @Column(name = "user_id")
    private Long userId;

    /** 原定决策时刻（游戏秒，自纪元起）——重试成功回填到该历史位置 */
    @Column(name = "game_second", nullable = false)
    private Long gameSecond;

    /** 游戏日（原定时刻所在日，自纪元起 floorDiv(gameSecond,86400)） */
    @Column(name = "game_day")
    private Long gameDay;

    /** 已尝试次数（初始 0；markFailed 递增） */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    /** 作业状态（PENDING/RETRYING/SUCCEEDED/ABANDONED；VARCHAR(16) 存枚举名） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 下次重试时刻（墙钟；指数退避排程；PENDING 必有，终态为空） */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /** 最近一次失败原因（异常消息/中断原因，截断 500 列宽） */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（claim/markFailed/markSucceeded 各阶段刷新；RETRYING 过期回收的依据） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
    }

    /** 更新前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
