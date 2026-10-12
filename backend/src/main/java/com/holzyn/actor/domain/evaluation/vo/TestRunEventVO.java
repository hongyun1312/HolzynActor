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

import com.holzyn.actor.domain.evaluation.entity.ActorTestRunEvent;

import java.time.LocalDateTime;

/**
 * 测试运行时间线事件 VO。
 */
public record TestRunEventVO(
        Long id,
        Long runId,
        Integer seq,
        String eventType,
        String action,
        String paramsJson,
        String resultJson,
        String stateSnapshotJson,
        Long gameSecond,
        Long realMs,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static TestRunEventVO of(ActorTestRunEvent e) {
        if (e == null) {
            return null;
        }
        return new TestRunEventVO(e.getId(), e.getRunId(), e.getSeq(), e.getEventType(), e.getAction(),
                e.getParamsJson(), e.getResultJson(), e.getStateSnapshotJson(), e.getGameSecond(),
                e.getRealMs(), e.getCreatedAt());
    }
}
