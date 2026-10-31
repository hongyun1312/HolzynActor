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

package com.holzyn.actor.domain.mapnav.repository;

import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 角色坐标仓储（actor_npc_position）。
 */
public interface ActorNpcPositionRepository extends JpaRepository<ActorNpcPosition, Long> {

    /**
     * 某项目的全部角色坐标（一个项目一张图，全量读进内存更快）。
     *
     * @param projectId 项目 ID
     * @return 坐标行
     */
    List<ActorNpcPosition> findByProjectId(Long projectId);

    /**
     * 单角色坐标。
     *
     * @param projectId 项目 ID
     * @param npcRef    角色引用
     * @return 坐标行
     */
    Optional<ActorNpcPosition> findByProjectIdAndNpcRef(Long projectId, String npcRef);

    /**
     * 清掉某张图的坐标（整图重生成时先删后写）。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);
}
