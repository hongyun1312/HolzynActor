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

package com.holzyn.actor.domain.world.effect.service;

import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.combat.model.CombatRequest;
import com.holzyn.actor.domain.combat.model.CombatResult;
import com.holzyn.actor.domain.combat.service.CombatService;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.craft.model.CraftJobResult;
import com.holzyn.actor.domain.craft.model.CraftStartRequest;
import com.holzyn.actor.domain.craft.service.CraftService;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.market.model.TradeRequest;
import com.holzyn.actor.domain.market.model.TradeResult;
import com.holzyn.actor.domain.market.service.MarketService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.effect.model.EffectApplyException;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.effect.model.EffectResult;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.service.InfoDiffusionService;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EffectApplier 9 op 单测。
 *
 * <p>重点：fact.* 的 source_event_id 必填、默认幂等键 plan:{id}:op:{i}、
 * 物品域未装配降级跳过、第二个 op 失败时异常下标正确（整批回滚由事务代理保证）。</p>
 */
class EffectApplierImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EffectApplierImpl applier;
    private WorldFactService factService;
    private WorldFactQuery factQuery;
    private InventoryPort inventoryPort;
    private RelationDynamicsService relationService;
    private AgentMemoryService memoryService;
    private InfoDiffusionService infoService;
    private WorldMemoryService worldMemoryService;
    private NpcStateService npcStateService;
    private AgentRuntime agentRuntime;

    @BeforeEach
    void setUp() {
        factService = mock(WorldFactService.class);
        factQuery = mock(WorldFactQuery.class);
        inventoryPort = mock(InventoryPort.class);
        relationService = mock(RelationDynamicsService.class);
        memoryService = mock(AgentMemoryService.class);
        infoService = mock(InfoDiffusionService.class);
        worldMemoryService = mock(WorldMemoryService.class);
        npcStateService = mock(NpcStateService.class);
        agentRuntime = mock(AgentRuntime.class);
        applier = new EffectApplierImpl();
        ReflectionTestUtils.setField(applier, "worldFactService", factService);
        ReflectionTestUtils.setField(applier, "worldFactQuery", factQuery);
        ReflectionTestUtils.setField(applier, "relationDynamicsService", relationService);
        ReflectionTestUtils.setField(applier, "agentMemoryService", memoryService);
        ReflectionTestUtils.setField(applier, "infoDiffusionService", infoService);
        ReflectionTestUtils.setField(applier, "worldMemoryService", worldMemoryService);
        ReflectionTestUtils.setField(applier, "npcStateService", npcStateService);
        ReflectionTestUtils.setField(applier, "agentRuntime", agentRuntime);
    }

    @Test
    @DisplayName("fact.upsert：默认幂等键 plan:{id}:op:{i}，source_event_id 取上下文，返回 factId")
    void factUpsertUsesPlanKeyAndSourceEvent() {
        ActorWorldFact saved = new ActorWorldFact();
        saved.setId(5L);
        when(factService.upsert(any(FactCommand.class)))
                .thenReturn(new FactChange(saved, 0, 1, "create"));
        ObjectNode body = mapper.createObjectNode()
                .put("factType", "route").put("factKey", "route:A-B")
                .put("stateText", "桥塌了").put("name", "A-B 路");
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("fact.upsert", body, null)), ctx(99L));

        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(captor.capture());
        assertEquals(99L, captor.getValue().sourceEventId());
        assertEquals("plan:8:op:0", captor.getValue().idempotencyKey());
        assertEquals(5L, report.get(0).factId());
    }

    @Test
    @DisplayName("fact.upsert：缺 source_event_id 直接失败（A17）")
    void factUpsertWithoutEventFails() {
        ObjectNode body = mapper.createObjectNode()
                .put("factType", "route").put("factKey", "route:A-B").put("stateText", "桥塌了");
        EffectApplyException e = assertThrows(EffectApplyException.class,
                () -> applier.apply(List.of(new EffectOp("fact.upsert", body, "k")), ctx(null)));
        assertEquals(0, e.index());
        verify(factService, never()).upsert(any());
    }

    @Test
    @DisplayName("inventory.transfer：InventoryPort 未装配 → SKIPPED_NO_INVENTORY，不抛异常")
    void inventoryWithoutPortIsSkipped() {
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "bread").put("qty", 2).put("from", "npc:1").put("to", "npc:2");
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("inventory.transfer", body, "inv:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_NO_INVENTORY, report.get(0).status());
        verify(inventoryPort, never()).transfer(any(), anyString(), anyInt(), any(), any(),
                any(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("inventory.transfer：装配端口后按幂等键调用")
    void inventoryWithPortTransfers() {
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "bread").put("qty", 2).put("from", "npc:1").put("to", "npc:2");
        applier.applyWithReport(List.of(new EffectOp("inventory.transfer", body, "inv:1")), ctx(99L));

        verify(inventoryPort).transfer(7L, "bread", 2, "npc:1", "npc:2",
                null, 99L, 1000L, "inv:1");
    }

    @Test
    @DisplayName("inventory.consume：装配端口后按幂等键调用（吃饭=真的少一件）")
    void inventoryConsumeWithPort() {
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "面包").put("qty", 1).put("from", "npc:1").put("reason", "consume");
        applier.applyWithReport(List.of(new EffectOp("inventory.consume", body, "eat:1")), ctx(99L));

        verify(inventoryPort).consume(7L, "面包", 1, "npc:1", "consume", 99L, 1000L, "eat:1");
    }

    @Test
    @DisplayName("inventory.consume：端口未装配 → SKIPPED_NO_INVENTORY，不抛异常")
    void inventoryConsumeWithoutPortIsSkipped() {
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "面包").put("qty", 1).put("from", "npc:1");
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("inventory.consume", body, "eat:2")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_NO_INVENTORY, report.get(0).status());
        verify(inventoryPort, never()).consume(any(), anyString(), anyInt(), any(), anyString(),
                any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("relation.delta：affinity/trust 分开传，不在 Applier 里自动折半")
    void relationDeltaUsesExactTrust() {
        ObjectNode body = mapper.createObjectNode()
                .put("from", "npc:1").put("to", "npc:2")
                .put("affinity", 5).put("trust", 2).put("reason", "帮忙");
        applier.applyWithReport(List.of(new EffectOp("relation.delta", body, "rel:1")), ctx(99L));

        verify(relationService).updateRelation(7L, "npc:1", "npc:2", 5, 2, "帮忙");
    }

    @Test
    @DisplayName("memory.add：写入 observation，importance/kind 透传")
    void memoryAddWritesObservation() {
        ObjectNode body = mapper.createObjectNode()
                .put("ref", "npc:1").put("content", "他记得那晚的雨")
                .put("importance", 4).put("kind", "AI");
        applier.applyWithReport(List.of(new EffectOp("memory.add", body, "mem:1")), ctx(99L));

        verify(memoryService).recordObservation(eq(7L), eq("npc:1"),
                eq("他记得那晚的雨"), eq(4), eq(com.holzyn.actor.domain.agent.runtime.DecisionSource.AI));
    }

    @Test
    @DisplayName("info.packet：发布带 source_event_id 的信息包")
    void infoPacketPublishesWithEvent() {
        when(infoService.publishPacket(anyLong(), anyLong(), anyString(), any(), anyString(),
                anyDouble(), any(), any(), anyLong())).thenReturn(11L);
        ObjectNode body = mapper.createObjectNode()
                .put("content", "码头封了").put("subject", "npc:2")
                .put("visibility", "location").put("reliability", 0.8).put("factKey", "route:A-B");
        applier.applyWithReport(List.of(new EffectOp("info.packet", body, "info:1")), ctx(99L));

        verify(infoService).publishPacket(7L, 99L, "码头封了", "npc:2",
                "location", 0.8, "码头", "route:A-B", 1000L);
    }

    @Test
    @DisplayName("entity.upsert：转 WorldMemoryService，day 从 gameSecond 推导")
    void entityUpsertWritesWorldMemory() {
        ObjectNode body = mapper.createObjectNode()
                .put("entityType", "place").put("content", "码头重新开张").put("subject", "码头");
        applier.applyWithReport(List.of(new EffectOp("entity.upsert", body, "ent:1")), ctx(99L));

        verify(worldMemoryService).upsertEntityState(7L, "码头", "place", "码头",
                "码头重新开张", null, 0L, "effect", 99L);
    }

    @Test
    @DisplayName("state.urgency：调 NpcStateService 的档位增量入口")
    void stateUrgencyCallsService() {
        ObjectNode body = mapper.createObjectNode()
                .put("ref", "npc:1").put("delta", 2).put("reason", "被跟踪");
        applier.applyWithReport(List.of(new EffectOp("state.urgency", body, "urg:1")), ctx(99L));

        verify(npcStateService).applyUrgencyDelta(7L, "npc:1", 2, "被跟踪");
    }

    @Test
    @DisplayName("schedule.plan：延迟计划返回 planId")
    void schedulePlanCallsRuntime() {
        when(agentRuntime.planLater(anyLong(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(33L);
        ObjectNode body = mapper.createObjectNode()
                .put("ref", "npc:1").put("delayMinutes", 15)
                .put("actionJson", "{\"action\":\"去码头\"}");
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("schedule.plan", body, "plan:1")), ctx(99L));

        verify(agentRuntime).planLater(7L, "npc:1", "{\"action\":\"去码头\"}", 15, 1000L, 99L);
        assertEquals(33L, report.get(0).planId());
    }

    @Test
    @DisplayName("market.trade：物品域未装配 → SKIPPED_NO_INVENTORY")
    void marketTradeWithoutPortIsSkipped() {
        ObjectNode body = mapper.createObjectNode()
                .put("seller", "loc:9").put("itemKey", "bread").put("qty", 1);
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("market.trade", body, "market:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_NO_INVENTORY, report.get(0).status());
    }

    @Test
    @DisplayName("market.trade：市场关闭 → SKIPPED_DISABLED（不抛异常）")
    void marketTradeDisabledIsSkipped() {
        MarketService market = mock(MarketService.class);
        when(market.enabled()).thenReturn(false);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "marketService", market);
        ObjectNode body = mapper.createObjectNode()
                .put("seller", "loc:9").put("itemKey", "bread").put("qty", 1);
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("market.trade", body, "market:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_DISABLED, report.get(0).status());
        verify(market, never()).trade(any());
    }

    @Test
    @DisplayName("market.trade：装配市场后按幂等键结算，买家取上下文行动者")
    void marketTradeDelegatesToService() {
        MarketService market = mock(MarketService.class);
        when(market.enabled()).thenReturn(true);
        when(market.trade(any(TradeRequest.class))).thenReturn(
                new TradeResult(5L, "bread", 2, 15, 30, true, false, 1, 17, false));
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "marketService", market);
        ObjectNode body = mapper.createObjectNode()
                .put("seller", "loc:9").put("itemKey", "bread").put("qty", 2)
                .put("unitPrice", 15).put("credit", false);
        applier.applyWithReport(List.of(new EffectOp("market.trade", body, "market:2")), ctx(99L));

        ArgumentCaptor<TradeRequest> captor = ArgumentCaptor.forClass(TradeRequest.class);
        verify(market).trade(captor.capture());
        assertEquals(7L, captor.getValue().projectId());
        assertEquals("loc:9", captor.getValue().sellerRef());
        assertEquals("npc:1", captor.getValue().buyerRef());
        assertEquals("bread", captor.getValue().itemKey());
        assertEquals(2, captor.getValue().qty());
        assertEquals(15, captor.getValue().unitPrice());
        assertEquals(false, captor.getValue().credit());
        assertEquals(99L, captor.getValue().eventId());
        assertEquals("market:2", captor.getValue().idempotencyKey());
    }

    @Test
    @DisplayName("craft.make：物品域未装配 → SKIPPED_NO_INVENTORY")
    void craftMakeWithoutPortIsSkipped() {
        ObjectNode body = mapper.createObjectNode()
                .put("recipeKey", "打柴刀").put("batches", 1);
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("craft.make", body, "craft:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_NO_INVENTORY, report.get(0).status());
    }

    @Test
    @DisplayName("craft.make：制作关闭 → SKIPPED_DISABLED（不抛异常，行为回 I2 之前）")
    void craftMakeDisabledIsSkipped() {
        CraftService craft = mock(CraftService.class);
        when(craft.enabled()).thenReturn(false);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "craftService", craft);
        ObjectNode body = mapper.createObjectNode()
                .put("recipeKey", "打柴刀").put("batches", 1);
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("craft.make", body, "craft:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_DISABLED, report.get(0).status());
        verify(craft, never()).start(any());
    }

    @Test
    @DisplayName("craft.make：装配制作后按幂等键开工，行动者/地点取上下文")
    void craftMakeDelegatesToService() {
        CraftService craft = mock(CraftService.class);
        when(craft.enabled()).thenReturn(true);
        when(craft.start(any(CraftStartRequest.class))).thenReturn(
                new CraftJobResult(11L, "打柴刀", "柴刀", 1, 1, 100_000L + 3600L, false));
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "craftService", craft);
        ObjectNode body = mapper.createObjectNode()
                .put("recipeKey", "打柴刀").put("batches", 2)
                .put("facilityRef", "facility:loc:9").put("locationId", 9L);
        applier.applyWithReport(List.of(new EffectOp("craft.make", body, "craft:2")), ctx(99L));

        ArgumentCaptor<CraftStartRequest> captor = ArgumentCaptor.forClass(CraftStartRequest.class);
        verify(craft).start(captor.capture());
        CraftStartRequest req = captor.getValue();
        assertEquals(7L, req.projectId());
        assertEquals("npc:1", req.actorRef());
        assertEquals("打柴刀", req.recipeKey());
        assertEquals(2, req.batches());
        assertEquals("facility:loc:9", req.facilityRef());
        assertEquals(9L, req.locationId());
        assertEquals(99L, req.eventId());
        assertEquals("craft:2", req.idempotencyKey());
    }

    @Test
    @DisplayName("未知 op 走闭集校验，失败下标 0")
    void unknownOpFails() {
        EffectApplyException e = assertThrows(EffectApplyException.class,
                () -> applier.apply(List.of(new EffectOp("world.delete", mapper.createObjectNode(), "x")), ctx(99L)));
        assertEquals(0, e.index());
    }

    @Test
    @DisplayName("第二个 op 失败：异常下标 1，便于写 EFFECT_FAILED:1")
    void secondOpFailureCarriesIndex() {
        ObjectNode bad = mapper.createObjectNode().put("factType", "route").put("factKey", "route:A-B");
        List<EffectOp> ops = List.of(
                new EffectOp("memory.add", mapper.createObjectNode()
                        .put("ref", "npc:1").put("content", "先记一笔"), "mem:1"),
                new EffectOp("fact.upsert", bad, "fact:1"));
        EffectApplyException e = assertThrows(EffectApplyException.class,
                () -> applier.apply(ops, ctx(99L)));
        assertEquals(1, e.index());
    }

    // ==================== item.equip / item.repair / combat.strike ====================

    @Test
    @DisplayName("I4 item.equip：物品域未装配 → SKIPPED_NO_INVENTORY")
    void itemEquipWithoutPortIsSkipped() {
        ObjectNode body = mapper.createObjectNode().put("itemKey", "柴刀").put("equipped", true);
        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("item.equip", body, "equip:1")), ctx(99L));
        assertEquals(EffectResult.SKIPPED_NO_INVENTORY, report.results().get(0).status());
    }

    @Test
    @DisplayName("I4 item.equip：损耗域关闭 → SKIPPED_DISABLED，不抛异常")
    void itemEquipDisabledIsSkipped() {
        EquipmentService equip = mock(EquipmentService.class);
        when(equip.enabled()).thenReturn(false);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "equipmentService", equip);
        ObjectNode body = mapper.createObjectNode().put("itemKey", "柴刀").put("equipped", true);

        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("item.equip", body, "equip:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_DISABLED, report.results().get(0).status());
        verify(equip, never()).equip(anyLong(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("I4 item.equip：装配后按槽位穿，行动者取上下文")
    void itemEquipDelegatesToService() {
        EquipmentService equip = mock(EquipmentService.class);
        when(equip.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "equipmentService", equip);
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "柴刀").put("slot", "hand").put("equipped", true);

        applier.applyWithReport(List.of(new EffectOp("item.equip", body, "equip:2")), ctx(99L));

        verify(equip).equip(7L, "npc:1", "hand", "柴刀", 1000L);
    }

    @Test
    @DisplayName("I4 item.equip：equipped=false 走脱下")
    void itemEquipFalseUnequips() {
        EquipmentService equip = mock(EquipmentService.class);
        when(equip.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "equipmentService", equip);
        ObjectNode body = mapper.createObjectNode()
                .put("itemKey", "柴刀").put("equipped", false);

        applier.applyWithReport(List.of(new EffectOp("item.equip", body, "equip:3")), ctx(99L));

        verify(equip).unequip(7L, "npc:1", null, 1000L);
    }

    @Test
    @DisplayName("I4 item.repair：损耗域关闭 → SKIPPED_DISABLED")
    void itemRepairDisabledIsSkipped() {
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        WearProperties wear = new WearProperties();
        wear.setEnabled(false);
        ReflectionTestUtils.setField(applier, "wearProperties", wear);
        ObjectNode body = mapper.createObjectNode().put("itemKey", "柴刀");

        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("item.repair", body, "repair:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_DISABLED, report.results().get(0).status());
        verify(inventoryPort, never()).restore(anyLong(), anyString(), anyString(), anyInt(),
                any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("I4 item.repair：装配后按幂等键修，点数取配置默认")
    void itemRepairDelegatesToService() {
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        WearProperties wear = new WearProperties();
        wear.setEnabled(true);
        ReflectionTestUtils.setField(applier, "wearProperties", wear);
        when(inventoryPort.restore(eq(7L), eq("npc:1"), eq("柴刀"), eq(25), eq(99L), eq(1000L), eq("repair:2")))
                .thenReturn(new DurabilityChange("柴刀", 40, 65, false, false));
        ObjectNode body = mapper.createObjectNode().put("itemKey", "柴刀");

        applier.applyWithReport(List.of(new EffectOp("item.repair", body, "repair:2")), ctx(99L));

        verify(inventoryPort).restore(7L, "npc:1", "柴刀", 25, 99L, 1000L, "repair:2");
    }

    @Test
    @DisplayName("I4 item.repair：东西不在身上 → 抛错（不静默成功）")
    void itemRepairMissingItemFails() {
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        WearProperties wear = new WearProperties();
        wear.setEnabled(true);
        ReflectionTestUtils.setField(applier, "wearProperties", wear);
        when(inventoryPort.restore(anyLong(), anyString(), anyString(), anyInt(),
                any(), anyLong(), anyString())).thenReturn(DurabilityChange.missing("柴刀"));
        ObjectNode body = mapper.createObjectNode().put("itemKey", "柴刀");

        assertThrows(EffectApplyException.class, () -> applier.apply(
                List.of(new EffectOp("item.repair", body, "repair:3")), ctx(99L)));
    }

    @Test
    @DisplayName("I4 combat.strike：损耗域关闭 → SKIPPED_DISABLED，不打人")
    void combatStrikeDisabledIsSkipped() {
        CombatService combat = mock(CombatService.class);
        when(combat.enabled()).thenReturn(false);
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "combatService", combat);
        ObjectNode body = mapper.createObjectNode().put("target", "npc:2").put("weaponKey", "柴刀");

        EffectReport report = applier.applyWithReport(List.of(
                new EffectOp("combat.strike", body, "strike:1")), ctx(99L));

        assertEquals(EffectResult.SKIPPED_DISABLED, report.results().get(0).status());
        verify(combat, never()).strike(any());
    }

    @Test
    @DisplayName("I4 combat.strike：装配后按幂等键结算，攻击者取上下文")
    void combatStrikeDelegatesToService() {
        CombatService combat = mock(CombatService.class);
        when(combat.enabled()).thenReturn(true);
        when(combat.strike(any(CombatRequest.class))).thenReturn(new CombatResult("npc:1", "npc:2",
                "柴刀", "柴刀", 8, 100, 92, 100, 98, false, false, true, null));
        ReflectionTestUtils.setField(applier, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(applier, "combatService", combat);
        ObjectNode body = mapper.createObjectNode().put("target", "npc:2").put("weaponKey", "柴刀");

        applier.applyWithReport(List.of(new EffectOp("combat.strike", body, "strike:2")), ctx(99L));

        ArgumentCaptor<CombatRequest> captor = ArgumentCaptor.forClass(CombatRequest.class);
        verify(combat).strike(captor.capture());
        assertEquals(7L, captor.getValue().projectId());
        assertEquals("npc:1", captor.getValue().attackerRef());
        assertEquals("npc:2", captor.getValue().targetRef());
        assertEquals("柴刀", captor.getValue().weaponItemKey());
        assertEquals("strike:2", captor.getValue().idempotencyKey());
    }

    private EffectContext ctx(Long eventId) {
        return new EffectContext(7L, eventId, "corr-1", "npc:1", 8L, "码头", 1000L);
    }
}
