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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 编年史提炼 AI 输出解析器。
 * <p>职责：解析 chronicle_distill 模板输出——AI 从「当日事件流 + 当日天气帧 + 环境快照」
 * 提炼当日编年史条目数组：</p>
 * <pre>
 * { "title": "村口古树遭雷击", "content": "村口的老槐树被雷劈断了大半，乡邻们议论纷纷",
 * "importance": 4, "event_ids": [1234, 1235],
 * "involved_entities": ["location:村口牌坊", "faction:村民"],
 * </pre>
 * <p>归一化：title 缺省取 content 前 40 字；importance 钳制 1~5 缺省 3；event_ids/involved_* 非数组丢弃；
 * 非法条目（content 空白）安全丢弃。gameDay 不信任 AI 输出（调用方传入为准）。</p>
 */
public final class ChronicleDistillParse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChronicleDistillParse() {
    }

    /**
     * 解析编年史提炼输出。
     *
     * @param aiText AI 输出文本（可 null/非法）
     *
     * @return 提炼条目列表（非法/空返回空列表）
     */
    public static List<ChronicleEntry> parse(String aiText) {
        List<ChronicleEntry> out = new ArrayList<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.isArray() ? root : root.path("entries");
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode item : arr) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String content = item.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                String title = item.path("title").asText("");
                if (title.isBlank()) {
                    title = content.length() > 40 ? content.substring(0, 40) : content;
                }
                int importance = item.path("importance").asInt(3);
                importance = Math.max(1, Math.min(5, importance));
                List<Long> eventIds = readLongArray(item, "event_ids");
                List<String> entities = readStringArray(item, "involved_entities");
                List<String> characters = readStringArray(item, "involved_characters");
                out.add(new ChronicleEntry(title.trim(), content.trim(), importance, eventIds, entities, characters));
            }
        } catch (Exception e) {
            // 解析失败返回已解析部分
            return out;
        }
        return out;
    }

    /** 读 Long 数组（非数组返回空列表）。 */
    private static List<Long> readLongArray(JsonNode item, String field) {
        JsonNode arr = item.path(field);
        if (!arr.isArray()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.isIntegralNumber()) {
                out.add(n.asLong());
            }
        }
        return out;
    }

    /** 读 String 数组（非数组返回空列表）。 */
    private static List<String> readStringArray(JsonNode item, String field) {
        JsonNode arr = item.path(field);
        if (!arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.isTextual() && !n.asText().isBlank()) {
                out.add(n.asText().trim());
            }
        }
        return out;
    }

    /**
     * 编年史提炼条目（AI 输出结构化模型，落库前归一化）。
     *
     * @param title 标题
     *
     * @param content 发生了什么+影响
     *
     * @param importance 重要度 1-5
     *
     * @param eventIds 关联 actor_event id 列表（幂等去重用）
     *
     * @param involvedEntities 关联实体引用列表
     *
     * @param involvedCharacters 关联角色引用列表
     */
    public record ChronicleEntry(String title, String content, int importance,
                                 List<Long> eventIds, List<String> involvedEntities,
                                 List<String> involvedCharacters) {
    }
}
