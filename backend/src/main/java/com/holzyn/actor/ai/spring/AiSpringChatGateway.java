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

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.guard.GuardRailPolicy;
import com.holzyn.actor.ai.guard.GuardRailTracker;
import com.holzyn.actor.ai.route.SceneRouter;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.agent.prompt.PromptResources;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Spring AI 对话网关。
 * <p>职责：把「旧手写 HttpClient 的对话补全」替换为 Spring AI {@code ChatClient + OpenAiChatModel}
 * 实现，返回与旧路径<b>同签名</b>的 {@link AiChatResult}； 覆盖非流式（chatCompletion / chatCompletionJson），
 * <b>起覆盖流式（chatCompletionStream）</b>；<b>起为唯一对话入口</b>（旧 AiProviderRouter 兼容壳已
 * 物理删除，业务消费方直接注入本网关调用 userId 重载；embedding/RAG 走 LangChain4j 侧）。</p>
 * <p><b>记账</b>：本网关主体内完成与旧 {@code AiProviderRouter} 完全同口径的
 * trace 埋点——begin（AiCallContext 感知）→ setBaseUrl/setStream → raw 请求/响应体截取
 * （detail_json input.raw/output.raw，重建为与旧 Provider 同形的 OpenAI 兼容 JSON）→ fillMessages →
 * markSuccess/Failure → INFO/DEBUG 实时日志 → end 落库。用量解析共用
 * {@link HolzynTraceAdvisor#usageOf}（DeepSeek/OpenAI 缓存命中双兼容），缺失时
 * {@link TokenEstimator} 本地估算兜底——与旧路径口径一致。</p>
 * <p><b>流式</b>：把 Spring AI 响应式 {@code Flux<ChatResponse>} 桥接回旧签名
 * ——内部 {@code Flux.subscribe + CountDownLatch} 阻塞等待，逐增量回调 onToken、结束后回调 onUsage
 * （与旧 Provider「流式末块携带 usage / 缺失本地估算」口径一致）；<b>断开/中断语义保留</b>：
 * onToken 回调抛出的业务异常（如 {@code "SSE 连接已断开"}）→ 立即取消订阅 + 原样上抛（消费方按消息识别），
 * Flux 底层错误包装为 {@link AiCallException}（与旧路径「AI 流式调用失败」契约一致）；超时防御性兜底
 * （10 分钟，取消订阅并上抛，避免悬挂线程）。</p>
 * <p><b>语义缓存</b>：仅非流式 chatCompletion / chatCompletionJson 生效（userId 入口内）——
 * 命中 {@link SemanticCacheService} 时直接复用回复（记 semantic_cache_hit trace，不调 LLM、不写 usage_log）；流式恒走 LLM。</p>
 * <p><b>jsonMode 兼容（方案 §11 风险缓解）</b>：沿用 {@code response_format=json_object}（{@link ResponseFormat.Type#JSON_OBJECT}），
 * 不迁移 Spring AI 严格 schema——本地校验/修复/定向重试（WorldGenSchemaValidator/DecisionService）继续兜底，输出质量不回退。</p>
 * <p><b>思考模式（输出 token 优化轮）</b>：{@code holzyn.actor.ai.thinking.mode} 全局开关
 * {@code additionalBodyProperties} 落到请求体顶层 {@code thinking} 字段，非流式与流式<b>同时</b>生效，
 * 并与 trace 的 input.raw 同来源。 长跑实测：云端隐藏思考占总输出 66.7%（742,592/1,114,785），
 * 本地（--reasoning off）单次输出仅 288 token —— 关思考是输出 token 的最大单一杠杆。</p>
 */
@Slf4j
@Component
public class AiSpringChatGateway {

    /** Jackson 3 序列化器（raw 请求/响应体重建） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 流式阻塞等待上限（防御性兜底：正常流式远快于此；超时取消订阅并上抛，避免调用线程悬挂） */
    private static final long STREAM_AWAIT_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 动态模型工厂（缓存复用 + 惰性失效） */
    private final DynamicChatModelFactory chatModelFactory;

    /** AI 调用 trace 埋点服务（与旧 Router 同源，口径一致） */
    private final AiCallTraceService aiCallTraceService;

    /** 观测型 Advisor */
    private final HolzynTraceAdvisor advisor;

    /** 供应商运行时配置服务 */
    private final ModelApiService modelApiService;

    /** 语义缓存服务 */
    private final SemanticCacheService semanticCacheService;

    /** AI 供应商连通性探测 */
    private final ChatModelProbe chatModelProbe;

    /** 统一护栏策略对象 */
    private final GuardRailPolicy guardRail;

    /** 护栏运行时状态（统计/告警/运行时覆盖；随 guardRail 注入） */
    private final GuardRailTracker guardTracker;

    /**
     * 决策模型 护栏语义复核（默认关场景；开启且装配时对非流式输出做 boolean 越界复核，
     * 越界按护栏失败处置；字段注入保持构造器不变，null=未装配[单测]跳过零开销）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.GuardSemanticJudge decisionGuardSemanticCheck;

    /**
     * 网关并发限流器（_07 网关并发错峰观察项；max-concurrent>0 时对全部 chat 调用收口
     * 限流——本地模型网关排队场景削峰；DeepSeek API 主用默认关闭；字段注入保持构造器不变，
     * null=未装配[单测]跳过零开销）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GatewayConcurrencyLimiter gatewayConcurrencyLimiter;

    /** 场景-模型路由器 */
    private final SceneRouter sceneRouter;

    /**
     * 常驻稳定前缀装配器（缓存事故）。
     *
     * <p><b>为什么放在网关</b>：仓库里有一批服务自己拼 messages（对话/记忆/环境/部分决策），
     * 它们把「契约 + 世界常识」塞在自己那条 user 消息里，而对话链路前面还排着几条 assistant 台词——
     * 于是可缓存前缀只剩开头那 200 token，实测同一世界跑满一天命中率仍压在 30% 上下。</p>
     *
     * <p>网关是所有 AI 调用的唯一收口，在这里把「契约 + 常驻前缀」铸成<b>第二条消息</b>
     * （system 之后、历史台词之前），全部场景的开头才逐字一致。</p>
     *
     * <p>字段注入 + {@code @Lazy}：保持构造器不变（不打断既有测试构造点），
     * {@code required=false} 让单测直接 new 本类时降级为「只前置契约」，
     * {@code @Lazy} 断开与上下文注入器之间的潜在 Bean 环（实测环会只在真实启动时炸）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 网关层「契约 + 常驻前缀」前插开关（关掉即回到改造前行为，便于 A/B）。 */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.ai.stable-prompt:true}")
    private boolean stablePromptEnabled = true;

    /** Prompt 工程指标（版本/token/格式合规率/缓存命中；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.prompt.PromptMetricsService promptMetrics;

    // ==================== 输出 token 优化：思考模式全局开关 ====================
    //
    // 【为什么需要】 长跑（R3-20260927）实测：1,114,785 输出 token 里 66.7%（742,592）是云端模型
    // 的「隐藏思考」（DeepSeek 官方 reasoning 默认开启且 effort=high，只回传 reasoning_content 不落正文），
    // 可见正文只有 375,295；同一套 prompt 在本地 llama.cpp（进程参数 --reasoning off）单次输出仅 288 token
    // （≈1/4）。结论：输出 token 的主因是「模型的思考模式」，max-tokens 分档只是失控护栏。
    //
    // 【此前的问题】客户端（本网关）从不下发任何思考控制字段，是否思考完全取决于**端点默认值**——
    // 默认供应商 67（本地 Qwen）恰好被 llama-server 的 --reasoning off 全局关掉，所以账单看着正常；
    // 一旦把默认供应商切到 68（DeepSeek 官方），思考会自动全量打开，token 直接涨 2~4 倍且无任何配置项可管。
    //
    // 【本开关的定位】把「是否思考」从「依赖外部进程参数 / 端点默认」变成「本服务 yml 的单一真源」：
    // 一处配置（holzyn.actor.ai.thinking.mode）同时约束云端与本地，且 trace 的 input.raw 与真实请求体
    // 走同一份数据（buildOptions/buildInputRaw 同一来源），不会出现「账本说关了、线上还在思考」的假账。
    //
    // 【下发通道】OpenAI 兼容请求体**顶层** thinking 字段（DeepSeek 官方契约）：
    // OpenAiChatOptions.extraBody(map) → Spring AI OpenAiChatModel.createRequest 把 entrySet 收成 Map →
    // openai-java `ChatCompletionCreateParams.Builder.additionalBodyProperties(map)` → 顶层 JSON。
    // （Spring AI 2.0.1 已改用官方 openai-java SDK，extraBody 由 SDK 的「附加 body 属性」通道下发；
    // 本文件的 buildInputRaw 同步写入同一份字段，保证 detail_json 与线上请求体同形。）
    // 【本地兼容性】llama.cpp 对请求级思考参数无效（HolzynLLMServer/README.md：Qwen3.6 要用模板级
    // chat_template_kwargs.enable_thinking，llama-server 的 --reasoning off 是全局默认）——多下发该字段
    // 被静默忽略、不会 400，因此 67/68 可共用同一份配置，无需按供应商分支。

    /**
     * 思考模式全局开关：{@code disabled}（默认，省 token）/ {@code enabled} / {@code auto}（不下发，跟随端点默认）。
     * <p>取值来源 {@code holzyn.actor.ai.thinking.mode}；空值（单测直接 new 出的实例、未走 Spring 注入）
     * 按 {@code auto} 兜底 = 老行为不下发，保证既有单测语义不变。</p>
     */
    @Value("${holzyn.actor.ai.thinking.mode:auto}")
    private String thinkingMode;

    /**
     * 思考强度（仅 {@code mode=enabled} 时随 thinking 一起下发；空=不下发，跟随端点默认）。
     * <p>取值来源 {@code holzyn.actor.ai.thinking.effort}（如 high/max）。思考关闭时供应商会忽略该字段。</p>
     */
    @Value("${holzyn.actor.ai.thinking.effort:}")
    private String thinkingEffort;

    /**
     * 启动自检：把生效的思考策略打一条日志。
     * <p>为什么值得单开一段：思考开关是「配错了不会报错、只会悄悄多花钱」的静默配置—— 长跑
     * 66.7% 的输出 token 就是隐藏思考。启动时把实际生效值写进日志，是唯一能在事后一眼看出
     * 「这轮是不是真的关了思考」的办法（本服务无 @SpringBootTest，yml 键写错只在真实启动暴露）。</p>
     */
    @PostConstruct
    void logThinkingPolicy() {
        String mode = normalizedThinkingMode();
        switch (mode) {
            case "disabled" -> log.info("[AI思考模式] mode=disabled：请求体顶层下发 thinking={type:disabled}，"
                    + "隐藏思考输出被关闭（输出 token 优化生效）");
            case "enabled" -> log.info("[AI思考模式] mode=enabled：请求体顶层下发 thinking={type:enabled}，"
                    + "思考强度 effort={}（空=跟随端点默认；云端思考会显著增加输出 token）",
                    effectiveEffort().isEmpty() ? "(未指定)" : effectiveEffort());
            case "auto", "" -> log.info("[AI思考模式] mode=auto：不下发思考控制字段，跟随端点默认"
                    + "（⚠️ 云端 DeepSeek 官方默认开启思考，输出 token 会显著偏高）");
            default -> log.warn("[AI思考模式] mode={} 不是合法取值（disabled/enabled/auto），已按 auto 处理："
                    + "不下发思考控制字段、跟随端点默认——请检查 holzyn.actor.ai.thinking.mode / "
                    + "HOLOZYN_ACTOR_AI_THINKING_MODE", thinkingMode);
        }
    }

    /**
     * 归一化思考模式（null 安全 + 去空白 + 小写）。
     *
     * @return {@code disabled}/{@code enabled}/{@code auto} 之一；空串表示未配置（按 auto 处理）
     */
    private String normalizedThinkingMode() {
        return thinkingMode == null ? "" : thinkingMode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 归一化思考强度（null 安全 + 去空白）。
     *
     * @return 强度字符串；空串表示未指定（不下发 reasoning_effort）
     */
    private String effectiveEffort() {
        return thinkingEffort == null ? "" : thinkingEffort.trim();
    }

    /**
     * 解析需追加到请求体<b>顶层</b>的思考控制字段（真实请求体与 trace 的 input.raw 共用同一份数据）。
     * <ul>
     * <li>{@code disabled} → {@code {"thinking":{"type":"disabled"}}}（默认；DeepSeek 官方契约的关闭写法）</li>
     * <li>{@code enabled} → {@code {"thinking":{"type":"enabled"}}}，另在 effort 非空时追加
     * <li>{@code auto} / 空 / 非法值 → 空 Map（不下发任何字段，跟随端点默认；非法值按 auto 兜底，
     * 避免一个拼写错误让全部 AI 调用 400）</li>
     * </ul>
     *
     * @return 顶层扩展字段（永不为 null；空 Map = 不加任何字段）
     */
    private Map<String, Object> resolveThinkingBody() {
        String mode = normalizedThinkingMode();
        if ("disabled".equals(mode)) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        if ("enabled".equals(mode)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "enabled"));
            String effort = effectiveEffort();
            if (!effort.isEmpty()) {
                body.put("reasoning_effort", effort);
            }
            return body;
        }
        return Map.of();
    }

    /**
     * 构造（Spring 注入；多构造器场景需显式 @Autowired 指明主构造器）。
     *
     * @param chatModelFactory 动态模型工厂
     *
     * @param aiCallTraceService trace 埋点服务
     *
     * @param modelApiService 供应商运行时配置服务
     *
     * @param semanticCacheService 语义缓存服务
     *
     * @param guardRail 统一护栏策略对象
     *
     * @param guardTracker 护栏运行时状态（统计/告警/运行时覆盖）
     *
     * @param sceneRouter 场景-模型路由器
     */
    @Autowired
    public AiSpringChatGateway(DynamicChatModelFactory chatModelFactory, AiCallTraceService aiCallTraceService,
                               ModelApiService modelApiService, SemanticCacheService semanticCacheService,
                               GuardRailPolicy guardRail, GuardRailTracker guardTracker, SceneRouter sceneRouter) {
        this(chatModelFactory, aiCallTraceService, modelApiService, semanticCacheService, new HolzynTraceAdvisor(),
                guardRail, guardTracker, sceneRouter);
    }

    /**
     * 构造（可注入自定义观测 Advisor/连通性探测，测试用；护栏/路由为 null=不执行，行为回退现状）。
     *
     * @param chatModelFactory 动态模型工厂
     *
     * @param aiCallTraceService trace 埋点服务
     *
     * @param modelApiService 供应商运行时配置服务
     *
     * @param semanticCacheService 语义缓存服务
     *
     * @param advisor 观测 Advisor（可空=不挂 advisor）
     */
    AiSpringChatGateway(DynamicChatModelFactory chatModelFactory, AiCallTraceService aiCallTraceService,
                        ModelApiService modelApiService, SemanticCacheService semanticCacheService,
                        HolzynTraceAdvisor advisor) {
        this(chatModelFactory, aiCallTraceService, modelApiService, semanticCacheService, advisor, null, null, null);
    }

    /**
     * 构造（测试用：可注入自定义 Advisor 与护栏/路由对象）。
     *
     * @param chatModelFactory 动态模型工厂
     *
     * @param aiCallTraceService trace 埋点服务
     *
     * @param modelApiService 供应商运行时配置服务
     *
     * @param semanticCacheService 语义缓存服务
     *
     * @param advisor 观测 Advisor（可空）
     *
     * @param guardRail 统一护栏策略对象（可空）
     *
     * @param guardTracker 护栏运行时状态（可空）
     *
     * @param sceneRouter 场景-模型路由器（可空）
     */
    AiSpringChatGateway(DynamicChatModelFactory chatModelFactory, AiCallTraceService aiCallTraceService,
                        ModelApiService modelApiService, SemanticCacheService semanticCacheService,
                        HolzynTraceAdvisor advisor, GuardRailPolicy guardRail, GuardRailTracker guardTracker,
                        SceneRouter sceneRouter) {
        this.chatModelFactory = chatModelFactory;
        this.aiCallTraceService = aiCallTraceService;
        this.modelApiService = modelApiService;
        this.semanticCacheService = semanticCacheService;
        this.chatModelProbe = new ChatModelProbe(chatModelFactory);
        this.advisor = advisor;
        this.guardRail = guardRail;
        this.guardTracker = guardTracker;
        this.sceneRouter = sceneRouter;
    }

    /**
     * 非流式对话补全（新层入口，供 Router 兼容壳 A/B 调用；内部完成 trace 记账）。
     *
     * @param projectId 项目 ID（trace 归属；可空）
     *
     * @param config 已解析的供应商运行时配置（含解密 Key）
     *
     * @param request 对话请求（含 jsonMode）
     *
     * @return 对话补全结果（含用量统计）
     */
    public AiChatResult chatCompletion(Long projectId, ProviderConfig config, AiChatRequest request) {
        return chatCompletion(projectId, config, request, null, null);
    }

    /**
     * 非流式对话补全（带 raw 请求/响应体截取，供 detail_json 使用）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param config 供应商运行时配置
     *
     * @param request 对话请求
     *
     * @param rawRequestSink 原始请求体回调（发送前以 OpenAI 兼容 JSON 调用；可空）
     *
     * @param rawResponseSink 原始响应体回调（解析后以 OpenAI 兼容 JSON 调用；可空）
     *
     * @return 对话补全结果
     */
    public AiChatResult chatCompletion(Long projectId, ProviderConfig config, AiChatRequest request,
                                       Consumer<String> rawRequestSink, Consumer<String> rawResponseSink) {
        AiCallTrace trace = aiCallTraceService.begin(projectId);
        try {
            // （detail_json）口径对齐旧 Router：回填供应商地址/非流式标记
            trace.setBaseUrl(config.baseUrl());
            trace.setStream(false);
            // Prompt 工程：trace 回填模板版本号（按当前场景解析，供回溯/合规率统计）
            applyPromptEngineering(trace);
            // 生产路径（Router A/B 调用 3 参重载 → 本方法 sinks 为 null）时，自动接线 trace 自身的
            // raw 请求/响应体截取，保证 detail_json input.raw/output.raw 与旧路径一致落库；
            // 显式传入 sinks（测试）则优先用外部 sink。
            Consumer<String> reqSink = rawRequestSink != null ? rawRequestSink : trace::setInputRaw;
            Consumer<String> respSink = rawResponseSink != null ? rawResponseSink : trace::setOutputRaw;
            AiChatResult result = doChatCompletion(config, request, projectId, reqSink, respSink);
            // 统一护栏：空输出/输出重复检测（非流式硬拦截=视为失败，业务侧程序化兜底）
            guardCheck(trace, result.content());
            trace.fillMessages(request);
            trace.markSuccess(result.content(), result.model(), result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), result.providerId());
            // Prompt 工程：指标埋点（调用/token/缓存/版本）
            recordPromptMetrics(trace);
            logAiCall(trace);
            return result;
        } catch (Exception e) {
            trace.fillMessages(request);
            trace.markFailure(e);
            // ：失败统计上报（超时细分），供调试页护栏状态观测
            recordGuardFailure(trace, e);
            // Prompt 工程：失败调用也计入指标（合规率分母）
            recordPromptMetrics(trace);
            logAiCall(trace);
            throw e;
        } finally {
            aiCallTraceService.end(trace);
        }
    }

    /**
     * 结构化 JSON 输出补全（供决策/世界生成等 jsonMode 场景；等价于 jsonMode=true 的 chatCompletion，
     * 本地 Schema 校验·修复·定向重试兜底仍在业务域保留）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param config 供应商运行时配置
     *
     * @param request 对话请求（jsonMode 字段被强制置 true）
     *
     * @return 对话补全结果
     */
    public AiChatResult chatCompletionJson(Long projectId, ProviderConfig config, AiChatRequest request) {
        AiChatRequest jsonRequest = new AiChatRequest(request.model(), request.messages(),
                request.temperature(), request.maxTokens(), true);
        return chatCompletion(projectId, config, jsonRequest);
    }

    // ==================== AI 接入层重构：userId 入口（消费方直接注入网关调用） ====================

    /**
     * 非流式对话补全。
     * <p>承接旧 {@code AiProviderRouter.chatCompletion} 的 4 参签名（user + project + provider + request）：
     * 内部经 {@link ModelApiService#resolveProviderConfig} 解析运行时凭据 → 语义缓存命中直接复用回复
     * （记 semantic_cache_hit trace，不调 LLM）；未命中走 LLM + 成功后入缓存。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级配置）
     *
     * @param providerId API 配置主键（可空=取项目级/用户级默认配置）
     *
     * @param request 对话请求
     *
     * @return 对话补全结果（含用量统计）
     */
    public AiChatResult chatCompletion(Long userId, Long projectId, Long providerId, AiChatRequest request) {
        // 场景路由：按当前场景解析参数档位 + 供应商规则（调用方显式 providerId 优先）
        SceneRouter.Route route = routeOf();
        AiChatRequest effective = sceneRouter != null ? sceneRouter.applyToRequest(request, route) : request;
        Long effProviderId = sceneRouter != null ? sceneRouter.effectiveProviderId(providerId, route) : providerId;
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, effProviderId,
                modelRoleOf(route));
        return chatCompletionCached(userId, projectId, config, effective);
    }

    /**
     * 结构化 JSON 输出补全。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级配置）
     *
     * @param providerId API 配置主键（可空）
     *
     * @param request 对话请求（jsonMode 被强制置 true）
     *
     * @return 对话补全结果
     */
    public AiChatResult chatCompletionJson(Long userId, Long projectId, Long providerId, AiChatRequest request) {
        AiChatRequest jsonRequest = new AiChatRequest(request.model(), request.messages(),
                request.temperature(), request.maxTokens(), true);
        // 场景路由：按当前场景解析参数档位 + 供应商规则
        SceneRouter.Route route = routeOf();
        AiChatRequest effective = sceneRouter != null ? sceneRouter.applyToRequest(jsonRequest, route) : jsonRequest;
        Long effProviderId = sceneRouter != null ? sceneRouter.effectiveProviderId(providerId, route) : providerId;
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, effProviderId,
                modelRoleOf(route));
        return chatCompletionCached(userId, projectId, config, effective);
    }

    /**
     * 流式对话补全。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级配置）
     *
     * @param providerId API 配置主键（可空）
     *
     * @param request 对话请求
     *
     * @param onToken 每个增量 token 的回调（可空）
     *
     * @param onUsage 结束时的用量回调（可空）
     */
    public void chatCompletionStream(Long userId, Long projectId, Long providerId, AiChatRequest request,
                                     Consumer<String> onToken, Consumer<AiUsage> onUsage) {
        // 场景路由：流式同样应用参数档位（对话高温档）与供应商规则
        SceneRouter.Route route = routeOf();
        AiChatRequest effective = sceneRouter != null ? sceneRouter.applyToRequest(request, route) : request;
        Long effProviderId = sceneRouter != null ? sceneRouter.effectiveProviderId(providerId, route) : providerId;
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, effProviderId,
                modelRoleOf(route));
        chatCompletionStream(projectId, config, effective, onToken, onUsage);
    }

    /**
     * 当前场景对应的模型角色：决策类场景用决策模型，其余用文本模型。
     *
     * <p>判定依据是 {@code routing.category-map} 里已登记的 decision 类别（action / plan_day /
     * goal_gen / npc_crowd_decision / npc_emotion_eval 等）——这样"哪些调用算决策"只有一份清单，
     * 与温度/长度档位同源，不会出现两套场景表。</p>
     *
     * @param route 场景路由（可空=路由关闭/未启用）
     *
     * @return {@link ModelApiService#ROLE_DECISION} 或 {@link ModelApiService#ROLE_TEXT}
     */
    private static String modelRoleOf(SceneRouter.Route route) {
        return route != null && "decision".equals(route.category())
                ? ModelApiService.ROLE_DECISION : ModelApiService.ROLE_TEXT;
    }

    /**
     * 连通性测试（userId 入口， 起设置页经 ModelApiService 走 {@link ChatModelProbe}；
     * 本方法承接旧 {@code AiProviderRouter.testProvider} 签名，供业务层直接调用）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级配置）
     *
     * @param providerId API 配置主键（已入库）
     *
     * @return 测试结果 Map（connected/method/status/message/latencyMs）
     */
    public Map<String, Object> testProvider(Long userId, Long projectId, Long providerId) {
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, providerId,
                ModelApiService.ROLE_TEXT);
        return chatModelProbe.testConnection(config);
    }

    /**
     * 语义缓存感知的非流式调用（仅 userId 入口使用；3 参 config 重载保持纯 LLM+Trace 路径供测试/内部）。
     * <p>命中 → 记 semantic_cache_hit trace（不调 LLM、不写 usage_log）；未命中 → LLM 调用成功后入缓存。</p>
     *
     * @param userId 归属用户 ID（语义缓存 embedding 供应商解析）
     *
     * @param projectId 项目 ID
     *
     * @param config 已解析的供应商运行时配置
     *
     * @param request 对话请求
     *
     * @return 对话补全结果（命中=缓存回复）
     */
    private AiChatResult chatCompletionCached(Long userId, Long projectId, ProviderConfig config, AiChatRequest request) {
        // 抓修：把当前场景交给语义缓存做白名单判定——世界运行的决策/情绪/行动场景默认
        // 不参与缓存（否则命中即回放旧决策：实测深夜复用白天行动、不同 NPC 复用同一句行动文本）
        AiCallTrace current = AiCallContext.current();
        String scene = current == null ? null : current.getScene();
        SemanticCacheService.Lookup lookup = semanticCacheService.lookup(userId, projectId, config, request, scene);
        if (lookup.hit() != null) {
            return recordSemanticCacheHitTrace(projectId, config, request, lookup);
        }
        AiChatResult result = chatCompletion(projectId, config, request, null, null);
        semanticCacheService.record(lookup, request, result);
        return result;
    }

    /**
     * 语义缓存命中：记一条 cacheHit trace（复用缓存条目的 token 账本；不调 LLM → 不写 usage_log）。
     *
     * @param projectId 项目 ID（trace 归属）
     *
     * @param config 供应商运行时配置（回填 baseUrl）
     *
     * @param request 对话请求（fillMessages 素材）
     *
     * @param lookup 语义缓存查询结果（含命中内容/用量/模型）
     *
     * @return 缓存命中的调用结果
     */
    private AiChatResult recordSemanticCacheHitTrace(Long projectId, ProviderConfig config, AiChatRequest request,
                                                     SemanticCacheService.Lookup lookup) {
        AiChatResult hit = lookup.hit();
        AiCallTrace trace = aiCallTraceService.begin(projectId);
        try {
            trace.setBaseUrl(config.baseUrl());
            trace.setStream(false);
            trace.setModel(hit.model());
            trace.setProviderId(hit.providerId());
            trace.fillMessages(request);
            // raw 请求/响应体重建（与 LLM 路径同形，detail_json input.raw/output.raw 素材；
            // 思考控制字段与真实路径同来源，保证账本与线上请求体同形）
            trace.setInputRaw(buildInputRaw(hit.model(), request, resolveThinkingBody()));
            trace.setOutputRaw(buildOutputRaw(hit.content(),
                    new AiUsage(hit.promptTokens(), hit.completionTokens(),
                            hit.cacheHitTokens(), hit.cacheMissTokens()),
                    hit.model()));
            trace.setRemark("语义缓存命中（未调用 LLM）");
            trace.setSemanticCacheHit(true);
            trace.markSuccess(hit.content(), hit.model(), hit.promptTokens(), hit.completionTokens(),
                    hit.cacheHitTokens(), hit.cacheMissTokens(), hit.providerId());
            logAiCall(trace);
            return hit;
        } catch (Exception e) {
            trace.fillMessages(request);
            trace.markFailure(e);
            logAiCall(trace);
            throw e;
        } finally {
            aiCallTraceService.end(trace);
        }
    }

    /**
     * 流式对话补全。
     * <p>桥接契约与旧路径完全一致：方法<b>同步阻塞</b>至流式结束；每个增量 token 经 {@code onToken} 回调
     * （供 SSE 转发）；结束后经 {@code onUsage} 回传用量（真实 usage 优先、本地估算兜底）。
     * onToken 回调抛出的业务异常（如 SSE 连接已断开）原样上抛并终止流式——消费方零改动。</p>
     *
     * @param projectId 项目 ID（trace 归属；可空）
     *
     * @param config 已解析的供应商运行时配置（含解密 Key）
     *
     * @param request 对话请求（含 jsonMode）
     *
     * @param onToken 每个增量 token 的回调（可空）
     *
     * @param onUsage 结束时的用量回调（可空；真实/估算兜底）
     */
    public void chatCompletionStream(Long projectId, ProviderConfig config, AiChatRequest request,
                                     Consumer<String> onToken, Consumer<AiUsage> onUsage) {
        chatCompletionStream(projectId, config, request, onToken, onUsage, null, null);
    }

    /**
     * 流式对话补全。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param config 供应商运行时配置
     *
     * @param request 对话请求
     *
     * @param onToken 每个增量 token 的回调（可空）
     *
     * @param onUsage 结束时的用量回调（可空）
     *
     * @param rawRequestSink 原始请求体回调（发送前以 OpenAI 兼容 JSON 调用；可空）
     *
     * @param rawResponseSink 原始响应体回调（结束后以 OpenAI 兼容 JSON 调用；可空）
     */
    public void chatCompletionStream(Long projectId, ProviderConfig config, AiChatRequest request,
                                     Consumer<String> onToken, Consumer<AiUsage> onUsage,
                                     Consumer<String> rawRequestSink, Consumer<String> rawResponseSink) {
        AiCallTrace trace = aiCallTraceService.begin(projectId);
        try {
            // 口径对齐旧 Router 流式分支：回填供应商地址/流式标记/模型/配置主键
            trace.setBaseUrl(config.baseUrl());
            trace.setStream(true);
            trace.setModel(config.model());
            trace.setProviderId(config.id());
            // Prompt 工程：trace 回填模板版本号（按当前场景解析）
            applyPromptEngineering(trace);
            // 生产路径（Router A/B 调用 4 参重载 → sinks 为 null）时自动接线 trace 自身 raw 截取，
            // 保证 detail_json input.raw/output.raw 与旧路径一致落库；显式传入 sinks（测试）优先外部 sink
            Consumer<String> reqSink = rawRequestSink != null ? rawRequestSink : trace::setInputRaw;
            Consumer<String> respSink = rawResponseSink != null ? rawResponseSink : trace::setOutputRaw;
            StreamOutcome outcome;
            // 消费端（SSE 转发）自己抛出的异常必须原样上抛：那说明浏览器已经断了，
            // 降级重发只会把同一段话再算一遍、再把 token 吐给一个没人听的连接。
            final boolean[] consumerFailed = {false};
            Consumer<String> guardedTokens = onToken == null ? null : delta -> {
                try {
                    onToken.accept(delta);
                } catch (RuntimeException e) {
                    consumerFailed[0] = true;
                    throw e;
                }
            };
            try {
                outcome = doChatCompletionStream(config, request, projectId, guardedTokens, onUsage, reqSink, respSink);
            } catch (RuntimeException e) {
                if (consumerFailed[0] || e instanceof AiCallException && e.getMessage() != null
                        && e.getMessage().contains("被中断")) {
                    throw e;
                }
                // 本地兼容网关（llama.cpp / vLLM 的 OpenAI 兼容层）对流式的
                // 分块编码支持并不齐——实测直连 curl -N 正常、经 Spring AI 的 Flux 桥接却抛
                // OpenAioException(Request failed)。流式只是传输方式，拿不到分块就读整段：
                // 降级为非流式重发一次，把全文当单个增量回调出去（SSE 消费方无需改动）。
                log.warn("[AI] 流式调用失败，降级为非流式重发一次：model={} : {}", config.model(), e.toString());
                trace.setStream(false);
                try {
                    outcome = fallbackNonStream(config, request, projectId, onToken, onUsage, reqSink, respSink);
                } catch (RuntimeException retryError) {
                    // 两次都失败才算真失败：把两条原因都带上，否则排障只能看到一个「Request failed」
                    throw new AiCallException("AI 流式调用失败，且降级为非流式后仍失败（流式："
                            + e.getMessage() + "；非流式：" + retryError.getMessage() + "）");
                }
            }
            // 统一护栏：流式仅统计+告警不硬拦截（内容已逐 token 送达客户端，否决无意义）
            guardStreamCheck(trace, outcome.text());
            trace.fillMessages(request);
            trace.markSuccess(outcome.text(), config.model(),
                    outcome.usage().promptTokens(), outcome.usage().completionTokens(),
                    outcome.usage().cacheHitTokens(), outcome.usage().cacheMissTokens(), config.id());
            // Prompt 工程：指标埋点（调用/token/缓存/版本）
            recordPromptMetrics(trace);
            logAiCall(trace);
        } catch (Exception e) {
            trace.fillMessages(request);
            trace.markFailure(e);
            recordGuardFailure(trace, e);
            // Prompt 工程：失败调用也计入指标（合规率分母）
            recordPromptMetrics(trace);
            logAiCall(trace);
            throw e;
        } finally {
            aiCallTraceService.end(trace);
        }
    }

    /**
     * 实际执行 Spring AI 调用（不负责 trace 生命周期；由公开入口包裹记账）。
     *
     * @param config 供应商运行时配置
     *
     * @param request 对话请求
     *
     * @param rawRequestSink raw 请求体回调
     *
     * @param rawResponseSink raw 响应体回调
     *
     * @return 对话补全结果
     */
    private AiChatResult doChatCompletion(ProviderConfig config, AiChatRequest request, Long projectId,
                                          Consumer<String> rawRequestSink, Consumer<String> rawResponseSink) {
        // 统一 system 前缀：不论调用方自带什么 system，本网关都把全局 system 放在第一条，
        // 让所有场景的请求拥有逐字相同的开头（前缀缓存的第一个可复用块）。
        request = withUnifiedSystem(request, projectId != null ? projectId : currentProjectId());
        dumpFinalBody(request);
        // 模型名解析：请求优先、配置回退（与旧 Provider 同口径）
        String model = (request.model() == null || request.model().isBlank()) ? config.model() : request.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未指定模型：请在该 API 配置中填写默认模型名");
        }
        ChatModel chatModel = chatModelFactory.get(config);
        // 消息映射（system/user/assistant，未知角色按 user 兜底）
        List<Message> messages = mapMessages(request);
        if (messages.isEmpty()) {
            throw new AiCallException("对话消息不能为空");
        }
        // raw 请求体截取（detail_json input.raw；与旧 Provider 同形的 OpenAI 兼容 JSON）
        // 思考控制字段（顶层 thinking）与真实请求体同来源：一次解析、两处使用，账本不会与线上不一致
        Map<String, Object> thinkingBody = resolveThinkingBody();
        if (rawRequestSink != null) {
            rawRequestSink.accept(buildInputRaw(model, request, thinkingBody));
        }
        Prompt prompt = new Prompt(messages, buildOptions(model, request, false, thinkingBody));
        // 网关并发限流（可选， _07）：进入 HTTP 调用前占位——本地模型网关排队场景削峰；
        // 等待超时抛 AiCallException（调用方按既有失败语义重试/兜底）；未启用零开销
        GatewayConcurrencyLimiter limiter = gatewayConcurrencyLimiter;
        if (limiter != null) {
            limiter.acquire();
        }
        try {
            ChatResponse response = buildClient(chatModel).prompt(prompt).call().chatResponse();
            String content = outputText(response);
            // 用量：Advisor 共用解析（DeepSeek/OpenAI 缓存命中双兼容）+ 本地估算兜底
            AiUsage usage = HolzynTraceAdvisor.usageOf(response);
            if (usage.promptTokens() == null || usage.promptTokens() <= 0
                    || usage.completionTokens() == null || usage.completionTokens() <= 0) {
                Integer promptTokens = (usage.promptTokens() == null || usage.promptTokens() <= 0)
                        ? TokenEstimator.estimatePromptTokens(request.messages()) : usage.promptTokens();
                Integer completionTokens = (usage.completionTokens() == null || usage.completionTokens() <= 0)
                        ? TokenEstimator.estimateTokens(content) : usage.completionTokens();
                usage = new AiUsage(promptTokens, completionTokens, usage.cacheHitTokens(), usage.cacheMissTokens());
            }
            // raw 响应体截取（detail_json output.raw；重建为 choices + usage 的 OpenAI 兼容 JSON）
            if (rawResponseSink != null) {
                rawResponseSink.accept(buildOutputRaw(content, usage, model));
            }
            Integer total = (usage.promptTokens() == null || usage.completionTokens() == null)
                    ? null : usage.promptTokens() + usage.completionTokens();
            return new AiChatResult(content, model, usage.promptTokens(), usage.completionTokens(),
                    total, usage.cacheHitTokens(), usage.cacheMissTokens(), config.id());
        } catch (AiCallException e) {
            // 已包装的业务异常原样上抛（保持调用方捕获语义）
            throw e;
        } catch (Exception e) {
            // 底层框架/网络异常：包装为带中文提示的 AiCallException（与旧 Provider「AI 调用失败」契约一致，
            // 不泄漏堆栈；原始信息保留在消息内供 trace/用户排查）
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new AiCallException("AI 调用失败（Spring AI）：请检查 API 地址、Key 与网络（" + detail + "）");
        } finally {
            if (limiter != null) {
                limiter.release();
            }
        }
    }

    /**
     * 实际执行 Spring AI 流式调用（不负责 trace 生命周期；由公开入口包裹记账）。
     * <p><b>Flux → 阻塞回调桥接</b>：{@code ChatClient.stream().chatResponse()} 返回
     * {@code Flux<ChatResponse>}，本方法 {@code subscribe + CountDownLatch} 阻塞等待完成；每块增量文本
     * 回调 {@code onToken}（SSE 转发）；结束后以末块携带的 usage（{@code streamUsage(true)} 请求回传）
     * 或本地估算兜底，回调 {@code onUsage}——与旧 Provider「逐 token + 末块 usage + 缺失估算」口径一致。</p>
     * <p><b>断开/中断语义</b>：{@code onToken} 回调抛出的业务异常（如 SSE 连接已断开）→ 立即取消订阅、
     * 记录并<b>原样上抛</b>（消费方按消息内容识别，契约零改动）；Flux 底层错误（网络中断等）包装为
     * {@link AiCallException}（与旧路径「AI 流式调用失败」契约一致）；防御性超时（{@link #STREAM_AWAIT_TIMEOUT_MS}）
     * 取消订阅并上抛，避免调用线程悬挂。</p>
     *
     * @param config 供应商运行时配置
     *
     * @param request 对话请求
     *
     * @param onToken 每个增量 token 的回调（可空）
     *
     * @param onUsage 结束时的用量回调（可空）
     *
     * @param rawRequestSink raw 请求体回调
     *
     * @param rawResponseSink raw 响应体回调（结束后以重建 OpenAI 兼容 JSON 调用）
     *
     * @return 流式结果（全文 + 用量）
     */
    private StreamOutcome doChatCompletionStream(ProviderConfig config, AiChatRequest request, Long projectId,
                                                 Consumer<String> onToken, Consumer<AiUsage> onUsage,
                                                 Consumer<String> rawRequestSink, Consumer<String> rawResponseSink) {
        request = withUnifiedSystem(request, projectId != null ? projectId : currentProjectId());
        dumpFinalBody(request);
        // 模型名解析：请求优先、配置回退（与旧 Provider 同口径）
        String model = (request.model() == null || request.model().isBlank()) ? config.model() : request.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未指定模型：请在该 API 配置中填写默认模型名");
        }
        ChatModel chatModel = chatModelFactory.get(config);
        List<Message> messages = mapMessages(request);
        if (messages.isEmpty()) {
            throw new AiCallException("对话消息不能为空");
        }
        // raw 请求体截取（detail_json input.raw；与旧 Provider 同形）
        // 思考控制字段（顶层 thinking）与真实请求体同来源（流式同样需要：它才是隐藏思考的最大来源）
        Map<String, Object> thinkingBody = resolveThinkingBody();
        if (rawRequestSink != null) {
            rawRequestSink.accept(buildInputRaw(model, request, thinkingBody));
        }
        Prompt prompt = new Prompt(messages, buildOptions(model, request, true, thinkingBody));
        // ---- Flux → 阻塞回调桥接 ----
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean businessFailure = new AtomicBoolean();
        StringBuilder full = new StringBuilder();
        AtomicReference<AiUsage> lastUsage = new AtomicReference<>();
        // 网关并发限流（可选， _07）：流式 HTTP 连接在订阅时建立，占位覆盖订阅→结束全程；
        // 所有退出路径（成功/业务异常/超时/中断）经 finally 释放
        GatewayConcurrencyLimiter limiter = gatewayConcurrencyLimiter;
        if (limiter != null) {
            limiter.acquire();
        }
        try {
            Flux<ChatResponse> flux = buildClient(chatModel).prompt(prompt).stream().chatResponse();
            flux.doOnSubscribe(subscriptionRef::set)
                    .subscribe(chunk -> {
                        try {
                            // 增量文本：usage-only 末块（results 为空）不产生 delta
                            String delta = deltaText(chunk);
                            if (delta != null && !delta.isBlank()) {
                                full.append(delta);
                                if (onToken != null) {
                                    onToken.accept(delta);
                                }
                            }
                            // 用量：通常末块 metadata 携带真实 usage（streamUsage(true) 请求回传）
                            AiUsage u = HolzynTraceAdvisor.usageOf(chunk);
                            if (u != null && u.promptTokens() != null && u.completionTokens() != null) {
                                lastUsage.set(u);
                            }
                        } catch (Throwable t) {
                            // 回调业务异常（SSE 断开等）：取消订阅立即终止 + 记录并原样上抛
                            businessFailure.set(true);
                            failure.set(t);
                            Subscription s = subscriptionRef.get();
                            if (s != null) {
                                s.cancel();
                            }
                            latch.countDown();
                        }
                    }, err -> {
                        // Flux 底层错误（网络中断/供应商错误等）
                        failure.set(err);
                        latch.countDown();
                    }, latch::countDown);
            boolean finished;
            try {
                finished = latch.await(STREAM_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Subscription s = subscriptionRef.get();
                if (s != null) {
                    s.cancel();
                }
                Thread.currentThread().interrupt();
                throw new AiCallException("AI 流式调用被中断");
            }
            if (!finished) {
                Subscription s = subscriptionRef.get();
                if (s != null) {
                    s.cancel();
                }
                throw new AiCallException("AI 流式调用超时（" + (STREAM_AWAIT_TIMEOUT_MS / 60_000L)
                        + " 分钟）：请检查网络或模型响应速度");
            }
            Throwable t = failure.get();
            if (t != null) {
                // 业务异常（回调抛出，如 SSE 断开）原样上抛；其余（Flux 底层错误）包装 AiCallException
                if (businessFailure.get() && t instanceof RuntimeException re) {
                    throw re;
                }
                String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                throw new AiCallException("AI 流式调用失败：请检查 API 地址、Key 与网络（" + detail + "）");
            }
            // ---- 用量：末块 usage 优先、本地估算兜底（与旧 Provider 口径一致） ----
            AiUsage usage = lastUsage.get();
            if (usage == null || usage.promptTokens() == null || usage.promptTokens() <= 0
                    || usage.completionTokens() == null || usage.completionTokens() <= 0) {
                Integer promptTokens = (usage == null || usage.promptTokens() == null || usage.promptTokens() <= 0)
                        ? TokenEstimator.estimatePromptTokens(request.messages()) : usage.promptTokens();
                Integer completionTokens = (usage == null || usage.completionTokens() == null || usage.completionTokens() <= 0)
                        ? TokenEstimator.estimateTokens(full.toString()) : usage.completionTokens();
                usage = new AiUsage(promptTokens, completionTokens,
                        usage == null || usage.cacheHitTokens() == null ? 0 : usage.cacheHitTokens(),
                        usage == null || usage.cacheMissTokens() == null ? 0 : usage.cacheMissTokens());
            } else {
                // 口径对齐旧 Provider 流式（usage[2]/[3] 恒非 null 默认 0）：cache 字段缺失记 0，
                // 避免消费方 onUsage 内 int[] 拆箱 NPE
                usage = new AiUsage(usage.promptTokens(), usage.completionTokens(),
                        usage.cacheHitTokens() == null ? 0 : usage.cacheHitTokens(),
                        usage.cacheMissTokens() == null ? 0 : usage.cacheMissTokens());
            }
            if (onUsage != null) {
                onUsage.accept(usage);
            }
            // raw 响应体截取
            if (rawResponseSink != null) {
                rawResponseSink.accept(buildOutputRaw(full.toString(), usage, model));
            }
            return new StreamOutcome(full.toString(), usage);
        } finally {
            if (limiter != null) {
                limiter.release();
            }
        }
    }

    /**
     * 流式调用结果（全文 + 用量），供网关公开入口记账。
     *
     * @param text 流式拼接全文
     *
     * @param usage 用量（真实或本地估算兜底）
     */
    private record StreamOutcome(String text, AiUsage usage) {
    }

    /**
     * 流式失败后的非流式兜底。
     *
     * <p>拿到整段文本后<b>一次性</b>回调 {@code onToken}：SSE 消费方本来就是「收到增量就转发」，
     * 一次给全文与分十次给是同一份输出，只是没有逐字效果——比起整条对话直接失败，这显然更好。</p>
     *
     * @param config          供应商运行时配置
     * @param request         对话请求
     * @param onToken         增量回调（可空）
     * @param onUsage         用量回调（可空）
     * @param rawRequestSink  raw 请求体回调（可空）
     * @param rawResponseSink raw 响应体回调（可空）
     * @return 流式结果（形态与真流式一致）
     */
    private StreamOutcome fallbackNonStream(ProviderConfig config, AiChatRequest request, Long projectId,
                                            Consumer<String> onToken, Consumer<AiUsage> onUsage,
                                            Consumer<String> rawRequestSink, Consumer<String> rawResponseSink) {
        AiChatResult result = doChatCompletion(config, request, projectId, rawRequestSink, rawResponseSink);
        String text = result.content() == null ? "" : result.content();
        if (onToken != null && !text.isEmpty()) {
            onToken.accept(text);
        }
        Integer promptTokens = result.promptTokens();
        if (promptTokens == null || promptTokens <= 0) {
            promptTokens = TokenEstimator.estimatePromptTokens(request.messages());
        }
        Integer completionTokens = result.completionTokens();
        if (completionTokens == null || completionTokens <= 0) {
            completionTokens = TokenEstimator.estimateTokens(text);
        }
        AiUsage usage = new AiUsage(promptTokens, completionTokens, 0, 0);
        if (onUsage != null) {
            onUsage.accept(usage);
        }
        return new StreamOutcome(text, usage);
    }

    /**
     * 每请求 options 组装（非流式/流式共用）：温度/maxTokens 非空才设；jsonMode →
     * response_format=json_object（不迁移严格 schema）；流式额外开启 {@code streamUsage(true)}
     * （等价旧路径 {@code stream_options.include_usage}，请求供应商回传末块真实用量）；
     * {@code extraBody} 非空时经 Spring AI → openai-java {@code additionalBodyProperties} 落到<b>请求体顶层</b>
     * （起用于下发思考控制字段 thinking / reasoning_effort）。
     *
     * @param model 实际模型名
     *
     * @param request 对话请求
     *
     * @param stream 是否流式调用
     *
     * @param extraBody 需追加到请求体顶层的字段（可空/空 Map = 不加；见 {@link #resolveThinkingBody()}）
     */
    private static OpenAiChatOptions buildOptions(String model, AiChatRequest request, boolean stream,
                                                 Map<String, Object> extraBody) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(model);
        if (request.temperature() != null) {
            optionsBuilder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            optionsBuilder.maxTokens(request.maxTokens());
        }
        if (request.jsonMode()) {
            optionsBuilder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        if (stream) {
            optionsBuilder.streamUsage(true);
        }
        if (extraBody != null && !extraBody.isEmpty()) {
            optionsBuilder.extraBody(extraBody);
        }
        return optionsBuilder.build();
    }

    /**
     * 消息映射：AiChatRequest.ChatMessage → Spring AI Message（system/user/assistant，未知角色按 user 兜底）。
     *
     * @param request 对话请求
     *
     * @return Spring AI 消息序列（可能为空，由调用方判空）
     */
    private static List<Message> mapMessages(AiChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (AiChatRequest.ChatMessage m : request.messages()) {
                if (m == null || m.content() == null) {
                    continue;
                }
                String role = m.role() == null ? "" : m.role();
                switch (role) {
                    case "system" -> messages.add(new SystemMessage(m.content()));
                    case "assistant" -> messages.add(new AssistantMessage(m.content()));
                    default -> messages.add(new UserMessage(m.content()));
                }
            }
        }
        return messages;
    }

    /**
     * 构建 ChatClient 并挂观测 Advisor（观测不参与记账；记账由本网关主体完成；非流式/流式共用）。
     *
     * @param chatModel 动态工厂取出的模型
     *
     * @return 已挂 Advisor 的 ChatClient
     */
    private ChatClient buildClient(ChatModel chatModel) {
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
        if (advisor != null) {
            clientBuilder.defaultAdvisors(advisor);
        }
        return clientBuilder.build();
    }

    /**
     * 提取流式增量文本（当前块的 assistant 文本；usage-only 末块/缺失返回 null——调用方判空跳过）。
     *
     * @param chunk 流式响应块（可空）
     *
     * @return 增量文本（可能 null）
     */
    private static String deltaText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? null : text;
    }

    /**
     * 提取输出全文（首个结果的 assistant 文本；缺失/空输出返回空串——调用方按内容判空）。
     *
     * @param response ChatModel 响应（可空）
     *
     * @return 输出文本
     */
    private static String outputText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 重建 raw 请求体 JSON（与旧 Provider 发送体同形：model/messages/temperature/response_format/max_tokens；
     * 起追加思考控制字段 thinking / reasoning_effort）。
     * <p>⚠️ 本方法只用于 trace 的 detail_json.input.raw（不参与真实发送），因此<b>必须</b>与
     * {@link #buildOptions} 使用同一份 {@code extraBody}（调用方同一次 {@link #resolveThinkingBody()} 结果），
     * 否则会出现「账本记着关了思考、线上其实还在思考」的假账——这正是本轮要消灭的观测盲区。</p>
     *
     * @param model 实际模型名
     *
     * @param request 对话请求
     *
     * @param extraBody 需追加到请求体顶层的字段（可空/空 Map = 不加）
     *
     * @return 请求体 JSON 字符串（组装失败返回 null）
     */
    private static String buildInputRaw(String model, AiChatRequest request, Map<String, Object> extraBody) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            ArrayNode msgs = body.putArray("messages");
            if (request.messages() != null) {
                for (AiChatRequest.ChatMessage m : request.messages()) {
                    if (m == null) {
                        continue;
                    }
                    msgs.addObject().put("role", m.role() == null ? "" : m.role())
                            .put("content", m.content() == null ? "" : m.content());
                }
            }
            if (request.temperature() != null) {
                body.put("temperature", request.temperature());
            }
            if (request.jsonMode()) {
                body.putObject("response_format").put("type", "json_object");
            }
            if (request.maxTokens() != null) {
                body.put("max_tokens", request.maxTokens());
            }
            // 顶层扩展字段（思考控制）：与真实请求体同形（嵌套结构经 Jackson 原样落盘，如 {"thinking":{"type":"disabled"}}）
            if (extraBody != null && !extraBody.isEmpty()) {
                for (Map.Entry<String, Object> entry : extraBody.entrySet()) {
                    body.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
                }
            }
            return body.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重建 raw 响应体 JSON（与旧 Provider 解析体同形：choices[0].message + usage[含缓存命中]）。
     *
     * @param content 输出全文
     *
     * @param usage 用量
     *
     * @param model 实际模型名
     *
     * @return 响应体 JSON 字符串（组装失败返回 null）
     */
    private static String buildOutputRaw(String content, AiUsage usage, String model) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            ArrayNode choices = root.putArray("choices");
            choices.addObject().putObject("message").put("role", "assistant").put("content", content);
            ObjectNode usageNode = root.putObject("usage");
            putInt(usageNode, "prompt_tokens", usage.promptTokens());
            putInt(usageNode, "completion_tokens", usage.completionTokens());
            if (usage.promptTokens() != null && usage.completionTokens() != null) {
                usageNode.put("total_tokens", usage.promptTokens() + usage.completionTokens());
            }
            putInt(usageNode, "prompt_cache_hit_tokens", usage.cacheHitTokens());
            putInt(usageNode, "prompt_cache_miss_tokens", usage.cacheMissTokens());
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 写 int 字段（null → 不写） */
    private static void putInt(ObjectNode node, String name, Integer v) {
        if (v != null) {
            node.put(name, v);
        }
    }

    // ==================== 评估与工程 /：统一护栏 + 场景路由执行点 ====================

    /**
     * 解析当前线程的场景路由：从 {@link AiCallContext} 读当前 scene；
     * 路由关闭/无路由器时返回 null（调用方原样调用）。
     *
     * @return 路由档位（可空）
     */
    private SceneRouter.Route routeOf() {
        if (sceneRouter == null || !sceneRouter.enabled()) {
            return null;
        }
        AiCallTrace cur = AiCallContext.current();
        String scene = cur == null ? null : cur.getScene();
        return sceneRouter.resolve(scene);
    }

    /**
     * 非流式护栏检测：空输出 / 输出重复（复读机）——命中即视为调用失败：
     * 记录统计 + 告警 + 标记降级，抛 {@link AiCallException} 由业务侧既有失败路径
     * （重试上限经护栏解析 / 程序化兜底）处置，世界推进永不因复读/空输出卡死。
     *
     * @param trace 本次调用 trace（scene 来源）
     *
     * @param content 输出文本
     */
    private void guardCheck(AiCallTrace trace, String content) {
        if (guardRail == null || guardTracker == null || !guardRail.enabled()) {
            return;
        }
        String scene = trace == null || trace.getScene() == null ? "chat" : trace.getScene();
        if (guardRail.isBlankOutput(scene, content)) {
            guardTracker.recordBlankOutput(scene);
            guardTracker.recordDegraded(scene);
            guardTracker.alert(scene, "blank_output",
                    "AI 输出为空（护栏拦截）：该场景输出低于长度阈值，按失败处置走程序化兜底");
            // Prompt 工程：格式合规率埋点（空输出=不合规）
            if (promptMetrics != null) {
                promptMetrics.recordBlank(scene);
                promptMetrics.recordJsonFail(scene, "AI 输出为空（护栏拦截）");
            }
            throw new AiCallException("AI 输出为空（护栏拦截）：模型未返回有效内容，请重试或检查模型状态");
        }
        if (guardRail.isDuplicate(scene, AiCallContext.currentTraceId(), content)) {
            guardTracker.recordDegraded(scene);
            guardTracker.alert(scene, "duplicate_guard",
                    "AI 输出重复（护栏拦截）：同一链路连续输出相同，判定复读机，按失败处置停止重试");
            // Prompt 工程：格式合规率埋点（复读=不合规）
            if (promptMetrics != null) {
                promptMetrics.recordJsonFail(scene, "AI 输出重复（护栏拦截，复读机）");
            }
            throw new AiCallException("AI 输出重复（护栏拦截）：模型输出陷入复读，已按失败处置");
        }
        // 护栏语义复核（guard_semantic 默认关）：决策模型 boolean「输出是否越界」
        // （空泛/复读/答非所问/违背任务要求）→ 越界按失败处置走程序化兜底（与空/复读同语义）
        if (decisionGuardSemanticCheck != null) {
            try {
                Boolean outOfBounds = decisionGuardSemanticCheck.check(
                        trace == null ? null : trace.getProjectId(), scene, content);
                if (Boolean.TRUE.equals(outOfBounds)) {
                    guardTracker.recordDegraded(scene);
                    guardTracker.alert(scene, "semantic_guard",
                            "AI 输出语义越界（决策模型 护栏复核拦截）：空泛/复读/答非所问，按失败处置");
                    if (promptMetrics != null) {
                        promptMetrics.recordJsonFail(scene, "AI 输出语义越界（决策模型 护栏复核）");
                    }
                    throw new AiCallException("AI 输出语义越界（决策模型 护栏复核）：内容空泛/答非所问，请重试");
                }
            } catch (com.holzyn.actor.ai.AiCallException e) {
                throw e;
            } catch (Exception e) {
                log.warn("[护栏] 决策模型 语义复核异常（放行，零阻断）: scene={} : {}", scene, e.getMessage());
            }
        }
    }

    /**
     * 流式护栏检测：仅统计 + 告警，不硬拦截（内容已逐 token 送达客户端）。
     *
     * @param trace 本次调用 trace
     *
     * @param content 流式拼接全文
     */
    private void guardStreamCheck(AiCallTrace trace, String content) {
        if (guardRail == null || guardTracker == null || !guardRail.enabled()) {
            return;
        }
        String scene = trace == null || trace.getScene() == null ? "chat" : trace.getScene();
        if (guardRail.isBlankOutput(scene, content)) {
            guardTracker.recordBlankOutput(scene);
            guardTracker.alert(scene, "blank_output",
                    "AI 流式输出为空（仅告警，内容已送达客户端）");
            return;
        }
        if (guardRail.isDuplicate(scene, AiCallContext.currentTraceId(), content)) {
            guardTracker.recordDuplicate(scene);
            guardTracker.alert(scene, "duplicate_guard",
                    "AI 流式输出重复（仅告警）：同一链路连续输出相同，traceId="
                            + (AiCallContext.currentTraceId() == null ? "-" : AiCallContext.currentTraceId()));
        }
    }

    /**
     * Prompt 工程：trace 回填模板版本号（按当前场景从 {@code PromptSpecRegistry} 解析；
     * 无当前 trace 场景时跳过，零副作用）。
     *
     * @param trace 本次调用 trace
     */
    private void applyPromptEngineering(AiCallTrace trace) {
        if (trace == null) {
            return;
        }
        AiCallTrace cur = AiCallContext.current();
        String scene = cur == null ? null : cur.getScene();
        int version = com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry.versionOf(scene);
        trace.setPromptVersion(version);
    }

    /**
     * Prompt 工程：指标埋点（调用次数/token 消耗/前缀缓存命中/模板版本；供调试页
     * 「Prompt 工程」页的格式合规率与缓存效率统计；promptMetrics 未装配时静默跳过）。
     *
     * @param trace 本次调用 trace
     */
    private void recordPromptMetrics(AiCallTrace trace) {
        if (promptMetrics == null || trace == null) {
            return;
        }
        String scene = trace.getScene() == null ? "chat" : trace.getScene();
        promptMetrics.recordCall(scene, trace.getTokenIn(), trace.getTokenOut(),
                trace.getCacheHit(), trace.getCacheMiss());
        promptMetrics.recordVersion(scene, trace.getPromptVersion() == null ? 0 : trace.getPromptVersion());
    }

    /**
     * 失败统计上报：网关 catch 点统一记录失败/超时计数，供调试页护栏状态观测。
     *
     * @param trace 本次调用 trace（scene 来源）
     *
     * @param e 失败异常（分类判定超时）
     */
    private void recordGuardFailure(AiCallTrace trace, Exception e) {
        if (guardRail == null || guardTracker == null || !guardRail.enabled()) {
            return;
        }
        String scene = trace == null || trace.getScene() == null ? "chat" : trace.getScene();
        guardTracker.recordFailure(scene);
        if (guardRail.classify(e) == GuardRailPolicy.FailureKind.TIMEOUT) {
            guardTracker.recordTimeout(scene);
            guardTracker.alert(scene, "timeout", "AI 调用超时（已由 HTTP 看门狗兜底断开）");
        }
    }

    /**
     * 输出本次 AI 调用的实时日志（与旧 Router.logAiCall 完全同格式，保证新旧路径日志口径一致）：
     * INFO 摘要进控制台与调试缓冲；DEBUG 全量输入/输出仅进调试缓冲。
     *
     * @param trace 本次调用的 trace
     */
    private void logAiCall(AiCallTrace trace) {
        if (trace == null) {
            return;
        }
        String scene = trace.getScene() == null ? "chat" : trace.getScene();
        Long projectId = trace.getProjectId();
        Long charId = trace.getCharacterId();
        String model = trace.getModel() == null ? "-" : trace.getModel();
        String status = trace.isDegraded() ? "degraded" : trace.getStatus();
        String err = trace.getErrorMessage() == null ? "" : " 错误=" + trace.getErrorMessage();
        log.info("[AI调用] scene={} project={} char={} model={} 状态={} tokens={}/{} 耗时={}ms{}",
                scene, projectId, charId, model, status,
                trace.getTokenIn(), trace.getTokenOut(), trace.getDurationMs(), err);
        String sys = trace.getSystemPrompt() == null ? "" : trace.getSystemPrompt();
        String usr = trace.getUserPrompt() == null ? "" : trace.getUserPrompt();
        String resp = trace.getResponse() == null ? "" : trace.getResponse();
        log.debug("[AI调用] scene={} project={} char={} model={} 状态={}\n"
                        + "── 系统提示({}字) ──\n{}\n"
                        + "── 用户输入({}字) ──\n{}\n"
                        + "── 输出({}字) ──\n{}",
                scene, projectId, charId, model, status,
                sys.length(), sys, usr.length(), usr, resp.length(), resp);
    }

    // ==================== 统一 system 前缀 ====================

    /**
     * 全局统一 system 文本（{@code prompts/_shared/system.md}），类加载时读一次。
     *
     * <p><b>为什么在网关兜这一层</b>：模板服务只覆盖"走模板"的调用；实测仍有 60+ 处调用方自己拼
     * system（大量是「你只输出严格的 JSON，不输出任何其他文字。」这类一行字，或各自的世界观/人设），
     * 于是每个场景的第一条消息都不一样，前缀缓存从第一个 token 就开始分叉——同一世界跑两小时，
     * 命中率被这些短命前缀拖到 60% 上下。网关是所有 AI 调用的唯一收口，在第一条消息前统一插入
     * 同一个 system，等于给全部场景造出同一段最长公共前缀；调用方原有 system 保留、只是排到它后面。
     */
    private static final String UNIFIED_SYSTEM = loadUnifiedSystem();

    /** 调用方自带 system 指令在 user 末尾的挂载标记（须逐字稳定，避免污染共享前缀）。 */
    private static final String LEGACY_SYSTEM_MARK = "\n\n【本场景特别约定】\n";

    /**
     * 全局共享契约（{@code prompts/_shared/contract.md}），类加载时读一次。
     *
     * <p><b>为什么还要在网关补这一层</b>：模板链路由 {@code PromptTemplateService.render()} 前置契约，
     * 但仓库里还有一批服务自己拼 messages（对话、记忆、环境、部分决策类），它们的 user 开头各不相同，
     * 与模板链路之间只能共享 system 的 ~200 token。网关在 user 开头补上同一段契约后，
     * <b>全部场景</b>的前若干个 token 逐字一致——实测命中量从 225 token 提升到 1200+ token。</p>
     */
    private static final String SHARED_CONTRACT = loadSharedContract();

    /** 契约正文的起手片段（用于判断调用方的 user 是否已经自带契约，避免重复前置）。 */
    private static final String CONTRACT_HEAD = "# 世界内产出通用契约";

    /**
     * 读共享契约（缺失时返回空串：网关不因为一个可选资源缺失而拒绝服务）。
     *
     * @return 契约文本（已 trim）
     */
    private static String loadSharedContract() {
        try {
            String s = PromptResources.shared("contract");
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读统一 system（缺失时返回空串：网关不因为一个可选资源缺失而拒绝服务）。
     *
     * @return 统一 system 文本
     */
    private static String loadUnifiedSystem() {
        try {
            String s = PromptResources.system("_shared");
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前调用归属的项目 ID（取自主线程绑定的 trace；无 trace 时为空）。
     *
     * <p>为什么要绕这一层：常驻前缀是按世界取的（名册/地点/民情都来自世界库），
     * 而 {@code doChatCompletion} 是私有实现、签名里没有 projectId；trace 在
     * {@code chatCompletion(projectId, ...)} 入口已经绑定，读它才不会为了一个参数
     * 去改四个私有方法签名（也避免漏改某一处导致前缀静默丢失）。</p>
     *
     * @return 项目 ID（可空）
     */
    private static Long currentProjectId() {
        AiCallTrace trace = AiCallContext.current();
        return trace == null ? null : trace.getProjectId();
    }

    /**
     * 统一 system 前缀：system 逐字恒定，调用方自带的 system 指令改挂到 user 末尾。
     *
     * <p>为什么不全并进 system：前缀缓存从第一个 token 开始逐字节比对，system 里只要混入一句
     * 场景说明（「只输出严格 JSON」之类），整条前缀立刻分叉，命中量就掉到 system 的共同部分。
     * 实测：同场景连调命中 2303 token，换场景后只剩 225 token。把场景说明挪到 user 末尾后，
     * 所有调用的开头都是「统一 system + 契约 + 世界设定」，跨场景也能复用。</p>
     *
     * @param request 原始请求
     *
     * @return 带统一 system 的请求（原 request 不变）
     */
    private AiChatRequest withUnifiedSystem(AiChatRequest request, Long projectId) {
        if (request == null || UNIFIED_SYSTEM.isEmpty()) {
            return request;
        }
        List<AiChatRequest.ChatMessage> src = request.messages();
        if (src == null || src.isEmpty()) {
            return request;
        }
        // llama.cpp 的 chat template 只接受一条 system 消息（多条直接 500），故 system 恒为一条。
        StringBuilder legacy = new StringBuilder();
        List<AiChatRequest.ChatMessage> rest = new ArrayList<>(src.size());
        for (AiChatRequest.ChatMessage m : src) {
            if (m != null && "system".equalsIgnoreCase(m.role())) {
                String c = m.content() == null ? "" : m.content().trim();
                if (!c.isEmpty() && !UNIFIED_SYSTEM.equals(c) && !c.contains(UNIFIED_SYSTEM)
                        && legacy.indexOf(c) < 0) {
                    if (legacy.length() > 0) {
                        legacy.append("\n\n");
                    }
                    legacy.append(c);
                }
            } else {
                rest.add(m);
            }
        }
        if (rest.isEmpty()) {
            return request;
        }
        if (legacy.length() > 0) {
            // 场景说明后置：既保留调用方的语气/格式约束，又不污染所有调用共享的前缀。
            int at = -1;
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i) != null && "user".equalsIgnoreCase(rest.get(i).role())) {
                    at = i;
                }
            }
            if (at < 0) {
                at = rest.size() - 1;
            }
            AiChatRequest.ChatMessage tail = rest.get(at);
            String content = (tail.content() == null ? "" : tail.content()) + LEGACY_SYSTEM_MARK + legacy;
            rest.set(at, new AiChatRequest.ChatMessage(tail.role(), content));
        }
        // 常驻段（契约 + 世界常驻前缀）铸成第一条 user：稳定内容在前，历史台词与本次情境在后。
        String stable = stablePrefix(projectId);
        String staticBlock = staticBlock(stable);
        if (!staticBlock.isEmpty()) {
            stripInjectedStatic(rest, stable);
        }
        List<AiChatRequest.ChatMessage> out = new ArrayList<>(rest.size() + 2);
        out.add(new AiChatRequest.ChatMessage("system", UNIFIED_SYSTEM));
        if (!staticBlock.isEmpty()) {
            // 第一条非 system 就是 user 时直接「并进同一条消息」：两个连续 user 段虽然合法，
            // 但会把一条本可读的提示拆成两截（实测观感上像提示被切成两半），且多一段消息边界
            // 对缓存毫无增益——缓存只认字节前缀。
            int firstUser = -1;
            for (int i = 0; i < rest.size(); i++) {
                AiChatRequest.ChatMessage m = rest.get(i);
                if (m != null && "user".equalsIgnoreCase(m.role())) {
                    firstUser = i;
                    break;
                }
            }
            if (firstUser == 0) {
                AiChatRequest.ChatMessage head = rest.get(0);
                String merged = staticBlock + "\n\n" + (head.content() == null ? "" : head.content());
                rest.set(0, new AiChatRequest.ChatMessage(head.role(), merged));
            } else {
                // 前面排着 assistant 台词（对话链路）：常驻段必须插到台词之前，否则那几行台词
                // 会把可缓存前缀压缩到只剩 system——这正是本轮「同一世界跑满一天仍 30%」的直接原因。
                out.add(new AiChatRequest.ChatMessage("user", staticBlock));
            }
        }
        out.addAll(rest);
        return new AiChatRequest(request.model(), out, request.temperature(), request.maxTokens(),
                request.jsonMode());
    }

    /**
     * 本世界的常驻前缀（人物名册 + 地点 + 民情常识），失败即空串。
     *
     * <p>由 {@code AiContextInjector#stablePrefix} 生成并自带 5 分钟缓存——同一游戏日里逐字相同，
     * 这正是缓存能跨场景复用的前提。注入器未装配（单测）或项目为空时返回空串，调用方退化为「只有契约」。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 常驻前缀（无则空串）
     */
    private String stablePrefix(Long projectId) {
        if (!stablePromptEnabled || aiContextInjector == null || projectId == null) {
            return "";
        }
        try {
            String s = aiContextInjector.stablePrefix(projectId);
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            log.debug("[AI-Call] 常驻前缀生成失败（降级为只有契约）：project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /**
     * 拼「契约 + 常驻前缀」这一整块（两部分都为空时返回空串）。
     *
     * @param stable 常驻前缀（可空）
     *
     * @return 常驻段文本
     */
    private static String staticBlock(String stable) {
        if (SHARED_CONTRACT.isEmpty()) {
            return stable == null ? "" : stable;
        }
        if (stable == null || stable.isEmpty()) {
            return SHARED_CONTRACT;
        }
        return SHARED_CONTRACT + "\n\n" + stable;
    }

    /**
     * 把各调用方自己拼进去的「契约 / 常驻前缀」从原消息里删掉，避免同一段文字出现两遍。
     *
     * <p>为什么必须删：模板链路（{@code PromptTemplateService}）本来就把契约与常驻前缀写在自己那条
     * user 消息里；现在网关统一前置同一份，若不删就会重复注入——既浪费 token，又让模型看到两份措辞
     * 相同的纪律（实测出现「契约重复多次」的观感）。按<b>整段原文</b>匹配替换，不做模糊匹配：
     * 匹配不上的情况最坏只是重复一段，不会误删业务内容。</p>
     *
     * @param messages 已去掉 system 的消息列表（原地修改）
     *
     * @param stable 常驻前缀原文（可空）
     */
    private void stripInjectedStatic(List<AiChatRequest.ChatMessage> messages, String stable) {
        for (int i = 0; i < messages.size(); i++) {
            AiChatRequest.ChatMessage m = messages.get(i);
            if (m == null || m.content() == null || m.content().isEmpty()) {
                continue;
            }
            String c = m.content();
            String orig = c;
            if (!SHARED_CONTRACT.isEmpty() && c.contains(SHARED_CONTRACT)) {
                c = c.replace(SHARED_CONTRACT, "");
            }
            if (stable != null && !stable.isEmpty() && c.contains(stable)) {
                c = c.replace(stable, "");
            }
            if (!c.equals(orig)) {
                messages.set(i, new AiChatRequest.ChatMessage(m.role(), c.stripLeading()));
            }
        }
    }

    // ==================== 最终请求体落盘（缓存前缀诊断） ====================

    /**
     * 是否把「收口后真正发出去」的请求体逐条落盘。
     *
     * <p>为什么需要：业务日志里的 {@code input.raw} 记的是<b>收口前</b>的原始请求，
     * 与真正发给模型的消息并不是同一份。排查「跨场景只命中 225 token」这类前缀分叉时，
     * 必须比对真正发送的字节，否则只能靠猜。</p>
     *
     * <p>默认开启：只保留最近 {@link #FINAL_BODY_KEEP} 条（约 2~3 MB），用于事后复盘
     * 「真正发出去的字节」；关闭方式 {@code holzyn.actor.debug.ai-log.final-body=false}
     * （环境变量按松弛绑定写作 {@code HOLOZYN_ACTOR_DEBUG_AILOG_FINALBODY=false}，连字符需删除）。</p>
     */
    @Value("${holzyn.actor.debug.ai-log.final-body:true}")
    private boolean finalBodyEnabled = true;

    /** 数据根目录（落盘位置 = {@code <data-dir>/ai-final-bodies/}）。 */
    @Value("${holzyn.actor.data-dir:./data}")
    private String finalBodyDataDir = "./data";

    /** 落盘序号：文件名零填充，字典序即发送顺序。 */
    private static final java.util.concurrent.atomic.AtomicLong FINAL_BODY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 只保留最近 N 条（长期跑不至于写爆磁盘）。 */
    private static final int FINAL_BODY_KEEP = 300;

    /** 诊断用序列化器（与业务序列化无关，避免互相影响）。 */
    private static final ObjectMapper FINAL_BODY_MAPPER = new ObjectMapper();

    /**
     * 把最终请求体写一条 JSON（含 system/user 原文与采样参数），并裁剪保留条数。
     *
     * @param request 收口后的请求（system 已统一、契约已前置、场景说明已后置）
     */
    private void dumpFinalBody(AiChatRequest request) {
        if (!finalBodyEnabled || request == null || request.messages() == null) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(finalBodyDataDir, "ai-final-bodies");
            java.nio.file.Files.createDirectories(dir);
            long seq = FINAL_BODY_SEQ.incrementAndGet();
            String scene = "";
            AiCallTrace trace = AiCallContext.current();
            if (trace != null && trace.getScene() != null) {
                scene = trace.getScene();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("seq", seq);
            body.put("time", java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
            body.put("scene", scene);
            body.put("model", request.model());
            body.put("temperature", request.temperature());
            body.put("max_tokens", request.maxTokens());
            body.put("json_mode", request.jsonMode());
            List<Map<String, String>> msgs = new ArrayList<>(request.messages().size());
            for (AiChatRequest.ChatMessage m : request.messages()) {
                Map<String, String> one = new LinkedHashMap<>();
                one.put("role", m == null ? "" : String.valueOf(m.role()));
                one.put("content", m == null || m.content() == null ? "" : m.content());
                msgs.add(one);
            }
            body.put("messages", msgs);
            String safeScene = scene.replaceAll("[^A-Za-z0-9_\\-]", "_");
            java.nio.file.Path file = dir.resolve(String.format("final-%06d-%s.json", seq, safeScene));
            java.nio.file.Files.writeString(file, FINAL_BODY_MAPPER.writeValueAsString(body),
                    java.nio.charset.StandardCharsets.UTF_8);
            pruneFinalBodies(dir);
        } catch (Exception e) {
            // 诊断落盘失败绝不影响真实调用
            log.debug("[AI-Call] 最终请求体落盘失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 裁剪历史文件：按文件名升序删除，只保留最近 {@link #FINAL_BODY_KEEP} 条。
     *
     * @param dir 落盘目录
     */
    private void pruneFinalBodies(java.nio.file.Path dir) {
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            List<java.nio.file.Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            int overflow = files.size() - FINAL_BODY_KEEP;
            for (int i = 0; i < overflow; i++) {
                java.nio.file.Files.deleteIfExists(files.get(i));
            }
        } catch (Exception e) {
            log.debug("[AI-Call] 裁剪最终请求体失败（忽略）：{}", e.getMessage());
        }
    }
}
