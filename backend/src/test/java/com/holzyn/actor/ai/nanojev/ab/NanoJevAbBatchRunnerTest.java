package com.holzyn.actor.ai.nanojev.ab;

import com.holzyn.actor.ai.nanojev.NanoJevEvaluateClient;
import com.holzyn.actor.ai.nanojev.NanoJevEvaluateResponse;
import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.nanojev.ab.entity.ActorNanoJevAbRun;
import com.holzyn.actor.ai.nanojev.ab.entity.ActorNanoJevAbSample;
import com.holzyn.actor.ai.nanojev.ab.repository.ActorNanoJevAbRunRepository;
import com.holzyn.actor.ai.nanojev.ab.repository.ActorNanoJevAbSampleRepository;
import com.holzyn.actor.domain.evaluation.JudgeScorer;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * N2 A/B 评估单测：NanoJevAbBatchRunner 批跑编排（样本落库 → NanoJev+Qwen 双轨对照 →
 * judge 评分 → 汇总报告）。
 *
 * <p>测试纪律（V1.3 第六层）：仓库全 mock + 内存收集；NanoJev 客户端 mock（返回脚本化
 * 响应）；Qwen 重问 mock；judge 用确定性 Fake（并发安全）；零网络零真实 AI。</p>
 *
 * <p>所属模块：ai/nanojev/ab（N 轨道 N2）</p>
 */
class NanoJevAbBatchRunnerTest {

    private ActorNanoJevAbRunRepository runRepository;
    private ActorNanoJevAbSampleRepository sampleRepository;
    private NanoJevAbSampleFactory sampleFactory;
    private NanoJevEvaluateClient nanoJevEvaluateClient;
    private QwenBaselineClient qwenBaselineClient;
    private NanoJevAbProperties properties;
    private JudgeScorer judgeScorer;
    private PromptTemplateService promptTemplateService;
    private NanoJevAbBatchRunner runner;

    /** judge 输出常量（四维固定 JSON）。 */
    private static final String JUDGE_OK = "{\"scores\":{\"consistency\":5,\"reasonability\":4,"
            + "\"context_usage\":4,\"naturalness\":5},"
            + "\"reasons\":{\"consistency\":\"符合性格\"},\"summary\":\"判定合理\"}";

    /** 确定性 JudgeScorer fake（并发安全）。 */
    private static class FakeJudgeScorer implements JudgeScorer {
        @Override
        public String call(String systemPrompt, String userPrompt) {
            return JUDGE_OK;
        }

        @Override
        public String modelName() {
            return "deepseek-v4-flash";
        }
    }

    /** 确定性 Qwen 重问 fake（返回脚本化选中项）。 */
    private static class FakeQwenBaselineClient extends QwenBaselineClient {
        private final Object answer;

        FakeQwenBaselineClient(Object answer) {
            super(null, null);
            this.answer = answer;
        }

