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

package com.holzyn.actor.domain.npc.state.repository;

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * NPC 内部状态仓库 ——决策侧状态权威持久层。
 * <p>职责：按项目/NPC 引用查询状态行（project_id+npc_ref 唯一）；衰减/恢复/紧急度更新落库。</p>
 */
public interface ActorNpcStateRepository extends JpaRepository<ActorNpcState, Long> {

    /** 按项目 + NPC 引用查询（特殊/普通人群唯一索引语义；含软删与不含两种口径） */
    Optional<ActorNpcState> findByProjectIdAndNpcRef(Long projectId, String npcRef);

    /** 按项目查询全部未删状态行（衰减/聚合遍历用） */
    List<ActorNpcState> findByProjectIdAndDeletedOrderByIdAsc(Long projectId, Integer deleted);

    /** 按项目 + 类型查询（special / crowd） */
    List<ActorNpcState> findByProjectIdAndNpcTypeAndDeletedOrderByIdAsc(Long projectId, String npcType, Integer deleted);
}
