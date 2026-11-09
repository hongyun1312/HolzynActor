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

package com.holzyn.actor.domain.world.fact.repository;

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 事实认知仓库（每人每事实一行；唯一键 project_id+fact_id+knower_ref）。
 */
@Repository
public interface ActorWorldFactKnowledgeRepository extends JpaRepository<ActorWorldFactKnowledge, Long> {

    /**
     * 按唯一键查认知行（learn 幂等）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factId    事实行 ID
     *
     * @param knowerRef 知情者 ref
     *
     * @return 认知行（无则空）
     */
    Optional<ActorWorldFactKnowledge> findByProjectIdAndFactIdAndKnowerRef(
            Long projectId, Long factId, String knowerRef);

    /**
     * 某知情者的认知行（按获知时间倒序；带条数限制）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param knowerRef 知情者 ref
     *
     * @param pageable  分页/条数
     *
     * @return 认知行列表
     */
    List<ActorWorldFactKnowledge> findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
            Long projectId, String knowerRef, Pageable pageable);

    /**
     * 某知情者的认知条数（摘要溢出提示用）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param knowerRef 知情者 ref
     *
     * @return 条数
     */
    long countByProjectIdAndKnowerRef(Long projectId, String knowerRef);
}
