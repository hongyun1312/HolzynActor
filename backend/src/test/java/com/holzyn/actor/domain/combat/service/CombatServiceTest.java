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

package com.holzyn.actor.domain.combat.service;

import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.combat.model.CombatRequest;
import com.holzyn.actor.domain.combat.model.CombatResult;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 一击的结算：扣血 + 磨损 + 顺手抽刀 + 幂等。
 *
 * <p>不加载 Spring：所有依赖 Mockito 模拟，字段注入用 {@code ReflectionTestUtils}
 * （既有服务的构造器一律不改，AGENTS §8.1.2）。</p>
 */
class CombatServiceTest {

    private WearProperties wearProperties;
    private ItemProperties itemProperties;
    private InventoryPort inventoryPort;
    private EquipmentService equipmentService;
    private NpcStateService npcStateService;
    private AgentMemoryService agentMemoryService;
    private ActorCharacterRepository characterRepository;
    private CombatService service;

    @BeforeEach
    void setUp() {
        wearProperties = new WearProperties();
        wearProperties.setEnabled(true);
        itemProperties = new ItemProperties();
        itemProperties.setEnabled(true);
        inventoryPort = mock(InventoryPort.class);
        equipmentService = mock(EquipmentService.class);
        npcStateService = mock(NpcStateService.class);
        agentMemoryService = mock(AgentMemoryService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        service = new CombatService(wearProperties, itemProperties);
        ReflectionTestUtils.setField(service, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(service, "equipmentService", equipmentService);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
        ReflectionTestUtils.setField(service, "agentMemoryService", agentMemoryService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
    }

    @Test
    @DisplayName("开关：损耗域 / 物品域 / 端口任一缺失 → 不生效")
    void enabledRequiresAll() {
        assertTrue(service.enabled());
        wearProperties.setEnabled(false);
        assertFalse(service.enabled());
        wearProperties.setEnabled(true);
        itemProperties.setEnabled(false);
        assertFalse(service.enabled());
        itemProperties.setEnabled(true);
        ReflectionTestUtils.setField(service, "inventoryPort", null);
        assertFalse(service.enabled());
    }

    @Test
    @DisplayName("请求缺字段 → BAD_REQUEST，不动任何状态")
    void rejectsBadRequest() {
        CombatResult result = service.strike(new CombatRequest(1L, null, "npc:2", null, 100L, 1L, "k"));
        assertEquals(CombatService.REASON_BAD_REQUEST, result.reason());
        assertEquals(0, result.damage());
        verify(npcStateService, never()).applyHealthDelta(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("目标不是角色 → NO_TARGET")
    void rejectsUnknownTarget() {
        when(characterRepository.findById(2L)).thenReturn(Optional.empty());
        CombatResult result = service.strike(request());
        assertEquals(CombatService.REASON_NO_TARGET, result.reason());
        assertFalse(result.targetPresent());
    }

    @Test
    @DisplayName("目标不在同一地点 → TOO_FAR（距离≤1 格）")
    void rejectsTooFar() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(actor(1L, "青岳主峰")));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(actor(2L, "青石巷")));
        CombatResult result = service.strike(request());
        assertEquals(CombatService.REASON_TOO_FAR, result.reason());
        verify(inventoryPort, never()).wear(anyLong(), anyString(), anyString(), anyInt(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("空手（没指定武器、手上空着、背包里没武器）→ NO_WEAPON")
    void rejectsNoWeapon() {
        samePlace();
        when(equipmentService.enabled()).thenReturn(true);
        when(equipmentService.handOf(1L, "npc:1")).thenReturn(Optional.empty());
        when(inventoryPort.list(1L, "npc:1"))
                .thenReturn(List.of(new ItemView("干粮", "干粮", "food", 2, 100, 100, null)));
        CombatResult result = service.strike(request());
        assertEquals(CombatService.REASON_NO_WEAPON, result.reason());
    }

    @Test
    @DisplayName("家伙已经断了（耐久 0）→ WEAPON_BROKEN，不磨损也不扣血")
    void rejectsBrokenWeapon() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 0, "hand")));
        CombatResult result = service.strike(request());
        assertEquals(CombatService.REASON_WEAPON_BROKEN, result.reason());
        verify(inventoryPort, never()).wear(anyLong(), anyString(), anyString(), anyInt(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("打中：伤害按成色折算，扣血 + 磨损，顺手抽刀")
    void landsAndWears() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")));
        when(inventoryPort.wear(eq(1L), eq("npc:1"), eq("柴刀"), anyInt(), eq(5L), eq(1000L),
                eq("plan:9:op:0:wear")))
                .thenReturn(new DurabilityChange("柴刀", 100, 98, false, false));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(100);
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(state);
        when(npcStateService.applyHealthDelta(1L, "npc:2", -8, "combat")).thenReturn(92);
        when(equipmentService.enabled()).thenReturn(true);
        when(equipmentService.handOf(1L, "npc:1")).thenReturn(Optional.empty());

        CombatResult result = service.strike(request());

        assertNull(result.reason(), "应该打成了");
        assertTrue(result.landed());
        assertEquals(8, result.damage(), "满成色按基准 8");
        assertEquals(100, result.healthBefore());
        assertEquals(92, result.healthAfter());
        assertEquals(98, result.durabilityAfter());
        assertFalse(result.weaponBroke());
        assertTrue(result.autoDrawn(), "原本手上空着 → 顺手抽刀");
        verify(equipmentService).equip(1L, "npc:1", "hand", "柴刀", 1000L);
        verify(agentMemoryService).recordObservation(eq(1L), eq("npc:2"), anyString(), eq(4), any());
    }

    @Test
    @DisplayName("卷了刃：伤害按成色打折（耐久 20 → 5 点），不是零")
    void bluntWeaponStillHurts() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 20, "hand")));
        when(inventoryPort.wear(anyLong(), anyString(), anyString(), anyInt(), any(), anyLong(), any()))
                .thenReturn(new DurabilityChange("柴刀", 20, 18, false, false));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(50);
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(state);
        when(npcStateService.applyHealthDelta(eq(1L), eq("npc:2"), anyInt(), anyString())).thenReturn(45);

