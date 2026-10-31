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

import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import java.time.LocalDateTime;

/**
 * 世界观设定视图对象（WorldSettingVO）。
 * <p>职责：向前端返回世界观设定的全部字段。</p>
 */
public record WorldSettingVO(
        Long id,
        Long projectId,
        Integer version,
        String name,
        String genre,
        String tone,
        String era,
        String geography,
        String factions,
        String magicSystem,
        String culture,
        String history,
        String freeText,
        Integer status,
        Long worldgenTaskId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param w 世界观实体
     *
     * @return VO 对象
     */
    public static WorldSettingVO of(ActorWorldSetting w) {
        return new WorldSettingVO(w.getId(), w.getProjectId(), w.getVersion(), w.getName(), w.getGenre(),
                w.getTone(), w.getEra(), w.getGeography(), w.getFactions(), w.getMagicSystem(), w.getCulture(),
                w.getHistory(), w.getFreeText(), w.getStatus(), w.getWorldgenTaskId(),
                w.getCreatedAt(), w.getUpdatedAt());
    }
}