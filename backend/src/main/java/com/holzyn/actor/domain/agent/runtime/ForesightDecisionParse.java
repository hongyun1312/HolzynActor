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

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 前瞻推演决策解析器。
 * <p>职责：从主决策 AI 输出的 JSON 中提取【前瞻推演】产物——候选行动数组
 * candidates:[{action, consequence}] 与最终行动 final（=决策 action 字段）。
 * 失败/缺失 → 空候选（success=false 由 ForesightService 记录判定）。</p>
 * <p>契约（与 action_gen 模板【前瞻推演】段一致）：AI 被要求先输出 2~3 个候选行动及短期
 * 后果，再在 action 字段选最终行动；candidates 为可选字段，缺失=AI 未按要求输出候选。</p>
 */
public final class ForesightDecisionParse {

    /** 候选行动单条文本长度上限（截断防撑爆） */
    private static final int ITEM_MAX = 120;

    private ForesightDecisionParse() {
    }

    /**
     * 从决策 JSON 节点提取候选行动列表。
     *
     * @param node 决策 JSON 节点（可空）
     *
     * @param maxCandidates 候选条数上限（<=0 不截断）
     *
     * @return 候选行动列表（[{action, consequence}]；无候选返回空列表）
     */
    public static List<Candidate> parseCandidates(JsonNode node, int maxCandidates) {
        List<Candidate> out = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        JsonNode arr = node.path("candidates");
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode c : arr) {
            if (c == null || !c.isObject()) {
                continue;
            }
            String action = c.path("action").asText("").trim();
            String consequence = c.path("consequence").asText("").trim();
            if (action.isBlank()) {
                continue;
            }
            Candidate cand = new Candidate(abbreviate(action, ITEM_MAX),
                    consequence.isBlank() ? "" : abbreviate(consequence, ITEM_MAX));
            out.add(cand);
            if (maxCandidates > 0 && out.size() >= maxCandidates) {
                break;
            }
        }
        return out;
    }

    /** 是否包含候选（=前瞻成功判定）。 */
    public static boolean hasCandidates(JsonNode node) {
        return !parseCandidates(node, 0).isEmpty();
    }

    /** 候选行动（不可变）。 */
    public record Candidate(String action, String consequence) {
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
