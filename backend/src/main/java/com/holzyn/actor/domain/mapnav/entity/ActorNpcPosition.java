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
 * 角色当前坐标（表 {@code actor_npc_position}）——新地图上<b>唯一</b>的位置权威。
 *
 * <p><b>为什么非要有这张表</b>：位置原来只存在于浏览器的一次插值里（谁刷新页面谁就丢），
 * 后端感知、AI 决策、编年史都拿不到「他现在站在哪」。现在一行 = 一个角色此刻的状态，
 * 渲染、决策、记忆三条路读的是同一行——这就是「AI 说的位置 = 图上画的位置」的物理实现。</p>
 *
 * <p><b>为什么不复用 {@code actor_sandbox_state}</b>：那张表的坐标域是旧沙盘的「场景格」
 * （500×500、中心化），新图是米制（1024×1024、西南原点）。把两套坐标塞进一列，
 * 迟早会出现「Unity 里的人在墙外、网页上的人在墙里」。两域各自保留，桥接在读取侧做换算。</p>
 *
 * <p>写入口只有 {@code NpcPositionService}；其他模块一律只读（AGENTS.md 8.1 第 4 条）。</p>
 */
@Data
@Entity
@Table(name = "actor_npc_position")
public class ActorNpcPosition {

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

    /** 角色引用（npc:{id} / special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 角色名（冗余：地图页不必再 join 角色表） */
    @Column(name = "npc_name", length = 100)
    private String npcName;

    /** 当前 X（米） */
    @Column(nullable = false)
    private Double x;

    /** 当前 Z（米） */
    @Column(nullable = false)
    private Double z;

    /** 朝向（度，0=北、顺时针；仅影响箭头与「面朝哪」的描述） */
    private Double heading = 0.0;

    /** 是否在室内（1=屋里，画空心环） */
    private Integer indoor = 0;

    /** 当前活动（sleep/meal/work/errand/chat/chores，与日程段同源） */
    @Column(length = 40)
    private String activity;

    /** 当前所在/前往的地图要素 code（可空：野外/路上） */
    @Column(name = "feature_code", length = 64)
    private String featureCode;

    /** 状态：static / walking / working */
    @Column(length = 16)
    private String status = "static";

    /** 这份坐标对应的世界游戏秒（对账/外推的锚点） */
    @Column(name = "game_second", nullable = false)
    private Long gameSecond;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (heading == null) {
            heading = 0.0;
        }
        if (indoor == null) {
            indoor = 0;
        }
        if (status == null) {
            status = "static";
        }
    }
}
