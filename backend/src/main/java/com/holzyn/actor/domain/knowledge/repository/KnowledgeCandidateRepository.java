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

import com.holzyn.actor.domain.knowledge.entity.KnowledgeCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 记忆-知识桥接候选仓库（knowledge_candidate）。
 * <p>职责：候选留档（promoted/duplicate/rejected 全状态审计）+ 去重查询（同角色已确认候选）。</p>
 */
public interface KnowledgeCandidateRepository extends JpaRepository<KnowledgeCandidate, Long> {

    /**
     * 查询某项目的候选列表（最新在前，管理/观测用）。
     *
     * @param projectId 项目 ID
     *
     * @return 候选列表
     */
    List<KnowledgeCandidate> findByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 查询某角色已确认（promoted）的候选（去重数据源）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用
     *
     * @return 已确认候选列表
     */
    List<KnowledgeCandidate> findByProjectIdAndCharacterRefAndStatus(Long projectId, String characterRef, String status);
}
