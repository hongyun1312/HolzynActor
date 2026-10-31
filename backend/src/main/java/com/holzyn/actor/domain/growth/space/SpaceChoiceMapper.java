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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.agent.prompt.DecisionReActParse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空间候选选择 · 决策输出映射器。
 * <p><b>职责</b>：把 决策模型 choice 选中的候选映射为下游执行链消费的<b>内部规范决策 Map</b>
 * ——action_detail=候选原文、target=候选目标、
 * reasoning=简短程序化文本（思想气泡可见）、priority/duration 按类型默认；
 * 新键（actionType/actionDetail/reasoning/priority/expectedDurationMinutes/target）与
 * 旧键（type/action/target/reason/urgency/duration/params/foresightCandidates）双写，
 * 与 {@link DecisionReActParse#parse} 产出的内部规范 Map 完全同构（下游执行链零改动）。</p>
 * 程序化兜底语义，AgentRuntime 识别后不新建行动计划=真人继续手头的事）；空间候选含目标地点或
 * 行动文本以「去/前往/到/回/上/进/赶」开头 → move_to；其余 → start_action。</p>
 * <p>纯静态可单测。</p>
 */
public final class SpaceChoiceMapper {

    private SpaceChoiceMapper() {
    }

    /** 地点动词前缀（行动文本以任一开头 → move_to）。 */
    private static final String[] MOVE_PREFIXES = {"去", "前往", "到", "回", "上", "进", "赶", "奔赴"};

    /**
     * 映射选中候选为内部规范决策 Map。
     *
     * @param chosen 选中的候选（null → 返回 null，调用方回退 Qwen 现状）
     *
     * @param currentActivity 当前行动文本（continue 的描述背景；可空）
     *
     * @param considered 本次考虑的候选行动原文表（可空；写入 foresightCandidates 供前瞻/调试）
     *
     * @return 内部规范决策 Map（新键+旧键双写）；null=输入无效
     */
    public static Map<String, Object> toDecisionMap(SpaceDecisionCandidates.SpaceCandidate chosen,
                                                    String currentActivity, List<String> considered) {
        if (chosen == null) {
            return null;
        }
        Map<String, Object> decision;
        if (SpaceDecisionCandidates.KEY_CONTINUE.equals(chosen.key())) {
            decision = DecisionReActParse.continueDecision(currentActivity);
        } else {
            String action = chosen.action() == null || chosen.action().isBlank()
                    ? "忙于日常事务" : chosen.action().trim();
            String target = chosen.target() == null ? "" : chosen.target().trim();
            String actionType = inferActionType(action, target);
            String reasoning = "此刻想" + action;
            int priority = chosen.generic() && SpaceDecisionCandidates.KEY_REST.equals(chosen.key()) ? 1
                    : (chosen.generic() && SpaceDecisionCandidates.KEY_CHAT.equals(chosen.key()) ? 2 : 3);
            int duration = 30;
            decision = new LinkedHashMap<>();
            // —— 新契约键（权威） ——
            decision.put("actionType", actionType);
            decision.put("actionDetail", action);
            decision.put("reasoning", reasoning);
            decision.put("priority", priority);
            decision.put("expectedDurationMinutes", duration);
            decision.put("target", target);
            // —— 映射旧键（下游执行链兼容，与 DecisionReActParse.parse 同构） ——
            decision.put("type", DecisionReActParse.legacyTypeFor(actionType, action));
            decision.put("action", action);
            decision.put("reason", reasoning);
            decision.put("urgency", priority);
            decision.put("duration", duration);
            Map<String, Object> params = new LinkedHashMap<>();
            if ("move_to".equals(actionType) && !target.isBlank()) {
                params.put("move", Map.of("to", target));
            }
            decision.put("params", params);
        }
        // 前瞻/调试候选（可选）
        if (considered != null && !considered.isEmpty()) {
            List<Map<String, String>> candList = new ArrayList<>();
            for (String c : considered) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                Map<String, String> one = new LinkedHashMap<>();
                one.put("action", c.trim());
                one.put("consequence", "");
                candList.add(one);
            }
            if (!candList.isEmpty()) {
                decision.put("foresightCandidates", candList);
            }
        }
        return decision;
    }

    /** 行动类型推断（含目标地点或地点动词前缀 → move_to；否则 start_action）。 */
    static String inferActionType(String action, String target) {
        if (target != null && !target.isBlank()) {
            return "move_to";
        }
        if (action != null) {
            for (String p : MOVE_PREFIXES) {
                if (action.startsWith(p)) {
                    return "move_to";
                }
            }
        }
        return "start_action";
    }
}
