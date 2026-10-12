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

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.langchain4j.RagSearchService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识检索服务。
 * <p>职责：实现知识文档的「分块 → embedding 入库 → top-k 检索」全链路——
 * ① 分块：按段落/句子切分（默认约 500 字/块 + 50 字重叠，逻辑抽至 {@link KnowledgeChunker}）；
 * ② 入库：AI 接入层重构 起改走 {@link KnowledgeVectorizerService}（LangChain4j embedding +
 * VectorStoreService 向量库）；<b>起旧 embedding 列已物理删除，无旧列双写</b>；
 * ③ 检索：<b>AI 接入层重构 ~</b>——RagSearchService（LangChain4j 向量库检索，query 经
 * DynamicEmbeddingModelFactory 向量化 + scene=embedding trace/用量记账 + 双粒度合并），
 * 未配置 embedding / 新路径失败 → 文本关键词检索降级 + 提示（textScore/snippetAround 保留）；
 * <b>起兼容壳 A/B 移除</b>（旧余弦路径随 EmbeddingService/旧列物理删除，langchain4j-enabled
 * 开关一并移除，本服务直接委托 RagSearchService）。</p>
 * <p>纯逻辑（分块/文本打分）抽为静态方法便于单元测试。</p>
 * <p><b>世界运行抓修（用户报障「一大部分请求被打到向量模型上」）</b>：感知侧 每次
 * 组装感知文本都会以「所在地 + 当前活动」为 query 检索一次知识（{@code PerceptionService.knowledgeHints}），
 * 而这个 query 在角色没换地点/没换活动时会<b>逐游戏小时完全相同</b>——实测 15 分钟内 240 次检索只有
 * 53 条不同 query（4.5 倍重复），每次都重新向量化 + 重新检索同一份静态知识库。
 * 现加 <b>query→结果缓存</b>：知识库不变 ⇒ 同问同答（这是只读检索、不是决策，不存在"回放旧情境"的
 * 真实性问题，与语义缓存有本质区别）；知识文档写入/删除时由 {@link KnowledgeVectorizerService}
 * 调用 {@link #invalidate(Long)} 显式失效，另有 TTL 兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final ActorKnowledgeDocRepository docRepository;
    private final ModelApiService modelApiService;
    /** LangChain4j RAG 检索路径 */
    private final RagSearchService ragSearchService;

    /** 检索结果缓存开关（默认 true；false=每次检索都走完整路径，退回抓修前行为） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.query-cache-enabled:true}")
    private boolean queryCacheEnabled = true;

    /** 缓存容量上限（默认 2000 条；超限整体清空——条目极小且重建代价低，无需精细 LRU） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.query-cache-max:2000}")
    private int queryCacheMax = 2000;

    /** 缓存有效期（真实毫秒，默认 300000=5 分钟；知识写入另有显式失效，此处只兜底"漏失效"） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.query-cache-ttl-ms:300000}")
    private long queryCacheTtlMs = 300_000L;

    /** 多路召回开关（默认 true：原 query 向量检索 + 话题词独立向量检索合并去重取 topK） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.multi-recall-enabled:true}")
    private boolean multiRecallEnabled = true;

    /** 话题词数量上限（默认 2；每个话题词 = 一次额外向量检索；0=关闭多路） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.topic-limit:2}")
    private int topicLimit = 2;

    /** 话题词索引缓存有效期（真实毫秒，默认 600000=10 分钟；知识库变更经 invalidate 显式失效） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.knowledge.topic-index-ttl-ms:600000}")
    private long topicIndexTtlMs = 600_000L;

    /** query→检索结果缓存（键含 项目|角色|topK|query；值=该次检索的完整命中列表） */
    private final java.util.Map<String, CacheEntry> queryCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 项目 → 话题词索引缓存（懒构建；invalidate 清） */
    private final java.util.Map<Long, TopicIndexEntry> topicIndexCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 话题词索引缓存条目（词表 + 构建时刻） */
    private record TopicIndexEntry(java.util.Map<String, Integer> terms, long atMs) {
    }

    /** 可观测计数：命中次数/回源次数/失效次数 */
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong invalidations = new java.util.concurrent.atomic.AtomicLong();

    /** 缓存条目（命中列表 + 写入时刻） */
    private record CacheEntry(List<KnowledgeHit> hits, long atMs) {
    }

    /**
     * 是否已配置可用的 embedding 供应商（RAG 向量检索开关探测，项目级优先/用户级回退）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @return true 表示可走向量检索
     */
    public boolean isRagAvailable(Long userId, Long projectId) {
        return modelApiService.resolveEmbeddingProvider(userId, projectId) != null;
    }

    /** 知识文档仓库（生命周期状态查询：archived 排除 + stale 标注；既有构造注入字段复用）。 */

    /** 生命周期服务（引用率计数：命中即引用；null=未装配跳过计数，过滤/标注仍生效）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeLifecycleService lifecycleService;

    /**
     * 知识检索。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（项目级全检）
     *
     * @param characterId 当前角色 ID（可空=仅项目级；非空=项目级 + 该角色级合并）
     *
     * @param query 查询文本
     *
     * @param topK 返回条数（默认 3）
     *
     * @return 命中的知识片段列表（按相关度降序；P1 ②-2：archived 文档已排除、stale 已标注）
     */
    public List<KnowledgeHit> search(Long userId, Long projectId, Long characterId, String query, int topK) {
        int k = topK > 0 ? topK : 3;
        // ① 缓存命中（同项目/同角色级/同 query/同 topK 且未过期）→ 直接返回，省一次 query 向量化 + 检索
        String key = queryCacheEnabled ? cacheKey(projectId, characterId, query, k) : null;
        List<KnowledgeHit> hits;
        if (key != null) {
            CacheEntry cached = queryCache.get(key);
            if (cached != null && System.currentTimeMillis() - cached.atMs() <= queryCacheTtlMs) {
                cacheHits.incrementAndGet();
                hits = cached.hits();
            } else {
                cacheMisses.incrementAndGet();
                hits = searchUncached(userId, projectId, characterId, query, k);
                if (queryCache.size() >= Math.max(1, queryCacheMax)) {
                    // 容量兜底：整体清空（条目极小、重建便宜；避免引入 LRU 结构的复杂度）
                    queryCache.clear();
                }
                queryCache.put(key, new CacheEntry(hits, System.currentTimeMillis()));
            }
        } else {
            cacheMisses.incrementAndGet();
            hits = searchUncached(userId, projectId, characterId, query, k);
        }
        // ② 生命周期出口（P1 ②-2）：archived 排除 + stale 标注 + 引用计数（缓存命中同样生效——
        // 命中即引用；幂等标注，重复处理无副作用）
        return applyLifecycle(hits, projectId);
    }

    /**
     * 生命周期出口（P1 ②-2）：统一在检索返回前处理——archived 文档排除（「遗忘」）、
     * stale 文档标注（消费方注入 prompt 时带「可能过时」）、命中即引用计数
     * （{@link KnowledgeLifecycleService#recordAccess}，驱动衰减；未装配跳过计数）。
     *
     * @param hits 检索命中（缓存或未缓存路径统一汇入）
     *
     * @param projectId 项目 ID
     *
     * @return 处理后的命中列表
     */
    private List<KnowledgeHit> applyLifecycle(List<KnowledgeHit> hits, Long projectId) {
        if (hits == null || hits.isEmpty() || projectId == null || docRepository == null) {
            return hits;
        }
        Set<Long> ids = hits.stream().map(KnowledgeHit::docId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return hits;
        }
        Map<Long, String> states = new LinkedHashMap<>();
        try {
            for (ActorKnowledgeDoc d : docRepository.findAllById(ids)) {
                if (d != null && d.getId() != null) {
                    states.put(d.getId(), d.getLifecycleState());
                }
            }
        } catch (Exception e) {
            // 状态查询失败不阻断检索（返回原始命中，零降级）
            log.warn("[知识库] 生命周期状态查询失败（跳过过滤/标注）: {}", e.getMessage());
            return hits;
        }
        List<KnowledgeHit> out = new ArrayList<>(hits.size());
        for (KnowledgeHit h : hits) {
            String st = h.docId() == null ? null : states.get(h.docId());
            if (KnowledgeLifecycleService.STATE_ARCHIVED.equals(st)) {
                continue; // 归档排除：不再参与检索（「遗忘」）
            }
            boolean stale = KnowledgeLifecycleService.STATE_STALE.equals(st);
            out.add(stale ? new KnowledgeHit(h.docId(), h.title(), h.text(), h.score(), h.characterId(), true) : h);
        }
        if (lifecycleService != null) {
            try {
                lifecycleService.recordAccess(projectId, ids);
            } catch (Exception e) {
                log.warn("[知识库] 引用计数失败（忽略，衰减按时间兜底）: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * 缓存键（null=不参与缓存：无项目/空 query）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空=仅项目级，与检索语义一致）
     *
     * @param query 查询文本
     *
     * @param k 返回条数
     *
     * @return 缓存键或 null
     */
    private static String cacheKey(Long projectId, Long characterId, String query, int k) {
        if (projectId == null || query == null || query.isBlank()) {
            return null;
        }
        return projectId + "|" + (characterId == null ? "-" : characterId) + "|" + k + "|" + query.trim();
    }

    /**
     * 使某项目的检索缓存失效（知识文档写入/删除/重建索引时调用）——知识库是检索结果的唯一输入，
     * 一变就必须让旧答案作废，否则新知识在 TTL 内不可见。
     *
     * @param projectId 项目 ID（null=清空全部）
     */
    public void invalidate(Long projectId) {
        if (projectId == null) {
            queryCache.clear();
            topicIndexCache.clear();
            invalidations.incrementAndGet();
            return;
        }
        String prefix = projectId + "|";
        queryCache.keySet().removeIf(k -> k.startsWith(prefix));
        // 话题词索引随知识库变更一并失效（词表源 = 文档标题+正文，变了必须重建）
        topicIndexCache.remove(projectId);
        invalidations.incrementAndGet();
    }

    /**
     * 检索缓存可观测统计（世界运行结束后核对"感知检索是否还在重复嵌入"）。
     */
    public java.util.Map<String, Object> cacheStats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("hits", cacheHits.get());
        m.put("misses", cacheMisses.get());
        m.put("invalidations", invalidations.get());
        m.put("size", queryCache.size());
        return m;
    }

    /**
     * 未走缓存的检索（原逻辑：RAG 优先，未命中/未配置/异常 → 文本关键词降级）。
     * <p><b>知识检索质量深化（_04）</b>：追加<b>多路召回</b>——
     * 从项目知识库构建话题词索引（标题+正文高频 2~4 字窗口），从 query 提取命中话题词
     * （最长优先 ≤ {@code topicLimit}），每个话题词独立向量检索（短词精确命中，补全长句
     * query 语义稀释下实体/主题的召回；<b>主路未命中时话题词路兜底</b>——短词/专有词
     * （如「势力」「云来茶馆」）主路语义检索常排不上，但话题词独立检索可精确命中），
     * 与主路结果按 (docId, text) 去重合并取 topK。</p>
     */
    private List<KnowledgeHit> searchUncached(Long userId, Long projectId, Long characterId, String query, int k) {
        // ① 主路：LangChain4j RAG 检索（唯一向量检索路径；未配置 embedding → RagSearchService 内部返回空）
        List<String> topics = List.of();
        try {
            List<KnowledgeHit> main = ragSearchService.search(userId, projectId, characterId, query, k);
            // ② 话题词提取（开关 + 上限 + 索引可用时）
            if (multiRecallEnabled && topicLimit > 0 && projectId != null) {
                topics = extractTopics(projectId, query);
            }
            // ③ 多路召回：话题词独立向量检索（主路命中或主路空但话题词非空时都跑——
            // 短词/专有词主路语义检索常未命中，话题词路是精确命中兜底）
            List<KnowledgeHit> merged = new ArrayList<>(main == null ? List.of() : main);
            for (String topic : topics) {
                try {
                    List<KnowledgeHit> extra = ragSearchService.search(userId, projectId, characterId, topic, k);
                    merged = mergeTopics(merged, extra);
                } catch (Exception e) {
                    // 话题词路失败不阻断（降级：仅主路/文本结果）
                    log.warn("[知识库] 话题词检索失败（忽略）: topic={} : {}", topic, e.getMessage());
                }
            }
            List<KnowledgeHit> top = top(merged, k);
            if (!top.isEmpty()) {
                log.info("[知识库] LangChain4j RAG 检索命中 {} 条：项目={} 角色={}（多路话题词={}）",
                        top.size(), projectId, characterId, topics);
                return top;
            }
            // 新路径未命中/未配置 embedding → 文本关键词检索降级（保留现状语义）
        } catch (Exception e) {
            log.warn("LangChain4j RAG 检索异常，降级文本检索: {}", e.getMessage());
        }
        return textSearch(projectId, characterId, query, k);
    }

    /**
     * 多路召回合并：主路 + 话题词路按 (docId, text) 去重，同条目取高分（静态纯逻辑，可单测）。
     *
     * @param base 主路命中列表（可空）
     *
     * @param extra 话题词路命中列表（可空）
     *
     * @return 合并后的命中列表（顺序 = base 原序 + extra 新增）
     */
    static List<KnowledgeHit> mergeTopics(List<KnowledgeHit> base, List<KnowledgeHit> extra) {
        if (base == null || base.isEmpty()) {
            return extra == null ? List.of() : extra;
        }
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        java.util.Map<String, KnowledgeHit> merged = new java.util.LinkedHashMap<>();
        for (KnowledgeHit h : base) {
            if (h != null) {
                merged.put(topicKey(h), h);
            }
        }
        for (KnowledgeHit h : extra) {
            if (h == null) {
                continue;
            }
            String key = topicKey(h);
            KnowledgeHit prev = merged.get(key);
            if (prev == null || h.score() > prev.score()) {
                merged.put(key, h);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 命中去重键（docId + 片段文本；同一文档同一 chunk 视为同一条目）。 */
    private static String topicKey(KnowledgeHit h) {
        return (h.docId() == null ? "-" : h.docId()) + "|" + (h.text() == null ? "" : h.text());
    }

    /**
     * 从 query 提取命中话题词（索引懒构建 + per-project 缓存，TTL 兜底 + invalidate 显式失效）。
     */
    private List<String> extractTopics(Long projectId, String query) {
        java.util.Map<String, Integer> index = topicIndex(projectId);
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        return QueryTopicExtractor.extractTopics(query, index, Math.max(1, topicLimit));
    }

    /** 取项目话题词索引（懒构建 + 缓存；构建失败返回空表，退化为单路检索）。 */
    private java.util.Map<String, Integer> topicIndex(Long projectId) {
        TopicIndexEntry entry = topicIndexCache.get(projectId);
        if (entry != null && System.currentTimeMillis() - entry.atMs() <= topicIndexTtlMs) {
            return entry.terms();
        }
        java.util.Map<String, Integer> terms = buildTopicIndex(projectId);
        topicIndexCache.put(projectId, new TopicIndexEntry(terms, System.currentTimeMillis()));
        return terms;
    }

    /** 从项目知识文档（标题 + 正文）构建话题词索引（失败返回空表，不阻断检索）。 */
    private java.util.Map<String, Integer> buildTopicIndex(Long projectId) {
        try {
            List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
            List<String> titles = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            for (ActorKnowledgeDoc d : docs) {
                if (d == null) {
                    continue;
                }
                if (d.getTitle() != null) {
                    titles.add(d.getTitle());
                }
                if (d.getContent() != null) {
                    contents.add(d.getContent());
                }
            }
            return QueryTopicExtractor.buildTermIndex(titles, contents);
        } catch (Exception e) {
            log.warn("[知识库] 话题词索引构建失败（退化为单路检索）: 项目={} : {}", projectId, e.getMessage());
            return java.util.Map.of();
        }
    }

    /**
     * 文本关键词检索降级（未命中/未配置 embedding/失败时的兜底路径）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 当前角色 ID（可空=仅项目级；非空=项目级 + 该角色级合并）
     *
     * @param query 查询文本
     *
     * @param k 返回条数
     *
     * @return 文本命中片段列表
     */
    private List<KnowledgeHit> textSearch(Long projectId, Long characterId, String query, int k) {
        List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(d -> d.getCharacterId() == null || d.getCharacterId().equals(characterId))
                .toList();
        List<KnowledgeHit> textHits = new ArrayList<>();
        for (ActorKnowledgeDoc d : docs) {
            textHits.addAll(textHits(d, query));
        }
        return top(textHits, k);
    }

    /**
     * 对单个文档做文本关键词检索（降级路径）。
     *
     * @param doc 文档实体
     *
     * @param query 查询文本
     *
     * @return 该文档的命中片段（全文作为一个候选片段）
     */
    private List<KnowledgeHit> textHits(ActorKnowledgeDoc doc, String query) {
        String content = doc.getContent() == null ? "" : doc.getContent();
        double score = textScore(query, content);
        if (score <= 0) {
            return List.of();
        }
        // 取命中位置附近的有界片段（句边界对齐窗口，round-46：不整篇注入世界观）
        String snippet = snippetAround(content, query);
        return List.of(new KnowledgeHit(doc.getId(), doc.getTitle(), snippet, score, doc.getCharacterId()));
    }

    /** 文本降级检索命中片段窗口（字符数）：以首个命中位置为中心的有界窗口（round-46 决策） */
    static final int SNIPPET_WINDOW_CHARS = 400;

    /** 句子终止符（与 {@link KnowledgeChunker#splitIntoSentences} 口径一致：换行/中英文句末标点） */
    private static final String SENTENCE_TERMINATORS = "。！？!?；;\n";

    /**
     * 取首个命中位置附近的有界片段（round-46 决策：<b>不注入整篇世界观</b>）。
     * <p>2026-10-10 演进：① 此前 120 字硬窗口常把片段切在句中（用户报「（灵石或」「庇」
     * 残句），② 全量返回又把整个世界设定文档灌进 AI 请求（用户报【你知晓的相关世界知识】
     * 内容过多）。现折中：以命中位置为中心取 {@link #SNIPPET_WINDOW_CHARS} 字窗口，向两侧
     * <b>尽力对齐到完整句边界</b>（不切在句中），窗口外以省略号标注（读者明确知道是节选）。</p>
     *
     * @param content 全文
     *
     * @param query 查询文本
     *
     * @return 句边界对齐的有界片段（全文不超窗口时原样返回）
     */
    private String snippetAround(String content, String query) {
        return excerpt(content, query, SNIPPET_WINDOW_CHARS);
    }

    /**
     * 有界命中片段（纯静态可单测）：以首个命中位置为中心取 maxChars 字窗口，向两侧尽力
     * 扩展到句边界，窗口外补省略号；无位置锚点（如纯语义命中、query 全文与正文无交集）时
     * 取文首句边界窗口。全文不超窗口时原样返回（短文档完整注入）。
     *
     * @param content 全文
     *
     * @param query 查询文本（可为空）
     *
     * @param maxChars 窗口预算（&lt;=0 回退 {@link #SNIPPET_WINDOW_CHARS}）
     *
     * @return 有界片段（空文本返回空串）
     */
    static String excerpt(String content, String query, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String text = content.trim();
        int budget = maxChars > 0 ? maxChars : SNIPPET_WINDOW_CHARS;
        if (text.length() <= budget) {
            return text;
        }
        int idx = matchStart(text, query);
        if (idx < 0) {
            // 无位置锚点：取文首 budget 字，收在句边界，省略号标注
            int end = Math.min(text.length(), budget);
            int b = lastTerminatorAtOrBefore(text, end);
            return text.substring(0, b >= 0 ? b + 1 : end) + "…";
        }
        int qLen = query == null ? 0 : query.trim().length();
        int before = budget / 3;
        int after = budget - before;
        int start = Math.max(0, idx - before);
        int end = Math.min(text.length(), idx + Math.min(qLen, budget) + after);
        // 句边界对齐（尽力而为）：仅当前一句/后一句较短时扩入，防超长句把窗口撑爆
        int ls = lastTerminatorBefore(text, start);
        if (ls >= 0 && (idx - (ls + 1)) <= before) {
            start = ls + 1;
        }
        int ne = nextTerminatorAtOrAfter(text, end);
        if (ne >= 0 && ((ne + 1) - idx) <= budget) {
            end = ne + 1;
        }
        // 硬顶（超长句极端情形）：窗口不超 2 倍预算，且尽力收在句边界
        if (end - start > budget * 2) {
            end = start + budget * 2;
            int nb = lastTerminatorBefore(text, end);
            if (nb >= start) {
                end = nb + 1;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("…");
        }
        sb.append(text, start, end);
        if (end < text.length()) {
            sb.append("…");
        }
        return sb.toString();
    }

    /**
     * 首个命中位置：整体 query 优先，其次按 query 中最先出现的二元组（与
     * {@link #textScore} 二元组共现口径一致）；无任何交集返回 -1。
     */
    private static int matchStart(String text, String query) {
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            int idx = text.indexOf(q);
            if (idx >= 0) {
                return idx;
            }
            for (int i = 0; i + 1 < q.length(); i++) {
                int j = text.indexOf(q.substring(i, i + 2));
                if (j >= 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    /** 位置 &lt; pos 的最近句子终止符索引（无则 -1）。 */
    private static int lastTerminatorBefore(String text, int pos) {
        for (int i = Math.min(pos - 1, text.length() - 1); i >= 0; i--) {
            if (SENTENCE_TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 位置 &gt;= pos 的最近句子终止符索引（无则 -1）。 */
    private static int nextTerminatorAtOrAfter(String text, int pos) {
        for (int i = Math.max(0, pos); i < text.length(); i++) {
            if (SENTENCE_TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 位置 &lt;= pos 的最近句子终止符索引（收尾用；无则 -1）。 */
    private static int lastTerminatorAtOrBefore(String text, int pos) {
        for (int i = Math.min(pos, text.length() - 1); i >= 0; i--) {
            if (SENTENCE_TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取 top-k（按得分降序）。
     *
     * @param hits 全部命中
     *
     * @param k 返回条数
     *
     * @return 排序后的 top-k
     */
    private List<KnowledgeHit> top(List<KnowledgeHit> hits, int k) {
        return hits.stream()
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(k)
                .toList();
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 文本分块。
     *
     * @param text 全文
     *
     * @param chunkSize 单块最大字符数
     *
     * @param overlap 相邻块重叠字符数
     *
     * @return 分块列表（空文本返回空列表）
     */
    static List<String> chunk(String text, int chunkSize, int overlap) {
        return KnowledgeChunker.chunk(text, chunkSize, overlap);
    }

    /**
     * 按句子切分（委托 {@link KnowledgeChunker}；保留静态委托，避免既有调用/单测改动）。
     *
     * @param text 文本
     *
     * @return 句子列表
     */
    static List<String> splitIntoSentences(String text) {
        return KnowledgeChunker.splitIntoSentences(text);
    }

    /**
     * 文本关键词打分（降级检索）：整句命中加权 + 二元组共现计数。
     *
     * @param query 查询文本
     *
     * @param content 文档内容
     *
     * @return 相关度得分（0 = 无命中）
     */
    static double textScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        String q = query.trim();
        String c = content;
        double score = 0;
        // 整句命中：按查询长度加权
        if (c.contains(q)) {
            score += q.length() * 2.0;
        }
        // 二元组共现计数（中文无空格分词，用二元组近似）
        for (int i = 0; i + 1 < q.length(); i++) {
            String bigram = q.substring(i, i + 2);
            int idx = 0, cnt = 0;
            while ((idx = c.indexOf(bigram, idx)) >= 0) {
                cnt++;
                idx += bigram.length();
            }
            score += cnt;
        }
        return score;
    }
}
