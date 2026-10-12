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

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 动态 embedding 模型工厂。
 * <p>职责：按运行时解析出的 {@link ProviderConfig}（baseUrl/apiKey/model，purpose=embedding/both 的配置）
 * 构建 {@link OpenAiEmbeddingModel}（LangChain4j OpenAI 兼容 /embeddings 接入，.baseUrl() 指向
 * DeepSeek/豆包/通义等任意兼容端点），并以<b>逻辑配置主键（providerId）</b>缓存复用
 * （{@code ConcurrentHashMap}，尺寸有界=已配置供应商数）。</p>
 * <p><b>缓存失效策略（与 DynamicChatModelFactory 同模式 = 惰性校验 + 快照比对）</b>：不侵入
 * {@code ModelApiService} 的 CRUD——每次 {@link #get} 把当前 ProviderConfig 与缓存条目内保存的
 * 配置快照比对，baseUrl/apiKey/model 任一变化即重建替换（Key 轮换/地址变更天然生效）；
 * 并发下允许重复构建（模型无状态，后写覆盖，成本可接受）。{@link #invalidate} 提供显式逐键清除。</p>
 * <p><b>未配置语义</b>：本工厂只负责「配置→模型」；embedding 供应商未配置由上层
 * （{@code ModelApiService.resolveEmbeddingProvider} 返回 null）先行短路，保留现有
 * {@code isRagAvailable} 文本降级语义；模型缺失/构建失败抛 {@link AiCallException}（与旧 Provider 口径一致）。</p>
 */
@Slf4j
@Component
public class DynamicEmbeddingModelFactory {

    /**
     * 构建 EmbeddingModel 所需的配置快照（用于惰性失效比对）。
     *
     * @param apiKey 明文 Key（仅存内存；模型实例本身也持有该 Key，不新增暴露面）
     *
     * @param model embedding 模型名
     */
    private record Snapshot(String baseUrl, String apiKey, String model) {

        /** 从运行时配置取快照 */
        static Snapshot of(ProviderConfig c) {
            return new Snapshot(c.baseUrl(), c.apiKey(), c.model());
        }

        /** 与当前配置比对：任一关键字段变化即视为失效需重建 */
        boolean matches(ProviderConfig c) {
            return eq(baseUrl, c.baseUrl()) && eq(apiKey, c.apiKey()) && eq(model, c.model());
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** 缓存条目：配置快照 + 构建好的模型 */
    private static final class Entry {
        final Snapshot snapshot;
        final OpenAiEmbeddingModel model;

        Entry(Snapshot snapshot, OpenAiEmbeddingModel model) {
            this.snapshot = snapshot;
            this.model = model;
        }
    }

    /** 模型缓存：providerId → 条目 */
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 按运行时配置取（懒构建 + 惰性失效）EmbeddingModel。
     *
     * @param config 已解析的 embedding 供应商运行时配置（含解密 Key；id 为全局唯一主键）
     *
     * @return 缓存复用或新建的 OpenAiEmbeddingModel
     */
    public OpenAiEmbeddingModel get(ProviderConfig config) {
        Long key = config.id();
        if (key == null) {
            throw new AiCallException("无法构建 LangChain4j EmbeddingModel：ProviderConfig 缺少配置主键");
        }
        Entry entry = cache.get(key);
        if (entry != null && entry.snapshot.matches(config)) {
            return entry.model;
        }
        OpenAiEmbeddingModel model = build(config);
        cache.put(key, new Entry(Snapshot.of(config), model));
        log.info("[LangChain4j] 构建/重建 EmbeddingModel: providerId={} baseUrl={} model={}", key, config.baseUrl(), config.model());
        return model;
    }

    /**
     * 显式逐键失效（测试/未来 ModelApiService 事件钩子用；惰性校验下通常无需调用）。
     *
     * @param providerId 配置主键（null 忽略）
     */
    public void invalidate(Long providerId) {
        if (providerId != null) {
            cache.remove(providerId);
        }
    }

    /** 当前缓存模型数（测试/启动报告用） */
    public int cachedCount() {
        return cache.size();
    }

    /**
     * 构建 OpenAiEmbeddingModel（LangChain4j 1.x Builder API：.baseUrl/.apiKey/.modelName）。
     * <p>模型名缺失 → AiCallException（与旧 EmbeddingService「未指定 embedding 模型名」口径一致）。</p>
     *
     * @param config 供应商运行时配置
     *
     * @return 构建好的模型
     */
    private OpenAiEmbeddingModel build(ProviderConfig config) {
        String model = config.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未配置 embedding 模型名：请在「设置-模型 API」为该供应商填写 embedding 模型");
        }
        try {
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .modelName(model)
                    .build();
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            // 构建失败（非法 baseUrl/Key 格式等）：包装为带中文提示的 AiCallException，不泄漏堆栈
            throw new AiCallException("LangChain4j EmbeddingModel 构建失败：请检查 Base URL / Key / 模型名（" + e.getMessage() + "）");
        }
    }
}
