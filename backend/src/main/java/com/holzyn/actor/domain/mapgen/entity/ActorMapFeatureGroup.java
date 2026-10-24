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
 * 地图要素组（表 actor_map_feature_group）——<b>院落 / 街区 / 田片</b>这一层的容器。
 * <p>为什么要这一层：真实聚落里房子不是一朵朵孤立的盒子，而是「一间正屋 + 厨房 + 柴棚 + 围墙 + 门」
 * 围成一个院，几个院沿街排成一段街（用户决策 #7）。渲染时也靠这一层做 <b>LOD</b>：
 * 缩远时只画院落轮廓，放大才展开院内每一栋。</p>
 */
@Data
@Entity
@Table(name = "actor_map_feature_group")
public class ActorMapFeatureGroup {

    /** 组类型：院落（民居/作坊的院子） */
    public static final String KIND_COURTYARD = "courtyard";

    /** 组类型：公共建筑组（主殿 + 厢房 + 院墙） */
    public static final String KIND_COMPOUND = "compound";

    /** 组类型：田片（一块田由若干条垄组成，渲染成整片） */
    public static final String KIND_FIELD = "field";

    /** 组类型：街段（沿一条路排布的一排铺面） */
    public static final String KIND_BLOCK = "block";

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

    /** 组编码（唯一） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 组名（「沈家院」「老街东段」） */
    @Column(length = 100)
    private String name;

    /** 组类型 */
    @Column(nullable = false, length = 24)
    private String kind;

    /** 中心 X（米） */
    @Column(name = "center_x", nullable = false)
    private Double centerX;

    /** 中心 Z（米） */
    @Column(name = "center_z", nullable = false)
    private Double centerZ;

    /** 外接半径（米） */
    private Double radius;

    /** 朝向（度，0=北） */
    private Double rotation;

    /** 风格编码（同一条街共用，保证观感统一） */
    @Column(name = "style_code", length = 40)
    private String styleCode;

    /** 说明（「渔家院，院内晒网」） */
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
            kind = KIND_COURTYARD;
        }
    }
}
