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

package com.holzyn.actor.domain.growth.genetic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 遗传参数包仓库。
 * <p>一行一 NPC（project_id + npc_ref 唯一）；查询按 npcRef、项目、父母三口径。</p>
 */
public interface ActorGrowthGenomeRepository extends JpaRepository<ActorGrowthGenome, Long> {

    Optional<ActorGrowthGenome> findByProjectIdAndNpcRef(Long projectId, String npcRef);

    List<ActorGrowthGenome> findByProjectId(Long projectId);

    List<ActorGrowthGenome> findBySourceParentAOrSourceParentB(Long parentA, Long parentB);

    /** 统计某项目基因行数（调试页展示用）。 */
    long countByProjectId(Long projectId);
}
