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

import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 中观组织成员关系仓储。
 * <p>查询口径：按组查成员（指令路由）/ 按角色查所属组（感知注入、响应回收）/
 * uk(group_id+actor_ref) 幂等。</p>
 */
public interface ActorMesoMembershipRepository extends JpaRepository<ActorMesoMembership, Long> {

    /** 某组织全部成员（按入组顺序） */
    List<ActorMesoMembership> findByGroupIdOrderByIdAsc(Long groupId);

    /** 某组织活跃成员 */
    List<ActorMesoMembership> findByGroupIdAndStatusOrderByIdAsc(Long groupId, String status);

    /** 某角色全部所属组织关系（感知注入/响应回收路由用） */
    List<ActorMesoMembership> findByProjectIdAndActorRefOrderByIdAsc(Long projectId, String actorRef);

    /** 某角色活跃所属关系 */
    List<ActorMesoMembership> findByProjectIdAndActorRefAndStatus(Long projectId, String actorRef, String status);

    /** 幂等查（uk 约束） */
    Optional<ActorMesoMembership> findByGroupIdAndActorRef(Long groupId, String actorRef);

    /** 某组织成员数（势力派生 memberCount 用） */
    long countByGroupIdAndStatus(Long groupId, String status);
}
