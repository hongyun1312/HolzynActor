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

package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/**
 * Holzyn trace 观测 Advisor。
 * <p>职责：挂在 {@code ChatClient} builder 链上，
 * 对每一次调用<b>观测</b>——成功/失败、模型、输出全文、用量（含 DeepSeek 缓存命中/未命中解析）；
 * 真正落库仍由 {@link AiSpringChatGateway} 主体（begin→调用→fillMessages→markSuccess/Failure→end，
 * 与旧 {@code AiProviderRouter} 同口径）完成。 起流式路径与未来直接消费 ChatClient 的业务方可复用本 Advisor
 * 的观测回调完成记账，无需改动落库体系。</p>
 * <p><b>用量解析（{@link #usageOf}）</b>：优先从 {@code nativeUsage}（供应商原生 usage 对象，经 Jackson3
 * 序列化后解析）读取 DeepSeek 风格 {@code prompt_cache_hit_tokens / prompt_cache_miss_tokens} 与
 * OpenAI 风格 {@code prompt_tokens_details.cached_tokens}（与旧 Provider 双兼容解析同口径）；
 * 缺失时回退 {@code Usage.getCacheReadInputTokens()}；prompt/completion 直取 {@code Usage}。
 * 供应商完全不返回 usage 时上层（网关）用 {@link com.holzyn.actor.ai.TokenEstimator} 本地估算兜底。</p>
 * <p>线程/生命周期：实例无状态（仅持 listener），网关单例复用；listener 默认 DEBUG 日志观测，
 * 测试可注入自定义 listener 断言观察事件。</p>
 */
@Slf4j
public class HolzynTraceAdvisor implements CallAdvisor, StreamAdvisor {

    /** Jackson 3 序列化器（nativeUsage 原始 JSON 解析用；与项目 ai 包一致） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 观测回调（默认 DEBUG 日志；测试/未来记账可注入） */
    private final Consumer<CallObservation> listener;

    /** 无参构造：默认 DEBUG 日志观测 */
    public HolzynTraceAdvisor() {
        this(HolzynTraceAdvisor::logObservation);
    }

    /**
     * 注入自定义观测回调。
     *
     * @param listener 每次调用完成（成功/失败）后的观察回调（可空=不观测）
     */
    public HolzynTraceAdvisor(Consumer<CallObservation> listener) {
        this.listener = listener;
    }

    /** Advisor 名（链上标识） */
    @Override
    public String getName() {
        return "holzyn-trace-advisor";
    }

