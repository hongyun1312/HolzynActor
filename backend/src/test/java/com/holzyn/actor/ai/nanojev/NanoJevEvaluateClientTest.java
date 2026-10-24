package com.holzyn.actor.ai.nanojev;

import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1 接入基建单测：NanoJevEvaluateClient（契约构造→HTTP→解析→记账→fail-closed 全链路）。
 *
 * <p>测试纪律（V1.3 第六层）：字段注入 + {@code ReflectionTestUtils.setField}，不改构造器；
 * HTTP 层注入内存 fake executor（零网络）；AI 不真实调用；分布级断言（argmax 项）而非点断言。</p>
 *
 * <p>所属模块：ai/nanojev（N 轨道 N1）</p>
 */
class NanoJevEvaluateClientTest {

    /** 官方样例响应（与 {@link NanoJevEvaluateResponseTest#OFFICIAL_SAMPLE} 同源）。 */
    private static final String SAMPLE_RESPONSE = """
            {"execution":{"device":"cuda:0","states":1,"questions":3,"candidate_paths":6,
              "forward_passes":1,"autoregressive_decode_steps":0,"network_model_calls":0,
              "persistent_model_load_count":1,"inference_call_index":1,"server_evaluation_seconds":0.0417},
             "states":[{"id":"s1","answers":{
               "action":{"type":"choice","probabilities":{"rest":0.145,"chat":0.376,"work":0.479},"choice":"work","value":"work"},
               "willing":{"type":"boolean","probabilities":{"false":0.194,"true":0.806},"p_true":0.806,"value":true},
               "mood":{"type":"score","probabilities":{"0":0.245,"1":0.307,"2":0.447},"score":1.20,"level":2}}}]}
            """;

    /** chat 静默回退形状（无 execution、有 choices）。 */
    private static final String CHAT_FALLBACK = """
            {"choices":[{"message":{"role":"assistant","content":"Hello!"}}],
             "model":"qwen3.6-35b-q4-un","usage":{"completion_tokens":8,"prompt_tokens":13}}
            """;

    /** 内存 fake executor：记录调用并返回脚本化结果。 */
    private static class FakeExecutor implements NanoJevHttpExecutor {
        final List<String> calls = new ArrayList<>();
        final List<HttpResult> results = new ArrayList<>();
        int failFirstN = 0; // 前 N 次抛异常（重试验证）

        @Override
        public HttpResult postJson(String url, String apiKey, String bodyJson, int connectSec, int readSec) {
            calls.add("POST " + url + " key=" + apiKey);
            if (calls.size() <= failFirstN) {
                throw new RuntimeException("模拟网络故障");
            }
            return results.isEmpty() ? new HttpResult(200, SAMPLE_RESPONSE, 42) : results.remove(0);
        }

        @Override
        public HttpResult getJson(String url, String apiKey, int connectSec, int readSec) {
            calls.add("GET " + url);
            return results.isEmpty() ? new HttpResult(200, SAMPLE_RESPONSE, 10) : results.remove(0);
        }
    }

    private NanoJevEvaluateClient client;
    private NanoJevProperties properties;
    private ModelApiService modelApiService;
    private AiCallTraceService aiCallTraceService;
    private AiCallTrace trace;
    private UsageLogService usageLogService;
    private CurrentUserProvider currentUserProvider;
    private FakeExecutor executor;
    private Consumer<AiCallTrace> savedSink;

