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
 * 角色沙盘状态实体（世界沙盘 S1，表 actor_sandbox_state）——位置权威源。
 * <p>职责：记录每个 NPC（特殊 + 普通人群）在沙盘场景中的实时状态：
 * 场景坐标 / 朝向 / 移动状态 / 动作 / 状态摘要 / 内心想法 + 对应游戏时刻。</p>
 * <p>位置权威规则（主开发方案 §5.3）：NPC 处于某地点 → 位置=该地点锚点；发生 move 行动 →
 * 引擎按游戏时间模拟路径更新 pos/heading 并广播；S1 无锚点时用地点名确定性派生坐标。</p>
 * <p>npc_ref 复用既有双轨引用约定：special:{id} / crowd:{id}。</p>
 */
@Data
@Entity
@Table(name = "actor_sandbox_state")
public class ActorSandboxState {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属场景 ID（关联 actor_sandbox_scene.id；S1 无场景时可空） */
    @Column(name = "scene_id")
    private Long sceneId;

    /** NPC 类型：special / crowd */
    @Column(name = "npc_type", length = 20)
    private String npcType;

    /** NPC 引用：special:{id} / crowd:{id} */
    @Column(name = "npc_ref", length = 50)
    private String npcRef;

    /** 场景坐标 X（y=地面高度，前期恒 0） */
    @Column(name = "pos_x")
    private Double posX;

    /** 场景坐标 Y（地面高度，前期恒 0） */
    @Column(name = "pos_y")
    private Double posY = 0d;

    /** 场景坐标 Z */
    @Column(name = "pos_z")
    private Double posZ;

    /** 朝向（弧度） */
    private Double heading = 0d;

    /** 移动状态：idle / walking / acting / talking / resting */
    @Column(name = "move_state", length = 20)
    private String moveState = "idle";

    /** 移动目标点/路径（move 时） */
    @Column(name = "target_pos_json", columnDefinition = "TEXT")
    private String targetPosJson;

    /** 当前动作描述（来自 current_activity / 行动） */
    @Column(name = "action_text", length = 255)
    private String actionText;

    /** 状态摘要（心情/目标/健康等，派生或 AI） */
    @Column(name = "status_text", length = 255)
    private String statusText;

    /** 内心想法（当前气泡内容） */
    @Column(name = "thought_text", length = 255)
    private String thoughtText;

    /** 想法来源：derived / ai / reflection / perception */
    @Column(name = "thought_source", length = 20)
    private String thoughtSource;

    /** 状态对应游戏时刻（秒） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (posY == null) posY = 0d;
        if (heading == null) heading = 0d;
        if (moveState == null) moveState = "idle";
    }

    /** 前回调：刷新更新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
