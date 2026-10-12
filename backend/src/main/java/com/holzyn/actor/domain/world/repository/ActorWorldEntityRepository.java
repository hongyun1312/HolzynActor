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

import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 项目记忆 L3 实体状态仓库。
 * <p>职责：实体状态当前态/历史版本链/as-of 查询（Zep 式失效更新：valid_from/to 窗口）。</p>
 */
public interface ActorWorldEntityRepository extends JpaRepository<ActorWorldEntity, Long> {

    /** 某实体键当前有效行（valid_to IS NULL）。 */
    Optional<ActorWorldEntity> findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(
            Long projectId, String entityKey);

    /** 某实体键全部版本链（新→旧）。 */
    List<ActorWorldEntity> findByProjectIdAndEntityKeyOrderByIdDesc(Long projectId, String entityKey);

    /** 某实体键在指定游戏日有效的行（as-of-date：valid_from <= day 且 (valid_to IS NULL 或 >day)）。 */
    @Query("SELECT e FROM ActorWorldEntity e WHERE e.projectId = :projectId AND e.entityKey = :entityKey "
            + "AND e.validFromGameDay <= :day AND (e.validToGameDay IS NULL OR e.validToGameDay > :day)")
    List<ActorWorldEntity> findAsOf(@Param("projectId") Long projectId,
                                    @Param("entityKey") String entityKey,
                                    @Param("day") Long day);

    /** 全部行（新→旧；as-of 聚合 / 实体名解析用，量小可接受）。 */
    List<ActorWorldEntity> findByProjectIdOrderByIdDesc(Long projectId);

    /** 当前有效实体分页（valid_to IS NULL；type 可空=全部）。 */
    Page<ActorWorldEntity> findByProjectIdAndValidToGameDayIsNullOrderByUpdatedAtDescIdDesc(
            Long projectId, Pageable pageable);

    /** 当前有效实体分页（按类型过滤）。 */
    Page<ActorWorldEntity> findByProjectIdAndEntityTypeAndValidToGameDayIsNullOrderByUpdatedAtDescIdDesc(
            Long projectId, String entityType, Pageable pageable);

    /** 当前有效且有近期变化的实体（【世界动态】注入素材：recent_change 非空，按更新时间降序）。 */
    List<ActorWorldEntity> findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(
            Long projectId);

    /** 统计（概览用）。 */
    long countByProjectId(Long projectId);

    /** 当前有效实体数（概览用）。 */
    long countByProjectIdAndValidToGameDayIsNull(Long projectId);
}
