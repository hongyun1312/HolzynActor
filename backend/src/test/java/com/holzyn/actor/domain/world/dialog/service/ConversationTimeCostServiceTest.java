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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话时间成本测试。
 *
 * <p>关键语义：只在世界暂停/停止时拨钟；运行态由真实流逝推进，额外拨钟会跨过未模拟 tick。
 * 冻结态单聊在关闭时按轮数一次性补推。</p>
 */
class ConversationTimeCostServiceTest {

    private WorldClockService clockService;
    private WorldLoopProperties properties;
    private ConversationTimeCostService service;

    @BeforeEach
    void setUp() {
        clockService = mock(WorldClockService.class);
        properties = new WorldLoopProperties();
        service = new ConversationTimeCostService();
        ReflectionTestUtils.setField(service, "worldClockService", clockService);
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    @DisplayName("暂停态：单轮按 2 游戏分钟拨钟")
    void pausedRoundAdvancesTwoMinutes() {
        when(clockService.requireClock(7L)).thenReturn(clock(1, 0));

        service.onPlayerRound(7L);

        verify(clockService).advanceConversationTime(7L, 120L, "对话轮次");
    }

    @Test
    @DisplayName("运行态：真实流逝已在推进，跳过额外拨钟")
    void runningWorldSkipsAdvance() {
        when(clockService.requireClock(7L)).thenReturn(clock(0, 1));

        service.onPlayerRound(7L);

        verify(clockService, never()).advanceConversationTime(anyLong(), anyLong(), eq("对话轮次"));
    }

    @Test
    @DisplayName("冻结会话关闭：轮数×2 分钟一次性补推")
    void closePushesRoundsAtOnce() {
        when(clockService.requireClock(7L)).thenReturn(clock(1, 0));

        service.onSessionClosed(7L, 3, "dialogue");

        verify(clockService).advanceConversationTime(7L, 360L, "dialogue会话关闭");
    }

    @Test
    @DisplayName("0 轮/空项目：不拨钟")
    void emptyInputDoesNothing() {
        service.onSessionClosed(7L, 0, "dialogue");
        service.onPlayerRound(null);

        verify(clockService, never()).advanceConversationTime(anyLong(), anyLong(), any());
    }

    private ActorWorldClock clock(int paused, int running) {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setPaused(paused);
        clock.setWorldRunning(running);
        return clock;
    }
}
