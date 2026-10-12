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

import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 项目记忆 L2 编年史仓库。
 * <p>职责：编年史条目的查询/幂等/归档扫描；关联事件 id 幂等（即时提炼去重）与每日批量幂等
 * （当日 source=daily 存在即跳过）在此收敛。</p>
 */
public interface ActorWorldChronicleRepository extends JpaRepository<ActorWorldChronicle, Long> {

    /** 按项目+游戏日倒序分页（编年史查询主入口）。 */
    Page<ActorWorldChronicle> findByProjectIdOrderByGameDayDescIdDesc(Long projectId, Pageable pageable);

    /** 按项目+重要度倒序分页（重要度筛选）。 */
    Page<ActorWorldChronicle> findByProjectIdAndImportanceGreaterThanEqualOrderByGameDayDescIdDesc(
            Long projectId, Integer importance, Pageable pageable);

    /** 按项目+关联实体筛选（entityKey LIKE 匹配 involved_entities JSON 文本，保守过滤可单测）。 */
    @Query("SELECT c FROM ActorWorldChronicle c WHERE c.projectId = :projectId "
            + "AND c.involvedEntities LIKE %:entityKey% ORDER BY c.gameDay DESC, c.id DESC")
    Page<ActorWorldChronicle> findByProjectIdAndEntityKeyContaining(@Param("projectId") Long projectId,
                                                                    @Param("entityKey") String entityKey,
                                                                    Pageable pageable);

    /** 按项目+游戏日区间筛选（fromDay/toDay，含边界）。 */
    @Query("SELECT c FROM ActorWorldChronicle c WHERE c.projectId = :projectId "
            + "AND (:fromDay IS NULL OR c.gameDay >= :fromDay) "
            + "AND (:toDay IS NULL OR c.gameDay <= :toDay) "
            + "ORDER BY c.gameDay DESC, c.id DESC")
    Page<ActorWorldChronicle> findByProjectIdAndDayRange(@Param("projectId") Long projectId,
                                                         @Param("fromDay") Long fromDay,
                                                         @Param("toDay") Long toDay,
                                                         Pageable pageable);

    /** 最近 N 条（注入【世界近闻】与概览用）。 */
    List<ActorWorldChronicle> findTop10ByProjectIdOrderByGameDayDescIdDesc(Long projectId);

    /** 按项目+来源（每日批量幂等：当日 source=daily 存在即跳过）。 */
    List<ActorWorldChronicle> findByProjectIdAndGameDayAndSource(Long projectId, Long gameDay, String source);

    /** 是否存在引用某事件 id 的编年史（即时提炼幂等：加载该项目全部条目后内存过滤，量小可接受）。 */
    List<ActorWorldChronicle> findByProjectIdOrderByGameDayDesc(Long projectId);

    /** 归档扫描：游戏日早于 threshold（超龄）的条目。 */
    List<ActorWorldChronicle> findByProjectIdAndGameDayLessThanEqualOrderByGameDayAsc(
            Long projectId, Long gameDay);

    /** 统计（概览用）。 */
    long countByProjectId(Long projectId);

    /**
     * trace 回灌审计：统计某项目某游戏日带「宏观」徽标的编年史条数（tags JSON 文本
     * LIKE 匹配 ["macro"]，保守过滤；WorldMemoryService kind=macro 即时提炼成条打标）。
     */
    long countByProjectIdAndGameDayAndTagsContaining(Long projectId, Long gameDay, String tags);
}
