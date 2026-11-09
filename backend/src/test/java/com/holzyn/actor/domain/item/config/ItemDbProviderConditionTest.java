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

package com.holzyn.actor.domain.item.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 物品端口装配条件：只有「开启 + provider=db」才装 DB 实现。
 *
 * <p>为什么这条不能省：{@code provider=engine} 时若仍装 DB 写实现，就会出现
 * 「引擎权威 + 后端也写」的双写漂移。条件写错不会报错，只会静默双写。</p>
 */
class ItemDbProviderConditionTest {

    private final ItemDbProviderCondition condition = new ItemDbProviderCondition();

    @Test
    @DisplayName("enabled=true + provider=db → 装配")
    void enabledDbMatches() {
        assertTrue(condition.matches(context("true", "db"), null));
    }

    @Test
    @DisplayName("enabled=false → 不装配（默认关，行为回基线）")
    void disabledDoesNotMatch() {
        assertFalse(condition.matches(context("false", "db"), null));
    }

    @Test
    @DisplayName("provider=engine → 不装配（避免后端双写引擎权威的库存）")
    void engineDoesNotMatch() {
        assertFalse(condition.matches(context("true", "engine"), null));
    }

    @Test
    @DisplayName("provider 大小写/空白宽松：DB / ' db ' 都算 db")
    void providerIsCaseInsensitive() {
        assertTrue(condition.matches(context("true", "DB"), null));
        assertTrue(condition.matches(context("true", " db "), null));
    }

    private static ConditionContext context(String enabled, String provider) {
        Environment env = mock(Environment.class);
        when(env.getProperty("holzyn.actor.item.enabled", "false")).thenReturn(enabled);
        when(env.getProperty("holzyn.actor.item.provider", "db")).thenReturn(provider);
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        return ctx;
    }
}
