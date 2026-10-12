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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 核心记忆自编辑 AI 输出解析器。
 * <p>职责：把 AI 调用（core_memory_update 模板，json_object 模式）返回的文本解析为
 * 结构化核心块更新指令——期望 JSON 对象：<pre>
 * { "action": "create", "block_key": "custom:近期目标", "block_name": "近期目标", "content": "...", "importance": 3 },
 * ],
 * </pre>
 * 兼容 Markdown 代码块/首尾说明文字（复用 {@link JsonUtil}）；action 缺省 update、
 * importance 缺省 3、block_key/action 非法条目安全丢弃；整体非法返回空列表。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§7.2（Letta core memory blocks 自编辑，
 * memory_replace/insert 语义映射为 update/create）。</p>
 */
public final class CoreMemoryUpdateParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法 action 枚举 */
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_DELETE = "delete";

    /** 工具类禁止实例化 */
    private CoreMemoryUpdateParse() {
    }

    /**
     * 解析自编辑 AI 输出为更新指令列表（非法/空返回空列表，不抛异常）。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 更新指令列表
     */
    public static List<CoreMemoryUpdate> parse(String aiText) {
        List<CoreMemoryUpdate> out = new ArrayList<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode updates = root.isObject() ? root.path("updates") : root;
            if (!updates.isArray()) {
                return out;
            }
            for (JsonNode item : updates) {
                CoreMemoryUpdate u = parseOne(item);
                if (u != null) {
                    out.add(u);
                }
            }
        } catch (Exception e) {
            // 解析失败：返回已解析部分（若部分成功），整体失败返回空
            return out;
        }
        return out;
    }

    /**
     * 解析单条更新指令。
     *
     * @param item JSON 对象节点
     *
     * @return 更新指令（非对象/action 非法/block_key 缺失返回 null；update/create 无 content 返回 null）
     */
    private static CoreMemoryUpdate parseOne(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String action = item.path("action").asText(ACTION_UPDATE).trim().toLowerCase();
        if (!ACTION_UPDATE.equals(action) && !ACTION_CREATE.equals(action) && !ACTION_DELETE.equals(action)) {
            return null;
        }
        String blockKey = item.path("block_key").asText("").trim();
        if (blockKey.isBlank()) {
            return null;
        }
        String content = item.path("content").asText("");
        if (!ACTION_DELETE.equals(action) && content.isBlank()) {
            return null; // 非删除指令必须有内容
        }
        int importance = item.path("importance").asInt(3);
        importance = Math.max(1, Math.min(5, importance));
        String blockName = item.path("block_name").asText("");
        String reason = item.path("reason").asText("");
        return new CoreMemoryUpdate(action, blockKey, blockName, content.trim(), importance, reason);
    }

    /**
     * 解析后的核心块更新指令。
     *
     * @param action 动作（update/create/delete）
     *
     * @param blockKey 块键（标准键或 custom:*）
     *
     * @param blockName 块显示名（可空，服务层兜底）
     *
     * @param content 内容（update/create 必填；delete 为空）
     *
     * @param importance 重要度（1~5，默认 3）
     *
     * @param reason 更新理由（记录审计）
     */
    public record CoreMemoryUpdate(String action, String blockKey, String blockName,
                                   String content, Integer importance, String reason) {
    }
}
