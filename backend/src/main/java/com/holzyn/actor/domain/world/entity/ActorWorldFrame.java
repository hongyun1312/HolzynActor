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
 * 每日世界帧实体。
 * <p>职责：承载「每游戏日 00:00 生成的结构化世界状态」——天气（按场景/区域）、宏观事件列表、
 * 环境变化、信息包摘要（保留原始 AI 输出，供 actor_info_packet 实体化源数据）、前一日帧摘要
 * （连续性）与生成时输入缓存（进行中事件），双通道生成（AI 主通道 / 机制兜底）+ 紧急更新。</p>
 * <p>数据语义：
 * ① uk(project_id+game_day)——每游戏日恰生成 1 帧（幂等，缓存 24h 语义）；② status 状态机——
 * active（当前生效）唯一，新帧生成后旧帧置 superseded（保留历史可查）；③ source 三态——
 * ai（AI 主通道）/ mechanism（机制兜底）/ emergency（紧急更新就地改写）；④ emergency 0/1——
 * 是否紧急更新帧（紧急更新不新建行，就地更新当前 active 帧，uk 保持成立）。</p>
 * <p>JSON 字段（weather_json/macro_events_json/environment_changes_json/info_packets_json/
 * FrameJson 结构一一对应，落库保留原始 JSON（可回放可解析）。</p>
 */
@Data
@Entity
@Table(name = "actor_world_frame",
        uniqueConstraints = @UniqueConstraint(name = "uk_world_frame_proj_day", columnNames = {"project_id", "game_day"}),
        indexes = @Index(name = "idx_world_frame_proj_status", columnList = "project_id,status"))
public class ActorWorldFrame {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 游戏日序号（自纪元起；uk：project_id+game_day，每游戏日恰 1 帧） */
    @Column(name = "game_day", nullable = false)
    private Long gameDay;

    /**
     * 季节（spring/summer/autumn/winter；时间季节系统 起落库时按生成时刻游戏月固定绑定，
     * 不信任 AI 输出—— 「季节按游戏月固定绑定」；存量行 NULL=按 game_day 派生）
     */
    @Column(name = "season", length = 10)
    private String season;

    /** 天气 JSON（按场景/区域：{"A村": {"condition": "阴", "temperature": 15, "wind": "北风"}}） */
    @Column(name = "weather_json", columnDefinition = "TEXT")
    private String weatherJson;

    /** 宏观事件 JSON（event_id/type/location/severity/summary/start_day/duration_days/affected_area 列表） */
    @Column(name = "macro_events_json", columnDefinition = "TEXT")
    private String macroEventsJson;

    /** 环境变化 JSON（scene/change/effect 列表） */
    @Column(name = "environment_changes_json", columnDefinition = "TEXT")
    private String environmentChangesJson;

    /** 信息包摘要 JSON（生成 actor_info_packet 的源数据，保留原始 AI 输出） */
    @Column(name = "info_packets_json", columnDefinition = "TEXT")
    private String infoPacketsJson;

    /** 前一日世界帧摘要（连续性：AI 生成时注入，机制帧取前一帧摘要） */
    @Column(name = "previous_day_summary", columnDefinition = "TEXT")
    private String previousDaySummary;

    /** 进行中事件 JSON（生成时输入缓存：前一帧未过期的宏观事件等） */
    @Column(name = "ongoing_events_json", columnDefinition = "TEXT")
    private String ongoingEventsJson;

    /** 生成来源：ai（AI 主通道）/ mechanism（机制兜底）/ emergency（紧急更新就地改写） */
    @Column(name = "source", length = 20)
    private String source = "ai";

    /** 状态：active（当前生效，唯一）/ superseded（已被新帧替代） */
    @Column(name = "status", length = 20)
    private String status = "active";

    /** 生成时的游戏秒（紧急更新=就地改写时刻） */
    @Column(name = "generated_at_game_second")
    private Long generatedAtGameSecond;

    /** 是否紧急更新帧（0/1；紧急更新就地改写当前 active 帧，紧急=1） */
    @Column(name = "emergency")
    private Integer emergency = 0;

    /**
     * 宏观社会演化系统：「社会宏观」板块（回灌路①）——MacroScheduler 每旬/月
     * 确定性写入宏观简报（人口/粮价/势力/舆论/科技摘要 + 近期宏观事件叙事），不依赖 AI 帧内容；
     * 重大宏观事件发生时追加紧急摘要。 日报「社会宏观」板块直接读此列。
     */
    @Column(name = "macro_summary_json", columnDefinition = "TEXT")
    private String macroSummaryJson;

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
        if (source == null || source.isBlank()) source = "ai";
        if (status == null || status.isBlank()) status = "active";
        if (emergency == null) emergency = 0;
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
