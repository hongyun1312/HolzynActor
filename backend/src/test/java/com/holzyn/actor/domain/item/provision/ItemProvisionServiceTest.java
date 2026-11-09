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

package com.holzyn.actor.domain.item.provision;

import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开局供给（对接）：开关、幂等键、职业/玩家/铺货三类发放。
 *
 * <p>不起 Spring：供给的每一笔都必须带固定幂等键（重复开世界不重复发），
 * 这条只能靠验证调用参数来钉住。</p>
 */
class ItemProvisionServiceTest {

    private InventoryService inventory;
    private EquipmentService equipment;
    private ActorCharacterRepository characterRepository;
    private WorldLocationRepository locationRepository;
    private ItemProperties itemProperties;
    private ProvisionProperties provisionProperties;
    private ItemProvisionService service;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryService.class);
        equipment = mock(EquipmentService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        locationRepository = mock(WorldLocationRepository.class);
        itemProperties = new ItemProperties();
        itemProperties.setEnabled(true);
        provisionProperties = new ProvisionProperties();
        service = new ItemProvisionService(inventory, equipment, characterRepository,
                locationRepository, itemProperties, provisionProperties);
    }

    @Test
    @DisplayName("开关：物品域关 / 供给关 → 整体 no-op（一笔都不发）")
    void gates() {
        itemProperties.setEnabled(false);
        assertEquals("ITEM_DISABLED", service.provision(1L, 0L).skipped());
        itemProperties.setEnabled(true);
        provisionProperties.setEnabled(false);
        assertEquals("PROVISION_DISABLED", service.provision(1L, 0L).skipped());
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("NPC：按职业发家伙 + 口粮 + 衣物，衣物顺手穿上，幂等键固定")
    void npcKitAndWear() {
        when(equipment.enabled()).thenReturn(true);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(npc(1L, "铁匠", 5)));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(1, report.characters());
        assertEquals(0, report.locations());
        assertTrue(report.grants() >= 5, "铁锤/铁锭/面包/水/粗布衣");
        verify(inventory).add(1L, "铁锤", 1, "npc:1", "spawn", null, 1000L, "provision:npc:1:铁锤:bag");
        verify(inventory).add(1L, "面包", 2, "npc:1", "spawn", null, 1000L, "provision:npc:1:面包:bag");
        verify(inventory).add(1L, "粗布衣", 1, "npc:1", "spawn", null, 1000L, "provision:npc:1:粗布衣:worn");
        verify(equipment).equip(1L, "npc:1", null, "粗布衣", 1000L);
    }

    @Test
    @DisplayName("玩家：短刀 + 100 文（与 NPC 的柴刀口径不同）")
    void playerKit() {
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(player(9L)));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());

        service.provision(1L, 1000L);

        verify(inventory).add(1L, "短刀", 1, "npc:9", "spawn", null, 1000L, "provision:npc:9:短刀:bag");
        verify(inventory).add(1L, "coin", 100, "npc:9", "spawn", null, 1000L, "provision:npc:9:coin:bag");
    }

    @Test
    @DisplayName("铺货：只给像店铺的地点发，货架 ref 是 loc:{id}")
    void locationStock() {
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of());
        ActorWorldLocation shop = location(65L, "铁匠铺");
        ActorWorldLocation hill = location(66L, "青岳主峰");
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(shop, hill));

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(1, report.locations(), "山顶不该铺货");
        verify(inventory).add(1L, "柴刀", 3, "loc:65", "spawn", null, 1000L, "provision:loc:65:柴刀:bag");
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), eq("loc:66"), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("上限：maxHolders 限制单次处理的角色数")
    void maxHoldersCap() {
        provisionProperties.setMaxHolders(1);
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(npc(1L, "铁匠", 5), npc(2L, "农户", 5)));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(1, report.characters());
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), eq("npc:2"), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("关闭分项：npc-kit=false 时只发铺货")
    void npcKitDisabled() {
        provisionProperties.setNpcKit(false);
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(npc(1L, "铁匠", 5)));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(location(65L, "集市")));

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(0, report.characters());
        assertEquals(1, report.locations());
        assertFalse(report.noop());
    }

    @Test
    @DisplayName("没有店铺地点：给最大的聚落铺货（镇 > 村），山/峰 不铺")
    void settlementFallback() {
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of());
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                location(65L, "青岳主峰"), location(71L, "青禾村"), location(72L, "临溪镇")));

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(1, report.locations(), "只给最大的那个聚落铺一次");
        verify(inventory).add(1L, "面包", 10, "loc:72", "spawn", null, 1000L, "provision:loc:72:面包:bag");
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), eq("loc:65"), anyString(),
                any(), anyLong(), any());
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), eq("loc:71"), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("有店铺时不再给聚落铺货（避免同一世界出现两个货架）")
    void shopWinsOverSettlement() {
        when(equipment.enabled()).thenReturn(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of());
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                location(65L, "临溪镇"), location(72L, "铁匠铺")));

        ItemProvisionService.ProvisionReport report = service.provision(1L, 1000L);

        assertEquals(1, report.locations());
        verify(inventory, never()).add(anyLong(), anyString(), anyInt(), eq("loc:65"), anyString(),
                any(), anyLong(), any());
    }

    // ==================== 工具 ====================

    private static ActorCharacter npc(Long id, String occupation, int importance) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("角色" + id);
        c.setOccupation(occupation);
        c.setImportance(importance);
        c.setController("npc");
        return c;
    }

    private static ActorCharacter player(Long id) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("玩家");
        c.setController("player");
        return c;
    }

    private static ActorWorldLocation location(Long id, String name) {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(id);
        loc.setName(name);
        return loc;
    }
}
