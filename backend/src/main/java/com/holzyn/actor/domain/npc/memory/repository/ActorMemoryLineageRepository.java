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

package com.holzyn.actor.domain.npc.memory.repository;

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryLineage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 跨层巩固谱系仓库。
 * <p>职责：谱系行 CRUD——按项目/源或目标查询（「记忆谱系」反向链展开用）。</p>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§4.2。</p>
 */
public interface ActorMemoryLineageRepository extends JpaRepository<ActorMemoryLineage, Long> {

    /**
     * 按项目 + 目标记忆 id 查（反向链：目标 → 直接来源）。
     */
    List<ActorMemoryLineage> findByProjectIdAndTargetMemoryIdOrderByIdAsc(
            Long projectId, Long targetMemoryId);

    /**
     * 按项目 + 源记忆 id 查（正向链：来源 → 去向）。
     */
    List<ActorMemoryLineage> findByProjectIdAndSourceMemoryIdOrderByIdAsc(
            Long projectId, Long sourceMemoryId);

    /**
     * 按项目查全部（调试列表用）。
     */
    List<ActorMemoryLineage> findByProjectIdOrderByIdDesc(Long projectId);
}