        CombatResult result = service.strike(request());

        assertEquals(5, result.damage(), "成色 20 → 系数 0.6 → 8×0.6≈5");
    }

    @Test
    @DisplayName("打到断：武器耐久归零 → weaponBroke=true")
    void weaponBreaks() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 2, "hand")));
        when(inventoryPort.wear(anyLong(), anyString(), anyString(), anyInt(), any(), anyLong(), any()))
                .thenReturn(new DurabilityChange("柴刀", 2, 0, true, false));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(100);
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(state);
        when(npcStateService.applyHealthDelta(eq(1L), eq("npc:2"), anyInt(), anyString())).thenReturn(96);

        CombatResult result = service.strike(request());

        assertTrue(result.weaponBroke());
        assertEquals(0, result.durabilityAfter());
    }

    @Test
    @DisplayName("幂等：磨损账说重放 → 立刻收手，不再扣一次血")
    void replayDoesNotDamageTwice() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")));
        when(inventoryPort.wear(anyLong(), anyString(), anyString(), anyInt(), any(), anyLong(), any()))
                .thenReturn(new DurabilityChange("柴刀", -1, -1, false, true));

        CombatResult result = service.strike(request());

        assertEquals(CombatService.REASON_REPLAYED, result.reason());
        verify(npcStateService, never()).applyHealthDelta(anyLong(), anyString(), anyInt(), anyString());
        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("M×I：对方穿着粗布衣 → 挡掉三成伤害，并写进结果与记忆")
    void armorAbsorbsDamage() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")));
        when(inventoryPort.wear(anyLong(), anyString(), anyString(), anyInt(), any(), anyLong(), any()))
                .thenReturn(new DurabilityChange("柴刀", 100, 98, false, false));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(100);
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(state);
        when(npcStateService.applyHealthDelta(1L, "npc:2", -5, "combat")).thenReturn(95);
        when(equipmentService.enabled()).thenReturn(true);
        when(equipmentService.handOf(1L, "npc:1")).thenReturn(Optional.empty());
        when(equipmentService.equipmentOf(1L, "npc:2")).thenReturn(List.of(
                new com.holzyn.actor.domain.equip.model.EquipmentView(
                        "body", "粗布衣", "粗布衣", "cloth", 1, 100, 100, 0L)));

        CombatResult result = service.strike(request());

        assertEquals(5, result.damage(), "8 点被全新粗布衣挡掉 40%（3 点）→ 5");
        assertEquals(3, result.armorAbsorbed());
        assertEquals("粗布衣", result.armorName());
        assertTrue(result.armored());
        assertEquals(95, result.healthAfter());
        verify(agentMemoryService).recordObservation(eq(1L), eq("npc:2"), anyString(), eq(4), any());
    }

    @Test
    @DisplayName("M×I：衣服旧了挡得少（成色 20 → 只挡 1 点），且永远留 1 点伤害")
    void wornArmorAbsorbsLess() {
        samePlace();
        when(inventoryPort.bestLayerOf(1L, "npc:1", "柴刀"))
                .thenReturn(Optional.of(new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")));
        when(inventoryPort.wear(anyLong(), anyString(), anyString(), anyInt(), any(), anyLong(), any()))
                .thenReturn(new DurabilityChange("柴刀", 100, 98, false, false));
        ActorNpcState state = new ActorNpcState();
        state.setHealth(100);
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(state);
        when(npcStateService.applyHealthDelta(eq(1L), eq("npc:2"), anyInt(), anyString())).thenReturn(93);
        when(equipmentService.enabled()).thenReturn(true);
        when(equipmentService.handOf(1L, "npc:1")).thenReturn(Optional.empty());
        when(equipmentService.equipmentOf(1L, "npc:2")).thenReturn(List.of(
                new com.holzyn.actor.domain.equip.model.EquipmentView(
                        "body", "粗布衣", "粗布衣", "cloth", 1, 100, 20, 0L)));

        CombatResult result = service.strike(request());

        assertEquals(1, result.armorAbsorbed(), "成色 20 → 3.2×0.2≈0.64 → 1 点");
        assertEquals(7, result.damage());
        assertTrue(result.damage() >= 1, "护具不能把伤害挡成 0（否则砍不动）");
    }

    // ==================== 工具 ====================

    private CombatRequest request() {
        return new CombatRequest(1L, "npc:1", "npc:2", "柴刀", 1000L, 5L, "plan:9:op:0");
    }

    private void samePlace() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(actor(1L, "青岳主峰")));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(actor(2L, "青岳主峰")));
    }

    private static ActorCharacter actor(Long id, String location) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("角色" + id);
        c.setLocation(location);
        return c;
    }
}
