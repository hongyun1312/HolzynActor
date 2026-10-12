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
 * 项目记忆 L2 编年史归档实体，对应表 actor_world_chronicle_archive。
 * <p>职责：编年史条目超龄（超过 chronicle-retention-years 游戏年）后的冷存储——
 * 结构同 actor_world_chronicle，追加 {@code chronicle_id}（原编年史 id）与 {@code archived_at}；
 * 归档不参与日常注入，可按游戏日检索回看（对齐角色记忆归档语义）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_chronicle_archive", indexes = {
        @Index(name = "idx_chronicle_archive_project_day", columnList = "project_id, game_day")
})
public class ActorWorldChronicleArchive {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原编年史条目 id */
    @Column(name = "chronicle_id", nullable = false)
    private Long chronicleId;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日（事件日） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** 游戏时刻（可空） */
    @Column(name = "game_hour")
    private Long gameHour;

    /** 季节（第二轮填充） */
    @Column(length = 10)
    private String season;

    /** 标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 内容（原文保留，不摘要化） */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 重要度 1-5 */
    private Integer importance = 3;

    /** 天气快照（JSON，可空） */
    @Column(name = "weather_snapshot", columnDefinition = "TEXT")
    private String weatherSnapshot;

    /** 关联实体引用列表（JSON，可空） */
    @Column(name = "involved_entities", columnDefinition = "TEXT")
    private String involvedEntities;

    /** 关联角色引用列表（JSON，可空） */
    @Column(name = "involved_characters", columnDefinition = "TEXT")
    private String involvedCharacters;

    /** 关联事件 id 列表（JSON，可空） */
    @Column(name = "event_ids", columnDefinition = "TEXT")
    private String eventIds;

    /** 提炼来源（归档时快照） */
    @Column(length = 20)
    private String source;

    /** 标签列表 */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** 归档时间 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

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
        if (archivedAt == null) archivedAt = now;
        if (importance == null) importance = 3;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
