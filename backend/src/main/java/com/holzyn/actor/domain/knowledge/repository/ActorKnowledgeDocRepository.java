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

package com.holzyn.actor.domain.knowledge.repository;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档仓库（actor_knowledge_doc）。
 * <p>职责：提供按项目/角色查询知识文档的访问能力，支撑 RAG 检索与文档管理。</p>
 */
public interface ActorKnowledgeDocRepository extends JpaRepository<ActorKnowledgeDoc, Long> {

    /**
     * 查询某项目的全部知识文档（按创建顺序）。
     *
     * @param projectId 项目 ID
     *
     * @return 文档列表
     */
    List<ActorKnowledgeDoc> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 按 id + 项目查询文档（归属校验）。
     *
     * @param id 文档主键
     *
     * @param projectId 项目 ID
     *
     * @return 匹配的文档（可能为空）
     */
    Optional<ActorKnowledgeDoc> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 关键词检索（观测域全局搜索用，只读）。
     * <p>命中字段：title / content；大小写不敏感子串匹配；标题命中优先，其次 id 降序（新文档优先）；
     * 由调用方 Pageable 限制条数。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（已 trim）
     *
     * @param pageable  分页/条数限制
     *
     * @return 命中的文档（标题命中优先）
     */
    @Query("select d from ActorKnowledgeDoc d where d.projectId = :projectId and ("
            + "lower(d.title) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(d.content, '')) like lower(concat('%', :q, '%')))"
            + " order by case when lower(d.title) like lower(concat('%', :q, '%')) then 0 else 1 end, d.id desc")
    List<ActorKnowledgeDoc> searchByKeyword(@Param("projectId") Long projectId,
                                            @Param("q") String q,
                                            Pageable pageable);
}
