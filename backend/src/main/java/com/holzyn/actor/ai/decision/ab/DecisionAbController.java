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

package com.holzyn.actor.ai.decision.ab;

import com.holzyn.actor.ai.decision.ab.entity.ActorDecisionAbRun;
import com.holzyn.actor.ai.decision.ab.entity.ActorDecisionAbSample;
import com.holzyn.actor.ai.decision.ab.repository.ActorDecisionAbRunRepository;
import com.holzyn.actor.ai.decision.ab.repository.ActorDecisionAbSampleRepository;
import com.holzyn.actor.domain.evaluation.TestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N 轨道 「决策模型 A/B 质量评估」调试接口（，ai/decision/ab）。
 * <p>职责：</p>
 * <ul>
 * <li><b>GET /runs（只读常开）</b>：项目最近 A/B 批跑列表（状态/样本数/一致数）；</li>
 * <li><b>GET /runs/{id}（只读常开）</b>：批次报告（汇总：一致率/分布合理性/延迟表/
 * 按场景分桶阈值标定 + 逐样本明细：题面/决策模型 分布/Qwen 重问/judge 评分/match）；</li>
 * <li><b>POST /runs（测试操作，{@code holzyn.actor.evaluation.test-enabled=true} 才可用，
 * 默认 403）</b>：创建异步批跑 {@code {projectId?, sceneScope?}}（all=三类全跑 /
 * emotion_eval / crowd_decision / social_willingness）；批跑执行不阻塞接口，
 * 进度经 GET 轮询。</li>
 * </ul>
 * <p>安全约定：本地单用户、无鉴权（纯本地调试工具，勿公网暴露）；写/调用操作一律走
 * {@link TestRunService#requireTestEnabled()} 门控（与既有测试接口同口径）。</p>
 * <p>；数据源=ai/decision）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/decision/ab")
@RequiredArgsConstructor
public class DecisionAbController {

    private final ActorDecisionAbRunRepository runRepository;
    private final ActorDecisionAbSampleRepository sampleRepository;
    private final DecisionAbBatchRunner batchRunner;
    private final DecisionAbProperties properties;
    private final TestRunService testRunService;

    /** 只读观测：A/B 评估配置 + 最近批跑列表（常开）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("concurrency", properties.getConcurrency());
        out.put("sampleQuotas", properties.getSampleQuotas() == null ? Map.of() : properties.getSampleQuotas());
        out.put("qwen", properties.getQwen() == null ? Map.of() : Map.of(
                "scene", properties.getQwen().getScene(),
                "temperature", properties.getQwen().getTemperature(),
                "maxTokens", properties.getQwen().getMaxTokens()));
        out.put("escalation", properties.getEscalation() == null ? Map.of() : Map.of(
                "enabled", properties.getEscalation().isEnabled(),
                "top1Prob", properties.getEscalation().getTop1Prob() == null
                        ? Map.of() : properties.getEscalation().getTop1Prob(),
                "entropy", properties.getEscalation().getEntropy() == null
                        ? Map.of() : properties.getEscalation().getEntropy()));
        return out;
    }

    /** 只读：最近批跑列表（项目 1=青禾村；projectId 参数可空默认 1）。 */
    @GetMapping("/runs")
    public Map<String, Object> runs(@org.springframework.web.bind.annotation.RequestParam(required = false)
                                    Long projectId) {
        long pid = projectId == null ? 1L : projectId;
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActorDecisionAbRun r : runRepository.findTop20ByProjectIdOrderByIdDesc(pid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("sceneScope", r.getSceneScope());
            m.put("status", r.getStatus());
            m.put("sampleCount", r.getSampleCount());
            m.put("comparedCount", r.getComparedCount());
            m.put("matchedCount", r.getMatchedCount());
            m.put("qwenModel", r.getQwenModel());
            m.put("judgeModel", r.getJudgeModel());
            m.put("templateVersion", r.getTemplateVersion());
            m.put("createdAt", String.valueOf(r.getCreatedAt()));
            m.put("finishedAt", r.getFinishedAt() == null ? null : String.valueOf(r.getFinishedAt()));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", pid);
        out.put("runs", list);
        return out;
    }

    /** 只读：批次报告（汇总 + 逐样本明细）。 */
    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        ActorDecisionAbRun run = runRepository.findById(id)
                .orElseThrow(() -> new com.holzyn.actor.common.BizException(404, "A/B 批次不存在：" + id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("projectId", run.getProjectId());
        out.put("sceneScope", run.getSceneScope());
        out.put("status", run.getStatus());
        out.put("sampleCount", run.getSampleCount());
        out.put("comparedCount", run.getComparedCount());
        out.put("matchedCount", run.getMatchedCount());
        out.put("qwenModel", run.getQwenModel());
        out.put("judgeModel", run.getJudgeModel());
        out.put("templateVersion", run.getTemplateVersion());
        out.put("errorMessage", run.getErrorMessage());
        out.put("createdAt", String.valueOf(run.getCreatedAt()));
        out.put("finishedAt", run.getFinishedAt() == null ? null : String.valueOf(run.getFinishedAt()));
        out.put("summary", parseJson(run.getSummaryJson()));
        out.put("report", parseJson(run.getReportJson()));
        // 逐样本明细（轻量：题面/分布/重问/judge/match）
        List<Map<String, Object>> samples = new ArrayList<>();
        for (ActorDecisionAbSample s : sampleRepository.findByAbRunIdOrderBySeqAsc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("scene", s.getScene());
            m.put("questionType", s.getQuestionType());
            m.put("sourceScene", s.getSourceScene());
            m.put("npcRef", s.getNpcRef());
            m.put("npcName", s.getNpcName());
            m.put("match", s.getMatchFlag() == 1);
            m.put("decisionStatus", s.getDecisionStatus());
            m.put("qwenStatus", s.getQwenStatus());
            m.put("decisionLatencyMs", s.getDecisionLatencyMs());
            m.put("qwenLatencyMs", s.getQwenLatencyMs());
            m.put("question", parseJson(s.getQuestionJson()));
            m.put("decision", parseJson(s.getDecisionResponseJson()));
            m.put("qwen", parseJson(s.getQwenResponseJson()));
            m.put("judge", parseJson(s.getJudgeScoresJson()));
            samples.add(m);
        }
        out.put("samples", samples);
        return out;
    }

    /**
     * 测试操作：创建异步 A/B 批跑。
     * <p>请求体：{@code {projectId?, sceneScope?}}——sceneScope=all（默认，三类全跑）/
     * emotion_eval / crowd_decision / social_willingness；批跑立即返回 run（pending），
     * 进度经 GET /runs/{id} 轮询（异步虚拟线程，不阻塞请求）。</p>
     *
     * @param body 请求参数（可空）
     *
     * @return 已创建的批次
     */
    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody(required = false) Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = body == null || body.get("projectId") == null
                ? 1L : number(body, "projectId", 1L);
        String sceneScope = body == null || body.get("sceneScope") == null
                ? "all" : String.valueOf(body.get("sceneScope"));
        ActorDecisionAbRun run = batchRunner.startRun(projectId,
                new DecisionAbBatchRunner.AbRunRequest(sceneScope));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("projectId", run.getProjectId());
        out.put("sceneScope", run.getSceneScope());
        out.put("status", run.getStatus());
        out.put("message", "批跑已创建，异步执行中——GET /api/debug/decision/ab/runs/{id} 轮询报告");
        return out;
    }

    /** 请求体数值解析（缺省回退）。 */
    private static Long number(Map<String, Object> body, String key, long def) {
        try {
            return Long.parseLong(String.valueOf(body.get(key)));
        } catch (Exception e) {
            return def;
        }
    }

    /** JSON 字符串 → 节点（非法 null）。 */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    /** Jackson 3（报告 JSON 读取）。 */
    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();
}
