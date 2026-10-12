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

package com.holzyn.actor.domain.sandbox.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 沙盘资产摆放实体（世界沙盘 S3，表 actor_sandbox_placement；九模块规范化重构 原 actor_scene_placement）。
 * <p>职责：记录「某个资产摆放在某个场景的哪个位置、多大、什么朝向」——即场景内模型资产的
 * 摆放信息（资产 URL 由 asset_id 关联 {@link ActorSceneAsset#getFilePath()} 解析；位置/缩放/朝向
 * 本表权威）。</p>
 * <p>与既有表的关系：场景信息/尺寸 → {@link ActorSandboxScene}（scene_id 关联）；场景内 NPC
 * 位置 → {@link ActorSandboxState}（引擎位置权威源）；本表只管「静态资产摆放」，互不重叠。</p>
 * <p>asset_id 复用资产登记表（actor_scene_asset.asset_id）的稳定标识（如 building_inn_01），
 * 允许自定义资产标识（登记表为项目级缓存视图，不强制外键）。</p>
 */
@Data
@Entity
@Table(name = "actor_sandbox_placement")
public class ActorScenePlacement {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属场景 ID（关联 actor_sandbox_scene.id，必填） */
    @Column(name = "scene_id", nullable = false)
    private Long sceneId;

    /** 资产稳定标识（actor_scene_asset.asset_id，如 building_inn_01；URL 由此解析） */
    @Column(name = "asset_id", length = 64)
    private String assetId;

    /** 摆放名（可选；缺省回退资产登记名） */
    @Column(length = 100)
    private String name;

    /** 摆放位置 X（米，场景坐标，0..scene_size） */
    @Column(name = "pos_x")
    private Double posX;

    /** 摆放位置 Y（米，地面高度，缺省 0） */
    @Column(name = "pos_y")
    private Double posY = 0d;

    /** 摆放位置 Z（米，场景坐标，0..scene_size） */
    @Column(name = "pos_z")
    private Double posZ;

    /** 缩放 X（缺省 1） */
    @Column(name = "scale_x")
    private Double scaleX = 1d;

    /** 缩放 Y（缺省 1） */
    @Column(name = "scale_y")
    private Double scaleY = 1d;

    /** 缩放 Z（缺省 1） */
    @Column(name = "scale_z")
    private Double scaleZ = 1d;

    /** 朝向（欧拉角，弧度；绕 X 轴，缺省 0） */
    @Column(name = "rot_x")
    private Double rotX = 0d;

    /** 朝向（欧拉角，弧度；绕 Y 轴，即水平朝向，缺省 0） */
    @Column(name = "rot_y")
    private Double rotY = 0d;

    /** 朝向（欧拉角，弧度；绕 Z 轴，缺省 0） */
    @Column(name = "rot_z")
    private Double rotZ = 0d;

    /** 排序（同场景内升序，稳定渲染顺序） */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (posY == null) posY = 0d;
        if (scaleX == null) scaleX = 1d;
        if (scaleY == null) scaleY = 1d;
        if (scaleZ == null) scaleZ = 1d;
        if (rotX == null) rotX = 0d;
        if (rotY == null) rotY = 0d;
        if (rotZ == null) rotZ = 0d;
        if (sortOrder == null) sortOrder = 0;
    }

    /** 前回调：刷新更新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
