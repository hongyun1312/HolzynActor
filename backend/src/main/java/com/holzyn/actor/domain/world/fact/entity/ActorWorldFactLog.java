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

package com.holzyn.actor.domain.world.fact.entity;

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
 * 事实变更日志，对应表 {@code actor_world_fact_log}。
 *
 * <p>不变量任何事实变化必须有一条日志，且 {@code event_id} 非空——
 * 玩家不能在台账上看到「凭空变了」。{@code op} 只允许 create/update/expire/void。</p>
 *
 * <p>幂等：{@code (project_id, idempotency_key)} 唯一，键形如
 * {@code plan:{planId}:op:{i}} / {@code dialog:{msgId}:op:{i}} / {@code engine:{projectId}:{seq}:{i}}。</p>
 */
@Data
@Entity
@Table(name = "actor_world_fact_log", indexes = {
        @Index(name = "ix_factlog_fact", columnList = "fact_id,version")
})
public class ActorWorldFactLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 事实行 ID */
    @Column(name = "fact_id", nullable = false)
    private Long factId;

    /** 事实稳定键（冗余，便于按 key 追溯） */
    @Column(name = "fact_key", nullable = false, length = 120)
    private String factKey;

    /** 变更后的版本号 */
    @Column(nullable = false)
    private Integer version;

    /** 操作：create / update / expire / void */
    @Column(nullable = false, length = 8)
    private String op;

    /** 变更前快照 JSON（create 为空） */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    /** 变更后快照 JSON */
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    /** 差异 JSON（可空） */
    @Column(name = "delta_json", columnDefinition = "TEXT")
    private String deltaJson;

    /** 变更发生游戏秒 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 触发变更的角色 ref（npc:{id}；系统变更为空） */
    @Column(name = "actor_ref", length = 64)
    private String actorRef;

    /** 写者：brain / engine（排障用，对应） */
    @Column(length = 12)
    private String writer;

    /** 来源事件 ID（非空） */
    @Column(name = "event_id")
    private Long eventId;

    /** 幂等键（同项目内唯一；可空=不做幂等） */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
