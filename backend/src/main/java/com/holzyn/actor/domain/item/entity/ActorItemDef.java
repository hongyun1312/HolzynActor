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
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 物品定义实体，对应表 {@code actor_item_def}（§4.4.5）。
 *
 * <p><b>两级定义</b>：{@code projectId=0} 是全局模板，{@code projectId>0} 是该世界的覆盖。
 * 查找顺序固定「先本项目、再全局」，不允许两处同时命中。</p>
 *
 * <p><b>为什么 project_id 用 0 而不是 NULL</b>：H2 唯一索引默认 NULLS DISTINCT，
 * 多个 NULL 互不相同，{@code (project_id, item_key)} 对全局模板就形同虚设。</p>
 *
 * <p>写入唯一入口：{@link com.holzyn.actor.domain.item.service.InventoryService}
 * （懒建最小定义也走它，不另开门面）。</p>
 */
@Data
@Entity
@Table(name = "actor_item_def", indexes = {
        @Index(name = "ix_itemdef_key", columnList = "item_key")
})
public class ActorItemDef {

    /** 全局模板哨兵：project_id=0 表示所有世界共用 */
    public static final long GLOBAL_PROJECT = 0L;

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID；0=全局模板 */
    @Column(name = "project_id", nullable = false)
    private Long projectId = GLOBAL_PROJECT;

    /** 稳定物品键（如 {@code grain}、{@code bread}） */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 展示名（如「麦子」「短刀」） */
    @Column(nullable = false, length = 120)
    private String name;

    /** 分类：food|material|tool|weapon|wearable|valuable|medicine */
    @Column(nullable = false, length = 24)
    private String category = "material";

    /** 单层堆叠上限（展示/交易拆分用；存储层一行即一个品质层，不受此列截断） */
    @Column(name = "stack_max", nullable = false)
    private Integer stackMax = 99;

    /** 单位重量 */
    @Column(name = "unit_weight", precision = 10, scale = 2)
    private BigDecimal unitWeight = BigDecimal.ZERO;

    /** 基准价格（交易用） */
    @Column(name = "base_price", nullable = false)
    private Integer basePrice = 0;

    /** 腐坏小时数（用；空=不腐坏） */
    @Column(name = "decay_hours")
    private Integer decayHours;

    /** 装备槽位（用；空=不可装备） */
    @Column(name = "equip_slot", length = 16)
    private String equipSlot;

    /** 扩展属性 JSON */
    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;
}
