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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 世界事件视图映射（B1 世界事件总线，domain/agent/event）。
 * <p>把 {@link ActorEvent} 映射为前端/调试/引擎统一的 Map 载荷——SSE 广播与查询端点共用同一映射，
 * 保证「业务事件流与调试事件流同源」 且字段口径一致。</p>
 * <p>SSE 事件名约定：{@code world-event}，载荷含
 * eventId/kind/title/locationName/characterIds/gameHour/gameTimeText 等。</p>
 */
public final class WorldEventViews {

    private WorldEventViews() {
    }

    /**
     * 事件实体 → 统一载荷 Map。
     *
     * @param e 事件实体
     *
     * @param worldClockService 世界时钟服务（解析 gameTimeText，可空则跳过）
     *
     * @return 载荷 Map（id 与 eventId 同值双字段，兼容新旧消费方）
     */
    public static Map<String, Object> toMap(ActorEvent e, WorldClockService worldClockService) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) {
            return m;
        }
        m.put("id", e.getId());
        m.put("eventId", e.getId());
        m.put("projectId", e.getProjectId());
        m.put("kind", e.getKind());
        m.put("source", e.getSource());
        m.put("title", e.getTitle());
        m.put("content", e.getContent());
        m.put("characterId", e.getCharacterId());
        m.put("characterIds", e.getCharacterIds());
        m.put("sceneId", e.getSceneId());
        m.put("evolutionId", e.getEvolutionId());
        m.put("locationId", e.getLocationId());
        m.put("locationName", e.getLocationName());
        m.put("visibility", e.getVisibility());
        m.put("payloadJson", e.getPayloadJson());
        m.put("sourceKey", e.getSourceKey());
        m.put("importance", e.getImportance());
        m.put("gameHour", e.getGameHour());
        if (worldClockService != null) {
            m.put("gameTimeText", worldClockService.gameTimeTextOf(e.getProjectId(), e.getGameHour()));
        }
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }
}
