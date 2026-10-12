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

import com.holzyn.actor.domain.world.entity.ActorMacroEmergenceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 二阶涌现事件仓储。
 * <p>查询口径：按项目（列表/详情）、按类型（冷却判定）、按游戏日区间（回放）；
 * 冷却判定=同项目同行为类最近事件（cooled_until_day ≥ 当前日 → 跳过）。</p>
 */
public interface ActorMacroEmergenceEventRepository extends JpaRepository<ActorMacroEmergenceEvent, Long> {

    /** 某项目全部涌现事件（按游戏日倒序；读接口） */
    List<ActorMacroEmergenceEvent> findByProjectIdOrderByIdDesc(Long projectId);

    /** 某项目某类型涌现事件（冷却判定/按类型过滤） */
    List<ActorMacroEmergenceEvent> findByProjectIdAndEmergenceTypeOrderByIdDesc(Long projectId, String emergenceType);

    /** 某项目某区间涌现事件（回放） */
    List<ActorMacroEmergenceEvent> findByProjectIdAndGameDayBetweenOrderByGameDayAsc(Long projectId, Long fromDay, Long toDay);

    /** 冷却判定：同项目同行为类最新事件 */
    Optional<ActorMacroEmergenceEvent> findTopByProjectIdAndBehaviorClassOrderByIdDesc(Long projectId, String behaviorClass);

    /** 详情读 */
    Optional<ActorMacroEmergenceEvent> findById(Long id);
}
