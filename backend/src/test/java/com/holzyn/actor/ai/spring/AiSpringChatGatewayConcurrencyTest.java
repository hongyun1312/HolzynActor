package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AiSpringChatGateway 网关并发限流集成测试（2026-09-29_07 网关并发错峰观察项）。
 * <p>职责：验证限流器在网关<b>真实调用路径</b>生效（字段注入同 Spring 装配）——慢模型模拟下并发
 * 扇出：①非流式：在途峰值 ≤ 并发上限、有排队等待（totalWaits&gt;0）、等待零超时、全部成功、结束归零；
 * ②流式同语义。限流器默认关闭（max=0）时网关直通（既有 AiSpringChatGatewayTest 全部通过即回归）。</p>
 */
class AiSpringChatGatewayConcurrencyTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private AiSpringChatGateway gateway;
    private GatewayConcurrencyLimiter limiter;

    private static final ProviderConfig CONFIG =
            new ProviderConfig(21L, "https://api.example.com/v1", "sk-conc", "m", false);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor());
        // 注入限流器（构造器签名不变，字段注入=Spring 装配同路径）：上限 2 + 慢模型 → 真实排队
        limiter = new GatewayConcurrencyLimiter();
        limiter.setMaxConcurrent(2);
        limiter.setWaitTimeoutSeconds(30);
        limiter.init();
        ReflectionTestUtils.setField(gateway, "gatewayConcurrencyLimiter", limiter);
        AiCallContext.setSink(traceService::submitAsync);
        when(factory.get(any())).thenReturn(chatModel);
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
        // 慢模型：单次 200ms（模拟自托管 35B 单实例延迟），让并发真实排队
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(200);
            return buildResponse("好", usage(10, 5));
        });
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /** 非流式：6 并发 → 在途峰值=上限 2、有排队等待、零超时、全部成功、结束归零。 */
    @Test
    void concurrentNonStreamCallsAreCappedAndAllSucceed() throws Exception {
        int n = 6;
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<AiChatResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.await();
                return AiCallContext.run("card_gen", 5L, () -> gateway.chatCompletion(9L, CONFIG, request));
            }));
        }
        ready.countDown();
        for (Future<AiChatResult> f : futures) {
            assertEquals("好", f.get(30, TimeUnit.SECONDS).content());
        }
        pool.shutdownNow();

        GatewayConcurrencyLimiter.Stats s = limiter.stats();
        assertEquals(2, s.maxInFlight(), "在途峰值应恰为并发上限 2（实测 " + s.maxInFlight() + "）");
        assertTrue(s.totalWaits() >= 1, "应有排队等待发生（实测 waits=" + s.totalWaits() + "）");
        assertEquals(0, s.waitTimeouts(), "排队等待应零超时（削峰而非失败）");
        assertEquals(0, s.inFlight(), "全部结束后在途归零");
    }

    /** 流式：4 并发 → 在途峰值=上限 2、有排队等待、零超时、全部成功、结束归零。 */
    @Test
    void concurrentStreamCallsAreCapped() throws Exception {
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(200);
            return Flux.just(buildResponse("好", usage(10, 5)));
        });
        int n = 4;
        AiChatRequest request = new AiChatRequest(null, List.of(new ChatMessage("user", "hi")), null, null, false);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.await();
                StringBuilder full = new StringBuilder();
                AtomicReference<AiUsage> usage = new AtomicReference<>();
                return AiCallContext.run("dialog", 7L, () -> {
                    gateway.chatCompletionStream(9L, CONFIG, request, full::append, usage::set);
                    return full.toString();
                });
            }));
        }
        ready.countDown();
        for (Future<String> f : futures) {
            assertEquals("好", f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        GatewayConcurrencyLimiter.Stats s = limiter.stats();
        assertEquals(2, s.maxInFlight(), "流式在途峰值应恰为并发上限 2（实测 " + s.maxInFlight() + "）");
        assertTrue(s.totalWaits() >= 1, "流式应有排队等待发生（实测 waits=" + s.totalWaits() + "）");
        assertEquals(0, s.waitTimeouts(), "流式排队等待应零超时");
        assertEquals(0, s.inFlight(), "流式全部结束后在途归零");
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
