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

import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;

/**
 * 权威模式解析（§4.5/§4.11.1）。
 *
 * <p>桌面端（无引擎连接）：{@code auto} 恒解析为 BRAIN；这也是本轮桌面后端验证的默认形态。</p>
 */
public interface WorldAuthorityResolver {

    /**
     * 解析项目当前实际生效的权威模式。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 实际模式（AUTO 永远解析为 BRAIN/ENGINE，不会返回 AUTO）
     */
    WorldAuthorityMode mode(Long projectId);

    /**
     * 当前是否引擎权威。
     *
     * @param projectId 项目/世界 ID
     *
     * @return true=ENGINE
     */
    boolean isEngine(Long projectId);

    /**
     * 引擎连接回调（只记日志；持久化由 {@link WorldAuthorityService} 负责）。
     *
     * @param projectId 项目/世界 ID
     */
    void onEngineConnected(Long projectId);

    /**
     * 引擎断开回调（只记日志；运行中不切模式，见 §4.11.1）。
     *
     * @param projectId 项目/世界 ID
     */
    void onEngineDisconnected(Long projectId);
}
