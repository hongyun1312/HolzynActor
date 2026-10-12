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

package com.holzyn.actor.domain.evaluation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 专门测试接口·测试运行记录主表。
 * <p>职责：承载一次「test run」（专门测试接口驱动的测试会话）的完整生命周期元信息——
 * 一次 test run = 阶段控制命令序列 + 干预动作 + 命令后状态快照 + 评估批次引用，
 * 与 {@link ActorTestRunEvent}（时间线子表）构成<b>完整回放时间线</b>。
 * 测试运行过程不污染正式世界运行数据：本表仅记录「做了什么/看到了什么状态/结果如何」。</p>
 * <p>状态机：active（运行中）→ finished（正常收尾）/ aborted（中途中止）；同一项目可并存多个 run。</p>
 */
@Data
@Entity
@Table(name = "actor_test_run")
public class ActorTestRun {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 运行名称（测试人员可读标识） */
    @Column(name = "name", length = 100)
    private String name;

    /** 运行目的说明（本次测试要验证什么） */
    @Column(name = "purpose", columnDefinition = "TEXT")
    private String purpose;

    /** 状态：active 运行中 / finished 正常收尾 / aborted 中止 */
    @Column(name = "status", length = 20)
    private String status = "active";

    /** 开始真实时刻（毫秒） */
    @Column(name = "start_real_ms")
    private Long startRealMs;

    /** 结束真实时刻（毫秒；未结束为 null） */
    @Column(name = "end_real_ms")
    private Long endRealMs;

    /** 开始时的游戏秒位置（引擎模拟位置） */
    @Column(name = "start_game_second")
    private Long startGameSecond;

    /** 结束时的游戏秒位置 */
    @Column(name = "end_game_second")
    private Long endGameSecond;

    /** 阶段控制命令数（phase_*） */
    @Column(name = "command_count")
    private Integer commandCount = 0;

    /** 干预动作数（intervene 系列 / trigger_chat） */
    @Column(name = "intervene_count")
    private Integer interveneCount = 0;

    /** 关联评估批次 ID 列表 JSON */
    @Column(name = "eval_run_ids_json", columnDefinition = "TEXT")
    private String evalRunIdsJson;

    /** 收尾汇总 JSON（起止游戏位置/耗时/各类动作计数/结论备注） */
    @Column(name = "summary_json", columnDefinition = "LONGTEXT")
    private String summaryJson;

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
        if (commandCount == null) commandCount = 0;
        if (interveneCount == null) interveneCount = 0;
        if (startRealMs == null) startRealMs = System.currentTimeMillis();
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
