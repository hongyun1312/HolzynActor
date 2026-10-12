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

package com.holzyn.actor.domain.growth.evolution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 人格偏移日志仓库。
 * <p>查询按 npc_ref / 维度 / 触发码过滤；{@code findLatestByNpcRef} 供「下次周期评估基准」读取
 * （{@code PersonalityShiftService} 用最近一次评估时刻判定 30 游戏日周期）。</p>
 */
public interface GrowthShiftLogRepository extends JpaRepository<GrowthShiftLog, Long> {

    /** 某角色偏移日志（按发生时刻倒序，最多 200 条，调试页/证据链回放）。 */
    List<GrowthShiftLog> findTop200ByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 某角色最近一条偏移日志（周期评估基准；无记录返回 empty）。 */
    Optional<GrowthShiftLog> findTopByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 某角色某维度最近一条偏移日志（面具强度演化基准）。 */
    Optional<GrowthShiftLog> findTopByProjectIdAndNpcRefAndDimOrderByIdDesc(
            Long projectId, String npcRef, String dim);

    /** 项目全量偏移日志（按发生时刻倒序，最多 200 条）。 */
    List<GrowthShiftLog> findTop200ByProjectIdOrderByIdDesc(Long projectId);

    /** 某角色偏移次数（评估触发去重/测试断言用）。 */
    long countByProjectIdAndNpcRef(Long projectId, String npcRef);

    /** 某角色最近评估时刻（游戏秒；无记录返回 null；cycle 触发码之外的手动评估不算周期基准）。 */
    @Query("select max(s.gameSecond) from GrowthShiftLog s "
            + "where s.projectId = :projectId and s.npcRef = :npcRef and s.triggerCode in "
            + "('cycle','stage_transition','major_event')")
    Long lastAssessedGameSecond(@Param("projectId") Long projectId, @Param("npcRef") String npcRef);
}
