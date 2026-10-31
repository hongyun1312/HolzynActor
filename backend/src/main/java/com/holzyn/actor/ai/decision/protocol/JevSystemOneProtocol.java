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

package com.holzyn.actor.ai.decision.protocol;

import com.holzyn.actor.ai.decision.DecisionAnswer;
import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.ai.decision.DecisionResponse;
import com.holzyn.actor.ai.decision.DecisionState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>单 state Jev 协议</b>（{@code jev-systemone}）：{@code {"state":...,"questions":{...}}} → {@code {"answers":{...}}}。
 *
 * <p>该形状被多家开源决策模型服务端采用（Laya 的 {@code POST /v1/systemone}、
 * Strands Decider 的 {@code POST /v1/systemone} 等），差异集中在题型命名：</p>
 * <ul>
 *   <li>布尔题在线上报文里叫 {@code noul}（返回 {@code noul} = 命题为真的概率）；</li>
 *   <li>{@code choice} 返回 {@code choice} + {@code probabilities}；</li>
 *   <li>{@code score} 返回期望值 {@code score} + {@code probabilities}（档位 → 概率）。</li>
 * </ul>
 * <p>本协议把三族答案<b>统一还原成内部模型</b>（boolean 题型名归一回 {@code boolean}，
 * 并补齐 {@code probabilities}/{@code pTrue}/{@code level}），因此上层判定门面不需要知道
 * 服务端用的是 {@code noul} 还是 {@code boolean}。</p>
 *
 * <p><b>一次只带一个 state</b>（{@link #batched()} = false）：客户端逐 state 调用后合并成
 * 一个统一响应，保证「9 个判定场景在任意适配器上语义一致」。</p>
 */
@Component
public class JevSystemOneProtocol implements DecisionProtocol {

    /** 协议 ID */
    public static final String ID = "jev-systemone";

    /** 默认路径（服务端无版本前缀时） */
    public static final String DEFAULT_PATH = "/systemone";

    /** 题型在 Jev 报文中的名称：boolean → noul */
    private static final String TYPE_NOUL = "noul";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String defaultEvaluatePath() {
        return DEFAULT_PATH;
    }

    @Override
    public boolean batched() {
        return false;
    }

    @Override
    public String buildBody(List<DecisionState> states) {
        DecisionState s = states.get(0);
        ObjectNode root = MAPPER.createObjectNode();
        if (s.state() instanceof String str) {
            root.put("state", str);
        } else {
            root.set("state", MAPPER.valueToTree(s.state()));
        }
        ObjectNode questions = root.putObject("questions");
        for (Map.Entry<String, DecisionQuestion> e : s.questions().entrySet()) {
            DecisionQuestion q = e.getValue();
            ObjectNode qo = questions.putObject(e.getKey());
            qo.put("type", wireType(q.type()));
            qo.put("instructions", q.instructions());
            if (DecisionQuestion.TYPE_CHOICE.equals(q.type()) && q.criteria() != null) {
                ObjectNode c = qo.putObject("criteria");
                q.criteria().forEach(c::put);
            } else if (DecisionQuestion.TYPE_SCORE.equals(q.type()) && q.scoreCriteria() != null) {
                ArrayNode arr = qo.putArray("criteria");
                q.scoreCriteria().forEach(arr::add);
            } else if (q.criteria() != null) {
                // boolean 的 false/true 判据：Jev 族以 noul 为名，判据表仍按 false/true 两键传递
                ObjectNode c = qo.putObject("criteria");
                q.criteria().forEach(c::put);
            }
        }
        return MAPPER.writeValueAsString(root);
    }

    @Override
    public DecisionResponse parse(String body, List<DecisionState> sent) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode answersNode = root.get("answers");
            if (answersNode == null || !answersNode.isObject()) {
                return null;
            }
            Map<String, DecisionAnswer> byQid = new LinkedHashMap<>();
            answersNode.properties().forEach(en -> byQid.put(en.getKey(), parseAnswer(en.getValue())));
            if (byQid.isEmpty()) {
                return null;
            }
            String stateId = sent == null || sent.isEmpty() ? "s1" : sent.get(0).id();
            Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
            answers.put(stateId, byQid);
            int questions = sent == null || sent.isEmpty() ? byQid.size() : sent.get(0).questionCount();
            int paths = sent == null || sent.isEmpty() ? byQid.size() : sent.get(0).pathCount();
            Double latency = root.path("latency_ms").isNumber() ? root.path("latency_ms").asDouble() : null;
            String model = root.path("model").isTextual() ? root.path("model").asText() : null;
            DecisionResponse.Execution execution = new DecisionResponse.Execution(
                    answers.size(), questions, paths,
                    // Jev 族服务端为「一次前向、零解码」，无 execution 字段 → 按协议语义合成证据
                    1, 0, 1, model, latency, false);
            return new DecisionResponse(execution, answers, body);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String healthPath() {
        return "/health";
    }

    /** 内部题型 → 线题型（boolean 在 Jev 族里叫 noul）。 */
    static String wireType(String type) {
        return DecisionQuestion.TYPE_BOOLEAN.equals(type) ? TYPE_NOUL : type;
    }

    /** 线题型 → 内部题型（兼容服务端回 {@code boolean} 的宽松实现）。 */
    private static String internalType(String wire) {
        return TYPE_NOUL.equalsIgnoreCase(wire) ? DecisionQuestion.TYPE_BOOLEAN : wire;
    }

    /** 解析单题答案（{@code noul/choice/score} 三族 → 内部统一模型）。 */
    private static DecisionAnswer parseAnswer(JsonNode n) {
        if (n == null || !n.isObject()) {
            return DecisionAnswer.empty("unknown");
        }
        String type = internalType(n.path("type").asText("unknown"));
        Map<String, Double> probs = new LinkedHashMap<>();
        JsonNode p = n.get("probabilities");
        if (p != null && p.isObject()) {
            p.properties().forEach(en -> probs.put(en.getKey(), en.getValue().asDouble()));
        }
        if (DecisionQuestion.TYPE_BOOLEAN.equals(type)) {
            Double pTrue = n.path("noul").isNumber() ? n.path("noul").asDouble()
                    : (n.path("p_true").isNumber() ? n.path("p_true").asDouble() : null);
            if (pTrue != null && probs.isEmpty()) {
                probs.put("false", 1.0 - pTrue);
                probs.put("true", pTrue);
            }
            Boolean value = pTrue == null ? null : pTrue >= 0.5;
            return new DecisionAnswer(DecisionQuestion.TYPE_BOOLEAN, probs, null, pTrue, null, null, value);
        }
        if (DecisionQuestion.TYPE_CHOICE.equals(type)) {
            String choice = n.path("choice").isTextual() ? n.path("choice").asText() : null;
            if (choice == null && n.path("value").isTextual()) {
                choice = n.path("value").asText();
            }
            return new DecisionAnswer(DecisionQuestion.TYPE_CHOICE, probs, choice, null, null, null, choice);
        }
        if (DecisionQuestion.TYPE_SCORE.equals(type)) {
            Double score = n.path("score").isNumber() ? n.path("score").asDouble() : null;
            Integer level = n.path("level").isNumber() ? n.path("level").asInt() : argmax(probs);
            return new DecisionAnswer(DecisionQuestion.TYPE_SCORE, probs, null, null, score, level, score);
        }
        return DecisionAnswer.empty(type);
    }

    /** 概率最大的档位（键需为数字；非数字/空返回 null）。 */
    private static Integer argmax(Map<String, Double> probs) {
        Integer best = null;
        double bestP = -1;
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            int idx;
            try {
                idx = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (e.getValue() != null && e.getValue() > bestP) {
                bestP = e.getValue();
                best = idx;
            }
        }
        return best;
    }
}
