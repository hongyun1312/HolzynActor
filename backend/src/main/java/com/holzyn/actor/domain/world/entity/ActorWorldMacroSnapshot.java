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
 * 宏观社会演化 · 宏观指标快照实体。
 * <p>职责：承载「宏观指标按 tick 周期行的历史快照」——人口/经济每日行、势力/舆论每旬行、
 * 文化/科技每月行，供曲线回看 与「每日快照断言」验收。</p>
 * <p>数据语义：
 * ① uk(project_id+game_day+tick_type)——每周期每类型恰一行（幂等，同 tick 不重复推进）；
 * ② tick_type 四态：init（初始化行）/ daily（人口+经济）/ faction（势力+舆论）/ monthly（文化+科技）；
 * ③ indicators_json 存该 tick 涉及子系统的全部指标（JSON 对象），summary 为人类可读简报文本；
 * ④ 滚动上限（每项目快照行数上限，默认 1000，超出删最旧 init/daily 行——对齐既有
 * actor_state_history 滚动保留语义）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_macro_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_macro_snapshot_proj_day_type",
                columnNames = {"project_id", "game_day", "tick_type"}),
        indexes = @Index(name = "idx_macro_snapshot_proj_day", columnList = "project_id,game_day"))
public class ActorWorldMacroSnapshot {

    /** tick 类型：初始化 */
    public static final String TICK_INIT = "init";
    /** tick 类型：每日（人口+经济） */
    public static final String TICK_DAILY = "daily";
    /** tick 类型：每旬（势力+舆论） */
    public static final String TICK_FACTION = "faction";
    /** tick 类型：每月（文化+科技） */
    public static final String TICK_MONTHLY = "monthly";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日序号（自纪元起；uk：project_id+game_day+tick_type） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /** tick 类型：init/daily/faction/monthly（uk 之一） */
    @Column(name = "tick_type", nullable = false, length = 20)
    private String tickType;

    /**
     * 宏观指标 JSON（该 tick 涉及子系统的全部指标对象：
     * population/economy/faction/culture/tech 五段，与 MacroStateService 序列化口径一致）
     */
    @Column(name = "indicators_json", columnDefinition = "TEXT")
    private String indicatorsJson;

    /** 人类可读简报文本（供帧「社会宏观」板块/日报复用） */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

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
        if (tickType == null || tickType.isBlank()) tickType = TICK_DAILY;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
