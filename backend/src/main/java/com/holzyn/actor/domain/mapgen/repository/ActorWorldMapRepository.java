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

package com.holzyn.actor.domain.mapgen.repository;

import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 世界地图仓储。
 */
public interface ActorWorldMapRepository extends JpaRepository<ActorWorldMap, Long> {

    /**
     * 项目下最新的地图。
     *
     * @param projectId 项目 ID
     * @return 地图（可能为空）
     */
    Optional<ActorWorldMap> findFirstByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 项目下可用的地图（按新到旧）。
     *
     * @param projectId 项目 ID
     * @param status    状态
     * @return 地图列表
     */
    List<ActorWorldMap> findByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);
}
