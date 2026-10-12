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

import com.holzyn.actor.domain.agent.runtime.entity.ActorConflictRecord;

import java.time.LocalDateTime;

/**
 * 冲突检测记录 VO。
 */
public record ConflictRecordVO(
        Long id,
        Long projectId,
        Long gameSecond,
        String npcRef,
        String npcName,
        String resourceKey,
        String resourceName,
        String conflictType,
        String opponentRef,
        String opponentName,
        Integer myPriority,
        Integer opponentPriority,
        String resolution,
        String negotiateJson,
        String actionBefore,
        String actionAfter,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static ConflictRecordVO of(ActorConflictRecord e) {
        if (e == null) {
            return null;
        }
        return new ConflictRecordVO(e.getId(), e.getProjectId(), e.getGameSecond(), e.getNpcRef(),
                e.getNpcName(), e.getResourceKey(), e.getResourceName(), e.getConflictType(),
                e.getOpponentRef(), e.getOpponentName(), e.getMyPriority(), e.getOpponentPriority(),
                e.getResolution(), e.getNegotiateJson(), e.getActionBefore(), e.getActionAfter(),
                e.getCreatedAt());
    }
}
