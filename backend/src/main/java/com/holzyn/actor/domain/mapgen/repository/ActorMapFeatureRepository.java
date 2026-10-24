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

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 地图要素仓储。
 */
public interface ActorMapFeatureRepository extends JpaRepository<ActorMapFeature, Long> {

    /**
     * 地图下全部要素（按渲染顺序）。
     *
     * @param mapId 地图 ID
     * @return 要素列表
     */
    List<ActorMapFeature> findByMapIdOrderBySortOrderAsc(Long mapId);

    /**
     * 地图下某一形态的要素。
     *
     * @param mapId 地图 ID
     * @param kind  形态
     * @return 要素列表
     */
    List<ActorMapFeature> findByMapIdAndKind(Long mapId, String kind);

    /**
     * 地图下带住的要素（可作为角色住所）。
     *
     * @param mapId     地图 ID
     * @param residency 1=住人
     * @return 要素列表
     */
    List<ActorMapFeature> findByMapIdAndResidency(Long mapId, Integer residency);

    /**
     * 删除地图下全部要素。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);
}
