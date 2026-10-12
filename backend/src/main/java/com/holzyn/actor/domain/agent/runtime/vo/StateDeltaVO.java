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

package com.holzyn.actor.domain.agent.runtime.vo;

import com.holzyn.actor.domain.agent.runtime.entity.ActorStateDelta;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 决策 state delta VO。
 */
public record StateDeltaVO(
        Long id,
        Long projectId,
        String npcRef,
        Long decisionId,
        String changesJson,
        List<String> changedFields,
        Long gameSecond,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static StateDeltaVO of(ActorStateDelta e, List<String> changedFields) {
        if (e == null) {
            return null;
        }
        return new StateDeltaVO(e.getId(), e.getProjectId(), e.getNpcRef(), e.getDecisionId(),
                e.getChangesJson(), changedFields == null ? List.of() : changedFields,
                e.getGameSecond(), e.getCreatedAt());
    }
}
