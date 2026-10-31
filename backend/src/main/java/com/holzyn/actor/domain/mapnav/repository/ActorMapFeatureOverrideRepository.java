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

import com.holzyn.actor.domain.mapnav.entity.ActorMapFeatureOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 布局覆盖仓储（actor_map_feature_override）。
 */
public interface ActorMapFeatureOverrideRepository extends JpaRepository<ActorMapFeatureOverride, Long> {

    /**
     * 某项目的全部手改。
     *
     * @param projectId 项目 ID
     * @return 覆盖行
     */
    List<ActorMapFeatureOverride> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 某张图的全部手改。
     *
     * @param mapId 地图 ID
     * @return 覆盖行
     */
    List<ActorMapFeatureOverride> findByMapIdOrderByIdAsc(Long mapId);

    /**
     * 单要素手改。
     *
     * @param mapId       地图 ID
     * @param featureCode 要素 code
     * @return 覆盖行
     */
    Optional<ActorMapFeatureOverride> findByMapIdAndFeatureCode(Long mapId, String featureCode);

    /**
     * 清掉某要素手改。
     *
     * @param mapId       地图 ID
     * @param featureCode 要素 code
     */
    void deleteByMapIdAndFeatureCode(Long mapId, String featureCode);

    /**
     * 清掉某张图全部手改。
     *
     * @param mapId 地图 ID
     */
    void deleteByMapId(Long mapId);

    /**
     * 清掉一个项目下全部手改（跨图）。
     *
     * <p>只在「重掷种子」时用：{@code feature_code} 是 {@code kind+序号} 形态，
     * 换一张图后同一个 code 可能落在另一栋房子上，按 code 重放会把用户的手改
     * 悄悄搬到别的建筑上。与其猜，不如在换图时明确丢弃。</p>
     *
     * @param projectId 项目 ID
     * @return 删除行数
     */
    long deleteByProjectId(Long projectId);
}
