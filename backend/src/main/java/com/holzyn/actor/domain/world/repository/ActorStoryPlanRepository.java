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

import com.holzyn.actor.domain.world.entity.ActorStoryPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 世界故事规划档案仓库。
 * <p>查询口径：按项目取版本链（升/降序）；幂等判重按「项目 + 来源任务」；
 * 氛围注入按「项目最新版本」取（{@link #findTopByProjectIdOrderByVersionDesc}）。</p>
 */
public interface ActorStoryPlanRepository extends JpaRepository<ActorStoryPlan, Long> {

    /** 项目全部故事规划（版本升序=历史链）。 */
    List<ActorStoryPlan> findByProjectIdOrderByVersionAsc(Long projectId);

    /** 项目全部故事规划（版本降序=最新在前）。 */
    List<ActorStoryPlan> findByProjectIdOrderByVersionDesc(Long projectId);

    /** 项目最新版本故事规划（氛围注入/展示默认用）。 */
    Optional<ActorStoryPlan> findTopByProjectIdOrderByVersionDesc(Long projectId);

    /** 项目 + 来源任务的故事规划（幂等判重：同任务重跑不重复建行）。 */
    Optional<ActorStoryPlan> findByProjectIdAndTaskId(Long projectId, Long taskId);
}
