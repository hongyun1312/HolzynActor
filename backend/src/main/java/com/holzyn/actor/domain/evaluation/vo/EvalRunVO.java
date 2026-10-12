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

package com.holzyn.actor.domain.evaluation.vo;

import com.holzyn.actor.domain.evaluation.entity.ActorEvalRun;

import java.time.LocalDateTime;

/**
 * 评估批次 VO。
 */
public record EvalRunVO(
        Long id,
        Long projectId,
        String sampleSource,
        Integer sampleCount,
        Integer scoredCount,
        String judgeProvider,
        String judgeModel,
        String rubricVersion,
        String status,
        String summaryJson,
        String reportJson,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime finishedAt) {

    /** 实体 → VO。 */
    public static EvalRunVO of(ActorEvalRun e) {
        if (e == null) {
            return null;
        }
        return new EvalRunVO(e.getId(), e.getProjectId(), e.getSampleSource(), e.getSampleCount(),
                e.getScoredCount(), e.getJudgeProvider(), e.getJudgeModel(), e.getRubricVersion(),
                e.getStatus(), e.getSummaryJson(), e.getReportJson(), e.getErrorMessage(),
                e.getCreatedAt(), e.getFinishedAt());
    }
}
