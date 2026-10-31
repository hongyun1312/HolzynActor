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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.ai.decision.DecisionAnswer;
import com.holzyn.actor.ai.decision.DecisionCallClient;
import com.holzyn.actor.ai.decision.DecisionResponse;
import com.holzyn.actor.ai.decision.DecisionProperties;
import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.ai.decision.DecisionSampler;
import com.holzyn.actor.ai.decision.DecisionState;
import com.holzyn.actor.domain.evaluation.TestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * N 轨道 「决策模型 判定」调试接口。
 * <p>职责：</p>
 * <ul>
 * <li><b>status（只读常开）</b>：enabled/base-url/采样模式 + 网关健康检查
 * （GET 适配器健康端点——防未部署/静默回退；判定层 fail-closed）；</li>
 * <li><b>sample（测试操作，{@code holzyn.actor.evaluation.test-enabled=true} 才可用，默认 403）</b>：
 * 青禾村真实样例题（行为选择 choice / 社交意愿 boolean / 情绪档位 score）经客户端真实调用，
 * 返回完整概率分布 + 按采样模式的抽样结果 + execution 元数据（forward_passes 防静默回退断言）；
 * 支持 {@code overrideBaseUrl} 指向不可达地址验证 fail-closed 回退链路。</li>
 * </ul>
 * <p>安全约定：本地单用户、无鉴权（纯本地调试工具，勿公网暴露）；写/调用操作一律走
 * {@link TestRunService#requireTestEnabled()} 门控（与既有测试接口同口径）。</p>
 * <p>；N 轨道数据源=ai/decision）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/decision")
@RequiredArgsConstructor
public class DecisionDebugController {

    private final DecisionCallClient decisionClient;
    private final DecisionProperties decisionProps;
    private final TestRunService testRunService;

    /** 只读观测：启用状态 + 网关健康检查（常开）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", decisionClient.isEnabled());
        out.put("baseUrl", decisionClient.baseUrl());
        out.put("samplingMode", decisionProps.getSampling() == null ? "sample"
                : decisionProps.getSampling().getMode());
        out.put("batchLimits", Map.of(
                "maxStates", decisionProps.getMaxStatesPerRequest(),
                "maxQuestions", decisionProps.getMaxQuestionsPerRequest(),
                "maxPaths", decisionProps.getMaxPathsPerRequest(),
                "maxBodyBytes", decisionProps.getMaxBodyBytes()));
        out.put("health", decisionClient.healthCheck(null, null));
        return out;
    }

    /**
     * 测试操作：青禾村真实样例题。
     * <ul>
     * <li>{@code mode}=sample（默认，按分布采样）/ argmax（确定性）；</li>
     * <li>{@code overrideBaseUrl} 指向不可达地址时验证 fail-closed（返回 success=false + 原因，
     * 应用零报错、trace 记 failed）—— 验收项③「停服务→自动回退链路冒烟」的自动化载体。</li>
     * </ul>
     *
     * @param body 请求参数（可空）
     *
     * @return 结果（success=false 表示 fail-closed 回退发生；含原因与 trace 状态线索）
     */
    @PostMapping("/sample")
    public Map<String, Object> sample(@RequestBody(required = false) Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = number(body, "projectId", 1L);
        Long characterId = number(body, "characterId", 1L);
        String scene = body == null || body.get("scene") == null ? "decision_sample" : String.valueOf(body.get("scene"));
        String mode = body == null || body.get("mode") == null ? "sample" : String.valueOf(body.get("mode"));
        String overrideBaseUrl = body == null || body.get("overrideBaseUrl") == null
                ? null : String.valueOf(body.get("overrideBaseUrl"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("mode", mode);
        List<DecisionState> states = sampleStates();
        out.put("paramsSnapshot", new com.holzyn.actor.ai.decision.DecisionRequest(states).paramsSnapshot());
        long t0 = System.currentTimeMillis();
        DecisionResponse resp = decisionClient.evaluateWithOverride(
                null, projectId, characterId, scene, states, overrideBaseUrl);
        out.put("latencyMs", System.currentTimeMillis() - t0);
        if (resp == null) {
            // fail-closed 回退链路：客户端返回 null，调用方回退——此处如实呈现
            out.put("success", false);
            out.put("fallback", true);
            out.put("reason", "决策模型 调用失败/未启用 → 调用方回退 Qwen 或程序化（fail-closed，零报错）");
            log.info("[DecisionDebug] sample fail-closed 回退触发：projectId={} overrideBaseUrl={}",
                    projectId, overrideBaseUrl == null ? "(配置值)" : overrideBaseUrl);
            return out;
        }
        out.put("success", true);
        out.put("fallback", false);
        out.put("execution", Map.of(
                "states", resp.execution().states(),
                "questions", resp.execution().questions(),
                "candidatePaths", resp.execution().candidatePaths(),
                "forwardPasses", resp.execution().forwardPasses(),
                "decodeSteps", resp.execution().decodeSteps(),
                "cacheHit", resp.execution().cacheHit(),
                "device", resp.execution().device(),
                "serverEvaluationSeconds", resp.execution().serverEvaluationSeconds()));
        List<Map<String, Object>> results = new ArrayList<>();
        Random rng = new Random();
        for (DecisionState st : states) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("stateId", st.id());
            sm.put("state", st.state());
            List<Map<String, Object>> answers = new ArrayList<>();
            for (Map.Entry<String, DecisionQuestion> en : st.questions().entrySet()) {
                DecisionAnswer ans = resp.answerOf(st.id(), en.getKey());
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("qid", en.getKey());
                am.put("type", en.getValue().type());
                am.put("probabilities", ans == null ? Map.of() : ans.probabilities());
                am.put("entropy", ans == null ? null : DecisionSampler.entropy(ans.probabilities()));
                if (ans != null) {
                    if (DecisionQuestion.TYPE_BOOLEAN.equals(ans.type())) {
                        am.put("pTrue", ans.pTrue());
                        am.put("sampled", DecisionSampler.decide(ans.pTrue() == null ? 0 : ans.pTrue(), mode, rng));
                    } else if (DecisionQuestion.TYPE_SCORE.equals(ans.type())) {
                        am.put("expectedScore", ans.score());
                        am.put("argmaxLevel", ans.level());
                        am.put("sampledLevel", DecisionSampler.level(ans.probabilities(), mode, rng));
                    } else {
                        am.put("argmax", ans.choice());
                        am.put("sampled", DecisionSampler.choose(ans.probabilities(), mode, rng));
                    }
                }
                answers.add(am);
            }
            sm.put("answers", answers);
            results.add(sm);
        }
        out.put("results", results);
        return out;
    }

    /** 青禾村样例题。 */
    private static List<DecisionState> sampleStates() {
        Map<String, DecisionQuestion> qs = DecisionState.newQuestions();
        qs.put("action", DecisionQuestion.choice("此刻最想做什么？",
                Map.of("rest", "停下歇一歇，什么也不做",
                        "chat", "和路过的人聊几句",
                        "work", "继续把手头的活干完")));
        qs.put("willing", DecisionQuestion.bool("此刻是否愿意停下手中的活与人攀谈？",
                "不愿意，想先把活干完",
                "愿意，停下来说几句话"));
        qs.put("mood", DecisionQuestion.score("此刻的心情属于哪个档位？",
                List.of("心情低落/疲惫，提不起劲",
                        "平常心，不好不坏",
                        "心情不错，有兴致")));
        return List.of(DecisionState.text("s1",
                "青禾村·午后。孙二娘在灵溪河畔洗衣，阿贵路过打招呼。她今日心情尚可，但家务还没做完。",
                qs));
    }

    /** 请求体数值解析（缺省回退）。 */
    private static Long number(Map<String, Object> body, String key, long def) {
        if (body == null || body.get(key) == null) {
            return def;
        }
        try {
            return Long.parseLong(String.valueOf(body.get(key)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
