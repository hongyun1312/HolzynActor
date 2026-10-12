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

import com.holzyn.actor.domain.npc.state.entity.ActorStateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * NPC 状态历史快照仓库 ——历史曲线数据源持久层。
 * <p>职责：按项目 + NPC 引用 + 时间范围查询快照（升序）；按项目 + NPC 引用删除（保留上限滚动清理）。</p>
 */
public interface ActorStateHistoryRepository extends JpaRepository<ActorStateHistory, Long> {

    /** 按项目 + NPC 引用查询全部快照（时间升序——曲线 x 轴自然升序）。 */
    List<ActorStateHistory> findByProjectIdAndNpcRefOrderByGameSecondAsc(Long projectId, String npcRef);

    /** 按项目查询全部快照（清理/迁移用）。 */
    List<ActorStateHistory> findByProjectIdOrderByGameSecondAsc(Long projectId);

    /** 删除某 NPC 超过保留条数的旧快照（保留上限滚动清理：按时间升序取最旧，删除） */
    List<ActorStateHistory> findTop200ByProjectIdAndNpcRefOrderByGameSecondAsc(Long projectId, String npcRef);

    /** 是否已存在同一 (项目, NPC 引用, 游戏时刻) 快照（幂等采样去重）。 */
    boolean existsByProjectIdAndNpcRefAndGameSecond(Long projectId, String npcRef, Long gameSecond);
}
