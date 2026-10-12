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

package com.holzyn.actor.domain.worldmap.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 住所实体（位置移动与地图生成系统 · M1，表 actor_npc_home）——一间房 = 一个家，可住多人。
 * <p>职责：记录<b>一间住所</b>——家名、所属地点（location_id 区域锚）、坐标（pos_x/pos_z，
 * 0..scene_size 地图坐标）、主屋标签/方位/补充。谁住在这由 {@link ActorHomeMember} 表达：
 * 亲密关系（配偶/情侣/亲子/兄弟姐妹/亲属）角色<b>同住一间</b>（决策 #19 同居共宅），
 * 每角色恰在一个住所的 members 中。</p>
 * <p>住所<b>不进</b> 15 地点表（actor_world_location），不污染地点语义（地点=公共区域，家=角色私有）。</p>
 * <p>后期能力（数据模型本轮就绪，行为端点后续轮）：新建住所（POST /homes）、搬家（member 改 home_id）、
 * 装修（{@link #decorationLevel} 0~100）、住宅废弃（{@link #status}=abandoned）。</p>
 */
@Data
@Entity
@Table(name = "actor_npc_home")
public class ActorNpcHome {

    /** 住所状态：在用 */
    public static final String STATUS_ACTIVE = "active";

    /** 住所状态：已废弃（后期端点维护） */
    public static final String STATUS_ABANDONED = "abandoned";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 家名（「沈家大宅」「李记面馆后屋」；感知/对话可引用） */
    @Column(length = 100)
    private String name;

    /** 所属地点 ID（actor_world_location.id；家所在的区域锚） */
    @Column(name = "location_id")
    private Long locationId;

    /** 对应住宅摆放（actor_sandbox_placement.id，可空=无独立摆放） */
    @Column(name = "placement_id")
    private Long placementId;

    /** 家坐标 X（0..scene_size 地图坐标） */
    @Column(name = "pos_x")
    private Double posX;

    /** 家坐标 Z（0..scene_size 地图坐标） */
    @Column(name = "pos_z")
    private Double posZ;

    /** 主屋标签（「正房」） */
    @Column(name = "room_label", length = 100)
    private String roomLabel;

    /** 方位（「村西」「村中」等口语方位，感知/对话素材） */
    @Column(length = 50)
    private String region;

    /** 补充说明（「书房在西厢」等；AI 生成或手动填写） */
    @Column(length = 200)
    private String note;

    /** 状态：active / abandoned（住宅废弃；后期端点维护） */
    @Column(length = 20)
    private String status = STATUS_ACTIVE;

    /** 装修度 0~100（后期装修端点维护；0=毛坯，100=精装） */
    @Column(name = "decoration_level")
    private Integer decorationLevel = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = STATUS_ACTIVE;
        if (decorationLevel == null) decorationLevel = 0;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
