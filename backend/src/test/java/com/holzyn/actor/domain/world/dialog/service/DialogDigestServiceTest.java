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

import com.holzyn.actor.domain.world.dialog.model.DialogClaim;
import com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.FactKnowledgeService;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话批量抽取的解析与事实边界测试。
 *
 * <p>核心不变量：承诺落 private contract 事实并给双方 knowledge；陈述未命中已知事实时
 * <b>绝不</b>创建/更新事实（谎话不能写成世界真相）。</p>
 */
class DialogDigestServiceTest {

    private WorldFactService factService;
    private WorldFactQuery factQuery;
    private FactKnowledgeService knowledgeService;
    private DialogDigestService service;

    @BeforeEach
    void setUp() {
        factService = mock(WorldFactService.class);
        factQuery = mock(WorldFactQuery.class);
        knowledgeService = mock(FactKnowledgeService.class);
        service = new DialogDigestService();
        ReflectionTestUtils.setField(service, "worldFactService", factService);
        ReflectionTestUtils.setField(service, "worldFactQuery", factQuery);
        ReflectionTestUtils.setField(service, "factKnowledgeService", knowledgeService);
    }

    @Test
    @DisplayName("解析：relations + claims 双数组，claim_type 统一大写，缺字段的条目丢弃")
    void parsesRelationsAndClaims() throws Exception {
        String json = """
                {
                  "relations": [
                    {"character_ref":"npc:2","affinity_delta":99,"trust_delta":-3,
                     "stage":"friend","note":"一起喝酒","state_summary":"放松",
                     "memory_points":["知道我喜欢麦酒"]}
                  ],
                  "claims": [
                    {"claim_type":"promise","speaker_ref":"npc:2","subject_ref":"npc:3",
                     "fact_key":"contract:1","name":"码头之约","state_text":"明早码头见",
                     "visibility":"private","due_game_minutes":1440,"confidence":95},
                    {"claim_type":"statement","speaker_ref":"npc:2","fact_key":"shop:9",
                     "state_text":"店关门了"},
                    {"claim_type":"statement","state_text":"没有 speaker，应丢弃"}
                  ]
                }
                """;

        DialogDigestService.DigestResult parsed = service.parse(json);

        assertEquals(1, parsed.relations().size());
        assertEquals(20, parsed.relations().get(0).affinityDelta(), "delta 钳制到 ±20");
        assertEquals(2, parsed.claims().size());
        assertEquals("PROMISE", parsed.claims().get(0).claimType());
        assertEquals("contract:1", parsed.claims().get(0).factKey());
        assertEquals(1440, parsed.claims().get(0).dueGameMinutes());
    }

    @Test
    @DisplayName("承诺：落 contract/private 事实，发起者 own、对方 told 各一条 knowledge")
    void promiseWritesPrivateContractAndBothKnowledges() {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(5L);
        fact.setFactKey("contract:1");
        when(factService.upsert(any(FactCommand.class)))
                .thenReturn(new FactChange(fact, 0, 1, "create"));
        DialogClaim claim = new DialogClaim("PROMISE", "npc:2", "npc:3", "contract:1",
                "码头之约", "明早码头见", "private", 1440, 95);

        int n = service.applyClaims(request(), List.of(claim), List.of());

        assertEquals(1, n);
        ArgumentCaptor<FactCommand> cmd = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(cmd.capture());
        assertEquals("contract", cmd.getValue().factType());
        assertEquals("private", cmd.getValue().visibility());
        assertEquals("明早码头见", cmd.getValue().stateText());
        verify(knowledgeService).learn(7L, 5L, "npc:2", "own", null, 100, null);
        verify(knowledgeService).learn(7L, 5L, "npc:3", "told", "npc:2", 100, null);
    }

    @Test
    @DisplayName("陈述未命中已知事实：不写事实（即使 AI 说是真的）")
    void unknownStatementNeverWritesFact() {
        when(factQuery.byKey(7L, "shop:9")).thenReturn(Optional.empty());
        DialogClaim claim = new DialogClaim("STATEMENT", "npc:2", null, "shop:9",
                "杂货铺", "店关门了", "public", null, 80);

        int n = service.applyClaims(request(), List.of(claim), List.of());

        assertEquals(0, n);
        verify(factService, never()).upsert(any(FactCommand.class));
    }

    @Test
    @DisplayName("陈述命中引擎权威事实：只写 knowledge，不覆写")
    void engineFactIsNotOverwritten() {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(6L);
        fact.setAuthority("engine");
        fact.setFactKey("shop:9");
        when(factQuery.byKey(7L, "shop:9")).thenReturn(Optional.of(fact));
        DialogClaim claim = new DialogClaim("STATEMENT", "npc:2", null, "shop:9",
                "杂货铺", "店关门了", null, null, 80);

        int n = service.applyClaims(request(), List.of(claim),
                List.of(new DialogDigestService.ActorWorldFactView("shop:9", "facility",
                        "杂货铺", "营业中", "public", "engine")));

        assertEquals(1, n);
        verify(factService, never()).upsert(any(FactCommand.class));
        verify(knowledgeService).learn(7L, 6L, "npc:3", "told", "npc:2", 80, null);
    }

    @Test
    @DisplayName("缺来源事件 ID：整批声明不落库（避免无因果的事实）")
    void claimsWithoutSourceEventAreSkipped() {
        DialogDigestRequest req = DialogDigestRequest.builder()
                .channel("dialogue").projectId(7L).userId(1L).sessionId(9L)
                .sessionKey("dialogue:9").participantRefs(List.of("npc:2", "npc:3"))
                .locationName("码头").gameSecond(1000L).lastMessageId(55L)
                .newDialogue(true).build();
        DialogClaim claim = new DialogClaim("PROMISE", "npc:2", null, "contract:1",
                "约定", "明早见", "private", null, 90);

        int n = service.applyClaims(req, List.of(claim), List.of());

        assertEquals(0, n);
        verify(factService, never()).upsert(any(FactCommand.class));
    }

