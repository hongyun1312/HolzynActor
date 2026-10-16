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

package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * docs/ 文档索引器（切片 + mtime 增量 + <b>向量化异步</b> + 向量库落库）。
 * <p>职责：扫描 {@code holzyn.actor.assistant.docs-index-dir}（默认 ../docs）下 Markdown，
 * 按章节切块并维护进程内注册表（关键词降级数据源），向量化经 {@link DynamicEmbeddingModelFactory}
 * 写入 {@link VectorStoreService} 的 <b>保留伪项目键 {@link AssistantDocsRagService#DOCS_PROJECT_ID}</b>。</p>
 * <p><b>异步向量化（冒烟抓修 2026-10-12）</b>：docs 全量向量化是大工程（实测 105 文档 / 5130 切片，
 * 远端 embedding 需数分钟）——<b>绝不能同步阻塞对话请求</b>（首次触发的聊天会被卡死直至超时）。
 * 因此：切片注册表<b>同步</b>毫秒级就绪（关键词降级即时可用），embedding+落库在<b>虚拟线程后台</b>执行；
 * 检索优先向量、未就绪降级关键词（见 {@link AssistantDocsRagService#search}）。后台构建互斥
 * （volatile 标志），失败仅记 lastError 不阻断。</p>
 * <p><b>增量语义</b>：注册表同步按 mtime 增量（变更/新增/删除）；向量侧由后台任务重嵌变更文档
 * （{@link VectorStoreService#addChunks} 按 docId 替换）。文档超长/黑名单（*_全量备份_*.md）跳过。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocsKnowledgeIndexer {

    /** 向量库元数据键：章节标题（此处存「相对路径 + 章节」便于来源展示）。 */
    private static final String META_TITLE = VectorStoreService.META_TITLE;

    /** 黑名单：全量备份等重复源文件（跳过，避免文档与备份内容重复命中）。 */
    private static final String[] EXCLUDE_PATTERNS = {
            "_全量备份_", "主对接文档_全量备份"
    };

    /** 进程内 chunk 注册表（关键词降级 + 来源展示；同步就绪）。 */
    private final List<DocChunk> chunks = new ArrayList<>();

    /** 文件 mtime 快照（相对路径 → lastModified）。 */
    private final Map<String, Long> mtimes = new ConcurrentHashMap<>();

    /** 相对路径 → docId（向量库替换/删除粒度）。 */
    private final Map<String, Long> docIds = new ConcurrentHashMap<>();

    /** 注册表是否已就绪（懒加载一次性标记）。 */
    private volatile boolean ready;

    /** 最近一次索引错误（状态展示用）。 */
    private volatile String lastError;

    /** embedding 累计调用 token（可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong embedTokens = new java.util.concurrent.atomic.AtomicLong();

    /** 向量后台构建互斥标志（同一时刻只跑一次全量/增量向量化）。 */
    private volatile boolean vectorBuildRunning;

    /** 向量后台执行器（虚拟线程；docs 向量化是 IO 密集，异步避免阻塞对话请求）。 */
    private final java.util.concurrent.ExecutorService vectorExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final AssistantProperties properties;
    private final DynamicEmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreService vectorStoreService;
    private final ModelApiService modelApiService;

    /**
     * 确保注册表就绪（同步、毫秒级）并触发向量后台构建（幂等；并发安全）。
     *
     * @param projectId 项目 ID（embedding 供应商解析：项目级优先、用户级兜底）
     */
    public void ensureIndexed(Long projectId) {
        if (!properties.isDocsIndexEnabled()) {
            return;
        }
        synchronized (this) {
            if (!ready) {
                scanAndSlice();
                ready = true;
            } else {
                incrementalSlice();
            }
        }
        startVectorBuild(projectId);
    }

    /** 手动全量重建（清注册表 + 清向量库 + 重新切片 + 触发后台向量化）。 */
    public synchronized void rebuild(Long projectId) {
        chunks.clear();
        mtimes.clear();
        docIds.clear();
        ready = false;
        vectorStoreService.removeProject(AssistantDocsRagService.DOCS_PROJECT_ID);
        ensureIndexed(projectId);
    }

    /** 同步切片：全量扫描（首次）。 */
    private void scanAndSlice() {
        List<Path> files = scanFiles();
        for (Path f : files) {
            try {
                sliceDoc(f, rel(f), lastModified(f));
            } catch (Exception e) {
                log.warn("[助手·docs索引] 文档切片失败（跳过）: {} : {}", f, e.getMessage());
            }
        }
        log.info("[助手·docs索引] 注册表就绪：{} 个文档 {} 个切片（同步，未阻塞）", docIds.size(), chunks.size());
    }

    /** 同步增量：变更/新增重切片、删除清理（仅注册表；向量侧由后台重嵌）。 */
    private void incrementalSlice() {
        try {
            List<Path> files = scanFiles();
            for (Path f : files) {
                String rel = rel(f);
                long mtime = lastModified(f);
                Long old = mtimes.get(rel);
                if (old != null && old == mtime) {
                    continue;
                }
                sliceDoc(f, rel, mtime);
            }
            List<String> removed = new ArrayList<>();
            for (String rel : new ArrayList<>(mtimes.keySet())) {
                boolean exists = files.stream().anyMatch(f -> rel(f).equals(rel));
                if (!exists) {
                    Long docId = docIds.remove(rel);
                    if (docId != null) {
                        vectorStoreService.removeDoc(AssistantDocsRagService.DOCS_PROJECT_ID, docId);
                    }
                    chunks.removeIf(c -> c.path().equals(rel));
                    mtimes.remove(rel);
                    removed.add(rel);
                }
            }
            if (!removed.isEmpty()) {
                log.info("[助手·docs索引] 增量删除 {} 个文档: {}", removed.size(), removed);
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("[助手·docs索引] 增量失败: {}", e.getMessage());
        }
    }

    /** 触发后台向量构建（幂等：无 embedding 供应商 / 已在跑 / 无文档 → 跳过）。 */
    private void startVectorBuild(Long projectId) {
        if (vectorBuildRunning) {
            return;
        }
        ProviderConfig cfg;
        try {
            cfg = modelApiService.resolveEmbeddingProvider(1L, projectId);
        } catch (Exception e) {
            log.warn("[助手·docs索引] embedding 供应商解析失败，仅关键词检索: {}", e.getMessage());
            return;
        }
        if (cfg == null) {
            return;
        }
        synchronized (this) {
            if (vectorBuildRunning) {
                return;
            }
            if (docIds.isEmpty()) {
                return;
            }
            vectorBuildRunning = true;
        }
        vectorExecutor.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                embedAll(projectId);
                log.info("[助手·docs索引] 后台向量化完成：{} 文档 / 向量库 {} 条（{}ms，embedding {} tokens）",
                        docIds.size(), vectorStoreService.count(AssistantDocsRagService.DOCS_PROJECT_ID),
                        System.currentTimeMillis() - t0, embedTokens.get());
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[助手·docs索引] 后台向量化失败（检索降级关键词）: {}", e.getMessage());
            } finally {
                vectorBuildRunning = false;
            }
        });
    }

    /** 后台任务：按注册表逐个文档 embedding + 向量库落库（docId 替换幂等）。 */
    private void embedAll(Long projectId) {
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(1L, projectId);
        if (cfg == null) {
            return;
        }
        for (Map.Entry<String, Long> e : new LinkedHashMap<>(docIds).entrySet()) {
            String rel = e.getKey();
            Path f = Path.of(properties.getDocsIndexDir()).resolve(rel).normalize();
            if (!Files.isRegularFile(f)) {
                continue;
            }
            embedDoc(cfg, f, rel, e.getValue());
        }
    }

    /** 单个文档切片（注册表写入；不含 embedding）。 */
    private void sliceDoc(Path f, String rel, long mtime) {
        String text = readFile(f);
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> sectionTexts = chunk(text);
        if (sectionTexts.isEmpty()) {
            return;
        }
        long docId = docIdOf(rel);
        chunks.removeIf(c -> c.path().equals(rel));
        for (int i = 0; i < sectionTexts.size(); i++) {
            chunks.add(new DocChunk(rel, rel + " §" + (i + 1), sectionTexts.get(i)));
        }
        docIds.put(rel, docId);
        mtimes.put(rel, mtime);
    }

    /** 单个文档：切片 → embedding → 向量库替换（docId 替换幂等）。 */
    private void embedDoc(ProviderConfig cfg, Path f, String rel, long docId) {
        String text = readFile(f);
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> sectionTexts = chunk(text);
        if (sectionTexts.isEmpty()) {
            return;
        }
        List<TextSegment> segments = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < sectionTexts.size(); i++) {
            String seg = sectionTexts.get(i);
            Metadata meta = new Metadata();
            meta.put(VectorStoreService.META_PROJECT_ID, String.valueOf(AssistantDocsRagService.DOCS_PROJECT_ID));
            meta.put(VectorStoreService.META_DOC_ID, String.valueOf(docId));
            meta.put(VectorStoreService.META_TITLE, rel + " §" + (i + 1));
            segments.add(new TextSegment(seg, meta));
            try {
                EmbeddingModel model = embeddingModelFactory.get(cfg);
                dev.langchain4j.model.output.Response<Embedding> resp = model.embed(seg);
                Embedding emb = resp.content();
                if (emb != null) {
                    embeddings.add(emb);
                    dev.langchain4j.model.output.TokenUsage tu = resp.tokenUsage();
                    int in = tu == null || tu.inputTokenCount() == null ? 0 : tu.inputTokenCount();
                    embedTokens.addAndGet(Math.max(in, 0));
                } else {
                    embeddings.add(null);
                }
            } catch (Exception e) {
                embeddings.add(null);
            }
        }
        if (embeddings.size() == segments.size() && embeddings.stream().allMatch(java.util.Objects::nonNull)) {
            vectorStoreService.addChunks(AssistantDocsRagService.DOCS_PROJECT_ID, docId, rel, null, segments, embeddings);
        } else {
            log.warn("[助手·docs索引] 文档 embedding 部分失败，跳过该文档向量（关键词仍可用）: {}", rel);
        }
    }

    /** 扫描 docs 目录下 Markdown（跳过黑名单与超大文件）。 */
    private List<Path> scanFiles() {
        String dir = properties.getDocsIndexDir();
        if (dir == null || dir.isBlank()) {
            return List.of();
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        for (String ex : EXCLUDE_PATTERNS) {
                            if (name.contains(ex)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(f -> sizeOf(f) <= properties.getDocsMaxFileChars())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            log.warn("[助手·docs索引] 扫描目录失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按章节切块（保留标题；超长章节再按窗口切，带重叠）。 */
    static List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = text.replace("\r\n", "\n");
        List<String> sections = new ArrayList<>();
        String[] lines = t.split("\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            boolean heading = line.matches("^#{1,4}\\s+.*");
            if (heading && cur.length() > 0) {
                String s = cur.toString().trim();
                if (!s.isBlank()) {
                    sections.add(s);
                }
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        String last = cur.toString().trim();
        if (!last.isBlank()) {
            sections.add(last);
        }
        // 超长章节按窗口切（带重叠）
        List<String> out = new ArrayList<>();
        for (String s : sections) {
            if (s.length() <= 600) {
                out.add(s);
            } else {
                int step = 440;
                for (int i = 0; i < s.length(); i += step) {
                    out.add(s.substring(i, Math.min(s.length(), i + 600)));
                }
            }
        }
        return out;
    }

    /** 相对路径（docs/ 根为基准）。 */
    private String rel(Path f) {
        String root = Path.of(properties.getDocsIndexDir()).toAbsolutePath().normalize().toString();
        String abs = f.toAbsolutePath().normalize().toString();
        String r = abs.startsWith(root) ? abs.substring(root.length()) : f.getFileName().toString();
        return r.replace('\\', '/').replaceAll("^/+", "");
    }

    /** 稳定 docId：相对路径 hash → 正 Long。 */
    private static long docIdOf(String rel) {
        long h = rel.hashCode() & 0x7fffffffL;
        return h == 0 ? 1 : h;
    }

    private static String readFile(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static long lastModified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 进程内 chunk 数（关键词降级/状态展示）。 */
    public int chunkCount() {
        return chunks.size();
    }

    /** 进程内文档数。 */
    public int docCount() {
        return docIds.size();
    }

    /** 最近一次索引错误（可空）。 */
    public String lastError() {
        return lastError;
    }

    /** 向量后台构建是否进行中（状态展示用）。 */
    public boolean isVectorBuildRunning() {
        return vectorBuildRunning;
    }

    /** 进程内 chunk 注册表（关键词降级用；只读快照）。 */
    public List<DocChunk> chunks() {
        synchronized (this) {
            return List.copyOf(chunks);
        }
    }

    /**
     * 文档切片（关键词降级 + 来源展示）。
     *
     * @param path  文档相对路径
     * @param title 来源标题（路径 + 章节）
     * @param text  切片正文
     */
    public record DocChunk(String path, String title, String text) {
    }
}
