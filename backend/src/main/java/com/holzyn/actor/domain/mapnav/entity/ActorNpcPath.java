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

package com.holzyn.actor.domain.mapnav.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条 NPC 移动轨迹（表 {@code actor_npc_path}）。
 *
 * <p><b>为什么把「路径」也落库</b>：位置只要一行，但「他是怎么走过去的」是验收的一半
 * （图上要看到沿路拐弯、日志里要对账、出问题时要知道绕了多远）。轨迹同时是**打断续走**的依据：
 * 新决策到来时从当前位置起算，而不是回到上一个落点。</p>
 *
 * <p><b>为什么存折线而不是逐帧采样</b>：40 个角色 × 每 5 游戏分钟一个采样点 = 一天上万行。
 * 折线 + 出发/到达时刻足以还原任意时刻的位置（按弧长插值），体积小两个数量级。</p>
 *
 * <p>轨迹是日志性质：{@code arrived}/{@code aborted} 行按保留期清理，不影响任何业务读数。</p>
 */
@Data
@Entity
@Table(name = "actor_npc_path")
public class ActorNpcPath {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属地图 */
    @Column(name = "map_id", nullable = false)
    private Long mapId;

    /** 角色引用 */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 折线（米，{@code [[x,z],…]} JSON） */
    @Column(name = "points_json", nullable = false, columnDefinition = "TEXT")
    private String pointsJson;

    /** 总长（米） */
    @Column(name = "length_m", nullable = false)
    private Double lengthM;

    /** 出发游戏秒 */
    @Column(name = "depart_game_second", nullable = false)
    private Long departGameSecond;

    /** 到达游戏秒 */
    @Column(name = "arrive_game_second", nullable = false)
    private Long arriveGameSecond;

    /** 起点引用（要素 code / 地点 code；可空） */
    @Column(name = "from_ref", length = 64)
    private String fromRef;

    /** 终点引用（要素 code / 地点 code；可空） */
    @Column(name = "to_ref", length = 64)
    private String toRef;

    /** 起因：routine / decision / return_home */
    @Column(nullable = false, length = 24)
    private String reason;

    /** 状态：walking / arrived / aborted */
    @Column(nullable = false, length = 16)
    private String status;

    /** 版本（打断重算时 +1，轨迹对账用） */
    private Integer version = 1;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (version == null) {
            version = 1;
        }
    }
}
