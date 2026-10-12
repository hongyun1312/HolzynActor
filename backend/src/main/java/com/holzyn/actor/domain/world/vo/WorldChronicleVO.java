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

import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 编年史条目 VO。
 */
public record WorldChronicleVO(
        Long id,
        Long projectId,
        Long gameDay,
        Long gameHour,
        String season,
        String title,
        String content,
        Integer importance,
        String weatherSnapshot,
        List<String> involvedEntities,
        List<String> involvedCharacters,
        List<Long> eventIds,
        String source,
        List<String> tags,
        LocalDateTime createdAt) {

    /** 实体转 VO（JSON 列解析为列表，非法返回空列表）。 */
    public static WorldChronicleVO of(ActorWorldChronicle c) {
        return new WorldChronicleVO(
                c.getId(),
                c.getProjectId(),
                c.getGameDay(),
                c.getGameHour(),
                c.getSeason(),
                c.getTitle(),
                c.getContent(),
                c.getImportance(),
                c.getWeatherSnapshot(),
                parseStrings(c.getInvolvedEntities()),
                parseStrings(c.getInvolvedCharacters()),
                parseLongs(c.getEventIds()),
                c.getSource(),
                parseStrings(c.getTags()),
                c.getCreatedAt());
    }

    private static List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.holzyn.actor.domain.world.service.WorldMemoryService.parseStringList(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.holzyn.actor.domain.world.service.WorldMemoryService.parseLongList(json);
        } catch (Exception e) {
            return List.of();
        }
    }
}
