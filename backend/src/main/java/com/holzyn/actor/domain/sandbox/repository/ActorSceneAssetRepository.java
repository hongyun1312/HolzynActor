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

package com.holzyn.actor.domain.sandbox.repository;

import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 沙盘 3D 资产登记仓库（世界沙盘 S1，actor_scene_asset）。
 * <p>职责：项目级资产登记/检索；内置包资产 project_id=0 全局共享，
 * 项目级资产按 project_id 查询（含 0=内置包）。</p>
 */
public interface ActorSceneAssetRepository extends JpaRepository<ActorSceneAsset, Long> {

    /** 按项目查询资产（含内置包 project_id=0 与项目级资产） */
    List<ActorSceneAsset> findByProjectIdInOrderByIdAsc(List<Long> projectIds);

    /** 按项目 + asset_id 查询（内置包 project_id=0 与项目级都查） */
    Optional<ActorSceneAsset> findFirstByProjectIdInAndAssetId(List<Long> projectIds, String assetId);

    /** 按项目 + 数据包 + asset_id 查询（资产包导入 upsert 用；内置包 project_id=0） */
    Optional<ActorSceneAsset> findFirstByProjectIdAndPackIdAndAssetId(Long projectId, String packId, String assetId);

    /** 按数据包 ID 查询全部资产（导入后统计用） */
    List<ActorSceneAsset> findByPackIdOrderByIdAsc(String packId);
}
