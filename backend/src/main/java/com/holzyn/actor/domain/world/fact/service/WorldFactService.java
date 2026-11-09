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

package com.holzyn.actor.domain.world.fact.service;

import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;

/**
 * BRAIN 侧事实写入口（§4.5）：fact + log 同事务，零 AI。
 *
 * <p>单一写入口纪律：本服务是 {@code actor_world_fact}/{@code actor_world_fact_log}
 * 在 BRAIN 模式下的唯一写者；ENGINE 模式下 {@code authority=engine} 的写权归
 * {@code EngineStateMirrorService}（E1），本服务被 {@code AuthorityWriteGuard} 拒绝。</p>
 */
public interface WorldFactService {

    /**
     * 新建或更新一条事实（同 factKey 覆盖；版本 +1）。
     *
     * @param cmd 写入命令
     *
     * @return 变更结果
     */
    FactChange upsert(FactCommand cmd);

    /**
     * 让事实失效（status=expired，保留因果）。
     *
     * @param cmd    写入命令（至少 projectId/factKey/sourceEventId/gameSecond）
     *
     * @param reason 失效原因
     *
     * @return 变更结果
     */
    FactChange expire(FactCommand cmd, String reason);

    /**
     * 撤销事实（status=void；用于 AI 幻觉写入的纠正，保留日志）。
     *
     * @param cmd    写入命令
     *
     * @param reason 撤销原因
     *
     * @return 变更结果
     */
    FactChange voidFact(FactCommand cmd, String reason);

    /**
     * 扫描并过期到期事实（小时钩子会调用；只提供能力）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param gameSecond 当前游戏秒
     */
    void sweepExpired(Long projectId, long gameSecond);
}
