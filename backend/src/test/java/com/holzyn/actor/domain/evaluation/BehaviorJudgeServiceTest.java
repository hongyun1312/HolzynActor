package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.domain.evaluation.entity.ActorEvalRun;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalSample;
import com.holzyn.actor.domain.evaluation.entity.ActorEvalScore;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalRunRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalSampleRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorEvalScoreRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BehaviorJudgeService 单元测试（O2.5 P2-1 评估批跑编排：采样→评分→落库→汇总报告）。
 */
class BehaviorJudgeServiceTest {

    private ActorEvalRunRepository evalRunRepository;
    private ActorEvalSampleRepository sampleRepository;
    private ActorEvalScoreRepository scoreRepository;
    private DecisionSampleSampler sampler;
    private JudgeScorer judgeScorer;
    private PromptTemplateService promptTemplateService;
    private EvaluationProperties properties;
    private BehaviorJudgeService service;

    /** judge 输出常量（成功路径固定 JSON）。 */
    private static final String JUDGE_OK = "{\"scores\":{\"consistency\":5,\"reasonability\":4,\"context_usage\":4,"
            + "\"foresight\":3,\"naturalness\":5},"
            + "\"reasons\":{\"consistency\":\"符合性格\"},\"summary\":\"很好\"}";

    /**
     * 确定性 JudgeScorer fake（替代 Mockito mock——原 mock 并发调用偶发丢桩返回 null，
     * 导致批跑并发评分时一路走 catch（NPE）→ scoredCount 少 1，测试偶发红；fake 并发安全零偶发）。
     */
    private static class FakeJudgeScorer implements JudgeScorer {
        private final String model;
        private final String result;
        private final RuntimeException failure;

        FakeJudgeScorer(String model, String result, RuntimeException failure) {
            this.model = model;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public String call(String systemPrompt, String userPrompt) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public String modelName() {
            return model;
        }
    }

