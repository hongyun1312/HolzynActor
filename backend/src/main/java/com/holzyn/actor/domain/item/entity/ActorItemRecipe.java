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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物品配方实体，对应表 {@code actor_item_recipe}（§4.4.5）。
 *
 * <p>只建表不写入；制作/建造阶段读 {@code inputs_json} 判「材料齐不齐」
 * （R-CRAFT-01），并复用 R-USE-01 的设施门控。{@code projectId=0} 为全局模板。</p>
 *
 * <p><b>补三列</b>（V214）：{@link #outputKind} 区分「产物进背包」与「产物落成世界里的
 * 设施/物件」——这就是「制作」与「建造」的分界；{@link #enabled} 让某个世界能临时停做某样
 * 东西而不删行；{@link #updatedAt} 便于排障。默认值与既有语义一致（item / true）。</p>
 */
@Data
@Entity
@Table(name = "actor_item_recipe")
public class ActorItemRecipe {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID；0=全局模板 */
    @Column(name = "project_id", nullable = false)
    private Long projectId = ActorItemDef.GLOBAL_PROJECT;

    /** 配方键 */
    @Column(name = "recipe_key", nullable = false, length = 60)
    private String recipeKey;

    /** 产出物品键 */
    @Column(name = "output_item_key", nullable = false, length = 60)
    private String outputItemKey;

    /** 产出数量 */
    @Column(name = "output_qty", nullable = false)
    private Integer outputQty = 1;

    /** 输入材料 JSON：[{"itemKey":"iron","qty":2}] */
    @Column(name = "inputs_json", nullable = false, columnDefinition = "TEXT")
    private String inputsJson;

    /** 需要的设施类型（可空=随处可做） */
    @Column(name = "facility_type", length = 24)
    private String facilityType;

    /** 耗时（游戏分钟） */
    @Column(name = "duration_minutes")
    private Integer durationMinutes = 30;

    /**
     * 产出形态：{@code item}=进背包（制作）/ {@code facility}=成为设施 / {@code prop}=成为物件。
     *
     * <p>建造的产物不是背包里的东西，而是世界里的一处事实（{@code facility:loc:{id}} /
     * {@code prop:loc:{id}:{key}}）；把它记在配方上，规则层才不需要猜「这次是制作还是建造」。</p>
     */
    @Column(name = "output_kind", nullable = false, length = 12)
    private String outputKind = "item";

    /** 是否可做（false=该世界临时停做，不删行以免丢失「曾经会做」的记忆） */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 更新时间（排障用；V214 新增列，历史行为空） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
