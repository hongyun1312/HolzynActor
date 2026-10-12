package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSpringChatGateway 思考模式全局开关单测（2026-09-27 输出 token 优化轮）。
 *
 * <p><b>背景</b>：第 3 轮长跑（R3-20260927）实测 1,114,785 输出 token 中 66.7%（742,592）是云端模型的
 * 隐藏思考（DeepSeek 官方 reasoning 默认开启、effort=high，思考只回传 reasoning_content 不进正文），
 * 可见正文仅 375,295；同 prompt 在本地 llama.cpp（--reasoning off）单次输出仅 288 token。也就是说
 * 输出 token 的主因是「模型思考模式」，不是 max-tokens 分档。本轮把「是否思考」从「依赖端点默认值/
 * 外部进程参数」收敛为本服务 yml 的单一真源。</p>
 *
 * <p><b>本测试断言的是「真源」而不是「实现细节」</b>：
 * ①{@code holzyn.actor.ai.thinking.mode} 的取值语义（disabled 默认 / enabled / auto / 非法值兜底）；
 * ②该字段确实经 Spring AI 选项通道 {@code OpenAiChatOptions.getExtraBody()} 挂在**最终交给模型**的
 * Prompt 上（非流式 {@code call} 与流式 {@code stream} 两条路径分别断言——流式是隐藏思考的最大来源，
 * 绝不能只关非流式）；
 * ③trace 的 detail_json.input.raw 与实际下发字段**同一来源**（否则会出现「账本说关了思考、
 * 线上其实还在思考」的假账，正是本轮要消灭的观测盲区）；
 * ④未走 Spring 注入（单测直接 new）时字段为 null 必须按 auto 兜底 = 老行为，保证既有单测语义不变。</p>
 *
 * <p><b>为什么断言点在 Prompt 而不是 HTTP 报文</b>：Spring AI 2.0.1 的 OpenAI 模型基于官方
 * openai-java SDK，{@code extraBody} 由 {@code OpenAiChatModel.createRequest} 收成 Map 后交给
 * {@code ChatCompletionCreateParams.Builder.additionalBodyProperties(...)}，由 SDK 序列化为
 * **请求体顶层**属性（已用 javap 核对 2.0.1 字节码）。因此「Prompt options 上的 extraBody」就是
 * 线上顶层字段的充分条件；本测试同时覆盖 ChatClient 装配后的合并结果，能捕获「选项被中间层丢掉」
 * 这类真实事故。</p>
 */
class AiSpringChatGatewayThinkingTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private AiSpringChatGateway gateway;

    private static final ProviderConfig CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk-test", "m", false);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        // 5 参构造 = 单测路径，@Value 字段保持 null（正好用来验证「null ⇒ auto」的兜底语义）
        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor());
        AiCallContext.setSink(traceService::submitAsync);
        when(factory.get(any())).thenReturn(chatModel);
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    // ==================== ① 取值语义 ====================

    /**
     * disabled（yml 默认值）：非流式请求体顶层应下发 thinking={"type":"disabled"}，且 trace 的
     * input.raw 与之一致（同一来源）。
     */
    @Test
    void disabledModeInjectsThinkingDisabledIntoOptionsAndRawBody() {
        setThinking("disabled", "");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), 0.3, 256, false);

        AiCallContext.run("card_gen", 5L, () -> gateway.chatCompletion(9L, CONFIG, request));

        // ② 真实下发通道：交给模型的 Prompt options
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertNotNull(opts.getExtraBody(), "disabled 模式必须挂 extraBody，否则思考不会被关闭");
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), opts.getExtraBody());

        // ③ trace 与实际下发同源
        traceService.awaitIdle(2000);
        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        String detailJson = captor.getValue().getDetailJson();
        assertNotNull(detailJson);
        assertTrue(detailJson.contains("\"thinking\":{\"type\":\"disabled\"}"),
                "detail_json.input.raw 应含顶层 thinking 字段（账本与线上请求体同形），实际：" + detailJson);
        assertFalse(detailJson.contains("reasoning_effort"),
                "disabled 模式不应下发 reasoning_effort");
    }

    /**
     * enabled：下发 thinking={"type":"enabled"}，并在 effort 非空时附带顶层 reasoning_effort。
     */
    @Test
    void enabledModeInjectsThinkingEnabledWithEffort() {
        setThinking("enabled", "high");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(Map.of("thinking", Map.of("type", "enabled"), "reasoning_effort", "high"),
                opts.getExtraBody());
    }

    /**
     * enabled 但未指定 effort：只下发 thinking，不下发 reasoning_effort（跟随端点默认强度）。
     */
    @Test
    void enabledModeWithoutEffortOmitsReasoningEffort() {
        setThinking("enabled", "   ");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), opts.getExtraBody());
    }

    /**
     * auto：不下发任何思考字段（老行为，跟随端点默认值）。
     */
    @Test
    void autoModeInjectsNothing() {
        setThinking("auto", "high");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        AiCallContext.run("card_gen", 5L, () -> gateway.chatCompletion(9L, CONFIG, request));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertTrue(opts.getExtraBody() == null || opts.getExtraBody().isEmpty(),
                "auto 模式不应下发任何思考字段，实际：" + opts.getExtraBody());

        traceService.awaitIdle(2000);
        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().getDetailJson().contains("thinking"),
                "auto 模式 trace 不应出现 thinking 字段");
    }

    /**
     * 非法取值兜底：拼错的 mode 按 auto 处理（不下发），避免一个笔误让全部 AI 调用 400。
     */
    @Test
    void invalidModeFallsBackToAuto() {
        setThinking("disabledd", "high");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertTrue(opts.getExtraBody() == null || opts.getExtraBody().isEmpty(),
                "非法模式应按 auto 兜底（不下发），实际：" + opts.getExtraBody());
    }

    /**
     * 大小写/空白容错：{@code " DISABLED "} 等价 disabled（yml/环境变量手写容错）。
     */
    @Test
    void modeIsTrimmedAndCaseInsensitive() {
        setThinking("  DISABLED ", "");
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), opts.getExtraBody());
    }

    /**
     * 未走 Spring 注入（字段为 null）：按 auto 兜底 = 老行为不下发。
     * <p>这条断言是既有 2268 全绿基线的护栏——所有老单测都直接 new 网关（@Value 不生效），
     * 一旦兜底语义被改成「null ⇒ disabled」，老测试的 raw 请求体会集体变化。</p>
     */
    @Test
    void nullInjectedFieldsKeepLegacyBehavior() {
        // 不调用 setThinking(...)：两个字段保持 null
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("ok", usage(10, 5)));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("user", "hi")), null, null, false);

        gateway.chatCompletion(9L, CONFIG, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertTrue(opts.getExtraBody() == null || opts.getExtraBody().isEmpty(),
                "未注入配置（测试路径）应保持老行为：不下发思考字段");
    }

    // ==================== ② 流式路径（隐藏思考的最大来源） ====================

    /**
     * 流式路径同样必须下发：dialog 等流式场景是长跑里隐藏思考的主要来源，
     * 只关非流式等于没关。
     */
    @Test
    void streamingPathInjectsThinkingDisabled() {
        setThinking("disabled", "");
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("你", null), chunk("好", null), chunk(null, usage(120, 60))));
        AiChatRequest request = new AiChatRequest(null,
                java.util.List.of(new ChatMessage("system", "你是助手"), new ChatMessage("user", "你好")),
                0.7, 512, false);
        StringBuilder full = new StringBuilder();

        AiCallContext.run("dialog", 7L, () -> {
            gateway.chatCompletionStream(9L, CONFIG, request, full::append, u -> { });
            return null;
        });

        assertEquals("你好", full.toString());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), opts.getExtraBody());
        // 流式原有语义不受影响：streamUsage 仍开启（末块真实用量；2.0.1 落在 StreamOptions.includeUsage）
        assertNotNull(opts.getStreamOptions(), "流式应带 stream_options");
        assertEquals(Boolean.TRUE, opts.getStreamOptions().includeUsage());

        traceService.awaitIdle(2000);
        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getDetailJson().contains("\"thinking\":{\"type\":\"disabled\"}"),
                "流式 trace 也应记录顶层 thinking 字段");
    }

    // ==================== ③ 启动期可观测性 ====================

    /**
     * {@code @PostConstruct logThinkingPolicy()} 必须对 null/空串/大小写混乱/非法值全部安全：
     * 这是 Spring 启动路径上的方法，一旦抛异常会**直接导致后端启动失败**；同时它的 WARN 是
     * 「yml 写错」的唯一启动期提示（无 {@code @SpringBootTest} 的仓库里尤其重要）。
     */
    @Test
    void postConstructThinkingPolicyLogNeverThrows() {
        // 字段为 null（单测 new 出来的网关 = @Value 未注入）
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(gateway, "logThinkingPolicy"));
        // 空串 / 大小写混乱 / 非法值 / 正常 disabled / 正常 enabled
        for (String[] pair : new String[][]{{"", ""}, {"  WeIrD  ", "  "}, {"auto", "x"},
                {"disabled", ""}, {"enabled", "high"}}) {
            setThinking(pair[0], pair[1]);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> ReflectionTestUtils.invokeMethod(gateway, "logThinkingPolicy"),
                    "@PostConstruct 不得因 mode=" + pair[0] + " 抛异常（会导致启动失败）");
        }
    }

    // ==================== 工具 ====================

    /** 注入思考配置（模拟 Spring @Value 注入；字段注入保持构造器签名不变） */
    private void setThinking(String mode, String effort) {
        ReflectionTestUtils.setField(gateway, "thinkingMode", mode);
        ReflectionTestUtils.setField(gateway, "thinkingEffort", effort);
    }

    /** 构造带用量/模型元数据的响应 */
    private static ChatResponse buildResponse(String text, Usage usage) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        return new ChatResponse(java.util.List.of(gen), meta);
    }

    /** 构造流式分块（text 与 usage 至少一个有值） */
    private static ChatResponse chunk(String text, Usage usage) {
        Generation gen = (text == null) ? null : new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        return (gen == null) ? new ChatResponse(java.util.List.of(), meta) : new ChatResponse(java.util.List.of(gen), meta);
    }

    private static Usage usage(Integer prompt, Integer completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
