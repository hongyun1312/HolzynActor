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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 中观组织实体。
 * <p>职责：承载中观层「组织/势力/群体」——宏观（数值引擎）与微观（个体决策）之间的结构层。
 * 组织拥有成员列表（actor_meso_membership）、内部角色、响应记录（actor_meso_response）、
 * 指令（actor_meso_directive）。组织形态通用（不硬编码任何具体世界）：</p>
 * <ul>
 * <li><b>type</b>：force 势力 / guild 行会 / sect 门派 / cult 教派 / community 社区 /
 * family 宗族 / adhoc 自发群体…（指令路由按类型匹配）；</li>
 * <li><b>origin</b>：lore 世界观文本通用解析 / template 模板语义骨架 / emergent 运行时涌现
 * （三源通用，禁止硬编码现有世界势力名）；</li>
 * <li><b>influence</b>：中观影响力（type=force 组的势力计算沿用 0-100 口径，
 * MacroFactionEngine 演进而来的值写回本列）；</li>
 * <li><b>relations</b>：组织间关系分（势力名 → -100..100）存于 meta_json["relations"]，
 * deriveFactions 派生回 MacroState.factions 供关系状态机使用。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_meso_group",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_meso_group_proj_name", columnNames = {"project_id", "name"})
        },
        indexes = {
                @Index(name = "idx_meso_group_proj", columnList = "project_id"),
                @Index(name = "idx_meso_group_type", columnList = "project_id,type")
        })
public class ActorMesoGroup {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 组织名（模板语义/世界观解析/自发成组命名——禁止硬编码具体世界） */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 组织类型：force 势力 / guild 行会 / sect 门派 / cult 教派 / community 社区 /
     * family 宗族 / adhoc 自发群体…（指令路由按类型匹配）
     */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    /** 上级组织 ID（层级：家族→宗族→势力；null=顶层） */
    @Column(name = "parent_id")
    private Long parentId;

    /** 状态：active 活跃 / dormant 沉寂 / absorbed 被吸收 / dissolved 解散 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /** 中观影响力（0-100；type=force 组的势力计算口径，MacroFactionEngine 写回） */
    @Column(name = "influence", nullable = false)
    private Double influence = 0.0;

    /** 首领引用（special:{id} / crowd:{id}；世界观解析按姓氏通用匹配，可空=背景组织） */
    @Column(name = "leader_ref", length = 50)
    private String leaderRef;

    /** 来源：lore 世界观解析 / template 模板骨架 / emergent 运行时涌现 */
    @Column(name = "origin", nullable = false, length = 20)
    private String origin = "lore";

    /** 组织标签 JSON（["商贸","粮食","村中"]…指令路由匹配用） */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    /** 扩展元数据 JSON（成立日/信条/领地/规则/relations 组织间关系分…） */
    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    /** 成立游戏秒 */
    @Column(name = "created_game_second")
    private Long createdGameSecond;

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
        if (status == null || status.isBlank()) status = "active";
        if (influence == null) influence = 0.0;
        if (origin == null || origin.isBlank()) origin = "lore";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
