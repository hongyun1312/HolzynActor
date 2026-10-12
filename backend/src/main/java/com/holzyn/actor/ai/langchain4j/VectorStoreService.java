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

package com.holzyn.actor.ai.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 向量库服务。
 * <p>职责：每项目一个 {@link InMemoryEmbeddingStore<TextSegment>} 的<b>懒加载</b>管理 +
 * <b>本地文件持久化</b>（{@code <data-dir>/vector-store/<projectId>/embeddings.json}，
 * LangChain4j 原生 JSON 序列化）——零外部依赖，最贴合本地单机「克隆即用」定位。</p>
 * <ul>
 * <li><b>懒加载</b>：首次访问某项目 store 时才加载（文件存在 → {@code fromJson} 反序列化，
 * 免重启后重 embedding；文件缺失 → 新建空 store）；</li>
 * <li><b>原子写</b>：变更后落盘 = 序列化到临时文件 + {@code ATOMIC_MOVE} 重命名（防写入中断损坏）；</li>
 * <li><b>进程内锁</b>：每项目一把 {@link ReentrantLock} 串行化「变更 + 落盘」（单进程模型无跨进程竞争）；</li>
 * <li><b>损坏兜底</b>：加载失败（文件损坏/非法 JSON）→ WARN + 回退空 store，不阻断启动/业务；</li>
 * <li><b>按 docId 替换</b>：{@link #addChunks} 先按 docId 元数据过滤移除旧 chunk 再写入
 * （文档重新向量化/更新不残留陈旧向量；{@link #removeDoc} 供文档删除同步清理）。</li>
 * </ul>
 * <p><b>元数据</b>：每个 chunk 的 {@link TextSegment} 携带 {@code {projectId, characterId, docId, title,
 * <p><b>数据目录</b>：默认取 {@code holzyn.actor.data-dir}/vector-store；可经
 * {@code holzyn.actor.ai.vector-store-dir} 显式覆盖；运行时数据入 .gitignore，克隆即用不携带。</p>
 */
@Slf4j
@Component
public class VectorStoreService {

    /** 向量库文件名（每项目一个） */
    private static final String STORE_FILENAME = "embeddings.json";

    /** 向量库子目录（位于数据目录下） */
    private static final String STORE_SUBDIR = "vector-store";

    /** TextSegment 元数据键：项目 ID */
    public static final String META_PROJECT_ID = "projectId";
    /** TextSegment 元数据键：角色级归属（空=项目级） */
    public static final String META_CHARACTER_ID = "characterId";
    /** TextSegment 元数据键：来源文档 ID（按此替换/删除） */
    public static final String META_DOC_ID = "docId";
    /** TextSegment 元数据键：文档标题 */
    public static final String META_TITLE = "title";
    /** TextSegment 元数据键：块序号（文档内从 0 起） */
    public static final String META_CHUNK_INDEX = "chunkIndex";

    /** 本地数据目录（application.yml holzyn.actor.data-dir） */
    private final String dataDir;

    /** 向量库根目录覆盖（application.yml holzyn.actor.ai.vector-store-dir；空=数据目录/vector-store） */
    private final String vectorStoreDirOverride;

    /** 内存 store 缓存：projectId → store（懒加载） */
    private final Map<Long, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    /** 内存 docId → chunk 数索引：projectId → (docId → chunkCount) */
    private final Map<Long, Map<Long, Integer>> docChunkCounts = new ConcurrentHashMap<>();

    /** 静态 ObjectMapper（加载时解析向量库 JSON 重建 docId 索引） */
    private static final tools.jackson.databind.ObjectMapper STORE_MAPPER = new tools.jackson.databind.ObjectMapper();

    /** 进程内锁：projectId → 变更/落盘串行化锁 */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 构造函数（构造器注入，便于单测传入临时目录）。
     *
     * @param dataDir 本地数据目录（{@code holzyn.actor.data-dir}）
     *
     * @param vectorStoreDirOverride 向量库根目录覆盖（可空/空=数据目录/vector-store）
     */
    public VectorStoreService(@Value("${holzyn.actor.data-dir:./data}") String dataDir,
                              @Value("${holzyn.actor.ai.vector-store-dir:}") String vectorStoreDirOverride) {
        this.dataDir = dataDir == null || dataDir.isBlank() ? "./data" : dataDir;
        this.vectorStoreDirOverride = vectorStoreDirOverride;
    }

    /**
     * 获取某项目向量库（懒加载：文件存在则反序列化，缺失/损坏回退空 store）。
     *
     * @param projectId 项目 ID
     *
     * @return 该项目的 store（永不为 null）
     */
    public InMemoryEmbeddingStore<TextSegment> storeFor(Long projectId) {
        return stores.computeIfAbsent(projectId, this::loadOrCreate);
    }

    /**
     * 写入某文档的全部分块，并落盘。
     *
     * @param projectId 项目 ID
     *
     * @param docId 来源文档 ID（替换/删除粒度）
     *
     * @param title 文档标题（写入 TextSegment 元数据）
     *
     * @param characterId 角色级归属（可空=项目级；写入元数据）
     *
     * @param segments 全部分块文本（已带元数据）
     *
     * @param embeddings 与 segments 一一对应的向量
     */
    public void addChunks(Long projectId, Long docId, String title, Long characterId,
                          List<TextSegment> segments, List<Embedding> embeddings) {
        if (segments == null || embeddings == null || segments.isEmpty()
                || segments.size() != embeddings.size()) {
            log.warn("[向量库] addChunks 忽略：项目={} 文档={} segments={} embeddings={}（数量不一致或为空）",
                    projectId, docId, segments == null ? 0 : segments.size(), embeddings == null ? 0 : embeddings.size());
            return;
        }
        withLock(projectId, () -> {
            InMemoryEmbeddingStore<TextSegment> store = storeFor(projectId);
            // 按 docId 替换：先移除该文档旧 chunk（元数据过滤；重启后从文件加载同样生效）
            store.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(String.valueOf(docId)));
            store.addAll(chunkIds(docId, segments.size()), embeddings, segments);
            // 同步 docId 索引
            docChunkCounts.computeIfAbsent(projectId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(docId, segments.size());
            persist(projectId, store);
            log.info("[向量库] 项目 {} 文档 {} 已写入 {} 个分块（当前共 {} 条）",
                    projectId, docId, segments.size(), store.size());
        });
    }

    /**
     * 移除某文档的全部 chunk（文档删除/弃用后同步清理，防检索命中陈旧向量），并落盘。
     *
     * @param projectId 项目 ID
     *
     * @param docId 来源文档 ID
     */
    public void removeDoc(Long projectId, Long docId) {
        withLock(projectId, () -> {
            InMemoryEmbeddingStore<TextSegment> store = storeFor(projectId);
            int before = store.size();
            store.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(String.valueOf(docId)));
            // 同步 docId 索引
            Map<Long, Integer> idx = docChunkCounts.get(projectId);
            if (idx != null) {
                idx.remove(docId);
            }
            if (store.size() != before) {
                persist(projectId, store);
                log.info("[向量库] 项目 {} 文档 {} 已移除 {} 个分块（剩余 {} 条）",
                        projectId, docId, before - store.size(), store.size());
            }
        });
    }

    /**
     * 清空并删除某项目的向量库（项目删除/世界初始化重建用）：内存驱逐 + 文件删除。
     *
     * @param projectId 项目 ID
     */
    public void removeProject(Long projectId) {
        withLock(projectId, () -> {
            stores.remove(projectId);
            locks.remove(projectId);
            docChunkCounts.remove(projectId);
            Path file = storeFile(projectId);
            try {
                Files.deleteIfExists(file);
                log.info("[向量库] 项目 {} 向量库已删除", projectId);
            } catch (Exception e) {
                log.warn("[向量库] 项目 {} 向量库文件删除失败（不影响主流程）: {}", projectId, e.getMessage());
            }
        });
    }

    /** 某项目向量库当前条数（懒加载；测试/启动报告用） */
    public int count(Long projectId) {
        return storeFor(projectId).size();
    }

    /**
     * 某文档在向量库中的 chunk 数。
     * <p>经内存 docId 索引（{@code projectId → docId → chunkCount}，与 store 变更同步、加载时从文件重建）
     * 返回该文档的 chunk 数；文档未入库/索引缺失返回 0（内容为空或向量化失败）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param docId 来源文档 ID
     *
     * @return 该文档的 chunk 数（0=未向量化/不存在）
     */
    public int docChunkCount(Long projectId, Long docId) {
        if (projectId == null || docId == null) {
            return 0;
        }
        // 先触发懒加载（文件存在则重建 docId 索引；新实例/首次访问必经此步）
        storeFor(projectId);
        return docChunkCounts.getOrDefault(projectId, Map.of()).getOrDefault(docId, 0);
    }

    /**
     * 某文档是否已向量化（向量库含该 docId 的 chunk）。
     *
     * @param projectId 项目 ID
     *
     * @param docId 来源文档 ID
     *
     * @return true=向量库已有该文档 chunk
     */
    public boolean hasDoc(Long projectId, Long docId) {
        return docChunkCount(projectId, docId) > 0;
    }

    /** 某项目向量库是否已在内存中加载（测试/启动报告用） */
    public boolean isLoaded(Long projectId) {
        return stores.containsKey(projectId);
    }

    /**
     * 某项目向量库是否已持久化（文件存在且非空；存量导入的「已导入则跳过」判定用）。
     *
     * @param projectId 项目 ID
     *
     * @return true 表示已有持久化向量数据（免重复导入）
     */
    public boolean hasPersistedStore(Long projectId) {
        Path file = storeFile(projectId);
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Files.size(file) > 0;
        } catch (Exception e) {
            log.warn("[向量库] 项目 {} 向量库文件大小读取失败（按未持久化处理）: {}", projectId, e.getMessage());
            return false;
        }
    }

    /**
     * 向量库根目录（可覆盖）。{@code <root>/<projectId>/embeddings.json} 为单项目文件。
     *
     * @return 根目录 Path
     */
    Path rootDir() {
        if (vectorStoreDirOverride != null && !vectorStoreDirOverride.isBlank()) {
            return Path.of(vectorStoreDirOverride);
        }
        return Path.of(dataDir, STORE_SUBDIR);
    }

    /**
     * 某项目向量库文件路径。
     *
     * @param projectId 项目 ID
     *
     * @return 文件 Path
     */
    private Path storeFile(Long projectId) {
        return rootDir().resolve(String.valueOf(projectId)).resolve(STORE_FILENAME);
    }

    /**
     * 懒加载或创建某项目 store（文件损坏回退空 store + WARN，不阻断启动/业务）。
     *
     * @param projectId 项目 ID
     *
     * @return store（永不为 null）
     */
    private InMemoryEmbeddingStore<TextSegment> loadOrCreate(Long projectId) {
        Path file = storeFile(projectId);
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromJson(json);
                    // ：重建 docId 索引（从序列化 JSON 解析 entries[].embedded.metadata.metadata.docId）
                    rebuildDocIndex(projectId, json);
                    log.info("[向量库] 加载项目 {} 向量库：{} 条（文件 {}）", projectId, store.size(), file);
                    return store;
                }
            } catch (Exception e) {
                log.warn("[向量库] 项目 {} 向量库文件损坏/读取失败，回退空 store（不影响启动）: {}",
                        projectId, e.getMessage());
            }
        }
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 从向量库序列化 JSON 重建 docId → chunk 数索引（， 知识页四态判定经向量库，
     * 重启加载后索引与 store 保持一致）。JSON 结构（LangChain4j serializeToJson）：
     * 解析失败仅 WARN，不阻断（索引缺失时 docChunkCount 返回 0，展示未向量化，属可接受兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param json 向量库序列化 JSON
     */
    private void rebuildDocIndex(Long projectId, String json) {
        try {
            Map<Long, Integer> idx = new java.util.concurrent.ConcurrentHashMap<>();
            tools.jackson.databind.JsonNode root = STORE_MAPPER.readTree(json);
            tools.jackson.databind.JsonNode entries = root.path("entries");
            if (entries.isArray()) {
                for (tools.jackson.databind.JsonNode e : entries) {
                    String docId = e.path("embedded").path("metadata").path("metadata")
                            .path(META_DOC_ID).asText("");
                    Long id = toLong(docId);
                    if (id != null) {
                        idx.merge(id, 1, Integer::sum);
                    }
                }
            }
            docChunkCounts.put(projectId, idx);
            log.info("[向量库] 项目 {} docId 索引重建完成：{} 个文档", projectId, idx.size());
        } catch (Exception e) {
            log.warn("[向量库] 项目 {} docId 索引重建失败（docChunkCount 暂返回 0）: {}", projectId, e.getMessage());
        }
    }

    /** 元数据字符串值安全转 Long（解析失败返回 null）。 */
    private static Long toLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 原子落盘：序列化 → 写临时文件 → ATOMIC_MOVE 重命名（防写入中断损坏）。
     *
     * @param projectId 项目 ID
     *
     * @param store 待持久化的 store
     */
    private void persist(Long projectId, InMemoryEmbeddingStore<TextSegment> store) {
        try {
            Path dir = storeFile(projectId).getParent();
            Files.createDirectories(dir);
            String json = store.serializeToJson();
            Path tmp = Files.createTempFile(dir, "embeddings-", ".json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, storeFile(projectId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("[向量库] 项目 {} 向量库落盘失败（内存态保留，下次变更重试）: {}", projectId, e.getMessage());
        }
    }

    /**
     * 生成 chunk 全局 ID（docId:chunkIndex，LangChain4j 条目 ID；removeAll 走元数据过滤，ID 仅保证唯一）。
     *
     * @param docId 文档 ID
     *
     * @param count 分块数
     *
     * @return ID 列表
     */
    private static List<String> chunkIds(Long docId, int count) {
        List<String> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(docId + ":" + i);
        }
        return ids;
    }

    /**
     * 进程内锁执行（变更 + 落盘串行化）。
     *
     * @param projectId 项目 ID
     *
     * @param action 临界区动作
     */
    private void withLock(Long projectId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
