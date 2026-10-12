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

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 世界演化会话仓库（actor_evolution）。
 */
@Repository
public interface ActorEvolutionRepository extends JpaRepository<ActorEvolution, Long> {

    /**
     * 按项目查询演化会话（进行中优先、ID 倒序）。
     *
     * @param projectId 项目 ID
     *
     * @return 演化会话列表
     */
    List<ActorEvolution> findByProjectIdOrderByStatusAscIdDesc(Long projectId);

    /**
     * 查询项目下进行中的演化会话。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态（running）
     *
     * @return 进行中的演化会话列表
     */
    Optional<ActorEvolution> findFirstByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);
}
