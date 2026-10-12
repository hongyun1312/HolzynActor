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

package com.holzyn.actor.domain.agent.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层 Prompt 组装器。
 * <p>按「变化频率从低到高」的固定层序组装 Prompt 文本，稳定前缀（SYSTEM + ROLE_PROFILE）
 * 恒在最前且跨请求逐字节一致，以命中服务端前缀缓存（vLLM/OpenAI 兼容 prefix cache）；
 * 高频段（CURRENT_STATE / MEMORY_CONTEXT / CONVERSATION_HISTORY / TASK）置后逐轮更新。</p>
 * <p>用法：</p>
 * <pre>
 * .layer(PromptLayers.SYSTEM, "你是沈砚秋，青禾村村长……")
 * .layer(PromptLayers.ROLE_PROFILE, "身份：……；OCEAN：……")
 * .layer(PromptLayers.CURRENT_STATE, "时刻：巳时；天气：晴……")
 * .layer(PromptLayers.MEMORY_CONTEXT, "记忆：……")
 * .layer(PromptLayers.TASK, "请以沈砚秋的口吻回复玩家一句话。")
 * .layer(PromptLayers.OUTPUT_FORMAT, "只输出回复正文，不要任何 JSON 包装。")
 * </pre>
 * <p>同层多块以 {@link PromptLayers#BLOCK_SEP} 拼接；空块自动跳过（零噪音）；
 * 块内容中不注入段标题（段标题由组装器统一生成，保证格式一致与缓存友好）。</p>
 */
public class LayeredPromptBuilder {

    /** 固定层序（稳定前缀在前）——本表即「变化频率从低到高」的权威顺序。 */
    private static final String[] ORDER = {
            PromptLayers.SYSTEM,
            PromptLayers.ROLE_PROFILE,
            PromptLayers.CURRENT_STATE,
            PromptLayers.DEVELOPMENTAL_CONSTRAINT,
            PromptLayers.MEMORY_CONTEXT,
            PromptLayers.CONVERSATION_HISTORY,
            PromptLayers.TASK,
            PromptLayers.POST_HISTORY_GUARD,
            PromptLayers.OUTPUT_FORMAT,
    };

    /** 各层内容块（层 → 块列表）。 */
    private final java.util.Map<String, List<String>> layers = new java.util.LinkedHashMap<>();

    private LayeredPromptBuilder() {
    }

    /** 创建组装器。 */
    public static LayeredPromptBuilder builder() {
        return new LayeredPromptBuilder();
    }

    /** 一层内容块（null/空白自动忽略；同层多次追加按序拼接）。 */
    public LayeredPromptBuilder layer(String layer, String content) {
        if (layer == null || layer.isBlank() || content == null || content.isBlank()) {
            return this;
        }
        layers.computeIfAbsent(layer, k -> new ArrayList<>()).add(content.trim());
        return this;
    }

    /** 层（自动加段标题，作为该层唯一块；等价于 layer(layer, title + "\n" + content)）。 */
    public LayeredPromptBuilder section(String layer, String content) {
        if (layer == null || layer.isBlank() || content == null || content.isBlank()) {
            return this;
        }
        return layer(layer, PromptLayers.sectionTitle(layer) + "\n" + content.trim());
    }

    /** 按固定层序组装全文（空层跳过；层内多块空行拼接；无内容返回空串）。 */
    public String build() {
        StringBuilder sb = new StringBuilder();
        for (String layer : ORDER) {
            List<String> blocks = layers.get(layer);
            if (blocks == null || blocks.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(PromptLayers.BLOCK_SEP);
            }
            sb.append(String.join(PromptLayers.BLOCK_SEP, blocks));
        }
        return sb.toString();
    }

    /** 是否含任何内容。 */
    public boolean isEmpty() {
        return layers.isEmpty();
    }
}
