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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 宏观事件仓储。
 * <p>查询口径：按项目倒序取最近（读接口/时间线）；区间 + 严重度/来源过滤（读接口）；
 * source_key 幂等查（防重复发布）。</p>
 */
public interface ActorWorldMacroEventRepository extends JpaRepository<ActorWorldMacroEvent, Long> {

    /** 幂等查（source_key 唯一；防同一宏观事件重复落库/重复发布） */
    Optional<ActorWorldMacroEvent> findByProjectIdAndSourceKey(Long projectId, String sourceKey);

    /** 最近事件（id 倒序=时间倒序；读接口） */
    List<ActorWorldMacroEvent> findTop20ByProjectIdOrderByIdDesc(Long projectId);

    /** 区间 + 严重度下限 + 来源过滤（读接口；来源 null 不过滤） */
    List<ActorWorldMacroEvent> findByProjectIdAndGameDayBetweenAndSeverityGreaterThanEqualOrderByGameDayAsc(
            Long projectId, Long fromDay, Long toDay, Integer severity);

    /** 区间查询（读接口，不限严重度） */
    List<ActorWorldMacroEvent> findByProjectIdAndGameDayBetweenOrderByGameDayAsc(Long projectId, Long fromDay, Long toDay);
}
