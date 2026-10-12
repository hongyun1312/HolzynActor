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
 * 实体状态更新判定 AI 输出解析器。
 * <p>职责：解析 entity_state_update 模板输出——AI 对编年史涉及实体逐一判定「世界视角公共状态
 * 是否变化」并给出新状态：</p>
 * <pre>
 * { "entity_key": "location:村口牌坊", "changed": true,
 * "current_state": "古槐被雷劈断，村口搭起了临时木棚", "recent_change": "古槐遭雷击" },
 * </pre>
 * <p>changed=false → 不改写（保留原状态行）；changed=true → 新状态行 supersedes 旧行 + 旧行
 * valid_to_game_day=当前游戏日。归一化：current_state 空白且 changed=true 时按 keep 丢弃该条
 * （AI 异常输出防污染）；entity_key 空白丢弃。</p>
 */
public final class EntityStateUpdateParse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntityStateUpdateParse() {
    }

    /**
     * 解析实体状态更新判定输出。
     *
     * @param aiText AI 输出文本（可 null/非法）
     *
     * @return 实体更新判定列表（非法/空返回空列表）
     */
    public static List<EntityUpdate> parse(String aiText) {
        List<EntityUpdate> out = new ArrayList<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.isArray() ? root : root.path("updates");
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode item : arr) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String entityKey = item.path("entity_key").asText("");
                if (entityKey.isBlank()) {
                    continue;
                }
                boolean changed = item.path("changed").asBoolean(false);
                if (!changed) {
                    out.add(new EntityUpdate(entityKey.trim(), false, null, null));
                    continue;
                }
                String currentState = item.path("current_state").asText("");
                if (currentState.isBlank()) {
                    // AI 声称变化但未给新状态：视为未变化（防污染，保守不写）
                    out.add(new EntityUpdate(entityKey.trim(), false, null, null));
                    continue;
                }
                String recentChange = item.path("recent_change").asText("");
                out.add(new EntityUpdate(entityKey.trim(), true, currentState.trim(),
                        recentChange.isBlank() ? null : recentChange.trim()));
            }
        } catch (Exception e) {
            // 解析失败返回已解析部分
            return out;
        }
        return out;
    }

    /**
     * 单实体更新判定。
     *
     * @param entityKey 实体键
     *
     * @param changed 状态是否变化
     *
     * @param currentState 新状态描述（changed=true 时非空）
     *
     * @param recentChange 最近变化描述（可空）
     */
    public record EntityUpdate(String entityKey, boolean changed, String currentState, String recentChange) {
    }
}
