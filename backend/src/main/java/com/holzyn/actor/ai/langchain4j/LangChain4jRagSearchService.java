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

package com.holzyn.actor.ai.langchain4j;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LangChain4j RAG 检索实现。
 * <p>职责：知识检索侧新路径——query 经 {@link DynamicEmbeddingModelFactory} 的 LangChain4j
 * {@code OpenAiEmbeddingModel} 向量化后，在 {@link VectorStoreService} 向量库中做相似度检索
 * （{@link InMemoryEmbeddingStore#search}），再按「项目级全检 + 当前角色级合并」双粒度语义
 * 过滤合并，返回与旧路径同形的 {@link KnowledgeHit} 列表。</p>
 * <p><b>实现说明</b>：
 * LangChain4j 1.19.0 Filter 无「字段缺失 isNull」运算符，而项目级 chunk 元数据<b>不带
 * characterId 键</b>（见 {@code KnowledgeVectorizerService.toSegments}）；故本实现走
 * <b>Filter 检索（projectId 恒等过滤）+ 内存双粒度合并</b>：检索取 {@code topK × headroom}
 * 头部余量（默认 4，可配置），再在内存按元数据 characterId 语义过滤（键缺失=项目级恒纳入 /
 * 等于当前角色）→ 按得分降序取 topK，与旧路径语义完全一致（偏差登记于方案 §10 勘误）。</p>
 * <p><b>审计口径</b>：query 向量化创建 scene=embedding 独立 trace
 * （embedding 只消耗输入 token，tokenIn=total），与旧路径 {@code KnowledgeRetrievalService.search}
 * 现状一致。</p>
 * <p><b>降级语义</b>：embedding 供应商未配置 / 向量化失败 / 检索异常 → 返回空列表，由兼容壳
 * {@code KnowledgeRetrievalService} 降级文本关键词检索（现有 textScore/snippetAround 保留）。</p>
 */
@Slf4j
@Service
public class LangChain4jRagSearchService implements RagSearchService {

    /** 检索头部余量倍数（双粒度内存合并前的检索条数 = topK × headroom，防角色级命中挤占项目级） */
    private final int headroom;

    /** 最低相似度阈值（与旧路径 score>0 对齐默认 0，可配置） */
    private final double minScore;

    private final ModelApiService modelApiService;
    private final DynamicEmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreService vectorStoreService;
    private final AiCallTraceService aiCallTraceService;
    private final UsageLogService usageLogService;

    // ==================== 同文本向量复用（调用风暴抓修 2026-10-07） ====================
    // 问题：群聊/感知并行决策时 3+ 个角色会以同一 query/话题词各自检索知识，每次都重新
    // embedding——实测 120 条 embedding 请求只有 ~15 条不同文本（"云来茶馆"×21、"后山"×18、
    // "掌柜"×9…全部是 3 的倍数）。向量化是「同文本→同向量」的幂等只读计算，重复调用纯属
    // 浪费，因此按「供应商 id|模型|文本」复用（对齐 MemoryVectorService.textVectorCache 口径）。

    /** 同文本向量复用开关（默认 true；false=每次检索都重新向量化，退回抓修前行为） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.ai.rag.query-vector-cache-enabled:true}")
    private boolean textCacheEnabled = true;

    /** 复用缓存容量上限（默认 5000 条；超限整体清空——单条向量数 KB，重建代价低） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.ai.rag.query-vector-cache-max:5000}")
    private int textCacheMax = 5000;

    /** 文本→向量复用缓存（键=供应商 id|模型|文本；值=该文本的向量） */
    private final java.util.Map<String, Embedding> queryVectorCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 可观测计数：复用命中次数 */
    private final java.util.concurrent.atomic.AtomicLong textCacheHits =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 构造函数（构造器注入，便于单测）。
     *
     * @param headroom 检索头部余量倍数（application.yml holzyn.actor.ai.rag.headroom，默认 4）
     *
     * @param minScore 最低相似度阈值（application.yml holzyn.actor.ai.rag.min-score，默认 0）
     *
     * @param modelApiService 模型配置源（resolveEmbeddingProvider）
     *
     * @param embeddingModelFactory LangChain4j embedding 模型工厂
     *
     * @param vectorStoreService 向量库服务
     *
     * @param aiCallTraceService trace 服务（scene=embedding 记账）
     *
     * @param usageLogService 用量服务（scene=embedding）
     */
    public LangChain4jRagSearchService(
            @Value("${holzyn.actor.ai.rag.headroom:4}") int headroom,
            @Value("${holzyn.actor.ai.rag.min-score:0}") double minScore,
            ModelApiService modelApiService,
            DynamicEmbeddingModelFactory embeddingModelFactory,
            VectorStoreService vectorStoreService,
            AiCallTraceService aiCallTraceService,
            UsageLogService usageLogService) {
        this.headroom = headroom > 0 ? headroom : 4;
        this.minScore = minScore;
        this.modelApiService = modelApiService;
        this.embeddingModelFactory = embeddingModelFactory;
        this.vectorStoreService = vectorStoreService;
        this.aiCallTraceService = aiCallTraceService;
        this.usageLogService = usageLogService;
    }

    /**
     * <p>流程：① 解析 embedding 供应商（未配置 → 空列表由上层降级）；② 取该项目向量库；
     * ③ query 向量化（LangChain4j model.embed + scene=embedding trace/用量记账；
     * <b>同文本复用：同供应商同模型同 query 直接复用向量，不再重复调用 embedding</b>——
     * 群聊/感知并行决策时 3+ 个角色会以同一 query/话题词检索，向量化是幂等只读计算，
     * 重复调用纯属浪费（实测 120 条 embedding 只有 ~15 条不同文本）；
     * ④ 向量库检索（Filter=projectId 恒等 + maxResults=topK×headroom + minScore）；
     * ⑤ 内存双粒度合并（项目级键缺失恒纳入 + 当前角色级）→ 按得分降序取 topK。</p>
     */
    @Override
    public List<KnowledgeHit> search(Long userId, Long projectId, Long characterId, String query, int topK) {
        // ① 未配置 embedding 供应商：走文本检索降级（与旧路径一致，不产生 trace/用量）
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(userId, projectId);
        if (cfg == null) {
            return List.of();
        }
        int k = topK > 0 ? topK : 3;
        long startMs = System.currentTimeMillis();
        // ③ 独立 scene=embedding trace（与旧路径 KnowledgeRetrievalService.search 同口径：beginScene 不关联 AiCallContext）
        AiCallTrace trace = aiCallTraceService.beginScene(projectId, "embedding");
        try {
            trace.setBaseUrl(cfg.baseUrl());
            trace.setStream(false);
            // ③-0 同文本向量复用：同供应商同模型同文本 → 直接复用向量（幂等只读，省重复 embedding 调用）
            String text = query == null ? "" : query;
            String cacheKey = textCacheEnabled ? (cfg.id() + "|" + cfg.model() + "|" + text) : null;
            Embedding queryVec = null;
            int prompt = 0;
            if (cacheKey != null) {
                queryVec = queryVectorCache.get(cacheKey);
            }
            if (queryVec == null) {
                // ③-1 query 向量化（LangChain4j OpenAiEmbeddingModel；用量取 Response.tokenUsage，缺省记 0）
                EmbeddingModel model = embeddingModelFactory.get(cfg);
                Response<Embedding> resp = model.embed(text);
                Embedding emb = resp.content();
                if (emb == null) {
                    throw new IllegalStateException("query 向量化返回空（模型=" + cfg.model() + "）");
                }
                queryVec = emb;
                TokenUsage tu = resp.tokenUsage();
                int inputTokens = tu == null || tu.inputTokenCount() == null ? 0 : tu.inputTokenCount();
                int totalTokens = tu == null || tu.totalTokenCount() == null ? inputTokens : tu.totalTokenCount();
                prompt = totalTokens > 0 ? totalTokens : inputTokens;
                // ③-2 写入复用缓存（同文本下次直接复用；容量超限整体清空防无界增长）
                if (cacheKey != null) {
                    if (queryVectorCache.size() >= Math.max(1, textCacheMax)) {
                        queryVectorCache.clear();
                    }
                    queryVectorCache.put(cacheKey, queryVec);
                }
            } else {
                textCacheHits.incrementAndGet();
            }
            // ④ 向量库检索：Filter=projectId 恒等（防跨项目串扰，双保险）+ 头部余量 + minScore
            InMemoryEmbeddingStore<TextSegment> store = vectorStoreService.storeFor(projectId);
            Filter filter = MetadataFilterBuilder.metadataKey(VectorStoreService.META_PROJECT_ID)
                    .isEqualTo(String.valueOf(projectId));
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryVec)
                    .maxResults(k * headroom)
                    .minScore(minScore)
                    .filter(filter)
                    .build();
            EmbeddingSearchResult<TextSegment> result = store.search(request);
            // ⑤ 内存双粒度合并（项目级键缺失恒纳入 + 当前角色级）
            List<KnowledgeHit> hits = mergeDualGranularity(result.matches(), characterId);
            List<KnowledgeHit> top = hits.stream()
                    .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                    .limit(k)
                    .toList();
            // 审计：trace 成功 + 用量落库（embedding 只消耗输入 token：tokenIn=total，tokenOut=0）
            trace.setInputSummary("embedding 供应商: " + cfg.baseUrl() + " 模型=" + cfg.model()
                    + "；query 检索（项目=" + projectId + " 角色=" + characterId + "）");
            trace.fillMessages(new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system",
                            "Embedding 供应商：" + cfg.baseUrl() + " 模型=" + cfg.model()),
                    new AiChatRequest.ChatMessage("user", query == null ? "" : query)), null, null));
            trace.markSuccess("已检索命中 " + top.size() + " 条（检索余量 " + (k * headroom)
                            + "，minScore=" + minScore + "）", cfg.model(), prompt, 0, 0, 0, cfg.id());
            usageLogService.record(userId, projectId, null, null, cfg.model(), "embedding",
                    prompt, 0, 0, 0, (int) (System.currentTimeMillis() - startMs));
            logAiCall(trace);
            return top;
        } catch (Exception e) {
            trace.markFailure(e);
            logAiCall(trace);
            log.warn("LangChain4j RAG 检索失败，降级文本检索: {}", e.getMessage());
            return List.of();
        } finally {
            aiCallTraceService.end(trace);
        }
    }

    /**
     * 双粒度合并：检索命中（已按项目过滤）→ 项目级（characterId 键缺失）恒纳入 + 当前角色级合并。
     * <p>语义与旧路径 {@code KnowledgeRetrievalService.search} 的「projectId 文档恒纳入 + characterId
     * 等于当前角色」完全一致；其他角色级 chunk 丢弃（与旧路径逐文档过滤同效果）。</p>
     *
     * @param matches 向量库检索命中（含 TextSegment 与相似度得分）
     *
     * @param characterId 当前角色 ID（可空=仅项目级）
     *
     * @return 合并后的命中列表
     */
    private List<KnowledgeHit> mergeDualGranularity(List<EmbeddingMatch<TextSegment>> matches, Long characterId) {
        List<KnowledgeHit> hits = new ArrayList<>();
        if (matches == null) {
            return hits;
        }
        for (EmbeddingMatch<TextSegment> m : matches) {
            if (m == null || m.embedded() == null) {
                continue;
            }
            TextSegment seg = m.embedded();
            Metadata meta = seg.metadata();
            String docIdStr = meta == null ? null : meta.getString(VectorStoreService.META_DOC_ID);
            String charIdStr = meta == null ? null : meta.getString(VectorStoreService.META_CHARACTER_ID);
            String title = meta == null ? null : meta.getString(VectorStoreService.META_TITLE);
            // 项目级：characterId 键缺失（无该元数据）恒纳入；角色级：仅等于当前角色
            boolean projectLevel = charIdStr == null || charIdStr.isBlank();
            boolean charMatch = characterId != null && String.valueOf(characterId).equals(charIdStr);
            if (!projectLevel && !charMatch) {
                continue;
            }
            Double score = m.score();
            hits.add(new KnowledgeHit(
                    toLong(docIdStr),
                    title == null || title.isBlank() ? "知识" : title,
                    seg.text() == null ? "" : seg.text(),
                    score == null ? 0 : score,
                    projectLevel ? null : characterId));
        }
        return hits;
    }

    /** 字符串安全转 Long（解析失败返回 null）。 */
    private static Long toLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 实时日志（对齐旧路径 recordEmbeddingUsage 的 logAiCall INFO 摘要口径）。
     *
     * @param trace 本次调用 trace
     */
    private void logAiCall(AiCallTrace trace) {
        String status = trace.isDegraded() ? "degraded" : trace.getStatus();
        String err = trace.getErrorMessage() == null ? "" : " 错误=" + trace.getErrorMessage();
        log.info("[AI调用] scene=embedding project={} char={} model={} 状态={} tokens={}/{} 耗时={}ms{}",
                trace.getProjectId(), trace.getCharacterId(),
                trace.getModel() == null ? "-" : trace.getModel(), status,
                trace.getTokenIn(), trace.getTokenOut(), trace.getDurationMs(), err);
    }
}
