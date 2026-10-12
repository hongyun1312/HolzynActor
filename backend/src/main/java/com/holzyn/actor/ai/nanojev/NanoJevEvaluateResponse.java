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

package com.holzyn.actor.ai.nanojev;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NanoJev evaluate 响应（官方契约原样解析 + 静默回退防护）。
 * <p>响应结构：顶层
 * 每个 state 含 {@code id} 与 {@code answers}（qid → 题型答案）。</p>
 * <p><b>⚠️ 静默回退防护</b>：{@code /v1/chat/completions} 传
 * {@code model=nanojev} 会静默回退 qwen3.6-35b-q4-un——响应形状变成 chat（含
 * {@code choices}），且 {@code execution.forward_passes} 缺失。客户端必须断言
 * {@link #isChatFallback()} 为 false，否则按失败处置（fail-closed），防止「以为在调
 * NanoJev 实际调 35B」。</p>
 * <p><b>缓存命中的例外</b>：上游 HolzynLLMServer 的
 * 确定性结果缓存命中时 {@code forward_passes=0} 但 {@code cache_hit=true}（详见
 * {@link Execution#forwardProof()}）。该情形不是静默回退，必须按成功处置。</p>
 *
 * @param execution 执行元数据（states/questions/candidatePaths/forwardPasses/decodeSteps/device/…）
 *
 * @param answers stateId → (qid → 答案)（保持响应返回序）
 *
 * @param raw 原始响应 JSON 文本（trace outputRaw 复用，避免重复序列化）
 */
public record NanoJevEvaluateResponse(Execution execution,
                                      Map<String, Map<String, NanoJevAnswer>> answers, String raw) {

    /** Jackson 3 序列化器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 执行元数据（一次前向的官方证明字段）。 */
    public record Execution(int states, int questions, int candidatePaths,
                            int forwardPasses, int decodeSteps, int networkModelCalls,
                            String device, Double serverEvaluationSeconds, boolean cacheHit) {

        /**
         * 兼容构造器（8 参，cache_hit 缺省 false）——老调用方/测试无需改动签名
         * （仓库纪律：不改既有构造签名；字段只走追加式通道）。
         */
        public Execution(int states, int questions, int candidatePaths,
                         int forwardPasses, int decodeSteps, int networkModelCalls,
                         String device, Double serverEvaluationSeconds) {
            this(states, questions, candidatePaths, forwardPasses, decodeSteps, networkModelCalls,
                    device, serverEvaluationSeconds, false);
        }
        /**
         * 是否「一次前向、零解码」证明（官方：forward_passes=1、autoregressive_decode_steps=0）。
         * <p><b>确定性缓存命中同样算通过</b>：HolzynLLMServer
         * 《NanoJev并发故障分析与修复 .md》§10.2 的确定性结果缓存，命中路径
         * <b>不跑 GPU</b>，响应标注 {@code execution.cache_hit=true} 且
         * 官方推理是纯函数（temperature=1、零采样，同输入同分布，概率偏差实测为 0），
         * 因此缓存命中与重算<b>语义等价</b>，不是「chat 静默回退」。
         * 旧判定只认 {@code forward_passes==1}，把命中一律判失败 → 整批 决策被丢弃
         * 。</p>
         *
         * @return true=通过（一次前向 或 确定性缓存命中 + 零解码）
         */
        public boolean forwardProof() {
            return decodeSteps == 0 && (forwardPasses == 1 || cacheHit);
        }
    }

    /**
     * 解析官方响应 JSON（宽松：结构不符返回 null，由调用方 fail-closed）。
     *
     * @param json 响应体原文
     *
     * @return 解析结果（非法返回 null）
     */
    public static NanoJevEvaluateResponse parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode exec = root.get("execution");
            if (exec == null || !exec.isObject()) {
                return null;
            }
            // 严格要求一次前向字段存在 + states 数组非空（缺任一=非官方响应形状，宽松回退 null）
            if (!exec.path("forward_passes").isNumber()) {
                return null;
            }
            JsonNode statesNode = root.get("states");
            if (statesNode == null || !statesNode.isArray() || statesNode.isEmpty()) {
                return null;
            }
            Execution execution = new Execution(
                    exec.path("states").asInt(0),
                    exec.path("questions").asInt(0),
                    exec.path("candidate_paths").asInt(0),
                    exec.path("forward_passes").asInt(0),
                    exec.path("autoregressive_decode_steps").asInt(0),
                    exec.path("network_model_calls").asInt(0),
                    exec.path("device").asText(null),
                    exec.path("server_evaluation_seconds").isNumber()
                            ? exec.path("server_evaluation_seconds").asDouble() : null,
                    // 确定性缓存命中标记（HolzynLLMServer §10.2 扩展字段；缺省=false=老版本服务，行为不变）
                    exec.path("cache_hit").asBoolean(false));
            Map<String, Map<String, NanoJevAnswer>> answers = new LinkedHashMap<>();
            for (JsonNode so : statesNode) {
                String sid = so.path("id").asText();
                Map<String, NanoJevAnswer> byQid = new LinkedHashMap<>();
                JsonNode ans = so.get("answers");
                if (ans != null && ans.isObject()) {
                    ans.properties().forEach(en -> byQid.put(en.getKey(), parseAnswer(en.getValue())));
                }
                answers.put(sid, byQid);
            }
            return new NanoJevEvaluateResponse(execution, answers, json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析单个题型答案（结构缺失回退空答案）。 */
    private static NanoJevAnswer parseAnswer(JsonNode n) {
        if (n == null || !n.isObject()) {
            return NanoJevAnswer.empty("unknown");
        }
        String type = n.path("type").asText("unknown");
        Map<String, Double> probs = new LinkedHashMap<>();
        JsonNode p = n.get("probabilities");
        if (p != null && p.isObject()) {
            p.properties().forEach(en -> probs.put(en.getKey(), en.getValue().asDouble()));
        }
        String choice = n.path("choice").isTextual() ? n.path("choice").asText() : null;
        Double pTrue = n.path("p_true").isNumber() ? n.path("p_true").asDouble() : null;
        Double score = n.path("score").isNumber() ? n.path("score").asDouble() : null;
        Integer level = n.path("level").isNumber() ? n.path("level").asInt() : null;
        Object value = null;
        JsonNode v = n.get("value");
        if (v != null && !v.isNull()) {
            value = v.isBoolean() ? v.asBoolean() : (v.isNumber() ? v.asDouble() : v.asText());
        }
        return new NanoJevAnswer(type, probs, choice, pTrue, score, level, value);
    }

    /**
     * 是否「一次前向、零解码」证明（forward_passes=1 且 decode_steps=0，
     * 或确定性缓存命中 cache_hit=true 且 decode_steps=0）——
     * 冒烟断言防静默回退（chat 回退路径无此字段）。
     *
     * @return true=通过
     */
    public boolean isForwardProof() {
        return execution != null && execution.forwardProof();
    }

    /**
     * 是否为 chat 回退响应（静默回退防护：出现 choices 且无 execution 形状）。
     *
     * @return true=疑似 chat 回退，调用方必须按失败处置
     */
    public boolean isChatFallback() {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            return root.has("choices") && (root.get("execution") == null || !root.get("execution").isObject());
        } catch (Exception e) {
            return false;
        }
    }

    /** 取指定 state/qid 的答案（缺省 null）。 */
    public NanoJevAnswer answerOf(String stateId, String qid) {
        Map<String, NanoJevAnswer> byQid = answers == null ? null : answers.get(stateId);
        return byQid == null ? null : byQid.get(qid);
    }
}
