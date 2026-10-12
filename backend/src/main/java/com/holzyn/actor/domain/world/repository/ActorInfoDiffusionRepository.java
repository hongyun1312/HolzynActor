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

import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 信息扩散状态仓库。
 */
public interface ActorInfoDiffusionRepository extends JpaRepository<ActorInfoDiffusion, Long> {

    /**
     * 按信息包查扩散状态（到达区域列表，packet_id+region 语义唯一）。
     *
     * @param packetId 信息包 ID
     *
     * @return 扩散状态列表
     */
    List<ActorInfoDiffusion> findByPacketId(Long packetId);

    /**
     * 按项目+游戏日查扩散状态（回放扩散进程）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @return 扩散状态列表
     */
    List<ActorInfoDiffusion> findByProjectIdAndGameDay(Long projectId, Long gameDay);

    /**
     * 按项目查全部扩散状态。
     *
     * @param projectId 项目 ID
     *
     * @return 扩散状态列表
     */
    List<ActorInfoDiffusion> findByProjectId(Long projectId);
}
