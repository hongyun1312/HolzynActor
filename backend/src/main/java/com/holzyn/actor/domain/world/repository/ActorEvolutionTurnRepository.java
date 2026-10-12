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

import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 世界演化轮次消息仓库（actor_evolution_turn）。
 */
@Repository
public interface ActorEvolutionTurnRepository extends JpaRepository<ActorEvolutionTurn, Long> {

    /**
     * 按演化会话查询全部轮次消息（时间正序）。
     *
     * @param evolutionId 演化会话 ID
     *
     * @return 轮次消息列表
     */
    List<ActorEvolutionTurn> findByEvolutionIdOrderByIdAsc(Long evolutionId);

    /**
     * 删除某演化会话的全部轮次消息（演化会话删除时级联清理）。
     *
     * @param evolutionId 演化会话 ID
     */
    void deleteByEvolutionId(Long evolutionId);
}
