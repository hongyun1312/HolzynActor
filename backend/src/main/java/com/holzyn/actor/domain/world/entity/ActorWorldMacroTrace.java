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
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 全链路 trace 实体。
 * <p>职责：承载「宏观 tick 输入 → 输出 → 回灌」全链路回放记录（次对接文档 阶段项②：
 * 入账→引擎→快照→事件→回灌逐跳可见）——每次宏观 tick（daily/faction/monthly）落一行：</p>
 * <ul>
 * <li><b>input_json</b>：tick 输入摘要（daily=季节/天气/连续累积串/前日入账聚合；
 * faction=近旬入账贡献/民怨/收成结果；monthly=文化入账/科技入账/话题/事件类型）；</li>
 * <li><b>params_json</b>：引擎推进后关键参数（收成系数/粮价/库存/民怨/人口/势力/议题/科技等）；</li>
 * <li><b>event_ids_json</b>：本 tick 产出的宏观事件主键列表（事件表回看跳转）；</li>
 * <li><b>feedback_json</b>：回灌落点审计（帧「社会宏观」板块写入 / 编年史「宏观」标签条数 /
 * 信息包条数 / 感知段联动标记）。</li>
 * </ul>
 * <p>数据语义：uk(project_id+game_day+tick_type) 每周期每类型恰一行（与快照表同口径幂等）；
 * 滚动上限每项目最近 {@code MacroTraceService.KEEP_PER_PROJECT} 行（默认 500，超出删最旧——
 * 防长跑膨胀）；trace 记录失败零阻断（tick 主流程隔离）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_macro_trace",
        uniqueConstraints = @UniqueConstraint(name = "uk_macro_trace_proj_day_type",
                columnNames = {"project_id", "game_day", "tick_type"}),
        indexes = @Index(name = "idx_macro_trace_proj_day", columnList = "project_id,game_day"))
public class ActorWorldMacroTrace {

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

    /** 游戏秒（tick 触发时刻审计） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** tick 类型：daily/faction/monthly（uk 之一） */
    @Column(name = "tick_type", nullable = false, length = 20)
    private String tickType;

    /** tick 输入摘要 JSON（入账聚合/季节天气/连续累积/话题——回放「输入」跳） */
    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    /** 引擎推进后关键参数 JSON（收成/粮价/民怨/人口/势力/议题/科技——回放「引擎」跳） */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** 本 tick 产出宏观事件主键列表 JSON（事件表回看跳转——回放「事件」跳） */
    @Column(name = "event_ids_json", columnDefinition = "TEXT")
    private String eventIdsJson;

    /** 回灌落点审计 JSON（帧板块写入/编年史标签/信息包/感知联动——回放「回灌」跳） */
    @Column(name = "feedback_json", columnDefinition = "TEXT")
    private String feedbackJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充创建时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