    @BeforeEach
    void setUp() {
        savedSink = null;
        // 保存并隔离静态 SINK（防其他测试类残留 sink 干扰）
        try {
            java.lang.reflect.Field f = AiCallContext.class.getDeclaredField("SINK");
            f.setAccessible(true);
            savedSink = (Consumer<AiCallTrace>) f.get(null);
        } catch (Exception ignored) {
            // 反射失败（JDK 约束）则保持默认 null
        }
        AiCallContext.setSink(null);

        client = new NanoJevEvaluateClient();
        properties = new NanoJevProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://llm.mbfsr.com:14443/v1");
        properties.setMaxRetries(1);
        properties.setTimeoutConnectSeconds(10);
        properties.setTimeoutReadSeconds(30);
        properties.setSampling(new NanoJevProperties.Sampling());
        modelApiService = mock(ModelApiService.class);
        aiCallTraceService = mock(AiCallTraceService.class);
        trace = mock(AiCallTrace.class);
        usageLogService = mock(UsageLogService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        executor = new FakeExecutor();
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiCallTraceService.begin(1L)).thenReturn(trace);
        when(modelApiService.resolveProviderConfig(1L, 1L, null)).thenReturn(
                new ProviderConfig(67L, "https://llm.mbfsr.com:14443/v1", "test-key", "qwen3.6-35b-q4-un", false));

        ReflectionTestUtils.setField(client, "properties", properties);
        ReflectionTestUtils.setField(client, "modelApiService", modelApiService);
        ReflectionTestUtils.setField(client, "aiCallTraceService", aiCallTraceService);
        ReflectionTestUtils.setField(client, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(client, "currentUserProvider", currentUserProvider);
        ReflectionTestUtils.setField(client, "executor", executor);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(savedSink);
    }

    private static List<NanoJevState> sampleStates() {
        Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
        qs.put("action", NanoJevQuestion.choice("此刻最想做什么？",
                Map.of("rest", "歇一歇", "chat", "聊几句", "work", "继续干活")));
        qs.put("willing", NanoJevQuestion.bool("是否愿意停下攀谈？", "不愿意", "愿意"));
        qs.put("mood", NanoJevQuestion.score("此刻心情档位？", List.of("低落", "平常", "不错")));
        return List.of(NanoJevState.text("s1", "青禾村·午后。孙二娘在溪边洗衣。", qs));
    }

    @Test
    @DisplayName("成功链路：真实响应解析 + trace 记账 + usage_log 0 token + 静默回退防护通过")
    void evaluateSuccessRecordsTraceAndUsage() {
        List<NanoJevEvaluateResponse> result = client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates());
        assertNotNull(result, "成功链路必须返回响应");
        assertEquals(1, result.size());
        NanoJevEvaluateResponse resp = result.get(0);
        assertTrue(resp.isForwardProof(), "forward_passes=1 证明");
        assertFalse(resp.isChatFallback());
        assertEquals("work", resp.answerOf("s1", "action").choice(), "argmax=work");
        assertTrue(resp.answerOf("s1", "willing").pTrue() > 0.8);
        assertEquals(2, resp.answerOf("s1", "mood").level());

        // 请求 URL 与 Key 复用（N1 用户决策 A：Key 来自 chat provider）
        assertEquals(1, executor.calls.size());
        assertTrue(executor.calls.get(0).startsWith("POST https://llm.mbfsr.com:14443/v1/nanojev/evaluate key=test-key"),
                "实际请求：" + executor.calls.get(0));

        // trace 记账：model/baseUrl/paramsSnapshot/raw/0 token
        verify(trace).setModel("nanojev");
        verify(trace).setBaseUrl("https://llm.mbfsr.com:14443/v1");
        verify(trace).setStream(false);
        verify(trace).setProviderId(67L);
        verify(trace).setNanojevParams("states=1, questions=3, paths=7");
        verify(trace).setInputRaw(anyString());
        verify(trace).setOutputRaw(anyString());
        verify(trace).markSuccess(anyString(), eq("nanojev"), eq(0), eq(0), eq(0), eq(0), eq(67L));

        // usage_log：0 token（completion_tokens=0 语义），请求数/耗时维度可见
        verify(usageLogService).record(eq(1L), eq(1L), eq(1L), eq(67L), eq("nanojev"), eq("nanojev_sample"),
                eq(0), eq(0), eq(0), eq(0), anyInt());
    }

