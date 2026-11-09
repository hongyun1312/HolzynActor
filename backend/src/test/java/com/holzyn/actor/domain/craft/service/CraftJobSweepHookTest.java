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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 制作结算钩子单测（{@code FactDecayHook} 的接线）。
 *
 * <p>为什么单独测：到点出活接的是<b>世界时钟</b>（每游戏小时边界），不是现实定时器。
 * 这条接线错了，表现是「活儿永远做不完」或「世界暂停时还在做」——两种都不真实，
 * 而且单测 CraftService 本身看不出来。</p>
 */
class CraftJobSweepHookTest {

    private static final Long PROJECT = 1L;

    private static final long GS = 3600L;

    @Test
    @DisplayName("每游戏小时把当前世界交给 finishDue（到点才出活）")
    void onHourDelegatesToService() {
        CraftService craft = mock(CraftService.class);
        when(craft.enabled()).thenReturn(true);
        when(craft.finishDue(PROJECT, GS)).thenReturn(2);
        CraftJobSweepHook hook = new CraftJobSweepHook();
        ReflectionTestUtils.setField(hook, "craftService", craft);

        hook.onHour(PROJECT, GS);

        verify(craft).finishDue(PROJECT, GS);
    }

    @Test
    @DisplayName("制作未启用 → 一次都不结算（关开关=行为回基线）")
    void onHourSkipsWhenDisabled() {
        CraftService craft = mock(CraftService.class);
        when(craft.enabled()).thenReturn(false);
        CraftJobSweepHook hook = new CraftJobSweepHook();
        ReflectionTestUtils.setField(hook, "craftService", craft);

        hook.onHour(PROJECT, GS);

        verify(craft, never()).finishDue(anyLong(), anyLong());
    }

    @Test
    @DisplayName("没有 CraftService（物品域未装配）→ 静默跳过，不抛异常")
    void onHourToleratesMissingService() {
        CraftJobSweepHook hook = new CraftJobSweepHook();

        hook.onHour(PROJECT, GS);
        hook.onHour(null, GS);
    }

    @Test
    @DisplayName("结算抛异常 → 钩子自己吞掉（世界引擎不能被一件坏活拖停）")
    void onHourSwallowsFailures() {
        CraftService craft = mock(CraftService.class);
        when(craft.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("库坏了")).when(craft).finishDue(PROJECT, GS);
        CraftJobSweepHook hook = new CraftJobSweepHook();
        ReflectionTestUtils.setField(hook, "craftService", craft);

        hook.onHour(PROJECT, GS);

        verify(craft).finishDue(PROJECT, GS);
    }
}
