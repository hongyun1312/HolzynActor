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

package com.holzyn.actor.domain.equip.entity;

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
 * 装备行实体，对应表 {@code actor_item_equipment}。
 *
 * <p><b>一行 = 某持有者某个槽位上「在用」的那件东西</b>，唯一索引
 * {@code (project_id, holder_ref, slot)} 保证一个槽位同时只有一件。</p>
 *
 * <p><b>只记 item_key，不复制品质/耐久</b>：状态（品质/耐久/新鲜度）的唯一真相是
 * {@code actor_item_stack}。装备行若另存一份，两边迟早对不上——卖了、吃了、烂了，
 * 装备行还挂着「他手里有把刀」。所以「手上这件到底是哪件」每次读的时候现找：
 * 同物品里最完好的那一层；找不到就说明没了，装备行自愈删除。</p>
 *
 * <p>写入唯一入口：{@link com.holzyn.actor.domain.equip.service.EquipmentService}。</p>
 */
@Data
@Entity
@Table(name = "actor_item_equipment", indexes = {
        @Index(name = "ix_equipment_item", columnList = "project_id,item_key")
})
public class ActorItemEquipment {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 持有者 ref：npc:{id} / loc:{id} / container:{placementId} */
    @Column(name = "holder_ref", nullable = false, length = 64)
    private String holderRef;

    /** 槽位：hand（手持）/ body（穿戴） */
    @Column(nullable = false, length = 16)
    private String slot;

    /** 物品键（指向持有栈，不复制状态） */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 从哪个游戏秒开始戴着（可空；排障/叙事用） */
    @Column(name = "since_game_second")
    private Long sinceGameSecond;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
