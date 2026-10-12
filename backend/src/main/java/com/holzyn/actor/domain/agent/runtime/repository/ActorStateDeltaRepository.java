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

package com.holzyn.actor.domain.agent.runtime.repository;

import com.holzyn.actor.domain.agent.runtime.entity.ActorStateDelta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 决策 state delta 仓库。
 * <p>职责：delta CRUD——按决策 ID 幂等定位（uk）、按项目/角色倒序查（恢复校验/调试回看）。</p>
 */
public interface ActorStateDeltaRepository extends JpaRepository<ActorStateDelta, Long> {

    /** 幂等键定位（decision_id=actor_plan.id，uk）。 */
    Optional<ActorStateDelta> findByDecisionId(Long decisionId);

    /** 按项目 + 角色倒序查（最新决策在前）。 */
    List<ActorStateDelta> findByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 按项目倒序查（调试全量）。 */
    List<ActorStateDelta> findByProjectIdOrderByIdDesc(Long projectId);
}
