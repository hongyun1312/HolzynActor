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

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorScene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 场景仓库（actor_scene）。
 */
@Repository
public interface ActorSceneRepository extends JpaRepository<ActorScene, Long> {

    /**
     * 按项目查询场景（启用优先、ID 升序）。
     *
     * @param projectId 项目 ID
     *
     * @return 场景列表
     */
    List<ActorScene> findByProjectIdOrderByEnabledDescIdAsc(Long projectId);
}
