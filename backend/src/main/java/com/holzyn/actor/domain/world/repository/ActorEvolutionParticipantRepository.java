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

import com.holzyn.actor.domain.world.entity.ActorEvolutionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 世界演化参与者仓库（actor_evolution_participant）。
 */
@Repository
public interface ActorEvolutionParticipantRepository extends JpaRepository<ActorEvolutionParticipant, Long> {

    /**
     * 按演化会话查询参与者（加入顺序）。
     *
     * @param evolutionId 演化会话 ID
     *
     * @return 参与者列表
     */
    List<ActorEvolutionParticipant> findByEvolutionIdOrderByIdAsc(Long evolutionId);

    /**
     * 查询指定演化会话 + 角色的参与者。
     *
     * @param evolutionId 演化会话 ID
     *
     * @param characterId 角色 ID
     *
     * @return 参与者（可能为空）
     */
    Optional<ActorEvolutionParticipant> findByEvolutionIdAndCharacterId(Long evolutionId, Long characterId);

    /**
     * 删除某演化会话的全部参与者（演化会话删除时级联清理）。
     *
     * @param evolutionId 演化会话 ID
     */
    void deleteByEvolutionId(Long evolutionId);
}
