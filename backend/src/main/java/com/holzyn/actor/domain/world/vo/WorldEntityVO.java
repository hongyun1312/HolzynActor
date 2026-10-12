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

package com.holzyn.actor.domain.world.vo;

import com.holzyn.actor.domain.world.entity.ActorWorldEntity;

import java.time.LocalDateTime;

/**
 * 实体状态 VO。
 */
public record WorldEntityVO(
        Long id,
        Long projectId,
        String entityType,
        String entityKey,
        String name,
        String currentState,
        String recentChange,
        Long validFromGameDay,
        Long validToGameDay,
        Long supersedesId,
        String provenance,
        Long provenanceRef,
        LocalDateTime createdAt) {

    /** 实体转 VO。 */
    public static WorldEntityVO of(ActorWorldEntity e) {
        return new WorldEntityVO(
                e.getId(),
                e.getProjectId(),
                e.getEntityType(),
                e.getEntityKey(),
                e.getName(),
                e.getCurrentState(),
                e.getRecentChange(),
                e.getValidFromGameDay(),
                e.getValidToGameDay(),
                e.getSupersedesId(),
                e.getProvenance(),
                e.getProvenanceRef(),
                e.getCreatedAt());
    }
}
