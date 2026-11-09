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

import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 世界事实“待处理”判定（决策链让路信号）。
 *
 * <p><b>为什么需要</b>：发育阶段门控（priority=90）与空间快判（priority=80）都是
 * 低成本的 System 1 通道；当角色知道一条即将到期的契约/约定时，继续走“老人打盹/
 * 就近挑一个候选”会吞掉世界事实，造成「事实已入库但决策看不见」的断链。</p>
 *
 * <p><b>语义边界</b>：本服务只回答“是否存在一条该角色知道、且将在窗口内到期的
 * 事实”，不读取全项目事实、不判断行动内容；让路后由 AI 深思（action_gen）读取
 * 感知段里的【我所知道的事】，把具体行动交给模型。窗口默认 6 游戏小时，覆盖
 * “一个时辰后还银币”这类短约，又不会让所有远期契约每小时都触发 AI。</p>
 *
 * <p>未装配（单测）/无事实查询/时钟读取失败 → false，保持原决策链行为，零阻断。</p>
 */
@Slf4j
@Service
public class WorldFactDecisionSignal {

    /** 默认到期窗口：6 游戏小时（21600 秒）。 */
    public static final long DEFAULT_DUE_WINDOW_SECONDS = 6L * 3600L;

    @Autowired(required = false)
    @Lazy
    private WorldFactQuery worldFactQuery;

    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /**
     * 是否存在需要在本次决策中处理的到期事实。
     *
     * @param projectId  项目/世界 ID
     *
     * @param ref        角色 ref（npc:{id}）
     *
     * @param gameSecond 游戏秒（null=读引擎当前位置）
     *
     * @return true=存在窗口内到期且已知的事实
     */
    public boolean hasActionableFact(Long projectId, String ref, Long gameSecond) {
        return nearestDueFact(projectId, ref, gameSecond) != null;
    }

    /**
     * 取本次决策最该处理的到期事实（最近到期的一条；因果挂接用）。
     *
     * @param projectId  项目/世界 ID
     *
     * @param ref        角色 ref（npc:{id}）
     *
     * @param gameSecond 游戏秒（null=读引擎当前位置）
     *
     * @return 事实；无待处理事实/服务未装配/异常返回 null
     */
    public com.holzyn.actor.domain.world.fact.entity.ActorWorldFact nearestDueFact(
            Long projectId, String ref, Long gameSecond) {
        if (worldFactQuery == null || projectId == null || ref == null || ref.isBlank()) {
            return null;
        }
        long gs = resolveGameSecond(projectId, gameSecond);
        if (gs <= 0) {
            return null;
        }
        try {
            return worldFactQuery.nearestDueWithin(projectId, ref, gs, DEFAULT_DUE_WINDOW_SECONDS);
        } catch (Exception e) {
            log.warn("[事实决策信号] 查询失败（按无待处理事实降级）: project={} ref={} : {}",
                    projectId, ref, e.getMessage());
            return null;
        }
    }

    /**
     * 解析决策使用的游戏秒：显式传入优先，否则读引擎位置。
     */
    private long resolveGameSecond(Long projectId, Long gameSecond) {
        if (gameSecond != null && gameSecond > 0) {
            return gameSecond;
        }
        if (worldClockService == null) {
            return 0L;
        }
        try {
            Long current = worldClockService.currentEngineGameSecond(projectId);
            return current == null ? 0L : current;
        } catch (Exception e) {
            log.warn("[事实决策信号] 引擎时钟读取失败（按 0 降级）: project={} : {}",
                    projectId, e.getMessage());
            return 0L;
        }
    }
}
