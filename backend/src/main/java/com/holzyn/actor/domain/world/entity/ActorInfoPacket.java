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
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信息包实体。
 * <p>职责：把世界帧中的「信息包」（可传播的消息/流言，如「B城粮价上涨」）实体化为独立行——
 * 每个信息包带 origin（发生地）/ content（原始内容）/ reliability（可信度）/ spread_channels
 * （传播渠道）/ spread_speed_days_per_km（传播速度，天/公里）/ valid_until_day（有效期游戏日）/
 * distortion_rules（失真规则列表）/ visibility_condition（社会层过滤条件：profession/affiliation/
 * location），供 信息扩散（InfoDiffusionService 每小时规则扩散）+ 三层过滤（空间→社会→认知）消费。</p>
 * <p>交付语义：世界帧生成时把帧内 info_packages 列表实体化落本表（frame_id 关联）；
 * 紧急更新就地改写帧时替换该帧全部信息包（删旧建新）。status=active/expired 状态由 扩散维护
 * （valid_until 过期即停）， 落库即 active。</p>
 */
@Data
@Entity
@Table(name = "actor_info_packet",
        indexes = {
                @Index(name = "idx_info_packet_frame", columnList = "frame_id"),
                @Index(name = "idx_info_packet_proj_status", columnList = "project_id,status")
        })
public class ActorInfoPacket {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属世界帧 ID（actor_world_frame.id；**可空**——宏观事件信息包 frame_id=null 独立于帧包
     * （回灌路③：macro: 前缀包直接落本表经信息扩散，不挂帧；⚠️ 冒烟抓修：原
     * nullable=false 与宏观包设计冲突 → 每日本宏观事件信息包保存必失败 → 事务 rollback-only
     * → 每日 tick 整轮回滚；实体放宽 + 存量库 ALTER COLUMN FRAME_ID SET NULL）
     */
    @Column(name = "frame_id", nullable = true)
    private Long frameId;

    /** 所属项目 ID（冗余查询） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 信息包编号（如 I-12-001-1，帧内唯一） */
    @Column(name = "info_id", length = 50)
    private String infoId;

    /** 信息发生地 */
    @Column(name = "origin", length = 100)
    private String origin;

    /** 信息内容（原始） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 可信度 0~1（1=完全可信） */
    private Double reliability;

    /** 传播渠道 JSON（商队/难民/信使…） */
    @Column(name = "spread_channels_json", columnDefinition = "TEXT")
    private String spreadChannelsJson;

    /** 传播速度 */
    @Column(name = "spread_speed_days_per_km")
    private Double spreadSpeedDaysPerKm;

    /** 有效期（游戏日序号，超过即停传） */
    @Column(name = "valid_until_day")
    private Long validUntilDay;

    /** 失真规则 JSON（距离越远越夸大/职业刻意隐瞒…） */
    @Column(name = "distortion_rules_json", columnDefinition = "TEXT")
    private String distortionRulesJson;

    /** 社会层过滤条件 JSON */
    @Column(name = "visibility_condition_json", columnDefinition = "TEXT")
    private String visibilityConditionJson;

    /** 状态：active / expired */
    @Column(name = "status", length = 20)
    private String status = "active";

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
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
