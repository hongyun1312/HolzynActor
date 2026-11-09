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

package com.holzyn.actor.domain.world.fact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 世界事实实体，对应表 {@code actor_world_fact}（§4.4.1）。
 *
 * <p><b>语义边界</b>：事件是「发生过」，事实是「现在怎样」，实体（{@code actor_world_entity}）
 * 是「他以为怎样」。三条线不合并——裁决器需要的是可机器读取的 {@code stateText/attributesJson}，
 * 不是一段需要解析的叙事。</p>
 *
 * <p><b>权威</b>：{@code authority ∈ brain|engine}。桌面端（无引擎）全部 brain；接引擎后
 * 「看得见的」事实（facility/route/stock/prop）可由 {@code EngineStateMirrorService} 写，
 * 「看不见的」（contract/reputation/office/secret/land）永远只由后端写。</p>
 *
 * <p>写入唯一入口：{@link com.holzyn.actor.domain.world.fact.service.WorldFactService}
 * （BRAIN 侧）；ENGINE 侧为 E1 的镜像服务。其他模块只读。</p>
 */
@Data
@Entity
@Table(name = "actor_world_fact", indexes = {
        @Index(name = "ix_fact_loc", columnList = "project_id,location_name,status"),
        @Index(name = "ix_fact_type", columnList = "project_id,fact_type,status"),
        @Index(name = "ix_fact_authority", columnList = "project_id,authority,status")
})
public class ActorWorldFact {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 事实类型：facility / route / stock / prop / land / contract / reputation / office / secret */
    @Column(name = "fact_type", nullable = false, length = 24)
    private String factType;

    /** 稳定键（如 {@code facility:loc:12}、{@code contract:{uuid}}）；同项目内唯一 */
    @Column(name = "fact_key", nullable = false, length = 120)
    private String factKey;

    /** 展示名（如「磨坊」「与李四的借约」） */
    @Column(length = 200)
    private String name;

    /** 一句话当前状态（供提示词与裁决器直接读取，不解析） */
    @Column(name = "state_text", nullable = false, length = 500)
    private String stateText;

    /** 结构化属性（数量/价格/开关/条件），JSON 文本 */
    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;

    /** 归一化地点 ID（可空；全局事实为空） */
    @Column(name = "location_id")
    private Long locationId;

    /** 地点名快照（可空） */
    @Column(name = "location_name", length = 100)
    private String locationName;

    /** 可见性：public 全项目 / location 同地点 / private 当事人 */
    @Column(nullable = false, length = 12)
    private String visibility = "public";

    /** 权威：brain（后端）/ engine（引擎镜像）；列宽 8 */
    @Column(nullable = false, length = 8)
    private String authority = "brain";

    /** 乐观版本号（每次变更 +1；与 fact_log.version 对齐） */
    @Column(nullable = false)
    private Integer version = 1;

    /** 生效起点（游戏秒；可空=一直有效） */
    @Column(name = "valid_from_game_second")
    private Long validFromGameSecond;

    /** 失效终点（游戏秒；可空=不自动失效） */
    @Column(name = "valid_to_game_second")
    private Long validToGameSecond;

    /** 状态：active / expired / void */
    @Column(nullable = false, length = 12)
    private String status = "active";

    /** 产生/最近一次变更的来源事件（日志里必填，事实行可空） */
    @Column(name = "source_event_id")
    private Long sourceEventId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (version == null) version = 1;
        if (visibility == null || visibility.isBlank()) visibility = "public";
        if (authority == null || authority.isBlank()) authority = "brain";
        if (status == null || status.isBlank()) status = "active";
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
