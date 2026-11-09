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

package com.holzyn.actor.domain.craft.service;

import com.holzyn.actor.domain.world.fact.service.FactDecayHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 制作作业的到点结算钩子。
 *
 * <p><b>为什么挂 {@link FactDecayHook} 而不是新写一个 {@code @Scheduled}</b>：
 * 这个接口就是为物品域预留的「每游戏小时」扩展点（原注释：「物品域接入后只需注册
 * 一个 Bean，不必再改世界引擎」）。用它意味着<b>一行世界引擎代码都不用改</b>，也不会
 * 出现「现实时间定时器」和「游戏时钟」两套节奏——制作耗时是<b>游戏时间</b>的，
 * 世界暂停时活就该停着。</p>
 *
 * <p>精度：每小时边界结算一次，所以一件 45 分钟的活最晚在下一个整点出结果。
 * 想立刻结算的场景由 {@link CraftService#start} 里的「开工前先结算旧活」兜住
 * （同一角色连续做活时不会因为扫描间隔被误判成 BUSY）。</p>
 *
 * <p>异常隔离：{@code FactSweepService} 已对每个钩子单独 try/catch，本类不再吞异常，
 * 让真实故障在日志里可见（方法内部已按件隔离，单件坏活不会拖垮整轮）。</p>
 */
@Slf4j
@Component
public class CraftJobSweepHook implements FactDecayHook {

    @Autowired(required = false)
    private CraftService craftService;

    /**
     * 每游戏小时结算到点的制作作业。
     *
     * @param projectId  项目/世界 ID
     * @param gameSecond 当前游戏秒（整点）
     */
    @Override
    public void onHour(Long projectId, long gameSecond) {
        if (craftService == null || !craftService.enabled() || projectId == null) {
            return;
        }
        try {
            int settled = craftService.finishDue(projectId, gameSecond);
            if (settled > 0) {
                log.info("[制作sweep] 结算 {} 件：project={} gs={}", settled, projectId, gameSecond);
            }
        } catch (RuntimeException e) {
            log.warn("[制作sweep] 结算失败（隔离继续）：project={} : {}", projectId, e.getMessage());
        }
    }
}
