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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事实小时 sweep（世界引擎 ②-e2 调用）。
 *
 * <p>职责：到期事实置 expired（由 {@link WorldFactService#sweepExpired} 完成，并统一发
 * fact 过期事件）；随后调用可选的 {@link FactDecayHook}（当前无物品域，列表为空）。</p>
 *
 * <p>零 AI；每个钩子单独 try/catch，坏钩子不阻断契约到期。</p>
 */
@Slf4j
@Service
public class FactSweepService {

    private final WorldFactService worldFactService;

    /** 扩展钩子（为空；物品域实现后自动注入，无需改本类）。 */
    @Autowired(required = false)
    private List<FactDecayHook> decayHooks;

    public FactSweepService(WorldFactService worldFactService) {
        this.worldFactService = worldFactService;
    }

    /**
     * 每游戏小时边界执行一次。
     *
     * @param projectId  项目/世界 ID
     * @param gameSecond 当前游戏秒（整点）
     */
    public void hourly(Long projectId, long gameSecond) {
        if (projectId == null) {
            return;
        }
        worldFactService.sweepExpired(projectId, gameSecond);
        if (decayHooks == null || decayHooks.isEmpty()) {
            return;
        }
        for (FactDecayHook hook : decayHooks) {
            if (hook == null) {
                continue;
            }
            try {
                hook.onHour(projectId, gameSecond);
            } catch (Exception e) {
                log.warn("[事实sweep] 腐坏钩子失败（隔离继续）: project={} hook={} : {}",
                        projectId, hook.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
