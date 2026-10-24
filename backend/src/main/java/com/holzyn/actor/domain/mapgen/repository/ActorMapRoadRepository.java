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

import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 道路仓储。
 */
public interface ActorMapRoadRepository extends JpaRepository<ActorMapRoad, Long> {

    /**
     * 地图下全部道路。
     *
     * @param mapId 地图 ID
     * @return 道路列表
     */
    List<ActorMapRoad> findByMapIdOrderBySortOrderAsc(Long mapId);

    /**
     * 删除地图下全部道路。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);
}
