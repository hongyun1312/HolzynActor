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

package com.holzyn.actor.domain.world.authority.service;

import com.holzyn.actor.domain.world.authority.entity.ActorWorldAuthorityState;
import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;

import java.time.LocalDateTime;

/**
 * 权威状态写入口（{@code actor_world_authority_state} 的唯一写者）。
 *
 * <p>只提供「落定模式记连接记交接」三件事；E1 的 hydrate/drift 编排复用同一入口。</p>
 */
public interface WorldAuthorityService {

    /**
     * 读取当前状态行（无则返回 null）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 状态行或 null
     */
    ActorWorldAuthorityState stateOf(Long projectId);

    /**
     * 落定当前模式（打开世界/交接时调用；upsert）。
     *
     * @param projectId   项目/世界 ID
     *
     * @param mode        实际生效模式
     *
     * @param gameSecond  当前游戏秒（可空）
     *
     * @return 落定后的状态行
     */
    ActorWorldAuthorityState settle(Long projectId, WorldAuthorityMode mode, Long gameSecond);

    /**
     * 记录引擎连接时间。
     *
     * @param projectId 项目/世界 ID
     *
     * @param at        连接时刻
     */
    void markEngineConnected(Long projectId, LocalDateTime at);

    /**
     * 记录一次交接（关闭前快照 / 打开时 hydrate 对账）。
     *
     * @param projectId     项目/世界 ID
     *
     * @param lastEngineSeq 引擎最后 seq（可空）
     *
     * @param driftJson     drift 明细 JSON（可空）
     */
    void recordHandover(Long projectId, Long lastEngineSeq, String driftJson);
}
