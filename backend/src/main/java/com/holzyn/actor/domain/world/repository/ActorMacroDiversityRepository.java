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

import com.holzyn.actor.domain.world.entity.ActorMacroDiversity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 行为多样性注入记录仓储。
 * <p>查询口径：按项目（调试面板回放）、按角色+游戏日（GET /macro/diversity）、
 * 最近 N 条（去趋同对照分析）。</p>
 */
public interface ActorMacroDiversityRepository extends JpaRepository<ActorMacroDiversity, Long> {

    /** 某项目全部记录（按时间倒序；调试面板回放） */
    List<ActorMacroDiversity> findByProjectIdOrderByIdDesc(Long projectId);

    /** 某项目某角色记录（按时间倒序） */
    List<ActorMacroDiversity> findByProjectIdAndActorRefOrderByIdDesc(Long projectId, String actorRef);

    /** 某项目某游戏日记录（去趋同对照：同情境多决策多样性统计） */
    List<ActorMacroDiversity> findByProjectIdAndGameDayOrderByIdAsc(Long projectId, Long gameDay);

    /** 某项目某角色某游戏日记录（定向回放） */
    List<ActorMacroDiversity> findByProjectIdAndActorRefAndGameDayOrderByIdAsc(Long projectId, String actorRef, Long gameDay);
}
