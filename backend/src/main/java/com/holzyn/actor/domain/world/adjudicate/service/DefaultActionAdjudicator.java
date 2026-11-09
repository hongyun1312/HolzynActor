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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.craft.facility.CraftFacilities;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.model.MaterialGap;
import com.holzyn.actor.domain.craft.service.CraftService;
import com.holzyn.actor.domain.equip.model.EquipmentSlot;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.item.wear.WearRules;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.effect.model.EffectOps;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认规则裁决器（§4.7，10 条规则）。
 *
 * <p>纯确定性、零 AI、零写库；所有“无记录”按 fail-open 放行（用户口径：明确否定才 FAIL）。
 * 物品/资金/配方数据当前不存在时返回 {@link com.holzyn.actor.domain.world.adjudicate.model.Verdict#UNSURE}
 * 与 {@code DATA_UNAVAILABLE}，交上层按重要度决定 AI 或 PARTIAL。</p>
 */
@Service
public class DefaultActionAdjudicator implements ActionAdjudicator {

    /** 体力低于该值判太累（NpcState.energy 0~100）。 */
    static final int MIN_ENERGY = 20;

    /** 关系差于该值判不愿交谈。 */
    static final int REFUSE_AFFINITY = -20;

    /** 声望低于该值判不赊账。 */
    static final int DISTRUST_REPUTATION = -20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 损耗域配置（只用来读「卷刃阈值」；未注入时按 {@link WearRules#BLUNT_CONDITION}）。
     *
     * <p>字段注入而非构造器：本类是既有服务，改构造器会打断全部规则单测的构造点
     * （AGENTS §8.1.2）。</p>
     */
    @Autowired(required = false)
    private WearProperties wearProperties;

    @Override
    public ActionVerdict adjudicate(AdjudicationContext ctx) {
        if (ctx == null || ctx.getIntent() == null) {
            return ActionVerdict.success("R-DEFAULT");
        }
        return switch (ctx.getIntent()) {
            case TRAVEL -> travel(ctx);
            case TALK -> talk(ctx);
            case TRADE -> trade(ctx);
            case CRAFT -> craft(ctx);
            case USE -> use(ctx);
            case ATTACK -> attack(ctx);
            case REPAIR -> repair(ctx);
            case EQUIP -> equip(ctx);
            case GIVE -> give(ctx);
            case REST -> rest(ctx);
            default -> ActionVerdict.success("R-DEFAULT");
        };
    }

    // ==================== TRAVEL ====================

    /** R-TRAVEL-01 目标存在且路线未封锁；R-TRAVEL-02 体力足够。 */
    ActionVerdict travel(AdjudicationContext ctx) {
        if (Boolean.FALSE.equals(ctx.getTargetLocationExists())) {
            return ActionVerdict.fail("R-TRAVEL-01", "TARGET_NOT_FOUND",
                    "他问了一圈，才发现镇上并没有这个去处。");
        }
        if (ctx.getRouteFact() != null && blocked(ctx.getRouteFact())) {
            return ActionVerdict.fail("R-TRAVEL-01", "ROUTE_BLOCKED",
                    "他走到路口又折返——路被封住了。");
        }
        if (ctx.getEnergy() != null && ctx.getEnergy() < MIN_ENERGY) {
            return ActionVerdict.fail("R-TRAVEL-02", "TOO_TIRED",
                    "他扶着墙喘气，没走两步就停下了。");
        }
        return ActionVerdict.success("R-TRAVEL-01");
    }

    // ==================== TALK ====================

    /** R-TALK-01 目标在场；R-TALK-02 交愿意愿（确定性阈值，不调社交门控 AI）。 */
    ActionVerdict talk(AdjudicationContext ctx) {
        if (Boolean.FALSE.equals(ctx.getTargetPresent())) {
            return ActionVerdict.fail("R-TALK-01", "NOT_PRESENT",
                    "他对着空屋子说了句话，没人应。");
        }
        if (ctx.getRelationToTarget() <= REFUSE_AFFINITY) {
            return ActionVerdict.fail("R-TALK-02", "REFUSED",
                    "对方别过脸去，没接话。");
        }
        if (ctx.getEnergy() != null && ctx.getEnergy() < 10) {
            return ActionVerdict.fail("R-TALK-02", "REFUSED",
                    "对方累得连眼皮都没抬。");
        }
        return ActionVerdict.success("R-TALK-01");
    }

    // ==================== TRADE ====================

    /**
     * R-TRADE-01 有货且钱够；R-TRADE-02 赊账看<b>买方</b>声望。
     *
     * <p><b>三处修正</b>：</p>
     * <ol>
     *   <li>货从 {@code counterpartyStock}（柜台 {@code loc:{id}}）读，不再拿自己的背包顶替；</li>
     *   <li>{@code NO_FUNDS} 用「买方身上货币数量 vs 单价×数量」，钱是物品（coin）；</li>
     *   <li>赊账看 {@code actorReputation}（我赖过账 → 掌柜不赊），不是对方的声望。</li>
     * </ol>
     *
     * <p>决策没写具体货物（{@code itemKey}）时只判「柜台上确实有货可谈」，<b>不结算</b>——
     * 不猜他要买哪件，也不凭空生成一笔交易。</p>
     */
    ActionVerdict trade(AdjudicationContext ctx) {
        if (!ctx.isInventoryKnown()) {
            return ActionVerdict.unsure("R-TRADE-01", "DATA_UNAVAILABLE",
                    "他摸了摸口袋，却没人知道他到底带没带钱。");
        }
        if (!ctx.isCounterpartyStockKnown()) {
            return ActionVerdict.unsure("R-TRADE-01", "STOCK_UNKNOWN",
                    "没人知道柜台上到底还有没有货。");
        }
        List<ItemView> stock = ctx.getCounterpartyStock();
        if (stock == null || stock.isEmpty()) {
            return ActionVerdict.fail("R-TRADE-01", "NO_STOCK",
                    "掌柜摊手：卖完了。");
        }
        String itemKey = ctx.getTradeItemKey();
        if (itemKey == null || itemKey.isBlank()) {
            // 没写具体货物：能谈，但不动账（fail-open，不臆断）
            return ActionVerdict.success("R-TRADE-01");
        }
        int qty = Math.max(1, ctx.getTradeQty());
        if (ItemView.totalOf(stock, itemKey) < qty) {
            return ActionVerdict.fail("R-TRADE-01", "NO_STOCK",
                    "掌柜摊手：这件卖完了。");
        }
        if (ctx.isCredit()) {
            Integer reputation = ctx.getBuyerReputation();
            if (reputation != null && reputation < DISTRUST_REPUTATION) {
                return ActionVerdict.fail("R-TRADE-02", "DISTRUSTED",
                        "掌柜把账本合上：不赊。");
            }
        } else {
            if (ctx.getUnitPrice() == null) {
                return ActionVerdict.unsure("R-TRADE-01", "PRICE_UNKNOWN",
                        "柜台没标价，他一时算不清要付多少。");
            }
            if (ctx.getFunds() == null) {
                return ActionVerdict.unsure("R-TRADE-01", "FUNDS_UNKNOWN",
                        "没人知道他身上到底有多少钱。");
            }
            long total = (long) ctx.getUnitPrice() * qty;
            if (ctx.getFunds() < total) {
                return ActionVerdict.fail("R-TRADE-01", "NO_FUNDS",
                        "他把钱袋翻了个底朝天，还是差几文。");
            }
        }
        if (ctx.getSellerRef() == null || ctx.getBuyerRef() == null) {
            return ActionVerdict.unsure("R-TRADE-01", "STOCK_UNKNOWN",
                    "没人知道柜台在哪。");
        }
        return ActionVerdict.success("R-TRADE-01", List.of(tradeOp(ctx, itemKey, qty)));
    }

    /** 构造一笔确定性成交的效果草稿（market.trade）。 */
    private static EffectOp tradeOp(AdjudicationContext ctx, String itemKey, int qty) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("seller", ctx.getSellerRef());
        body.put("buyer", ctx.getBuyerRef());
        body.put("itemKey", itemKey);
        body.put("qty", qty);
        if (ctx.getUnitPrice() != null) {
            body.put("unitPrice", ctx.getUnitPrice());
        }
        body.put("credit", ctx.isCredit());
        return new EffectOp(EffectOps.MARKET_TRADE, body, null);
    }

    // ==================== CRAFT ====================

    /**
     * R-CRAFT-01 材料齐 + 设施可用 + 配方存在（起为确定判定）。
     *
     * <p><b>判定顺序是有讲究的</b>：</p>
     * <ol>
     *   <li>目标地点设施关门 → {@code NO_FACILITY}：不看配方也该看得见「门锁着」；</li>
     *   <li>制作域未启用 / 物品数据未知 / 配方解析不出 → {@code UNSURE}：
     *       不知道就交给 AI 或低重要度放行，绝不凭空判 {@code NO_MATERIAL}
     *       （这正是之前那条「空背包不等于缺材料」的教训）；</li>
     *   <li>配方要求设施但现场没有/类型不对 → {@code NO_FACILITY}；</li>
     *   <li>设施被别人占着 → {@code OCCUPIED}（复用 R-USE-01 的词汇）；</li>
     *   <li>材料有缺口 → {@code NO_MATERIAL}，并在可见文本里说出「少什么、少几份」；</li>
     *   <li>手上还压着活 → {@code BUSY}：一个人不能同时打两把刀。</li>
     * </ol>
     *
     * <p>成功时发出 {@code craft.make}（只由规则层产出，AI 白名单不含它）。</p>
     */
    ActionVerdict craft(AdjudicationContext ctx) {
        if (ctx.getFacilityFact() != null && closed(ctx.getFacilityFact())) {
            return ActionVerdict.fail("R-CRAFT-01", "NO_FACILITY",
                    "炉子冷着，门也锁着。");
        }
        if (!ctx.isCraftDataKnown()) {
            // 制作域没开：保持之前的行为（物品未知→DATA_UNAVAILABLE，否则配方未知）
            if (!ctx.isInventoryKnown()) {
                return ActionVerdict.unsure("R-CRAFT-01", "DATA_UNAVAILABLE",
                        "他翻遍口袋，却没人知道他到底缺不缺材料。");
            }
            return ActionVerdict.unsure("R-CRAFT-01", "RECIPE_UNKNOWN",
                    "他不知道这东西该怎么配。");
        }
        if (!ctx.isInventoryKnown()) {
            return ActionVerdict.unsure("R-CRAFT-01", "DATA_UNAVAILABLE",
                    "他翻遍口袋，却没人知道他到底缺不缺材料。");
        }
        CraftRecipeView recipe = ctx.getRecipe();
        if (!ctx.isRecipeKnown() || recipe == null) {
            return ActionVerdict.unsure("R-CRAFT-01", "RECIPE_UNKNOWN",
                    "他不知道这东西该怎么配。");
        }
        if (recipe.needsFacility()) {
            if (ctx.getCraftFacilityRef() == null) {
                return ActionVerdict.fail("R-CRAFT-01", "NO_FACILITY",
                        "这儿没有「" + recipe.facilityType() + "」，这活做不成。");
            }
            if (!CraftFacilities.matches(recipe.facilityType(), ctx.getCraftFacilityType(),
                    ctx.getCraftFacilityName())) {
                return ActionVerdict.fail("R-CRAFT-01", "NO_FACILITY",
                        "这处「" + firstNonBlank(ctx.getCraftFacilityName(), "地方") + "」做不了"
                                + recipe.facilityType() + "的活。");
            }
        }
        if (ctx.getCraftOccupant() != null) {
            return ActionVerdict.fail("R-CRAFT-01", "OCCUPIED",
                    "「" + firstNonBlank(ctx.getCraftFacilityName(), recipe.facilityType()) + "」前已经站了人。");
        }
        List<MaterialGap> gaps = CraftService.gapsOf(recipe, ctx.getInventory(), ctx.getCraftBatches());
        if (!gaps.isEmpty()) {
            return ActionVerdict.fail("R-CRAFT-01", "NO_MATERIAL",
                    "他翻遍口袋，少" + gaps.get(0).describe() + "。");
        }
        if (ctx.getCraftBusyRecipeKey() != null) {
            return ActionVerdict.fail("R-CRAFT-01", "BUSY",
                    "他手上那件「" + ctx.getCraftBusyRecipeKey() + "」还没做完。");
        }
        return ActionVerdict.success("R-CRAFT-01", List.of(craftOp(ctx, recipe)));
    }

    /** 构造一次开工的效果草稿（craft.make）。 */
    private static EffectOp craftOp(AdjudicationContext ctx, CraftRecipeView recipe) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("actor", NpcRefs.of(ctx.getActor().getId()));
        }
        body.put("recipeKey", recipe.recipeKey());
        body.put("batches", Math.max(1, ctx.getCraftBatches()));
        if (ctx.getCraftFacilityRef() != null) {
            body.put("facilityRef", ctx.getCraftFacilityRef());
        }
        if (ctx.getCraftLocationId() != null) {
            body.put("locationId", ctx.getCraftLocationId());
        }
        if (ctx.getLocationName() != null) {
            body.put("locationName", ctx.getLocationName());
        }
        return new EffectOp(EffectOps.CRAFT_MAKE, body, null);
    }

    /** 取第一个非空白（失败文本用）。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    // ==================== USE ====================

    /** R-USE-01 设施开门且未占用；无设施记录按未知放行。 */
    ActionVerdict use(AdjudicationContext ctx) {
        if (ctx.getFacilityFact() == null) {
            return ActionVerdict.success("R-USE-01");
        }
        JsonNode attrs = attrs(ctx.getFacilityFact());
        if ("false".equalsIgnoreCase(attrs.path("open").asText("true"))) {
            return ActionVerdict.fail("R-USE-01", "CLOSED", "门是锁着的。");
        }
        if ("true".equalsIgnoreCase(attrs.path("occupied").asText("false"))) {
            return ActionVerdict.fail("R-USE-01", "OCCUPIED", "里面有人。");
        }
        return ActionVerdict.success("R-USE-01");
    }

    // ==================== ATTACK ====================

    /**
     * R-ATTACK-01 手里得有能用的家伙，且目标在跟前（起为确定判定）。
     *
     * <p><b>判定顺序</b>：</p>
     * <ol>
     *   <li>物品数据未知 → {@code UNSURE}（不凭空说人空手）；</li>
     *   <li>损耗域没开 → 走旧路径（身上有武器即算），保证关开关时行为回基线；</li>
     *   <li>目标不在跟前 → {@code TOO_FAR}：设计文档 R-ATTACK-01 的前置是「距离≤1 格」；</li>
     *   <li>手上没有、身上也没有武器 → {@code NO_WEAPON}；</li>
     *   <li>家伙已经断了（耐久 0）→ {@code WEAPON_BROKEN}；</li>
     *   <li>卷了刃（成色低于阈值）→ {@code PARTIAL}：还砍得动，但打不狠（伤害由
     *       {@code WearRules.damageOf} 按成色折算）；</li>
     *   <li>其余 → {@code SUCCESS} + {@code combat.strike}。</li>
     * </ol>
     *
     * <p><b>「顺手抽刀」</b>：手上没拿但身上有武器时仍然算成功——NPC 的行动文本常常
     * 直接就是「拔刀砍过去」，要求他先单独装备一次会让真实行为被流程卡死。
     * 结算时 {@code CombatService} 会顺势把刀装到手上（旁人从此看得见）。</p>
     */
    ActionVerdict attack(AdjudicationContext ctx) {
        if (!ctx.isInventoryKnown()) {
            return ActionVerdict.unsure("R-ATTACK-01", "DATA_UNAVAILABLE",
                    "他攥紧拳头，却没人知道他有没有带家伙。");
        }
        if (!ctx.isWearDataKnown()) {
            // 旧口径：只要身上有像武器的就算有家伙（关开关=回基线）
            boolean weapon = ctx.getInventory() != null && ctx.getInventory().stream()
                    .anyMatch(i -> i != null && i.weaponLike());
            if (!weapon) {
                return ActionVerdict.fail("R-ATTACK-01", "NO_WEAPON",
                        "他攥紧拳头，又松开。");
            }
            return ActionVerdict.success("R-ATTACK-01");
        }
        if (ctx.getTargetRef() == null || ctx.getTargetRef().isBlank()) {
            return ActionVerdict.fail("R-ATTACK-01", "NO_TARGET",
                    "他抡起刀，却不知道这一下该冲谁去。");
        }
        if (Boolean.FALSE.equals(ctx.getTargetPresent())) {
            return ActionVerdict.fail("R-ATTACK-01", "TOO_FAR",
                    "他挥了个空——那人不在跟前。");
        }
        EquipmentView hand = ctx.getHandEquipment();
        ItemView carried = bestWeaponOf(ctx.getInventory());
        if (hand == null && carried == null) {
            return ActionVerdict.fail("R-ATTACK-01", "NO_WEAPON",
                    "他攥紧拳头，又松开。");
        }
        String itemKey;
        String name;
        int quality;
        int durability;
        if (hand != null) {
            itemKey = hand.itemKey();
            name = hand.name();
            quality = hand.quality();
            durability = hand.durability();
        } else {
            itemKey = carried.itemKey();
            name = carried.name();
            quality = carried.quality();
            durability = carried.durability();
        }
        if (durability <= 0) {
            return ActionVerdict.fail("R-ATTACK-01", "WEAPON_BROKEN",
                    "那把" + name + "已经断了，握在手里只剩半截。");
        }
        boolean blunt = WearRules.blunt(quality, durability, bluntThreshold());
        List<EffectOp> effects = List.of(strikeOp(ctx, itemKey));
        if (blunt) {
            return new ActionVerdict(Verdict.PARTIAL, "WEAPON_BLUNT",
                    "那把" + name + "卷了刃，砍下去软绵绵的。", 0, effects, "rule:R-ATTACK-01");
        }
        return ActionVerdict.success("R-ATTACK-01", effects);
    }

    /** 构造一次攻击的效果草稿（combat.strike）。 */
    private static EffectOp strikeOp(AdjudicationContext ctx, String itemKey) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("attacker", NpcRefs.of(ctx.getActor().getId()));
        }
        body.put("target", ctx.getTargetRef());
        body.put("weaponKey", itemKey);
        return new EffectOp(EffectOps.COMBAT_STRIKE, body, null);
    }

    /** 身上成色最好的那把武器（没有返回 null）。 */
    private static ItemView bestWeaponOf(List<ItemView> items) {
        if (items == null) {
            return null;
        }
        ItemView best = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() <= 0 || !item.weaponLike()) {
                continue;
            }
            if (best == null || WearRules.conditionOf(item.quality(), item.durability())
                    > WearRules.conditionOf(best.quality(), best.durability())) {
                best = item;
            }
        }
        return best;
    }

    // ==================== REPAIR ====================

    /**
     * R-REPAIR-01 身上有这件东西、且它确实坏了（新增）。
     *
     * <p><b>没说要修什么就不臆断</b>：直接 {@code R-DEFAULT} 放行（fail-open）。
     * 修东西是有代价的行动，凭空挑一件去修比不修更失真。</p>
     *
     * <p>损耗域关着时同样直接放行：之前 REPAIR 没有规则，行为是「成功但无效果」，
     * 这里必须保持逐字节一致。</p>
     */
    ActionVerdict repair(AdjudicationContext ctx) {
        if (!ctx.isWearDataKnown()) {
            return ActionVerdict.success("R-DEFAULT");
        }
        if (ctx.getRepairItemKey() == null || ctx.getRepairItemKey().isBlank()) {
            return ActionVerdict.success("R-DEFAULT");
        }
        ItemView target = ctx.getRepairTarget();
        if (target == null) {
            return ActionVerdict.fail("R-REPAIR-01", "NO_ITEM",
                    "他摸了摸口袋，「" + ctx.getRepairItemKey() + "」不在身上。");
        }
        if (target.durability() >= InventoryService.DEFAULT_DURABILITY) {
            return ActionVerdict.fail("R-REPAIR-01", "NOT_DAMAGED",
                    "那" + target.name() + "还好好的，用不着修。");
        }
        return ActionVerdict.success("R-REPAIR-01", List.of(repairOp(ctx, target.itemKey())));
    }

    /** 构造一次修理的效果草稿（item.repair）。 */
    private static EffectOp repairOp(AdjudicationContext ctx, String itemKey) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("actor", NpcRefs.of(ctx.getActor().getId()));
        }
        body.put("itemKey", itemKey);
        return new EffectOp(EffectOps.ITEM_REPAIR, body, null);
    }

    // ==================== EQUIP ====================

    /**
     * R-EQUIP-01 穿得上/放得下（新增）。
     *
     * <p>判定顺序：没说要穿什么 → 放行（不臆断）；东西不在身上 → {@code NO_ITEM}；
     * 这件没有装备槽 → {@code NOT_EQUIPPABLE}；手上槽位只能拿武器 → {@code NOT_A_WEAPON}；
     * 要脱但本来就没戴 → {@code NOT_EQUIPPED}。</p>
     */
    ActionVerdict equip(AdjudicationContext ctx) {
        if (!ctx.isWearDataKnown()) {
            return ActionVerdict.success("R-DEFAULT");
        }
        String itemKey = ctx.getEquipItemKey();
        if (itemKey == null || itemKey.isBlank()) {
            return ActionVerdict.success("R-DEFAULT");
        }
        if (ctx.isEquipUnequip()) {
            if (ctx.getEquipment() == null || ctx.getEquipment().isEmpty()) {
                return ActionVerdict.fail("R-EQUIP-01", "NOT_EQUIPPED",
                        "他身上本来就没戴什么。");
            }
            return ActionVerdict.success("R-EQUIP-01",
                    List.of(equipOp(ctx, itemKey, ctx.getEquipSlot(), false)));
        }
        ItemView item = findInInventory(ctx.getInventory(), itemKey);
        if (item == null) {
            return ActionVerdict.fail("R-EQUIP-01", "NO_ITEM",
                    "他摸了摸口袋，「" + itemKey + "」不在身上。");
        }
        String defSlot = item.equipSlot() == null || item.equipSlot().isBlank()
                ? null : item.equipSlot().trim().toLowerCase(java.util.Locale.ROOT);
        if (defSlot == null) {
            return ActionVerdict.fail("R-EQUIP-01", "NOT_EQUIPPABLE",
                    "「" + item.name() + "」没法穿戴。");
        }
        if (EquipmentSlot.HAND.equals(defSlot) && !item.weaponLike()) {
            return ActionVerdict.fail("R-EQUIP-01", "NOT_A_WEAPON",
                    "「" + item.name() + "」不是能拿在手里的家伙。");
        }
        return ActionVerdict.success("R-EQUIP-01",
                List.of(equipOp(ctx, item.itemKey(), defSlot, true)));
    }

    /** 构造一次穿/脱的效果草稿（item.equip）。 */
    private static EffectOp equipOp(AdjudicationContext ctx, String itemKey, String slot, boolean equipped) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("actor", NpcRefs.of(ctx.getActor().getId()));
        }
        body.put("itemKey", itemKey);
        if (slot != null && !slot.isBlank()) {
            body.put("slot", slot);
        }
        body.put("equipped", equipped);
        return new EffectOp(EffectOps.ITEM_EQUIP, body, null);
    }

    /** 在背包里找某物品的一层（键不区分 ASCII 大小写）。 */
    private static ItemView findInInventory(List<ItemView> items, String itemKey) {
        if (items == null || itemKey == null) {
            return null;
        }
        String wanted = itemKey.trim();
        for (ItemView item : items) {
            if (item != null && item.itemKey() != null && wanted.equalsIgnoreCase(item.itemKey().trim())) {
                return item;
            }
        }
        return null;
    }

    /** 卷刃阈值（配置未注入时用 {@link WearRules#BLUNT_CONDITION}）。 */
    private int bluntThreshold() {
        return wearProperties == null ? WearRules.BLUNT_CONDITION : wearProperties.getBluntThreshold();
    }

    // ==================== REST（对接：真的吃掉背包里那件） ====================

    /**
     * R-REST-01 休息/进食：行动文本点名了身上某样吃食 → 扣掉一件（{@code inventory.consume}）。
     *
     * <p><b>为什么这条规则是「饥饿闭环」的关键</b>：{@code PhysioNeedService} 早就知道
     * 谁饿了、也知道该去哪个地点吃饭，但它的恢复是纯文本的——{@code NpcStateService}
     * 看见「用餐」两个字就把饥饿补回去。于是「家里有粮」和「家里没粮」对世界毫无区别，
     * 面包只是背包里的装饰。这里补上最后一环：<b>说吃了什么，就得少一件什么</b>。</p>
     *
     * <p><b>为什么必须「点名」才扣</b>：行动文本可能是「去酒馆吃饭」「在集市买张饼」——
     * 那是别人做的饭、还没买的饼，跟背包无关。只有文本里出现他<b>确实带着</b>的那样
     * 吃食的名字（或键）时才扣，避免「去吃席结果把自家口粮扣光」。</p>
     *
     * <p><b>为什么没点到就放行而不是失败</b>：fail-open 是既定口径——明确否定才
     * FAIL。文本里提到「面包房」不代表他要吃自家的面包，判死会误伤买面包这类正常行动。</p>
     */
    ActionVerdict rest(AdjudicationContext ctx) {
        if (!ctx.isInventoryKnown()) {
            return ActionVerdict.success("R-REST-01");
        }
        String action = actionTextOf(ctx);
        if (action.isBlank()) {
            return ActionVerdict.success("R-REST-01");
        }
        ItemView named = namedEdibleOf(ctx.getInventory(), action);
        if (named == null) {
            return ActionVerdict.success("R-REST-01");
        }
        String reason = named.drinkLike() ? "drink" : "consume";
        return ActionVerdict.success("R-REST-01", List.of(consumeOp(ctx, named.itemKey(), reason)));
    }

    /** 决策里的行动文本（{@code action} 优先，回退 {@code actionDetail}；两步都先解包 JSON）。 */
    private static String actionTextOf(AdjudicationContext ctx) {
        JsonNode decision = ctx.getDecision();
        if (decision == null || decision.isNull()) {
            return "";
        }
        String action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("action").asText(""));
        if (action == null || action.isBlank()) {
            action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("actionDetail").asText(""));
        }
        return action == null ? "" : action.trim();
    }

    /**
     * 行动文本里点名的那件吃食（能吃/能喝、还有剩、名字或键出现在文本里）。
     *
     * <p>多件同时命中时取<b>成色最差</b>的那件——先吃快坏的，与 {@code InventoryService}
     * 扣减「低品质先出」的口径一致。</p>
     */
    private static ItemView namedEdibleOf(List<ItemView> items, String action) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        ItemView best = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() <= 0 || !(item.foodLike() || item.drinkLike())) {
                continue;
            }
            boolean named = (item.name() != null && !item.name().isBlank() && action.contains(item.name()))
                    || (item.itemKey() != null && !item.itemKey().isBlank() && action.contains(item.itemKey()));
            if (!named) {
                continue;
            }
            if (best == null || item.condition() < best.condition()) {
                best = item;
            }
        }
        return best;
    }

    /** 构造一次消耗的效果草稿（inventory.consume）。 */
    private static EffectOp consumeOp(AdjudicationContext ctx, String itemKey, String reason) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("from", NpcRefs.of(ctx.getActor().getId()));
        }
        body.put("itemKey", itemKey);
        body.put("qty", 1);
        body.put("reason", reason);
        return new EffectOp(EffectOps.INVENTORY_CONSUME, body, null);
    }

    // ==================== GIVE ====================

    /**
     * R-GIVE-01 要给的物品必须在自己身上（新增）。
     *
     * <p>决策里没写物品键时<b>不臆断</b>：直接按 {@code R-DEFAULT} 放行（fail-open），
     * 否则所有「给」类行动都会被一个缺失字段判死，世界反而失真。</p>
     */
    ActionVerdict give(AdjudicationContext ctx) {
        // 还款闭环：说「还钱/还账/结清」时，账要真的还——这不是「把某件东西给他」。
        // 赊账成交写的是 settled=false，在还款接线之前没有任何一处会把它改回 true，
        // 欠条只会越积越多（真实世界里赊了账是要还的）。
        if (repayIntent(actionTextOf(ctx))) {
            if (!ctx.isInventoryKnown()) {
                return ActionVerdict.unsure("R-GIVE-02", "DATA_UNAVAILABLE",
                        "他摸了摸口袋，却没人知道他到底带没带钱。");
            }
            return ActionVerdict.success("R-GIVE-02", List.of(settleOp(ctx)));
        }
        String itemKey = textArg(ctx, "itemKey");
        if (itemKey == null) {
            itemKey = textArg(ctx, "item");
        }
        if (itemKey == null) {
            return ActionVerdict.success("R-DEFAULT");
        }
        if (!ctx.isInventoryKnown()) {
            return ActionVerdict.unsure("R-GIVE-01", "DATA_UNAVAILABLE",
                    "他摸了摸口袋，却没人知道他到底有没有那东西。");
        }
        if (ItemView.totalOf(ctx.getInventory(), itemKey) <= 0) {
            return ActionVerdict.fail("R-GIVE-01", "NO_ITEM",
                    "他摸了摸口袋，那东西不在身上。");
        }
        return ActionVerdict.success("R-GIVE-01");
    }

    // ==================== 工具 ====================

    /** 是否「还钱」类措辞（还款闭环）。 */
    private static boolean repayIntent(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        return action.contains("还钱") || action.contains("还账") || action.contains("还债")
                || action.contains("还清") || action.contains("结清") || action.contains("清账");
    }

    /** 构造一次还款的效果草稿（market.settle）。 */
    private static EffectOp settleOp(AdjudicationContext ctx) {
        ObjectNode body = MAPPER.createObjectNode();
        if (ctx.getActor() != null && ctx.getActor().getId() != null) {
            body.put("buyer", NpcRefs.of(ctx.getActor().getId()));
        }
        return new EffectOp(EffectOps.MARKET_SETTLE, body, null);
    }

    /** 从决策 params 里取一个文本字段（缺失/空白返回 null，不猜）。 */
    private static String textArg(AdjudicationContext ctx, String field) {
        if (ctx.getArgs() == null) {
            return null;
        }
        JsonNode node = ctx.getArgs().get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** route 事实是否 blocked。 */
    private static boolean blocked(ActorWorldFact fact) {
        String state = attrs(fact).path("state").asText("");
        return "blocked".equalsIgnoreCase(state) || "danger".equalsIgnoreCase(state);
    }

    /** facility 是否关闭。 */
    private static boolean closed(ActorWorldFact fact) {
        return "false".equalsIgnoreCase(attrs(fact).path("open").asText("true"));
    }

    /** 解析 attributes_json；坏 JSON 按空对象处理（不阻断裁决）。 */
    private static JsonNode attrs(ActorWorldFact fact) {
        if (fact == null || fact.getAttributesJson() == null || fact.getAttributesJson().isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(fact.getAttributesJson());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
