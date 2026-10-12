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

package com.holzyn.actor.domain.npc.profile.repository;

import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 脑力配置仓库，对应表 actor_agent_profile（阶段 C BrainProfile，domain/agent/brain）。
 * <p>查询语义：agent_type + agent_ref_id 构成唯一键（uk_agent_profile_ref）——
 * 特殊 NPC 用 ("special", actor_character.id)、普通 NPC 用 ("ordinary", actor_ordinary_npc.id)。</p>
 */
public interface ActorAgentProfileRepository extends JpaRepository<ActorAgentProfile, Long> {

    /**
     * 按智能体类型 + 引用 ID 查询脑力配置行（唯一键命中，最多一行）。
     *
     * @param agentType 智能体类型（special/ordinary）
     *
     * @param agentRefId 智能体引用 ID（特殊=角色 id / 普通=普通 NPC id）
     *
     * @return 脑力配置行（可空）
     */
    Optional<ActorAgentProfile> findByAgentTypeAndAgentRefId(String agentType, Long agentRefId);

    /**
     * 判断某智能体的脑力配置行是否已存在（幂等补行判定用）。
     *
     * @param agentType 智能体类型（special/ordinary）
     *
     * @param agentRefId 智能体引用 ID
     *
     * @return true 表示已存在
     */
    boolean existsByAgentTypeAndAgentRefId(String agentType, Long agentRefId);
}
