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

package com.holzyn.actor.domain.equip.service;

import com.holzyn.actor.domain.equip.entity.ActorItemEquipment;
import com.holzyn.actor.domain.equip.model.EquipmentRejectedException;
import com.holzyn.actor.domain.equip.model.EquipmentSlot;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.repository.ActorItemEquipmentRepository;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.item.wear.WearProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装备唯一写入口：穿/脱/自愈/开关。
 *
 * <p>不加载 Spring：仓储与物品服务全部 Mockito 模拟。装备行只记 item_key，
 * 「手上这件是哪件」由 {@link InventoryService#bestLayerOf} 现找——所以这里的桩
 * 全部打在 bestLayerOf 上。</p>
 */
class EquipmentServiceTest {

    private ActorItemEquipmentRepository repository;
    private InventoryService inventoryService;
    private ItemProperties itemProperties;
    private WearProperties wearProperties;
    private EquipmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActorItemEquipmentRepository.class);
        inventoryService = mock(InventoryService.class);
        itemProperties = new ItemProperties();
        itemProperties.setEnabled(true);
        wearProperties = new WearProperties();
        wearProperties.setEnabled(true);
        service = new EquipmentService(repository, inventoryService, itemProperties, wearProperties);
        when(repository.findByProjectIdAndHolderRefAndSlot(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findByProjectIdAndHolderRefOrderBySlotAsc(anyLong(), anyString()))
                .thenReturn(List.of());
    }

    // ==================== 开关 ====================

    @Test
    @DisplayName("开关：损耗域关 / 物品域关 → 不生效")
    void enabledRequiresBothDomains() {
        assertTrue(service.enabled());
        wearProperties.setEnabled(false);
        assertFalse(service.enabled());
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(false);
        assertFalse(service.enabled());
    }

    // ==================== 穿 ====================

    @Test
    @DisplayName("未登记物品 → NOT_FOUND")
    void equipRejectsUnknownItem() {
        when(inventoryService.findDef(1L, "柴刀")).thenReturn(Optional.empty());
        EquipmentRejectedException e = assertThrows(EquipmentRejectedException.class,
                () -> service.equip(1L, "npc:1", null, "柴刀", 100L));
        assertEquals(EquipmentRejectedException.NOT_FOUND, e.reasonCode());
    }

    @Test
    @DisplayName("没有装备槽的东西 → NOT_EQUIPPABLE")
    void equipRejectsNotEquippable() {
        when(inventoryService.findDef(1L, "面粉")).thenReturn(Optional.of(def("面粉", null)));
        EquipmentRejectedException e = assertThrows(EquipmentRejectedException.class,
                () -> service.equip(1L, "npc:1", null, "面粉", 100L));
        assertEquals(EquipmentRejectedException.NOT_EQUIPPABLE, e.reasonCode());
    }

    @Test
    @DisplayName("槽位对不上（拿衣服往手上套）→ WRONG_SLOT")
    void equipRejectsWrongSlot() {
        when(inventoryService.findDef(1L, "粗布衣")).thenReturn(Optional.of(def("粗布衣", "body")));
        EquipmentRejectedException e = assertThrows(EquipmentRejectedException.class,
                () -> service.equip(1L, "npc:1", "hand", "粗布衣", 100L));
        assertEquals(EquipmentRejectedException.WRONG_SLOT, e.reasonCode());
    }

    @Test
    @DisplayName("东西不在身上 → NOT_CARRIED")
    void equipRejectsNotCarried() {
        when(inventoryService.findDef(1L, "柴刀")).thenReturn(Optional.of(def("柴刀", "hand")));
        when(inventoryService.bestLayerOf(1L, "npc:1", "柴刀")).thenReturn(Optional.empty());
        EquipmentRejectedException e = assertThrows(EquipmentRejectedException.class,
                () -> service.equip(1L, "npc:1", null, "柴刀", 100L));
        assertEquals(EquipmentRejectedException.NOT_CARRIED, e.reasonCode());
    }

    @Test
    @DisplayName("手上槽位只能拿武器 → NOT_A_WEAPON")
    void equipRejectsNonWeaponInHand() {
        when(inventoryService.findDef(1L, "火把")).thenReturn(Optional.of(def("火把", "hand")));
        when(inventoryService.bestLayerOf(1L, "npc:1", "火把"))
                .thenReturn(Optional.of(new ItemView("火把", "火把", "tool", 1, 100, 100, "hand")));
        EquipmentRejectedException e = assertThrows(EquipmentRejectedException.class,
                () -> service.equip(1L, "npc:1", null, "火把", 100L));
        assertEquals(EquipmentRejectedException.NOT_A_WEAPON, e.reasonCode());
    }

    @Test
    @DisplayName("拿刀：落一行装备，槽位按物品定义，返回成色")
    void equipCreatesRow() {
        when(inventoryService.findDef(1L, "柴刀")).thenReturn(Optional.of(def("柴刀", "hand")));
        when(inventoryService.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 60, "hand")));

        EquipmentView view = service.equip(1L, "npc:1", null, "柴刀", 500L);

        assertEquals(EquipmentSlot.HAND, view.slot());
        assertEquals("柴刀", view.itemKey());
        assertEquals(60, view.condition(), "成色=min(品质100, 耐久60)");
        ArgumentCaptor<ActorItemEquipment> rows = ArgumentCaptor.forClass(ActorItemEquipment.class);
        verify(repository).save(rows.capture());
        assertEquals("npc:1", rows.getValue().getHolderRef());
        assertEquals("hand", rows.getValue().getSlot());
        assertEquals("柴刀", rows.getValue().getItemKey());
        assertEquals(500L, rows.getValue().getSinceGameSecond());
    }

    @Test
    @DisplayName("换装：同槽位已有别的 → 覆盖 item_key，不新增行")
    void equipReplacesSameSlot() {
        ActorItemEquipment existing = new ActorItemEquipment();
        existing.setId(9L);
        existing.setProjectId(1L);
        existing.setHolderRef("npc:1");
        existing.setSlot("hand");
        existing.setItemKey("短刀");
        when(repository.findByProjectIdAndHolderRefAndSlot(1L, "npc:1", "hand"))
                .thenReturn(Optional.of(existing));
        when(inventoryService.findDef(1L, "柴刀")).thenReturn(Optional.of(def("柴刀", "hand")));
        when(inventoryService.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")));

        service.equip(1L, "npc:1", "hand", "柴刀", 900L);

        ArgumentCaptor<ActorItemEquipment> rows = ArgumentCaptor.forClass(ActorItemEquipment.class);
        verify(repository).save(rows.capture());
        assertEquals(9L, rows.getValue().getId(), "应复用同一行（槽位唯一）");
        assertEquals("柴刀", rows.getValue().getItemKey());
    }

    // ==================== 脱 ====================

    @Test
    @DisplayName("脱：本来就没戴 → false，不删任何行")
    void unequipWithoutRow() {
        assertFalse(service.unequip(1L, "npc:1", null, 100L));
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("脱：有行 → 删掉并返回 true")
    void unequipDeletesRow() {
        ActorItemEquipment row = new ActorItemEquipment();
        row.setId(3L);
        row.setItemKey("柴刀");
        when(repository.findByProjectIdAndHolderRefAndSlot(1L, "npc:1", "hand"))
                .thenReturn(Optional.of(row));

        assertTrue(service.unequip(1L, "npc:1", "hand", 100L));
        verify(repository).delete(row);
    }

    // ==================== 读 / 自愈 ====================

    @Test
    @DisplayName("equipmentOf：物品没了 → 自愈删行，不返回假装备")
    void equipmentOfHealsDanglingRow() {
        ActorItemEquipment row = new ActorItemEquipment();
        row.setId(5L);
        row.setSlot("hand");
        row.setItemKey("柴刀");
        when(repository.findByProjectIdAndHolderRefOrderBySlotAsc(1L, "npc:1"))
                .thenReturn(List.of(row));
        when(inventoryService.bestLayerOf(1L, "npc:1", "柴刀")).thenReturn(Optional.empty());

        List<EquipmentView> views = service.equipmentOf(1L, "npc:1");

        assertTrue(views.isEmpty(), "东西没了就不该有装备");
        verify(repository).delete(row);
    }

    @Test
    @DisplayName("equipmentOf：物品还在 → 返回视图（状态来自持有栈）")
    void equipmentOfResolvesLayer() {
        ActorItemEquipment row = new ActorItemEquipment();
        row.setId(5L);
        row.setSlot("hand");
        row.setItemKey("柴刀");
        row.setSinceGameSecond(88L);
        when(repository.findByProjectIdAndHolderRefOrderBySlotAsc(1L, "npc:1"))
                .thenReturn(List.of(row));
        when(inventoryService.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 80, 40, "hand")));

        List<EquipmentView> views = service.equipmentOf(1L, "npc:1");

        assertEquals(1, views.size());
        assertEquals(40, views.get(0).condition());
        assertEquals(88L, views.get(0).sinceGameSecond());
    }

    @Test
    @DisplayName("handOf：只认 hand 槽")
    void handOfFiltersSlot() {
        ActorItemEquipment body = new ActorItemEquipment();
        body.setSlot("body");
        body.setItemKey("粗布衣");
        when(repository.findByProjectIdAndHolderRefOrderBySlotAsc(1L, "npc:1"))
                .thenReturn(List.of(body));
        when(inventoryService.bestLayerOf(1L, "npc:1", "粗布衣"))
                .thenReturn(Optional.of(new ItemView("粗布衣", "粗布衣", "wearable", 1, 100, 100, "body")));

        assertTrue(service.handOf(1L, "npc:1").isEmpty(), "身上穿的不是手上的");
        assertTrue(service.slotOf(1L, "npc:1", "body").isPresent());
    }

    private static ActorItemDef def(String key, String slot) {
        ActorItemDef def = new ActorItemDef();
        def.setItemKey(key);
        def.setName(key);
        def.setCategory(slot == null ? "material" : ("body".equals(slot) ? "wearable" : "weapon"));
        def.setEquipSlot(slot);
        return def;
    }
}
