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

package com.holzyn.actor.domain.world.authority.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权威模式状态，对应表 {@code actor_world_authority_state}（每个世界一行）。
 *
 * <p>职责：记录当前权威模式（brain/engine）、引擎最后上报 seq、最后连接/交接时间、
 * 交接时的 drift。不是「第六层」，只是状态层的一个属性容器。</p>
 *
 * <p>写入口：{@link com.holzyn.actor.domain.world.authority.service.WorldAuthorityService}。</p>
 */
@Data
@Entity
@Table(name = "actor_world_authority_state")
public class ActorWorldAuthorityState {

    /** 主键 = 项目/世界 ID（不生成，打开世界时 upsert） */
    @Id
    @Column(name = "project_id")
    private Long projectId;

    /** 当前模式：brain / engine */
    @Column(nullable = false, length = 12)
    private String mode = "brain";

    /** 引擎最后成功应用的 seq（缺号检测基准） */
    @Column(name = "last_engine_seq")
    private Long lastEngineSeq;

    /** 引擎最后连接时间 */
    @Column(name = "last_engine_connected_at")
    private LocalDateTime lastEngineConnectedAt;

    /** 最后一次完整交接时间（关闭世界前快照回写） */
    @Column(name = "last_handover_at")
    private LocalDateTime lastHandoverAt;

    /** 最后记录的游戏秒 */
    @Column(name = "last_game_second")
    private Long lastGameSecond;

    /** 交接 drift 明细 JSON */
    @Column(name = "drift_json", columnDefinition = "TEXT")
    private String driftJson;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
