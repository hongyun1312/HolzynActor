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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 世界故事规划档案实体，对应表 actor_story_plan。
 * <p>职责：承载 story-plan 工作流的产出——<b>导演视角</b>的世界叙事档案（玩家/制作者可读，
 * <b>角色永不感知</b>，零 OOC）：</p>
 * <ul>
 * <li><b>story_plan_json</b>：故事引擎产物（三层力学引擎·世界版 surface/engine/payoff + issues）；</li>
 * <li><b>style_profile_json</b>：风格档案产物（世界风格宪法 tone/references/narrative_persona/
 * commandments/genre_notes）；</li>
 * <li><b>story_plan_md / style_profile_md</b>：渲染后的 Markdown 文档（前端展示用）；</li>
 * <li><b>atmosphere_segment</b>：<b>世界氛围片段</b>——从风格档案/引擎层提取、过滤元叙事概念
 * （读者 promise/爆点语法等）后的世界气质文本，供环境描述 / 宏观演化叙事 / 世界事件润色
 * 三处注入（角色可感知「世界的气质」，但不感知「叙事概念」，零 OOC）。</li>
 * </ul>
 * <p><b>版本化</b>：同项目每次成功生成递增 version（保留历史，可对照演进）；
 * 两段式落库——persist_story_engine 先建行（style 为空），persist_style_profile 同版本行补齐。</p>
 * <p>；worldgen 工作流负责生成，本表只存档案）</p>
 */
@Data
@Entity
@Table(name = "actor_story_plan")
public class ActorStoryPlan {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 来源 worldgen 任务 ID（actor_worldgen_task.id；幂等判重与溯源用） */
    @Column(name = "task_id")
    private Long taskId;

    /** 版本号（项目内递增：1,2,3…；每次成功生成 +1，保留历史） */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 故事引擎 JSON 原文（{"story_plan": {surface, engine, payoff, issues}}） */
    @Column(name = "story_plan_json", columnDefinition = "LONGTEXT")
    private String storyPlanJson;

    /** 风格档案 JSON 原文（{"style_profile": {tone, references, narrative_persona, commandments, genre_notes}}；风格层未生成时为空） */
    @Column(name = "style_profile_json", columnDefinition = "LONGTEXT")
    private String styleProfileJson;

    /** 故事引擎渲染 Markdown（前端展示；两段式落库第一阶段写入） */
    @Column(name = "story_plan_md", columnDefinition = "LONGTEXT")
    private String storyPlanMd;

    /** 风格档案渲染 Markdown（前端展示；风格层生成后写入） */
    @Column(name = "style_profile_md", columnDefinition = "LONGTEXT")
    private String styleProfileMd;

    /** 世界氛围片段（过滤元叙事概念后的世界气质文本，供环境/宏观/世界事件三处注入；风格层生成后写入） */
    @Column(name = "atmosphere_segment", columnDefinition = "LONGTEXT")
    private String atmosphereSegment;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充创建时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
