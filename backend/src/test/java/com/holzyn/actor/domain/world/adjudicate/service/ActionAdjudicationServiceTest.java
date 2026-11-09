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

package com.holzyn.actor.domain.world.adjudicate.service;

import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 裁决入口：开关透传、规则优先、UNSURE 按重要度分流、AI 失败降级。
 */
class ActionAdjudicationServiceTest {

    private ActionAdjudicationService service;
    private AgentActionService actionService;
    private ActionAdjudicator adjudicator;
    private AdjudicationAiService aiService;
    private WorldLoopProperties props;

    @BeforeEach
    void setUp() {
        service = new ActionAdjudicationService();
        actionService = mock(AgentActionService.class);
        adjudicator = mock(ActionAdjudicator.class);
        aiService = mock(AdjudicationAiService.class);
        props = new WorldLoopProperties();
        props.setEnabled(true);

        ActorCharacterRepository characters = mock(ActorCharacterRepository.class);
        ActorCharacter actor = new ActorCharacter();
        actor.setId(10L);
        actor.setProjectId(1L);
        actor.setName("阿禾");
        actor.setLocation("集市");
        actor.setImportance(3);
        when(characters.findById(10L)).thenReturn(Optional.of(actor));
        when(characters.findByProjectIdAndName(any(), any())).thenReturn(List.of());

        WorldLocationRepository locations = mock(WorldLocationRepository.class);
        com.holzyn.actor.domain.world.entity.ActorWorldLocation loc =
                new com.holzyn.actor.domain.world.entity.ActorWorldLocation();
        loc.setId(99L);
        loc.setName("码头");
        when(locations.findByProjectId(1L)).thenReturn(List.of(loc));

        WorldFactQuery facts = mock(WorldFactQuery.class);
        when(facts.byKey(any(), any())).thenReturn(Optional.empty());
        when(facts.knownBy(any(), any(), anyLong(), anyInt())).thenReturn(List.of());

        NpcStateService states = mock(NpcStateService.class);
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        st.setEnergy(80);
        when(states.stateFor(1L, "npc:10")).thenReturn(st);

        com.holzyn.actor.domain.world.service.WorldClockService clock =
                mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        when(clock.currentEngineGameSecond(1L)).thenReturn(1000L);

        ReflectionTestUtils.setField(service, "agentActionService", actionService);
        ReflectionTestUtils.setField(service, "intentClassifier", new IntentClassifier());
        ReflectionTestUtils.setField(service, "adjudicator", adjudicator);
        ReflectionTestUtils.setField(service, "aiService", aiService);
        ReflectionTestUtils.setField(service, "properties", props);
        ReflectionTestUtils.setField(service, "characterRepository", characters);
        ReflectionTestUtils.setField(service, "locationRepository", locations);
        ReflectionTestUtils.setField(service, "worldFactQuery", facts);
        ReflectionTestUtils.setField(service, "npcStateService", states);
        ReflectionTestUtils.setField(service, "worldClockService", clock);
    }

    @Test
    @DisplayName("总开关关：executeGated 直接透传旧 execute，不裁决不写四列")
    void disabledPassesThrough() {
        props.setEnabled(false);
        ActorActionPlan plan = plan();
        service.executeGated(plan);
        verify(actionService).execute(plan);
        verify(actionService, never()).executeWithVerdict(any(), any());
    }

    @Test
    @DisplayName("裁决段关：即使总开关开也不裁决")
    void adjudicateSectionDisabledPassesThrough() {
        props.getAdjudicate().setEnabled(false);
        ActorActionPlan plan = plan();
        service.executeGated(plan);
        verify(actionService).execute(plan);
        verify(adjudicator, never()).adjudicate(any());
    }

    @Test
    @DisplayName("规则 FAIL 原样交给执行层（取消+失败事件）")
    void ruleFailGoesToExecuteWithVerdict() {
        ActionVerdict fail = ActionVerdict.fail("R-TRAVEL-01", "ROUTE_BLOCKED", "路封了。");
        when(adjudicator.adjudicate(any())).thenReturn(fail);
        ActorActionPlan plan = plan();
        service.executeGated(plan);
        verify(actionService, never()).execute(plan);
        verify(actionService).executeWithVerdict(plan, fail);
    }