    @Test
    @DisplayName("M3：有 EffectApplier 时承诺走统一效果入口，不再直写 WorldFactService")
    void promiseGoesThroughEffectApplierWhenWired() {
        com.holzyn.actor.domain.world.effect.service.EffectApplier applier =
                mock(com.holzyn.actor.domain.world.effect.service.EffectApplier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "effectApplier", applier);
        org.mockito.Mockito.when(applier.applyWithReport(any(), any())).thenReturn(
                new com.holzyn.actor.domain.world.effect.model.EffectReport(List.of(
                        com.holzyn.actor.domain.world.effect.model.EffectResult.applied(
                                0, "fact.upsert", 5L, null))));
        DialogClaim claim = new DialogClaim("PROMISE", "npc:2", "npc:3", "contract:1",
                "码头之约", "明早码头见", "private", 1440, 95);

        int n = service.applyClaims(request(), List.of(claim), List.of());

        assertEquals(1, n);
        verify(factService, never()).upsert(any(FactCommand.class));
        verify(applier).applyWithReport(any(), any());
        verify(knowledgeService).learn(7L, 5L, "npc:2", "own", null, 100, null);
    }

    @Test
    @DisplayName("M×I：GIFT 声明走效果批次，生成 inventory.transfer（说话者 → 收礼人）")
    void giftGoesThroughEffectApplier() {
        com.holzyn.actor.domain.world.effect.service.EffectApplier applier =
                mock(com.holzyn.actor.domain.world.effect.service.EffectApplier.class);
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        ReflectionTestUtils.setField(service, "effectApplier", applier);
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        when(applier.applyWithReport(any(), any())).thenReturn(
                new com.holzyn.actor.domain.world.effect.model.EffectReport(List.of(
                        com.holzyn.actor.domain.world.effect.model.EffectResult.applied(
                                0, "inventory.transfer", null, null))));
        DialogClaim claim = new DialogClaim("GIFT", "npc:2", "npc:3", "面包",
                "面包", "把面包递过去", null, null, null, 2);

        int n = service.applyClaims(request(), List.of(claim), List.of());

        assertEquals(1, n);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.holzyn.actor.domain.world.adjudicate.model.EffectOp>> ops =
                ArgumentCaptor.forClass(List.class);
        verify(applier).applyWithReport(ops.capture(), any());
        assertEquals(1, ops.getValue().size());
        assertEquals(com.holzyn.actor.domain.world.effect.model.EffectOps.INVENTORY_TRANSFER,
                ops.getValue().get(0).op());
        assertEquals("npc:2", ops.getValue().get(0).body().path("from").asText());
        assertEquals("npc:3", ops.getValue().get(0).body().path("to").asText());
        assertEquals(2, ops.getValue().get(0).body().path("qty").asInt());
        assertEquals("gift", ops.getValue().get(0).body().path("reason").asText());
        verify(factService, never()).upsert(any(FactCommand.class));
    }

    @Test
    @DisplayName("M×I：无效果入口时 GIFT 直连转移（reason=gift）")
    void giftDirectTransfer() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        DialogClaim claim = new DialogClaim("GIFT", "npc:2", "npc:3", "面包",
                "面包", "把面包递过去", null, null, null);

        int n = service.applyClaimsDirect(request(), List.of(claim), List.of());

        assertEquals(1, n);
        verify(port).transfer(eq(7L), eq("面包"), eq(1), eq("npc:2"), eq("npc:3"),
                eq("gift"), eq(88L), eq(1000L), anyString());
    }

    @Test
    @DisplayName("M×I：物品域关闭时 GIFT 不转移（关开关=回基线）")
    void giftSkippedWhenItemDisabled() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        com.holzyn.actor.domain.item.config.ItemProperties itemProperties =
                new com.holzyn.actor.domain.item.config.ItemProperties();
        itemProperties.setEnabled(false);
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "itemProperties", itemProperties);
        DialogClaim claim = new DialogClaim("GIFT", "npc:2", "npc:3", "面包",
                "面包", "把面包递过去", null, null, null);

        int n = service.applyClaimsDirect(request(), List.of(claim), List.of());

        assertEquals(0, n);
        verify(port, never()).transfer(any(), anyString(), anyInt(), any(), any(), any(), any(),
                anyLong(), any());
    }

    @Test
    @DisplayName("M×I：GIFT 的 qty 会被解析（缺省=1）")
    void giftQtyParsed() throws Exception {
        DialogDigestService.DigestResult parsed = service.parse("""
                {"relations":[],"claims":[
                  {"claim_type":"gift","speaker_ref":"npc:2","subject_ref":"npc:3",
                   "fact_key":"面包","state_text":"递过去","qty":3}
                ]}
                """);
        assertEquals(1, parsed.claims().size());
        assertEquals("GIFT", parsed.claims().get(0).claimType());
        assertEquals(3, parsed.claims().get(0).qty());
        assertTrue(parsed.claims().get(0).gift());
    }

    private DialogDigestRequest request() {
        return DialogDigestRequest.builder()
                .channel("dialogue").projectId(7L).userId(1L).sessionId(9L)
                .sessionKey("dialogue:9").participantRefs(List.of("npc:2", "npc:3"))
                .locationName("码头").gameSecond(1000L).eventId(88L)
                .lastMessageId(55L).newDialogue(true).build();
    }
}
