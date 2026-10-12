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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroTrace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 宏观社会演化 · 全链路 trace 仓库。
 * <p>读取：按项目倒序取最近 N 条（trace 面板数据源）；uk 查询（幂等覆盖写用）；
 * 滚动裁剪=按 id 升序取最旧 K 条（service 侧查 id 后批量删除，兼容 H2/MySQL）。</p>
 */
public interface ActorWorldMacroTraceRepository extends JpaRepository<ActorWorldMacroTrace, Long> {

    /** 按项目倒序取最近 N 条（tickType 可空=全部）。 */
    @Query("select t from ActorWorldMacroTrace t where t.projectId = :projectId "
            + "and (:tickType is null or t.tickType = :tickType) order by t.id desc")
    List<ActorWorldMacroTrace> findRecent(@Param("projectId") Long projectId,
                                          @Param("tickType") String tickType,
                                          Pageable pageable);

    /** uk 查询（幂等覆盖写：同项目同日同类型只保留最新一次 trace）。 */
    Optional<ActorWorldMacroTrace> findByProjectIdAndGameDayAndTickType(Long projectId, Long gameDay, String tickType);

    /** 按 id 升序取最旧 N 条（滚动裁剪候选）。 */
    List<ActorWorldMacroTrace> findByProjectIdOrderByIdAsc(Long projectId, Pageable pageable);

    /** 统计某项目 trace 行数（滚动裁剪判定）。 */
    long countByProjectId(Long projectId);
}
