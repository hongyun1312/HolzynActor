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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信息扩散状态实体。
 * <p>职责：物化「信息包到达某区域」的扩散状态——每包每区域一条（packet_id+region 语义唯一），
 * 记录到达游戏时刻/游戏日、中间传播节点（商队/信使…）与到达时应用的失真参数（调试/回放）。
 * 数据由 信息扩散引擎（InfoDiffusionService 每游戏小时规则扩散）写入； 仅建表（扩散逻辑
 * 留，本表 无数据）。</p>
 * <p>检索维度（主开发方案 §5.4）：(packet_id, region) 按包查到达区域；(project_id, game_day)
 * 按项目按日回放扩散进程。三层过滤的空间边界（NPC 所在地 ∈ 信息包 affected_area 且已扩散到达）
 * 即查询本表。</p>
 */
@Data
@Entity
@Table(name = "actor_info_diffusion",
        indexes = {
                @Index(name = "idx_info_diffusion_packet_region", columnList = "packet_id,region"),
                @Index(name = "idx_info_diffusion_proj_day", columnList = "project_id,game_day")
        })
public class ActorInfoDiffusion {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 信息包 ID（actor_info_packet.id） */
    @Column(name = "packet_id", nullable = false)
    private Long packetId;

    /** 所属项目 ID（冗余查询） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 到达区域（场景/地点名） */
    @Column(name = "region", length = 100)
    private String region;

    /** 到达游戏时刻（秒） */
    @Column(name = "reached_game_second")
    private Long reachedGameSecond;

    /** 到达游戏日（检索/回放用） */
    @Column(name = "game_day")
    private Long gameDay;

    /** 中间传播节点（商队/信使…，可空） */
    @Column(name = "relayed_by", length = 100)
    private String relayedBy;

    /** 到达时应用的失真参数 JSON（调试/回放） */
    @Column(name = "distortion_applied_json", columnDefinition = "TEXT")
    private String distortionAppliedJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
