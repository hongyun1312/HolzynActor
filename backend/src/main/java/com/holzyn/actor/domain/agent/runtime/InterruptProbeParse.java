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

/**
 * 打断前 AI 探针输出解析器。
 * <p>职责：把 AI 调用（interrupt_probe 模板，json_object 模式）返回的文本解析为结构化
 * 探针决策——期望 JSON 对象：<pre>
 * { "interrupt": true|false, "reason": "符合性格与处境的理由" }
 * </pre>
 * 兼容 Markdown 代码块/首尾说明文字（复用 {@link JsonUtil}）；interrupt 非布尔/缺失 →
 * 返回 null。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径》§4.3（- 共性框架 +
 * 起「是否打断」由性格 AI 决策个性化，本框架兜底）+ 用户逐题决策（
 * 打断前 AI 判断； / 走探针 / 强制打断；探针失败兜底默认打断）。
 * </p>
 */
public final class InterruptProbeParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化 */
    private InterruptProbeParse() {
    }

    /**
     * 解析探针决策输出（非法/空返回 null，不抛异常）。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     *
     * @return 探针决策（interrupt 缺失/非布尔返回 null）
     */
    public static InterruptProbe parse(String aiText) {
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
            JsonNode flag = root.path("interrupt");
            if (flag == null || !flag.isBoolean()) {
                return null; // 非法探针 → 上层回退共性框架（默认打断）
            }
            String reason = root.path("reason").asText("").trim();
            return new InterruptProbe(flag.asBoolean(), reason.isBlank() ? null : reason);
        } catch (Exception e) {
            // 解析失败：整体返回 null（上层回退共性框架默认打断）
            return null;
        }
    }

    /**
     * 解析后的探针决策。
     *
     * @param interrupt true=放下手头事去应对（打断）；false=继续手头事（不打断）
     *
     * @param reason 决策理由（可空，审计/日志）
     */
    public record InterruptProbe(boolean interrupt, String reason) {
    }
}
