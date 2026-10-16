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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorWorldEngineTickStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 世界引擎 tick 预算统计仓库（actor_world_engine_tick_stat，P0-03 度量基座）。
 * <p>职责：持久化每轮推进的预算消耗（修 BudgetGuard 内存态 endTick 即丢），供预算
 * 与决策来源统计面板查询。</p>
 */
public interface ActorWorldEngineTickStatRepository extends JpaRepository<ActorWorldEngineTickStat, Long> {

    /**
     * 查询某项目最近 N 轮 tick 统计（按游戏时刻倒序=最新在前）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 条数上限
     *
     * @return 最近统计（倒序）
     */
    List<ActorWorldEngineTickStat> findTopByProjectIdOrderByGameSecondDesc(Long projectId, int limit);
}
