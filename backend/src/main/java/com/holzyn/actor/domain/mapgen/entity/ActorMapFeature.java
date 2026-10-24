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
 * 地图要素（表 actor_map_feature）——<b>几何层</b>：一栋房、一块田、一片林、一座桥、一个码头。
 * <p>与语义层的分工（用户决策 #11）：{@code actor_world_location} 回答「青禾家住哪」
 * （名字级的公共地点），本表回答「那间屋子的墙在哪个米数」。两层用 {@link #code}
 * 与 {@link #locationCode} 关联，不互为外键——地点可以没有建筑，建筑也可以不属于任何命名地点。</p>
 * <p>坐标一律米：{@link #posX}/{@link #posZ} 是<b>占地面中心</b>，{@link #sizeW}/{@link #sizeD}
 * 是面宽/进深，{@link #rotation} 是绕 Y 轴角度（0=正面朝南，顺时针为东）。</p>
 */
@Data
@Entity
@Table(name = "actor_map_feature")
public class ActorMapFeature {

    /** 形态：建筑 */
    public static final String KIND_BUILDING = "building";

    /** 形态：农田 */
    public static final String KIND_FIELD = "field";

    /** 形态：林地 */
    public static final String KIND_FOREST = "forest";

    /** 形态：码头 */
    public static final String KIND_PIER = "pier";

    /** 形态：桥 */
    public static final String KIND_BRIDGE = "bridge";

    /** 形态：围墙 */
    public static final String KIND_WALL = "wall";

    /** 形态：广场/空地 */
    public static final String KIND_PLAZA = "plaza";

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

    /** 所属要素组（院落/街区/田片，可空） */
    @Column(name = "group_id")
    private Long groupId;

    /** 资产类型 ID（可空：地形派生的林地/桥没有资产类型） */
    @Column(name = "asset_type_id")
    private Long assetTypeId;

    /** 资产类型编码（冗余，便于前端按类型合批渲染） */
    @Column(name = "asset_code", length = 64)
    private String assetCode;

    /** 要素编码（唯一，如 f_house_fisher_003） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 名称（「沈家大宅」；可空=无名棚屋） */
    @Column(length = 100)
    private String name;

    /** 大类（民居/商铺/工坊/公共/生产/水工/自然） */
    @Column(length = 32)
    private String category;

    /** 形态 */
    @Column(nullable = false, length = 24)
    private String kind;

    /** 中心 X（米） */
    @Column(name = "pos_x", nullable = false)
    private Double posX;

    /** 中心 Z（米） */
    @Column(name = "pos_z", nullable = false)
    private Double posZ;

    /** 面宽（米，局部 X） */
    @Column(name = "size_w")
    private Double sizeW;

    /** 进深（米，局部 Z） */
    @Column(name = "size_d")
    private Double sizeD;

    /** 朝向（度） */
    private Double rotation = 0.0;

    /** 檐口高度（米） */
    private Double height;

    /** 层数 */
    private Integer floors = 1;

    /** 地基海拔（米，地形高度；用于把建筑贴地） */
    @Column(name = "base_elevation")
    private Double baseElevation;

    /** 墙色（hex） */
    @Column(name = "wall_color", length = 16)
    private String wallColor;

    /** 屋顶色（hex） */
    @Column(name = "roof_color", length = 16)
    private String roofColor;

    /** 屋顶形制 */
    @Column(name = "roof_shape", length = 24)
    private String roofShape;

    /** 门位置 X（米；角色寻路到门口） */
    @Column(name = "door_x")
    private Double doorX;

    /** 门位置 Z（米） */
    @Column(name = "door_z")
    private Double doorZ;

    /** 门朝向（度） */
    @Column(name = "door_dir")
    private Double doorDir;

    /** 占地多边形（[[x,z],...] 米，含旋转后的四角） */
    @Column(name = "polygon_json", columnDefinition = "TEXT")
    private String polygonJson;

    /** 占地矩形（未旋转：{w,d,rot}） */
    @Column(name = "footprint_json", columnDefinition = "TEXT")
    private String footprintJson;

    /** 语义（{role:"民居", servedBy:["plaza_center"], note:"..."}） */
    @Column(name = "semantic_json", columnDefinition = "TEXT")
    private String semanticJson;

    /** 关联的命名地点编码（可空） */
    @Column(name = "location_code", length = 64)
    private String locationCode;

    /**
     * 锚定的世界地点名（「月牙湾」「松针丘陵」）。
     *
     * <p>与 {@link #locationCode} 的区别：{@code location_code} 是语义层的<b>编码</b>（供程序 join），
     * 这里是世界数据里的<b>原名</b>（供人读、供 AI 复述）。契约里的 {@code anchorLocation}
     * 原样落下，报告里才能直接核对「契约说灯塔在北端海岬，图上这栋是不是挂在那个锚点上」。</p>
     */
    @Column(name = "anchor_location", length = 100)
    private String anchorLocation;

    /** 所属世界区域编码（契约 regions[].code；农田/林地/设施按区域归类时用） */
    @Column(name = "region_code", length = 64)
    private String regionCode;

    /**
     * 归属住所（actor_npc_home.id 的字符串形式；V5 / M6 的核心列）。
     *
     * <p>这一列让「世界数据里的 12 个住所」与「图上的 12 栋主屋」真正绑定：
     * 角色可以顺着 {@code owner_home_ref} 找到自己家那栋房子，房子也能反查住着谁。
     * 附属房/厢房记成「所属主屋的 homeRef + 后缀」，所以也走这一列。</p>
     */
    @Column(name = "owner_home_ref", length = 64)
    private String ownerHomeRef;

    /** 扩展标记（JSON：{@code {"rear":true}} / {@code {"crop":"beans","garden":true}} / {@code {"ridge":true}}） */
    @Column(name = "flags_json", columnDefinition = "TEXT")
    private String flagsJson;

    /** 是否住人（0/1；=1 才可作为角色的住所） */
    private Integer residency = 0;

    /** 重要度 1~5（地标 5，普通民居 1；LOD 与渲染顺序用） */
    private Integer importance = 1;

    /** 排序 */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (kind == null) {
            kind = KIND_BUILDING;
        }
        if (rotation == null) {
            rotation = 0.0;
        }
        if (floors == null || floors < 1) {
            floors = 1;
        }
        if (residency == null) {
            residency = 0;
        }
        if (importance == null) {
            importance = 1;
        }
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
