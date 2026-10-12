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

package com.holzyn.actor.domain.agent.runtime.repository;

import com.holzyn.actor.domain.agent.runtime.entity.ActorResourceOccupancy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 资源占用登记仓库。
 * <p>职责：占用登记 CRUD——按项目+资源键查占用（冲突判定）、按项目+占用者查（换目标自动释放）、
 * 按项目查全量（调试/过期清理）。</p>
 */
public interface ActorResourceOccupancyRepository extends JpaRepository<ActorResourceOccupancy, Long> {

    /** 按项目 + 资源键 + 状态查（冲突判定：该资源当前占用者）。 */
    List<ActorResourceOccupancy> findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(
            Long projectId, String resourceKey, String status);

    /** 按项目 + 占用者 + 状态查（换目标自动释放自身旧占用）。 */
    List<ActorResourceOccupancy> findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
            Long projectId, String npcRef, String status);

    /** 按项目查全量（调试展示）。 */
    List<ActorResourceOccupancy> findByProjectIdOrderByIdDesc(Long projectId);
}
