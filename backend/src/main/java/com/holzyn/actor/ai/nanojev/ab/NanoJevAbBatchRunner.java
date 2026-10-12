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

package com.holzyn.actor.ai.nanojev.ab;

import com.holzyn.actor.ai.nanojev.NanoJevAnswer;
import com.holzyn.actor.ai.nanojev.NanoJevEvaluateClient;
import com.holzyn.actor.ai.nanojev.NanoJevEvaluateResponse;
import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.nanojev.NanoJevSampler;
import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.ai.nanojev.ab.entity.ActorNanoJevAbRun;
import com.holzyn.actor.ai.nanojev.ab.entity.ActorNanoJevAbSample;
import com.holzyn.actor.ai.nanojev.ab.repository.ActorNanoJevAbRunRepository;
import com.holzyn.actor.ai.nanojev.ab.repository.ActorNanoJevAbSampleRepository;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.evaluation.JudgeScoreParse;
import com.holzyn.actor.domain.evaluation.JudgeScorer;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
 * NanoJev A/B 质量评估 · 异步批跑编排。
 * <p>流程：</p>
 * <ol>
 * <li><b>样本构造</b>：{@link NanoJevAbSampleFactory} 从青禾村真实运行记录
 * （actor_ai_call_log）抽取真实情境 → 三类场景三题型判定题（配额：情绪 75 /
 * 候选选择 75 / 社交意愿 50）；</li>
 * <li><b>落样本</b>：actor_nanojev_ab_sample 行（seq 递增，题面/情境快照）；</li>
 * <li><b>双轨对照</b>（逐样本，4 路并发）：NanoJev evaluate（scene=nanojev_*，完整分布 +
 * argmax + 熵）+ Qwen 同题型重问（jsonMode 输出选中项）→ <b>match = NanoJev top-1
 * == Qwen 选中项</b>；</li>
 * <li><b>Judge 四维合理性评分</b>（复用 JudgeChatClient + nanojev_ab_judge 模板，
 * ⑪全量评分）；</li>
 * <li><b>汇总报告</b>：{@link NanoJevAbReportService} 一致率/分布合理性/延迟表/按场景
 * 分桶阈值标定 → run.summary_json + report_json。</li>
 * </ol>
 * <p><b>出口条件</b>：报告输出各场景 top-1
 * 一致率、分布尖锐度、延迟对照、阈值标定建议；落地范围清单由用户按报告确认后再定
 * 。</p>
 * <p><b>架构不变量</b>：本类批跑核心方法一律<b>不带 {@code @Transactional}</b>——
 * NanoJev/Qwen/judge 调用均为 AI 调用（网络等待），事务内持锁违反 固化
 * 架构不变量（AiCallOutsideTransactionTest 扩展守护）；落库由仓库短事务各自承担。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NanoJevAbBatchRunner {

    private final ActorNanoJevAbRunRepository runRepository;
    private final ActorNanoJevAbSampleRepository sampleRepository;
    private final NanoJevAbSampleFactory sampleFactory;
    private final NanoJevEvaluateClient nanoJevEvaluateClient;
    private final QwenBaselineClient qwenBaselineClient;
    private final NanoJevAbProperties properties;
    private final JudgeScorer judgeScorer;
    private final PromptTemplateService promptTemplateService;

    /** Jackson 3（题面/分布/报告 JSON 组装）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 批跑请求。 */
    public record AbRunRequest(String sceneScope) {
    }

    /**
     * 启动一次 A/B 批跑（创建批次 → 异步执行；立即返回批次，进度经调试接口查看）。
     *
     * @param projectId 项目 ID
     *
     * @param req 批跑请求（sceneScope=all/emotion_eval/crowd_decision/social_willingness）
     *
     * @return 已创建的批次实体
     */
    public ActorNanoJevAbRun startRun(Long projectId, AbRunRequest req) {
        if (!properties.isEnabled()) {
            throw new BizException(400,
                    "NanoJev A/B 评估未启用（holzyn.actor.ai.nanojev.ab.enabled=false）——"
                            + "评估是离线设施，需显式开启后才可批跑");
        }
        String scope = req.sceneScope() == null || req.sceneScope().isBlank()
                ? "all" : req.sceneScope().trim();
        if (!List.of("all", AbSample.SCENE_EMOTION_EVAL, AbSample.SCENE_CROWD_DECISION,
                AbSample.SCENE_SOCIAL_WILLINGNESS).contains(scope)) {
            throw new BizException(400, "sceneScope 仅支持 all/emotion_eval/crowd_decision/social_willingness");
        }
        ActorNanoJevAbRun run = new ActorNanoJevAbRun();
        run.setProjectId(projectId);
        run.setSceneScope(scope);
        run.setStatus("pending");
        run.setQwenModel(qwenModelName());
        run.setJudgeModel(judgeScorer.modelName());
        run.setTemplateVersion("2");
        run = runRepository.save(run);
        ActorNanoJevAbRun finalRun = run;
        executor().execute(() -> runBatchQuietly(finalRun.getId(), scope));
        return run;
    }

    /** 批跑入口（异常隔离：批次级失败落 error_message，不抛给调用方）。 */
    private void runBatchQuietly(Long runId, String scope) {
        try {
            runBatch(runId, scope);
        } catch (Exception e) {
            log.warn("[NanoJev A/B] 批跑 {} 失败: {}", runId, e.getMessage());
            ActorNanoJevAbRun run = runRepository.findById(runId).orElse(null);
            if (run != null) {
                run.setStatus("failed");
                run.setErrorMessage(truncate(e.getMessage(), 2000));
                run.setFinishedAt(LocalDateTime.now());
                runRepository.save(run);
            }
        }
    }

    /**
     * 执行一次批跑（同步核心；测试可直接调用）。
     * <p><b>无 @Transactional</b>：本方法含 NanoJev/Qwen/judge AI 调用（网络等待），
     * 事务内持锁违反架构不变量；落库经仓库短事务各自独立提交。</p>
     *
     * @param runId 批次 ID
     *
     * @param scope 场景范围
     */
    public void runBatch(Long runId, String scope) {
        ActorNanoJevAbRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BizException(404, "A/B 批次不存在：" + runId));
        run.setStatus("running");
        runRepository.save(run);
        Long projectId = run.getProjectId();

        // ① 样本构造（真实情境 → 三题型判定题）
        List<AbSample> samples = sampleFactory.buildSamples(projectId, scope);
        if (samples.isEmpty()) {
            run.setStatus("done");
            run.setSampleCount(0);
            run.setComparedCount(0);
            run.setMatchedCount(0);
            run.setSummaryJson("{\"sampleCount\":0,\"comparedCount\":0,\"matchedCount\":0,"
                    + "\"message\":\"无可用真实情境样本（时间窗内无对应场景成功 AI 调用）\"}");
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
            return;
        }

        // ② 落样本（seq 递增）
        List<ActorNanoJevAbSample> persisted = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            AbSample s = samples.get(i);
            ActorNanoJevAbSample e = new ActorNanoJevAbSample();
            e.setAbRunId(runId);
            e.setSeq(i + 1);
            e.setScene(s.scene());
            e.setQuestionType(s.questionType());
            e.setSourceScene(s.sourceScene());
            e.setTraceId(s.traceId());
            e.setNpcRef(s.npcRef());
            e.setNpcName(s.npcName());
            e.setStateText(truncate(s.stateText(), stateMaxChars()));
            e.setQuestionJson(questionSnapshot(s.question()));
            persisted.add(sampleRepository.save(e));
        }
        run.setSampleCount(persisted.size());
        runRepository.save(run);

        // ③ 双轨对照（NanoJev + Qwen 同题型重问；并发池 4 路）
        int concurrency = Math.max(1, properties.getConcurrency());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, persisted.size()));
        final ActorNanoJevAbRun runRef = run;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ActorNanoJevAbSample sample : persisted) {
                futures.add(pool.submit(() -> compareOne(projectId, runRef, sample, samples.get(sample.getSeq() - 1))));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.MINUTES); // 单样本看门狗（远大于三端超时之和）
                } catch (Exception e) {
                    log.warn("[NanoJev A/B] 单样本对照等待异常: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        // ④ Judge 四维合理性评分（并发复用同一池语义；另开池避免长任务相互饿死）
        run = runRepository.findById(runId).orElse(run);
        final ActorNanoJevAbRun runForJudge = run;
        List<ActorNanoJevAbSample> finalSamples = sampleRepository.findByAbRunIdOrderBySeqAsc(runId);
        ExecutorService judgePool = Executors.newFixedThreadPool(Math.min(concurrency, finalSamples.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ActorNanoJevAbSample sample : finalSamples) {
                futures.add(judgePool.submit(() -> judgeOne(projectId, runForJudge, sample)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[NanoJev A/B] 单样本 judge 等待异常: {}", e.getMessage());
                }
            }
        } finally {
            judgePool.shutdown();
        }

        // ⑤ 汇总报告（一致率/分布/延迟/按场景分桶阈值标定）
        run = runRepository.findById(runId).orElse(run);
        Map<String, Object> report = aggregateReport(runId, run);
        run.setSummaryJson(toJson(report.get("summary")));
        Object reportObj = report.get("report");
        run.setReportJson(reportObj instanceof String s ? s : toJson(reportObj));
        run.setStatus("done");
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
        log.info("[NanoJev A/B] 批跑 {} 完成：project={} 样本={} 对照={} 一致={}",
                runId, projectId, run.getSampleCount(), run.getComparedCount(), run.getMatchedCount());
    }

    // ==================== 单样本双轨对照 ====================

    /** 单样本：NanoJev evaluate + Qwen 同题型重问 → match 判定 → 落库。 */
    private void compareOne(Long projectId, ActorNanoJevAbRun run, ActorNanoJevAbSample sample, AbSample ab) {
        // ① NanoJev（evaluate 分布；scene=nanojev_{scene}；state id 按样本 seq 唯一）
        String stateId = "s" + sample.getSeq();
        Map<String, Object> nanojev = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        try {
            NanoJevState state = NanoJevState.text(stateId, sample.getStateText(),
                    Map.of("q1", ab.question()));
            NanoJevEvaluateResponse resp = nanoJevEvaluateClient.evaluateOnce(
                    null, projectId, sample.getNpcRef() == null ? null : npcId(sample.getNpcRef()),
                    ab.nanojevScene(), List.of(state));
            long nanojevMs = System.currentTimeMillis() - t0;
            if (resp != null) {
                NanoJevAnswer ans = resp.answerOf(stateId, "q1");
                if (ans != null) {
                    nanojev.put("type", ans.type());
                    nanojev.put("probabilities", ans.probabilities());
                    nanojev.put("entropy", NanoJevSampler.entropy(ans.probabilities()));
                    if (NanoJevQuestion.TYPE_BOOLEAN.equals(ans.type())) {
                        nanojev.put("pTrue", ans.pTrue());
                        nanojev.put("top1", ans.pTrue() != null && ans.pTrue() >= 0.5);
                    } else if (NanoJevQuestion.TYPE_SCORE.equals(ans.type())) {
                        nanojev.put("expectedScore", ans.score());
                        nanojev.put("top1", String.valueOf(ans.level()));
                    } else {
                        nanojev.put("top1", ans.choice());
                    }
                    nanojev.put("latencyMs", nanojevMs);
                    sample.setNanojevLatencyMs(nanojevMs);
                    sample.setNanojevStatus("success");
                } else {
                    sample.setNanojevStatus("failed");
                    nanojev.put("error", "响应缺 q1 答案");
                }
            } else {
                sample.setNanojevStatus("failed");
                nanojev.put("error", "fail-closed 回退（调用失败/未启用）");
            }
        } catch (Exception e) {
            sample.setNanojevStatus("failed");
            nanojev.put("error", e.getMessage());
        }
        sample.setNanojevResponseJson(toJson(nanojev));

        // ② Qwen 同题型重问
        long t1 = System.currentTimeMillis();
        QwenAnswerParse.QwenAnswer qwen = qwenBaselineClient.ask(null, projectId,
                sample.getNpcRef() == null ? null : npcId(sample.getNpcRef()),
                sample.getNpcRef(), sample.getNpcName(), sample.getStateText(), ab.question());
        long qwenMs = System.currentTimeMillis() - t1;
        Map<String, Object> qwenMap = new LinkedHashMap<>();
        if (qwen != null) {
            qwenMap.put("answer", qwen.answer());
            qwenMap.put("raw", truncate(qwen.raw(), 500));
            sample.setQwenLatencyMs(qwenMs);
            sample.setQwenStatus("success");
        } else {
            qwenMap.put("error", "Qwen 重问失败/输出不可解析");
            sample.setQwenLatencyMs(qwenMs);
            sample.setQwenStatus("failed");
        }
        sample.setQwenResponseJson(toJson(qwenMap));

        // ③ match：NanoJev top-1 == Qwen 选中项（任一侧失败 → 0，不参与分子）
        boolean matched = "success".equals(sample.getNanojevStatus())
                && "success".equals(sample.getQwenStatus())
                && nanojev.get("top1") != null
                && top1Equals(nanojev.get("top1"), qwen == null ? null : qwen.answer(), ab.question());
        sample.setMatchFlag(matched ? 1 : 0);
        sampleRepository.save(sample);

        synchronized (run) {
            run.setComparedCount((run.getComparedCount() == null ? 0 : run.getComparedCount()) + 1);
            if (matched) {
                run.setMatchedCount((run.getMatchedCount() == null ? 0 : run.getMatchedCount()) + 1);
            }
            runRepository.save(run);
        }
    }

    /**
     * top-1 一致判定：NanoJev top-1（字符串/布尔）与 Qwen answer（字符串/布尔/整数）归一比较。
     * <p>choice 场景宽松处理：Qwen 可能输出候选键（act_1）或候选描述文本——建立
     * 描述→键 反向映射，两侧都归一为候选键后比较。</p>
     */
    static boolean top1Equals(Object nanojevTop1, Object qwenAnswer, NanoJevQuestion question) {
        if (nanojevTop1 == null || qwenAnswer == null) {
            return false;
        }
        String n = String.valueOf(nanojevTop1).trim();
        String q = String.valueOf(qwenAnswer).trim();
        if (NanoJevQuestion.TYPE_SCORE.equals(question.type())) {
            // score：档位索引字符串相等（NanoJev level=Integer、Qwen answer=Integer）
            return n.equals(q);
        }
        if (NanoJevQuestion.TYPE_BOOLEAN.equals(question.type())) {
            // boolean：true/false 文本一致
            return n.equalsIgnoreCase(q);
        }
        // choice：候选键一致（Qwen 可能输出候选描述文本——反向映射后比较）
        if (n.equals(q)) {
            return true;
        }
        if (question.criteria() == null) {
            return false;
        }
        // 描述 → 键 反向映射（值完全匹配时取键）
        for (Map.Entry<String, String> e : question.criteria().entrySet()) {
            if (q.equals(e.getValue()) || q.equals(e.getValue().trim())) {
                return n.equals(e.getKey());
            }
        }
        return false;
    }

    // ==================== 单样本 Judge 四维评分 ====================

    /** 单样本 Judge 评分：渲染 nanojev_ab_judge 模板 → 调用 judge → 解析落库。 */
    private void judgeOne(Long projectId, ActorNanoJevAbRun run, ActorNanoJevAbSample sample) {
        try {
            String sys = promptTemplateService.systemMessage(0L, projectId,
                    PromptTemplateService.CODE_NANOJEV_AB_JUDGE, null);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("npc_name", sample.getNpcName() == null ? "（无归属）" : sample.getNpcName());
            vars.put("npc_ref", sample.getNpcRef() == null ? "-" : sample.getNpcRef());
            vars.put("scene", sample.getScene());
            vars.put("question_type", sample.getQuestionType());
            vars.put("state_text", sample.getStateText() == null ? "（无）" : sample.getStateText());
            vars.put("question", sample.getQuestionJson() == null ? "（无）" : sample.getQuestionJson());
            vars.put("nanojev_answer", sample.getNanojevResponseJson() == null
                    ? "（NanoJev 调用失败）" : sample.getNanojevResponseJson());
            String user = promptTemplateService.render(0L, projectId,
                    PromptTemplateService.CODE_NANOJEV_AB_JUDGE, vars);
            String raw = judgeScorer.call(sys, user);
            JudgeScoreParse.JudgeResult parsed = JudgeScoreParse.parse(raw);
            if (parsed == null) {
                sample.setJudgeScoresJson("{\"error\":\"judge 输出不可解析\",\"raw\":\""
                        + truncate(raw == null ? "" : raw, 800).replace("\"", "'") + "\"}");
            } else {
                ObjectNode node = MAPPER.createObjectNode();
                node.set("scores", MAPPER.valueToTree(parsed.scores()));
                node.set("reasons", MAPPER.valueToTree(parsed.reasons()));
                node.put("summary", truncate(parsed.summary(), 1000));
                node.put("total", parsed.total());
                sample.setJudgeScoresJson(node.toString());
            }
        } catch (Exception e) {
            sample.setJudgeScoresJson("{\"error\":\"评分失败：" + truncate(e.getMessage(), 500) + "\"}");
            log.warn("[NanoJev A/B] 样本 {} judge 评分失败: {}", sample.getId(), e.getMessage());
        }
        sampleRepository.save(sample);
    }

    // ==================== 报告聚合（轻量聚合器；统计/阈值标定逻辑集中在 NanoJevAbReportService） ====================

    /** 批次汇总/报告聚合（一致率/分布/延迟/按场景分桶阈值标定）。 */
    private Map<String, Object> aggregateReport(Long runId, ActorNanoJevAbRun run) {
        List<ActorNanoJevAbSample> samples = sampleRepository.findByAbRunIdOrderBySeqAsc(runId);
        // 统计维度：总体 + 按场景
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", run.getSampleCount());
        summary.put("comparedCount", run.getComparedCount());
        summary.put("matchedCount", run.getMatchedCount());
        summary.put("qwenModel", run.getQwenModel());
        summary.put("judgeModel", run.getJudgeModel());
        summary.put("templateVersion", run.getTemplateVersion());
        summary.put("scenes", sceneStats(samples));
        summary.put("latency", latencyTable(samples));
        summary.put("thresholdCalibration", thresholdCalibration(samples));
        ObjectNode report = MAPPER.createObjectNode();
        report.put("abRunId", runId);
        report.set("summary", MAPPER.valueToTree(summary));
        ArrayNode arr = MAPPER.createArrayNode();
        for (ActorNanoJevAbSample s : samples) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("seq", s.getSeq());
            n.put("scene", s.getScene());
            n.put("questionType", s.getQuestionType());
            n.put("npcRef", s.getNpcRef());
            n.put("npcName", s.getNpcName());
            n.put("sourceScene", s.getSourceScene());
            n.put("match", s.getMatchFlag() == 1);
            putJson(n, "question", s.getQuestionJson());
            putJson(n, "nanojev", s.getNanojevResponseJson());
            putJson(n, "qwen", s.getQwenResponseJson());
            putJson(n, "judge", s.getJudgeScoresJson());
            arr.add(n);
        }
        report.set("samples", arr);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("report", report.toString());
        return out;
    }

    /** 按场景统计：样本数/对照数/一致数/一致率/分布熵均值/top-1 概率均值。 */
    private Map<String, Object> sceneStats(List<ActorNanoJevAbSample> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String scene : List.of(AbSample.SCENE_EMOTION_EVAL, AbSample.SCENE_CROWD_DECISION,
                AbSample.SCENE_SOCIAL_WILLINGNESS)) {
            List<ActorNanoJevAbSample> inScene = samples.stream()
                    .filter(s -> scene.equals(s.getScene())).toList();
            if (inScene.isEmpty()) {
                continue;
            }
            long compared = inScene.stream()
                    .filter(s -> "success".equals(s.getNanojevStatus()) && "success".equals(s.getQwenStatus()))
                    .count();
            long matched = inScene.stream().filter(s -> s.getMatchFlag() == 1).count();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("sampleCount", inScene.size());
            n.put("comparedCount", compared);
            n.put("matchedCount", matched);
            n.put("matchRate", compared == 0 ? 0 : Math.round(matched * 1000.0 / compared) / 10.0);
            n.put("entropyAvg", Math.round(entropyAvg(inScene) * 1000.0) / 1000.0);
            n.put("top1ProbAvg", Math.round(top1ProbAvg(inScene) * 1000.0) / 1000.0);
            out.put(scene, n);
        }
        return out;
    }

    /** 分布熵均值（样本 nanojev.response_json.entropy）。 */
    private static double entropyAvg(List<ActorNanoJevAbSample> samples) {
        double sum = 0;
        int n = 0;
        for (ActorNanoJevAbSample s : samples) {
            JsonNode node = readJson(s.getNanojevResponseJson());
            if (node != null && node.has("entropy")) {
                sum += node.path("entropy").asDouble(0);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** top-1 概率均值（分布最大概率；未存原始分布时回退 NaN→0）。 */
    private static double top1ProbAvg(List<ActorNanoJevAbSample> samples) {
        double sum = 0;
        int n = 0;
        for (ActorNanoJevAbSample s : samples) {
            JsonNode node = readJson(s.getNanojevResponseJson());
            JsonNode probs = node == null ? null : node.get("probabilities");
            if (probs != null && probs.isObject()) {
                double max = 0;
                for (JsonNode v : probs) {
                    max = Math.max(max, v.asDouble(0));
                }
                sum += max;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 延迟表：NanoJev / Qwen / judge 的 avg/max。 */
    private Map<String, Object> latencyTable(List<ActorNanoJevAbSample> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Long> nanojev = new ArrayList<>();
        List<Long> qwen = new ArrayList<>();
        for (ActorNanoJevAbSample s : samples) {
            if (s.getNanojevLatencyMs() != null) nanojev.add(s.getNanojevLatencyMs());
            if (s.getQwenLatencyMs() != null) qwen.add(s.getQwenLatencyMs());
        }
        out.put("nanojev", latencyOf(nanojev));
        out.put("qwen", latencyOf(qwen));
        return out;
    }

    private static Map<String, Object> latencyOf(List<Long> ms) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", ms.size());
        out.put("avgMs", ms.isEmpty() ? 0 : Math.round(ms.stream().mapToLong(Long::longValue).average().orElse(0)));
        out.put("maxMs", ms.isEmpty() ? 0 : ms.stream().mapToLong(Long::longValue).max().orElse(0));
        List<Long> sorted = ms.stream().sorted().toList();
        out.put("p50Ms", percentile(sorted, 0.5));
        out.put("p90Ms", percentile(sorted, 0.9));
        return out;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /**
     * 按场景分桶的置信度升级阈值标定。
     * <p>方法：每个场景内，把样本按 NanoJev top-1 概率分桶（&lt;0.4 / 0.4~0.5 / 0.5~0.6 /
     * 0.6~0.7 / ≥0.7）与熵分桶（&lt;0.5 / 0.5~1.0 / 1.0~1.5 / ≥1.5），统计各桶一致率——
     * 找到「一致率 ≥ 参考线（默认 80%）」的最低 top-1 概率下界 / 最高熵上界，作为该场景
     * 置信度升级建议阈值（top-1 概率低于阈值或熵高于阈值 → 分布不尖锐 → 升 Qwen）。</p>
     *
     * @return 每场景：{top1Buckets:{桶:一致率}, entropyBuckets:{桶:一致率}, suggested:{top1Prob, entropy}}
     */
    static Map<String, Object> thresholdCalibration(List<ActorNanoJevAbSample> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        double line = 0.8; // 参考线
        for (String scene : List.of(AbSample.SCENE_EMOTION_EVAL, AbSample.SCENE_CROWD_DECISION,
                AbSample.SCENE_SOCIAL_WILLINGNESS)) {
            List<ActorNanoJevAbSample> inScene = samples.stream()
                    .filter(s -> scene.equals(s.getScene()) && s.getMatchFlag() != null).toList();
            if (inScene.isEmpty()) {
                continue;
            }
            ObjectNode n = MAPPER.createObjectNode();
            n.set("top1Buckets", MAPPER.valueToTree(bucketStats(inScene, "top1")));
            n.set("entropyBuckets", MAPPER.valueToTree(bucketStats(inScene, "entropy")));
            // 建议阈值：满足一致率 ≥ line 的最宽泛触发条件（top-1 下界最高档 → 升级最保守）
            ObjectNode suggested = MAPPER.createObjectNode();
            suggested.put("top1Prob", suggestedTop1(inScene, line));
            suggested.put("entropy", suggestedEntropy(inScene, line));
            suggested.put("referenceLine", line);
            n.set("suggested", suggested);
            out.put(scene, n);
        }
        return out;
    }

    /** 按维度分桶的一致率统计（桶 → {count, matchRate}）。 */
    private static Map<String, Object> bucketStats(List<ActorNanoJevAbSample> samples, String dim) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> buckets = dim.equals("top1")
                ? List.of("<0.4", "0.4-0.5", "0.5-0.6", "0.6-0.7", ">=0.7")
                : List.of("<0.5", "0.5-1.0", "1.0-1.5", ">=1.5");
        for (String b : buckets) {
            List<ActorNanoJevAbSample> inBucket = samples.stream()
                    .filter(s -> inBucket(s, dim, b)).toList();
            if (inBucket.isEmpty()) {
                continue;
            }
            long matched = inBucket.stream().filter(s -> s.getMatchFlag() == 1).count();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("count", inBucket.size());
            n.put("matchRate", Math.round(matched * 1000.0 / inBucket.size()) / 10.0);
            out.put(b, n);
        }
        return out;
    }

    /** 样本是否落在某桶（top1/entropy 维度）。 */
    private static boolean inBucket(ActorNanoJevAbSample s, String dim, String bucket) {
        return inBucketValue(s.getNanojevResponseJson(), dim, bucket);
    }

    /**
     * 分桶判断（纯静态可测）：按 nanojev 响应 JSON 的 top-1 概率 / 熵分桶。
     *
     * @param nanojevJson NanoJev 响应 JSON（probabilities/entropy 字段）
     *
     * @return 是否落在该桶（数据不可解析返回 false）
     */
    public static boolean inBucketValue(String nanojevJson, String dim, String bucket) {
        double v;
        JsonNode node = readJson(nanojevJson);
        if (dim.equals("top1")) {
            JsonNode probs = node == null ? null : node.get("probabilities");
            if (probs == null || !probs.isObject()) {
                return false;
            }
            double max = 0;
            for (JsonNode p : probs) {
                max = Math.max(max, p.asDouble(0));
            }
            v = max;
        } else {
            v = node == null ? 0 : node.path("entropy").asDouble(0);
        }
        return switch (bucket) {
            case "<0.4" -> v < 0.4;
            case "0.4-0.5" -> v >= 0.4 && v < 0.5;
            case "0.5-0.6" -> v >= 0.5 && v < 0.6;
            case "0.6-0.7" -> v >= 0.6 && v < 0.7;
            case ">=0.7" -> v >= 0.7;
            case "<0.5" -> v < 0.5;
            case "0.5-1.0" -> v >= 0.5 && v < 1.0;
            case "1.0-1.5" -> v >= 1.0 && v < 1.5;
            case ">=1.5" -> v >= 1.5;
            default -> false;
        };
    }

    /** 建议 top-1 阈值：一致率 ≥ line 的最低桶下界（无达标桶返回 0=无可用阈值）。 */
    private static double suggestedTop1(List<ActorNanoJevAbSample> samples, double line) {
        double[] bounds = {0.4, 0.5, 0.6, 0.7};
        String[] buckets = {"<0.4", "0.4-0.5", "0.5-0.6", "0.6-0.7", ">=0.7"};
        for (int i = 0; i < buckets.length; i++) {
            final String bucket = buckets[i];
            List<ActorNanoJevAbSample> inBucket = samples.stream()
                    .filter(s -> inBucket(s, "top1", bucket)).toList();
            if (inBucket.isEmpty()) {
                continue;
            }
            long matched = inBucket.stream().filter(s -> s.getMatchFlag() == 1).count();
            if (matched * 1.0 / inBucket.size() >= line) {
                return i == 0 ? 0.4 : bounds[i - 1];
            }
        }
        return 0;
    }

    /** 建议熵阈值：一致率 ≥ line 的最高熵上界（无达标桶返回 99=不限制）。 */
    private static double suggestedEntropy(List<ActorNanoJevAbSample> samples, double line) {
        String[] buckets = {"<0.5", "0.5-1.0", "1.0-1.5", ">=1.5"};
        for (int i = buckets.length - 1; i >= 0; i--) {
            final String bucket = buckets[i];
            List<ActorNanoJevAbSample> inBucket = samples.stream()
                    .filter(s -> inBucket(s, "entropy", bucket)).toList();
            if (inBucket.isEmpty()) {
                continue;
            }
            long matched = inBucket.stream().filter(s -> s.getMatchFlag() == 1).count();
            if (matched * 1.0 / inBucket.size() >= line) {
                return switch (i) {
                    case 0 -> 0.5;
                    case 1 -> 1.0;
                    case 2 -> 1.5;
                    default -> 99;
                };
            }
        }
        return 99;
    }

    // ==================== 内部工具 ====================

    /** 虚拟线程执行器（异步批跑；单次启动即用，无常驻池）。 */
    private ExecutorService executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Qwen 模型名（trace 侧取实际模型不可得时用占位）。 */
    private String qwenModelName() {
        return "qwen3.6-35b（chat provider）";
    }

    /** npcRef → 数字 ID（npc:{id}；非法 null）。 */
    private static Long npcId(String npcRef) {
        if (npcRef == null || !npcRef.contains(":")) {
            return null;
        }
        try {
            return Long.parseLong(npcRef.substring(npcRef.indexOf(':') + 1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 题面快照 JSON（instructions/criteria/scoreCriteria）。 */
    private static String questionSnapshot(NanoJevQuestion q) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", q.type());
        n.put("instructions", q.instructions());
        if (q.criteria() != null) {
            n.set("criteria", MAPPER.valueToTree(q.criteria()));
        }
        if (q.scoreCriteria() != null) {
            n.set("scoreCriteria", MAPPER.valueToTree(q.scoreCriteria()));
        }
        return n.toString();
    }

    /** state 文本上限（配置优先，默认 3000）。 */
    private int stateMaxChars() {
        return 3000;
    }

    /** 读 JSON（非法 null）。 */
    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
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
        }
    }

    /** Map → JSON 字符串（空 Map → "{}"）。 */
    static String toJson(Object o) {
        try {
            return o == null ? "{}" : MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
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
