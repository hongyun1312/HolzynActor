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

import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 角色日程仓储（actor_npc_routine）。
 */
public interface ActorNpcRoutineRepository extends JpaRepository<ActorNpcRoutine, Long> {

    /**
     * 某项目某张图的全部日程（按角色、时间排序）。
     *
     * @param projectId 项目 ID
     * @param mapId     地图 ID
     * @return 日程段
     */
    List<ActorNpcRoutine> findByProjectIdAndMapIdOrderByNpcRefAscSeqAsc(Long projectId, Long mapId);

    /**
     * 清掉某张图的日程（整图重生成时先删后写，快照语义）。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);
}