    @Test
    @DisplayName("规则 UNSURE + 重要度 3 < 4 → PARTIAL LOW_IMPORTANCE_PASS，不调 AI")
    void unsureLowImportancePassesWithoutAi() {
        when(adjudicator.adjudicate(any())).thenReturn(ActionVerdict.unsure("R-TRADE-01", "DATA_UNAVAILABLE", "没数据。"));
        ActorActionPlan plan = plan();
        service.executeGated(plan);
        verify(aiService, never()).adjudicate(any());
        verify(actionService).executeWithVerdict(eq(plan), any(ActionVerdict.class));
        ActionVerdict captured = captureVerdict(plan);
        assertEquals(Verdict.PARTIAL, captured.verdict());
        assertEquals("LOW_IMPORTANCE_PASS", captured.reasonCode());
    }

    @Test
    @DisplayName("规则 UNSURE + 重要度 5 ≥ 4 → 调一次 AI，AI 结论原样落地")
    void unsureHighImportanceCallsAi() {
        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"move_to\",\"action\":\"去码头\",\"target\":\"码头\",\"urgency\":5}");
        when(adjudicator.adjudicate(any())).thenReturn(ActionVerdict.unsure("R-TRADE-01", "DATA_UNAVAILABLE", "没数据。"));
        ActionVerdict ai = ActionVerdict.of(Verdict.SUCCESS, "OK", "成了。", 5, List.of(), "ai");
        when(aiService.adjudicate(any())).thenReturn(ai);
        service.executeGated(plan);
        verify(aiService).adjudicate(any());
        verify(actionService).executeWithVerdict(plan, ai);
    }

    @Test
    @DisplayName("AI 不可用返回 null → PARTIAL AI_UNAVAILABLE，不 FAIL")
    void aiNullFallsBackToPartial() {
        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"move_to\",\"action\":\"去码头\",\"target\":\"码头\",\"urgency\":5}");
        when(adjudicator.adjudicate(any())).thenReturn(ActionVerdict.unsure("R-TRADE-01", "DATA_UNAVAILABLE", "没数据。"));
        when(aiService.adjudicate(any())).thenReturn(null);
        service.executeGated(plan);
        ActionVerdict captured = captureVerdict(plan);
        assertEquals(Verdict.PARTIAL, captured.verdict());
        assertEquals("AI_UNAVAILABLE", captured.reasonCode());
    }

    @Test
    @DisplayName("上下文组装：意图/目标地点/精力/重要度正确进入裁决器")
    void contextCarriesCoreFacts() {
        when(adjudicator.adjudicate(any())).thenAnswer(inv -> {
            com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext c = inv.getArgument(0);
            assertEquals(IntentKind.TRAVEL, c.getIntent());
            assertEquals("码头", c.getTargetName());
            assertEquals(Boolean.TRUE, c.getTargetLocationExists());
            assertEquals(80, c.getEnergy());
            assertEquals(2, c.getImportance());
            assertEquals(1000L, c.getGameSecond());
            return ActionVerdict.success("R-TRAVEL-01");
        });
        service.executeGated(plan());
        verify(adjudicator).adjudicate(any());
    }

    @Test
    @DisplayName("TALK 双方 location 均为空 → targetPresent=null（未知放行，不误判 NOT_PRESENT）")
    void talkWithUnknownLocationsPassesAsUnknown() {
        ActorCharacter target = new ActorCharacter();
        target.setId(11L);
        target.setProjectId(1L);
        target.setName("阿苔");
        target.setLocation(null);
        when(((ActorCharacterRepository) ReflectionTestUtils.getField(service, "characterRepository"))
                .findByProjectIdAndName(1L, "阿苔")).thenReturn(List.of(target));
        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"speak_to\",\"action\":\"找阿苔说话\",\"target\":\"阿苔\"}");
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);
        org.junit.jupiter.api.Assertions.assertNull(ctx.getTargetPresent());
        assertEquals("npc:11", ctx.getTargetRef());
        // 用真实规则确认 null 不会落到 NOT_PRESENT 分支
        DefaultActionAdjudicator real = new DefaultActionAdjudicator();
        assertEquals(Verdict.SUCCESS, real.adjudicate(ctx).verdict());
    }

    @Test
    @DisplayName("I1 接线：装配 InventoryPort 时裁决上下文带上真实物品清单")
    void buildContextReadsInventory() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "npc:10")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("grain", "麦子", "food", 5, 100, null, null)));
        ReflectionTestUtils.setField(service, "inventoryPort", port);

        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan());

        org.junit.jupiter.api.Assertions.assertTrue(ctx.isInventoryKnown(), "端口已装配应为已知");
        assertEquals(1, ctx.getInventory().size());
        assertEquals("麦子", ctx.getInventory().get(0).name());
        org.junit.jupiter.api.Assertions.assertFalse(ctx.isCounterpartyStockKnown(), "I1 没有货架数据");
        org.junit.jupiter.api.Assertions.assertFalse(ctx.isRecipeKnown(), "I1 没有配方数据");
    }

    @Test
    @DisplayName("I1 未装配物品域：裁决上下文维持 inventoryKnown=false（行为回 M2）")
    void buildContextWithoutInventoryPortStaysUnknown() {
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan());
        org.junit.jupiter.api.Assertions.assertFalse(ctx.isInventoryKnown());
        org.junit.jupiter.api.Assertions.assertTrue(ctx.getInventory().isEmpty());
    }

    @Test
    @DisplayName("I2 接线：TRADE 时上下文带柜台库存/单价/资金/买卖双方")
    void buildContextFillsTradeContext() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "loc:99")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("bread", "面包", "food", 3, 100, null, null)));
        when(port.quantityOf(1L, "npc:10", "coin")).thenReturn(100);
        com.holzyn.actor.domain.market.service.MarketService market =
                mock(com.holzyn.actor.domain.market.service.MarketService.class);
        when(market.enabled()).thenReturn(true);
        when(market.currencyKey()).thenReturn("coin");
        when(market.quote(1L, "loc:99", "bread", 1000L)).thenReturn(
                new com.holzyn.actor.domain.market.model.MarketQuote("loc:99", "bread", 10, 15, 3, 0, 1.5, 1000L));
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "marketService", market);

        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"start_action\",\"action\":\"去米行买面包\",\"target\":\"码头\","
                + "\"params\":{\"itemKey\":\"bread\",\"qty\":2}}");
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);

        assertEquals(IntentKind.TRADE, ctx.getIntent());
        assertEquals("loc:99", ctx.getSellerRef(), "买入时柜台=目标地点");
        assertEquals("npc:10", ctx.getBuyerRef(), "买入时买家=行动者");
        assertEquals("bread", ctx.getTradeItemKey());
        assertEquals(2, ctx.getTradeQty());
        assertEquals(15, ctx.getUnitPrice());
        assertEquals(100, ctx.getFunds());
        org.junit.jupiter.api.Assertions.assertTrue(ctx.isCounterpartyStockKnown());
        assertEquals(3, com.holzyn.actor.domain.item.model.ItemView.totalOf(ctx.getCounterpartyStock(), "bread"));
    }

    @Test
    @DisplayName("I2 兜底：决策只写自然语言（买两件面包）也能认出货与件数并结算")
    void buildContextResolvesItemFromActionText() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "loc:99")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("bread", "面包", "food", 10, 100, null, null),
                new com.holzyn.actor.domain.item.model.ItemView("flour", "面粉", "material", 5, 100, null, null)));
        when(port.quantityOf(1L, "npc:10", "coin")).thenReturn(100);
        com.holzyn.actor.domain.market.service.MarketService market =
                mock(com.holzyn.actor.domain.market.service.MarketService.class);
        when(market.enabled()).thenReturn(true);
        when(market.currencyKey()).thenReturn("coin");
        when(market.quote(1L, "loc:99", "bread", 1000L)).thenReturn(
                new com.holzyn.actor.domain.market.model.MarketQuote("loc:99", "bread", 10, 15, 10, 0, 1.5, 1000L));
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "marketService", market);

        ActorActionPlan plan = plan();
        // 真实模型输出形状：只有自然语言，没有 params.itemKey
        plan.setActionJson("{\"actionType\":\"start_action\",\"type\":\"custom\","
                + "\"action\":\"去米行买两件面包，再带一袋面粉回去\",\"target\":\"码头\",\"params\":{}}");
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);

        assertEquals(IntentKind.TRADE, ctx.getIntent());
        assertEquals("bread", ctx.getTradeItemKey(), "应从行动文本认出面包");
        assertEquals(2, ctx.getTradeQty(), "「两件」应解析成 2");
        assertEquals(15, ctx.getUnitPrice());

        // 最长名字优先：不能把「买面粉」认成买「面」
        ActorActionPlan plan2 = plan();
        plan2.setActionJson("{\"actionType\":\"start_action\",\"type\":\"custom\","
                + "\"action\":\"去米行买一袋面粉\",\"target\":\"码头\",\"params\":{}}");
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx2 = service.buildContext(plan2);
        assertEquals("flour", ctx2.getTradeItemKey());
        assertEquals(1, ctx2.getTradeQty());
    }

    @Test
    @DisplayName("I2 方向：卖出类措辞时卖家=行动者、买家=目标")
    void buildContextFlipsSidesWhenActorSells() {
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "npc:10")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("grain", "麦子", "food", 4, 100, null, null)));
        when(port.quantityOf(1L, "loc:99", "coin")).thenReturn(50);
        com.holzyn.actor.domain.market.service.MarketService market =
                mock(com.holzyn.actor.domain.market.service.MarketService.class);
        when(market.enabled()).thenReturn(true);
        when(market.currencyKey()).thenReturn("coin");
        when(market.quote(1L, "npc:10", "grain", 1000L)).thenReturn(
                new com.holzyn.actor.domain.market.model.MarketQuote("npc:10", "grain", 10, 12, 4, 0, 1.2, 1000L));
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "marketService", market);

        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"start_action\",\"action\":\"把麦子卖给码头\",\"target\":\"码头\","
                + "\"params\":{\"itemKey\":\"grain\",\"qty\":1}}");
        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);

        assertEquals(IntentKind.TRADE, ctx.getIntent());
        assertEquals("npc:10", ctx.getSellerRef(), "卖出时卖家=行动者");
        assertEquals("loc:99", ctx.getBuyerRef(), "卖出时买家=目标地点");
        assertEquals(12, ctx.getUnitPrice());
        assertEquals(50, ctx.getFunds());
    }

    @Test
    @DisplayName("生产形状回归：actionType=start_action + type=rest → 意图 REST 且真的扣掉随身口粮")
    void productionPlanShapeYieldsRestConsume() {
        // 真实计划 JSON 的形状（整世界长跑从 actor_plan.action_json 解出来的原文）：
        // 语义分类在 type，动作类型在 actionType。旧实现只读 type → "rest" 不在词表 → OTHER，
        // 于是裁决器的 R-REST-01 永远不跑，角色「说吃了」但背包一件不少。
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "npc:10")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("面包", "面包", "food", 2, 100, 100, null)));
        ReflectionTestUtils.setField(service, "inventoryPort", port);

        ActorActionPlan plan = plan();
        plan.setActionJson("{\"actionType\":\"start_action\",\"actionDetail\":\"吃随身的面包充饥\","
                + "\"type\":\"rest\",\"action\":\"吃随身的面包充饥\",\"target\":\"\",\"params\":{}}");

        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);
        assertEquals(IntentKind.REST, ctx.getIntent(), "生产形状的 rest 计划必须分类为 REST");

        ActionVerdict v = new DefaultActionAdjudicator().adjudicate(ctx);
        assertEquals(Verdict.SUCCESS, v.verdict());
        assertEquals(1, v.effects().size(), "点名了随身的面包 → 必须产生一条消耗效果");
        assertEquals(com.holzyn.actor.domain.world.effect.model.EffectOps.INVENTORY_CONSUME,
                v.effects().get(0).op());
        assertEquals("面包", v.effects().get(0).body().path("itemKey").asText());
    }

    @Test
    @DisplayName("旧形态计划（只有 type=rest、没有 actionType）：文本里要买 → 仍按 TRADE 认货")
    void legacyTypePlanFallsBackToActionText() {
        // 空间候选/发育骨架产出的计划长这样：只有语义 type，没有 actionType。
        // 旧口径下 "rest" 不在动作词表 → OTHER → R-DEFAULT 放行，于是
        // 「在集市口歇脚，等对方买完再进镇买吃食」这类计划永远不会成交
        // （复测：29 条含「买」的决策 0 笔成交）。
        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "loc:99")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("bread", "面包", "food", 10, 100, null, null)));
        when(port.quantityOf(1L, "npc:10", "coin")).thenReturn(20);
        com.holzyn.actor.domain.market.service.MarketService market =
                mock(com.holzyn.actor.domain.market.service.MarketService.class);
        when(market.enabled()).thenReturn(true);
        when(market.currencyKey()).thenReturn("coin");
        when(market.quote(1L, "loc:99", "bread", 1000L)).thenReturn(
                new com.holzyn.actor.domain.market.model.MarketQuote("loc:99", "bread", 10, 10, 10, 0, 1.0, 1000L));
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "marketService", market);

        ActorActionPlan plan = plan();
        plan.setActionJson("{\"type\":\"rest\",\"action\":\"在集市口歇脚，等对方买完再进镇买吃食\","
                + "\"target\":\"码头\",\"params\":{\"move\":{\"to\":\"码头\"}}}");

        com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext ctx = service.buildContext(plan);

        assertEquals(IntentKind.TRADE, ctx.getIntent(), "词表分类落空时必须再按自由文本认一次");
        assertEquals("bread", ctx.getTradeItemKey(), "「吃食」→ 柜台上的 food 类货物");
        assertEquals("loc:99", ctx.getSellerRef(), "买入时柜台=目标地点");
    }

    @Test
    @DisplayName("效果落地失败：外层捕获并写 EFFECT_FAILED:{i}，不重复走旧 execute")
    void effectFailureMarksPlan() {
        com.holzyn.actor.domain.world.effect.service.ActionEffectService effects =
                mock(com.holzyn.actor.domain.world.effect.service.ActionEffectService.class);
        org.mockito.Mockito.doThrow(new com.holzyn.actor.domain.world.effect.model.EffectApplyException(
                1, "relation.delta", new IllegalStateException("boom")))
                .when(effects).execute(any(), any());
        ReflectionTestUtils.setField(service, "actionEffectService", effects);

        service.executeGated(plan());

        verify(actionService).markEffectFailed(7L, 1);
        verify(actionService, never()).executeWithVerdict(any(), any());
    }

    private static ActorActionPlan plan() {
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(7L);
        plan.setCharacterId(10L);
        plan.setStatus("planned");
        plan.setActionJson("{\"type\":\"move_to\",\"action\":\"去码头\",\"target\":\"码头\",\"urgency\":2}");
        return plan;
    }

    /** 从 Mockito 调用里抓 executeWithVerdict 的第二个参数（避免用 ArgumentCaptor 的泛型噪声）。 */
    private ActionVerdict captureVerdict(ActorActionPlan ignored) {
        org.mockito.ArgumentCaptor<ActionVerdict> captor =
                org.mockito.ArgumentCaptor.forClass(ActionVerdict.class);
        verify(actionService).executeWithVerdict(eq(ignored), captor.capture());
        return captor.getValue();
    }
}
