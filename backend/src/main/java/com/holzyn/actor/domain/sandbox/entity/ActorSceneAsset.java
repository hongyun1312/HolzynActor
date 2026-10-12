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
 * 沙盘 3D 资产登记实体（世界沙盘 S1，表 actor_sandbox_asset；九模块规范化重构 原 actor_scene_asset）。
 * <p>职责：项目级资产登记/检索视图（缓存）。资产数据包 manifest.json 为权威（文件即事实），
 * 本表为项目级缓存视图，字段与 manifest 共用 10 类枚举。</p>
 * <p>10 类类别：building_residential / building_public / building_commercial / plant /
 * terrain / prop / road / water / npc / decoration。</p>
 */
@Data
@Entity
@Table(name = "actor_sandbox_asset")
public class ActorSceneAsset {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级资产（内置模板资产 project_id=0 全局共享） */
    @Column(name = "project_id", nullable = false)
    private Long projectId = 0L;

    /** 所属资产数据包 ID（manifest.pack.id） */
    @Column(name = "pack_id", length = 64)
    private String packId;

    /** 稳定标识（如 building_inn_01、tree_oak_01） */
    @Column(name = "asset_id", length = 64)
    private String assetId;

    /** 中文名 */
    @Column(length = 100)
    private String name;

    /** 10 类类别枚举（见类注释） */
    @Column(length = 40)
    private String category;

    /** 描述（含 AI 放置参考语义） */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 规模：{length,width,height}（米，程序扫描或人工） */
    @Column(name = "size_json", columnDefinition = "TEXT")
    private String sizeJson;

    /** 占地：{length,width}（米，AI 放置避让/校验用） */
    @Column(name = "footprint_json", columnDefinition = "TEXT")
    private String footprintJson;

    /** 风格标签数组（如 ["乡村","木质"]） */
    @Column(name = "style_json", columnDefinition = "TEXT")
    private String styleJson;

    /** 标签数组（如 ["酒馆","公共","可进入"]） */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    /** GLB 文件路径（内置=前端资源；项目级=uploads/scene-assets/…；鸿蒙=rawfile） */
    @Column(name = "file_path", length = 255)
    private String filePath;

    /** 是否 Draco/KTX2 压缩 */
    private Boolean compressed = false;

    /** 可选缩略图路径 */
    @Column(length = 255)
    private String preview;

    /** 人工覆盖 size/footprint 标记（扫描脚本跳过） */
    private Boolean manual = false;

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
        if (projectId == null) projectId = 0L;
        if (compressed == null) compressed = false;
        if (manual == null) manual = false;
    }

    /** 前回调：刷新更新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
