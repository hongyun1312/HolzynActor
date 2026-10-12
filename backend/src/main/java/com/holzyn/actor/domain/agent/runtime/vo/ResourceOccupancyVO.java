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

import com.holzyn.actor.domain.agent.runtime.entity.ActorResourceOccupancy;

import java.time.LocalDateTime;

/**
 * 资源占用登记 VO。
 */
public record ResourceOccupancyVO(
        Long id,
        Long projectId,
        String resourceKey,
        String resourceName,
        String resourceType,
        String npcRef,
        String npcName,
        Long sinceGameSecond,
        Long untilGameSecond,
        String status,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static ResourceOccupancyVO of(ActorResourceOccupancy e) {
        if (e == null) {
            return null;
        }
        return new ResourceOccupancyVO(e.getId(), e.getProjectId(), e.getResourceKey(), e.getResourceName(),
                e.getResourceType(), e.getNpcRef(), e.getNpcName(), e.getSinceGameSecond(),
                e.getUntilGameSecond(), e.getStatus(), e.getCreatedAt());
    }
}
