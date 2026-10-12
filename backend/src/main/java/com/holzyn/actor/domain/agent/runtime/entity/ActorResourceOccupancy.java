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

package com.holzyn.actor.domain.agent.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策与协调 资源占用登记实体（表 actor_resource_occupancy，）。
 * <p>职责：为「高争抢资源」（摊位/医馆/铁匠铺等地点性资源）登记专用占用——行动目标命中
 * 资源键（归一化目标地点/摆放名）时登记占用（占用者/起止游戏秒），冲突检测据此判定
 * 「该资源已被其他 NPC 占用」；到期（until_game_second）自动失效，换目标/换地点自动释放
 * 自身旧占用。零 AI，纯确定性。</p>
 * <p>语义：既有判定（进行中
 * 计划目标地点/目标角色正忙）之外，资源登记表为争抢场景提供精确占用依据。</p>
 */
@Data
@Entity
@Table(name = "actor_resource_occupancy",
        indexes = @Index(name = "idx_occupancy_proj_key", columnList = "project_id,resource_key"))
public class ActorResourceOccupancy {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 资源键（归一化目标地点/摆放名，trim+小写；冲突匹配基准） */
    @Column(name = "resource_key", nullable = false, length = 200)
    private String resourceKey;

    /** 资源展示名（原文） */
    @Column(name = "resource_name", length = 200)
    private String resourceName;

    /** 资源类型：location 地点 / placement 沙盘摆放 / object 对象（预留） */
    @Column(name = "resource_type", length = 20)
    private String resourceType = "location";

    /** 占用者引用（special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 占用者名快照 */
    @Column(name = "npc_name", length = 64)
    private String npcName;

    /** 占用起始游戏秒 */
    @Column(name = "since_game_second")
    private Long sinceGameSecond;

    /** 占用到期游戏秒（=起始+行动耗时；到点自动失效） */
    @Column(name = "until_game_second")
    private Long untilGameSecond;

    /** 状态：active 占用中 / released 已释放 */
    @Column(name = "status", length = 20)
    private String status = "active";

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = "active";
        if (resourceType == null || resourceType.isBlank()) resourceType = "location";
    }
}
