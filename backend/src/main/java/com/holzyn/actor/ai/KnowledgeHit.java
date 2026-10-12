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

package com.holzyn.actor.ai;

/**
 * 知识检索命中片段。
 * <p>方案《AI接入层重构_SpringAI_LangChain4j_技术方案》§6 接口契约表：{@code KnowledgeHit}
 * 与 {@link AiChatRequest}/{@link AiChatResult}/{@link AiUsage}/{@link ProviderConfig} 并列列为
 * 「签名不变（兼容壳契约）」的共享类型——旧余弦路径（{@code KnowledgeRetrievalService.search}）与
 * 新 LangChain4j RAG 路径（{@code RagSearchService}）统一产出本类型，兼容壳/感知层/对话层/知识页
 * 消费方零感知。</p>
 * <p>字段与原 {@code KnowledgeRetrievalService.KnowledgeHit} 内嵌 record 完全一致（签名不变），
 * 仅提升包位置：避免 {@code ai/langchain4j → domain/knowledge} 反向包依赖（domain/knowledge 已
 * 依赖 ai 层，包环会破坏「domain 单向依赖 ai」的架构约束）。</p>
 *
 * @param docId 来源文档 ID
 *
 * @param title 文档标题
 *
 * @param text 命中的知识片段文本
 *
 * @param score 相关度得分（旧路径=余弦相似度；新路径=LangChain4j 相似度）
 *
 * @param characterId 文档角色级归属（空=项目级）
 *
 * @param stale 是否「可能过时」（P1 ②-2 知识层生命周期：来源文档处于 stale 状态，
 *              消费方注入 prompt 时标注，AI 引用时自行斟酌；false=正常）
 */
public record KnowledgeHit(Long docId, String title, String text, double score, Long characterId, boolean stale) {

    /** 便捷构造（stale=false）：兼容既有构造点与测试。 */
    public KnowledgeHit(Long docId, String title, String text, double score, Long characterId) {
        this(docId, title, text, score, characterId, false);
    }
}
