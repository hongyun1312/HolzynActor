package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 项目文档检索服务单元测试（向量缺失 → 关键词降级 + 来源标注；工具函数）。
 * <p>职责：验证——embedding 供应商未配置时降级关键词检索并标注「检索降级」、
 * 索引未启用返回空、术语抽取与打分。</p>
 */
class AssistantDocsRagServiceTest {

    private DocsKnowledgeIndexer indexer;
    private VectorStoreService vectorStoreService;
    private DynamicEmbeddingModelFactory embeddingModelFactory;
    private ModelApiService modelApiService;
    private AssistantProperties properties;
    private AssistantDocsRagService service;

    @BeforeEach
    void setUp() {
        indexer = Mockito.mock(DocsKnowledgeIndexer.class);
        vectorStoreService = Mockito.mock(VectorStoreService.class);
        embeddingModelFactory = Mockito.mock(DynamicEmbeddingModelFactory.class);
        modelApiService = Mockito.mock(ModelApiService.class);
        properties = new AssistantProperties();
        service = new AssistantDocsRagService(indexer, vectorStoreService, embeddingModelFactory,
                modelApiService, properties);
    }

    @Test
    void degradeToKeywordSearchWhenNoEmbeddingProvider() {
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(null);
        when(indexer.chunks()).thenReturn(List.of(
                new DocsKnowledgeIndexer.DocChunk("docs/主对接文档.md", "主对接文档.md §1",
                        "记忆系统分为 L1 短期、L2 情景、L3 核心三层，L3 只放不可遗忘的核心记忆。"),
                new DocsKnowledgeIndexer.DocChunk("docs/设计.md", "设计.md §2",
                        "灵脉是这个世界力量体系的根基，位于地脉深处。")));

        var hits = service.search(1L, "记忆系统 三层");

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text().contains("L3"));
        assertTrue(hits.get(0).source().source().contains("关键词检索（降级）"));
    }

    @Test
    void disabledDocsIndexReturnsEmpty() {
        properties.setDocsIndexEnabled(false);
        var hits = service.search(1L, "任意内容");
        assertEquals(0, hits.size());
    }

    @Test
    void blankQueryReturnsEmpty() {
        var hits = service.search(1L, "  ");
        assertEquals(0, hits.size());
    }

    @Test
    void noChunksReturnsEmpty() {
        when(indexer.chunks()).thenReturn(List.of());
        var hits = service.search(1L, "记忆");
        assertEquals(0, hits.size());
    }

    @Test
    void extractTermsSplitsChinese() {
        var terms = AssistantDocsRagService.extractTerms("灵脉 在哪里？");
        assertTrue(terms.contains("灵脉"));
        assertTrue(terms.contains("在哪里"));
    }

    @Test
    void scoreRewardsTermPresence() {
        double s = AssistantDocsRagService.score("青禾村的灵脉灵石", List.of("灵脉"));
        assertTrue(s > 0);
        assertEquals(0.0, AssistantDocsRagService.score("完全无关内容", List.of("灵脉")));
    }
}
