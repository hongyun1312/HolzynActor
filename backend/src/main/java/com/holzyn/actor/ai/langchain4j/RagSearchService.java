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

import com.holzyn.actor.ai.KnowledgeHit;

import java.util.List;

/**
 * LangChain4j RAG 检索接口。
 * <p>职责：知识检索侧新路径——query 经 {@link DynamicEmbeddingModelFactory} 的 LangChain4j
 * {@code OpenAiEmbeddingModel} 向量化后，在 {@link VectorStoreService} 向量库中做相似度检索，
 * 返回与旧路径 {@code KnowledgeRetrievalService.search} 同形的 {@link List<KnowledgeHit>}
 * （方案 §6 契约表：KnowledgeHit 签名不变）。</p>
 * <p><b>语义对齐现状</b>（方案 §5.3）：项目级全检（characterId 空恒纳入）+ 当前角色级合并；
 * 未配置 embedding / 调用失败 → 上层（兼容壳）降级文本关键词检索。</p>
 * <p><b>审计口径</b>：query 向量化同样创建 scene=embedding trace +
 * UsageLogService 用量。</p>
 */
public interface RagSearchService {

    /**
     * LangChain4j RAG 检索（query 向量化 + 向量库相似度 + 双粒度合并）。
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID（向量库按项目隔离）
     *
     * @param characterId 当前角色 ID（可空=仅项目级；非空=项目级 + 该角色级合并）
     *
     * @param query 查询文本
     *
     * @param topK 返回条数（默认 3）
     *
     * @return 命中的知识片段列表（按相关度降序；未配置/失败返回空列表，由上层降级）
     */
    List<KnowledgeHit> search(Long userId, Long projectId, Long characterId, String query, int topK);
}
