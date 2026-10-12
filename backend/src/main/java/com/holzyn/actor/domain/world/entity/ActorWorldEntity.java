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
 * 项目记忆 L3 实体状态实体，对应表 actor_world_entity。
 * <p>职责：存储「世界视角公共状态」——地点/人物/势力三域的当前状态，<b>Zep 式失效更新</b>
 * 新信息矛盾时<b>不删旧行</b>，写新状态行（supersedes_id 版本链）+ 旧行 valid_to_game_day=当前游戏日；
 * 每行带 valid_from_game_day / valid_to_game_day（NULL=当前有效）；provenance 记录来源可追溯；
 * 支持按时间点查询（as-of-date）：{@code WHERE valid_from <=:day AND (valid_to IS NULL OR valid_to >:day)}。</p>
 * <p>三域边界（决策 #10）：实体表=世界视角公共状态（谁都知道的客观事实）；角色记忆=个人关系与感受；
 * 公共状态变化经项目记忆注入让所有角色知晓。</p>
 * <p>entity_key 统一引用：{@code location:地点名} / {@code person:角色名} / {@code person:special:{id}} /
 * {@code faction:势力名}（中文名直用，名称匹配挂接）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_entity", indexes = {
        @Index(name = "idx_entity_key", columnList = "project_id, entity_key, valid_to_game_day")
})
public class ActorWorldEntity {

    /** 实体类型：地点 */
    public static final String TYPE_LOCATION = "location";
    /** 实体类型：人物 */
    public static final String TYPE_PERSON = "person";
    /** 实体类型：势力 */
    public static final String TYPE_FACTION = "faction";

    /** 来源：编年史条目 */
    public static final String PROVENANCE_CHRONICLE = "chronicle";
    /** 来源：世界事件 */
    public static final String PROVENANCE_EVENT = "event";
    /** 来源：手动更新 */
    public static final String PROVENANCE_MANUAL = "manual";
    /** 来源：批量巩固 */
    public static final String PROVENANCE_CONSOLIDATION = "consolidation";
    /** 来源：对话抽取路由（扩展，设计枚举之外的对话通道） */
    public static final String PROVENANCE_DIALOG = "dialog";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 实体类型：location/person/faction */
    @Column(name = "entity_type", nullable = false, length = 10)
    private String entityType;

    /** 统一实体键（location:村口牌坊 / person:special:110 / faction:...） */
    @Column(name = "entity_key", nullable = false, length = 100)
    private String entityKey;

    /** 显示名 */
    @Column(length = 100)
    private String name;

    /** 当前状态描述 */
    @Column(name = "current_state", columnDefinition = "TEXT", nullable = false)
    private String currentState;

    /** 最近一次变化描述（【世界动态】注入素材；NULL=无近期变化不注入） */
    @Column(name = "recent_change", columnDefinition = "TEXT")
    private String recentChange;

    /** 有效起始游戏日 */
    @Column(name = "valid_from_game_day", nullable = false)
    private Long validFromGameDay;

    /** 有效结束游戏日（NULL=当前有效；被新状态取代时=当前游戏日） */
    @Column(name = "valid_to_game_day")
    private Long validToGameDay;

    /** 版本链：被本条取代的旧状态行 id（旧行保留可追溯） */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /** 来源（chronicle/event/manual/consolidation/dialog） */
    @Column(length = 20)
    private String provenance = PROVENANCE_CHRONICLE;

    /** 来源 id（编年史/事件/手动无） */
    @Column(name = "provenance_ref")
    private Long provenanceRef;

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
        if (provenance == null || provenance.isBlank()) provenance = PROVENANCE_CHRONICLE;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
