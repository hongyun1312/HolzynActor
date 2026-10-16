package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * docs 文档索引器单元测试（切片 / 黑名单 / 超长跳过 / embedding 供应商缺失降级注册表）。
 * <p>职责：验证——按章节切块、全量备份黑名单不索引、超大文件跳过、embedding 供应商未配置时
 * 仅建注册表（关键词降级数据源）不抛异常。</p>
 */
class DocsKnowledgeIndexerTest {

    @TempDir
    Path tempDir;

    private AssistantProperties properties;
    private DynamicEmbeddingModelFactory embeddingModelFactory;
    private VectorStoreService vectorStoreService;
    private ModelApiService modelApiService;
    private DocsKnowledgeIndexer indexer;

    @BeforeEach
    void setUp() {
        properties = new AssistantProperties();
        embeddingModelFactory = Mockito.mock(DynamicEmbeddingModelFactory.class);
        vectorStoreService = Mockito.mock(VectorStoreService.class);
        modelApiService = Mockito.mock(ModelApiService.class);
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(null);
        indexer = new DocsKnowledgeIndexer(properties, embeddingModelFactory, vectorStoreService, modelApiService);
    }

    @Test
    void chunkSplitsByHeadings() {
        List<String> chunks = DocsKnowledgeIndexer.chunk("# 青禾村\n村东水田有一片稻田。\n## 灵脉\n地脉深处有灵石。");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("青禾村"));
        assertTrue(chunks.get(1).contains("灵脉"));
    }

    @Test
    void chunkWindowsOversizedSection() {
        StringBuilder big = new StringBuilder("# 长文档\n");
        big.append("甲".repeat(2000));
        List<String> chunks = DocsKnowledgeIndexer.chunk(big.toString());
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 700));
    }

    @Test
    void ensureIndexedBuildsRegistryWithoutEmbeddingProvider() throws Exception {
        Files.writeString(tempDir.resolve("主对接文档.md"),
                "# 主对接文档\n\n项目级 AI 助手文档索引验证。\n## 当前阶段\nV2.0 规范化重构。",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("主对接文档_全量备份_20261010.md"),
                "# 备份\n不应被索引。", StandardCharsets.UTF_8);
        properties.setDocsIndexDir(tempDir.toString());

        indexer.ensureIndexed(1L);

        assertEquals(1, indexer.docCount());
        assertTrue(indexer.chunkCount() > 0);
        // 黑名单文件不进入注册表
        assertTrue(indexer.chunks().stream().noneMatch(c -> c.path().contains("全量备份")));
        // embedding 供应商缺失：仅注册表，不调用向量库
        Mockito.verify(vectorStoreService, Mockito.never()).addChunks(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.isNull(), Mockito.anyList(), Mockito.anyList());
    }

    @Test
    void oversizedFileIsSkipped() throws Exception {
        StringBuilder big = new StringBuilder("# 超大\n");
        big.append("长".repeat(300_000));
        Files.writeString(tempDir.resolve("超大文档.md"), big.toString(), StandardCharsets.UTF_8);
        properties.setDocsIndexDir(tempDir.toString());
        properties.setDocsMaxFileChars(200_000);

        indexer.ensureIndexed(1L);

        assertEquals(0, indexer.docCount());
    }

    @Test
    void rebuildIsIdempotent() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# A\n内容甲。", StandardCharsets.UTF_8);
        properties.setDocsIndexDir(tempDir.toString());
        indexer.ensureIndexed(1L);
        indexer.rebuild(1L);
        assertEquals(1, indexer.docCount());
        assertTrue(indexer.chunkCount() > 0);
    }
}
