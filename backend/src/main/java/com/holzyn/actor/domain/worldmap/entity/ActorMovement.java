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
 * 在途移动瞬态实体（位置移动与地图生成系统 · M2，表 actor_movement）——连续移动状态机权威。
 * <p>职责：记录一个 NPC（特殊 + 普通人群）的一次<b>真实移动</b>——起点/终点坐标、出发与
 * 预计到达的游戏秒、步行速度、触发源（reason）、目标地点。引擎每 tick 按当前游戏秒线性插值
 * 推进 {@code ActorSandboxState.posX/posZ/heading}（坐标权威读写点仍是沙盘状态行），到点后
 * status=arrived 落位；被打断（即时决策/新行动）→ status=cancelled，坐标停在打断点。</p>
 * <p>坐标域：<b>地图坐标 [0, scene_size)</b>（与 layout_json / 住所 / 地点坐标区一致，
 * 符合开发路径 §4.1 权威定义）；写回沙盘状态时由 MovementService 转中心化（−size/2，
 * 保持既有沙盘渲染与 Unity CoordinateTransformer 语义不破坏）。</p>
 * <p>status：walking（在途）/ arrived（已到达）/ cancelled（被打断）；reason：plan/decision/
 * physio/home/sandbox（四触发源 + 沙盘干预）。</p>
 */
@Data
@Entity
@Table(name = "actor_movement")
public class ActorMovement {

    /** 移动状态：在途 */
    public static final String STATUS_WALKING = "walking";

    /** 移动状态：已到达（落位完成） */
    public static final String STATUS_ARRIVED = "arrived";

    /** 移动状态：被打断（坐标停在打断点） */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 触发源：日常计划 */
    public static final String REASON_PLAN = "plan";

    /** 触发源：行动决策 */
    public static final String REASON_DECISION = "decision";

    /** 触发源：生理需求驱动 */
    public static final String REASON_PHYSIO = "physio";

    /** 触发源：就寝回家 */
    public static final String REASON_HOME = "home";

    /** 触发源：沙盘干预 */
    public static final String REASON_SANDBOX = "sandbox";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** NPC 引用（统一 {@code npc:{id}}） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 起点坐标 X（地图坐标 [0, scene_size)） */
    @Column(name = "from_x")
    private Double fromX;

    /** 起点坐标 Z（地图坐标 [0, scene_size)） */
    @Column(name = "from_z")
    private Double fromZ;

    /** 目标坐标 X（地图坐标 [0, scene_size)） */
    @Column(name = "to_x")
    private Double toX;

    /** 目标坐标 Z（地图坐标 [0, scene_size)） */
    @Column(name = "to_z")
    private Double toZ;

    /** 出发游戏秒（引擎权威位置） */
    @Column(name = "from_game_second")
    private Long fromGameSecond;

    /** 预计到达游戏秒 */
    @Column(name = "arrive_game_second")
    private Long arriveGameSecond;

    /** 步行速度（米/游戏秒；= 配置 walk-speed-game-meters-per-second） */
    private Double speed;

    /** 状态：walking / arrived / cancelled */
    @Column(length = 20)
    private String status = STATUS_WALKING;

    /** 触发源：plan / decision / physio / home / sandbox */
    @Column(length = 50)
    private String reason;

    /** 目标地点 ID（可空=坐标直行） */
    @Column(name = "target_location_id")
    private Long targetLocationId;

    /** 目标地点名（可空=坐标直行） */
    @Column(name = "target_location_name", length = 100)
    private String targetLocationName;

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
        if (status == null) status = STATUS_WALKING;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
