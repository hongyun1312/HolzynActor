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

package com.holzyn.actor.domain.evaluation.controller;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.ai.spring.GatewayConcurrencyLimiter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.evaluation.BehaviorJudgeService;
import com.holzyn.actor.domain.evaluation.TestRunService;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalRun;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalRunRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalSampleRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalScoreRepository;
import com.holzyn.actor.domain.evaluation.vo.EvalRunVO;
import com.holzyn.actor.domain.evaluation.vo.EvalSampleVO;
import com.holzyn.actor.domain.evaluation.vo.EvalScoreVO;
import com.holzyn.actor.domain.evaluation.vo.TestRunEventVO;
import com.holzyn.actor.domain.evaluation.vo.TestRunVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 专门测试接口控制器。
 * <p>统一前缀 {@code /api/worlds/{projectId}/test}，五能力：</p>
 * <ul>
 * phase_fastforward/phase_intensity）；</li>
 * <li><b>② 状态聚合</b>：GET /state（引擎推进/群聊活跃/预算/用量归因四块，时间窗可配）；</li>
 * <li><b>④ 评估批跑</b>：POST /eval-runs（LLM-as-Judge 样本评分，DB 采样 + JSONL 导入双通道）
 * + GET /eval-runs/{id}（批次报告：汇总 + 逐样本逐维评分）；</li>
 * <li><b>⑤ test_run_log</b>：POST /runs 创建 → command 时间线（命令/干预/快照）→ finish/abort →
 * GET /runs/{id} 完整回放；<b>test.enabled 开关</b>（默认 false，关闭时全部 403）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/worlds/{projectId}/test")
@RequiredArgsConstructor
public class TestController {

    private final TestRunService testRunService;
    private final BehaviorJudgeService behaviorJudgeService;
    private final ActorEvalRunRepository evalRunRepository;
    private final ActorEvalSampleRepository evalSampleRepository;
    private final ActorEvalScoreRepository evalScoreRepository;
    private final AiSpringChatGateway aiSpringChatGateway;
    private final GatewayConcurrencyLimiter gatewayConcurrencyLimiter;

    // ==================== ⑤ test_run_log（创建/命令/收尾/回放） ====================

    /**
     * 创建 test run。入参 {name?, purpose?}。
     */
    @PostMapping("/runs")
    public R<TestRunVO> createRun(@PathVariable Long projectId,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(testRunService.createRun(projectId, str(body, "name"), str(body, "purpose")));
    }

    /**
     * 执行命令（① 阶段控制 / ③ 干预驱动 / eval_ref；自动落时间线 + 状态快照）。
     * 入参 {action 必填, params?}。
     */
    @PostMapping("/runs/{runId}/command")
    public R<TestRunEventVO> command(@PathVariable Long projectId, @PathVariable Long runId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        String action = str(body, "action");
        Object paramsObj = body == null ? null : body.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = paramsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        return R.ok(testRunService.executeCommand(projectId, runId, action, params));
    }

    /**
     * 收尾 test run（⑤：status=finished + 汇总 JSON）。
     */
    @PostMapping("/runs/{runId}/finish")
    public R<TestRunVO> finishRun(@PathVariable Long projectId, @PathVariable Long runId) {
        return R.ok(testRunService.finishRun(projectId, runId));
    }

    /**
     * 中止 test run。
     */
    @PostMapping("/runs/{runId}/abort")
    public R<TestRunVO> abortRun(@PathVariable Long projectId, @PathVariable Long runId) {
        return R.ok(testRunService.abortRun(projectId, runId));
    }

    /**
     * test run 列表。
     */
    @GetMapping("/runs")
    public R<List<TestRunVO>> listRuns(@PathVariable Long projectId) {
        return R.ok(testRunService.listRuns(projectId));
    }

    /**
     * test run 完整回放（⑤：run + 命令/干预/快照时间线）。
     */
    @GetMapping("/runs/{runId}")
    public R<Map<String, Object>> runDetail(@PathVariable Long projectId, @PathVariable Long runId) {
        return R.ok(testRunService.runDetail(projectId, runId));
    }

    // ==================== ② 状态聚合（四块，时间窗可配） ====================

    /**
     * 状态聚合：引擎推进 / 群聊活跃 / 预算 / 用量归因。
     *
     * @param projectId 项目 ID
     *
     * @param windowGameMinutes 游戏时间窗（分钟，默认 1440=近 1 游戏日）
     */
    @GetMapping("/state")
    public R<Map<String, Object>> state(@PathVariable Long projectId,
                                        @RequestParam(name = "windowGameMinutes", required = false)
                                        Integer windowGameMinutes) {
        return R.ok(testRunService.state(projectId, windowGameMinutes));
    }

    // ==================== ④ 评估批跑（LLM-as-Judge 样本评分） ====================

