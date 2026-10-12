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

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 世界观地点表仓库。
 * <p>职责：按项目查询/维护地点（排序稳定：sortOrder 升序 + id 升序兜底）。
 * 项目级归属，随 .holzyn 导入导出。</p>
 */
public interface WorldLocationRepository extends JpaRepository<ActorWorldLocation, Long> {

    /** 按项目查询地点（sortOrder 升序，同序按 id 升序，保证顺序稳定） */
    List<ActorWorldLocation> findByProjectIdOrderBySortOrderAscIdAsc(Long projectId);

    /** 按项目查询全部地点（供候选/统计，顺序无关） */
    List<ActorWorldLocation> findByProjectId(Long projectId);

    /** 按项目统计地点数（图谱查询层·概览计数用） */
    long countByProjectId(Long projectId);

    /**
     * 关键词检索（观测域全局搜索用，只读）。
     * <p>命中字段：name / intro / type；大小写不敏感子串匹配；名称命中优先，
     * 由调用方的 Pageable 限制条数（不加载全表）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（已 trim，不含 % 通配语义）
     *
     * @param pageable  分页/条数限制
     *
     * @return 命中的地点（名称命中优先，其次 sortOrder 升序）
     */
    @Query("select l from ActorWorldLocation l where l.projectId = :projectId and ("
            + "lower(l.name) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(l.intro, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(l.type, '')) like lower(concat('%', :q, '%')))"
            + " order by case when lower(l.name) like lower(concat('%', :q, '%')) then 0 else 1 end, l.sortOrder asc, l.id asc")
    List<ActorWorldLocation> searchByKeyword(@Param("projectId") Long projectId,
                                             @Param("q") String q,
                                             Pageable pageable);
}
