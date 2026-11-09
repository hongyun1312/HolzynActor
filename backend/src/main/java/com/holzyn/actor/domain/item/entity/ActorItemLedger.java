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

package com.holzyn.actor.domain.item.entity;

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
 * 物品账本实体，对应表 {@code actor_item_ledger}（§4.4.5）。
 *
 * <p><b>幂等</b>：{@code (project_id, idempotency_key)} 唯一。同一效果重放时先查账本，
 * 命中即跳过——账本既是审计，也是去重表（外部实践一致：把幂等记录与状态放在同一个库、
 * 同一个事务里，重试才会变成无害的冲突）。</p>
 *
 * <p><b>qty_delta 口径</b>：转移记正数（方向由 from_ref/to_ref 表达）；产出记 +qty；
 * 消耗记 -qty。一行一次变更，不做借贷两行——两行会撞同一个幂等键。</p>
 */
@Data
@Entity
@Table(name = "actor_item_ledger", indexes = {
        @Index(name = "ix_ledger_item", columnList = "project_id,item_key,game_second")
})
public class ActorItemLedger {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 物品键 */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 数量增量（转移=正数；产出=+qty；消耗=-qty） */
    @Column(name = "qty_delta", nullable = false)
    private Integer qtyDelta;

    /** 来源持有者（可空=无来源产出） */
    @Column(name = "from_ref", length = 64)
    private String fromRef;

    /** 目标持有者（可空=销毁/消耗） */
    @Column(name = "to_ref", length = 64)
    private String toRef;

    /** 原因：trade|gift|craft|consume|decay|loot|repair|initial|spawn */
    @Column(nullable = false, length = 32)
    private String reason;

    /** 写者：brain|engine（排障用） */
    @Column(length = 12)
    private String writer;

    /** 来源事件 ID（可空；效果链路会带） */
    @Column(name = "event_id")
    private Long eventId;

    /** 游戏秒 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 幂等键（可空；带键的变更会被去重） */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
