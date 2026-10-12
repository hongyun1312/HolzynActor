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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 记忆向量服务。
 * <p>职责：L1 short_term 短期事件记忆的<b>向量化 + 模糊噪声 + 混合检索分</b>——
 * 检索难度维度（设计 §3.1.2/§3.2 ③）：对记忆向量施加随 fuzz_level 增长的确定性高斯噪声
 * σ=fuzz_level×max-noise（seed=记忆 id+gameSecond 可复现），噪声越大向量相似度越低=越难命中。</p>
 * <ul>
 * <li><b>向量存储</b>：每项目一个 JSON 文件 {@code data/vector-store/<project>/memory-vectors.json}
 * （{memoryWorkingId: [float...]}），原子写（临时文件 + ATOMIC_MOVE）+ 进程锁，与知识向量库
 * 隔离（记忆向量不走 VectorStoreService 的文档分块语义，docId 即 memory_working.id）；</li>
 * <li><b>embedding 调用</b>：复用 ModelApiService.resolveEmbeddingProvider + DynamicEmbeddingModelFactory
 * （qwen3-embedding）；未配置供应商/调用失败 → 返回 null 零阻断（检索自动回退纯 Dice）；
 * 每次调用落 scene=embedding trace + 用量日志（对齐 KnowledgeVectorizerService 口径）；</li>
 * <li><b>模糊噪声</b>：{@link #applyNoiseAndStore}——对当前模糊文本重新向量化后叠加高斯噪声
 * （确定性种子），归一化后落盘；噪声幅度记录于 fuzz_state.embedding_noise；</li>
 * （默认 0.6/0.4 可配置；向量缺失自动退纯 Dice）。</li>
 * </ul>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§3.2 ③ +
 * 用户 决策 「必须引入 embedding 向量噪声」。</p>
 */
@Slf4j
@Service
public class MemoryVectorService {

    /** 向量文件名 */
    private static final String FILE_NAME = "memory-vectors.json";

    /** 混合分中向量相似度权重（默认 0.6；其余 0.4 给 Dice 文本重叠） */
    @Value("${holzyn.actor.memory.vector.hybrid-vec-weight:0.6}")
    private double hybridVecWeight;

    /** embedding 供应商解析 */
    private final ModelApiService modelApiService;

    /** 动态 embedding 模型工厂（配置变化惰性重建） */
    private final DynamicEmbeddingModelFactory embeddingModelFactory;

    /** AI 调用 trace 服务（scene=embedding 审计） */
    private final AiCallTraceService aiCallTraceService;

    /** 用量日志 */
    private final UsageLogService usageLogService;

    /** 本地数据目录（application.yml holzyn.actor.data-dir） */
    private final String dataDir;

    /** 向量库根目录覆盖（application.yml holzyn.actor.ai.vector-store-dir；空=数据目录/vector-store） */
    private final String vectorStoreDirOverride;

    /** 内存缓存：projectId → (memoryWorkingId → 向量)（懒加载，与文件同步） */
    private final Map<Long, Map<Long, float[]>> cache = new ConcurrentHashMap<>();

    /** 进程锁：projectId → 读写/落盘串行化 */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    // ==================== 世界运行抓修：同文本向量复用 ====================
    // 问题（用户报障「一大部分请求被打到向量模型上」）：写入路径对每条记忆都调一次 embedding，
    // 而记忆文本高度重复——实测同一句「竹林茅舍 rest（歇息就寝）」在 4 分钟内被向量化 16 次、
    // 「青禾村塾 rest（歇息就寝）」12 次（15 分钟内共 158 次嵌入只有 36 条不同文本）。
    // 嵌入是「同文本 → 同向量」的幂等只读计算，重复调用纯属浪费，因此按「供应商|模型|文本」复用。
    /** 同文本向量复用开关（默认 true；false=每次写入都重新调用 embedding，退回抓修前行为） */
    @Value("${holzyn.actor.memory.vector.text-cache-enabled:true}")
    private boolean textCacheEnabled = true;

    /** 复用缓存容量上限（默认 5000 条；超限整体清空——单条向量数 KB，重建代价低） */
    @Value("${holzyn.actor.memory.vector.text-cache-max:5000}")
    private int textCacheMax = 5000;

    /** 文本→向量复用缓存（键=供应商 id|模型|文本；值=该文本的向量） */
    private final Map<String, float[]> textVectorCache = new ConcurrentHashMap<>();

    /** 可观测计数：复用命中次数 / 真实嵌入次数 */
    private final java.util.concurrent.atomic.AtomicLong textCacheHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong embedCalls = new java.util.concurrent.atomic.AtomicLong();

    public MemoryVectorService(ModelApiService modelApiService,
                               DynamicEmbeddingModelFactory embeddingModelFactory,
                               AiCallTraceService aiCallTraceService,
                               UsageLogService usageLogService,
                               @Value("${holzyn.actor.data-dir:./data}") String dataDir,
                               @Value("${holzyn.actor.ai.vector-store-dir:}") String vectorStoreDirOverride) {
        this.modelApiService = modelApiService;
        this.embeddingModelFactory = embeddingModelFactory;
        this.aiCallTraceService = aiCallTraceService;
        this.usageLogService = usageLogService;
        this.dataDir = dataDir;
        this.vectorStoreDirOverride = vectorStoreDirOverride;
    }

    // ==================== 写入：向量化 + 落盘（写时向量化 original_content） ====================

    /**
     * 为一条 L1 short_term 记忆写入向量（写入路径调用：原始内容向量化）。
     *
     * @param userId 用户 ID（embedding 凭据归属；null 回退 1）
     *
     * @param projectId 项目 ID
     *
     * @param memoryWorkingId 记忆工作项 id（docId 语义）
     *
     * @param content 记忆文本（写入时=original_content）
     *
     * @return true=向量已写入；false=未配置供应商/调用失败（零阻断）
     */
    public boolean addVector(Long userId, Long projectId, Long memoryWorkingId, String content) {
        if (projectId == null || memoryWorkingId == null || content == null || content.isBlank()) {
            return false;
        }
        float[] vec = embed(userId, projectId, content);
        if (vec == null) {
            return false;
        }
        withLock(projectId, () -> {
            Map<Long, float[]> m = cache.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
            m.put(memoryWorkingId, vec);
            persist(projectId);
        });
        return true;
    }

    /**
     * 模糊后重向量化 + 施加确定性高斯噪声并落盘（检索难度上升维度）。
     * 可复现（同一条记忆同一时刻噪声相同）。</p>
     *
     * @return true=已写入带噪向量；false=embedding 失败（保留旧向量，零阻断）
     */
    public boolean applyNoiseAndStore(Long userId, Long projectId, Long memoryWorkingId,
                                      String fuzzyContent, double sigma, long seed) {
        if (projectId == null || memoryWorkingId == null || fuzzyContent == null || fuzzyContent.isBlank()) {
            return false;
        }
        float[] base = embed(userId, projectId, fuzzyContent);
        if (base == null) {
            return false;
        }
        float[] noisy = noise(base, Math.max(0.0, sigma), seed);
        withLock(projectId, () -> {
            Map<Long, float[]> m = cache.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
            m.put(memoryWorkingId, noisy);
            persist(projectId);
        });
        return true;
    }

    /**
     * 移除一条记忆向量（记忆删除/睡眠清洗琐事合并清理时同步清理，防检索命中陈旧向量）。
     */
    public void removeVector(Long projectId, Long memoryWorkingId) {
        if (projectId == null || memoryWorkingId == null) {
            return;
        }
        withLock(projectId, () -> {
            Map<Long, float[]> m = cache.get(projectId);
            if (m != null && m.remove(memoryWorkingId) != null) {
                persist(projectId);
            }
        });
    }

    // ==================== 读取：向量 + 混合检索分（检索路径调用） ====================

    /**
     * 取一条记忆向量（懒加载项目文件；缺失返回 null）。
     */
    public float[] vectorOf(Long projectId, Long memoryWorkingId) {
        if (projectId == null || memoryWorkingId == null) {
            return null;
        }
        Map<Long, float[]> m = cache.computeIfAbsent(projectId, k -> load(projectId));
        return m.get(memoryWorkingId);
    }

    /**
     * 混合检索分：relevance = vecWeight×cosine(query, mem) + (1−vecWeight)×Dice(query, content)。
     * <p>向量缺失（未配置供应商/未向量化）自动回退纯 Dice——零阻断；cosine 与 Dice 均 0~1。</p>
     */
    public double hybridRelevance(float[] queryVec, float[] memVec, String query, String content) {
        double vecScore = 0.0;
        if (queryVec != null && memVec != null) {
            vecScore = cosine(queryVec, memVec);
        }
        double diceScore = MemoryDedup.overlapScore(query, content);
        return clamp01(hybridVecWeight * vecScore + (1.0 - hybridVecWeight) * diceScore);
    }

    /** 余弦相似度（任一 null/维度不一致返回 0）。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 高斯噪声叠加 + 归一化（确定性种子：同 seed 同输出）。 */
    static float[] noise(float[] base, double sigma, long seed) {
        Random rnd = new Random(seed);
        float[] out = new float[base.length];
        double sumSq = 0.0;
        for (int i = 0; i < base.length; i++) {
            out[i] = base[i] + (float) (rnd.nextGaussian() * sigma);
            sumSq += out[i] * out[i];
        }
        if (sumSq > 0) {
            double norm = Math.sqrt(sumSq);
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (out[i] / norm);
            }
        }
        return out;
    }

    // ==================== embedding 调用（复用知识向量化口径：scene=embedding trace + 用量） ====================

    /**
     * 单条文本向量化（LangChain4j embed；未配置供应商/调用失败返回 null 零阻断）。
     */
    private float[] embed(Long userId, Long projectId, String text) {
        Long uid = userId == null ? 1L : userId;
        try {
            ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(uid, projectId);
            if (cfg == null) {
                return null;
            }
            // ① 同文本复用：同供应商同模型同文本 → 直接复用向量，不再打 embedding 接口
            String cacheKey = textCacheEnabled
                    ? (cfg.id() + "|" + cfg.model() + "|" + text) : null;
            if (cacheKey != null) {
                float[] cached = textVectorCache.get(cacheKey);
                if (cached != null) {
                    textCacheHits.incrementAndGet();
                    return cached;
                }
            }
            long startMs = System.currentTimeMillis();
            AiCallTrace trace = aiCallTraceService.beginScene(projectId, "embedding");
            try {
                trace.setBaseUrl(cfg.baseUrl());
                trace.setStream(false);
                EmbeddingModel model = embeddingModelFactory.get(cfg);
                Response<Embedding> resp = model.embed(text);
                Embedding emb = resp == null ? null : resp.content();
                if (emb == null || emb.vector() == null) {
                    throw new IllegalStateException("embedding 响应为空");
                }
                embedCalls.incrementAndGet();
                int prompt = resp.tokenUsage() == null || resp.tokenUsage().inputTokenCount() == null
                        ? 0 : resp.tokenUsage().inputTokenCount();
                trace.fillMessages(new com.holzyn.actor.ai.AiChatRequest(null, List.of(
                        new com.holzyn.actor.ai.AiChatRequest.ChatMessage("system",
                                "Embedding 供应商：" + cfg.baseUrl() + " 模型=" + cfg.model()),
                        new com.holzyn.actor.ai.AiChatRequest.ChatMessage("user", text)), null, null));
                trace.markSuccess("记忆向量化：维度 " + emb.dimension(), cfg.model(), prompt, 0, 0, 0, cfg.id());
                usageLogService.record(uid, projectId, null, null, cfg.model(), "embedding",
                        prompt, 0, 0, 0, (int) (System.currentTimeMillis() - startMs));
                logAiCall(trace);
                // ② 写入复用缓存（同文本下次直接复用；容量超限整体清空防无界增长）
                if (cacheKey != null) {
                    if (textVectorCache.size() >= Math.max(1, textCacheMax)) {
                        textVectorCache.clear();
                    }
                    textVectorCache.put(cacheKey, emb.vector());
                }
                return emb.vector();
            } catch (Exception e) {
                trace.markFailure(e);
                logAiCall(trace);
                log.warn("[记忆向量] embedding 失败（零阻断，检索回退 Dice）: project={} : {}", projectId, e.getMessage());
                return null;
            } finally {
                aiCallTraceService.end(trace);
            }
        } catch (Exception e) {
            log.warn("[记忆向量] embedding 调用异常（零阻断）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 实时日志（对齐 KnowledgeVectorizerService 口径）。 */
    private void logAiCall(AiCallTrace trace) {
        String status = trace.isDegraded() ? "degraded" : trace.getStatus();
        String err = trace.getErrorMessage() == null ? "" : " 错误=" + trace.getErrorMessage();
        log.info("[AI调用] scene=embedding project={} model={} 状态={} tokens={}/{} 耗时={}ms{}",
                trace.getProjectId(), trace.getModel() == null ? "-" : trace.getModel(), status,
                trace.getTokenIn(), trace.getTokenOut(), trace.getDurationMs(), err);
    }

    /**
     * 向量化可观测统计。
     */
    public Map<String, Object> vectorStats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("textCacheHits", textCacheHits.get());
        m.put("embedCalls", embedCalls.get());
        m.put("textCacheSize", textVectorCache.size());
        return m;
    }

    // ==================== 持久化（每项目一个 JSON：{memoryWorkingId: [float...]}，原子写） ====================

    /**
     * 懒加载某项目记忆向量（文件缺失/损坏回退空 Map——零阻断）。
     */
    private Map<Long, float[]> load(Long projectId) {
        Path file = fileOf(projectId);
        if (!Files.exists(file)) {
            return new ConcurrentHashMap<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<Long, float[]> out = new ConcurrentHashMap<>();
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(json);
            tools.jackson.databind.JsonNode arr = root.path("vectors");
            if (arr.isObject()) {
                for (var e : arr.properties()) {
                    try {
                        Long id = Long.parseLong(e.getKey());
                        tools.jackson.databind.JsonNode v = e.getValue();
                        float[] vec = new float[v.size()];
                        for (int i = 0; i < v.size(); i++) {
                            vec[i] = (float) v.get(i).asDouble(0.0);
                        }
                        out.put(id, vec);
                    } catch (Exception ignored) {
                        // 单条损坏跳过
                    }
                }
            }
            log.debug("[记忆向量] 加载项目 {} 记忆向量：{} 条", projectId, out.size());
            return out;
        } catch (Exception e) {
            log.warn("[记忆向量] 项目 {} 向量文件损坏/读取失败，回退空（零阻断）: {}", projectId, e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }

    /**
     * 原子落盘（临时文件 + ATOMIC_MOVE）。
     */
    private void persist(Long projectId) {
        Path file = fileOf(projectId);
        try {
            Map<Long, float[]> m = cache.getOrDefault(projectId, Map.of());
            tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();
            tools.jackson.databind.node.ObjectNode root = om.createObjectNode();
            tools.jackson.databind.node.ObjectNode vecs = om.createObjectNode();
            for (Map.Entry<Long, float[]> e : m.entrySet()) {
                tools.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                for (float f : e.getValue()) {
                    arr.add(f);
                }
                vecs.set(String.valueOf(e.getKey()), arr);
            }
            root.set("vectors", vecs);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, om.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("[记忆向量] 项目 {} 落盘失败（内存态继续可用）: {}", projectId, e.getMessage());
        }
    }

    /** 记忆向量文件路径（与知识向量库同根目录，独立文件名隔离）。 */
    private Path fileOf(Long projectId) {
        String root = (vectorStoreDirOverride == null || vectorStoreDirOverride.isBlank())
                ? Path.of(dataDir, "vector-store").toString() : vectorStoreDirOverride;
        return Path.of(root).resolve(String.valueOf(projectId)).resolve(FILE_NAME);
    }

    /** 进程锁内执行。 */
    private void withLock(Long projectId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /** 钳制 0~1。 */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
