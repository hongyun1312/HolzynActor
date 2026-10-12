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
 * 沙盘场景定义实体（世界沙盘 S1，表 actor_sandbox_scene）。
 * <p>职责：存储项目沙盘的场景定义——模板/风格/尺寸/种子 + 场景布局坐标图
 * （layout_json 权威）+ 环境/锚点/地形覆盖/色板等扩展列（场景模块 SC2 对齐）。</p>
 * <p>⚠️ 表名用 actor_sandbox_scene（避开既有世界演化实体 actor_scene，双端一致）。</p>
 */
@Data
@Entity
@Table(name = "actor_sandbox_scene")
public class ActorSandboxScene {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 场景名（默认=项目名 +「· 主场景」） */
    @Column(length = 100)
    private String name;

    /** 预设模板标识（village/town/forest/…或 custom；决定整体布局基调与预置资产参考） */
    @Column(name = "template_code", length = 50)
    private String templateCode;

    /** 程序地形风格（plain/mountain/hills/seaside/desert/snow 或 custom；决定色板与地形参数） */
    @Column(name = "style_code", length = 50)
    private String styleCode;

    /** 场景边长（500 的整数倍，1 块=1 米） */
    @Column(name = "scene_size")
    private Integer sceneSize = 500;

    /** 程序地形随机种子（可复现） */
    private Long seed;

    /** 场景布局坐标图（权威）：{scene:{size,style}, rivers[], roads[], placements[]} */
    @Column(name = "layout_json", columnDefinition = "TEXT")
    private String layoutJson;

    /** 环境：{sky, fog, light, groundColor}（昼夜/天气由世界时钟驱动） */
    @Column(name = "env_json", columnDefinition = "TEXT")
    private String envJson;

    /** 地点锚点映射：[{locationId, locationName, x, y, z}]（AI 生成后自动派生，人工可改） */
    @Column(name = "anchor_json", columnDefinition = "TEXT")
    private String anchorJson;

    /** 人工稀疏地形覆盖 {index: type}（河路栅格化不落此列，渲染时实时栅格化） */
    @Column(name = "terrain_override_json", columnDefinition = "TEXT")
    private String terrainOverrideJson;

    /** 自定义色板覆盖 */
    @Column(name = "palette_json", columnDefinition = "TEXT")
    private String paletteJson;

    /** 自定义风格描述（style_code=custom 时） */
    @Column(name = "style_desc", columnDefinition = "TEXT")
    private String styleDesc;

    /** 来源：template / manual / ai_generated */
    @Column(length = 30)
    private String source = "template";

    /** 场景定义版本（AI 重生成/编辑升级） */
    private Integer version = 1;

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
        if (sceneSize == null) sceneSize = 500;
        if (version == null) version = 1;
        if (source == null) source = "template";
    }

    /** 前回调：刷新更新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
