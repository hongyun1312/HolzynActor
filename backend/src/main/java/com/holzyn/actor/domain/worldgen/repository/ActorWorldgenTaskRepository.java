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

package com.holzyn.actor.domain.worldgen.repository;

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 世界观生成任务仓库（actor_worldgen_task）。
 */
public interface ActorWorldgenTaskRepository extends JpaRepository<ActorWorldgenTask, Long> {

    /**
     * 按状态查询任务。
     *
     * @param status 任务状态（running/succeeded/failed/canceled，见 WorldGenStatus）
     *
     * @return 匹配的任务列表（按 id 升序）
     */
    List<ActorWorldgenTask> findByStatusOrderByIdAsc(String status);
}
