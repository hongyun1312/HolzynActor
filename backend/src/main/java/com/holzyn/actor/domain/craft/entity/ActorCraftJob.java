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

package com.holzyn.actor.domain.craft.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一件正在做的活（表 {@code actor_craft_job}）。
 *
 * <p><b>为什么制作要有作业行</b>：「瞬间把铁矿变成柴刀」是最不真实的一种实现——
 * 角色不用守炉子、设施不会被占用、旁人看不到他在干活。作业行让「正在打铁」成为
 * 世界里的真实状态：设施被占、到点才出活、人走了活就废了。</p>
 *
 * <p><b>材料在开工时扣</b>（严格「没有就是没有」）：若等完工再扣，两个角色能同时开工
 * 抢同一批料，世界会出现「料只够一份却做出两件」。</p>
 */
@Data
@Entity
@Table(name = "actor_craft_job",
        indexes = {
                @Index(name = "ix_craftjob_due", columnList = "project_id, status, finish_game_second"),
                @Index(name = "ix_craftjob_actor", columnList = "project_id, actor_ref, status")
        })
public class ActorCraftJob {

    /** 状态：进行中 */
    public static final String STATUS_IN_PROGRESS = "in_progress";

    /** 状态：已完成（产物已出） */
    public static final String STATUS_DONE = "done";

    /** 状态：已取消（主动放弃，材料不退） */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 状态：失败（中途离场/结算异常，材料不退） */
    public static final String STATUS_FAILED = "failed";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 干活的人（{@code npc:{id}}） */
    @Column(name = "actor_ref", nullable = false, length = 64)
    private String actorRef;

    /** 配方键 */
    @Column(name = "recipe_key", nullable = false, length = 60)
    private String recipeKey;

    /** 产出物品键 */
    @Column(name = "output_item_key", nullable = false, length = 60)
    private String outputItemKey;

    /** 产出形态（item/facility/prop） */
    @Column(name = "output_kind", nullable = false, length = 12)
    private String outputKind = "item";

    /** 单份产出数量 */
    @Column(name = "output_qty", nullable = false)
    private Integer outputQty = 1;

    /** 份数 */
    @Column(name = "batches", nullable = false)
    private Integer batches = 1;

    /** 需要的设施类型（可空） */
    @Column(name = "facility_type", length = 24)
    private String facilityType;

    /** 使用的设施 ref（可空=随处可做） */
    @Column(name = "facility_ref", length = 64)
    private String facilityRef;

    /** 地点 ID（建造产物落点） */
    @Column(name = "location_id")
    private Long locationId;

    /** 地点名（中途离场判定用） */
    @Column(name = "location_name", length = 120)
    private String locationName;

    /** 开工游戏秒 */
    @Column(name = "started_game_second", nullable = false)
    private Long startedGameSecond;

    /** 预计完工游戏秒 */
    @Column(name = "finish_game_second", nullable = false)
    private Long finishGameSecond;

    /** 状态（in_progress/done/cancelled/failed） */
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_IN_PROGRESS;

    /** 取消/失败原因（可空） */
    @Column(name = "reason", length = 64)
    private String reason;

    /** 触发事件 ID（因果挂点，可空） */
    @Column(name = "event_id")
    private Long eventId;

    /** 幂等键（唯一索引 project_id + idempotency_key） */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 是否还在做。
     *
     * @return true=进行中
     */
    public boolean inProgress() {
        return STATUS_IN_PROGRESS.equals(status);
    }

    /**
     * 总产出（份数 × 单份）。
     *
     * @return 总产出
     */
    public int totalOutput() {
        int per = outputQty == null ? 0 : outputQty;
        int b = batches == null ? 0 : batches;
        return per * b;
    }
}
