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

package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.ai.AiChatRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识入库向量化服务（AI 接入层重构，方案《AI接入层重构_SpringAI_LangChain4j_技术方案》§9；
 * 起旧 embedding 列物理删除，停写清理）。
 * <p>职责：替代旧 {@code KnowledgeRetrievalService.buildEmbeddedChunks} 的入库路径——
 * ① 分块（复用 {@link KnowledgeChunker} 中文分块，避免检索质量回退）；
 * ② 经 {@link DynamicEmbeddingModelFactory} 的 LangChain4j {@code OpenAiEmbeddingModel} 批量向量化
 * （按 batch-size 分批，防超供应商单次 input 上限）；
 * ③ 写入 {@link VectorStoreService} 向量库；
 * ④ <b>起无旧列双写</b>（actor_knowledge_doc.embedding 列已物理删除，向量唯一存储 = 向量库文件）；
 * ⑤ <b>审计不中断</b>：每次向量化创建一条 scene=embedding 独立 trace
 * （{@link AiCallTraceService#beginScene}，与旧 Router.embedding 同口径）+
 * {@link UsageLogService} 按 scene=embedding 记用量（embedding 只消耗输入 token）。</p>
 * <p><b>降级语义</b>（对齐旧 buildEmbeddedChunks）：embedding 供应商未配置 → 直接返回（无向量，静默）；
 * 向量化/落库任一步失败 → WARN + 返回（文档无向量，检索走文本降级，不阻断业务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeVectorizerService {

    /** 单批默认条数（与旧路径 EmbeddingService 同源，供应商单次 input 上限，如 doubao-embedding 单次≤10） */
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final ModelApiService modelApiService;
    private final DynamicEmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreService vectorStoreService;
    private final AiCallTraceService aiCallTraceService;
    private final UsageLogService usageLogService;

    /** embedding 单批最多 input 条数（application.yml holzyn.actor.embedding.batch-size，默认 10；与旧路径同配置） */
    @Value("${holzyn.actor.embedding.batch-size:10}")
    private int batchSize;

    /**
     * 检索结果缓存失效钩子（抓修：知识库是检索结果的唯一输入，一变就得让旧答案作废；
     * 字段注入保持构造器不变，null=未装配跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeRetrievalService retrievalService;

    /** 知识库变更后使检索缓存失效（隔离异常：缓存问题绝不阻断入库主流程）。 */
    private void invalidateRetrievalCache(Long projectId) {
        if (retrievalService == null) {
            return;
        }
        try {
            retrievalService.invalidate(projectId);
        } catch (Exception e) {
            log.warn("[知识库] 检索缓存失效失败（忽略，走 TTL 兜底）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 向量化一份知识文档。
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @param docId 文档主键（向量库按此替换/删除）
     *
     * @param title 文档标题（写入向量元数据）
     *
     * @param characterId 角色级归属（可空=项目级；写入向量元数据）
     *
     * @param content 文档全文
     */
    public void vectorize(Long userId, Long projectId, Long docId, String title, Long characterId, String content) {
        // ① 未配置 embedding 供应商：走文本检索降级（与旧路径一致，不产生 trace/用量）
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(userId, projectId);
        if (cfg == null) {
            // 无 embedding 供应商（走文本降级检索）：文档已落库 → 同样要让检索缓存失效
            invalidateRetrievalCache(projectId);
            return;
        }
        // ② 中文分块（复用现有分块，避免检索质量回退）
        List<String> chunks = KnowledgeChunker.chunk(content, KnowledgeChunker.CHUNK_SIZE, KnowledgeChunker.CHUNK_OVERLAP);
        if (chunks.isEmpty()) {
            invalidateRetrievalCache(projectId);
            return;
        }
        long startMs = System.currentTimeMillis();
        // ③ 独立 scene=embedding trace（与旧 Router.embedding 同口径：beginScene 不关联 AiCallContext）
        AiCallTrace trace = aiCallTraceService.beginScene(projectId, "embedding");
        try {
            trace.setBaseUrl(cfg.baseUrl());
            trace.setStream(false);
            // ④ LangChain4j 批量向量化（分块→带元数据 TextSegment→按 batch-size 分批 embedAll 合并）
            EmbeddingModel model = embeddingModelFactory.get(cfg);
            List<TextSegment> segments = toSegments(projectId, docId, title, characterId, chunks);
            BatchResult batchResult = embedAllBatched(model, segments);
            List<Embedding> embeddings = batchResult.embeddings();
            // 用量：LangChain4j Response.tokenUsage() 累加（部分供应商可能缺省 → 记 0 保证调用次数可统计）
            int prompt = batchResult.inputTokens();
            // ⑤ 写入向量库（按 docId 替换 + 原子落盘）
            vectorStoreService.addChunks(projectId, docId, title, characterId, segments, embeddings);
            // ⑥ 审计：trace 成功 + 用量落库（embedding 只消耗输入 token：tokenIn=total，tokenOut=0）
            trace.setInputSummary("embedding 供应商: " + cfg.baseUrl() + " 模型=" + cfg.model()
                    + "；输入" + segments.size() + "段文本（" + batchResult.batches() + " 批）");
            trace.fillMessages(new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system",
                            "Embedding 供应商：" + cfg.baseUrl() + " 模型=" + cfg.model()),
                    new AiChatRequest.ChatMessage("user", String.join("\n", chunks))), null, null));
            trace.markSuccess("已生成 " + embeddings.size() + " 条向量（维度 "
                            + (embeddings.isEmpty() ? 0 : embeddings.get(0).dimension()) + "）",
                    cfg.model(), prompt, 0, 0, 0, cfg.id());
            usageLogService.record(userId, projectId, null, null, cfg.model(), "embedding",
                    prompt, 0, 0, 0, (int) (System.currentTimeMillis() - startMs));
            logAiCall(trace);
        } catch (Exception e) {
            trace.markFailure(e);
            logAiCall(trace);
            log.warn("LangChain4j embedding 生成失败，降级文本检索（该文档无向量）: {}", e.getMessage());
        } finally {
            aiCallTraceService.end(trace);
            // ⑦ 入库/替换完成（或失败但文档已落库）→ 检索缓存必须失效：知识库变了，旧检索答案不得再复用
            invalidateRetrievalCache(projectId);
        }
    }

    /** 批量向量化结果（局部返回，避免实例字段共享状态在并发下串扰）。 */
    private record BatchResult(List<Embedding> embeddings, int inputTokens, int batches) {
    }

    /**
     * 分批调用 embedAll 并合并结果。
     * <p><b>缺陷</b>： 直接 {@code model.embedAll(segments)} 一次发送全部分块——大文档（如整篇世界观）
     * 分块可上百，超出供应商单次 input 上限（如 doubao-embedding 单次≤10，实测 82 块报
     * "Embeddings API input limit exceeded: max 10, got 82"）导致整个文档向量化失败降级文本检索。
     * <b>修复</b>：按 {@code holzyn.actor.embedding.batch-size}（默认 10，与旧路径 EmbeddingService
     * 同配置）分批 embedAll，向量与用量（TokenUsage）按序累加合并，单批失败整体降级（语义不变）。</p>
     *
     * @param model LangChain4j embedding 模型
     *
     * @param segments 全部分块（已带元数据）
     *
     * @return 分批结果（向量与 segments 一一对应 + 累计输入 token + 批次数）
     */
    private BatchResult embedAllBatched(EmbeddingModel model, List<TextSegment> segments) {
        int batchSize = this.batchSize > 0 ? this.batchSize : DEFAULT_BATCH_SIZE;
        List<Embedding> all = new ArrayList<>(segments.size());
        int totalInputTokens = 0;
        int batches = 0;
        for (int from = 0; from < segments.size(); from += batchSize) {
            int to = Math.min(segments.size(), from + batchSize);
            List<TextSegment> batch = segments.subList(from, to);
            Response<List<Embedding>> resp = model.embedAll(batch);
            List<Embedding> embs = resp.content();
            if (embs == null || embs.size() != batch.size()) {
                throw new IllegalStateException("embedding 响应数量与请求不一致（期望 " + batch.size()
                        + "，实际 " + (embs == null ? 0 : embs.size()) + "）");
            }
            all.addAll(embs);
            // 用量累加：LangChain4j Response.tokenUsage()（部分供应商可能缺省 → 记 0）
            TokenUsage tu = resp.tokenUsage();
            int inputTokens = tu == null || tu.inputTokenCount() == null ? 0 : tu.inputTokenCount();
            int totalTokens = tu == null || tu.totalTokenCount() == null ? inputTokens : tu.totalTokenCount();
            totalInputTokens += totalTokens > 0 ? totalTokens : inputTokens;
            batches++;
        }
        return new BatchResult(all, totalInputTokens, batches);
    }

    /**
     * 移除某文档的向量。
     *
     * @param projectId 项目 ID
     *
     * @param docId 文档主键
     */
    public void removeDoc(Long projectId, Long docId) {
        vectorStoreService.removeDoc(projectId, docId);
        invalidateRetrievalCache(projectId);
    }

    /**
     * 分块文本 → 带元数据 {@link TextSegment}（{@code {projectId, characterId, docId, title, chunkIndex}}）。
     *
     * @param projectId 项目 ID
     *
     * @param docId 文档 ID
     *
     * @param title 文档标题
     *
     * @param characterId 角色级归属（可空）
     *
     * @param chunks 分块文本
     *
     * @return TextSegment 列表
     */
    private static List<TextSegment> toSegments(Long projectId, Long docId, String title,
                                                Long characterId, List<String> chunks) {
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put(VectorStoreService.META_PROJECT_ID, String.valueOf(projectId));
            if (characterId != null) {
                meta.put(VectorStoreService.META_CHARACTER_ID, String.valueOf(characterId));
            }
            meta.put(VectorStoreService.META_DOC_ID, String.valueOf(docId));
            meta.put(VectorStoreService.META_TITLE, title == null ? "" : title);
            meta.put(VectorStoreService.META_CHUNK_INDEX, i);
            segments.add(TextSegment.from(chunks.get(i), Metadata.from(meta)));
        }
        return segments;
    }

    /**
     * 实时日志（对齐旧 Router.embedding 的 logAiCall INFO 摘要口径：scene/project/model/状态/token/耗时）。
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
