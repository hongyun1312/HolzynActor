package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KnowledgeVectorizerService 单元测试（AI 接入层重构 M3，2026-09-05；M5 适配：vectorize 改 void 停写旧 embedding 列）。
 * <p>职责：验证入库向量化编排——①embedding 供应商未配置 → 静默跳过（文本降级，零调用）；
 * ②成功路径：LangChain4j 批量向量化 + 向量库按 docId 写入（带元数据）
 * + scene=embedding trace/用量记账（与旧 Router.embedding 同口径）；
 * ③失败路径：降级（不写向量库/不记用量，trace 记失败）；④removeDoc 委托向量库清理。</p>
 */
class KnowledgeVectorizerServiceTest {

    private final ModelApiService modelApiService = mock(ModelApiService.class);
    private final DynamicEmbeddingModelFactory factory = mock(DynamicEmbeddingModelFactory.class);
    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);
    private final AiCallTraceService aiCallTraceService = mock(AiCallTraceService.class);
    private final UsageLogService usageLogService = mock(UsageLogService.class);

    private final KnowledgeVectorizerService svc = new KnowledgeVectorizerService(
            modelApiService, factory, vectorStoreService, aiCallTraceService, usageLogService);

    /**
     * 未配置 embedding 供应商：静默跳过（文本降级），不产生任何工厂/向量库/trace/用量调用。
     */
    @Test
    void noEmbeddingConfigSkipsSilently() {
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(null);
        svc.vectorize(1L, 2L, 10L, "标题", null, "第一句。第二句。");
        verifyNoInteractions(factory, vectorStoreService, aiCallTraceService, usageLogService);
    }

    /**
     * 成功路径：向量库写入（带 docId 元数据）+ trace/用量记账（M5 起无旧列双写）。
     */
    @Test
    void successWritesStoreAndRecordsUsage() {
        ProviderConfig cfg = new ProviderConfig(5L, "https://api.example.com/v1", "sk", "emb", false);
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(cfg);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(factory.get(cfg)).thenReturn(model);
        List<Embedding> embeddings = List.of(new Embedding(new float[]{1f, 2f, 3f}));
        when(model.embedAll(anyList())).thenReturn(Response.from(embeddings, new TokenUsage(10, 0, 10)));
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        svc.vectorize(1L, 2L, 10L, "标题", 3L, "第一句。第二句。第三句。");

        // ① 向量库写入：按 docId 替换 + 元数据带 docId/角色
        ArgumentCaptor<List<TextSegment>> segCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> embCap = ArgumentCaptor.forClass(List.class);
        verify(vectorStoreService).addChunks(eq(2L), eq(10L), eq("标题"), eq(3L), segCap.capture(), embCap.capture());
        assertTrue(segCap.getValue().size() == 1, "3 句合成 1 块（<500 字）");
        assertTrue("10".equals(segCap.getValue().get(0).metadata().getString(VectorStoreService.META_DOC_ID)));
        assertTrue("3".equals(segCap.getValue().get(0).metadata().getString(VectorStoreService.META_CHARACTER_ID)));
        assertTrue(embCap.getValue().size() == 1);
        // ② 用量记账（embedding 只消耗输入 token：tokenIn=10，tokenOut=0）
        verify(usageLogService).record(eq(1L), eq(2L), isNull(), isNull(), eq("emb"), eq("embedding"),
                eq(10), eq(0), eq(0), eq(0), anyInt());
        // ③ trace 成功 + end（与旧 Router.embedding 同口径）
        verify(trace).markSuccess(anyString(), eq("emb"), eq(10), eq(0), eq(0), eq(0), eq(5L));
        verify(aiCallTraceService).end(trace);
    }

    /**
     * 失败路径：embedding 调用异常 → 降级（不写向量库/不记用量），trace 记失败 + end。
     */
    @Test
    void failureDegradesSilently() {
        ProviderConfig cfg = new ProviderConfig(5L, "https://api.example.com/v1", "sk", "emb", false);
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(cfg);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(factory.get(cfg)).thenReturn(model);
        when(model.embedAll(anyList())).thenThrow(new RuntimeException("api down"));
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        svc.vectorize(1L, 2L, 10L, "标题", null, "第一句。第二句。");

        verify(vectorStoreService, never()).addChunks(any(), any(), any(), any(), any(), any());
        verify(usageLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(trace).markFailure(any());
        verify(aiCallTraceService).end(trace);
    }

    /**
     * removeDoc：委托向量库按 docId 清理。
     */
    @Test
    void removeDocDelegatesToStore() {
        svc.removeDoc(2L, 10L);
        verify(vectorStoreService).removeDoc(2L, 10L);
    }

    /**
     * M4 冒烟抓修：大文档（>batch-size 分块）按批 embedAll，向量与用量累加合并。
     * <p>缺陷：M3 一次 embedAll 全部分块超供应商 input 上限（doubao 单次≤10）→ 整文档向量化失败。
     * 修复后按 10/批拆分调用，向量与用量按序累加合并（批次数=ceil(分块数/10)，实测应 >1）。</p>
     */
    @Test
    void largeDocEmbedsInBatches() {
        ProviderConfig cfg = new ProviderConfig(5L, "https://api.example.com/v1", "sk", "emb", false);
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(cfg);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(factory.get(cfg)).thenReturn(model);
        // 动态返回：每批给与输入等量的向量（验证分批 + 合并正确，不依赖具体分块数）
        when(model.embedAll(anyList())).thenAnswer(inv -> {
            List<?> input = inv.getArgument(0);
            List<Embedding> vecs = new java.util.ArrayList<>();
            for (int i = 0; i < input.size(); i++) {
                vecs.add(new Embedding(new float[]{1f, 0f, 0f}));
            }
            return Response.from(vecs, new TokenUsage(input.size(), 0, input.size()));
        });
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);
        // 足够长文本 → 必然 >10 块（触发多批）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("第").append(i).append("句：这是一段足够长的测试内容，用于触发多批向量化调用以验证批次拆分逻辑的正确性，内容重复但长度可控。");
        }

        svc.vectorize(1L, 2L, 10L, "标题", null, sb.toString());

        // ① 多次调用 embedAll（>1 批）
        verify(model, atLeast(2)).embedAll(anyList());
        // ② 向量库写入条数 = 分块数（>10），向量与分块一一对应
        ArgumentCaptor<List<TextSegment>> segCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> embCap = ArgumentCaptor.forClass(List.class);
        verify(vectorStoreService).addChunks(eq(2L), eq(10L), eq("标题"), isNull(), segCap.capture(), embCap.capture());
        int chunkCount = segCap.getValue().size();
        assertTrue(chunkCount > 10, "分块数应 >10 以触发多批，实际 " + chunkCount);
        assertTrue(embCap.getValue().size() == chunkCount, "向量应与分块一一对应");
        // ③ 用量累加：tokenIn=分块总数（每批 input.size()）
        verify(usageLogService).record(eq(1L), eq(2L), isNull(), isNull(), eq("emb"), eq("embedding"),
                eq(chunkCount), eq(0), eq(0), eq(0), anyInt());
        verify(trace).markSuccess(anyString(), eq("emb"), eq(chunkCount), eq(0), eq(0), eq(0), eq(5L));
    }
}
