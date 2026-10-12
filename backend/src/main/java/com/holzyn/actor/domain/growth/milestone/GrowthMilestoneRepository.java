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

package com.holzyn.actor.domain.growth.milestone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 发育里程碑仓库。
 */
public interface GrowthMilestoneRepository extends JpaRepository<GrowthMilestone, Long> {

    /** 某 NPC 的里程碑历史（新→旧）。 */
    List<GrowthMilestone> findByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 某项目全部里程碑（新→旧，调试页总览）。 */
    List<GrowthMilestone> findByProjectIdOrderByIdDesc(Long projectId);

    /** 全局最新 200 条里程碑（调试页跨项目浏览）。 */
    List<GrowthMilestone> findTop200ByOrderByIdDesc();

    /** 某项目最新 200 条里程碑（调试页总览）。 */
    List<GrowthMilestone> findTop200ByProjectIdOrderByIdDesc(Long projectId);

    /** 某 NPC 是否存在某类型的里程碑（阶段叙事幂等：同一天同一阶段不重复叙事）。 */
    boolean existsByProjectIdAndNpcRefAndKindAndGameSecondGreaterThanEqual(
            Long projectId, String npcRef, String kind, long gameSecond);
}
