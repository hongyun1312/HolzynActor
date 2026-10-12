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

import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 信息包仓库。
 */
public interface ActorInfoPacketRepository extends JpaRepository<ActorInfoPacket, Long> {

    /**
     * 按世界帧查信息包列表（实体化后的包；紧急更新就地改写帧时按 frame_id 替换）。
     *
     * @param frameId 世界帧 ID
     *
     * @return 信息包列表
     */
    List<ActorInfoPacket> findByFrameId(Long frameId);

    /**
     * 按项目+帧查信息包列表。
     *
     * @param projectId 项目 ID
     *
     * @param frameId 世界帧 ID
     *
     * @return 信息包列表
     */
    List<ActorInfoPacket> findByProjectIdAndFrameId(Long projectId, Long frameId);

    /**
     * 按项目+状态查信息包。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态
     *
     * @return 信息包列表
     */
    List<ActorInfoPacket> findByProjectIdAndStatus(Long projectId, String status);

    /**
     * 按项目查全部信息包。
     *
     * @param projectId 项目 ID
     *
     * @return 信息包列表
     */
    List<ActorInfoPacket> findByProjectId(Long projectId);

    /**
     * 按项目统计宏观来源信息包条数（trace 回灌审计：info_id 带 macro: 前缀——
     * MacroFeedbackService 实体化的宏观事件包，独立于帧包）。
     *
     * @param projectId 项目 ID
     *
     * @param prefix info_id 前缀（"macro:"）
     *
     * @return 宏观信息包条数
     */
    long countByProjectIdAndInfoIdStartingWith(Long projectId, String prefix);
}
