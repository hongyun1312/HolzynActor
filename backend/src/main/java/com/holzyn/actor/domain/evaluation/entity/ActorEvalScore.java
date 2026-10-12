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
 * 行为质量评估·逐样本评分表。
 * <p>职责：承载 judge 对<b>单条样本</b>的评分明细——定制五维（consistency 角色一致性 /
 * reasonability 决策合理性 / context-usage 记忆与环境利用 / foresight 前瞻性 /
 * naturalness 语言与行为自然度）逐维 1-5 绝对评分 + 逐维理由 + 总分 + judge 原始输出 JSON
 * （回放/审计用）。五维定义见 {@code behavior_judge} 内置模板与报告聚合。</p>
 * <p>偏见规避协议：绝对评分（非成对比较）+ 固定 rubric 锚点描述 +
 * 样本长度上限截断 + 逐维 chain-of-thought（先逐维评再汇总），judge 与生成模型不同家族
 * （云端 deepseek-v4-flash vs 本地 qwen3.6）规避 self-preference 偏见。</p>
 */
@Data
@Entity
@Table(name = "actor_eval_score")
public class ActorEvalScore {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属评估批次 */
    @Column(name = "eval_run_id", nullable = false)
    private Long evalRunId;

    /** 归属样本 */
    @Column(name = "sample_id", nullable = false)
    private Long sampleId;

    /** 总分（各维均值 ×10 取整，如 4.2 → 42；null=评分失败） */
    @Column(name = "total_score")
    private Integer totalScore;

    /** 逐维分数 JSON {"consistency":4,"reasonability":3,...} */
    @Column(name = "scores_json", columnDefinition = "TEXT")
    private String scoresJson;

    /** 逐维理由 JSON {"consistency":"...","reasonability":"...",...} */
    @Column(name = "reasons_json", columnDefinition = "LONGTEXT")
    private String reasonsJson;

    /** 一句话总评（judge 输出 summary） */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** judge 原始输出 JSON（评分失败时存原始响应/错误） */
    @Column(name = "judge_raw_json", columnDefinition = "LONGTEXT")
    private String judgeRawJson;

    /** 实际评分的 judge 模型 */
    @Column(name = "judge_model", length = 100)
    private String judgeModel;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
