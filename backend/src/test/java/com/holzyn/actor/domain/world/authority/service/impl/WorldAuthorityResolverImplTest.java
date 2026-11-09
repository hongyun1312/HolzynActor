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
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权威模式解析（§4.11.1）：桌面端 auto 恒 BRAIN，接引擎后 auto 自动 ENGINE。
 */
class WorldAuthorityResolverImplTest {

    private WorldLoopProperties properties;
    private UnitySessionRegistry registry;
    private WorldAuthorityResolverImpl resolver;

    @BeforeEach
    void setUp() {
        properties = new WorldLoopProperties();
        registry = mock(UnitySessionRegistry.class);
        resolver = new WorldAuthorityResolverImpl(properties);
        ReflectionTestUtils.setField(resolver, "unitySessionRegistry", registry);
    }

    @Test
    @DisplayName("AUTO：未连接 → BRAIN（桌面端形态）；已连接 → ENGINE")
    void autoFollowsConnection() {
        when(registry.isConnected(1L)).thenReturn(false);
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L));
        assertFalse(resolver.isEngine(1L));

        when(registry.isConnected(1L)).thenReturn(true);
        assertEquals(WorldAuthorityMode.ENGINE, resolver.mode(1L));
        assertTrue(resolver.isEngine(1L));
    }

    @Test
    @DisplayName("BRAIN 强制：即使引擎连接也不切 ENGINE")
    void brainModeIsForced() {
        properties.getAuthority().setMode(WorldAuthorityMode.BRAIN);
        when(registry.isConnected(1L)).thenReturn(true);
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L));
    }

    @Test
    @DisplayName("ENGINE 但未连接：降级 BRAIN（fail-safe，不留无权威世界）")
    void engineDegradesToBrainWhenDisconnected() {
        properties.getAuthority().setMode(WorldAuthorityMode.ENGINE);
        when(registry.isConnected(1L)).thenReturn(false);
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L), "未连接时必须降级，不能无权威");

        when(registry.isConnected(1L)).thenReturn(true);
        assertEquals(WorldAuthorityMode.ENGINE, resolver.mode(1L));
    }

    @Test
    @DisplayName("注册表未装配/读取异常/null 项目：一律按未连接 → BRAIN")
    void missingRegistryOrBrokenConnectionFallsBackToBrain() {
        WorldAuthorityResolverImpl noRegistry = new WorldAuthorityResolverImpl(properties);
        assertEquals(WorldAuthorityMode.BRAIN, noRegistry.mode(1L), "未装配注册表时按未连接");

        when(registry.isConnected(1L)).thenThrow(new IllegalStateException("session store down"));
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L), "连接态读取失败按未连接");

        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(null), "null 项目无权切换引擎");
    }

    @Test
    @DisplayName("连接/断开回调只记日志，不热切当前模式（§4.11.1）")
    void callbacksDoNotHotSwitch() {
        resolver.onEngineConnected(1L);
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L));
        resolver.onEngineDisconnected(1L);
        assertEquals(WorldAuthorityMode.BRAIN, resolver.mode(1L));
    }
}
