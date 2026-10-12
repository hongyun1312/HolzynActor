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

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * L1 模糊状态仓库。
 * <p>职责：模糊状态行 CRUD——按项目/角色/状态查询（模糊扫描/睡眠清洗/调试列表）、
 * 按 memory_working_id 1:1 定位（蒸馏回写/模糊触发）。</p>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§3.1.2。</p>
 */
public interface ActorMemoryFuzzStateRepository extends JpaRepository<ActorMemoryFuzzState, Long> {

    /**
     * 按项目 + 角色 + 状态查（调试列表/睡眠清洗按角色处理用）。
     */
    List<ActorMemoryFuzzState> findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(
            Long projectId, String characterRef, String status);

    /**
     * 按项目 + 状态查（全项目模糊扫描用；按下次触发时刻升序=到点优先）。
     */
    List<ActorMemoryFuzzState> findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(
            Long projectId, String status);

    /**
     * 按项目查全部（调试/清理用）。
     */
    List<ActorMemoryFuzzState> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 按 1:1 关联定位（模糊触发/蒸馏回写用）。
     */
    java.util.Optional<ActorMemoryFuzzState> findByMemoryWorkingId(Long memoryWorkingId);

    /**
     * 批量按 1:1 关联定位（睡眠清洗批量处理用）。
     */
    List<ActorMemoryFuzzState> findByMemoryWorkingIdIn(java.util.Collection<Long> memoryWorkingIds);
}
