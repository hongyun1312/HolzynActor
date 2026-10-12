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

package com.holzyn.actor.domain.npc.goal.repository;

import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 角色目标仓库（actor_character_goal，阶段 D，domain/agent/thinking/repository）。
 * <p>职责：提供按项目/角色引用/状态查询目标的能力——目标注入感知、反思状态流转、
 * 手动 CRUD 与前端展示共用；软删标记统一按 deleted=0 过滤。</p>
 */
public interface ActorCharacterGoalRepository extends JpaRepository<ActorCharacterGoal, Long> {

    /**
     * 查询某角色全部未删目标（按创建时间倒序）。
     *
     * @param projectId 项目 ID
     *
     * @param agentRef 统一角色引用（special:{id}）
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表（新在前）
     */
    List<ActorCharacterGoal> findByProjectIdAndAgentRefAndDeletedOrderByCreatedAtDesc(
            Long projectId, String agentRef, Integer deleted);

    /**
     * 查询某角色指定状态的全部未删目标（按创建时间倒序，感知注入/反思流转用）。
     *
     * @param projectId 项目 ID
     *
     * @param agentRef 统一角色引用
     *
     * @param status 状态（active/done/abandoned）
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表（新在前）
     */
    List<ActorCharacterGoal> findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
            Long projectId, String agentRef, String status, Integer deleted);

    /**
     * 查询某角色全部未删目标（分页，前端目标 Tab 用）。
     *
     * @param projectId 项目 ID
     *
     * @param agentRef 统一角色引用
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表
     */
    List<ActorCharacterGoal> findByProjectIdAndAgentRefAndDeleted(
            Long projectId, String agentRef, Integer deleted);

    // ===== （决策链路可视化 · 漂移度量视图）：目标一致率聚合查询 =====

    /**
     * 查询某项目下全部未删目标（漂移度量：目标一致率按项目聚合，按 id 升序=创建顺序）。
     *
     * @param projectId 项目 ID
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表（按 id 升序）
     */
    List<ActorCharacterGoal> findByProjectIdAndDeletedOrderByIdAsc(Long projectId, Integer deleted);

    /**
     * 查询某角色引用下全部未删目标（漂移度量：目标一致率按角色聚合，按 id 升序）。
     *
     * @param agentRef 统一角色引用（special:{id}）
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表（按 id 升序）
     */
    List<ActorCharacterGoal> findByAgentRefAndDeletedOrderByIdAsc(String agentRef, Integer deleted);

    /**
     * 查询全部未删目标（漂移度量：全局口径，无项目过滤时使用，按 id 升序）。
     *
     * @param deleted 软删标记（0）
     *
     * @return 目标列表（按 id 升序）
     */
    List<ActorCharacterGoal> findByDeletedOrderByIdAsc(Integer deleted);
}
