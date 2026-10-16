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

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 决策重试作业仓库（P0-02 根治 · 可重试性）。
 * <p>职责：PENDING 作业的到点查询、按 {@code (project, character, gameSecond)} 去重、
 * 状态统计，以及两个原子状态迁移——{@link #claim}（PENDING→RETRYING，并发防双跑）与
 * {@link #reclaimStale}（RETRYING 过期回收回 PENDING，防崩溃残留卡死）。</p>
 */
public interface ActorDecisionRetryRepository extends JpaRepository<ActorDecisionRetry, Long> {

    /**
     * 到点待重试的 PENDING 作业（退避到期、最早到期优先）。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态（PENDING）
     *
     * @param dueAt 当前墙钟时刻（next_retry_at ≤ 该时刻 = 到点）
     *
     * @return 到点作业（按 next_retry_at 升序）
     */
    List<ActorDecisionRetry> findByProjectIdAndStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            Long projectId, String status, LocalDateTime dueAt);

    /**
     * 去重查询：某角色某原定时刻是否已有作业行。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @param gameSecond 原定决策时刻（游戏秒）
     *
     * @return 作业行（可能为已成功/已放弃的历史行）
     */
    Optional<ActorDecisionRetry> findByProjectIdAndCharacterIdAndGameSecond(
            Long projectId, Long characterId, Long gameSecond);

    /**
     * 某项目某状态作业数（入队容量闸门与统计用）。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态
     *
     * @return 行数
     */
    long countByProjectIdAndStatus(Long projectId, String status);

    /**
     * 原子认领一个到点作业（PENDING→RETRYING）。
     * <p>并发防双跑的关键：只认领「仍是 PENDING」的行——引擎扫描与手动推进并发时，
     * 只有一个线程能认领成功（返回 1），其余（返回 0）跳过，杜绝同一决策点被重跑两次
     * （双重行动执行）。</p>
     *
     * @param id 作业 ID
     *
     * @param now 认领时刻（写 updated_at，作过期回收依据）
     *
     * @return 受影响行数（1=认领成功；0=已被认领/已终态）
     */
    @Modifying
    @Query("update ActorDecisionRetry r set r.status = 'RETRYING', r.updatedAt = :now "
            + "where r.id = :id and r.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 回收崩溃残留的 RETRYING 作业（回 PENDING 并立即可重试）。
     * <p>认领方在执行中途崩溃（进程退出）时作业会永久停在 RETRYING——引擎扫描按
     * {@code updatedAt} 过期（默认 10 分钟）回收，让作业重新回到退避队列。</p>
     *
     * @param projectId 项目 ID
     *
     * @param staleBefore 过期判据（updated_at &lt; 该时刻 = 认领方疑似已死）
     *
     * @return 受影响行数
     */
    @Modifying
    @Query("update ActorDecisionRetry r set r.status = 'PENDING', r.nextRetryAt = :now, "
            + "r.updatedAt = :now where r.projectId = :projectId and r.status = 'RETRYING' "
            + "and r.updatedAt < :staleBefore")
    int reclaimStale(@Param("projectId") Long projectId, @Param("now") LocalDateTime now,
                     @Param("staleBefore") LocalDateTime staleBefore);
}