    /**
     * 启动评估批跑：创建批次 → 异步执行 → 立即返回批次。
     * 入参 {source: db|jsonl, sampleCount?, windowGameMinutes?, npcRef?, jsonlPath?（jsonl 必填）, runId?（关联 test run）}。
     */
    @PostMapping("/eval-runs")
    public R<EvalRunVO> startEvalRun(@PathVariable Long projectId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String source = str(body, "source");
        Integer sampleCount = intOf(body, "sampleCount");
        Integer windowGameMinutes = intOf(body, "windowGameMinutes");
        String npcRef = str(body, "npcRef");
        String jsonlPath = str(body, "jsonlPath");
        Long runId = longOf(body, "runId");
        ActorEvalRun run = behaviorJudgeService.startBatch(projectId,
                new BehaviorJudgeService.EvalRunRequest(source, sampleCount, windowGameMinutes, npcRef, jsonlPath));
        // 关联 test run（⑤ eval_ref 时间线）
        if (runId != null) {
            try {
                testRunService.executeCommand(projectId, runId, "eval_ref",
                        Map.of("evalRunId", run.getId()));
            } catch (Exception e) {
                log.warn("[测试接口] 评估批次关联 test run 失败（不影响批跑）: run={} : {}", run.getId(), e.getMessage());
            }
        }
        return R.ok(EvalRunVO.of(run));
    }

    /**
     * 评估批次列表。
     */
    @GetMapping("/eval-runs")
    public R<List<EvalRunVO>> listEvalRuns(@PathVariable Long projectId) {
        testRunService.requireTestEnabled();
        return R.ok(evalRunRepository.findTop20ByProjectIdOrderByIdDesc(projectId).stream()
                .map(EvalRunVO::of).toList());
    }

    /**
     * 评估批次详情（④：批次汇总 + 逐样本明细 + 逐维评分报告）。
     */
    @GetMapping("/eval-runs/{evalRunId}")
    public R<Map<String, Object>> evalRunDetail(@PathVariable Long projectId, @PathVariable Long evalRunId) {
        testRunService.requireTestEnabled();
        ActorEvalRun run = evalRunRepository.findById(evalRunId)
                .filter(r -> projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new BizException(404, "评估批次不存在：" + evalRunId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", EvalRunVO.of(run));
        out.put("samples", evalSampleRepository.findByEvalRunIdOrderBySeqAsc(evalRunId).stream()
                .map(EvalSampleVO::of).toList());
        out.put("scores", evalScoreRepository.findByEvalRunIdOrderByIdAsc(evalRunId).stream()
                .map(EvalScoreVO::of).toList());
        return R.ok(out);
    }

    // ==================== ⑥ 网关并发限流冒烟（_07 网关并发错峰观察项） ====================

    /**
     * 网关并发限流冒烟：并发扇出 N 路轻量 chat 调用，实证「排队而非 120s 超时」。
     * 入参 {count 必填[1~200], message?, userId 必填, providerId?, projectId?}。
     * 返回逐路 {index, success, durationMs, content/error} + 限流器观测（maxInFlight ≤ 上限、
     * totalWaits&gt;0、waitTimeouts=0 即 PASS）。test.enabled 门禁（关闭 403）。
     */
    @PostMapping("/ai/gateway-concurrency-smoke")
    public R<Map<String, Object>> gatewayConcurrencySmoke(@PathVariable Long projectId,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        testRunService.requireTestEnabled();
        int count = body == null || body.get("count") == null ? 20 : intOf(body, "count");
        if (count < 1) {
            throw new BizException(400, "count 必须 ≥ 1");
        }
        int capped = Math.min(count, 200);
        String message = str(body, "message");
        if (message == null) {
            message = "请只回复一个字：好";
        }
        Long userId = longOf(body, "userId");
        Long providerId = longOf(body, "providerId");
        if (userId == null) {
            throw new BizException(400, "userId 必填（发起调用的账号 ID）");
        }
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("user", message)), null, null, false);
        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < capped; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        AiChatResult r = aiSpringChatGateway.chatCompletion(userId, projectId, providerId, request);
                        results.add(Map.of("index", idx, "success", true,
                                "durationMs", System.currentTimeMillis() - start, "content", r.content()));
                    } catch (Exception e) {
                        results.add(Map.of("index", idx, "success", false,
                                "durationMs", System.currentTimeMillis() - start,
                                "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[网关并发冒烟] 等待单路超时/中断: {}", e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        results.sort(Comparator.comparingInt(m -> (Integer) m.get("index")));
        long ok = results.stream().filter(m -> (Boolean) m.get("success")).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", capped);
        out.put("success", ok);
        out.put("fail", results.size() - ok);
        out.put("results", results);
        out.put("limiterStats", gatewayConcurrencyLimiter == null ? null : gatewayConcurrencyLimiter.stats());
        return R.ok(out);
    }

    // ==================== 内部工具 ====================

    /** Map 取字符串（null 安全）。 */
    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String s = String.valueOf(body.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    /** Map 取整数（null 安全）。 */
    private Integer intOf(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Map 取 Long（null 安全）。 */
    private Long longOf(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
