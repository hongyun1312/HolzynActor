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

package com.holzyn.actor.domain.mapgen.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 建筑资产类型（表 actor_map_asset_type）——S2 的产物：<b>「这一带该有什么房子」</b>。
 * <p>由 AI 依据世界观产出（{@code world_map_assets}），<b>不含任何坐标</b>（用户决策 #27）：
 * 只描述类型（尺寸/层数/墙色/屋顶形制/权重）。S4 落位时按语义挑类型、按地形定朝向与位置，
 * 再实例化成 {@link ActorMapFeature}。</p>
 * <p>大类 {@link #category} 固定为六个通用类（民居/商铺/工坊/公共/生产/水工，用户决策 #19），
 * 保证跨时代可用；{@link #name} 自由命名，贴合世界观。</p>
 */
@Data
@Entity
@Table(name = "actor_map_asset_type")
public class ActorMapAssetType {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属地图 */
    @Column(name = "map_id", nullable = false)
    private Long mapId;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 类型编码（英文小写下划线，全局唯一；如 house_fisher） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 名称（世界观里的叫法：「渔家小院」） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 大类：民居 / 商铺 / 工坊 / 公共 / 生产 / 水工 */
    @Column(nullable = false, length = 32)
    private String category;

    /** 要素形态：building / field / forest / pier / bridge / wall / plaza / dock */
    @Column(nullable = false, length = 24)
    private String kind = "building";

    /** 基准面宽（米，X 方向） */
    @Column(name = "base_w")
    private Double baseW;

    /** 基准进深（米，Z 方向） */
    @Column(name = "base_d")
    private Double baseD;

    /** 最少层数 */
    @Column(name = "floors_min")
    private Integer floorsMin = 1;

    /** 最多层数 */
    @Column(name = "floors_max")
    private Integer floorsMax = 1;

    /** 墙色（hex） */
    @Column(name = "wall_color", length = 16)
    private String wallColor;

    /** 屋顶/主色（hex） */
    @Column(name = "roof_color", length = 16)
    private String roofColor;

    /** 屋顶形制：flat / gable / hip / shed / dome / spire / thatch */
    @Column(name = "roof_shape", length = 24)
    private String roofShape = "gable";

    /** 主要材料：wood / stone / brick / rammed / thatch / mud */
    @Column(length = 24)
    private String material;

    /** 风格标签（逗号分隔） */
    @Column(name = "style_tags", length = 200)
    private String styleTags;

    /** 出现权重 1~20 */
    private Integer weight = 10;

    /** 是否被禁忌清单拦下（true=保留记录但不实例化，便于用户查看「为什么没生成」） */
    private Integer forbidden = 0;

    /** 用途说明（「谁在用、用来干什么」） */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 排序 */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (kind == null) {
            kind = "building";
        }
        if (weight == null || weight <= 0) {
            weight = 10;
        }
        if (forbidden == null) {
            forbidden = 0;
        }
    }
}
