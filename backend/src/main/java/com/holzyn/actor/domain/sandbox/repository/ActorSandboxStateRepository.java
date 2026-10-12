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

package com.holzyn.actor.domain.sandbox.repository;

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 角色沙盘状态仓库（世界沙盘 S1，actor_sandbox_state）——位置权威源持久层。
 * <p>职责：按项目/NPC 引用查询沙盘状态行；移动/动作/想法更新落库。</p>
 */
public interface ActorSandboxStateRepository extends JpaRepository<ActorSandboxState, Long> {

    /** 按项目查询全部状态行 */
    List<ActorSandboxState> findByProjectIdOrderByIdAsc(Long projectId);

    /** 按项目 + NPC 引用查询（特殊/普通人群唯一索引语义） */
    Optional<ActorSandboxState> findByProjectIdAndNpcRef(Long projectId, String npcRef);

    /** 按项目 + 类型查询（special / crowd） */
    List<ActorSandboxState> findByProjectIdAndNpcTypeOrderByIdAsc(Long projectId, String npcType);
}
