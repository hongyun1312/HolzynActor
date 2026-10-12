package com.holzyn.actor.ai.langchain4j;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LangChain4jRagSearchService 单元测试（AI 接入层重构 M4，2026-09-06，方案 §9 M4 / §5.3，用户决策 Q4~Q7）。
 * <p>职责：验证检索侧新路径——①未配置 embedding 供应商 → 空列表（上层文本降级，零调用）；
 * ②成功路径：query 向量化（scene=embedding trace/用量记账）+ 向量库相似度检索 + 双粒度合并
 * （项目级键缺失恒纳入 + 当前角色级）+ 按得分降序 topK；
 * ③元数据过滤正确：其他角色级 chunk 不返回；
 * ④失败路径：query 向量化异常 → 空列表 + trace 记失败（上层降级）；⑤minScore/headroom 生效。</p>
 * <p>使用真实 {@link VectorStoreService}（@TempDir 隔离）+ mock EmbeddingModel（返回固定维度向量），
 * 校验「向量库内容 → 检索结果」全链路语义，无需真实 AI。</p>
 */
class LangChain4jRagSearchServiceTest {

    /** 临时数据目录（每用例独立；向量库根 = <temp>/vector-store） */
    @TempDir
    Path tempDir;

    private final ModelApiService modelApiService = mock(ModelApiService.class);
    private final DynamicEmbeddingModelFactory factory = mock(DynamicEmbeddingModelFactory.class);
    private final AiCallTraceService aiCallTraceService = mock(AiCallTraceService.class);
    private final UsageLogService usageLogService = mock(UsageLogService.class);

    /** 构造被测服务：真实 VectorStoreService + 可配置 headroom/minScore */
    private LangChain4jRagSearchService newService(int headroom, double minScore) {
        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        return new LangChain4jRagSearchService(headroom, minScore,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
    }

    /** mock OpenAiEmbeddingModel：固定返回给定向量 + 用量 */
    private static OpenAiEmbeddingModel fixedModel(float[] vector) {
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(
                new Embedding(vector), new TokenUsage(7, 0, 7)));
        return model;
    }

