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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 决策点台账仓库（actor_decision_log，P0-03 度量基座）。
 * <p>职责：落一行=一个决策点；按游戏日分组统计 AI/兜底/预算降级/缺失 各来源数量与占比
 * （审计 P0-02 验收 1「三类之和 = 该日应有的决策点总数」）。</p>
 */
public interface ActorDecisionLogRepository extends JpaRepository<ActorDecisionLog, Long> {

    /**
     * 按项目 + 游戏日统计各决策来源数量。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日（自纪元起）
     *
     * @return 投影列表 [decision_source, 数量]
     */
    @Query("select d.decisionSource as source, count(d) as cnt from ActorDecisionLog d "
            + "where d.projectId = :projectId and d.gameDay = :gameDay group by d.decisionSource")
    List<SourceCount> countBySource(@Param("projectId") Long projectId, @Param("gameDay") Long gameDay);

    /**
     * 按项目 + 游戏秒窗口统计各决策来源数量（供每轮 tick 统计持久化）。
     *
     * @param projectId 项目 ID
     *
     * @param fromSecond 窗口下界（含）
     *
     * @param toSecond 窗口上界（不含）
     *
     * @return 投影列表 [decision_source, 数量]
     */
    @Query("select d.decisionSource as source, count(d) as cnt from ActorDecisionLog d "
            + "where d.projectId = :projectId and d.gameSecond >= :fromSecond and d.gameSecond < :toSecond "
            + "group by d.decisionSource")
    List<SourceCount> countBySourceBetween(@Param("projectId") Long projectId,
                                           @Param("fromSecond") Long fromSecond,
                                           @Param("toSecond") Long toSecond);

    /**
     * 某游戏日决策点总数（三类/四类来源之和 = 该日决策点总数）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日（自纪元起）
     *
     * @return 决策点总数
     */
    long countByProjectIdAndGameDay(Long projectId, Long gameDay);

    /**
     * 查询某项目某游戏日的决策台账明细（按 id 升序=决策先后）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日（自纪元起）
     *
     * @return 明细列表
     */
    List<ActorDecisionLog> findByProjectIdAndGameDayOrderByIdAsc(Long projectId, Long gameDay);

    /**
     * 来源计数投影（source=DecisionSource 枚举名，cnt=数量）。
     */
    interface SourceCount {
        /** 决策来源枚举名（DECISION_SOURCE 列） */
        String getSource();

        /** 数量 */
        Long getCnt();
    }
}
