package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.guard.GuardRailPolicy;
import com.holzyn.actor.ai.guard.GuardRailProperties;
import com.holzyn.actor.ai.guard.GuardRailTracker;
import com.holzyn.actor.ai.route.SceneRouter;
import com.holzyn.actor.ai.route.SceneRoutingProperties;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关接入统一护栏 + 场景路由单元测试（O3 评估与工程 P2-4/P2-5，2026-09-21）。
 * <p>职责：验证网关侧执行点——①路由按场景补温度/长度档位（request 未显式设置时）且
 * 显式值保留；②路由 provider-id 生效（resolveProviderConfig 收到路由供应商主键）；
 * ③空输出拦截=视为失败（AiCallException + 护栏统计）；④输出重复拦截（同一链路连续
 * 相同输出第 3 次抛异常 + 统计/告警）；⑤护栏/路由关闭或未装配=行为回退现状。</p>
 */
class AiSpringChatGatewayGuardRoutingTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private GuardRailPolicy guardRail;
    private GuardRailTracker guardTracker;
    private SceneRouter sceneRouter;
    private ModelApiService modelApiService;
    private AiSpringChatGateway gateway;

    private static final ProviderConfig CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk-test", "m", false);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        modelApiService = Mockito.mock(ModelApiService.class);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);

        // 护栏：默认配置 + 真实 tracker
        GuardRailProperties guardProps = new GuardRailProperties();
        guardTracker = new GuardRailTracker();
        guardRail = new GuardRailPolicy(guardProps, guardTracker);

        // 路由：按类别档位 + 场景覆盖
        SceneRoutingProperties routingProps = new SceneRoutingProperties();
        routingProps.setDefaultTier(new SceneRoutingProperties.Tier(0.5, 2048));
        routingProps.getTiers().put("dialog", new SceneRoutingProperties.Tier(0.8, 1024));
        routingProps.getTiers().put("decision", new SceneRoutingProperties.Tier(0.3, 2048));
        routingProps.getCategoryMap().put("dialog", "dialog");
        routingProps.getCategoryMap().put("action", "decision");
        sceneRouter = new SceneRouter(routingProps, modelApiService);

        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor(), guardRail, guardTracker, sceneRouter);
        AiCallContext.setSink(traceService::submitAsync);
        when(factory.get(any())).thenReturn(chatModel);
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
        when(modelApiService.resolveProviderConfig(any(), any(), any(), any())).thenReturn(CONFIG);
        // 语义缓存恒 miss（LLM 路径）
        when(semanticCacheService.lookup(any(), any(), any(), any(), any()))
                .thenReturn(SemanticCacheService.Lookup.miss(null, null, null));
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    private AiChatRequest request(Double temperature, Integer maxTokens) {
        return new AiChatRequest(null, List.of(new ChatMessage("user", "你好")), temperature, maxTokens, false);
    }

    private ChatResponse buildResponse(String text, Usage usage) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        return new ChatResponse(List.of(gen), meta);
    }

    private Usage usage(Integer in, Integer out) {
        return new DefaultUsage(in, out, in + out);
    }

    @Test
    void routingFillsTemperatureBySceneWhenRequestUnset() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("好的，我这就去。", usage(10, 5)));
        // dialog 场景 + 温度未设 → 补 0.8；maxTokens 未设 → 补 1024
        AiChatResult result = AiCallContext.run("dialog", 5L,
                () -> gateway.chatCompletion(1L, 9L, null, request(null, null)));
        assertEquals("好的，我这就去。", result.content());
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue().getOptions();
        assertEquals(0.8, options.getTemperature());
        assertEquals(1024, options.getMaxTokens());
    }

    @Test
    void routingOverridesExplicitParams() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("好的。", usage(10, 5)));
        // 用户决策（2026-09-21）路由优先：dialog 场景档位 0.8/1024 覆盖调用方显式 0.1/32
        AiCallContext.run("dialog", 5L,
                () -> gateway.chatCompletion(1L, 9L, null, request(0.1, 32)));
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue().getOptions();
        assertEquals(0.8, options.getTemperature());
        assertEquals(1024, options.getMaxTokens());
    }

    @Test
    void routingProviderIdPassedToResolver() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("好的。", usage(10, 5)));
        // 场景覆盖指定 provider-id=67 → resolveProviderConfig 收到 67
        sceneRouter.putOverride("action", new SceneRoutingProperties.SceneRouteOverride(null, null, 67L));
        AiCallContext.run("action", 5L,
                () -> gateway.chatCompletion(1L, 9L, null, request(null, null)));
        verify(modelApiService).resolveProviderConfig(1L, 9L, 67L, "decision");
    }

    @Test
    void blankOutputInterceptedAsFailureWithStats() {
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("", usage(10, 5)));
        AiCallException ex = assertThrows(AiCallException.class, () -> AiCallContext.run("action", 5L,
                () -> gateway.chatCompletion(1L, 9L, null, request(null, null))));
        assertTrue(ex.getMessage().contains("为空"), "拦截异常消息应含空输出说明");
        assertEquals(1, guardTracker.statsOf("action").blankOutputs());
        assertEquals(1, guardTracker.statsOf("action").degraded());
        assertEquals(1, guardTracker.statsOf("action").failures());
        // 告警已记录
        assertTrue(guardTracker.recentAlerts().stream().anyMatch(a -> a.type().equals("blank_output")));
    }

    @Test
    void duplicateOutputInterceptedAfterWindow() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse("沈砚秋决定去青禾集市看看行情。", usage(10, 5)));
        // 同一链路（withTrace 包裹 → 共享 traceId）连续 3 次相同输出 → 第 3 次拦截
        Runnable call = () -> AiCallContext.run("action", 5L,
                () -> gateway.chatCompletion(1L, 9L, null, request(null, null)));
        AiCallContext.beginTrace();
        try {
            call.run();
            call.run();
            AiCallException ex = assertThrows(AiCallException.class, call::run);
            assertTrue(ex.getMessage().contains("重复"), "拦截异常消息应含重复说明");
        } finally {
            AiCallContext.endTrace();
        }
        assertEquals(1, guardTracker.statsOf("action").duplicates());
        assertTrue(guardTracker.recentAlerts().stream().anyMatch(a -> a.type().equals("duplicate")));
    }

    @Test
    void disabledGuardSkipsChecks() {
        GuardRailProperties guardProps = new GuardRailProperties();
        guardProps.setEnabled(false);
        GuardRailTracker tracker2 = new GuardRailTracker();
        GuardRailPolicy guard2 = new GuardRailPolicy(guardProps, tracker2);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        when(semanticCacheService.lookup(any(), any(), any(), any(), any()))
                .thenReturn(SemanticCacheService.Lookup.miss(null, null, null));
        AiSpringChatGateway gateway2 = new AiSpringChatGateway(factory, traceService, modelApiService,
                semanticCacheService, new HolzynTraceAdvisor(), guard2, tracker2, sceneRouter);
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("", usage(10, 5)));
        // 护栏关闭 → 空输出不拦截，正常返回
        AiChatResult result = AiCallContext.run("action", 5L,
                () -> gateway2.chatCompletion(1L, 9L, null, request(null, null)));
        assertEquals("", result.content());
        assertEquals(0, tracker2.statsOf("action").blankOutputs());
    }

    @Test
    void noGuardNoRouterBehavesAsBefore() {
        // 旧 5 参构造器（guard/router=null）→ 路由不补值、护栏不拦截
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        when(semanticCacheService.lookup(any(), any(), any(), any(), any()))
                .thenReturn(SemanticCacheService.Lookup.miss(null, null, null));
        AiSpringChatGateway plain = new AiSpringChatGateway(factory, traceService, modelApiService,
                semanticCacheService, new HolzynTraceAdvisor());
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("好的。", usage(10, 5)));
        AiCallContext.run("dialog", 5L,
                () -> plain.chatCompletion(1L, 9L, null, request(null, null)));
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue().getOptions();
        // 无路由：不补温度
        assertTrue(options.getTemperature() == null);
    }

    @Test
    void semanticCacheHitSkipsGuardChecks() {
        // 语义缓存命中路径不经 LLM、不执行护栏检测（缓存内容直接返回）
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        AiChatResult cached = new AiChatResult("", "m", 10, 5, 15, 0, 0, 11L);
        when(semanticCacheService.lookup(any(), any(), any(), any(), any()))
                .thenReturn(SemanticCacheService.Lookup.hit("你好", "m", null, cached));
        AiSpringChatGateway gateway2 = new AiSpringChatGateway(factory, traceService, modelApiService,
                semanticCacheService, new HolzynTraceAdvisor(), guardRail, guardTracker, sceneRouter);
        AiChatResult result = AiCallContext.run("action", 5L,
                () -> gateway2.chatCompletion(1L, 9L, null, request(null, null)));
        assertEquals("", result.content());
        assertEquals(0, guardTracker.statsOf("action").blankOutputs());
    }
}
