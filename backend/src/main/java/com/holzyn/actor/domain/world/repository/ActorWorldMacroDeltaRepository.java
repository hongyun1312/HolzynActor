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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 宏观入账流水仓储。
 * <p>查询口径：按项目 + 游戏日聚合（每日汇总）；区间 + 目标子系统过滤（每旬势力汇总）；
 * 按 NPC 引用回看（读接口）；流水行只增不改（审计），不设消费标记——每日汇总按
 * 「前一游戏日」聚合（本日入账次日 00:05 经济 tick 生效，因果链成立）。</p>
 */
public interface ActorWorldMacroDeltaRepository extends JpaRepository<ActorWorldMacroDelta, Long> {

    /** 某项目某游戏日全部入账行（每日汇总源） */
    List<ActorWorldMacroDelta> findByProjectIdAndGameDay(Long projectId, Long gameDay);

    /** 区间 + 目标子系统过滤（每旬势力汇总等） */
    List<ActorWorldMacroDelta> findByProjectIdAndGameDayBetweenAndTargetMacro(
            Long projectId, Long fromDay, Long toDay, String targetMacro);

    /** 按 NPC 引用回看（读接口） */
    List<ActorWorldMacroDelta> findTop100ByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 区间回看（读接口） */
    List<ActorWorldMacroDelta> findByProjectIdAndGameDayBetweenOrderByGameDayAsc(Long projectId, Long fromDay, Long toDay);
}
