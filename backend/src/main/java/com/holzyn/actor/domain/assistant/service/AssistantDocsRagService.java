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
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目文档检索服务（docs/ 向量 RAG + 关键词降级）。
 * <p>职责：docs/ 目录 Markdown 文档的<b>向量化检索</b>（query → embedding → {@link VectorStoreService}
 * 相似度检索）与<b>关键词降级</b>（embedding 供应商未配置/向量化失败 → 本地关键词打分，
 * 并标注「检索降级」来源）。</p>
 * <p><b>索引生命周期</b>：懒加载 + 增量重建——首次检索触发全量切片+embedding，此后按文件
 * mtime 增量（新增/变更重嵌、删除清理）；索引与向量均落在既有 {@link VectorStoreService}
 * （每项目一个 store 的持久化 JSON），docs 使用<b>保留伪项目键 {@link #DOCS_PROJECT_ID}</b>
 * 与真实项目隔离（docs 是应用级知识，非项目级）。</p>
 * <p><b>审计</b>：query 向量化走 scene=docs_embedding 独立 trace + 用量记账（对齐
 * LangChain4jRagSearchService 口径）；检索命中标注来源 = 文档相对路径 + 章节。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantDocsRagService {

    /** docs 向量库保留伪项目键（与真实项目 id 空间隔离；真实项目 id ≥1） */
    public static final long DOCS_PROJECT_ID = -1L;

    /** 章节标题正则（Markdown 一级~四级标题）。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,4})\\s+(.+)$");

    /** 查询向量复用缓存（文本→向量；文档量大时防同 query 重复 embedding）。 */
    private final Map<String, Embedding> queryVectorCache = new ConcurrentHashMap<>();

    /** 全量索引/重建互斥锁（防并发重复 embedding）。 */
    private final ReentrantLock indexLock = new ReentrantLock();

    /** 文档索引器（切片/embedding/落库/增量）。 */
    private final DocsKnowledgeIndexer indexer;

    /** 向量库服务（docs 检索落地）。 */
    private final VectorStoreService vectorStoreService;

    /** LangChain4j embedding 模型工厂。 */
    private final DynamicEmbeddingModelFactory embeddingModelFactory;

    /** 供应商运行时配置服务（resolveEmbeddingProvider）。 */
    private final ModelApiService modelApiService;

    /** 助手配置。 */
    private final AssistantProperties properties;

    /**
     * 检索项目文档（向量优先，关键词降级）。
     *
     * @param projectId 项目 ID（索引/embedding 供应商解析用）
     * @param query     检索关键词或问题
     *
     * @return 命中列表（text + 来源标注）
     */
    public List<Hit> search(Long projectId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!properties.isDocsIndexEnabled()) {
            return List.of();
        }
        try {
            indexer.ensureIndexed(projectId);
        } catch (Exception e) {
            log.warn("[助手·文档检索] docs 索引就绪失败，走关键词降级: {}", e.getMessage());
        }
        List<Hit> vectorHits = searchVector(projectId, query);
        if (!vectorHits.isEmpty()) {
            return vectorHits;
        }
        // 降级：关键词检索（标注降级来源）
        return searchKeyword(query);
    }

    /** 向量检索（embedding 供应商未配置/失败 → 空列表由上层降级关键词）。 */
    private List<Hit> searchVector(Long projectId, String query) {
        try {
            ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(1L, projectId);
            if (cfg == null) {
                return List.of();
            }
            Embedding queryVec = queryVector(query, cfg);
            if (queryVec == null) {
                return List.of();
            }
            int topK = Math.max(1, properties.getDocsSearchTopK());
            var store = vectorStoreService.storeFor(DOCS_PROJECT_ID);
            var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryVec)
                    .maxResults(topK * 3)
                    .minScore(0.0)
                    .filter(dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                            .metadataKey(VectorStoreService.META_PROJECT_ID)
                            .isEqualTo(String.valueOf(DOCS_PROJECT_ID)))
                    .build();
            var result = store.search(request);
            List<Hit> hits = new ArrayList<>();
            for (var m : result.matches()) {
                if (m == null || m.embedded() == null) {
                    continue;
                }
                TextSegment seg = m.embedded();
                Metadata meta = seg.metadata();
                String path = meta == null ? null : meta.getString(VectorStoreService.META_TITLE);
                Double score = m.score();
                hits.add(new Hit(seg.text() == null ? "" : seg.text(),
                        new AssistantSourceVO("项目文档·向量检索",
                                (path == null || path.isBlank() ? "docs/?" : path)
                                        + "（相关度 " + (score == null ? 0 : String.format(Locale.ROOT, "%.2f", score)) + "）")));
            }
            hits.sort(Comparator.comparingDouble((Hit h) -> scoreOf(h.source().detail())).reversed());
            return hits.stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("[助手·文档检索] 向量检索失败（降级关键词）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 从来源 detail 里解析相关度数字（排序用）。 */
    private static double scoreOf(String detail) {
        if (detail == null) {
            return 0;
        }
        Matcher m = Pattern.compile("相关度 ([0-9.]+)").matcher(detail);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /** 关键词降级检索（本地打分；来源标注「关键词检索·降级」）。 */
    private List<Hit> searchKeyword(String query) {
        try {
            List<DocsKnowledgeIndexer.DocChunk> chunks = indexer.chunks();
            if (chunks.isEmpty()) {
                return List.of();
            }
            List<String> terms = extractTerms(query);
            List<Map.Entry<DocsKnowledgeIndexer.DocChunk, Double>> scored = new ArrayList<>();
            for (DocsKnowledgeIndexer.DocChunk c : chunks) {
                double s = score(c.text(), terms);
                if (s > 0) {
                    scored.add(Map.entry(c, s));
                }
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            int topK = Math.max(1, properties.getDocsSearchTopK());
            return scored.stream().limit(topK)
                    .map(e -> new Hit(e.getKey().text(),
                            new AssistantSourceVO("项目文档·关键词检索（降级）",
                                    e.getKey().path())))
                    .toList();
        } catch (Exception e) {
            log.warn("[助手·文档检索] 关键词降级失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 文本关键词打分（术语包含加权 + 字符共现）。 */
    static double score(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return 0;
        }
        String t = text.toLowerCase(Locale.ROOT);
        double s = 0;
        for (String term : terms) {
            if (term.length() >= 2 && t.contains(term)) {
                s += 2 + Math.min(4, count(t, term));
            }
        }
        return s;
    }

    /** 非重叠出现次数。 */
    private static int count(String text, String term) {
        int c = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) {
            c++;
            idx += term.length();
        }
        return c;
    }

    /** 抽取检索术语（空白/标点切分 → 2+ 字词；无词时退化为字符二元组）。 */
    static List<String> extractTerms(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String part : q.split("[\\s，。！？、；：,.!?;:()（）\\[\\]\\{\\}“”\"'\\-—]")) {
            String p = part.trim();
            if (p.length() >= 2) {
                terms.add(p);
            }
        }
        if (terms.isEmpty() && q.length() >= 2) {
            for (int i = 0; i + 1 < q.length(); i++) {
                terms.add(q.substring(i, i + 2));
            }
        }
        return terms;
    }

    /** 查询向量化（带同文本复用缓存）。 */
    private Embedding queryVector(String query, ProviderConfig cfg) {
        String key = cfg.id() + "|" + cfg.model() + "|" + query;
        Embedding cached = queryVectorCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            EmbeddingModel model = embeddingModelFactory.get(cfg);
            Response<Embedding> resp = model.embed(query);
            Embedding emb = resp.content();
            if (emb != null && queryVectorCache.size() < 2000) {
                queryVectorCache.put(key, emb);
            }
            return emb;
        } catch (Exception e) {
            log.warn("[助手·文档检索] 查询向量化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 索引状态（设置页/调试展示）。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isDocsIndexEnabled());
        out.put("indexDir", properties.getDocsIndexDir());
        out.put("chunkCount", indexer.chunkCount());
        out.put("docCount", indexer.docCount());
        out.put("vectorStoreChunks", vectorStoreService.count(DOCS_PROJECT_ID));
        out.put("vectorBuilding", indexer.isVectorBuildRunning());
        out.put("lastError", indexer.lastError());
        return out;
    }

    /** 手动全量重建。 */
    public Map<String, Object> rebuild(Long projectId) {
        Map<String, Object> before = status();
        try {
            indexLock.lock();
            indexer.rebuild(projectId);
        } finally {
            indexLock.unlock();
        }
        Map<String, Object> after = status();
        after.put("before", before);
        return after;
    }

    /**
     * 检索命中。
     *
     * @param text    命中片段
     * @param source  来源标注（source/detail）
     */
    public record Hit(String text, AssistantSourceVO source) {
    }
}
