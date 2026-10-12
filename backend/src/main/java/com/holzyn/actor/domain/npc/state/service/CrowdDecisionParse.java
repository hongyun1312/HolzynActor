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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 人群行动 AI 决策输出解析器。
 * <p>职责：把 AI 调用（npc_crowd_decision 模板，json_object 模式）返回的文本解析为
 * 结构化行动决策——期望 JSON 对象：<pre>
 * { "action": "前往王都酒馆用餐", "actionType": "eat", "target": "王都酒馆", "reason": "有些饿了" }
 * </pre>
 * 兼容 Markdown 代码块/首尾说明文字（复用 {@link JsonUtil}）；actionType 校验枚举
 * eat/drink/toilet/rest/wash/chat/play/work/idle（非法回退空串 → 按动作文本关键词恢复需求）；
 * action 空 → 返回 null（无可应用内容，上层回退程序化状态机）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§8.2（人群 AI 建议式决策）。
 * </p>
 */
public final class CrowdDecisionParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法 actionType 枚举（对齐模板输出结构与 onNpcAction 恢复映射） */
    private static final Set<String> ACTION_TYPES =
            Set.of("eat", "drink", "toilet", "rest", "wash", "chat", "play", "work", "idle");

    /** 工具类禁止实例化 */
    private CrowdDecisionParse() {
    }

    /**
     * 解析人群行动 AI 决策输出（非法/空返回 null，不抛异常）。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 行动决策（action 缺失/非法返回 null）
     */
    public static CrowdAction parse(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return null;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            String action = root.path("action").asText("").trim();
            if (action.isBlank()) {
                return null; // 无可应用内容（AI 未给出行动）
            }
            String actionType = root.path("actionType").asText("").trim().toLowerCase();
            if (!ACTION_TYPES.contains(actionType)) {
                actionType = ""; // 非法类型 → 空串（上层按动作文本关键词恢复需求）
            }
            String target = root.path("target").asText("").trim();
            String reason = root.path("reason").asText("");
            return new CrowdAction(action, actionType, target.isBlank() ? null : target,
                    reason.isBlank() ? null : reason);
        } catch (Exception e) {
            // 解析失败：整体返回 null（上层回退程序化状态机）
            return null;
        }
    }

    /**
     * 解析后的人群行动决策。
     *
     * @param action 动作描述（必填，如「前往王都酒馆用餐」）
     *
     * @param actionType 行动类型（eat/drink/toilet/rest/wash/chat/play/work/idle；非法回退空串）
     *
     * @param target 目标地点名（可空；取自世界观地点）
     *
     * @param reason 决策理由（可空，审计/日志）
     */
    public record CrowdAction(String action, String actionType, String target, String reason) {
    }
}
