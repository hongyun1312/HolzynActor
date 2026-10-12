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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆抽取「按需复核」AI 输出解析器。
 * <p>职责：解析 memory_extract_review 模板输出——AI 对抽取候选（重要度≥3 或带 update/invalidate
 * 操作）的一次性批量复核判定：每条候选确认保留/丢弃/修改（改内容或重要度）。</p>
 * <p>输出结构：</p>
 * <pre>
 * [ { "index": 0, "action": "keep|drop|modify", "content": "（modify 时新内容，可空=沿用）", "importance": 3 } ]
 * </pre>
 * <p>语义（设计 §3.2 按需 AI 复核）：复核失败静默回退——调用方仍按初判执行，不阻断；
 * 未提及的 index 按 keep（保持初判）处理。</p>
 */
public final class MemoryExtractReviewParse {

    /** 复核动作：保留（按初判执行） */
    public static final String ACTION_KEEP = "keep";
    /** 复核动作：丢弃（不写入） */
    public static final String ACTION_DROP = "drop";
    /** 复核动作：修改（替换内容/重要度后仍按原操作执行） */
    public static final String ACTION_MODIFY = "modify";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemoryExtractReviewParse() {
    }

    /**
     * 解析复核输出：按候选 index → 判定映射。
     *
     * @param aiText AI 输出文本（可 null/非法，返回空 Map）
     *
     * @return index → ReviewDecision（action/content/importance；importance 可空=不改）
     */
    public static Map<Integer, ReviewDecision> parseReview(String aiText) {
        Map<Integer, ReviewDecision> out = new LinkedHashMap<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.isArray() ? root
                    : (root.path("reviews").isArray() ? root.path("reviews")
                    : (root.path("results").isArray() ? root.path("results") : null));
            if (arr == null) {
                return out;
            }
            for (JsonNode item : arr) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                if (!item.path("index").isIntegralNumber()) {
                    continue;
                }
                int index = item.path("index").asInt(-1);
                if (index < 0) {
                    continue;
                }
                String action = normalizeAction(item.path("action").asText(""));
                String content = item.path("content").asText("");
                Integer importance = item.path("importance").isIntegralNumber()
                        ? Math.max(1, Math.min(5, item.path("importance").asInt(1)))
                        : null;
                out.put(index, new ReviewDecision(action, content.isBlank() ? null : content.trim(), importance));
            }
        } catch (Exception e) {
            // 解析失败返回已解析部分
            return out;
        }
        return out;
    }

    /** 归一化 action（非法值回退 keep）。 */
    private static String normalizeAction(String raw) {
        if (raw == null) {
            return ACTION_KEEP;
        }
        return switch (raw.trim()) {
            case ACTION_DROP -> ACTION_DROP;
            case ACTION_MODIFY -> ACTION_MODIFY;
            default -> ACTION_KEEP;
        };
    }

    /**
     * 单条候选的复核判定。
     *
     * @param content 修改后的内容（modify 时；null=沿用初判）
     *
     * @param importance 修改后的重要度（modify 时；null=沿用初判）
     */
    public record ReviewDecision(String action, String content, Integer importance) {
        /** 是否丢弃（调用方跳过该条）。 */
        public boolean isDrop() {
            return ACTION_DROP.equals(action);
        }
    }

    /** 便捷工具：按初判列表组装候选描述文本（复核模板输入，纯静态可单测）。 */
    public static List<String> candidateTexts(List<String> candidates) {
        List<String> out = new ArrayList<>();
        if (candidates == null) {
            return out;
        }
        for (int i = 0; i < candidates.size(); i++) {
            out.add(i + ". " + candidates.get(i));
        }
        return out;
    }
}
