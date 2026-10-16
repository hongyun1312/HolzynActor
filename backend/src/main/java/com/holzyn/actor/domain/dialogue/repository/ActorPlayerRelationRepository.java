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

package com.holzyn.actor.domain.dialogue.repository;

import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 玩家-角色关系仓库。
 */
public interface ActorPlayerRelationRepository extends JpaRepository<ActorPlayerRelation, Long> {

    /** 项目 + 角色的关系行（渲染「与玩家的关系」段用）。 */
    Optional<ActorPlayerRelation> findByProjectIdAndCharacterRef(Long projectId, String characterRef);

    /** 项目全部关系行（列表/调试用）。 */
    java.util.List<ActorPlayerRelation> findByProjectId(Long projectId);
}