    @Test
    @DisplayName("未启用（enabled=false / 空 base-url）→ null，零 HTTP")
    void disabledReturnsNull() {
        properties.setEnabled(false);
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        assertEquals(0, executor.calls.size(), "未启用不得发请求");
        properties.setEnabled(true);
        properties.setBaseUrl("  ");
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        assertEquals(0, executor.calls.size());
    }

    @Test
    @DisplayName("空 states → null（fail-closed）")
    void emptyStatesReturnsNull() {
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", List.of()));
        assertEquals(0, executor.calls.size());
    }

    @Test
    @DisplayName("Key 解析失败 → null（fail-closed，不发起请求）")
    void keyResolutionFailureReturnsNull() {
        when(modelApiService.resolveProviderConfig(1L, 1L, null))
                .thenThrow(new RuntimeException("未配置主 AI API"));
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        assertEquals(0, executor.calls.size());
    }

    @Test
    @DisplayName("NanoJev 专用 Key 优先于主供应商 Key，且不再解析主供应商（第 3 轮：主模型=DeepSeek 官方 / NanoJev=内网网关）")
    void dedicatedApiKeyOverridesProviderKey() {
        properties.setApiKey("hk-dedicated-gateway-key");
        // 等价场景：主模型已切到 DeepSeek 官方（其 Key 在内网网关 401）——
        // 专用 Key 必须让 NanoJev 完全不依赖主供应商解析结果（此处刻意让解析抛异常）
        when(modelApiService.resolveProviderConfig(1L, 1L, null))
                .thenThrow(new RuntimeException("主供应商与 NanoJev 不同网关（DeepSeek 官方 Key 在内网网关无效）"));

        List<NanoJevEvaluateResponse> result = client.evaluate(1L, 1L, 1L, "nanojev_decision_social", sampleStates());

        assertNotNull(result, "专用 Key 场景不得因主供应商异常而 fail-closed");
        assertEquals(1, executor.calls.size());
        assertTrue(executor.calls.get(0).endsWith("key=hk-dedicated-gateway-key"),
                "请求应携带专用 Key，实际：" + executor.calls.get(0));
        // 未解析主供应商 → 供应商主键为 null（不误记为主模型供应商）
        verify(trace).setProviderId(isNull());
        verify(trace).markSuccess(anyString(), eq("nanojev"), eq(0), eq(0), eq(0), eq(0), isNull());
        verify(usageLogService).record(eq(1L), eq(1L), eq(1L), isNull(), eq("nanojev"),
                eq("nanojev_decision_social"), eq(0), eq(0), eq(0), eq(0), anyInt());
    }

    @Test
    @DisplayName("专用 Key 为空白 → 沿用主供应商 Key 与主供应商主键（第 1/2 轮行为不变）")
    void blankDedicatedApiKeyFallsBackToProvider() {
        properties.setApiKey("   ");
        List<NanoJevEvaluateResponse> result = client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates());

