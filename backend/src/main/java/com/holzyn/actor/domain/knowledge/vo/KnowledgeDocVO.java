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

package com.holzyn.actor.domain.knowledge.vo;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;

import java.time.LocalDateTime;

/**
 * 知识文档视图对象（KnowledgeDocVO）。
 * <p>职责：向前端返回知识文档数据，补充分块数/向量化状态（四态 storeState）等聚合信息。</p>
 * <p><b>向量化四态</b>：
 * <ul>
 * <li>{@code VECTORIZED}：向量库含该 docId 的 chunk（chunkCount&gt;0，embeddingModel 展示）；</li>
 * <li>{@code NOT_CONFIGURED}：未配置 embedding 供应商（预期降级，前端顶部降级提示）；</li>
 * <li>{@code FAILED}：供应商已配置但向量库无该文档 chunk（向量化失败/未入库）；</li>
 * <li>{@code EMPTY}：文档内容为空（无内容可分块向量化）。</li>
 * </ul></p>
 *
 * @param id 文档主键
 *
 * @param projectId 项目 ID
 *
 * @param characterId 角色级归属（空=项目级）
 *
 * @param characterName 角色级归属角色名（空=项目级，供前端展示）
 *
 * @param title 文档标题
 *
 * @param content 文档全文
 *
 * @param chunkCount 分块数（向量化四态：VECTORIZED 时 &gt;0，其余 0）
 *
 * @param vectorized 是否已向量化（=storeState 为 VECTORIZED，兼容旧字段保留）
 *
 * @param storeState 向量化四态：VECTORIZED/NOT_CONFIGURED/FAILED/EMPTY
 *
 * @param embeddingModel 向量化所用 embedding 模型（展示用，可空）
 *
 * @param createdAt 创建时间
 *
 * @param updatedAt 更新时间
 *
 * @param lifecycleState 知识生命周期状态（P1 ②-2）：hot/warm/stale/archived
 *
 * @param confidence 知识置信度（0~1）
 *
 * @param accessCount 累计被检索引用次数
 *
 * @param lastAccessAt 最后被检索引用时间
 */
public record KnowledgeDocVO(
        Long id,
        Long projectId,
        Long characterId,
        String characterName,
        String title,
        String content,
        int chunkCount,
        boolean vectorized,
        String storeState,
        String embeddingModel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String lifecycleState,
        Double confidence,
        Integer accessCount,
        LocalDateTime lastAccessAt
) {
    /** 向量化四态常量：已向量化 */
    public static final String STATE_VECTORIZED = "VECTORIZED";
    /** 向量化四态常量：未配置 embedding 供应商 */
    public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";
    /** 向量化四态常量：供应商已配置但向量库无该文档（向量化失败） */
    public static final String STATE_FAILED = "FAILED";
    /** 向量化四态常量：文档内容为空 */
    public static final String STATE_EMPTY = "EMPTY";

    /**
     * 从实体构造 VO。
     *
     * @param d 文档实体
     *
     * @param characterName 角色名（可空）
     *
     * @param chunkCount 分块数
     *
     * @param storeState 向量化四态（VECTORIZED/NOT_CONFIGURED/FAILED/EMPTY）
     *
     * @param embeddingModel embedding 模型名（可空）
     *
     * @return VO 对象
     */
    public static KnowledgeDocVO of(ActorKnowledgeDoc d, String characterName, int chunkCount,
                                    String storeState, String embeddingModel) {
        boolean vectorized = STATE_VECTORIZED.equals(storeState);
        return new KnowledgeDocVO(d.getId(), d.getProjectId(), d.getCharacterId(), characterName,
                d.getTitle(), d.getContent(), chunkCount, vectorized, storeState, embeddingModel,
                d.getCreatedAt(), d.getUpdatedAt(), d.getLifecycleState(), d.getConfidence(),
                d.getAccessCount(), d.getLastAccessAt());
    }
}
