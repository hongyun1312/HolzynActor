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

package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalRun;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalSample;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalScore;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalRunRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalSampleRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalScoreRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 行为质量评估服务。
 * <p>职责：把「评估批跑」做成一次可回放的离线批次——</p>
 * <ol>
 * <li><b>采样</b>：DB 采样（当前项目 actor_ai_call_log 决策类 scene）或
 * JSONL 导入（v2 长跑 ai-calls.jsonl 捕获数据，DB 还原后仍可离线评）；</li>
 * <li><b>评分</b>：逐样本调用独立 judge 模型（云端 deepseek-v4-flash，与生成模型不同家族）
 * ——绝对评分 1-5 + 固定五维 rubric 锚点 + 样本长度上限截断 + 逐维 chain-of-thought
 * ；</li>
 * <li><b>落库</b>：actor_eval_sample（样本快照）+ actor_eval_score（逐维评分明细）+ 批次汇总/报告
 * （各维均值/分布/低分样本）；</li>
 * <li><b>并发</b>：4 路并发 judge 调用（默认，配置可调；云端模型单条样本独立无依赖）；</li>
 * <li><b>隔离</b>：批跑不阻塞测试接口（异步虚拟线程），judge 调用不落 actor_ai_call_log
 * （离线评估设施，非世界运行 AI）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorJudgeService {

    private final ActorEvalRunRepository evalRunRepository;
    private final ActorEvalSampleRepository sampleRepository;
    private final ActorEvalScoreRepository scoreRepository;
    private final DecisionSampleSampler sampler;
    private final JudgeScorer judgeScorer;
    private final PromptTemplateService promptTemplateService;
    private final EvaluationProperties properties;

    /** Jackson 3（报告/汇总 JSON 组装）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 评估批跑请求（④ 评估批跑）。 */
    public record EvalRunRequest(String source, Integer sampleCount, Integer windowGameMinutes,
                                 String npcRef, String jsonlPath) {
    }

    /**
     * 启动一次评估批跑（创建批次 → 异步执行；立即返回批次，进度经 GET /test/eval-runs/{id} 查看）。
     *
     * @param projectId 项目 ID
     *
     * @param req 批跑请求（source=db|jsonl）
     *
     * @return 已创建的批次实体
     */
    @Transactional
    public ActorEvalRun startBatch(Long projectId, EvalRunRequest req) {
        String source = req.source() == null || req.source().isBlank() ? "db" : req.source().trim();
        if (!"db".equals(source) && !"jsonl".equals(source)) {
            throw new BizException(400, "source 仅支持 db（DB 采样）/ jsonl（JSONL 导入）");
        }
        if ("jsonl".equals(source) && (req.jsonlPath() == null || req.jsonlPath().isBlank())) {
            throw new BizException(400, "source=jsonl 时 jsonlPath 必填（长跑捕获的 ai-calls.jsonl 绝对路径）");
        }
        ActorEvalRun run = new ActorEvalRun();
        run.setProjectId(projectId);
        run.setSampleSource(source);
        run.setJudgeProvider(properties.getJudgeProviderLabel());
        run.setJudgeModel(judgeScorer.modelName());
        run.setRubricVersion(properties.getRubricVersion());
        run.setStatus("pending");
        run = evalRunRepository.save(run);
        ActorEvalRun finalRun = run;
        executor().execute(() -> runBatchQuietly(finalRun.getId(), req));
        return run;
    }

    /** 批跑入口（异常隔离：批次级失败落 error_message，不抛给调用方）。 */
    private void runBatchQuietly(Long runId, EvalRunRequest req) {
        try {
            runBatch(runId, req);
        } catch (Exception e) {
            log.warn("[评估批跑] 批次 {} 失败: {}", runId, e.getMessage());
            ActorEvalRun run = evalRunRepository.findById(runId).orElse(null);
            if (run != null) {
                run.setStatus("failed");
                run.setErrorMessage(truncate(e.getMessage(), 2000));
                run.setFinishedAt(LocalDateTime.now());
                evalRunRepository.save(run);
            }
        }
    }

    /**
     * 执行一次批跑（同步核心；测试可直接调用）。
     *
     * @param runId 批次 ID
     *
     * @param req 批跑请求
     */
    @Transactional
    public void runBatch(Long runId, EvalRunRequest req) {
        ActorEvalRun run = evalRunRepository.findById(runId)
                .orElseThrow(() -> new BizException(404, "评估批次不存在：" + runId));
        run.setStatus("running");
        evalRunRepository.save(run);
        Long projectId = run.getProjectId();

        // ① 采样（双通道）
        List<DecisionSample> samples = "jsonl".equals(run.getSampleSource())
                ? sampler.sampleFromJsonl(projectId, req.jsonlPath(), req.sampleCount())
                : sampler.sampleFromDb(projectId, req.sampleCount(), req.windowGameMinutes(), req.npcRef());
        if (samples.isEmpty()) {
            run.setStatus("done");
            run.setSampleCount(0);
            run.setScoredCount(0);
            run.setSummaryJson("{\"sampleCount\":0,\"scoredCount\":0,\"message\":\"无可用决策样本（时间窗内无决策类 AI 调用，或 JSONL 无匹配行）\"}");
            run.setFinishedAt(LocalDateTime.now());
            evalRunRepository.save(run);
            return;
        }
        int cap = Math.min(samples.size(),
                req.sampleCount() == null || req.sampleCount() <= 0 ? properties.getDefaultSampleCount() : req.sampleCount());
        samples = samples.subList(0, Math.min(cap, samples.size()));

        // ② 落样本（seq 递增）
        List<ActorEvalSample> persisted = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            DecisionSample s = samples.get(i);
            ActorEvalSample e = new ActorEvalSample();
            e.setEvalRunId(runId);
            e.setSeq(i + 1);
            e.setSource(s.source());
            e.setNpcRef(s.npcRef());
            e.setNpcName(s.npcName());
            e.setScene(s.scene());
            e.setCallStatus(s.callStatus());
            e.setGameSecond(s.gameSecond());
            e.setStimulusText(truncate(s.stimulusText(), properties.getJudgeMaxInputChars()));
            e.setContextText(truncate(s.contextText(), properties.getJudgeMaxInputChars()));
            e.setResponseText(truncate(s.responseText(), properties.getJudgeMaxInputChars()));
            e.setTraceId(s.traceId());
            persisted.add(sampleRepository.save(e));
        }
        run.setSampleCount(persisted.size());
        evalRunRepository.save(run);

        // ③ 逐样本评分（并发池，默认 4 路）
        int concurrency = Math.max(1, properties.getJudgeConcurrency());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, persisted.size()));
        // run 在后续会重新拉取（汇总报告前），lambda 需捕获 final 副本
        final ActorEvalRun runRef = run;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ActorEvalSample sample : persisted) {
                futures.add(pool.submit(() -> scoreOne(projectId, runRef, sample)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.MINUTES); // 单样本看门狗（远大于 judge 超时）
                } catch (Exception e) {
                    log.warn("[评估批跑] 单样本评分等待异常: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        // ④ 汇总报告
        run = evalRunRepository.findById(runId).orElse(run);
        String summary = buildSummary(run);
        run.setSummaryJson(summary);
        run.setReportJson(buildReport(run, summary));
        run.setStatus("done");
        run.setFinishedAt(LocalDateTime.now());
        evalRunRepository.save(run);
        log.info("[评估批跑] 批次 {} 完成：project={} 样本={} 已评分={}",
                runId, projectId, run.getSampleCount(), run.getScoredCount());
    }

    /** 单样本评分：调用 judge → 解析 → 落 actor_eval_score。 */
    private void scoreOne(Long projectId, ActorEvalRun run, ActorEvalSample sample) {
        String sys = promptTemplateService.systemMessage(0L, projectId,
                PromptTemplateService.CODE_BEHAVIOR_JUDGE, null);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("npc_name", sample.getNpcName() == null ? "（无归属/项目级调用）" : sample.getNpcName());
        vars.put("npc_ref", sample.getNpcRef() == null ? "-" : sample.getNpcRef());
        vars.put("scene", sample.getScene());
        vars.put("stimulus", sample.getStimulusText() == null ? "（无）" : sample.getStimulusText());
        vars.put("context", sample.getContextText() == null ? "（无）" : sample.getContextText());
        vars.put("response", sample.getResponseText() == null ? "（无）" : sample.getResponseText());
        String user = promptTemplateService.render(0L, projectId,
                PromptTemplateService.CODE_BEHAVIOR_JUDGE, vars);

        ActorEvalScore score = new ActorEvalScore();
        score.setEvalRunId(run.getId());
        score.setSampleId(sample.getId());
        score.setJudgeModel(judgeScorer.modelName());
        try {
            String raw = judgeScorer.call(sys, user);
            JudgeScoreParse.JudgeResult parsed = JudgeScoreParse.parse(raw);
            score.setScoresJson(toJson(parsed.scores()));
            score.setReasonsJson(toJson(parsed.reasons()));
            score.setSummary(truncate(parsed.summary(), 1000));
            score.setJudgeRawJson(truncate(raw, 8000));
            score.setTotalScore(parsed.total());
            scoreRepository.save(score);
            synchronized (run) {
                run.setScoredCount((run.getScoredCount() == null ? 0 : run.getScoredCount()) + 1);
                // 并发线程内持久化计数（runBatch 汇总前按 DB 行读取，须落库）
                evalRunRepository.save(run);
            }
        } catch (Exception e) {
            score.setJudgeRawJson(truncate("评分失败：" + e.getMessage(), 2000));
            scoreRepository.save(score);
            log.warn("[评估批跑] 样本 {} 评分失败: {}", sample.getId(), e.getMessage());
        }
    }

    /** 批次汇总 JSON：各维均值/分布/总分均值/低分样本/高分样本。 */
    private String buildSummary(ActorEvalRun run) {
        List<ActorEvalScore> scores = scoreRepository.findByEvalRunIdOrderByIdAsc(run.getId());
        ObjectNode root = MAPPER.createObjectNode();
        root.put("sampleCount", run.getSampleCount() == null ? 0 : run.getSampleCount());
        root.put("scoredCount", run.getScoredCount() == null ? 0 : run.getScoredCount());
        root.put("judgeModel", run.getJudgeModel());
        root.put("rubricVersion", run.getRubricVersion());
        // 总分（0-50）统计
        List<Integer> totals = new ArrayList<>();
        for (ActorEvalScore s : scores) {
            if (s.getTotalScore() != null && s.getTotalScore() > 0) {
                totals.add(s.getTotalScore());
            }
        }
        if (!totals.isEmpty()) {
            root.put("totalAvg", Math.round(mean(totals) * 10.0) / 10.0);
        } else {
            root.put("totalAvg", 0);
        }
        // 逐维统计
        ObjectNode dims = MAPPER.createObjectNode();
        for (String dim : EvaluationProperties.RUBRIC_DIMENSIONS) {
            List<Integer> vals = new ArrayList<>();
            for (ActorEvalScore s : scores) {
                Integer v = dimValue(s.getScoresJson(), dim);
                if (v != null) {
                    vals.add(v);
                }
            }
            ObjectNode d = MAPPER.createObjectNode();
            d.put("mean", vals.isEmpty() ? 0 : Math.round(mean(vals) * 10.0) / 10.0);
            d.put("count", vals.size());
            ObjectNode dist = MAPPER.createObjectNode();
            for (int i = 1; i <= 5; i++) {
                final int si = i; // lambda 捕获需 effectively final
                int c = (int) vals.stream().filter(v -> v == si).count();
                dist.put("s" + i, c);
            }
            d.set("distribution", dist);
            dims.set(dim, d);
        }
        root.set("dimensions", dims);
        // 低分/高分样本（按总分）
        ArrayNode low = MAPPER.createArrayNode();
        ArrayNode high = MAPPER.createArrayNode();
        for (ActorEvalScore s : scores) {
            if (s.getTotalScore() == null || s.getTotalScore() <= 0) {
                continue;
            }
            ActorEvalSample sample = sampleRepository.findById(s.getSampleId()).orElse(null);
            ObjectNode n = MAPPER.createObjectNode();
            n.put("sampleId", s.getSampleId());
            n.put("seq", sample == null ? 0 : sample.getSeq());
            n.put("npcName", sample == null ? null : sample.getNpcName());
            n.put("scene", sample == null ? null : sample.getScene());
            n.put("total", s.getTotalScore());
            if (s.getTotalScore() <= 30) {
                low.add(n);
            } else if (s.getTotalScore() >= 40) {
                high.add(n);
            }
        }
        root.set("lowSamples", low);
        root.set("highSamples", high);
        return root.toString();
    }

    /** 完整报告 JSON：汇总 + 逐样本明细（回放/报告接口）。 */
    private String buildReport(ActorEvalRun run, String summaryJson) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("evalRunId", run.getId());
        try {
            root.set("summary", MAPPER.readTree(summaryJson));
        } catch (Exception ignored) {
            root.put("summary", summaryJson);
        }
        ArrayNode samples = MAPPER.createArrayNode();
        for (ActorEvalScore s : scoreRepository.findByEvalRunIdOrderByIdAsc(run.getId())) {
            ActorEvalSample sample = sampleRepository.findById(s.getSampleId()).orElse(null);
            ObjectNode n = MAPPER.createObjectNode();
            n.put("sampleId", s.getSampleId());
            n.put("seq", sample == null ? 0 : sample.getSeq());
            n.put("npcRef", sample == null ? null : sample.getNpcRef());
            n.put("npcName", sample == null ? null : sample.getNpcName());
            n.put("scene", sample == null ? null : sample.getScene());
            n.put("source", sample == null ? null : sample.getSource());
            n.put("total", s.getTotalScore());
            putJson(n, "scores", s.getScoresJson());
            putJson(n, "reasons", s.getReasonsJson());
            n.put("summary", s.getSummary());
            if (sample != null) {
                n.put("stimulus", truncate(sample.getStimulusText(), 500));
                n.put("response", truncate(sample.getResponseText(), 500));
            }
            samples.add(n);
        }
        root.set("samples", samples);
        return root.toString();
    }

    // ==================== 内部工具 ====================

    /** 虚拟线程执行器（异步批跑；单次启动即用，无常驻池）。 */
    private ExecutorService executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** scores_json 中某维分数（非法/缺失 null）。 */
    private static Integer dimValue(String scoresJson, String dim) {
        if (scoresJson == null || scoresJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(scoresJson);
            JsonNode v = node.get(dim);
            if (v == null || !v.canConvertToInt()) {
                return null;
            }
            return v.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static double mean(List<Integer> vals) {
        double sum = 0;
        for (int v : vals) {
            sum += v;
        }
        return sum / vals.size();
    }

    /** Map → JSON 字符串（空 Map → "{}"）。 */
    static String toJson(Map<?, ?> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** JSON 字符串写入节点（非法跳过）。 */
    private static void putJson(ObjectNode node, String key, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            node.set(key, MAPPER.readTree(json));
        } catch (Exception ignored) {
            // 非法 JSON 不阻断
        }
    }

    /** 文本截断。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
