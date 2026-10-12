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

package com.holzyn.actor.domain.npc.relation.repository;

import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 角色社会关系仓库（actor_character_relation）。
 * <p>职责：提供按项目查询社会关系列表的能力。</p>
 */
public interface ActorCharacterRelationRepository extends JpaRepository<ActorCharacterRelation, Long> {

    /**
     * 查询某项目全部社会关系。
     *
     * @param projectId 项目 ID
     *
     * @return 关系列表
     */
    List<ActorCharacterRelation> findByProjectId(Long projectId);
}