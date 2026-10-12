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
import java.time.LocalDateTime;

/**
 * 项目记忆 L2 编年史条目实体，对应表 actor_world_chronicle。
 * <p>职责：存储 AI 提炼的「世界发生了什么」——事件+天气快照+影响+关联（《记忆系统重构与
 * 世界时间季节系统_设计方案》§4.3）。与角色记忆（actor_memory）双系统并列：
 * 本表=世界视角公共编年史，所有角色经注入知晓；角色记忆=个人视角与感受。</p>
 * <p>提炼触发（三重）：① 重要事件即时（actor_event 重要度≥阈值，source=instant）② 每日批量
 * （游戏日 01:00 错峰帧生成，source=daily）③ 定时批量巩固补漏（source=consolidation）；
 * 手动补充 source=manual。保留策略：默认保留最近 3 游戏年，超龄→actor_world_chronicle_archive。</p>
 * <p>season 列。</p>
 * <p>weather_snapshot 列。</p>
 */
@Data
@Entity
@Table(name = "actor_world_chronicle", indexes = {
        @Index(name = "idx_chronicle_project_day", columnList = "project_id, game_day"),
        @Index(name = "idx_chronicle_project_imp", columnList = "project_id, importance")
})
public class ActorWorldChronicle {

    /** 提炼来源：重要事件即时 */
    public static final String SOURCE_INSTANT = "instant";
    /** 提炼来源：每日批量 */
    public static final String SOURCE_DAILY = "daily";
    /** 提炼来源：定时批量巩固补漏 */
    public static final String SOURCE_CONSOLIDATION = "consolidation";
    /** 提炼来源：手动补充 */
    public static final String SOURCE_MANUAL = "manual";
    /**
     * 提炼来源：世界诞生记录（_05：完整模式 worldgen 创建完成时写入；
     * 仅作「世界视角背景存档」，<b>不进任何角色感知注入</b>——防角色 OOC 感知「世界被创建」）
     */
    public static final String SOURCE_WORLDGEN = "worldgen";
    /** 标签：宏观社会演化来源 */
    public static final String TAG_MACRO = "macro";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日（事件日，自纪元起 floorDiv(gameSecond,86400)） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 游戏时刻（事件时刻，小时数，可空） */
    @Column(name = "game_hour")
    private Long gameHour;

    /** 季节 */
    @Column(length = 10)
    private String season;

    /** 标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 发生了什么+影响（AI 提炼，TEXT） */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 重要度 1-5 */
    private Integer importance = 3;

    /** 天气快照 */
    @Column(name = "weather_snapshot", columnDefinition = "TEXT")
    private String weatherSnapshot;

    /** 关联实体引用列表（JSON 数组：["location:村口牌坊","person:special:110","faction:..."]） */
    @Column(name = "involved_entities", columnDefinition = "TEXT")
    private String involvedEntities;

    /** 关联角色引用列表（JSON 数组：["special:110","crowd:12"]） */
    @Column(name = "involved_characters", columnDefinition = "TEXT")
    private String involvedCharacters;

    /** 关联 actor_event id 列表（JSON 数组，幂等键：已提炼事件不再重复提炼） */
    @Column(name = "event_ids", columnDefinition = "TEXT")
    private String eventIds;

    /** 提炼来源：instant/daily/consolidation/manual */
    @Column(length = 20)
    private String source = SOURCE_DAILY;

    /** 标签列表 */
    @Column(columnDefinition = "TEXT")
    private String tags;

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
        if (importance == null) importance = 3;
        if (source == null || source.isBlank()) source = SOURCE_DAILY;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
