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
 * 行为质量评估·评估批次主表。
 * <p>职责：承载一次「LLM-as-Judge 离线批跑」的批次元信息与聚合报告——
 * 从样本源 取 N 条真实决策样本，
 * 逐样本调用独立云端 judge 模型（与生成模型不同家族，规避 self-preference 偏见）按
 * <b>定制五维 rubric（1-5 绝对评分）</b>打分，落 {@link ActorEvalSample}
 * 与 {@link ActorEvalScore} 后聚合成批次报告。</p>
 * <p>状态机：pending（已创建未启动）→ running（批跑中）→ done（完成）/ failed（失败）；
 * 批跑为异步（虚拟线程 4 路并发），不阻塞测试接口。</p>
 */
@Data
@Entity
@Table(name = "actor_eval_run")
public class ActorEvalRun {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 样本来源：db=当前项目 DB 采样 / jsonl=外部 ai-calls.jsonl 导入（双通道） */
    @Column(name = "sample_source", length = 20)
    private String sampleSource = "db";

    /** 本批次样本数 */
    @Column(name = "sample_count")
    private Integer sampleCount = 0;

    /** 已成功评分样本数 */
    @Column(name = "scored_count")
    private Integer scoredCount = 0;

    /** judge 供应商标识（如 ark-coding-v3） */
    @Column(name = "judge_provider", length = 60)
    private String judgeProvider;

    /** judge 模型名（如 deepseek-v4-flash） */
    @Column(name = "judge_model", length = 100)
    private String judgeModel;

    /** rubric 版本（v1；升级 rubric 时递增，报告与历史批次可区分） */
    @Column(name = "rubric_version", length = 20)
    private String rubricVersion = "v1";

    /** 状态：pending/running/done/failed */
    @Column(name = "status", length = 20)
    private String status = "pending";

    /** 批次汇总 JSON（各维均值/分布/低分样本/高分样本） */
    @Column(name = "summary_json", columnDefinition = "LONGTEXT")
    private String summaryJson;

    /** 完整报告 JSON（汇总 + 逐样本评分明细引用） */
    @Column(name = "report_json", columnDefinition = "LONGTEXT")
    private String reportJson;

    /** 失败原因（批次级失败时填充） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 完成时间（done/failed 时填充） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (status == null || status.isBlank()) status = "pending";
        if (sampleCount == null) sampleCount = 0;
        if (scoredCount == null) scoredCount = 0;
        if (rubricVersion == null || rubricVersion.isBlank()) rubricVersion = "v1";
        if (sampleSource == null || sampleSource.isBlank()) sampleSource = "db";
    }
}