        @Override
        public QwenAnswerParse.QwenAnswer ask(Long userId, Long projectId, Long characterId,
                                              String npcRef, String npcName, String stateText,
                                              NanoJevQuestion question) {
            return new QwenAnswerParse.QwenAnswer(answer, "{\"answer\":" + answer + "}");
        }
    }

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(ActorNanoJevAbRunRepository.class);
        sampleRepository = Mockito.mock(ActorNanoJevAbSampleRepository.class);
        sampleFactory = Mockito.mock(NanoJevAbSampleFactory.class);
        nanoJevEvaluateClient = Mockito.mock(NanoJevEvaluateClient.class);
        properties = new NanoJevAbProperties();
        properties.setEnabled(true);
        properties.setConcurrency(2);
        judgeScorer = new FakeJudgeScorer();
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        runner = new NanoJevAbBatchRunner(runRepository, sampleRepository, sampleFactory,
                nanoJevEvaluateClient, new FakeQwenBaselineClient(2), properties,
                judgeScorer, promptTemplateService);
    }

    /** 完整批跑：2 条情绪样本（score）双轨对照一致 → done + matchedCount 落库 + 报告 JSON。 */
    @Test
    void runBatch_comparesAndAggregates() throws Exception {
        ActorNanoJevAbRun run = new ActorNanoJevAbRun();
        run.setId(1L);
        run.setProjectId(1L);
        run.setSceneScope("all");
        run.setStatus("running");
        run.setSampleCount(0);
        run.setComparedCount(0);
        run.setMatchedCount(0);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(runRepository.save(any(ActorNanoJevAbRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sampleRepository.save(any(ActorNanoJevAbSample.class))).thenAnswer(inv -> {
            ActorNanoJevAbSample s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(s.getSeq().longValue());
                savedSamples.add(s); // 仅首次落库收集（compareOne/judgeOne 的更新 save 不重复收集）
            }
            return s;
        });
        // 样本构造：2 条情绪样本（score 档位题，NanoJev level=2，Qwen answer=2 → 一致）
        AbSample ab1 = new AbSample(AbSample.SCENE_EMOTION_EVAL, NanoJevQuestion.TYPE_SCORE,
                "npc:1", "孙二娘", "青禾村·午后。孙二娘在洗衣，心情不错。",
                NanoJevAbTemplates.emotionQuestion(), "npc_emotion_eval", "t1");
        AbSample ab2 = new AbSample(AbSample.SCENE_EMOTION_EVAL, NanoJevQuestion.TYPE_SCORE,
                "npc:2", "雷铁根", "青禾村·傍晚。雷铁根收摊回家，平淡。",
                NanoJevAbTemplates.emotionQuestion(), "npc_emotion_eval", "t2");
        when(sampleFactory.buildSamples(1L, "all")).thenReturn(List.of(ab1, ab2));
        // NanoJev 脚本化响应：按 state id 区分——s1（孙二娘）level=2、s2（雷铁根）level=0
        // （s1 与 Qwen=2 一致、s2 与 Qwen=2 不一致 → matchedCount=1；按内容匹配避免并发顺序漂移）
        when(nanoJevEvaluateClient.evaluateOnce(any(), anyLong(), any(), anyString(), anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<com.holzyn.actor.ai.nanojev.NanoJevState> states = inv.getArgument(4);
                    String sid = states.get(0).id();
                    if ("s1".equals(sid)) {
                        return responseFor("s1", "score", Map.of("0", 0.1, "1", 0.2, "2", 0.7), 2);
                    }
                    return responseFor("s2", "score", Map.of("0", 0.6, "1", 0.3, "2", 0.1), 0);
                });
        // 样本落库后读取（模拟 DB 行返回）
        when(sampleRepository.findByAbRunIdOrderBySeqAsc(1L)).thenAnswer(inv -> savedSamples);
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("sys");
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("user");

        runner.runBatch(1L, "all");

        assertEquals("done", run.getStatus());
        assertEquals(2, run.getSampleCount());
        assertEquals(2, run.getComparedCount());
        assertEquals(1, run.getMatchedCount());
        // 汇总 JSON：含场景统计/延迟/阈值标定
        JsonNode summary = new ObjectMapper().readTree(run.getSummaryJson());
        assertEquals(2, summary.path("sampleCount").asInt());
        assertTrue(summary.path("scenes").has("emotion_eval"));
        assertTrue(summary.path("latency").has("nanojev"));
        assertTrue(summary.path("thresholdCalibration").has("emotion_eval"));
        JsonNode report = new ObjectMapper().readTree(run.getReportJson());
        assertEquals(2, report.path("samples").size());
    }

    /** 无可用样本 → done + 空报告（不伪造）。 */
    @Test
    void runBatch_emptySamples() throws Exception {
        ActorNanoJevAbRun run = new ActorNanoJevAbRun();
        run.setId(2L);
        run.setProjectId(1L);
        run.setSceneScope("all");
        run.setStatus("running");
        when(runRepository.findById(2L)).thenReturn(Optional.of(run));
        when(runRepository.save(any(ActorNanoJevAbRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sampleFactory.buildSamples(1L, "all")).thenReturn(List.of());

        runner.runBatch(2L, "all");

        assertEquals("done", run.getStatus());
        assertEquals(0, run.getSampleCount());
        assertNotNull(run.getSummaryJson());
        assertTrue(run.getSummaryJson().contains("无可用真实情境样本"));
    }

    /** 未启用 → startRun 400（BizException）。 */
    @Test
    void startRun_disabledRejects() {
        properties.setEnabled(false);
        try {
            runner.startRun(1L, new NanoJevAbBatchRunner.AbRunRequest("all"));
            assertTrue(false, "应抛出 400 未启用异常");
        } catch (com.holzyn.actor.common.BizException e) {
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("未启用"));
        }
    }

    // ==================== 内部工具 ====================

    /** 内存收集已保存样本（模拟 DB 行，save 回调填充）。 */
    private final List<ActorNanoJevAbSample> savedSamples = new ArrayList<>();

    /** 构造 NanoJev 响应（score 档位；合法 JSON 概率表）。 */
    private static NanoJevEvaluateResponse responseFor(String stateId, String type,
                                                       Map<String, Double> probs, int level) {
        StringBuilder pb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            if (i++ > 0) pb.append(",");
            pb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        pb.append("}");
        String json = "{\"execution\":{\"states\":1,\"questions\":1,\"candidate_paths\":3,"
                + "\"forward_passes\":1,\"autoregressive_decode_steps\":0,\"device\":\"cuda:0\"},"
                + "\"states\":[{\"id\":\"" + stateId + "\",\"answers\":{\"q1\":{\"type\":\"" + type
                + "\",\"probabilities\":" + pb
                + ",\"level\":" + level + "}}}]}";
        return NanoJevEvaluateResponse.parse(json);
    }
}
