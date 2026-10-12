package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSpringChatGateway 单元测试（AI 接入层重构 M1，2026-09-02，方案 §9 M1）。
 * <p>职责：验证新层 Spring AI 网关——①非流式成功调用：结果/token/provider 正确返回；
 * ②trace 记账与旧 Router 同口径（scene/char/project/status/model/token/detail_json[stream=false]）；
 * ③温度/最大 token 参数映射到 Prompt options；④jsonMode → response_format=json_object（不迁移严格 schema）；
 * ⑤用量缺失时 TokenEstimator 本地估算兜底；⑥raw 请求/响应体截取为 OpenAI 兼容 JSON（detail_json 素材）；
 * ⑦失败：异常包装为 AiCallException + trace failed + Advisor 观测到失败；⑧Advisor 观测成功事件与缓存命中解析。</p>
 * <p>测试经真实 ChatClient 装配（mock ChatModel.call），验证「网关主体记账 + Advisor 观测」决策下
 * 两条路径同时生效。</p>
 */
class AiSpringChatGatewayTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private List<HolzynTraceAdvisor.CallObservation> observations;
    private AiSpringChatGateway gateway;

    private static final ProviderConfig CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk-test", "m", false);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        observations = new ArrayList<>();
        // M5：网关构造新增 ModelApiService + SemanticCacheService（3 参 config 路径不经过语义缓存，mock 即可）
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor(observations::add));
        AiCallContext.setSink(traceService::submitAsync);
        when(factory.get(any())).thenReturn(chatModel);
        // 真实 ChatClient 装配会把 Prompt options 与模型默认 options 合并（getOptions/getDefaultOptions），
        // mock 需预置非空 options 避免「ChatModel.getOptions() is null」NPE
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /**
     * 成功路径：结果/用量/provider 正确 + trace 与旧 Router 同口径落库（scene=card_gen、detail_json stream=false）。
     */
    @Test
    void successReturnsResultAndRecordsTrace() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("你好，我是助手", usage(120, 60)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "你是助手"), new ChatMessage("user", "你好")), 0.7, 512, false);

        AiChatResult result = AiCallContext.run("card_gen", 5L,
                () -> gateway.chatCompletion(9L, CONFIG, request));

        assertEquals("你好，我是助手", result.content());
        assertEquals("m", result.model());
        assertEquals(120, result.promptTokens());
        assertEquals(60, result.completionTokens());
        assertEquals(11L, result.providerId());
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("card_gen", saved.getScene());
        assertEquals(9L, saved.getProjectId());
        assertEquals(5L, saved.getCharacterId());
        assertEquals("success", saved.getStatus());
        assertEquals("m", saved.getModel());
        assertEquals(120, saved.getTokenIn());
        assertEquals(60, saved.getTokenOut());
        assertTrue(saved.getDetailJson() != null && saved.getDetailJson().contains("\"stream\":false"),
                "detail_json 应含非流式标记");
        // Advisor 观测成功事件
        assertEquals(1, observations.size());
        assertFalse(observations.get(0).failed());
        assertEquals("你好，我是助手", observations.get(0).text());
        assertEquals(120, observations.get(0).usage().promptTokens());
    }

    /**
     * 参数映射：温度/最大 token 经 Prompt options 下发。
     */
    @Test
    void mapsTemperatureAndMaxTokensToOptions() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("user", "hi")), 0.3, 256, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(0.3, opts.getTemperature());
        assertEquals(256, opts.getMaxTokens());
    }

    /**
     * jsonMode：response_format=json_object（沿用旧路径语义，不迁移严格 schema）。
     */
    @Test
    void jsonModeSetsResponseFormatJsonObject() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("{\"ok\":true}", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("user", "生成 JSON")), null, null, true);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(ResponseFormat.Type.JSON_OBJECT, opts.getResponseFormat().getType());
    }

    /**
     * chatCompletionJson：强制 jsonMode=true（等价 jsonMode 请求）。
     */
    @Test
    void chatCompletionJsonForcesJsonMode() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("{\"a\":1}", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "x")), null, null, false);

        gateway.chatCompletionJson(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(ResponseFormat.Type.JSON_OBJECT, opts.getResponseFormat().getType());
    }

    /**
     * 用量缺失兜底：供应商不返回 usage → TokenEstimator 本地估算非 0。
     */
    @Test
    void missingUsageFallsBackToEstimate() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("你好，世界", null));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "你好呀")), null, null, false);

        AiChatResult result = gateway.chatCompletion(9L, CONFIG, request);

        assertTrue(result.promptTokens() > 0, "prompt 应本地估算非 0");
        assertTrue(result.completionTokens() > 0, "completion 应本地估算非 0");
    }

    /**
     * 缓存命中解析：nativeUsage 带 DeepSeek 风格字段 → cacheHit/cacheMiss 正确解析（与旧 Provider 双兼容口径）。
     */
    @Test
    void parsesCacheHitMissFromNativeUsage() {
        Map<String, Integer> nativeUsage = new LinkedHashMap<>();
        nativeUsage.put("prompt_cache_hit_tokens", 30);
        nativeUsage.put("prompt_cache_miss_tokens", 90);
        Usage usage = new DefaultUsage(120, 60, 180, nativeUsage, 30L, null);
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);

        AiChatResult result = gateway.chatCompletion(9L, CONFIG, request);

        assertEquals(30, result.cacheHitTokens());
        assertEquals(90, result.cacheMissTokens());
    }

    /**
     * raw 截取：raw 请求/响应体为 OpenAI 兼容 JSON（detail_json input.raw/output.raw 素材，与旧 Provider 同形）。
     */
    @Test
    void rawSinksCaptureOpenAiShapedJson() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("回复内容", usage(50, 20)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "usr")), 0.5, 100, false);
        String[] rawReq = new String[1];
        String[] rawResp = new String[1];

        gateway.chatCompletion(9L, CONFIG, request, s -> rawReq[0] = s, s -> rawResp[0] = s);

        assertTrue(rawReq[0].contains("\"model\":\"m\""));
        assertTrue(rawReq[0].contains("\"role\":\"system\""));
        assertTrue(rawReq[0].contains("\"max_tokens\":100"));
        assertTrue(rawResp[0].contains("\"prompt_tokens\":50"));
        assertTrue(rawResp[0].contains("\"completion_tokens\":20"));
    }

    /**
     * 失败：底层异常包装为 AiCallException（中文友好提示含原始信息）+ trace failed + Advisor 观测到失败。
     */
    @Test
    void failureWrapsExceptionAndMarksTraceFailed() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);

        AiCallException ex = assertThrows(AiCallException.class,
                () -> AiCallContext.run("card_gen", 5L, () -> gateway.chatCompletion(9L, CONFIG, request)));
        assertTrue(ex.getMessage().contains("connection refused"));
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("failed", saved.getStatus());
        assertTrue(saved.getErrorMessage().contains("connection refused"));
        // Advisor 观测到失败事件
        assertEquals(1, observations.size());
        assertTrue(observations.get(0).failed());
    }

    /**
     * 生产路径（3 参、无外部 sink）：网关自动接线自身 trace 的 raw 截取 → detail_json input.raw/output.raw 落库
     * （与旧路径口径一致；2026-09-02 冒烟发现 M1 缺陷修复）。
     */
    @Test
    void productionPathCapturesRawIntoTrace() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("回复内容", usage(50, 20)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "usr")), 0.5, 100, false);

        AiChatResult result = gateway.chatCompletion(9L, CONFIG, request);
        assertEquals("回复内容", result.content());
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertTrue(saved.getDetailJson() != null && saved.getDetailJson().contains("\"raw\":{\"model\":\"m\""),
                "生产路径 detail_json 应含 input.raw（model 开头）");
        assertTrue(saved.getDetailJson().contains("\"prompt_tokens\":50"),
                "生产路径 detail_json 应含 output.raw（usage）");
    }

    /** 构造带用量/模型元数据的响应 */
    private static ChatResponse buildResponse(String text, Usage usage) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        return new ChatResponse(List.of(gen), meta);
    }

    private static Usage usage(Integer prompt, Integer completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
