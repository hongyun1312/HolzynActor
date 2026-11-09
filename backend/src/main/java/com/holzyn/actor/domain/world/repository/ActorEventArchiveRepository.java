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

import com.holzyn.actor.domain.world.entity.ActorEventArchive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 事件冷存储仓库。
 *
 * <p>只由事件总线的滚动保护写（归档后删除热表行），查询仅用于取证/调试。</p>
 */
public interface ActorEventArchiveRepository extends JpaRepository<ActorEventArchive, Long> {

    /**
     * 按项目 + 游戏日取归档事件（按原事件主键升序，即发生顺序）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay   游戏日
     *
     * @return 归档事件
     */
    List<ActorEventArchive> findByProjectIdAndGameDayOrderByEventIdAsc(Long projectId, Long gameDay);

    /**
     * 按项目统计归档条数（运维自检：`热表 + 冷表` 才是完整历史）。
     *
     * @param projectId 项目 ID
     *
     * @return 归档条数
     */
    long countByProjectId(Long projectId);

    /**
     * 按原事件主键查归档行（核对某条被淘汰事件是否已完整搬运）。
     *
     * @param eventId 原事件主键
     *
     * @return 归档行
     */
    List<ActorEventArchive> findByEventId(Long eventId);
}
