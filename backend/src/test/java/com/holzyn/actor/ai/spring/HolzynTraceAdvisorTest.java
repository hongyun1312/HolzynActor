package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HolzynTraceAdvisor 单元测试（AI 接入层重构 M1，2026-09-02，方案 §9 M1）。
 * <p>职责：验证观测型 Advisor——①adviseCall 成功投递观察事件（文本/模型/用量）；
 * ②adviseCall 失败投递失败事件并原样上抛；③adviseStream 流式逐块投递（M2 占位验证）；
 * ④usageOf 用量解析：nativeUsage DeepSeek 缓存命中/未命中、OpenAI cached_tokens、标准字段回退、缺失 null。</p>
 */
class HolzynTraceAdvisorTest {

    /**
     * adviseCall 成功：listener 收到成功观察事件（文本/模型/用量）。
     */
    @Test
    void adviseCallSuccessDeliversObservation() {
        List<HolzynTraceAdvisor.CallObservation> obs = new ArrayList<>();
        HolzynTraceAdvisor advisor = new HolzynTraceAdvisor(obs::add);
        ChatClientRequest request = request();
        ChatClientResponse response = response("你好", usage(10, 20));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse returned = advisor.adviseCall(request, chain);

        assertEquals(response, returned);
        assertEquals(1, obs.size());
        HolzynTraceAdvisor.CallObservation o = obs.get(0);
        assertFalse(o.failed());
        assertEquals("你好", o.text());
        assertEquals("m", o.model());
        assertEquals(10, o.usage().promptTokens());
        assertEquals(20, o.usage().completionTokens());
    }

    /**
     * adviseCall 失败：listener 收到失败事件，异常原样上抛（不吞）。
     */
    @Test
    void adviseCallFailureDeliversErrorAndRethrows() {
        List<HolzynTraceAdvisor.CallObservation> obs = new ArrayList<>();
        HolzynTraceAdvisor advisor = new HolzynTraceAdvisor(obs::add);
        ChatClientRequest request = request();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenThrow(new RuntimeException("boom"));

        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> advisor.adviseCall(request, chain));

        assertEquals("boom", ex.getMessage());
        assertEquals(1, obs.size());
        assertTrue(obs.get(0).failed());
        assertNotNull(obs.get(0).error());
    }

    /**
     * adviseStream（M2 占位）：流式链逐块投递观察事件。
     */
    @Test
    void adviseStreamDeliversPerResponseObservation() {
        List<HolzynTraceAdvisor.CallObservation> obs = new ArrayList<>();
        HolzynTraceAdvisor advisor = new HolzynTraceAdvisor(obs::add);
        ChatClientRequest request = request();
        ChatClientResponse r1 = response("块一", usage(10, 3));
        ChatClientResponse r2 = response("块二", usage(10, 3));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(r1, r2));

        List<ChatClientResponse> collected = advisor.adviseStream(request, chain).collectList().block();

        assertNotNull(collected);
        assertEquals(2, collected.size());
        assertEquals(2, obs.size(), "每个响应块各投递一次观察");
        assertEquals("块一", obs.get(0).text());
        assertEquals("块二", obs.get(1).text());
    }

    /**
     * usageOf：nativeUsage 带 DeepSeek 风格缓存命中/未命中 → 双字段正确解析。
     */
    @Test
    void usageOfParsesDeepSeekCacheFields() {
        Map<String, Integer> nativeUsage = new LinkedHashMap<>();
        nativeUsage.put("prompt_cache_hit_tokens", 30);
        nativeUsage.put("prompt_cache_miss_tokens", 90);
        Usage usage = new DefaultUsage(120, 60, 180, nativeUsage, 30L, null);
        AiUsage parsed = HolzynTraceAdvisor.usageOf(response("x", usage));
        assertEquals(120, parsed.promptTokens());
        assertEquals(60, parsed.completionTokens());
        assertEquals(30, parsed.cacheHitTokens());
        assertEquals(90, parsed.cacheMissTokens());
    }

    /**
     * usageOf：OpenAI 风格 prompt_tokens_details.cached_tokens → cacheHit 解析；
     * 未回传 miss 时按「prompt = hit + miss」推算（否则命中率恒 100%）。
     */
    @Test
    void usageOfParsesOpenAiCachedTokens() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cached_tokens", 40);
        Map<String, Object> nativeUsage = new LinkedHashMap<>();
        nativeUsage.put("prompt_tokens_details", details);
        Usage usage = new DefaultUsage(100, 25, 125, nativeUsage, null, null);
        AiUsage parsed = HolzynTraceAdvisor.usageOf(response("x", usage));
        assertEquals(40, parsed.cacheHitTokens());
        assertEquals(60, parsed.cacheMissTokens(), "未回传 miss 应推算为 prompt - hit = 100 - 40");
    }

    /**
     * usageOf：nativeUsage 缺失 → 回退 Usage.getCacheReadInputTokens；再缺失则 null（上层本地估算兜底）。
     */
    @Test
    void usageOfFallsBackToCacheReadAndNull() {
        Usage withCacheRead = new DefaultUsage(50, 10, 60, null, 15L, null);
        AiUsage parsed = HolzynTraceAdvisor.usageOf(response("x", withCacheRead));
        assertEquals(15, parsed.cacheHitTokens());

        AiUsage noUsage = HolzynTraceAdvisor.usageOf(response("x", null));
        assertNull(noUsage.promptTokens());
        assertNull(noUsage.completionTokens());
        assertNull(noUsage.cacheHitTokens());
    }

    /** 构造 ChatClientRequest（Prompt 含一条 user 消息） */
    private static ChatClientRequest request() {
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")));
        return new ChatClientRequest(prompt, Map.of());
    }

    /** 构造 ChatClientResponse（含文本/模型/用量元数据） */
    private static ChatClientResponse response(String text, Usage usage) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        ChatResponse chatResponse = new ChatResponse(List.of(gen), meta);
        return new ChatClientResponse(chatResponse, Map.of());
    }

    private static Usage usage(Integer prompt, Integer completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
