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

package com.holzyn.actor.domain.craft.repository;

import com.holzyn.actor.domain.craft.entity.ActorCraftJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 制作作业仓储。
 *
 * <p>查询口径只有三种：幂等去重、到点结算扫描、「这个人手上还有没有活」。
 * 不提供跨项目查询——一个世界的作业不能漏到另一个世界。</p>
 */
@Repository
public interface ActorCraftJobRepository extends JpaRepository<ActorCraftJob, Long> {

    /**
     * 按幂等键查作业（重放不重复开工）。
     *
     * @param projectId      项目 ID
     * @param idempotencyKey 幂等键
     *
     * @return 作业
     */
    Optional<ActorCraftJob> findByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    /**
     * 某角色进行中的作业（一个角色同时只做一件活）。
     *
     * @param projectId 项目 ID
     * @param actorRef  角色 ref
     * @param status    状态（一般传 in_progress）
     *
     * @return 作业列表（按开工先后）
     */
    List<ActorCraftJob> findByProjectIdAndActorRefAndStatusOrderByStartedGameSecondAsc(
            Long projectId, String actorRef, String status);

    /**
     * 到点待结算的作业（status=in_progress 且 finish ≤ 当前游戏秒）。
     *
     * @param projectId  项目 ID
     * @param status     状态
     * @param gameSecond 当前游戏秒
     * @param limit      单轮上限
     *
     * @return 待结算作业（先到点的先结算）
     */
    @Query("select j from ActorCraftJob j where j.projectId = :projectId and j.status = :status "
            + "and j.finishGameSecond <= :gameSecond order by j.finishGameSecond asc, j.id asc")
    List<ActorCraftJob> findDue(@Param("projectId") Long projectId, @Param("status") String status,
                                @Param("gameSecond") long gameSecond,
                                org.springframework.data.domain.Pageable limit);

    /**
     * 某角色的作业历史（排障/前端用）。
     *
     * @param projectId 项目 ID
     * @param actorRef  角色 ref
     * @param limit     上限
     *
     * @return 作业列表（新的在前）
     */
    List<ActorCraftJob> findByProjectIdAndActorRefOrderByIdDesc(Long projectId, String actorRef,
                                                                org.springframework.data.domain.Pageable limit);

    /**
     * 某状态下有多少件活（冒烟/看板断言用）。
     *
     * @param projectId 项目 ID
     * @param status    状态
     *
     * @return 数量
     */
    long countByProjectIdAndStatus(Long projectId, String status);

    /**
     * 某设施当前是否被进行中的作业占用（R-CRAFT-01 的 OCCUPIED 判定）。
     *
     * @param projectId   项目 ID
     * @param facilityRef 设施 ref
     * @param status      状态
     *
     * @return 作业列表
     */
    List<ActorCraftJob> findByProjectIdAndFacilityRefAndStatusOrderByIdAsc(
            Long projectId, String facilityRef, String status);
}
