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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 专门测试接口·测试运行时间线事件。
 * <p>职责：记录一次 test run 内的<b>每一条命令/干预/状态快照/评估批次引用</b>（完整回放时间线）：
 * </p>
 * <ul>
 * <li><b>command</b>：阶段控制命令（phase_pause/phase_resume/phase_advance/phase_fastforward/
 * phase_intensity），params_json=命令参数，result_json=执行结果；</li>
 * <li><b>intervene</b>：干预动作（intervene_event/intervene_action/trigger_chat），
 * params_json=干预参数，result_json=干预结果；</li>
 * <li><b>snapshot</b>：命令后的状态聚合快照（TestStateAggregator 四块聚合），
 * state_snapshot_json=快照载荷；</li>
 * <li><b>eval_ref</b>：评估批次引用（④ 评估批跑启动/完成时记录）。</li>
 * </ul>
 * <p>每次命令执行成功后自动附带一条 snapshot 事件（状态快照），供回放/报告/回归基线使用。</p>
 */
@Data
@Entity
@Table(name = "actor_test_run_event")
public class ActorTestRunEvent {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属 test run */
    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** 事件序号（同 run 内递增，回放顺序） */
    @Column(name = "seq")
    private Integer seq = 0;

    /** 事件类型：command 阶段控制命令 / intervene 干预 / snapshot 状态快照 / eval_ref 评估批次引用 */
    @Column(name = "event_type", length = 20)
    private String eventType;

    /**
     * 动作名：phase_pause/phase_resume/phase_advance/phase_fastforward/phase_intensity/
     */
    @Column(name = "action", length = 40)
    private String action;

    /** 命令/干预参数 JSON（原样回放） */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** 执行结果 JSON（status/summary/返回载荷摘要） */
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    /** 命令后的状态聚合快照 JSON（仅 snapshot 类型填充；四块聚合载荷） */
    @Column(name = "state_snapshot_json", columnDefinition = "LONGTEXT")
    private String stateSnapshotJson;

    /** 事件发生时的游戏秒位置 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 事件发生真实时刻（毫秒） */
    @Column(name = "real_ms")
    private Long realMs;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (seq == null) seq = 0;
        if (realMs == null) realMs = System.currentTimeMillis();
    }
}
