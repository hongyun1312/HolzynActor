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

package com.holzyn.actor.domain.item.fact;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物品变化的「被看见」层（对接）：{@code stock:*} 事实同步。
 *
 * <p>锁三件事：① 只给地点写（个人背包是私事）；② 文本与市场口径逐字一致；
 * ③ 物品域关着时一条都不写（关开关=回基线）。</p>
 */
class ItemStockFactsTest {

    private WorldFactService worldFactService;
    private InventoryService inventoryService;
    private WorldLocationRepository locationRepository;
    private ItemProperties itemProperties;
    private ItemStockFacts facts;

    @BeforeEach
    void setUp() {
        worldFactService = mock(WorldFactService.class);
        inventoryService = mock(InventoryService.class);
        locationRepository = mock(WorldLocationRepository.class);
        itemProperties = new ItemProperties();
        itemProperties.setEnabled(true);
        facts = new ItemStockFacts();
        ReflectionTestUtils.setField(facts, "worldFactService", worldFactService);
        ReflectionTestUtils.setField(facts, "inventoryService", inventoryService);
        ReflectionTestUtils.setField(facts, "itemProperties", itemProperties);
        ReflectionTestUtils.setField(facts, "locationRepository", locationRepository);
    }

    @Test
    @DisplayName("地点货架：写 stock:loc:{id}:{item}，文本与市场口径一致")
    void writesForLocation() {
        when(inventoryService.quantityOf(1L, "loc:65", "面包")).thenReturn(3);
        ActorItemDef def = new ActorItemDef();
        def.setItemKey("面包");
        def.setName("面包");
        when(inventoryService.findDef(1L, "面包")).thenReturn(Optional.of(def));
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(65L);
        loc.setName("集市");
        when(locationRepository.findById(65L)).thenReturn(Optional.of(loc));

        facts.syncHolder(1L, "loc:65", "面包", 1000L);

        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(worldFactService).upsert(captor.capture());
        FactCommand cmd = captor.getValue();
        assertEquals("stock", cmd.factType());
        assertEquals("stock:loc:65:面包", cmd.factKey());
        assertEquals("面包还剩 3 件", cmd.stateText());
        assertEquals("location", cmd.visibility());
        assertEquals(65L, cmd.locationId());
        assertEquals("集市", cmd.locationName());
        assertTrue(cmd.attributesJson().contains("\"qty\":3"));
    }

    @Test
    @DisplayName("个人背包：不写事实（隔着墙不该知道人家有几袋米）")
    void skipsCharacterHolder() {
        facts.syncHolder(1L, "npc:1", "面包", 1000L);
        verify(worldFactService, never()).upsert(any());
    }

    @Test
    @DisplayName("物品域关闭：一条都不写")
    void skipsWhenItemDisabled() {
        itemProperties.setEnabled(false);
        facts.syncHolder(1L, "loc:65", "面包", 1000L);
        verify(worldFactService, never()).upsert(any());
        verify(inventoryService, never()).quantityOf(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("卖完了：文本是「卖完了」，不是「还剩 0 件」")
    void zeroStockText() {
        when(inventoryService.quantityOf(1L, "loc:65", "面包")).thenReturn(0);
        ActorItemDef def = new ActorItemDef();
        def.setItemKey("面包");
        def.setName("面包");
        when(inventoryService.findDef(1L, "面包")).thenReturn(Optional.of(def));

        facts.syncHolder(1L, "loc:65", "面包", 1000L);

        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(worldFactService).upsert(captor.capture());
        assertEquals("面包卖完了", captor.getValue().stateText());
    }

    @Test
    @DisplayName("转移两端：只同步是地点的那一端")
    void syncPairOnlyLocationSide() {
        when(inventoryService.quantityOf(1L, "loc:65", "柴刀")).thenReturn(2);
        ActorItemDef def = new ActorItemDef();
        def.setItemKey("柴刀");
        def.setName("柴刀");
        when(inventoryService.findDef(1L, "柴刀")).thenReturn(Optional.of(def));

        facts.syncPair(1L, "loc:65", "npc:1", "柴刀", 1000L);

        verify(worldFactService).upsert(any());
        verify(inventoryService, never()).quantityOf(1L, "npc:1", "柴刀");
    }

    @Test
    @DisplayName("全量刷一遍：按持有者清单逐个写")
    void syncAll() {
        when(inventoryService.list(1L, "loc:65")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("面包", "面包", "food", 3, 100, 100, null),
                new com.holzyn.actor.domain.item.model.ItemView("柴刀", "柴刀", "weapon", 1, 100, 98, "hand")));
        when(inventoryService.findDef(anyLong(), anyString())).thenReturn(Optional.empty());

        facts.syncAll(1L, "loc:65", 1000L);

        verify(worldFactService, org.mockito.Mockito.times(2)).upsert(any());
    }
}
