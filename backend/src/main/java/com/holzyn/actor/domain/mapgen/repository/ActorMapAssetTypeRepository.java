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

import com.holzyn.actor.domain.mapgen.entity.ActorMapAssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 建筑资产类型仓储。
 */
public interface ActorMapAssetTypeRepository extends JpaRepository<ActorMapAssetType, Long> {

    /**
     * 地图下的全部资产类型。
     *
     * @param mapId 地图 ID
     * @return 资产类型列表
     */
    List<ActorMapAssetType> findByMapIdOrderBySortOrderAsc(Long mapId);

    /**
     * 地图下的可用（未被禁忌拦截）资产类型。
     *
     * @param mapId     地图 ID
     * @param forbidden 0=可用
     * @return 资产类型列表
     */
    List<ActorMapAssetType> findByMapIdAndForbiddenOrderBySortOrderAsc(Long mapId, Integer forbidden);

    /**
     * 删除地图下全部资产类型。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);
}
