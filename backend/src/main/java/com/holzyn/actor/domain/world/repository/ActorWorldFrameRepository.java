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

import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 每日世界帧仓库。
 */
public interface ActorWorldFrameRepository extends JpaRepository<ActorWorldFrame, Long> {

    /**
     * 按项目+游戏日查帧（uk 唯一，幂等/缓存 24h 语义的判定依据）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日序号
     *
     * @return 帧（可空）
     */
    Optional<ActorWorldFrame> findByProjectIdAndGameDay(Long projectId, Long gameDay);

    /**
     * 按项目+状态查帧（active 应唯一=当前生效帧）。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态（active/superseded）
     *
     * @return 帧列表
     */
    List<ActorWorldFrame> findByProjectIdAndStatus(Long projectId, String status);

    /**
     * 按项目查帧历史（game_day 降序，最新在前；调用方截断 limit）。
     *
     * @param projectId 项目 ID
     *
     * @return 帧列表（按 game_day 降序）
     */
    List<ActorWorldFrame> findByProjectIdOrderByGameDayDesc(Long projectId);

    /**
     * 收成天气源：按项目查「最接近且不晚于某游戏日」的帧（当日帧已生成则取当日，
     * 否则取最近的前一日帧——宏观 00:05 tick 时 AI 帧可能未生成，读最近可用帧天气；
     * 因果语义：收成受近期天气影响，取前一日天气成立）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日序号
     *
     * @return 帧（可空=无任何帧）
     */
    java.util.Optional<ActorWorldFrame> findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(
            Long projectId, Long gameDay);
}
