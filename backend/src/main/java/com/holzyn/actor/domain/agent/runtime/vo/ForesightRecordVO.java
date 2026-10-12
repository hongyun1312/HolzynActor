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

import com.holzyn.actor.domain.agent.runtime.entity.ActorForesightRecord;

import java.time.LocalDateTime;

/**
 * 前瞻推演记录 VO。
 */
public record ForesightRecordVO(
        Long id,
        Long projectId,
        String npcRef,
        String npcName,
        Long gameSecond,
        String triggerReason,
        String situationExcerpt,
        String macroSummary,
        String candidatesJson,
        String finalAction,
        boolean success,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static ForesightRecordVO of(ActorForesightRecord e) {
        if (e == null) {
            return null;
        }
        return new ForesightRecordVO(e.getId(), e.getProjectId(), e.getNpcRef(), e.getNpcName(),
                e.getGameSecond(), e.getTriggerReason(), e.getSituationExcerpt(), e.getMacroSummary(),
                e.getCandidatesJson(), e.getFinalAction(), Boolean.TRUE.equals(e.getSuccess()),
                e.getCreatedAt());
    }
}
