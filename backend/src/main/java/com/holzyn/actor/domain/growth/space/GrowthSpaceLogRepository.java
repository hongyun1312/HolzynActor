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

package com.holzyn.actor.domain.growth.space;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 空间历史日志仓库。
 */
public interface GrowthSpaceLogRepository extends JpaRepository<GrowthSpaceLog, Long> {

    /** 某 NPC 的空间生成历史（新→旧，调试页/回放）。 */
    List<GrowthSpaceLog> findByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 某项目全部空间历史（统计口径）。 */
    List<GrowthSpaceLog> findByProjectIdOrderByIdDesc(Long projectId);
}