    @BeforeEach
    void setUp() {
        evalRunRepository = Mockito.mock(ActorEvalRunRepository.class);
        sampleRepository = Mockito.mock(ActorEvalSampleRepository.class);
        scoreRepository = Mockito.mock(ActorEvalScoreRepository.class);
        sampler = Mockito.mock(DecisionSampleSampler.class);
        judgeScorer = new FakeJudgeScorer("deepseek-v4-flash", JUDGE_OK, null);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        properties = new EvaluationProperties();
        ReflectionTestUtils.setField(properties, "defaultSampleCount", 20);
        ReflectionTestUtils.setField(properties, "judgeConcurrency", 4);
        ReflectionTestUtils.setField(properties, "judgeMaxInputChars", 4000);
        ReflectionTestUtils.setField(properties, "judgeProviderLabel", "ark-coding-v3");
        ReflectionTestUtils.setField(properties, "judgeModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(properties, "rubricVersion", "v1");
        service = new BehaviorJudgeService(evalRunRepository, sampleRepository, scoreRepository,
                sampler, judgeScorer, promptTemplateService, properties);
    }

    /** 完整批跑：2 样本评分成功 → run done + scoredCount=2 + 汇总含五维统计与低分样本。 */
    @Test
    void runBatchScoresAndAggregates() throws Exception {
        ActorEvalRun run = run(1L);
        when(evalRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(evalRunRepository.save(any(ActorEvalRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sampleRepository.save(any(ActorEvalSample.class))).thenAnswer(inv -> {
            ActorEvalSample s = inv.getArgument(0);
            if (s.getId() == null) s.setId(s.getSeq().longValue());
            return s;
        });
        // 评分落库收集 + 批次汇总读取同一内存列表（模拟 DB 行）；judge 并发池同时落分，
        // 必须线程安全（裸 ArrayList 并发 add 会扩容竞态丢元素/抛异常，导致 scoredCount 偶发少计）
        List<ActorEvalScore> savedScores = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(scoreRepository.save(any(ActorEvalScore.class))).thenAnswer(inv -> {
            ActorEvalScore s = inv.getArgument(0);
            savedScores.add(s);
            return s;
        });
        when(scoreRepository.findByEvalRunIdOrderByIdAsc(1L)).thenAnswer(inv -> savedScores);
        when(sampler.sampleFromDb(anyLong(), any(), any(), any()))
                .thenReturn(List.of(sample("special:5", "沈砚秋", "action_gen", "刺激1", "决策1"),
                        sample("crowd:9", "王二", "dialog", "刺激2", "决策2")));
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("sys");
        when(promptTemplateService.render(anyLong(), anyLong(), any(), any())).thenReturn("user");

        service.runBatch(1L, new BehaviorJudgeService.EvalRunRequest("db", 5, 1440, null, null));

        assertEquals("done", run.getStatus());
        assertEquals(2, run.getScoredCount());
        assertEquals(2, run.getSampleCount());
        // 评分落库 ×2
        ArgumentCaptor<ActorEvalScore> captor = ArgumentCaptor.forClass(ActorEvalScore.class);
        verify(scoreRepository, Mockito.times(2)).save(captor.capture());
        assertEquals(42, captor.getAllValues().get(0).getTotalScore(), "(5+4+4+3+5)/5=4.2→42");
        // 汇总 JSON 含五维统计
        JsonNode summary = new ObjectMapper().readTree(run.getSummaryJson());
        assertEquals(2, summary.get("sampleCount").asInt());
        assertEquals(42.0, summary.get("totalAvg").asDouble(), 0.01);
        assertTrue(summary.has("dimensions"));
        assertTrue(summary.get("dimensions").has("consistency"));
        assertTrue(summary.get("dimensions").has("naturalness"));
    }

    /** judge 失败样本：不阻断批跑，score 行记录错误，scoredCount 保持 0。 */
    @Test
    void runBatchToleratesJudgeFailure() throws Exception {
        // 换用抛错 fake（judge 超时场景）
        service = new BehaviorJudgeService(evalRunRepository, sampleRepository, scoreRepository,
                sampler, new FakeJudgeScorer("deepseek-v4-flash", null, new RuntimeException("judge 超时")),
                promptTemplateService, properties);
        ActorEvalRun run = run(1L);
        when(evalRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(evalRunRepository.save(any(ActorEvalRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sampleRepository.save(any(ActorEvalSample.class))).thenAnswer(inv -> {
            ActorEvalSample s = inv.getArgument(0);
            if (s.getId() == null) s.setId(s.getSeq().longValue());
            return s;
        });
        when(scoreRepository.findByEvalRunIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(sampler.sampleFromDb(anyLong(), any(), any(), any()))
                .thenReturn(List.of(sample("special:5", "沈砚秋", "action_gen", "刺激", "决策")));
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("sys");
        when(promptTemplateService.render(anyLong(), anyLong(), any(), any())).thenReturn("user");

        service.runBatch(1L, new BehaviorJudgeService.EvalRunRequest("db", 5, 1440, null, null));

        assertEquals("done", run.getStatus());
        assertEquals(0, run.getScoredCount());
        ArgumentCaptor<ActorEvalScore> captor = ArgumentCaptor.forClass(ActorEvalScore.class);
        verify(scoreRepository).save(captor.capture());
        assertTrue(captor.getValue().getJudgeRawJson().contains("评分失败"));
    }

    /** 无样本批次：直接 done，汇总提示无可用样本。 */
    @Test
    void runBatchWithNoSamplesFinishesDone() throws Exception {
        ActorEvalRun run = run(1L);
        when(evalRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(evalRunRepository.save(any(ActorEvalRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sampler.sampleFromDb(anyLong(), any(), any(), any())).thenReturn(List.of());
        service.runBatch(1L, new BehaviorJudgeService.EvalRunRequest("db", 20, 1440, null, null));
        assertEquals("done", run.getStatus());
        assertEquals(0, run.getSampleCount());
        assertTrue(run.getSummaryJson().contains("无可用决策样本"));
    }

    /** startBatch：jsonl 缺路径 400；db 创建 pending 批次并异步执行。 */
    @Test
    void startBatchValidatesAndCreates() {
        when(evalRunRepository.save(any(ActorEvalRun.class))).thenAnswer(inv -> {
            ActorEvalRun r = inv.getArgument(0);
            r.setId(7L);
            return r;
        });
        try {
            service.startBatch(1L, new BehaviorJudgeService.EvalRunRequest("jsonl", 5, 1440, null, null));
        } catch (com.holzyn.actor.common.BizException e) {
            assertEquals(400, e.getCode());
        }
        ActorEvalRun r = service.startBatch(1L, new BehaviorJudgeService.EvalRunRequest("db", 5, 1440, null, null));
        assertEquals("pending", r.getStatus());
        assertEquals("ark-coding-v3", r.getJudgeProvider());
        assertEquals("deepseek-v4-flash", r.getJudgeModel());
    }

    private ActorEvalRun run(Long id) {
        ActorEvalRun r = new ActorEvalRun();
        r.setId(id);
        r.setProjectId(1L);
        r.setSampleSource("db");
        r.setStatus("pending");
        return r;
    }

    private DecisionSample sample(String ref, String name, String scene, String stimulus, String response) {
        return new DecisionSample("db", ref, name, scene, "success", null, stimulus, "ctx", response, "t");
    }
}
