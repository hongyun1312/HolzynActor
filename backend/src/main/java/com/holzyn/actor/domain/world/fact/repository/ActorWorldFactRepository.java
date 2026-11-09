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

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 世界事实仓库（只读查询为主；写入只经 {@code WorldFactService}/{@code EngineStateMirrorService}）。
 */
@Repository
public interface ActorWorldFactRepository extends JpaRepository<ActorWorldFact, Long> {

    /**
     * 按项目 + 稳定键查唯一事实。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factKey   稳定键
     *
     * @return 事实（无则空）
     */
    Optional<ActorWorldFact> findByProjectIdAndFactKey(Long projectId, String factKey);

    /**
     * 项目内某状态的全部事实（台账/调试）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param status    状态
     *
     * @return 事实列表
     */
    List<ActorWorldFact> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    /**
     * 项目全部事实（事实台账只读浏览，新→旧）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 事实列表
     */
    List<ActorWorldFact> findByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 项目内某地点 + 状态的事实。
     *
     * @param projectId    项目/世界 ID
     *
     * @param locationName 地点名
     *
     * @param status       状态
     *
     * @return 事实列表
     */
    List<ActorWorldFact> findByProjectIdAndLocationNameAndStatusOrderByIdAsc(
            Long projectId, String locationName, String status);

    /**
     * 项目内某类型 + 状态的事实（带条数限制）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factType  类型
     *
     * @param status    状态
     *
     * @param pageable  分页/条数
     *
     * @return 事实列表
     */
    List<ActorWorldFact> findByProjectIdAndFactTypeAndStatusOrderByIdAsc(
            Long projectId, String factType, String status, Pageable pageable);

    /**
     * 到期扫描：active 且 valid_to ≤ 当前游戏秒。
     *
     * @param projectId  项目/世界 ID
     *
     * @param status     状态（传 active）
     *
     * @param gameSecond 当前游戏秒
     *
     * @return 到期事实列表
     */
    List<ActorWorldFact> findByProjectIdAndStatusAndValidToGameSecondLessThanEqualOrderByIdAsc(
            Long projectId, String status, Long gameSecond);

    /**
     * 事实层最大版本（stateVersion 组成；无事实返回 0）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 最大版本
     */
    @Query("select coalesce(max(f.version), 0) from ActorWorldFact f where f.projectId = :projectId")
    long maxVersion(@Param("projectId") Long projectId);

    /**
     * 项目内事实条数（调试/台账）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param status    状态
     *
     * @return 条数
     */
    long countByProjectIdAndStatus(Long projectId, String status);
}