        assertNotNull(result);
        assertTrue(executor.calls.get(0).endsWith("key=test-key"), "实际请求：" + executor.calls.get(0));
        verify(trace).setProviderId(67L);
    }

    @Test
    @DisplayName("健康检查同样优先使用专用 Key（专用 Key 下主供应商解析失败仍可探活）")
    void healthCheckUsesDedicatedApiKey() {
        properties.setApiKey("hk-dedicated-gateway-key");
        when(modelApiService.resolveProviderConfig(1L, 1L, null))
                .thenThrow(new RuntimeException("主供应商不可用"));
        executor.results.add(new NanoJevHttpExecutor.HttpResult(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"nanojev\",\"owned_by\":\"local-nanojev\"}]}", 12));

        Map<String, Object> out = client.healthCheck(1L, 1L);

        assertEquals(Boolean.TRUE, out.get("ready"), "专用 Key 下探活应成功：" + out);
        assertTrue(executor.calls.get(0).contains("GET "), "healthCheck 走 GET：" + executor.calls.get(0));
    }

    @Test
    @DisplayName("HTTP 500（契约超限/服务未就绪）→ null + trace failed（fail-closed）")
    void http500ReturnsNull() {
        executor.results.add(new NanoJevHttpExecutor.HttpResult(500, "{\"code\":500,\"message\":\"Local demo limit\"}", 10));
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        verify(trace).markFailure(any(Exception.class));
        verify(usageLogService, never()).record(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("chat 静默回退响应 → null（防假调用）")
    void chatFallbackReturnsNull() {
        executor.results.add(new NanoJevHttpExecutor.HttpResult(200, CHAT_FALLBACK, 10));
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        verify(trace).markFailure(any(Exception.class));
    }

    @Test
    @DisplayName("forward_passes!=1 响应 → null（静默回退防护）")
    void nonForwardProofReturnsNull() {
        executor.results.add(new NanoJevHttpExecutor.HttpResult(200,
                SAMPLE_RESPONSE.replace("\"forward_passes\":1", "\"forward_passes\":2"), 10));
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        verify(trace).markFailure(any(Exception.class));
    }

    @Test
    @DisplayName("网络异常重试：第 1 次失败第 2 次成功（maxRetries=1），trace 记成功")
    void retriesThenSucceeds() {
        executor.failFirstN = 1;
        List<NanoJevEvaluateResponse> result = client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates());
        assertNotNull(result, "重试后成功");
        assertEquals(2, executor.calls.size(), "失败 1 次 + 成功 1 次");
        verify(trace).markSuccess(anyString(), eq("nanojev"), eq(0), eq(0), eq(0), eq(0), eq(67L));
    }

    @Test
    @DisplayName("重试耗尽仍失败 → null（fail-closed）")
    void retriesExhaustedReturnsNull() {
        executor.failFirstN = 5; // maxRetries=1 → 最多 2 次尝试
        assertNull(client.evaluate(1L, 1L, 1L, "nanojev_sample", sampleStates()));
        assertEquals(2, executor.calls.size(), "恰好尝试 2 次后放弃");
        verify(trace).markFailure(any(Exception.class));
    }

    @Test
    @DisplayName("多批拆分：33 states → 2 次请求 → 2 个响应")
    void multiBatchEvaluate() {
        List<NanoJevState> states = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
            qs.put("q", NanoJevQuestion.bool("命题" + i));
            states.add(NanoJevState.text("s" + i, "上下文" + i, qs));
        }
        List<NanoJevEvaluateResponse> result = client.evaluate(1L, 1L, 1L, "nanojev_sample", states);
        assertNotNull(result);
        assertEquals(2, result.size(), "32+1 两批");
        assertEquals(2, executor.calls.size(), "每批一次请求");
    }

    @Test
    @DisplayName("健康检查：模型列表含 nanojev → ready；不含 → ready=false；未启用 → ready=false")
    void healthCheck() {
        executor.results.add(new NanoJevHttpExecutor.HttpResult(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.6-35b-q4-un\",\"owned_by\":\"llamacpp\"},"
                        + "{\"id\":\"qwen3-embedding\",\"owned_by\":\"local-embedding\"},"
                        + "{\"id\":\"nanojev\",\"owned_by\":\"local-nanojev\"}]}", 15));
        Map<String, Object> ok = client.healthCheck(1L, 1L);
        assertEquals(Boolean.TRUE, ok.get("ready"), "nanojev 在列 → ready");
        assertEquals("ok", ok.get("reason"));

        executor.results.add(new NanoJevHttpExecutor.HttpResult(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.6-35b-q4-un\"}]}", 15));
        Map<String, Object> missing = client.healthCheck(1L, 1L);
        assertEquals(Boolean.FALSE, missing.get("ready"), "nanojev 不在列 → ready=false");
        assertTrue(String.valueOf(missing.get("reason")).contains("未含"), "原因提示未含 nanojev");

        properties.setEnabled(false);
        Map<String, Object> disabled = client.healthCheck(1L, 1L);
        assertEquals(Boolean.FALSE, disabled.get("ready"), "未启用 → ready=false");
    }
}
