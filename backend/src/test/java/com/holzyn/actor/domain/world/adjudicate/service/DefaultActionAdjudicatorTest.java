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

import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.model.RecipeInput;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.effect.model.EffectOps;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10 条确定性规则（每条 ≥2 例）：明确否定才 FAIL，无记录 fail-open。
 */
class DefaultActionAdjudicatorTest {

    private final DefaultActionAdjudicator adjudicator = new DefaultActionAdjudicator();

    // ==================== TRAVEL ====================

    @Test
    @DisplayName("R-TRAVEL-01 目标地点不存在 → FAIL TARGET_NOT_FOUND")
    void travelFailsWhenTargetMissing() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRAVEL).targetLocationExists(false).build());
        assertVerdict(v, Verdict.FAIL, "TARGET_NOT_FOUND");
    }

    @Test
    @DisplayName("R-TRAVEL-01 路线 blocked → FAIL ROUTE_BLOCKED")
    void travelFailsWhenRouteBlocked() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRAVEL)
                .targetLocationExists(true).routeFact(fact("route:a-b", "{\"state\":\"blocked\"}")).build());
        assertVerdict(v, Verdict.FAIL, "ROUTE_BLOCKED");
    }

    @Test
    @DisplayName("R-TRAVEL-01 路线 danger → FAIL ROUTE_BLOCKED")
    void travelFailsWhenRouteDanger() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRAVEL)
                .targetLocationExists(true).routeFact(fact("route:a-b", "{\"state\":\"danger\"}")).build());
        assertVerdict(v, Verdict.FAIL, "ROUTE_BLOCKED");
    }

    @Test
    @DisplayName("R-TRAVEL-02 体力 19 → FAIL TOO_TIRED")
    void travelFailsWhenTooTired() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRAVEL)
                .targetLocationExists(true).energy(19).build());
        assertVerdict(v, Verdict.FAIL, "TOO_TIRED");
    }

    @Test
    @DisplayName("TRAVEL 目标存在、路线通畅、体力够 → SUCCESS")
    void travelSucceeds() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRAVEL)
                .targetLocationExists(true).energy(80).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    // ==================== TALK ====================

    @Test
    @DisplayName("R-TALK-01 目标不在场 → FAIL NOT_PRESENT")
    void talkFailsWhenAbsent() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TALK).targetPresent(false).build());
        assertVerdict(v, Verdict.FAIL, "NOT_PRESENT");
    }

    @Test
    @DisplayName("R-TALK-02 affinity=-20 → FAIL REFUSED")
    void talkFailsAtAffinityThreshold() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TALK)
                .targetPresent(true).relationToTarget(-20).energy(50).build());
        assertVerdict(v, Verdict.FAIL, "REFUSED");
    }

    @Test
    @DisplayName("R-TALK-02 affinity=-19 且精力 50 → SUCCESS（阈值下一点不拒绝）")
    void talkSucceedsJustAboveAffinityThreshold() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TALK)
                .targetPresent(true).relationToTarget(-19).energy(50).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    @Test
    @DisplayName("R-TALK-02 精力 9 → FAIL REFUSED")
    void talkFailsWhenTooExhausted() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TALK)
                .targetPresent(true).relationToTarget(0).energy(9).build());
        assertVerdict(v, Verdict.FAIL, "REFUSED");
    }

    // ==================== TRADE ====================

    @Test
    @DisplayName("R-TRADE-01 物品数据不可用 → UNSURE DATA_UNAVAILABLE")
    void tradeUnsureWithoutInventoryData() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRADE).inventoryKnown(false).build());
        assertVerdict(v, Verdict.UNSURE, "DATA_UNAVAILABLE");
    }

    @Test
    @DisplayName("R-TRADE-01 没有柜台数据 → UNSURE STOCK_UNKNOWN（不把空背包当没货）")
    void tradeUnsureWithoutCounterpartyStock() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRADE)
                .inventoryKnown(true).inventory(items("干粮")).build());
        assertVerdict(v, Verdict.UNSURE, "STOCK_UNKNOWN");
    }

    @Test
    @DisplayName("R-TRADE-01 柜台已知且为空 → FAIL NO_STOCK")
    void tradeFailsWithoutStock() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.TRADE)
                .inventoryKnown(true).counterpartyStockKnown(true).counterpartyStock(List.of()).build());
        assertVerdict(v, Verdict.FAIL, "NO_STOCK");
    }

    @Test
    @DisplayName("R-TRADE-01 柜台没有这件货 → FAIL NO_STOCK")
    void tradeFailsWhenItemMissing() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx()
                .tradeItemKey("bread").counterpartyStock(items("干粮")).build());
        assertVerdict(v, Verdict.FAIL, "NO_STOCK");
    }

    @Test
    @DisplayName("R-TRADE-01 钱不够 → FAIL NO_FUNDS（钱是物品 coin）")
    void tradeFailsWhenNoFunds() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx()
                .unitPrice(15).tradeQty(2).funds(29).build());
        assertVerdict(v, Verdict.FAIL, "NO_FUNDS");
    }

    @Test
    @DisplayName("R-TRADE-01 现金够 → SUCCESS，并产出 market.trade 效果（货+钱一次结算）")
    void tradeSucceedsAndEmitsMarketEffect() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx()
                .unitPrice(15).tradeQty(2).funds(30).build());

        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-TRADE-01", v.adjudicatedBy());
        assertEquals(1, v.effects().size());
        assertEquals(EffectOps.MARKET_TRADE, v.effects().get(0).op());
        assertEquals("loc:9", v.effects().get(0).body().path("seller").asText());
        assertEquals("bread", v.effects().get(0).body().path("itemKey").asText());
        assertEquals(2, v.effects().get(0).body().path("qty").asInt());
        assertEquals(15, v.effects().get(0).body().path("unitPrice").asInt());
        assertEquals(false, v.effects().get(0).body().path("credit").asBoolean());
    }

    @Test
    @DisplayName("R-TRADE-02 赊账看买方声望：-21 → FAIL DISTRUSTED")
    void creditFailsWhenBuyerDistrusted() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx()
                .credit(true).buyerReputation(-21).build());
        assertVerdict(v, Verdict.FAIL, "DISTRUSTED");
        assertEquals("rule:R-TRADE-02", v.adjudicatedBy());
    }

    @Test
    @DisplayName("R-TRADE-02 买方声望 -20（阈值上）→ 赊账成交，效果 credit=true")
    void creditSucceedsAtThreshold() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx()
                .credit(true).buyerReputation(-20).build());

        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(true, v.effects().get(0).body().path("credit").asBoolean());
    }

    @Test
    @DisplayName("决策没写 itemKey → SUCCESS 但不结算（不臆断买哪件）")
    void tradeWithoutItemKeyDoesNotSettle() {
        ActionVerdict v = adjudicator.adjudicate(tradeCtx().tradeItemKey(null).build());

        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(0, v.effects().size(), "没写具体货物时不能凭空生成成交");
    }

    @Test
    @DisplayName("现金交易缺报价/缺资金数据 → UNSURE（不误判 NO_FUNDS）")
    void tradeUnsureWhenPriceOrFundsUnknown() {
        assertVerdict(adjudicator.adjudicate(tradeCtx().unitPrice(null).funds(100).build()),
                Verdict.UNSURE, "PRICE_UNKNOWN");
        assertVerdict(adjudicator.adjudicate(tradeCtx().unitPrice(15).funds(null).build()),
                Verdict.UNSURE, "FUNDS_UNKNOWN");
    }

    // ==================== CRAFT ====================

    @Test
    @DisplayName("R-CRAFT-01 无物品数据 → UNSURE DATA_UNAVAILABLE")
    void craftUnsureWithoutInventoryData() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT).inventoryKnown(false).build());
        assertVerdict(v, Verdict.UNSURE, "DATA_UNAVAILABLE");
    }

    @Test
    @DisplayName("R-CRAFT-01 设施关门 → FAIL NO_FACILITY（配方未知也先判设施）")
    void craftFailsWhenFacilityClosed() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).inventory(items("铁")).facilityFact(fact("facility:loc:1", "{\"open\":false}"))
                .build());
        assertVerdict(v, Verdict.FAIL, "NO_FACILITY");
    }

    @Test
    @DisplayName("R-CRAFT-01 制作域未启用 → UNSURE RECIPE_UNKNOWN（不把空背包当缺材料）")
    void craftUnsureWhenCraftDisabled() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).inventory(List.of()).build());
        assertVerdict(v, Verdict.UNSURE, "RECIPE_UNKNOWN");
    }

    @Test
    @DisplayName("R-CRAFT-01 制作域开着但配方解析不出 → UNSURE RECIPE_UNKNOWN（不臆断）")
    void craftUnsureWhenRecipeUnknown() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).craftBatches(1).inventory(items("铁")).build());
        assertVerdict(v, Verdict.UNSURE, "RECIPE_UNKNOWN");
    }

    @Test
    @DisplayName("R-CRAFT-01 材料不齐 → FAIL NO_MATERIAL，且可见文本说出缺什么")
    void craftFailsWithoutMaterial() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 2), input("木头", 1)))
                .craftBatches(1).craftFacilityRef("facility:loc:9").craftFacilityType("铁匠炉")
                .craftFacilityName("铁匠铺")
                .inventory(items("铁锭")).build());
        assertVerdict(v, Verdict.FAIL, "NO_MATERIAL");
        assertTrue(v.observedText().contains("铁锭"), "失败文本要说出缺什么：" + v.observedText());
    }

    @Test
    @DisplayName("R-CRAFT-01 配方要炉子但现场没有 → FAIL NO_FACILITY")
    void craftFailsWhenFacilityMissing() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 1)))
                .craftBatches(1).inventory(items("铁锭")).build());
        assertVerdict(v, Verdict.FAIL, "NO_FACILITY");
    }

    @Test
    @DisplayName("R-CRAFT-01 设施类型不对（灶台做不了铁匠活）→ FAIL NO_FACILITY")
    void craftFailsWhenFacilityTypeMismatch() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 1)))
                .craftBatches(1).craftFacilityRef("facility:loc:9").craftFacilityType("灶台")
                .craftFacilityName("张家厨房")
                .inventory(items("铁锭")).build());
        assertVerdict(v, Verdict.FAIL, "NO_FACILITY");
    }

    @Test
    @DisplayName("R-CRAFT-01 设施被别人占着 → FAIL OCCUPIED（复用 R-USE-01 词汇）")
    void craftFailsWhenFacilityOccupied() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 1)))
                .craftBatches(1).craftFacilityRef("facility:loc:9").craftFacilityType("铁匠炉")
                .craftFacilityName("铁匠铺").craftOccupant("npc:77")
                .inventory(items("铁锭")).build());
        assertVerdict(v, Verdict.FAIL, "OCCUPIED");
    }

    @Test
    @DisplayName("R-CRAFT-01 手上还压着活 → FAIL BUSY（一个人不能同时打两把刀）")
    void craftFailsWhenBusy() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 1)))
                .craftBatches(1).craftFacilityRef("facility:loc:9").craftFacilityType("铁匠炉")
                .craftFacilityName("铁匠铺").craftBusyRecipeKey("锯木板")
                .inventory(items("铁锭")).build());
        assertVerdict(v, Verdict.FAIL, "BUSY");
    }

    @Test
    @DisplayName("CRAFT 材料齐、设施对、不忙 → SUCCESS 且发出 craft.make")
    void craftSucceeds() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("打柴刀", "柴刀", "铁匠炉", 60, input("铁锭", 1), input("木头", 1)))
                .craftBatches(1).craftFacilityRef("facility:loc:9").craftFacilityType("铁匠炉")
                .craftFacilityName("铁匠铺").craftLocationId(9L).locationName("青峰镇")
                .actor(actor(12L))
                .inventory(items("铁锭", "木头")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(1, v.effects().size(), "必须恰好发出 1 条效果");
        assertEquals("craft.make", v.effects().get(0).op());
        assertEquals("打柴刀", v.effects().get(0).body().path("recipeKey").asText());
        assertEquals("facility:loc:9", v.effects().get(0).body().path("facilityRef").asText());
        assertEquals("npc:12", v.effects().get(0).body().path("actor").asText());
    }

    @Test
    @DisplayName("CRAFT 不要求设施时（编草绳）没设施也能做")
    void craftSucceedsWithoutFacility() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.CRAFT)
                .inventoryKnown(true).craftDataKnown(true).recipeKnown(true)
                .recipe(recipe("编草绳", "草绳", null, 15, input("稻草", 2)))
                .craftBatches(1).inventory(stock("稻草", 2)).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    // ==================== USE ====================

    @Test
    @DisplayName("R-USE-01 没有设施记录 → SUCCESS（未知放行）")
    void useSucceedsWithoutFacility() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.USE).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    @Test
    @DisplayName("R-USE-01 设施关门 → FAIL CLOSED")
    void useFailsWhenClosed() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.USE)
                .facilityFact(fact("facility:loc:1", "{\"open\":false}")).build());
        assertVerdict(v, Verdict.FAIL, "CLOSED");
    }

    @Test
    @DisplayName("R-USE-01 设施被占用 → FAIL OCCUPIED")
    void useFailsWhenOccupied() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.USE)
                .facilityFact(fact("facility:loc:1", "{\"open\":true,\"occupied\":true}")).build());
        assertVerdict(v, Verdict.FAIL, "OCCUPIED");
    }

    @Test
    @DisplayName("USE 设施开且空闲 → SUCCESS")
    void useSucceedsWhenOpen() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.USE)
                .facilityFact(fact("facility:loc:1", "{\"open\":true,\"occupied\":false}")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    // ==================== ATTACK ====================

    @Test
    @DisplayName("R-ATTACK-01 无物品数据 → UNSURE DATA_UNAVAILABLE")
    void attackUnsureWithoutInventoryData() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).inventoryKnown(false).build());
        assertVerdict(v, Verdict.UNSURE, "DATA_UNAVAILABLE");
    }

    @Test
    @DisplayName("R-ATTACK-01 没有武器 → FAIL NO_WEAPON")
    void attackFailsWithoutWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK)
                .inventoryKnown(true).inventory(items("干粮")).build());
        assertVerdict(v, Verdict.FAIL, "NO_WEAPON");
    }

    @Test
    @DisplayName("ATTACK 带刀 → SUCCESS")
    void attackSucceedsWithWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK)
                .inventoryKnown(true).inventory(items("短刀")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
    }

    // ==================== GIVE ====================

    @Test
    @DisplayName("R-GIVE-01 物品不在身上 → FAIL NO_ITEM")
    void giveFailsWithoutItem() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.GIVE)
                .inventoryKnown(true).inventory(items("干粮")).args(giveArgs("短刀")).build());
        assertVerdict(v, Verdict.FAIL, "NO_ITEM");
    }

    @Test
    @DisplayName("R-GIVE-01 物品在身上 → SUCCESS（规则 R-GIVE-01）")
    void giveSucceedsWithItem() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.GIVE)
                .inventoryKnown(true).inventory(items("短刀")).args(giveArgs("短刀")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-GIVE-01", v.adjudicatedBy());
    }

    @Test
    @DisplayName("R-GIVE-01 决策没写物品键 → R-DEFAULT 放行（不臆断）")
    void giveWithoutItemKeyFallsOpen() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.GIVE)
                .inventoryKnown(true).inventory(items("短刀")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-DEFAULT", v.adjudicatedBy());
    }

    @Test
    @DisplayName("R-GIVE-02 说「还钱」→ market.settle（赊账要真的还，不是递东西）")
    void giveRepayEmitsMarketSettle() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.GIVE)
                .inventoryKnown(true).inventory(items("coin")).actor(actor(12L))
                .decision(action("把上回赊的账还钱给掌柜")).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-GIVE-02", v.adjudicatedBy());
        assertEquals(1, v.effects().size(), "应该产生一条结清效果");
        assertEquals(EffectOps.MARKET_SETTLE, v.effects().get(0).op());
        assertEquals("npc:12", v.effects().get(0).body().path("buyer").asText());
    }

    @Test
    @DisplayName("R-GIVE-02 说还钱但物品数据未知 → UNSURE（不凭空结清）")
    void giveRepayUnknownInventoryIsUnsure() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.GIVE)
                .inventoryKnown(false).actor(actor(12L))
                .decision(action("把欠的账还清")).build());
        assertVerdict(v, Verdict.UNSURE, "DATA_UNAVAILABLE");
    }

    // ==================== DEFAULT ====================

    @Test
    @DisplayName("未知意图 / 空上下文 → SUCCESS R-DEFAULT（fail-open）")
    void otherIntentSucceeds() {
        assertVerdict(adjudicator.adjudicate(ctx(IntentKind.OTHER).build()), Verdict.SUCCESS, "OK");
        assertVerdict(adjudicator.adjudicate(null), Verdict.SUCCESS, "OK");
    }

    // ==================== ATTACK（耐久/装备） ====================

    @Test
    @DisplayName("I4 R-ATTACK-01 目标不是角色 → FAIL NO_TARGET（不抡空刀）")
    void attackWearWithoutTargetRef() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 100, "hand")))
                .build());
        assertVerdict(v, Verdict.FAIL, "NO_TARGET");
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 目标不在跟前 → FAIL TOO_FAR（距离≤1 格）")
    void attackWearTargetTooFar() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 100, "hand")))
                .targetRef("npc:2").targetPresent(false).build());
        assertVerdict(v, Verdict.FAIL, "TOO_FAR");
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 手上和身上都没有武器 → FAIL NO_WEAPON")
    void attackWearNoWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("干粮", 2, 100, 100, null)))
                .targetRef("npc:2").targetPresent(true).build());
        assertVerdict(v, Verdict.FAIL, "NO_WEAPON");
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 刀已经断了（耐久 0）→ FAIL WEAPON_BROKEN")
    void attackWearWeaponBroken() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 0, "hand")))
                .targetRef("npc:2").targetPresent(true).build());
        assertVerdict(v, Verdict.FAIL, "WEAPON_BROKEN");
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 卷了刃 → PARTIAL WEAPON_BLUNT，仍发出 combat.strike")
    void attackWearBlunt() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 10, "hand")))
                .targetRef("npc:2").targetPresent(true).build());
        assertVerdict(v, Verdict.PARTIAL, "WEAPON_BLUNT");
        assertEquals(1, v.effects().size());
        assertEquals(EffectOps.COMBAT_STRIKE, v.effects().get(0).op());
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 手上拿着好刀 → SUCCESS + combat.strike（指定武器键）")
    void attackWearUsesEquippedWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 100, "hand")))
                .equipmentKnown(true).equipment(List.of(worn("hand", "柴刀", 1, 100, 100)))
                .handEquipment(worn("hand", "柴刀", 1, 100, 100))
                .targetRef("npc:2").targetPresent(true).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(EffectOps.COMBAT_STRIKE, v.effects().get(0).op());
        assertEquals("柴刀", v.effects().get(0).body().path("weaponKey").asText());
    }

    @Test
    @DisplayName("I4 R-ATTACK-01 手上空着但身上有刀 → 仍 SUCCESS（顺手抽刀）")
    void attackWearAutoDrawsCarriedWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.ATTACK).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 80, "hand")))
                .equipmentKnown(true).equipment(List.of())
                .targetRef("npc:2").targetPresent(true).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("柴刀", v.effects().get(0).body().path("weaponKey").asText());
    }

    // ==================== REPAIR ====================

    @Test
    @DisplayName("I4 R-REPAIR-01 损耗域没开 → R-DEFAULT 放行（回 I3 基线）")
    void repairFallsOpenWithoutWear() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.REPAIR)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 10, "hand")))
                .repairItemKey("柴刀").build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-DEFAULT", v.adjudicatedBy());
    }

    @Test
    @DisplayName("I4 R-REPAIR-01 没说要修什么 → R-DEFAULT 放行（不臆断挑一件去修）")
    void repairFallsOpenWithoutItemKey() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.REPAIR).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 10, "hand"))).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-DEFAULT", v.adjudicatedBy());
    }

    @Test
    @DisplayName("I4 R-REPAIR-01 东西不在身上 → FAIL NO_ITEM")
    void repairFailsWithoutItem() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.REPAIR).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("干粮", 1, 100, 100, null)))
                .repairItemKey("柴刀").build());
        assertVerdict(v, Verdict.FAIL, "NO_ITEM");
    }

    @Test
    @DisplayName("I4 R-REPAIR-01 东西还完好 → FAIL NOT_DAMAGED")
    void repairFailsWhenNotDamaged() {
        ItemView knife = item("柴刀", 1, 100, 100, "hand");
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.REPAIR).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(knife))
                .repairItemKey("柴刀").repairTarget(knife).build());
        assertVerdict(v, Verdict.FAIL, "NOT_DAMAGED");
    }

    @Test
    @DisplayName("I4 R-REPAIR-01 有磨损 → SUCCESS + item.repair")
    void repairSucceedsWithWornItem() {
        ItemView knife = item("柴刀", 1, 100, 30, "hand");
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.REPAIR).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(knife))
                .repairItemKey("柴刀").repairTarget(knife).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(EffectOps.ITEM_REPAIR, v.effects().get(0).op());
        assertEquals("柴刀", v.effects().get(0).body().path("itemKey").asText());
    }

    // ==================== EQUIP ====================

    @Test
    @DisplayName("I4 R-EQUIP-01 损耗域没开 → R-DEFAULT 放行（回 I3 基线）")
    void equipFallsOpenWithoutWear() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP)
                .inventoryKnown(true).inventory(List.of(item("粗布衣", 1, 100, 100, "body")))
                .equipItemKey("粗布衣").build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals("rule:R-DEFAULT", v.adjudicatedBy());
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 东西不在身上 → FAIL NO_ITEM")
    void equipFailsWithoutItem() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("干粮", 1, 100, 100, null)))
                .equipItemKey("粗布衣").build());
        assertVerdict(v, Verdict.FAIL, "NO_ITEM");
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 这件没有装备槽 → FAIL NOT_EQUIPPABLE")
    void equipFailsWhenNotEquippable() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("干粮", 1, 100, 100, null)))
                .equipItemKey("干粮").build());
        assertVerdict(v, Verdict.FAIL, "NOT_EQUIPPABLE");
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 手上槽位只能拿武器 → FAIL NOT_A_WEAPON")
    void equipFailsWhenHandItemIsNotWeapon() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true)
                .inventory(List.of(new ItemView("火把", "火把", "tool", 1, 100, 100, "hand")))
                .equipItemKey("火把").build());
        assertVerdict(v, Verdict.FAIL, "NOT_A_WEAPON");
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 穿上粗布衣 → SUCCESS + item.equip（slot=body）")
    void equipBodySucceeds() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("粗布衣", 1, 100, 100, "body")))
                .equipItemKey("粗布衣").build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(EffectOps.ITEM_EQUIP, v.effects().get(0).op());
        assertEquals("body", v.effects().get(0).body().path("slot").asText());
        assertTrue(v.effects().get(0).body().path("equipped").asBoolean());
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 身上没戴东西却要脱 → FAIL NOT_EQUIPPED")
    void unequipFailsWhenNothingWorn() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 100, "hand")))
                .equipItemKey("柴刀").equipUnequip(true).equipment(List.of()).build());
        assertVerdict(v, Verdict.FAIL, "NOT_EQUIPPED");
    }

    @Test
    @DisplayName("I4 R-EQUIP-01 脱下柴刀 → SUCCESS + item.equip（equipped=false）")
    void unequipSucceeds() {
        ActionVerdict v = adjudicator.adjudicate(ctx(IntentKind.EQUIP).wearDataKnown(true)
                .inventoryKnown(true).inventory(List.of(item("柴刀", 1, 100, 100, "hand")))
                .equipItemKey("柴刀").equipUnequip(true)
                .equipment(List.of(worn("hand", "柴刀", 1, 100, 100))).build());
        assertVerdict(v, Verdict.SUCCESS, "OK");
        assertEquals(EffectOps.ITEM_EQUIP, v.effects().get(0).op());
        assertFalse(v.effects().get(0).body().path("equipped").asBoolean(), "应该是脱下");
    }

    // ==================== 工具 ====================

    /** 造一组「一个品质层一件」的物品视图（name 同时作为 itemKey，便于按名字判武器）。 */
    private static List<ItemView> items(String... names) {
        List<ItemView> list = new ArrayList<>();
        for (String name : names) {
            list.add(new ItemView(name, name, "material", 1, 100, null, null));
        }
        return list;
    }

    /** 造决策 params：{"itemKey": "..."}。 */
    private static JsonNode giveArgs(String itemKey) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("itemKey", itemKey);
        return node;
    }

    private static void assertVerdict(ActionVerdict v, Verdict expected, String reason) {
        assertEquals(expected, v.verdict(), "verdict");
        assertEquals(reason, v.reasonCode(), "reasonCode");
    }

    // ==================== 对接：R-REST-01（说吃了什么就少什么） ====================

    @Test
    @DisplayName("R-REST-01：行动文本点名了随身的口粮 → 扣掉一件（inventory.consume）")
    void restConsumesNamedFood() {
        AdjudicationContext ctx = ctx(IntentKind.REST)
                .inventoryKnown(true)
                .inventory(List.of(item("干粮", 1, 100, 60, null)))
                .decision(action("吃随身的干粮充饥"))
                .build();

        ActionVerdict verdict = adjudicator.adjudicate(ctx);

        assertEquals(Verdict.SUCCESS, verdict.verdict());
        assertEquals(1, verdict.effects().size(), "应该产生一条消耗效果");
        assertEquals(EffectOps.INVENTORY_CONSUME, verdict.effects().get(0).op());
        assertEquals("干粮", verdict.effects().get(0).body().path("itemKey").asText());
        assertEquals("consume", verdict.effects().get(0).body().path("reason").asText());
    }

    @Test
    @DisplayName("R-REST-01：说的是去酒馆吃饭（没点名自家东西）→ 不扣背包")
    void restDoesNotConsumeUnnamedFood() {
        AdjudicationContext ctx = ctx(IntentKind.REST)
                .inventoryKnown(true)
                .inventory(List.of(item("干粮", 1, 100, 60, null)))
                .decision(action("去酒馆吃饭"))
                .build();

        ActionVerdict verdict = adjudicator.adjudicate(ctx);

        assertEquals(Verdict.SUCCESS, verdict.verdict());
        assertTrue(verdict.effects().isEmpty(), "别人做的饭不该扣自家口粮");
    }

    @Test
    @DisplayName("R-REST-01：喝水走 drink 原因，且水不会被当成饭")
    void restConsumesNamedDrink() {
        AdjudicationContext ctx = ctx(IntentKind.REST)
                .inventoryKnown(true)
                .inventory(List.of(new ItemView("水", "水", "drink", 2, 100, 100, null),
                        item("面包", 1, 100, 100, null)))
                .decision(action("喝随身的水解渴"))
                .build();

        ActionVerdict verdict = adjudicator.adjudicate(ctx);

        assertEquals(1, verdict.effects().size());
        assertEquals("水", verdict.effects().get(0).body().path("itemKey").asText());
        assertEquals("drink", verdict.effects().get(0).body().path("reason").asText());
    }

    @Test
    @DisplayName("R-REST-01：物品数据未知 → 放行且不扣（不凭空说人吃了什么）")
    void restSkipsWhenInventoryUnknown() {
        AdjudicationContext ctx = ctx(IntentKind.REST)
                .inventoryKnown(false)
                .decision(action("吃随身的干粮充饥"))
                .build();

        ActionVerdict verdict = adjudicator.adjudicate(ctx);

        assertEquals(Verdict.SUCCESS, verdict.verdict());
        assertTrue(verdict.effects().isEmpty());
    }

    /** 造一个带 action 文本的决策 JSON。 */
    private static JsonNode action(String text) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("action", text);
        return node;
    }

    private static AdjudicationContext.AdjudicationContextBuilder ctx(IntentKind intent) {
        return AdjudicationContext.builder().intent(intent).energy(80).relationToTarget(0);
    }

    /**
     * TRADE 的默认上下文：柜台已知且有一件 bread，卖方 loc:9，单价 10，身上 100 文。
     *
     * <p>各用例只覆盖自己关心的字段（库存/单价/资金/赊账/声望），避免每个测试都写 8 行 builder。</p>
     */
    private static AdjudicationContext.AdjudicationContextBuilder tradeCtx() {
        return ctx(IntentKind.TRADE).inventoryKnown(true)
                .counterpartyStockKnown(true).counterpartyStock(stock("bread", 5))
                .sellerRef("loc:9").buyerRef("npc:10")
                .tradeItemKey("bread").tradeQty(1).unitPrice(10).funds(100);
    }

    /** 造一件指定数量的柜台货（一个品质层）。 */
    private static List<ItemView> stock(String name, int qty) {
        return List.of(new ItemView(name, name, "material", qty, 100, null, null));
    }

    private static ActorWorldFact fact(String key, String attrs) {
        ActorWorldFact f = new ActorWorldFact();
        f.setFactKey(key);
        f.setAttributesJson(attrs);
        return f;
    }

    /** 造一项配方材料。 */
    private static RecipeInput input(String itemKey, int qty) {
        return new RecipeInput(itemKey, qty);
    }

    /** 造一份配方视图（产出形态固定 item；建造类用例另行覆盖）。 */
    private static CraftRecipeView recipe(String recipeKey, String outputItemKey, String facilityType,
                                          int minutes, RecipeInput... inputs) {
        return new CraftRecipeView(recipeKey, outputItemKey, 1, "item", facilityType, minutes,
                List.of(inputs));
    }

    /** 造一个最小角色（只有 id，够拼 {@code npc:{id}}）。 */
    private static ActorCharacter actor(Long id) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName("某人");
        return c;
    }

    /**
     * 造一件带状态层的物品：耐久是层键的一部分。
     *
     * @param name       展示名（同时作 itemKey）
     * @param qty        件数
     * @param quality    品质
     * @param durability 耐久
     * @param equipSlot  装备槽（null=不可装备）
     */
    private static ItemView item(String name, int qty, int quality, int durability, String equipSlot) {
        String category = equipSlot == null ? "material"
                : ("body".equals(equipSlot) ? "wearable" : "weapon");
        return new ItemView(name, name, category, qty, quality, durability, equipSlot);
    }

    /** 造一件「正在用」的装备。 */
    private static EquipmentView worn(String slot, String name, int qty, int quality, int durability) {
        return new EquipmentView(slot, name, name, "weapon", qty, quality, durability, 100L);
    }
}
