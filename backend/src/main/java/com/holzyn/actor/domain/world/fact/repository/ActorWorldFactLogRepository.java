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

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 事实变更日志仓库（只追加；无更新入口）。
 */
@Repository
public interface ActorWorldFactLogRepository extends JpaRepository<ActorWorldFactLog, Long> {

    /**
     * 幂等判断：同项目内该键是否已写过日志。
     *
     * @param projectId      项目/世界 ID
     *
     * @param idempotencyKey 幂等键
     *
     * @return true=已写过
     */
    boolean existsByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    /**
     * 按幂等键取日志（重放时返回已有结果）。
     *
     * @param projectId      项目/世界 ID
     *
     * @param idempotencyKey 幂等键
     *
     * @return 日志（无则空）
     */
    Optional<ActorWorldFactLog> findTopByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    /**
     * 某事实的变更历史（版本倒序）。
     *
     * @param factId 事实行 ID
     *
     * @return 日志列表
     */
    List<ActorWorldFactLog> findByFactIdOrderByVersionDescIdDesc(Long factId);
}
