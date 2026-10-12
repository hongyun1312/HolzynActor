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

package com.holzyn.actor.domain.npc.goal.repository;

import com.holzyn.actor.domain.npc.goal.entity.ActorInterruptRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 被打断任务记录仓库。
 * <p>职责：提供按项目/角色/状态的查询能力，支撑 {@code InterruptRecoveryService}
 * 的打断记录创建（有当前活动且无 active 不覆盖）与恢复判断（读 active → 三态消费）。</p>
 */
@Repository
public interface ActorInterruptRecordRepository extends JpaRepository<ActorInterruptRecord, Long> {

    /**
     * 按角色查询某状态的最新记录（新→旧）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（special:{id}）
     *
     * @param status 状态（active/restored/adjusted/discarded）
     *
     * @return 记录列表（新→旧）
     */
    List<ActorInterruptRecord> findByProjectIdAndNpcRefAndStatusOrderByIdDesc(Long projectId, String npcRef, String status);

    /**
     * 按角色查询全部记录（新→旧，审计/调试用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return 记录列表（新→旧）
     */
    List<ActorInterruptRecord> findByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);
}
