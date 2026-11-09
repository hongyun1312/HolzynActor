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

package com.holzyn.actor.domain.world.authority.service.impl;

import com.holzyn.actor.domain.unity.service.UnitySessionRegistry;
import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import com.holzyn.actor.domain.world.authority.service.WorldAuthorityResolver;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 权威模式解析实现（§4.11.1）。
 *
 * <p>桌面端没有 Unity 连接，因此 {@code auto} 恒解析为 BRAIN——这是本轮「桌面端后端验证」
 * 的默认形态。接引擎后同一份代码在 {@code auto} 下自动切 ENGINE，无需改配置。</p>
 *
 * <p>{@code mode=engine} 但引擎未连接时<b>降级 BRAIN</b> 并 WARN（fail-safe，不是失败）：
 * 否则桌面端打开一个标记为 engine 的世界会没有权威。</p>
 */
@Slf4j
@Service
public class WorldAuthorityResolverImpl implements WorldAuthorityResolver {

    private final WorldLoopProperties properties;

    /** Unity 连接态唯一来源；字段注入保持「新增依赖不改构造器」纪律，也便于单测。 */
    @Autowired(required = false)
    private UnitySessionRegistry unitySessionRegistry;

    public WorldAuthorityResolverImpl(WorldLoopProperties properties) {
        this.properties = properties;
    }

    @Override
    public WorldAuthorityMode mode(Long projectId) {
        WorldAuthorityMode configured = properties == null || properties.getAuthority() == null
                ? WorldAuthorityMode.AUTO
                : properties.getAuthority().getMode();
        if (configured == null) {
            configured = WorldAuthorityMode.AUTO;
        }
        return switch (configured) {
            case BRAIN -> WorldAuthorityMode.BRAIN;
            case ENGINE -> engineConnected(projectId) ? WorldAuthorityMode.ENGINE : WorldAuthorityMode.BRAIN;
            case AUTO -> engineConnected(projectId) ? WorldAuthorityMode.ENGINE : WorldAuthorityMode.BRAIN;
        };
    }

    @Override
    public boolean isEngine(Long projectId) {
        return mode(projectId) == WorldAuthorityMode.ENGINE;
    }

    @Override
    public void onEngineConnected(Long projectId) {
        log.info("[权威] 引擎已连接：project={}，按需在下次打开世界时落定模式", projectId);
    }

    @Override
    public void onEngineDisconnected(Long projectId) {
        // 运行中不切模式（§4.11.1）：连接态只影响下一次 open 的落定与时钟来源。
        log.info("[权威] 引擎已断开：project={}，当前世界模式不热切换", projectId);
    }

    /**
     * 引擎是否连接（注册表缺失/未装配时视为未连接）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return true=已连接
     */
    private boolean engineConnected(Long projectId) {
        if (projectId == null || unitySessionRegistry == null) {
            return false;
        }
        try {
            return unitySessionRegistry.isConnected(projectId);
        } catch (RuntimeException e) {
            log.debug("[权威] 连接态读取失败（按未连接处理）：{}", e.getMessage());
            return false;
        }
    }
}
