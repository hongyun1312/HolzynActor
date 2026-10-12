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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义缓存服务。
 * <p>职责：用<b>向量相似度</b>对「语义相近的最后一条 user 消息」命中缓存回复——同一/相似 prompt 的重复调用
 * （重放飞轮、worldgen 重试、预览、A/B 回归等）直接复用 LLM 输出，<b>降本</b>。两框架（Spring AI / LangChain4j）均无内置。</p>
 * <p>设计：<ul>
 * <li><b>作用范围</b>：仅非流式 chatCompletion / chatCompletionJson；</li>
 * <li><b>缓存键/相似度基准</b>：最后一条 role=user 消息文本经 {@link DynamicEmbeddingModelFactory} 向量化，
 * 同 {@code model + jsonMode + temperature} 且余弦相似度 ≥ threshold（默认 0.9）即命中；</li>
 * <li><b>持久化</b>：进程内内存态，重启清空；FIFO 淘汰，容量上限 max-size（默认 200）；</li>
 * <li><b>配置</b>：semantic-cache-enabled（<b>默认 false</b>，见下）/ threshold / max-size / min-query-length（默认 8，
 * 过短 query 语义含糊不参与，防误命中）；</li>
 * <li><b>降级语义</b>：未配置 embedding 供应商 / 向量化失败 / 无匹配 → 一律 miss（零阻断业务，不抛错）；</li>
 * <li><b>命中审计</b>：网关命中时记一条 trace（semantic_cache_hit=true + 复用缓存 token 账本），
 * 不写 usage_log（未产生新用量，成本即本次嵌入的少量 embedding token）。</li>
 * </ul></p>
 * <p>单次调用只做一次 query embedding（{@link Lookup} 携带向量供 miss 后 {@link #record} 复用，避免重复调用）。</p>
 * <p><b>⚠️ 世界运行抓修（真实生产缺陷，用户报障「一大部分请求被打到向量模型上」）</b>——
 * 本服务原为「全部非流式调用统一生效 」，实测在世界运行 15 分钟内造成两类严重问题：</p>
 * <ol>
 * <li><b>决策被回放（最严重，直接毁掉世界真实性）</b>：世界引擎的决策/情绪/行动 prompt 天然高度相似
 * （同一角色的性格段 + 当前状态 + 时刻），实测 <b>312 次尝试里 135 次命中（43%）</b>：
 * {@code npc_crowd_decision} 深夜复用了白天的「整理书案准备次日授课」、<b>不同 NPC（12 与 13）拿到
 * 同一句行动文本</b>、{@code npc_emotion_eval} 把同一条 calm JSON 反复复用（情绪冻结）。
 * 缓存设计目标是「重放飞轮/预览/A-B」这类<b>同一次调用重放</b>，而世界运行的每次调用都是
 * 「此刻独有」的——命中即等于 NPC 拿旧情境的答案演现在。</li>
 * <li><b>成本不可见</b>：每次非流式 chat 调用都要先 embed 一次 query（本次 15 分钟约 300 次，占发往模型
 * 服务全部请求的约四分之三），而这笔嵌入<u>既不落 trace 也不写 usage_log</u>——运维侧只能看到
 * 「请求都打到向量模型上」，却在 HA 账本里查不到任何来源。</li>
 * </ol>
 * <p>因此改为 <b>fail-closed 场景白名单</b>：{@code semantic-cache.scene-allow} 为空（缺省）时
 * <b>任何场景都不参与缓存</b>；只有显式点名的场景（前缀匹配，如 {@code worldgen} 覆盖
 * {@code worldgen_build_v1}；{@code *}=全部场景，仅供开发工作流临时开启）才走缓存。
 * 的场景默认落在「不缓存」一侧，不会再出现「悄悄回放决策」。</p>
 */
@Slf4j
@Component
public class SemanticCacheService {

    /** 默认阈值：余弦相似度 ≥ 该值视为「语义相近」（0.9 保守，防无关 query 误命中） */
    private static final double DEFAULT_THRESHOLD = 0.9;

    /** 默认容量上限（FIFO 淘汰） */
    private static final int DEFAULT_MAX_SIZE = 200;

    /** 默认最小 query 长度（过短不参与缓存，防寒暄/单字误命中） */
    private static final int DEFAULT_MIN_QUERY_LENGTH = 8;

    /** 供应商运行时配置服务（embedding 供应商解析） */
    private final ModelApiService modelApiService;

    /** LangChain4j 动态 embedding 模型工厂（query 向量化） */
    private final DynamicEmbeddingModelFactory embeddingModelFactory;

    /**
     * 语义缓存总开关（<b>默认 false</b>—— 抓修：世界运行期命中率 43% 且命中即回放旧决策，
     * 详见类注释；仅开发工作流（重放飞轮/预览/A-B）需要时显式开启，且须同时配置 scene-allow）
     */
    @Value("${holzyn.actor.ai.semantic-cache-enabled:false}")
    private boolean enabled = false;

    /**
     * 生效场景白名单（逗号分隔，前缀匹配；{@code *}=全部场景）；<b>空（缺省）=任何场景都不参与缓存</b>
     * ——fail-closed：新增场景默认不会被缓存"悄悄回放"
     */
    @Value("${holzyn.actor.ai.semantic-cache.scene-allow:}")
    private String sceneAllow = "";

    /** 相似度阈值（0~1，默认 0.9） */
    @Value("${holzyn.actor.ai.semantic-cache.threshold:0.9}")
    private double threshold = DEFAULT_THRESHOLD;

    /** 缓存容量上限（默认 200；FIFO 淘汰最旧） */
    @Value("${holzyn.actor.ai.semantic-cache.max-size:200}")
    private int maxSize = DEFAULT_MAX_SIZE;

    /** 最小参与缓存的 query 长度（字符数，默认 8） */
    @Value("${holzyn.actor.ai.semantic-cache.min-query-length:8}")
    private int minQueryLength = DEFAULT_MIN_QUERY_LENGTH;

    /** 用量台账服务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.usage.service.UsageLogService usageLogService;

    /** query 向量化用量场景名（独立于 RAG 的 embedding：语义缓存的嵌入成本单独可见） */
    static final String SCENE_USAGE = "semantic_cache_embedding";

    /** 缓存条目（FIFO 顺序 = 插入顺序；线程安全由 synchronized 保证） */
    private final List<CacheEntry> entries = new ArrayList<>();

    /** 可观测计数 */
    private final java.util.concurrent.atomic.AtomicLong lookupCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong deniedByScene = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong queryEmbedCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong hitCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 构造（Spring 注入；测试可手动构造 + 反射置位开关）。
     *
     * @param modelApiService 供应商运行时配置服务
     *
     * @param embeddingModelFactory LangChain4j 动态 embedding 模型工厂
     */
    public SemanticCacheService(ModelApiService modelApiService, DynamicEmbeddingModelFactory embeddingModelFactory) {
        this.modelApiService = modelApiService;
        this.embeddingModelFactory = embeddingModelFactory;
    }

    /**
     * 语义缓存单次查询结果（避免 miss 后 record 重复 embedding）。
     *
     * @param query 最后一条 user 消息文本（可能为 null=不参与）
     *
     * @param model 实际模型名（可能为 null=不参与）
     *
     * @param queryVector query 向量（null=未向量化，不参与命中也不记录）
     *
     * @param hit 命中结果（null=未命中）
     */
    public static final class Lookup {
        private final String query;
        private final String model;
        private final float[] queryVector;
        private final AiChatResult hit;

        private Lookup(String query, String model, float[] queryVector, AiChatResult hit) {
            this.query = query;
            this.model = model;
            this.queryVector = queryVector;
            this.hit = hit;
        }

        static Lookup hit(String query, String model, float[] queryVector, AiChatResult hit) {
            return new Lookup(query, model, queryVector, hit);
        }

        static Lookup miss(String query, String model, float[] queryVector) {
            return new Lookup(query, model, queryVector, null);
        }

        /** 命中结果（null=未命中） */
        public AiChatResult hit() {
            return hit;
        }

        /** 最后一条 user 消息文本（可空） */
        String query() {
            return query;
        }

        /** 实际模型名（可空） */
        String model() {
            return model;
        }

        /** query 向量（null=未向量化；record 复用避免二次 embedding） */
        float[] queryVector() {
            return queryVector;
        }
    }

    /**
     * 查询语义缓存（单次 query embedding；未配置/失败/无匹配 → miss，不抛错不阻断）。
     * <p><b>无场景上下文（scene=null）视为不参与</b>——fail-closed：查不到"这是哪类调用"时宁可不缓存，
     * 也不冒"把旧决策回放给 NPC"的风险。</p>
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @param chatConfig 已解析的对话供应商运行时配置（取实际模型名）
     *
     * @param request 对话请求（最后一条 user 消息为相似度基准）
     *
     * @param scene 当前调用场景（{@code AiCallContext} 的 scene；null=不参与缓存）
     *
     * @return 查询结果（命中时 {@link Lookup#hit()} 非空）
     */
    public Lookup lookup(Long userId, Long projectId, ProviderConfig chatConfig, AiChatRequest request,
                         String scene) {
        if (!enabled || request == null || chatConfig == null) {
            return Lookup.miss(null, null, null);
        }
        lookupCount.incrementAndGet();
        // ① 场景白名单（fail-closed， 抓修）：不在名单内的场景一律不参与——连 query 向量化
        // 都不做（零成本），更不会命中。世界运行的决策/情绪/行动场景默认全部不在名单内。
        if (!sceneAllowed(scene)) {
            deniedByScene.incrementAndGet();
            return Lookup.miss(null, null, null);
        }
        String query = lastUserMessage(request);
        String model = resolveModel(request, chatConfig);
        if (query == null || model == null || query.length() < minQueryLength) {
            return Lookup.miss(query, model, null);
        }
        // embedding 供应商未配置 → miss（语义缓存不可用，零阻断）
        ProviderConfig embConfig = modelApiService.resolveEmbeddingProvider(userId, projectId);
        if (embConfig == null) {
            return Lookup.miss(query, model, null);
        }
        float[] qv = embed(embConfig, query, userId, projectId);
        if (qv == null) {
            return Lookup.miss(query, model, null);
        }
        synchronized (this) {
            CacheEntry best = null;
            double bestScore = threshold;
            for (CacheEntry e : entries) {
                // 同模型 + 同 jsonMode + 同温度才参与相似度（防跨模型/跨形态/跨采样误命中）
                if (!e.model.equals(model) || e.jsonMode != request.jsonMode()
                        || !eqTemperature(e.temperature, request.temperature())) {
                    continue;
                }
                double s = cosine(qv, e.queryVector);
                if (s >= bestScore) {
                    bestScore = s;
                    best = e;
                }
            }
            if (best != null) {
                hitCount.incrementAndGet();
                AiUsage u = best.usage;
                Integer total = (u.promptTokens() == null || u.completionTokens() == null)
                        ? null : u.promptTokens() + u.completionTokens();
                AiChatResult hit = new AiChatResult(best.content, model,
                        u.promptTokens(), u.completionTokens(), total,
                        u.cacheHitTokens(), u.cacheMissTokens(), best.providerId);
                log.info("[语义缓存] 命中：scene={} model={} jsonMode={} 相似度={} query={}", scene, model,
                        request.jsonMode(), String.format("%.3f", bestScore), truncate(query, 60));
                return Lookup.hit(query, model, qv, hit);
            }
        }
        return Lookup.miss(query, model, qv);
    }

    /**
     * 查询语义缓存（无场景上下文重载）：等价于 {@code lookup(..., scene=null)}——恒 miss（fail-closed）。
     * <p>保留该重载仅为兼容存量测试/内部调用；业务调用方应走带 scene 的版本。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param chatConfig 已解析的对话供应商运行时配置
     *
     * @param request 对话请求
     *
     * @return 查询结果（恒 miss）
     */
    public Lookup lookup(Long userId, Long projectId, ProviderConfig chatConfig, AiChatRequest request) {
        return lookup(userId, projectId, chatConfig, request, null);
    }

    /**
     * 场景是否允许参与缓存（fail-closed）：白名单为空 → 全部拒绝；条目支持前缀匹配；{@code *}=全部允许。
     *
     * @param scene 场景名（null/空=拒绝）
     *
     * @return true=允许
     */
    private boolean sceneAllowed(String scene) {
        String cfg = sceneAllow;
        if (cfg == null || cfg.isBlank()) {
            return false;
        }
        for (String raw : cfg.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if ("*".equals(pattern)) {
                return true;
            }
            if (scene != null && !scene.isBlank() && scene.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录一次成功调用结果入缓存（未命中且调用成功时由网关调用）。
     * <p>仅当 {@link Lookup#queryVector()} 非空（本次调用已向量化）才记录；结果为空/异常不入缓存。</p>
     *
     * @param lookup 本次查询结果（miss 且带向量）
     *
     * @param request 对话请求（jsonMode/temperature 入缓存键）
     *
     * @param result 成功的调用结果
     */
    public void record(Lookup lookup, AiChatRequest request, AiChatResult result) {
        if (!enabled || lookup == null || lookup.queryVector() == null || maxSize <= 0) {
            return;
        }
        if (result == null || result.content() == null || result.content().isBlank()) {
            return; // 空输出不缓存（可能是异常/受限输出）
        }
        String model = lookup.model();
        if (model == null) {
            return;
        }
        synchronized (this) {
            entries.add(new CacheEntry(model, request == null || request.jsonMode(),
                    request == null ? null : request.temperature(),
                    lookup.query(), lookup.queryVector(), result.content(),
                    new AiUsage(result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens()),
                    result.providerId()));
            // FIFO 淘汰：超出容量移除最旧
            while (entries.size() > maxSize) {
                entries.remove(0);
            }
            log.debug("[语义缓存] 记录：model={} jsonMode={} 当前 {} 条/上限 {}", model,
                    request == null || request.jsonMode(), entries.size(), maxSize);
        }
    }

    /**
     * 当前缓存条数（测试/概览用）
     */
    public synchronized int cachedCount() {
        return entries.size();
    }

    /** 清空缓存（测试/显式重置用） */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * 可观测统计（抓修：语义缓存此前对账本完全隐形——用量/命中都被藏着，出问题时只能
     * 从模型服务侧"感觉请求都打到向量模型上"倒查；现提供 counters 供概览/排障核对）。
     */
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lookups", lookupCount.get());
        m.put("deniedByScene", deniedByScene.get());
        m.put("queryEmbeddings", queryEmbedCount.get());
        m.put("hits", hitCount.get());
        return m;
    }

    /** 当前生效的场景白名单原文（排障用） */
    public String sceneAllowConfig() {
        return sceneAllow == null ? "" : sceneAllow;
    }

    /**
     * 取最后一条 role=user 的非空消息文本（语义相似度基准；无则 null）。
     *
     * @param request 对话请求
     *
     * @return 最后一条 user 消息文本或 null
     */
    private static String lastUserMessage(AiChatRequest request) {
        if (request.messages() == null) {
            return null;
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            AiChatRequest.ChatMessage m = request.messages().get(i);
            if (m != null && "user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                return m.content().trim();
            }
        }
        return null;
    }

    /**
     * 实际模型名：请求优先、配置回退；均空 → null（不参与缓存）。
     *
     * @param request 对话请求
     *
     * @param config 供应商运行时配置
     *
     * @return 模型名或 null
     */
    private static String resolveModel(AiChatRequest request, ProviderConfig config) {
        String model = (request.model() == null || request.model().isBlank()) ? config.model() : request.model();
        return (model == null || model.isBlank()) ? null : model;
    }

    /**
     * query 向量化（DynamicEmbeddingModelFactory 同源，与 RAG 检索同口径）；失败 → null（miss）。
     * <p><b>抓修：本方法产生的用量必须可见</b>——原先这里既不落 trace 也不写 usage_log，
     * 世界运行期"每次非流式对话都先嵌入一次"的成本在 HA 账本里完全查不到（用户只能从模型服务侧
     * 发现"请求都被打到向量模型上"）。现按独立 scene={@value #SCENE_USAGE} 记用量 + INFO 日志。</p>
     *
     * @param embConfig embedding 供应商运行时配置
     *
     * @param query 待向量化文本
     *
     * @param userId 归属用户（用量台账）
     *
     * @param projectId 项目 ID（用量台账）
     *
     * @return 查询向量或 null
     */
    private float[] embed(ProviderConfig embConfig, String query, Long userId, Long projectId) {
        long startMs = System.currentTimeMillis();
        try {
            EmbeddingModel model = embeddingModelFactory.get(embConfig);
            Response<Embedding> resp = model.embed(query);
            if (resp == null || resp.content() == null) {
                return null;
            }
            long ms = System.currentTimeMillis() - startMs;
            int prompt = resp.tokenUsage() == null || resp.tokenUsage().inputTokenCount() == null
                    ? 0 : resp.tokenUsage().inputTokenCount();
            queryEmbedCount.incrementAndGet();
            if (usageLogService != null) {
                try {
                    usageLogService.record(userId == null ? 1L : userId, projectId, null, null,
                            embConfig.model(), SCENE_USAGE, prompt, 0, 0, 0, (int) ms);
                } catch (Exception e) {
                    // 记账失败不影响缓存查询（零阻断）
                    log.debug("[语义缓存] 用量记账失败（忽略）: {}", e.getMessage());
                }
            }
            log.info("[语义缓存] query 向量化：model={} tokens={} 耗时={}ms query={}",
                    embConfig.model(), prompt, ms, truncate(query, 40));
            return resp.content().vector();
        } catch (Exception e) {
            log.warn("[语义缓存] query 向量化失败，本次 miss（不阻断）: {}", e.getMessage());
            return null;
        }
    }

    /** 余弦相似度（两个向量；维度不同/零向量返回 0） */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 温度相等（含 null） */
    private static boolean eqTemperature(Double a, Double b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Double.compare(a, b) == 0;
    }

    /** 调试日志截断 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    /** 缓存条目（FIFO 顺序=插入顺序；不可变） */
    private record CacheEntry(String model, boolean jsonMode, Double temperature, String query,
                              float[] queryVector, String content, AiUsage usage, Long providerId) {
    }
}
