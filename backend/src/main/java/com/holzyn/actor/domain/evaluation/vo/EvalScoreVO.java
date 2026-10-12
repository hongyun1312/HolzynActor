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

import com.holzyn.actor.domain.evaluation.entity.ActorEvalScore;

import java.time.LocalDateTime;

/**
 * 逐样本评分 VO。
 */
public record EvalScoreVO(
        Long id,
        Long evalRunId,
        Long sampleId,
        Integer totalScore,
        String scoresJson,
        String reasonsJson,
        String summary,
        String judgeRawJson,
        String judgeModel,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static EvalScoreVO of(ActorEvalScore e) {
        if (e == null) {
            return null;
        }
        return new EvalScoreVO(e.getId(), e.getEvalRunId(), e.getSampleId(), e.getTotalScore(),
                e.getScoresJson(), e.getReasonsJson(), e.getSummary(), e.getJudgeRawJson(),
                e.getJudgeModel(), e.getCreatedAt());
    }
}
