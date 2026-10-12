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

import com.holzyn.actor.domain.evaluation.entity.ActorTestRun;

import java.time.LocalDateTime;

/**
 * 测试运行记录 VO。
 */
public record TestRunVO(
        Long id,
        Long projectId,
        String name,
        String purpose,
        String status,
        Long startRealMs,
        Long endRealMs,
        Long startGameSecond,
        Long endGameSecond,
        Integer commandCount,
        Integer interveneCount,
        String evalRunIdsJson,
        String summaryJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 实体 → VO。 */
    public static TestRunVO of(ActorTestRun e) {
        if (e == null) {
            return null;
        }
        return new TestRunVO(e.getId(), e.getProjectId(), e.getName(), e.getPurpose(), e.getStatus(),
                e.getStartRealMs(), e.getEndRealMs(), e.getStartGameSecond(), e.getEndGameSecond(),
                e.getCommandCount(), e.getInterveneCount(), e.getEvalRunIdsJson(), e.getSummaryJson(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
