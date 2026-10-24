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
 * 道路（表 actor_map_road）——一条路 = 一条折线，坐标是<b>米</b>。
 * <p>S3 的产物：AI 只给拓扑（谁连谁、路性格），走线由最小代价 A* 在真实地形上求出
 * （坡度/过河/绕建筑都是代价，用户决策 #6），所以路一定顺着地势、一定绕开房子、
 * 一定要过河的地方自动生成桥（{@link #bridgeOver}）。</p>
 * <p>层级 {@link #level}：major（主干，贯穿核心）/ minor（巷道）/ trail（生产土路）。
 * 渲染宽度 {@link #width} 单位米，1 米格网下一辆牛车约需 2.5~3 米。</p>
 */
@Data
@Entity
@Table(name = "actor_map_road")
public class ActorMapRoad {

    /** 层级：主干 */
    public static final String LEVEL_MAJOR = "major";

    /** 层级：巷道 */
    public static final String LEVEL_MINOR = "minor";

    /** 层级：生产土路 */
    public static final String LEVEL_TRAIL = "trail";

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

    /** 编码（唯一） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 路名（「正街」「后巷」） */
    @Column(length = 100)
    private String name;

    /** 形态：street / lane / path / bridge / stairs / pier */
    @Column(nullable = false, length = 24)
    private String kind;

    /** 层级 */
    @Column(length = 16)
    private String level = LEVEL_MINOR;

    /** 宽度（米） */
    private Double width = 3.0;

    /** 路面：stone / dirt / wood / gravel */
    @Column(length = 24)
    private String surface = "dirt";

    /** 折线（[[x,z],...] 米） */
    @Column(name = "path_json", nullable = false, columnDefinition = "TEXT")
    private String pathJson;

    /** 起点要素（可空） */
    @Column(name = "from_feature_id")
    private Long fromFeatureId;

    /** 终点要素（可空） */
    @Column(name = "to_feature_id")
    private Long toFeatureId;

    /** 连接语义（{from:"harbor_gate", to:"plaza_center"}） */
    @Column(name = "connects_json", columnDefinition = "TEXT")
    private String connectsJson;

    /** 长度（米） */
    private Double length;

    /** 跨水类型（river=跨河桥，null=不跨水） */
    @Column(name = "bridge_over", length = 24)
    private String bridgeOver;

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
            kind = "lane";
        }
        if (level == null) {
            level = LEVEL_MINOR;
        }
        if (width == null || width <= 0) {
            width = 3.0;
        }
        if (surface == null) {
            surface = "dirt";
        }
    }
}
