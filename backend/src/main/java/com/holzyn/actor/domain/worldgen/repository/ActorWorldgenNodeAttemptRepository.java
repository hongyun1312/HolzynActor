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

package com.holzyn.actor.domain.worldgen.repository;

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 世界观生成节点尝试仓库（actor_worldgen_node_attempt）。
 * <p>查询约定：同一节点多次执行（重试/跳过）追加多行，按 id 升序即执行顺序；
 * 「节点当前状态」= 该节点最新一行（id 最大）。</p>
 */
public interface ActorWorldgenNodeAttemptRepository extends JpaRepository<ActorWorldgenNodeAttempt, Long> {

    /**
     * 查询任务下某节点的全部尝试（按 id 升序=执行顺序，首行 retry_count=0）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeId 节点 ID
     *
     * @return 尝试列表（空=该节点从未执行）
     */
    List<ActorWorldgenNodeAttempt> findByTaskIdAndNodeIdOrderByIdAsc(Long taskId, String nodeId);

    /**
     * 查询任务下全部节点的全部尝试（按 id 升序）。
     *
     * @param taskId 任务 ID
     *
     * @return 尝试列表
     */
    List<ActorWorldgenNodeAttempt> findByTaskIdOrderByIdAsc(Long taskId);

    /**
     * 查询任务下某一执行序号开始的全部尝试（重试/跳过续跑断点后定位用）。
     *
     * @param taskId 任务 ID
     *
     * @param nodeOrder 起始执行序号（含）
     *
     * @return 尝试列表（按 id 升序）
     */
    List<ActorWorldgenNodeAttempt> findByTaskIdAndNodeOrderGreaterThanEqualOrderByIdAsc(Long taskId, Integer nodeOrder);
}
