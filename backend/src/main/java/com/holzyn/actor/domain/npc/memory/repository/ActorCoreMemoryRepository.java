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

package com.holzyn.actor.domain.npc.memory.repository;

import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * L3 核心记忆仓库。
 * <p>职责：核心块查询——按项目/角色/块键/删除标记/重要度排序；CRUD 由
 * {@code CoreMemoryService} 编排。</p>
 */
public interface ActorCoreMemoryRepository extends JpaRepository<ActorCoreMemory, Long> {

    /**
     * 查询某角色全部未删核心块（重要度 desc + 较新优先，注入/详情/自编辑共用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（special:{id}/crowd:{id}；NULL=项目级核心块）
     *
     * @param deleted 软删标记（0）
     *
     * @return 核心块列表（重要度高的在前）
     */
    List<ActorCoreMemory> findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
            Long projectId, String characterRef, Integer deleted);

    /**
     * 查询某角色指定块键的未删核心块（同键最多 1 条活动块，CRUD/自编辑版本链用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param blockKey 块键（identity/personality/beliefs/values/goals/relations/custom:*）
     *
     * @param deleted 软删标记（0）
     *
     * @return 匹配的核心块列表
     */
    List<ActorCoreMemory> findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
            Long projectId, String characterRef, String blockKey, Integer deleted);

    /**
     * 统计某角色未删核心块数量（每角色 ≤max-blocks 上限校验用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param deleted 软删标记（0）
     *
     * @return 未删核心块数
     */
    long countByProjectIdAndCharacterRefAndDeleted(Long projectId, String characterRef, Integer deleted);

    // ===== （记忆治理健康度）：项目维度 / 版本链查询 =====

    /**
     * 统计某项目全部未删核心块数量（健康度分层分布用，含全部角色/项目级）。
     */
    long countByProjectIdAndDeleted(Long projectId, Integer deleted);

    /**
     * 查询某项目已进入版本链（supersedes_id 非空）的未删核心块（健康度版本链数，全部角色）。
     */
    List<ActorCoreMemory> findByProjectIdAndSupersedesIdIsNotNullAndDeleted(
            Long projectId, Integer deleted);

    /**
     * 查询某角色已进入版本链（supersedes_id 非空）的未删核心块（健康度版本链数，角色维度）。
     */
    List<ActorCoreMemory> findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeleted(
            Long projectId, String characterRef, Integer deleted);
}
