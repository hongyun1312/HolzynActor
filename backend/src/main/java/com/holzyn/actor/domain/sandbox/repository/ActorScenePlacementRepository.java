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

import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 沙盘资产摆放仓库（世界沙盘 S3，actor_scene_placement）。
 * <p>职责：按场景/项目查询摆放记录（稳定顺序：sort_order 升序 + id 升序）。</p>
 */
public interface ActorScenePlacementRepository extends JpaRepository<ActorScenePlacement, Long> {

    /** 按项目查询全部摆放（跨场景，overview 聚合用；场景内 sort_order 升序 + id 升序） */
    List<ActorScenePlacement> findByProjectIdOrderBySceneIdAscSortOrderAscIdAsc(Long projectId);

    /** 按场景查询摆放（sort_order 升序 + id 升序，稳定渲染顺序） */
    List<ActorScenePlacement> findBySceneIdOrderBySortOrderAscIdAsc(Long sceneId);

    /** 按场景删除全部摆放（S3 补全：预设应用时先清空再重建，保证幂等重跑不产生重复摆放） */
    void deleteBySceneId(Long sceneId);
}
