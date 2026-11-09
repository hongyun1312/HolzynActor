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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 会话时间成本。
 * <p>世界处于暂停/停止态时，对话轮次按 {@code world-loop.dialog.time-cost-minutes-per-round}
 * （默认 2 游戏分钟）拨动世界钟；世界正在运行时<b>不额外拨钟</b>——真实流逝已经在推进世界，
 * 再叠加会跳过 NPC 的 tick 模拟（时钟是「已消费位置」，不能凭空跨过未模拟的时段）。</p>
 * <p>新对谈单聊在冻结态进行，轮次时间在关闭会话时一次性补推（冻结快照期间世界不应跳变）。</p>
 */
@Slf4j
@Service
public class ConversationTimeCostService {

    @Autowired(required = false)
    private WorldClockService worldClockService;

    @Autowired(required = false)
    private WorldLoopProperties properties;

    /**
     * 单轮时间成本（旧对话/未冻结会话）。
     *
     * @param projectId 项目 ID
     */
    public void onPlayerRound(Long projectId) {
        int minutes = properties == null ? 2 : properties.getDialog().getTimeCostMinutesPerRound();
        advance(projectId, minutes, "对话轮次");
    }

    /**
     * 会话关闭补推（冻结态对谈）。
     *
     * @param projectId 项目 ID
     * @param rounds    本轮会话的对话轮数
     * @param channel   链路（日志用）
     */
    public void onSessionClosed(Long projectId, int rounds, String channel) {
        if (rounds <= 0) {
            return;
        }
        int perRound = properties == null ? 2 : properties.getDialog().getTimeCostMinutesPerRound();
        advance(projectId, (long) rounds * perRound, channel + "会话关闭");
    }

    /**
     * 拨动世界钟（仅暂停/停止态；运行态跳过）。
     *
     * @param projectId 项目 ID
     * @param minutes   游戏分钟
     * @param reason    原因（日志）
     */
    void advance(Long projectId, long minutes, String reason) {
        if (projectId == null || minutes <= 0 || worldClockService == null) {
            return;
        }
        try {
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            boolean paused = Integer.valueOf(1).equals(clock.getPaused());
            boolean stopped = !Integer.valueOf(1).equals(clock.getWorldRunning());
            if (!paused && !stopped) {
                log.debug("[会话时间] 世界运行中，时间由真实流逝推进，跳过额外拨钟: project={}", projectId);
                return;
            }
            worldClockService.advanceConversationTime(projectId, minutes * 60L, reason);
        } catch (Exception e) {
            log.warn("[会话时间] 拨动失败（fail-open，不影响对话）: project={} : {}", projectId, e.getMessage());
        }
    }
}
