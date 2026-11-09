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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物品持有栈实体，对应表 {@code actor_item_stack}（§4.4.5）。
 *
 * <p><b>一行 = 一个 (持有者, 物品, 品质, 耐久) 层</b>：{@code quantity} 是该层总数，
 * 不同品质/不同磨损不合并（唯一索引带 quality + durability），否则「这把刀八成新」
 * 和「另一把全新」会被抹平成一堆说不清状态的东西。</p>
 *
 * <p><b>holder_ref 命名空间</b>：{@code npc:{profileId}}（含玩家，玩家就是
 * {@code controller=player} 的 actor_profile 行）、{@code loc:{locationId}}（货架/仓廪）、
 * {@code container:{placementId}}（将来的容器）。不新增 holder_type 列，靠前缀区分——
 * 与仓库 {@code NpcRefs} 单前缀惯例一致。</p>
 *
 * <p><b>写入唯一入口</b>：{@link com.holzyn.actor.domain.item.service.InventoryService}。
 * 其他模块只读（裁决/提示词）。</p>
 */
@Data
@Entity
@Table(name = "actor_item_stack", indexes = {
        @Index(name = "ix_itemstack_holder", columnList = "project_id,holder_ref")
})
public class ActorItemStack {

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

    /** 物品键 */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 数量（严格 ≥0，由 ck_itemstack_qty 兜底） */
    @Column(nullable = false)
    private Integer quantity;

    /** 品质 0-100（恒 100；起腐坏会把它一路拉低） */
    @Column(nullable = false)
    private Integer quality = 100;

    /**
     * 耐久 0-100（起必填，默认 100=完好；0=已毁，不允许留在库里）。
     *
     * <p><b>为什么是层的一部分而不是「层上的一个属性」</b>：唯一索引
     * {@code (project_id, holder_ref, item_key, quality, durability)} 把它并入了层键，
     * 于是「5 把刀用钝 1 把」= 从耐久 100 层挪 1 件到耐久 99 层，逐件真实；
     * 若只改层上的值，整层会一起变钝、一起断。</p>
     */
    @Column(nullable = false)
    private Integer durability = 100;

    /**
     * 新鲜锚点（游戏秒）：这件（批）东西从什么时候开始算「新鲜的」。
     *
     * <p>腐坏质量由 {@code 100 - floor(100 × (now - freshAt) / (decayHours × 3600))}
     * 现算，不累加小数——同一游戏秒重复结算必然得到同一结果（幂等、可复算）。
     * 为空表示还没被腐坏逻辑碰过（首次 tick 补成当前游戏秒）。</p>
     */
    @Column(name = "fresh_at_game_second")
    private Long freshAtGameSecond;

    /** 乐观版本号（每次数量变化 +1，排障用） */
    @Column(nullable = false)
    private Integer version = 1;

    /** 最近一次变更的来源事件（可空） */
    @Column(name = "source_event_id")
    private Long sourceEventId;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (version == null) {
            version = 1;
        }
        if (quality == null) {
            quality = 100;
        }
        if (durability == null) {
            durability = 100;
        }
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
