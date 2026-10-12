package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiUsage;
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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSpringChatGateway 流式单测（AI 接入层重构 M2，2026-09-03，方案 §9 M2）。
 * <p>职责：验证流式桥接——①成功：Flux 逐块增量回调 onToken、末块 usage 回传 onUsage、trace 以
 * stream=true 同口径落库；②用量缺失：TokenEstimator 本地估算兜底（onUsage 仍被回调）；③断开语义：
 * onToken 回调抛出的业务异常（"SSE 连接已断开"）原样上抛 + trace failed（消费方零改动）；
 * ④Flux 底层错误包装 AiCallException + trace failed；⑤raw 请求/响应体截取为 OpenAI 兼容 JSON；
 * ⑥生产路径（4 参）自动接线自身 trace raw 截取。</p>
 * <p>测试经真实 ChatClient 装配（mock ChatModel.stream 返回测试 Flux），验证「网关主体记账 +
 * Advisor 观测」决策下流式路径记账与旧 Router 同口径。</p>
 */
class AiSpringChatGatewayStreamTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private AiSpringChatGateway gateway;

    private static final ProviderConfig CONFIG =
            new ProviderConfig(12L, "https://api.example.com/v1", "sk-stream", "m", false);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        // M5：网关构造新增 ModelApiService + SemanticCacheService（流式不经过语义缓存，mock 即可）
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor());
        AiCallContext.setSink(traceService::submitAsync);
        when(factory.get(any())).thenReturn(chatModel);
        // 真实 ChatClient 装配会把 Prompt options 与模型默认 options 合并，mock 需预置非空 options 防 NPE
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /**
     * 成功路径：两段内容 + 末块真实用量 → onToken 收全增量、onUsage 收真实用量、trace stream=true 同口径落库。
     */
    @Test
    void streamSuccessDeliversTokensAndUsageAndRecordsTrace() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                chunk("你", null),
                chunk("好", null),
                chunk(null, usage(120, 60))));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "你是助手"), new ChatMessage("user", "你好")), 0.7, 512, false);
        StringBuilder full = new StringBuilder();
        AtomicReference<AiUsage> lastUsage = new AtomicReference<>();

        AiCallContext.run("dialog", 7L, () -> {
            gateway.chatCompletionStream(9L, CONFIG, request, full::append, lastUsage::set);
            return null;
        });

        assertEquals("你好", full.toString());
        assertEquals(120, lastUsage.get().promptTokens());
        assertEquals(60, lastUsage.get().completionTokens());
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("dialog", saved.getScene());
        assertEquals(9L, saved.getProjectId());
        assertEquals(7L, saved.getCharacterId());
        assertEquals("success", saved.getStatus());
        assertEquals("m", saved.getModel());
        assertEquals(120, saved.getTokenIn());
        assertEquals(60, saved.getTokenOut());
        assertEquals("你好", saved.getResponse());
        assertTrue(saved.getDetailJson() != null && saved.getDetailJson().contains("\"stream\":true"),
                "detail_json 应含流式标记");
    }

    /**
     * 流式参数映射：温度/maxTokens 进 Prompt options，且流式开启 streamUsage（等价旧路径 include_usage）。
     */
    @Test
    void streamMapsOptionsAndEnablesStreamUsage() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(chunk("ok", usage(10, 5))));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("user", "hi")), 0.3, 256, false);

        gateway.chatCompletionStream(9L, CONFIG, request, s -> {
        }, u -> {
        });

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> promptCaptor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(0.3, opts.getTemperature());
        assertEquals(256, opts.getMaxTokens());
        assertTrue(opts.getStreamOptions() != null, "流式应开启 stream_options（include_usage 回传用量）");
    }

    /**
     * 用量缺失兜底：供应商流式不返回 usage → TokenEstimator 本地估算非 0 且 onUsage 仍被回调（与旧路径一致）。
     */
    @Test
    void streamMissingUsageFallsBackToEstimate() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(chunk("你好，世界", null)));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("user", "你好呀")), null, null, false);
        AtomicReference<AiUsage> usageRef = new AtomicReference<>();

        gateway.chatCompletionStream(9L, CONFIG, request, s -> {
        }, usageRef::set);

        assertTrue(usageRef.get().promptTokens() > 0, "prompt 应本地估算非 0");
        assertTrue(usageRef.get().completionTokens() > 0, "completion 应本地估算非 0");
    }

    /**
     * 真实 usage 但 cache 字段缺失（doubao 等仅返回 prompt_cache_hit_tokens）：onUsage 的 cacheHit/cacheMiss
     * 归一化为 0（对齐旧 Provider 流式恒非 null 默认 0）——2026-09-03 冒烟抓修（消费方 int[] 拆箱 NPE）。
     */
    @Test
    void streamNullCacheFieldsNormalizedToZero() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                chunk("回复", null),
                chunk(null, usage(120, 60))));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);
        AtomicReference<AiUsage> usageRef = new AtomicReference<>();

        gateway.chatCompletionStream(9L, CONFIG, request, s -> {
        }, usageRef::set);

        assertEquals(120, usageRef.get().promptTokens());
        assertEquals(60, usageRef.get().completionTokens());
        assertEquals(0, usageRef.get().cacheHitTokens(), "cacheHit 缺失应归一化为 0");
        assertEquals(0, usageRef.get().cacheMissTokens(), "cacheMiss 缺失应归一化为 0");
    }

    /**
     * 断开语义：onToken 回调抛出的业务异常（SSE 连接已断开）原样上抛（不包装）+ trace failed——消费方零改动。
     */
    @Test
    void streamDisconnectPropagatesCallbackException() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(chunk("你", null), chunk("好", null)));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> AiCallContext.run("dialog", 7L, () -> {
            gateway.chatCompletionStream(9L, CONFIG, request,
                    s -> {
                        throw new RuntimeException("SSE 连接已断开");
                    },
                    u -> {
                    });
            return null;
        }));

        assertEquals("SSE 连接已断开", ex.getMessage());
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("failed", saved.getStatus());
        assertTrue(saved.getErrorMessage() != null && saved.getErrorMessage().contains("SSE 连接已断开"));
    }

    /**
     * Flux 底层错误（网络中断/供应商错误）→ 包装为 AiCallException（与旧路径「AI 流式调用失败」契约一致）+ trace failed。
     */
    @Test
    void streamFluxErrorWrapsAiCallException() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.error(new RuntimeException("connection reset")));
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);

        AiCallException ex = assertThrows(AiCallException.class,
                () -> AiCallContext.run("dialog", 7L, () -> {
                    gateway.chatCompletionStream(9L, CONFIG, request, s -> {
                    }, u -> {
                    });
                    return null;
                }));

        assertTrue(ex.getMessage().contains("connection reset"));
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
    }

    /**
     * raw 截取：流式 raw 请求/响应体为 OpenAI 兼容 JSON（detail_json input.raw/output.raw 素材，与旧 Provider 同形）。
     */
    @Test
    void streamRawSinksCaptureOpenAiShapedJson() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                chunk("回复", null),
                chunk(null, usage(50, 20))));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "usr")), 0.5, 100, false);
        String[] rawReq = new String[1];
        String[] rawResp = new String[1];

        gateway.chatCompletionStream(9L, CONFIG, request, s -> {
        }, u -> {
        }, s -> rawReq[0] = s, s -> rawResp[0] = s);

        assertTrue(rawReq[0].contains("\"model\":\"m\""));
        assertTrue(rawReq[0].contains("\"role\":\"system\""));
        assertTrue(rawReq[0].contains("\"max_tokens\":100"));
        assertTrue(rawResp[0].contains("\"prompt_tokens\":50"));
        assertTrue(rawResp[0].contains("\"completion_tokens\":20"));
        assertTrue(rawResp[0].contains("\"content\":\"回复\""));
    }

    /**
     * 生产路径（4 参、无外部 sink）：网关自动接线自身 trace 的 raw 截取 → detail_json input.raw/output.raw 落库。
     */
    @Test
    void streamProductionPathCapturesRawIntoTrace() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                chunk("回复内容", null),
                chunk(null, usage(50, 20))));
        AiChatRequest request = new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "usr")), 0.5, 100, false);

        gateway.chatCompletionStream(9L, CONFIG, request, s -> {
        }, u -> {
        });
        traceService.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertTrue(saved.getDetailJson() != null && saved.getDetailJson().contains("\"raw\":{\"model\":\"m\""),
                "生产路径 detail_json 应含 input.raw（model 开头）");
        assertTrue(saved.getDetailJson().contains("\"prompt_tokens\":50"),
                "生产路径 detail_json 应含 output.raw（usage）");
        assertTrue(saved.getDetailJson().contains("\"stream\":true"), "detail_json 应含流式标记");
    }

    /**
     * 空消息：与旧路径同口径抛 AiCallException。
     */
    @Test
    void streamEmptyMessagesRejected() {
        AiChatRequest request = new AiChatRequest(null, List.of(), null, null, false);

        AiCallException ex = assertThrows(AiCallException.class,
                () -> gateway.chatCompletionStream(9L, CONFIG, request, s -> {
                }, u -> {
                }));

        assertTrue(ex.getMessage().contains("对话消息不能为空"));
    }

    /** 构造流式块：text 为增量文本（可空=usage-only 末块）；usage 可空=该块不携带用量（metadata 不带 usage 字段） */
    private static ChatResponse chunk(String text, Usage usage) {
        Generation gen = text == null ? null : new Generation(new AssistantMessage(text));
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder().model("m");
        if (usage != null) {
            metaBuilder.usage(usage);
        }
        return new ChatResponse(gen == null ? List.of() : List.of(gen), metaBuilder.build());
    }

    private static Usage usage(Integer prompt, Integer completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
