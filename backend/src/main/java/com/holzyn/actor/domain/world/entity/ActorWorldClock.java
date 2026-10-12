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

package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;
import java.time.LocalDateTime;

/**
 * 世界时钟实体，对应表 actor_world_clock。
 * <p>职责：承载每项目的游戏时钟状态——真实时间按速率映射为游戏时间：
 * 锚点（worldStartAt 真实时刻 + worldStartGameHour 对应游戏起始小时）与 rate（每真实小时推进的游戏小时数，
 * 默认 24=1 真实小时推进 1 游戏日）；lastSimTime/lastGameHour 记录最近一次模拟推进位置（惰性补算与幂等）；
 * paused 暂停开关；lastSummary 最近推进摘要（前端展示）。</p>
 * <p>⚠️ 长跑缺陷 配套：本表是<b>多写者热点行</b>（引擎每 tick 写推进位置/基准/摘要，
 * 配置接口写 rate/paused/锚点），故开启 {@link DynamicUpdate}——任何 save/脏检查刷写都只更新**变更过**的列，
 * 不再整行覆盖，从机制上防止「后写者拿事务前快照把前写者的新值打回」。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_world_clock")
public class ActorWorldClock {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID（唯一，uk_world_clock_project） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 速率：每真实小时推进的游戏小时数（默认 24=1真实小时推进1游戏日） */
    @Column(nullable = false)
    private Integer rate = 24;

    /** 真实时刻锚点（默认=项目创建时刻） */
    @Column(name = "world_start_at")
    private LocalDateTime worldStartAt;

    /** 锚点对应的游戏起始时刻（小时数，自纪元起，默认 0） */
    @Column(name = "world_start_game_hour", nullable = false)
    private Long worldStartGameHour = 0L;

    /** 最近一次模拟推进的真实时刻（null=尚未首次推进） */
    @Column(name = "last_sim_time")
    private LocalDateTime lastSimTime;

    /**
     * 最近推进到的游戏时刻（小时数，自纪元起； 起为 last_game_second 的派生兼容字段，
     * 引擎落库时同步写 floor(lastGameSecond/3600)，存量读取方（时间线/状态/调试）不感知变化）
     */
    @Column(name = "last_game_hour", nullable = false)
    private Long lastGameHour = 0L;

    /**
     * 引擎最近推进到的游戏<b>秒</b>级位置（自纪元起；世界演化与游戏时间系统 新增，
     * ）：单一权威<b>秒级</b>模拟位置——引擎按 5 游戏分钟步进追平流动时钟时落此列，
     * last_game_hour 保留为兼容派生（floor(lastGameSecond/3600)）供既有读取方使用；
     * 存量行 该列为 NULL，requireClock 惰性初始化 = lastGameHour×3600
     */
    @Column(name = "last_game_second")
    private Long lastGameSecond = 0L;

    /** 暂停开关：0推进/1暂停（临时冻结；与「持续运行」常驻开关正交，见 worldRunning） */
    @Column(nullable = false)
    private Integer paused = 0;

    /** 暂停时刻（真实时刻）：暂停时记录用于冻结游戏时间；未暂停为 null */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /**
     * 世界持续运行常驻开关（B3 世界推进引擎）：0 关 / 1 开（缺省 1=持续运行）。
     * <p>paused 是临时冻结，running 是常驻策略——world_running=0 时引擎完全不推进该项目
     * （替代旧「活跃窗口」，修 L-02/）；存量行（B3 升级前已存在的时钟）该列为 NULL，
     * 代码统一视为 0（存量默认关，需用户在设置页手动开启，）；
     * 升级后新建的时钟行默认 1（新项目开，无人值守自转前提）。</p>
     */
    @Column(name = "world_running")
    private Integer worldRunning;

    /**
     * 引擎最近一次真正推进的真实时刻（B3 回补基准，替代「活跃窗口」判定；
     * 引擎按自此刻起的真实流逝换算游戏小时数逐步推进，null=尚未推进）
     */
    @Column(name = "last_engine_run_at")
    private LocalDateTime lastEngineRunAt;

    /** 最近推进摘要（供前端展示） */
    @Column(name = "last_summary", length = 500)
    private String lastSummary;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (rate == null) rate = 24;
        if (worldStartGameHour == null) worldStartGameHour = 0L;
        if (lastGameHour == null) lastGameHour = 0L;
        if (lastGameSecond == null) lastGameSecond = 0L;
        if (paused == null) paused = 0;
        // 新建时钟行默认「持续运行」开（新项目无人值守自转前提）；存量行不经过此处保持 NULL（视为关）
        if (worldRunning == null) worldRunning = 1;
        if (lastEngineRunAt == null) lastEngineRunAt = now;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
