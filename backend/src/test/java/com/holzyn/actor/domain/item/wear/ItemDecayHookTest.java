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

package com.holzyn.actor.domain.item.wear;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 腐坏 tick 钩子：只在损耗域 + 物品域都开着时才动库存。
 *
 * <p>不加载 Spring：{@link FactDecayHook} 的实现只做「开关判断 + 委托 + 异常隔离」，
 * 用 Mockito 验调用即可。</p>
 */
class ItemDecayHookTest {

    private InventoryService inventoryService;
    private WearProperties wearProperties;
    private ItemProperties itemProperties;
    private ItemDecayHook hook;

    @BeforeEach
    void setUp() {
        inventoryService = mock(InventoryService.class);
        wearProperties = new WearProperties();
        itemProperties = new ItemProperties();
        hook = new ItemDecayHook(inventoryService, wearProperties, itemProperties);
    }

    @Test
    @DisplayName("默认关：不碰库存")
    void disabledByDefault() {
        hook.onHour(1L, 3600L);
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("物品域关着：即使损耗域开着也不腐坏（没有可信持有栈）")
    void itemDomainOffSkips() {
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(false);
        hook.onHour(1L, 3600L);
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("两个开关都开：按游戏秒结算一次")
    void enabledDelegates() {
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(true);
        when(inventoryService.decayTick(anyLong(), anyLong()))
                .thenReturn(new InventoryService.DecayReport(2, 1));

        hook.onHour(7L, 7200L);

        verify(inventoryService).decayTick(7L, 7200L);
    }

    @Test
    @DisplayName("空 projectId：直接返回，不查库")
    void nullProjectSkips() {
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(true);
        hook.onHour(null, 3600L);
        verify(inventoryService, never()).decayTick(anyLong(), anyLong());
    }

    @Test
    @DisplayName("结算抛异常：吞掉不冒泡（不阻断事实到期）")
    void exceptionIsSwallowed() {
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(true);
        when(inventoryService.decayTick(anyLong(), anyLong())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> hook.onHour(1L, 3600L));
    }
}
