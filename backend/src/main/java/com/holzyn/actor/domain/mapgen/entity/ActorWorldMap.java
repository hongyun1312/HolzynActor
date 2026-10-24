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
 * 世界地图（表 actor_world_map）——一次地图生成产物的<b>根</b>。
 * <p>一张图一行：种子、尺度、地理参数（{@link #paramsJson}）、世界档案（{@link #profileJson}）、
 * 阶段与进度（{@link #stage}/{@link #progress}，供 SSE 推进度）、地形贴图路径（{@link #terrainPath}）、
 * 统计（{@link #statsJson}）。地图内的所有要素（资产类型/聚落/建筑/道路/地块）都以 {@code map_id} 挂在本行下，
 * 重新生成时按 {@code map_id} 整体清理，<b>不做跨图兼容</b>（用户决策 #8）。</p>
 * <p>尺度：{@link #size} 格 × {@link #metersPerCell} 米。坐标一律用<b>米</b>存库，
 * 格数只在渲染/寻路时换算——这样地图尺寸可调而数据不迁移（用户决策 #4/#9）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_map")
public class ActorWorldMap {

    /** 状态：排队中 */
    public static final String STATUS_PENDING = "pending";

    /** 状态：生成中 */
    public static final String STATUS_RUNNING = "running";

    /** 状态：可用（全部阶段完成） */
    public static final String STATUS_READY = "ready";

    /** 状态：失败 */
    public static final String STATUS_FAILED = "failed";

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 种子（同种子同图；用户「重掷」只改这里） */
    @Column(nullable = false)
    private Long seed;

    /** 版本（管线版本，便于识别旧数据） */
    private Integer version;

    /** 边长（格） */
    @Column(nullable = false)
    private Integer size;

    /** 每格米数 */
    @Column(name = "meters_per_cell", nullable = false)
    private Double metersPerCell;

    /** 题材档：island / river_valley_village / ... */
    @Column(name = "genre_profile", length = 40)
    private String genreProfile;

    /** 地图名（「青屿镇」） */
    @Column(length = 100)
    private String name;

    /** 状态 */
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /** 当前阶段（S0/S1/S2/S3/S4/S5） */
    @Column(length = 24)
    private String stage;

    /** 阶段内进度 0~100 */
    private Integer progress = 0;

    /** 地形生成参数（TerrainParams 序列化） */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** 世界档案（AI 原文 + 地标/禁忌清单） */
    @Column(name = "profile_json", columnDefinition = "TEXT")
    private String profileJson;

    /** 统计（陆地占比/可建造占比/群系分布…，前端参数面板展示） */
    @Column(name = "stats_json", columnDefinition = "TEXT")
    private String statsJson;

    /**
     * 世界→地图契约原文（V5 起独立成列，不再埋在 {@code params_json.brief} 里）。
     *
     * <p>为什么要独立：契约是「这张图为什么是现在这个样子」的唯一答案（区域/设施/住所/人口），
     * 排障、重跑、报告都要读它；埋在 params_json 里意味着每次读它都要先把整个地形参数树解析一遍。
     * 旧行没有这一列，读的时候回落到 {@code params_json.brief}。</p>
     */
    @Column(name = "brief_json", columnDefinition = "TEXT")
    private String briefJson;

    /**
     * 环境动态（季节/天气/潮汐，JSON）——地图不是一张静态照片，它随世界时钟变化。
     *
     * <p>存的是「生成/刷新时刻的世界环境」，前端按它做季节色、潮位、雨湿表现；
     * 世界时钟推进后由接口重算（见 {@code MapAmbientService}）。</p>
     */
    @Column(name = "ambient_json", columnDefinition = "TEXT")
    private String ambientJson;

    /** 写实底图（4096² 主图）相对路径 */
    @Column(name = "composite_path", length = 255)
    private String compositePath;

    /** 夜景写实底图相对路径 */
    @Column(name = "composite_night_path", length = 255)
    private String compositeNightPath;

    /** 地形贴图相对路径（相对产物根目录，如 {@code 12/terrain.png}） */
    @Column(name = "terrain_path", length = 255)
    private String terrainPath;

    /** 地形版本（复算时自增，前端靠它破缓存） */
    @Column(name = "terrain_version")
    private Integer terrainVersion = 1;

    /** 生成完成时间 */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

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
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (progress == null) {
            progress = 0;
        }
        if (terrainVersion == null) {
            terrainVersion = 1;
        }
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