    /** 最高优先级（最先观测请求/最后收尾响应，不影响业务 advisor 顺序） */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 非流式观测：链调用成功后/异常时各投递一次观察事件 */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try {
            ChatClientResponse response = chain.nextCall(request);
            if (listener != null) {
                listener.accept(new CallObservation(request, response, null));
            }
            return response;
        } catch (Exception e) {
            if (listener != null) {
                listener.accept(new CallObservation(request, null, e));
            }
            throw e;
        }
    }

    /** 流式观测：逐响应块投递；错误/完成时投递——保持回调契约可扩展 */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        if (listener == null) {
            return flux;
        }
        return flux
                .doOnNext(resp -> listener.accept(new CallObservation(request, resp, null)))
                .doOnError(e -> listener.accept(new CallObservation(request, null, e)));
    }

    /**
     * 单次调用的观察事件（成功或失败）。
     *
     * @param request 请求（含完整 Prompt）
     *
     * @param response 响应（成功时非空；流式=每块各一条）
     *
     * @param error 异常（失败时非空）
     */
    public record CallObservation(ChatClientRequest request, ChatClientResponse response, Throwable error) {

        /** 是否失败 */
        public boolean failed() {
            return error != null;
        }

        /** 实际模型名（响应元数据；缺失 null） */
        public String model() {
            ChatResponse r = response == null ? null : response.chatResponse();
            ChatResponseMetadata m = r == null ? null : r.getMetadata();
            return m == null ? null : m.getModel();
        }

        /** 输出全文（首个结果的文本；缺失 null） */
        public String text() {
            ChatResponse r = response == null ? null : response.chatResponse();
            if (r == null || r.getResult() == null || r.getResult().getOutput() == null) {
                return null;
            }
            return r.getResult().getOutput().getText();
        }

        /** 用量（prompt/completion/cacheHit/cacheMiss；缺失 null） */
        public AiUsage usage() {
            return HolzynTraceAdvisor.usageOf(response);
        }
    }

    /**
     * 从 ChatClient 响应解析用量（与旧 Provider 双兼容解析同口径）。
     *
     * @param response ChatClient 响应（可空）
     *
     * @return 用量（字段可空；供应商不返回 usage 时全 null，由上层本地估算兜底）
     */
    public static AiUsage usageOf(ChatClientResponse response) {
        if (response == null) {
            return new AiUsage(null, null, null, null);
        }
        return usageOf(response.chatResponse());
    }

    /**
     * 从 ChatModel 响应解析用量（网关主体与 Advisor 共用，保证口径一致）。
     *
     * @param response ChatModel 响应（可空）
     *
     * @return 用量（字段可空）
     */
    public static AiUsage usageOf(ChatResponse response) {
        if (response == null) {
            return new AiUsage(null, null, null, null);
        }
        ChatResponseMetadata meta = response.getMetadata();
        Usage usage = meta == null ? null : meta.getUsage();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer hit = null;
        Integer miss = null;
        // 缓存命中/未命中：优先 nativeUsage 原始 JSON（DeepSeek prompt_cache_hit/miss_tokens；OpenAI cached_tokens）
        try {
            Object nativeUsage = usage == null ? null : usage.getNativeUsage();
            if (nativeUsage != null) {
                JsonNode node = MAPPER.valueToTree(nativeUsage);
                hit = intOrNull(node.get("prompt_cache_hit_tokens"));
                miss = intOrNull(node.get("prompt_cache_miss_tokens"));
                if (hit == null) {
                    JsonNode details = node.get("prompt_tokens_details");
                    if (details != null) {
                        hit = intOrNull(details.get("cached_tokens"));
                    }
                }
            }
        } catch (Exception e) {
            // nativeUsage 序列化失败（供应商差异）不影响主流程，回退标准字段
            log.debug("[SpringAI] nativeUsage 解析失败，回退标准字段: {}", e.getMessage());
        }
        if (hit == null && usage != null) {
            Long cacheRead = usage.getCacheReadInputTokens();
            if (cacheRead != null) {
                hit = cacheRead.intValue();
            }
        }
        // 未命中推算：部分兼容网关（HolzynLLMServer / 部分 DeepSeek 风格代理）只回传
        // prompt_cache_hit_tokens 而省略 prompt_cache_miss_tokens——若缺 miss 但已知 prompt 与 hit，
        // 按「prompt = hit + miss」约定补齐 miss，否则命中率会恒被算成 100%（hit/(hit+0)）。
        if (miss == null && hit != null && prompt != null) {
            miss = Math.max(0, prompt - hit);
        }
        return new AiUsage(prompt, completion, hit, miss);
    }

    /** 默认观测：DEBUG 日志（成功=模型/输出字数/用量；失败=异常信息） */
    private static void logObservation(CallObservation obs) {
        if (obs.failed()) {
            String err = obs.error() == null ? "未知错误" : obs.error().getMessage();
            log.debug("[SpringAI-Advisor] 调用失败: {}", err);
            return;
        }
        AiUsage usage = obs.usage();
        String text = obs.text();
        log.debug("[SpringAI-Advisor] 观测: model={} 输出{}字 tokens={}/{} cache={}/{}",
                obs.model(),
                text == null ? 0 : text.length(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.cacheHitTokens(),
                usage == null ? null : usage.cacheMissTokens());
    }

    /** 安全读取数值节点（缺失/非数值 → null） */
    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }
}
