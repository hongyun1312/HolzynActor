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

package com.holzyn.actor.domain.usage.repository;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量日志仓库（actor_usage_log）。
 * <p>职责：提供按用户查询用量记录的能力，
 * 查询一律按 userId 归属隔离，防止跨用户查看用量。</p>
 */
@Repository
public interface ActorUsageLogRepository extends JpaRepository<ActorUsageLog, Long> {

    /**
     * 按归属用户查询全部用量记录（最新在前）。
     *
     * @param userId 归属用户 ID
     *
     * @return 用量记录列表
     */
    List<ActorUsageLog> findByUserIdOrderByIdDesc(Long userId);

    /**
     * 按归属用户 + 创建时间范围查询用量记录（时间倒序）。
     *
     * @param userId 归属用户 ID
     *
     * @param start 起始时间（含）
     *
     * @param end 结束时间（含）
     *
     * @return 用量记录列表
     */
    List<ActorUsageLog> findByUserIdAndCreatedAtBetweenOrderByIdDesc(Long userId, LocalDateTime start, LocalDateTime end);
}