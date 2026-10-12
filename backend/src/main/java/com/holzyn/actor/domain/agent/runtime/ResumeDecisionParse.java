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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 恢复决策 AI 输出解析器。
 * <p>职责：把 AI 调用（interrupt_resume 模板，json_object 模式）返回的文本解析为结构化
 * 恢复决策——期望 JSON 对象：<pre>
 * { "decision": "resume|adjust|abandon", "action": "行动描述", "target": "目标地点/对象", "reason": "理由" }
 * </pre>
 * 兼容 Markdown 代码块/首尾说明文字（复用 {@link JsonUtil}）；decision 不在三态枚举内或
 * 缺失 → 返回 null；adjust 时 action 必填（缺 action 视为
 * abandon 语义，交由上层共性框架判定）。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径》§4.3（打断与恢复）+ 决策
 * （用户 恢复=独立轻量 AI 决策三态执行，AI 不可用回退共性框架）。
 * </p>
 */
public final class ResumeDecisionParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法 decision 枚举（对齐 interrupt_resume 模板输出结构） */
    private static final Set<String> DECISIONS = Set.of("resume", "adjust", "abandon");

    /** 工具类禁止实例化 */
    private ResumeDecisionParse() {
    }

    /**
     * 解析恢复决策输出（非法/空返回 null，不抛异常）。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 恢复决策（decision 缺失/非法返回 null）
     */
    public static ResumeDecision parse(String aiText) {
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
            String decision = root.path("decision").asText("").trim().toLowerCase();
            if (!DECISIONS.contains(decision)) {
                return null; // 非法决策 → 上层回退共性框架
            }
            String action = root.path("action").asText("").trim();
            String target = root.path("target").asText("").trim();
            String reason = root.path("reason").asText("").trim();
            return new ResumeDecision(decision, action, target.isBlank() ? null : target,
                    reason.isBlank() ? null : reason);
        } catch (Exception e) {
            // 解析失败：整体返回 null（上层回退共性框架）
            return null;
        }
    }

    /**
     * 解析后的恢复决策。
     *
     * @param decision 三态：resume（回原地点继续原活动）/ adjust（调整为新任务）/ abandon（放弃）
     *
     * @param action 行动描述（resume=原任务；adjust=新行动；abandon 可空）
     *
     * @param target 目标地点/对象（可空）
     *
     * @param reason 决策理由（可空，审计/日志）
     */
    public record ResumeDecision(String decision, String action, String target, String reason) {
    }
}
