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

import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 行动决策仓库（actor_action_plan）。
 * <p>职责：提供按角色/项目角色集合/状态/计划时间的查询能力，
 * 支撑行动列表、项目级时间线聚合与 scheduled 定时轮询执行。</p>
 */
@Repository
public interface ActorActionPlanRepository extends JpaRepository<ActorActionPlan, Long> {

    /**
     * 按角色查询行动决策（新→旧）。
     *
     * @param characterId 角色 ID
     *
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdOrderByIdDesc(Long characterId);

    /**
     * 按角色 + 状态查询行动决策（新→旧）。
     *
     * @param characterId 角色 ID
     *
     * @param status 状态（planned/executing/done/cancelled）
     *
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdAndStatusOrderByIdDesc(Long characterId, String status);

    /**
     * 查询多个角色（项目内全部角色）的行动决策（新→旧），用于项目级时间线。
     *
     * @param characterIds 角色 ID 集合
     *
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdInOrderByIdDesc(Collection<Long> characterIds);

    /**
     * scheduled 定时轮询：查询状态为 planned 且计划时间已到期（<=now）的决策。
     *
     * @param status 状态（planned）
     *
     * @param plannedTime 当前时间
     *
     * @return 到期待执行的决策列表
     */
    List<ActorActionPlan> findByStatusAndPlannedTimeLessThanEqual(String status, LocalDateTime plannedTime);

    /**
     * 等待类计划到点轮询：查询状态为 planned 且
     * 计划游戏时刻已到期（<= gameSecond）的等待类计划——由引擎按推进位置到点触发重新决策；
     * planned_game_second 为 NULL 的行（非等待计划）不参与本查询。
     *
     * @param status 状态（planned）
     *
     * @param gameSecond 当前游戏时刻（秒，引擎推进位置）
     *
     * @return 到期待重新决策的等待计划列表
     */
    List<ActorActionPlan> findByStatusAndPlannedGameSecondLessThanEqual(String status, Long gameSecond);

    /**
     * 决策与协调：按角色 + 状态集合查询进行中计划（planned/executing）——
     * 冲突检测「同地点被其他 NPC 进行中计划占用」判定用（新→旧）。
     *
     * @param characterId 角色 ID
     *
     * @param statuses 状态集合（planned/executing）
     *
     * @return 该角色进行中计划列表（新→旧）
     */
    List<ActorActionPlan> findByCharacterIdAndStatusInOrderByIdDesc(Long characterId, Collection<String> statuses);
}