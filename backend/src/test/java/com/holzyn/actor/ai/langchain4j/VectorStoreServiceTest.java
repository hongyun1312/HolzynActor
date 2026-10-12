package com.holzyn.actor.ai.langchain4j;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VectorStoreService 单元测试（AI 接入层重构 M3，2026-09-05，方案 §9 M3 / §5.2，用户决策 Q3/Q6/Q9）。
 * <p>职责：验证向量库——①无文件懒加载空 store；②addChunks 原子落盘 + 重启新实例免重加载
 * （从文件反序列化）；③多项目隔离（各自文件）；④按 docId 替换（重新向量化不残留旧 chunk，用户决策 Q9）；
 * ⑤removeDoc 按元数据过滤删除（重启后文件加载态同样生效）；⑥损坏文件回退空 store + 可再写入覆盖；
 * ⑦removeProject 删文件；⑧数量不一致忽略。</p>
 */
class VectorStoreServiceTest {

    /** 临时数据目录（每用例独立；向量库根 = <temp>/vector-store） */
    @TempDir
    Path tempDir;

    private VectorStoreService newService() {
        return new VectorStoreService(tempDir.toString(), "");
    }

    /** 构造某文档的分块 TextSegment（带 projectId/docId/chunkIndex 元数据） */
    private static List<TextSegment> segments(long projectId, long docId, int count) {
        List<TextSegment> segs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put(VectorStoreService.META_PROJECT_ID, String.valueOf(projectId));
            meta.put(VectorStoreService.META_DOC_ID, String.valueOf(docId));
            meta.put(VectorStoreService.META_CHUNK_INDEX, i);
            segs.add(TextSegment.from("文本块" + i, Metadata.from(meta)));
        }
        return segs;
    }

    /** 构造 count 条向量 */
    private static List<Embedding> embeddings(int count) {
        List<Embedding> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Embedding(new float[]{1.0f, i}));
        }
        return list;
    }

    /**
     * 懒加载：无文件时返回空 store（0 条），不抛异常。
     */
    @Test
    void storeForCreatesEmptyWhenNoFile() {
        VectorStoreService service = newService();
        assertEquals(0, service.count(1L));
        assertFalse(service.hasPersistedStore(1L));
    }

    /**
     * 原子落盘 + 重启免重加载：addChunks 后新实例从文件反序列化得到同量向量。
     */
    @Test
    void addChunksPersistsAndReloads() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        assertTrue(s1.hasPersistedStore(1L));
        // 新实例（模拟重启）：懒加载从文件读取，无需重新 embedding
        VectorStoreService s2 = newService();
        assertEquals(2, s2.count(1L));
    }

    /**
     * 多项目隔离：各项目独立 store 文件，互不串扰。
     */
    @Test
    void multiProjectIsolation() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T1", null, segments(1L, 10L, 2), embeddings(2));
        s1.addChunks(2L, 20L, "T2", null, segments(2L, 20L, 3), embeddings(3));
        VectorStoreService s2 = newService();
        assertEquals(2, s2.count(1L));
        assertEquals(3, s2.count(2L));
    }

    /**
     * 按 docId 替换（用户决策 Q9）：同文档重新向量化只保留最新分块；其他文档不受影响。
     */
    @Test
    void replaceByDocIdKeepsOnlyLatest() {
        VectorStoreService service = newService();
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 3), embeddings(3));
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        assertEquals(2, service.count(1L), "同 docId 应替换：只保留最新 2 块");
        service.addChunks(1L, 11L, "T2", null, segments(1L, 11L, 1), embeddings(1));
        assertEquals(3, service.count(1L), "不同 docId 不受替换影响");
    }

    /**
     * removeDoc：只删该文档 chunk；且重启后（文件加载态）经元数据过滤同样生效。
     */
    @Test
    void removeDocRemovesOnlyThatDoc() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        s1.addChunks(1L, 11L, "T2", null, segments(1L, 11L, 1), embeddings(1));
        s1.removeDoc(1L, 10L);
        assertEquals(1, s1.count(1L));
        // 重启新实例：removeDoc 的元数据过滤对文件加载态同样生效
        VectorStoreService s2 = newService();
        s2.removeDoc(1L, 11L);
        assertEquals(0, s2.count(1L));
    }

    /**
     * 损坏文件兜底：非法 JSON → 回退空 store + WARN，不抛异常；空 store 可再写入覆盖损坏文件。
     */
    @Test
    void corruptFileFallsBackToEmptyAndCanRewrite() throws Exception {
        Path dir = tempDir.resolve("vector-store").resolve("99");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("embeddings.json"), "{broken json!!!");
        VectorStoreService service = newService();
        assertEquals(0, service.count(99L), "损坏文件应回退空 store");
        service.addChunks(99L, 100L, "T", null, segments(99L, 100L, 1), embeddings(1));
        assertEquals(1, service.count(99L), "损坏文件上可正常写入覆盖");
    }

    /**
     * removeProject：内存驱逐 + 删除文件，后续访问回退空 store。
     */
    @Test
    void removeProjectDeletesFile() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        s1.removeProject(1L);
        assertFalse(s1.hasPersistedStore(1L));
        assertEquals(0, newService().count(1L));
    }

    /**
     * 数量不一致忽略：segments 与 embeddings 条数不同 → 不写入不落盘。
     */
    @Test
    void addChunksMismatchIgnored() {
        VectorStoreService service = newService();
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(1));
        assertEquals(0, service.count(1L));
        assertFalse(service.hasPersistedStore(1L));
    }

    // ==================== M4：docId 索引（知识页四态判定） ====================

    /**
     * docChunkCount/hasDoc：写入后按 docId 计数正确；未入库文档返回 0。
     */
    @Test
    void docChunkCountAndHasDoc() {
        VectorStoreService service = newService();
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        service.addChunks(1L, 11L, "T2", null, segments(1L, 11L, 3), embeddings(3));
        assertEquals(2, service.docChunkCount(1L, 10L));
        assertEquals(3, service.docChunkCount(1L, 11L));
        assertTrue(service.hasDoc(1L, 10L));
        assertEquals(0, service.docChunkCount(1L, 99L), "未入库文档应返回 0");
        assertFalse(service.hasDoc(1L, 99L));
    }

    /**
     * docChunkCount 按 docId 替换：重新向量化后计数更新为最新分块数。
     */
    @Test
    void docChunkCountFollowsReplace() {
        VectorStoreService service = newService();
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 3), embeddings(3));
        assertEquals(3, service.docChunkCount(1L, 10L));
        service.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        assertEquals(2, service.docChunkCount(1L, 10L), "替换后应更新为最新分块数");
    }

    /**
     * docChunkCount 重启重建：新实例懒加载后索引从文件 JSON 重建，计数正确。
     */
    @Test
    void docChunkCountRebuiltAfterReload() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        s1.addChunks(1L, 11L, "T2", null, segments(1L, 11L, 3), embeddings(3));
        // 新实例（模拟重启）：懒加载从文件读取，docId 索引重建
        VectorStoreService s2 = newService();
        assertEquals(2, s2.docChunkCount(1L, 10L));
        assertEquals(3, s2.docChunkCount(1L, 11L));
        assertEquals(0, s2.docChunkCount(1L, 99L));
    }

    /**
     * removeDoc 同步清索引：删除后 docChunkCount 归 0；removeProject 清空全部索引。
     */
    @Test
    void removeDocAndProjectClearIndex() {
        VectorStoreService s1 = newService();
        s1.addChunks(1L, 10L, "T", null, segments(1L, 10L, 2), embeddings(2));
        s1.addChunks(1L, 11L, "T2", null, segments(1L, 11L, 1), embeddings(1));
        s1.removeDoc(1L, 10L);
        assertEquals(0, s1.docChunkCount(1L, 10L));
        assertEquals(1, s1.docChunkCount(1L, 11L));
        s1.removeProject(1L);
        assertEquals(0, s1.docChunkCount(1L, 11L));
    }
}
