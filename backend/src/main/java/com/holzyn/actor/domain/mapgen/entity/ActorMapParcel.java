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
 * 地块（表 actor_map_parcel）——S4 落位的<b>「候选格子」</b>。
 * <p>为什么要先切地块：真实聚落是「先划地、再盖房」——沿主街两侧切出一排临街地块，
 * 每块放一栋面宽 6~12 米的房子；农田按垄切成条；林地成片。直接把建筑随机撒在可用格上
 * 会得到「一堆乱七八糟的盒子」（上一版的观感问题）。地块还是 LOD 与后续玩家建房的锚点。</p>
 */
@Data
@Entity
@Table(name = "actor_map_parcel")
public class ActorMapParcel {

    /** 地块类型：临街宅基地 */
    public static final String KIND_HOMESTEAD = "homestead";

    /** 地块类型：农田 */
    public static final String KIND_FARM = "farm";

    /** 地块类型：林地 */
    public static final String KIND_FOREST = "forest";

    /** 地块类型：公共广场/空地 */
    public static final String KIND_PLAZA = "plaza";

    /** 地块类型：码头/水工 */
    public static final String KIND_WATERFRONT = "waterfront";

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

    /** 所属要素组（可空） */
    @Column(name = "group_id")
    private Long groupId;

    /** 编码（唯一） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 地块类型 */
    @Column(nullable = false, length = 24)
    private String kind;

    /** 中心 X（米） */
    @Column(name = "center_x", nullable = false)
    private Double centerX;

    /** 中心 Z（米） */
    @Column(name = "center_z", nullable = false)
    private Double centerZ;

    /** 面积（平方米） */
    private Double area;

    /** 可建造评分 0~1（来自地形 buildable） */
    private Double buildable;

    /** 多边形（[[x,z],...] 米） */
    @Column(name = "polygon_json", columnDefinition = "TEXT")
    private String polygonJson;

    /** 落在本块上的要素（可空=空地） */
    @Column(name = "feature_id")
    private Long featureId;

    /** 临接道路编码（临街地块才有） */
    @Column(name = "road_code", length = 64)
    private String roadCode;

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
            kind = KIND_HOMESTEAD;
        }
    }
}