    /** 构造某文档的分块 TextSegment（带 projectId/docId/可选 characterId/chunkIndex 元数据） */
    private static List<TextSegment> segments(long projectId, long docId, Long characterId, int count) {
        List<TextSegment> segs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put(VectorStoreService.META_PROJECT_ID, String.valueOf(projectId));
            if (characterId != null) {
                meta.put(VectorStoreService.META_CHARACTER_ID, String.valueOf(characterId));
            }
            meta.put(VectorStoreService.META_DOC_ID, String.valueOf(docId));
            meta.put(VectorStoreService.META_TITLE, "文档" + docId);
            meta.put(VectorStoreService.META_CHUNK_INDEX, i);
            segs.add(TextSegment.from("项目" + projectId + "文档" + docId + "块" + i, Metadata.from(meta)));
        }
        return segs;
    }

    /** 构造 count 条固定向量（与 query 同维度） */
    private static List<Embedding> embeddings(int count, float[] vector) {
        List<Embedding> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Embedding(vector));
        }
        return list;
    }

    /** 基础供应商配置（purpose=embedding） */
    private static ProviderConfig cfg() {
        return new ProviderConfig(5L, "https://api.example.com/v1", "sk", "emb", false);
    }

    /**
     * 未配置 embedding 供应商：返回空列表（上层文本降级），不产生任何工厂/向量库/trace/用量调用。
     */
    @Test
    void noEmbeddingConfigReturnsEmpty() {
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(null);
        LangChain4jRagSearchService svc = newService(4, 0);
        assertTrue(svc.search(1L, 2L, null, "查询", 3).isEmpty());
        verifyNoInteractions(factory, aiCallTraceService, usageLogService);
    }

    /**
     * 成功路径：向量库有项目级文档 → 检索命中 + trace/用量记账（scene=embedding 与旧路径同口径）。
     */
    @Test
    void successSearchesStoreAndRecordsAudit() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 1), embeddings(1, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        List<KnowledgeHit> hits = svc.search(1L, 2L, 3L, "查询", 3);

        // 命中：项目级文档 1 条
        assertEquals(1, hits.size());
        assertEquals(10L, hits.get(0).docId());
        // 审计：query 向量化记 scene=embedding trace + 用量（与旧路径同口径）
        verify(usageLogService).record(eq(1L), eq(2L), isNull(), isNull(), eq("emb"), eq("embedding"),
                eq(7), eq(0), eq(0), eq(0), anyInt());
        verify(trace).markSuccess(anyString(), eq("emb"), eq(7), eq(0), eq(0), eq(0), eq(5L));
        verify(aiCallTraceService).end(trace);
    }

    /**
     * 调用风暴抓修（2026-10-07 实测：同一 query 被 3 个并行 NPC 检索，embedding 重复 3 次）：
     * 同文本向量复用缓存——同供应商同模型同 query 只调用一次 model.embed，检索仍各自独立。
     */
    @Test
    void sameQueryEmbeddingCachedAcrossCalls() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 1), embeddings(1, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        // 模拟 3 个角色并行决策以同一 query 检索（实际为串行调用，缓存语义等价）
        svc.search(1L, 2L, 3L, "云来茶馆", 3);
        svc.search(1L, 2L, 9L, "云来茶馆", 3);
        svc.search(1L, 2L, 12L, "云来茶馆", 3);
        // 同文本只向量化一次（3 次检索共用第 1 次的向量），不再重复调用 embedding
        verify(model, org.mockito.Mockito.times(1)).embed(eq("云来茶馆"));
        // 每次检索都独立产生 trace/用量（审计不合并）
        verify(usageLogService, org.mockito.Mockito.times(3)).record(
                eq(1L), eq(2L), isNull(), isNull(), eq("emb"), eq("embedding"),
                anyInt(), eq(0), eq(0), eq(0), anyInt());
    }

    /**
     * 调用风暴抓修：不同 query 各自独立向量化（缓存按文本区分，不误合并）。
     */
    @Test
    void differentQueriesNotMergedByCache() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 2), embeddings(2, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        svc.search(1L, 2L, 3L, "云来茶馆", 3);
        svc.search(1L, 2L, 3L, "后山异象", 3);
        svc.search(1L, 2L, 3L, "粮价", 3);
        // 3 个不同文本 → 3 次独立向量化
        verify(model, org.mockito.Mockito.times(1)).embed(eq("云来茶馆"));
        verify(model, org.mockito.Mockito.times(1)).embed(eq("后山异象"));
        verify(model, org.mockito.Mockito.times(1)).embed(eq("粮价"));
    }

    /**
     * 元数据过滤正确（用户决策 Q7 双粒度语义）：项目级恒纳入 + 当前角色级合并，其他角色级剔除。
     */
    @Test
    void dualGranularityMergeFiltersByCharacter() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        // 用与测试同目录的 store 构造服务（保证同一 store 实例）
        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        // 项目级文档（无 characterId 键）×2 chunk + 角色 3 文档 ×1 + 其他角色 9 文档 ×1（应被剔除）
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 2), embeddings(2, new float[]{1f, 0f, 0f}));
        store.addChunks(2L, 11L, "角色设定", 3L, segments(2L, 11L, 3L, 1), embeddings(1, new float[]{1f, 0f, 0f}));
        store.addChunks(2L, 12L, "其他角色", 9L, segments(2L, 12L, 9L, 1), embeddings(1, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        List<KnowledgeHit> hits = svc.search(1L, 2L, 3L, "查询", 10);

        // 项目级 2 条 + 角色 3 的 1 条 = 3 条；其他角色 9 的 1 条被剔除
        assertEquals(3, hits.size());
        List<Long> docIds = hits.stream().map(KnowledgeHit::docId).sorted().toList();
        assertEquals(List.of(10L, 10L, 11L), docIds);
        assertTrue(hits.stream().noneMatch(h -> h.docId() == 12L), "其他角色级文档不应返回");
        // 角色级命中 characterId 标注；项目级为 null
        assertEquals(3L, hits.stream().filter(h -> h.docId() == 11L).findFirst().orElseThrow().characterId());
        assertEquals(null, hits.stream().filter(h -> h.docId() == 10L).findFirst().orElseThrow().characterId());
        // 项目级 chunk 文本保留
        assertTrue(hits.stream().filter(h -> h.docId() == 10L).findFirst().orElseThrow().text().contains("文档10"));
    }

    /**
     * 角色级过滤：characterId=null（仅项目级）时，角色级 chunk 全部剔除。
     */
    @Test
    void projectLevelOnlyWhenCharacterNull() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 1), embeddings(1, new float[]{1f, 0f, 0f}));
        store.addChunks(2L, 11L, "角色设定", 3L, segments(2L, 11L, 3L, 1), embeddings(1, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        List<KnowledgeHit> hits = svc.search(1L, 2L, null, "查询", 10);

        assertEquals(1, hits.size());
        assertEquals(10L, hits.get(0).docId());
    }

    /**
     * topK：按得分降序取前 k（项目级 2 条同分，topK=1 返回 1 条）。
     */
    @Test
    void topKRespected() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 2), embeddings(2, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        List<KnowledgeHit> hits = svc.search(1L, 2L, null, "查询", 1);
        assertEquals(1, hits.size());
    }

    /**
     * minScore：检索条数按 topK×headroom，minScore 过滤低相似命中（此处 query 与全部向量同向 → 不触发过滤，
     * 仅验证配置被接受且不报错）。
     */
    @Test
    void minScoreAccepted() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = fixedModel(new float[]{1f, 0f, 0f});
        when(factory.get(c)).thenReturn(model);
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        VectorStoreService store = new VectorStoreService(tempDir.toString(), "");
        store.addChunks(2L, 10L, "项目设定", null, segments(2L, 10L, null, 1), embeddings(1, new float[]{1f, 0f, 0f}));

        LangChain4jRagSearchService svc = new LangChain4jRagSearchService(4, 0.9,
                modelApiService, factory, store, aiCallTraceService, usageLogService);
        List<KnowledgeHit> hits = svc.search(1L, 2L, null, "查询", 3);
        assertEquals(1, hits.size(), "同向向量相似度≈1 应通过 minScore=0.9");
    }

    /**
     * 失败路径：query 向量化异常 → 空列表（上层降级），trace 记失败 + end，不记用量。
     */
    @Test
    void failureDegradesToEmpty() {
        ProviderConfig c = cfg();
        when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(c);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(factory.get(c)).thenReturn(model);
        when(model.embed(anyString())).thenThrow(new RuntimeException("api down"));
        AiCallTrace trace = mock(AiCallTrace.class);
        when(aiCallTraceService.beginScene(2L, "embedding")).thenReturn(trace);

        LangChain4jRagSearchService svc = newService(4, 0);
        assertTrue(svc.search(1L, 2L, null, "查询", 3).isEmpty());
        verify(trace).markFailure(any());
        verify(aiCallTraceService).end(trace);
        verify(usageLogService, never()).record(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }
}
