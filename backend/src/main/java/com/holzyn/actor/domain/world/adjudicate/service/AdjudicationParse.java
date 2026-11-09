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

package com.holzyn.actor.domain.world.adjudicate.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 罕见 AI 裁决输出解析（§4.7）。
 *
 * <p>只接受单轮闭集：{@code verdict ∈ SUCCESS/PARTIAL/FAIL}、{@code effects} 最多 N 条且 op
 * 必须在白名单内。任何解析失败一律降级 {@code PARTIAL + AI_PARSE_FALLBACK}，绝不 FAIL。</p>
 */
public final class AdjudicationParse {

    /**
     * 允许的 effect op 白名单（只落草稿，不应用）。
     *
     * <p><b>刻意不含 {@code market.trade} 与 {@code craft.make}</b>：
     * 这两个 op 会直接动钱袋、动材料、占设施，只允许确定性规则层发出。
     * AI 即使被提示词误导写了这两个 op，也会在这里被拒掉（整条效果丢弃，不静默执行）。</p>
     */
    static final Set<String> ALLOWED_OPS = Set.of(
            "fact.upsert", "fact.expire", "inventory.transfer", "relation.delta",
            "memory.add", "info.packet", "entity.upsert", "state.urgency", "schedule.plan");

    static final int MAX_OBSERVED = 200;
    static final int MAX_REASON = 40;
    static final int MAX_DURATION_MINUTES = 24 * 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdjudicationParse() {
    }

    /**
     * 解析 AI 文本。
     *
     * @param text       AI 原始输出
     * @param maxEffects 允许的最大效果条数
     *
     * @return 裁决结果；失败返回 PARTIAL + AI_PARSE_FALLBACK
     */
    public static ActionVerdict parse(String text, int maxEffects) {
        try {
            JsonNode root = MAPPER.readTree(stripFence(text));
            Verdict verdict = Verdict.parseAi(root.path("verdict").asText(""));
            if (verdict == null) {
                return fallback();
            }
            String reason = truncate(root.path("reasonCode").asText(root.path("reason_code").asText("AI")),
                    MAX_REASON);
            String observed = truncate(root.path("observedText").asText(root.path("observed_text").asText("")),
                    MAX_OBSERVED);
            int duration = clamp(root.path("durationMinutes").asInt(root.path("duration_minutes").asInt(0)),
                    0, MAX_DURATION_MINUTES);
            List<EffectOp> effects = parseEffects(root.path("effects"), maxEffects);
            return ActionVerdict.of(verdict, reason, observed, duration, effects, "ai");
        } catch (Exception e) {
            return fallback();
        }
    }

    /** 解析失败降级（PARTIAL，永不 FAIL）。 */
    static ActionVerdict fallback() {
        return ActionVerdict.of(Verdict.PARTIAL, "AI_PARSE_FALLBACK",
                "他试了，但结果一时说不清。", 0, List.of(), "fallback");
    }

    private static List<EffectOp> parseEffects(JsonNode arr, int maxEffects) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(0, maxEffects);
        List<EffectOp> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (out.size() >= cap) {
                break;
            }
            String op = n.path("op").asText("").trim();
            if (op.isBlank() || !ALLOWED_OPS.contains(op)) {
                continue;
            }
            JsonNode body = n.path("body");
            if (body.isMissingNode() || body.isNull()) {
                body = MAPPER.createObjectNode();
            }
            String idem = n.path("idempotencyKey").asText(n.path("idempotency_key").asText(""));
            out.add(new EffectOp(op, body, idem.isBlank() ? null : idem));
        }
        return out;
    }

    /** 去掉 ```json 围栏。 */
    private static String stripFence(String text) {
        if (text == null) {
            return "{}";
        }
        String t = text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) {
                t = t.substring(first + 1);
            }
            int last = t.lastIndexOf("```");
            if (last >= 0) {
                t = t.substring(0, last);
            }
        }
        return t.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
