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
import com.holzyn.actor.domain.agent.runtime.DecisionSource;
import com.holzyn.actor.domain.combat.model.CombatRequest;
import com.holzyn.actor.domain.combat.model.CombatResult;
import com.holzyn.actor.domain.combat.service.CombatService;
import com.holzyn.actor.domain.craft.model.CraftJobResult;
import com.holzyn.actor.domain.craft.model.CraftStartRequest;
import com.holzyn.actor.domain.craft.service.CraftService;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.market.model.TradeRequest;
import com.holzyn.actor.domain.market.model.TradeResult;
import com.holzyn.actor.domain.market.service.MarketService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.effect.model.EffectApplyException;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectOps;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.effect.model.EffectResult;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.model.FactTypes;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.service.InfoDiffusionService;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 效果统一入口实现（§4.6）。
 *
 * <p><b>事务边界</b>：本类方法带 {@code @Transactional}，事务内只做规则/DB 写入，
 * <b>绝不调用 AI</b>；任一 op 抛错 → 整批回滚，异常携带失败下标供上层写
 * {@code EFFECT_FAILED:{i}}。</p>
 *
 * <p><b>降级</b>：物品域未装配（{@link InventoryPort} = null）时，{@code inventory.*}
 * 记为 {@code SKIPPED_NO_INVENTORY} 并继续，不抛异常。其余 op 缺依赖/缺字段
 * 均按失败处理，避免出现“看起来执行了但什么都没发生”的半真状态。</p>
 */
@Slf4j
@Service
public class EffectApplierImpl implements EffectApplier {

    @Autowired(required = false)
    private WorldFactService worldFactService;

    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    @Autowired(required = false)
    private InventoryPort inventoryPort;

    /**
     * 对接：物品变化后同步 {@code stock:*} 事实（让「货少了」这件事被旁人看见）。
     *
     * <p>只对地点持有者生效（个人背包不写事实）；失败只告警，不回滚已完成的物品变更。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.item.fact.ItemStockFacts stockFacts;

    /** 市场结算（成交 = 扣货 + 收钱 + 报价 + 流水，必须原子） */
    @Autowired(required = false)
    private MarketService marketService;

    /** 制作（开工/作业；null 或未启用 → craft.make 记 SKIPPED_DISABLED，不抛异常） */
    @Autowired(required = false)
    private CraftService craftService;

    /** 装备（item.equip；null 或未启用 → SKIPPED_DISABLED） */
    @Autowired(required = false)
    private EquipmentService equipmentService;

    /** 战斗（combat.strike；null 或未启用 → SKIPPED_DISABLED） */
    @Autowired(required = false)
    private CombatService combatService;

    /** 损耗域配置（item.repair 的开关与默认修复点数） */
    @Autowired(required = false)
    private WearProperties wearProperties;

    @Autowired(required = false)
    private RelationDynamicsService relationDynamicsService;

    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;

    @Autowired(required = false)
    private InfoDiffusionService infoDiffusionService;

    @Autowired(required = false)
    private WorldMemoryService worldMemoryService;

    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** AgentRuntime 反向依赖 ActionAdjudicationService → ActionEffectService → EffectApplier；
     * 用 @Lazy 打破启动期环（真实环，非单测假设），运行期首次调用时才解析。 */
    @Lazy
    @Autowired(required = false)
    private AgentRuntime agentRuntime;

    /**
     * 应用一批效果（失败抛异常，由代理回滚事务）。
     *
     * @param ops 效果列表
     * @param ctx 上下文
     */
    @Override
    @Transactional
    public void apply(List<EffectOp> ops, EffectContext ctx) {
        applyWithReport(ops, ctx);
    }

    /**
     * 应用并返回逐 op 结果。
     *
     * @param ops 效果列表
     * @param ctx 上下文
     * @return 报告
     */
    @Override
    @Transactional
    public EffectReport applyWithReport(List<EffectOp> ops, EffectContext ctx) {
        if (ops == null || ops.isEmpty()) {
            return EffectReport.empty();
        }
        if (ctx == null || ctx.projectId() == null) {
            throw new EffectApplyException(0, ops.get(0).op(), new IllegalArgumentException("效果上下文缺少 projectId"));
        }
        List<EffectResult> results = new ArrayList<>(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            EffectOp op = ops.get(i);
            String name = op == null ? "" : op.op();
            try {
                results.add(applyOne(i, op, ctx));
            } catch (RuntimeException e) {
                throw new EffectApplyException(i, name, e);
            }
        }
        return new EffectReport(results);
    }

    // ==================== 分派 ====================

    private EffectResult applyOne(int index, EffectOp op, EffectContext ctx) {
        if (op == null) {
            throw new IllegalArgumentException("效果 op 不能为空");
        }
        String name = EffectOps.require(op.op());
        JsonNode body = bodyOf(op);
        return switch (name) {
            case EffectOps.FACT_UPSERT -> factUpsert(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.FACT_EXPIRE -> factExpire(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.INVENTORY_TRANSFER -> inventoryTransfer(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.INVENTORY_CONSUME -> inventoryConsume(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.MARKET_TRADE -> marketTrade(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.MARKET_SETTLE -> marketSettle(index, name, body, ctx);
            case EffectOps.CRAFT_MAKE -> craftMake(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.ITEM_EQUIP -> itemEquip(index, name, body, ctx);
            case EffectOps.ITEM_REPAIR -> itemRepair(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.COMBAT_STRIKE -> combatStrike(index, name, body, ctx, idempotency(op, ctx, index));
            case EffectOps.RELATION_DELTA -> relationDelta(index, name, body, ctx);
            case EffectOps.MEMORY_ADD -> memoryAdd(index, name, body, ctx);
            case EffectOps.INFO_PACKET -> infoPacket(index, name, body, ctx);
            case EffectOps.ENTITY_UPSERT -> entityUpsert(index, name, body, ctx);
            case EffectOps.STATE_URGENCY -> stateUrgency(index, name, body, ctx);
            case EffectOps.SCHEDULE_PLAN -> schedulePlan(index, name, body, ctx);
            default -> throw new IllegalArgumentException("未实现的效果 op：" + name);
        };
    }

    // ==================== fact.* ====================

    private EffectResult factUpsert(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (worldFactService == null) {
            throw new IllegalStateException("WorldFactService 未装配");
        }
        Long eventId = requireEventId(ctx);
        String factKey = text(body, "factKey", true);
        String factType = FactTypes.require(text(body, "factType", true));
        String stateText = text(body, "stateText", true);
        ActorWorldFact existing = existingFact(ctx.projectId(), factKey);
        String name = firstNonBlank(textOrNull(body, "name"),
                existing == null ? null : existing.getName(), factKey);
        String visibility = FactTypes.requireVisibility(textOrNull(body, "visibility"),
                existing == null ? factType : existing.getFactType());
        FactAuthority authority = authorityOf(textOrNull(body, "authority"), existing);
        Long locationId = longOrNull(body, "locationId",
                existing == null ? null : existing.getLocationId());
        String locationName = firstNonBlank(textOrNull(body, "locationName"),
                existing == null ? null : existing.getLocationName(), ctx.locationName());
        Long validFrom = longOrNull(body, "validFromGameSecond",
                existing == null ? null : existing.getValidFromGameSecond());
        Long validTo = longOrNull(body, "validToGameSecond",
                existing == null ? null : existing.getValidToGameSecond());
        String attrs = jsonText(body.get("attributes"));
        if (attrs == null && existing != null) {
            attrs = existing.getAttributesJson();
        }
        FactCommand cmd = new FactCommand(ctx.projectId(), factType, factKey, name, stateText,
                attrs, locationId, locationName, visibility, authority,
                validFrom == null ? ctx.gameSecond() : validFrom, validTo,
                ctx.gameSecond(), ctx.actorRef(), eventId, intOr(body, "importance", 3), idem);
        FactChange change = worldFactService.upsert(cmd);
        Long factId = change == null || change.fact() == null ? null : change.fact().getId();
        return EffectResult.applied(index, op, factId, null);
    }

    private EffectResult factExpire(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (worldFactService == null || worldFactQuery == null) {
            throw new IllegalStateException("事实层未装配");
        }
        Long eventId = requireEventId(ctx);
        String factKey = text(body, "factKey", true);
        ActorWorldFact existing = existingFact(ctx.projectId(), factKey);
        if (existing == null) {
            throw new IllegalArgumentException("事实不存在：" + factKey);
        }
        FactCommand cmd = new FactCommand(ctx.projectId(), existing.getFactType(), factKey,
                existing.getName(), existing.getStateText(), existing.getAttributesJson(),
                existing.getLocationId(), existing.getLocationName(),
                existing.getVisibility(), authorityOf(existing.getAuthority(), existing),
                existing.getValidFromGameSecond(), existing.getValidToGameSecond(),
                ctx.gameSecond(), ctx.actorRef(), eventId, 3, idem);
        FactChange change = worldFactService.expire(cmd, textOrNull(body, "reason"));
        Long factId = change == null || change.fact() == null ? null : change.fact().getId();
        return EffectResult.applied(index, op, factId, null);
    }

    // ==================== inventory.* ====================

    private EffectResult inventoryTransfer(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        String itemKey = text(body, "itemKey", true);
        int qty = intOr(body, "qty", 1);
        if (qty <= 0) {
            throw new IllegalArgumentException("inventory.transfer 的 qty 必须为正数");
        }
        String fromRef = firstNonBlank(textOrNull(body, "from"), ctx.actorRef());
        String toRef = textOrNull(body, "to");
        if (toRef == null) {
            throw new IllegalArgumentException("inventory.transfer 缺少 to");
        }
        inventoryPort.transfer(ctx.projectId(), itemKey, qty, fromRef, toRef,
                textOrNull(body, "reason"), ctx.eventId(), ctx.gameSecond(), idem);
        if (stockFacts != null) {
            stockFacts.syncPair(ctx.projectId(), fromRef, toRef, itemKey, ctx.gameSecond());
        }
        return EffectResult.applied(index, op, null, null);
    }

    /**
     * 消耗（对接）：吃饭/喝水/用药——从背包里拿掉，没有目标持有者。
     *
     * <p>与 {@code inventory.transfer} 的区别只在「货去哪了」：transfer 必须有收件人，
     * consume 就是消失了。硬用 transfer 顶替会往世界账本里塞一个虚拟持有者，把「全库
     * 总量」算多一份。</p>
     */
    private EffectResult inventoryConsume(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        String itemKey = text(body, "itemKey", true);
        int qty = intOr(body, "qty", 1);
        if (qty <= 0) {
            throw new IllegalArgumentException("inventory.consume 的 qty 必须为正数");
        }
        String fromRef = firstNonBlank(textOrNull(body, "from"), ctx.actorRef());
        if (fromRef == null) {
            throw new IllegalArgumentException("inventory.consume 缺少 from");
        }
        String reason = firstNonBlank(textOrNull(body, "reason"), "consume");
        inventoryPort.consume(ctx.projectId(), itemKey, qty, fromRef, reason,
                ctx.eventId(), ctx.gameSecond(), idem);
        if (stockFacts != null) {
            stockFacts.syncHolder(ctx.projectId(), fromRef, itemKey, ctx.gameSecond());
        }
        return EffectResult.applied(index, op, null, null);
    }

    // ==================== market.trade ====================

    /**
     * 市场成交：货、钱、报价、流水在同一个事务里完成。
     *
     * <p>降级口径与 {@code inventory.transfer} 一致：物品域未装配 → SKIPPED；市场开关关闭 →
     * SKIPPED_DISABLED。两者都不抛异常，保证「关开关 = 行为回基线」。</p>
     */
    private EffectResult marketTrade(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        if (marketService == null || !marketService.enabled()) {
            log.info("[效果] 市场未启用，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.disabled(index, op, "市场未启用");
        }
        String itemKey = text(body, "itemKey", true);
        int qty = intOr(body, "qty", 1);
        if (qty <= 0) {
            throw new IllegalArgumentException("market.trade 的 qty 必须为正数");
        }
        String sellerRef = text(body, "seller", true);
        String buyerRef = firstNonBlank(textOrNull(body, "buyer"), ctx.actorRef());
        if (buyerRef == null) {
            throw new IllegalArgumentException("market.trade 缺少 buyer");
        }
        Integer unitPrice = body.hasNonNull("unitPrice") ? intOr(body, "unitPrice", 0) : null;
        boolean credit = body.path("credit").asBoolean(false);
        TradeResult result = marketService.trade(new TradeRequest(ctx.projectId(), sellerRef, buyerRef,
                itemKey, qty, unitPrice, credit, textOrNull(body, "reason"),
                ctx.eventId(), ctx.gameSecond(), idem));
        log.info("[效果] 市场成交落地: trade={} {} x{} 单价={} 赊账={}",
                result.tradeId(), itemKey, result.qty(), result.unitPrice(), result.credit());
        return EffectResult.applied(index, op, null, null);
    }

    // ==================== market.settle（还款） ====================

    /**
     * 赊账结清（还款闭环）：把「还钱」这句话变成真的还钱。
     *
     * <p>与 {@code market.trade} 同口径：市场未启用 → SKIPPED_DISABLED（关开关=回基线），
     * 不抛异常。钱不够时 {@link MarketService#settleDebts} 返回 0，账不动——
     * 「说了还钱但还不起」在可见文本里由规则层说明，这里不做部分还款。</p>
     */
    private EffectResult marketSettle(int index, String op, JsonNode body, EffectContext ctx) {
        if (marketService == null || !marketService.enabled()) {
            log.info("[效果] 市场未启用，降级跳过: op={}", op);
            return EffectResult.disabled(index, op, "市场未启用");
        }
        String buyerRef = firstNonBlank(textOrNull(body, "buyer"), ctx.actorRef());
        if (buyerRef == null) {
            throw new IllegalArgumentException("market.settle 缺少 buyer");
        }
        long gameSecond = ctx.gameSecond();
        int settled = marketService.settleDebts(ctx.projectId(), buyerRef, gameSecond);
        log.info("[效果] 赊账结清落地: buyer={} 笔数={}", buyerRef, settled);
        return EffectResult.applied(index, op, null, null);
    }

    // ==================== craft.make ====================

    /**
     * 制作开工：按配方扣多份材料 + 建计时作业 + 占设施，在同一事务里完成。
     *
     * <p>降级口径与 {@code market.trade} 一致：物品域未装配 → SKIPPED；制作开关关闭 →
     * SKIPPED_DISABLED。两者都不抛异常，保证「关开关 = 行为回基线」。</p>
     */
    private EffectResult craftMake(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} recipe={}", op, textOrNull(body, "recipeKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        if (craftService == null || !craftService.enabled()) {
            log.info("[效果] 制作未启用，降级跳过: op={} recipe={}", op, textOrNull(body, "recipeKey"));
            return EffectResult.disabled(index, op, "制作未启用");
        }
        String recipeKey = text(body, "recipeKey", true);
        int batches = intOr(body, "batches", 1);
        if (batches <= 0) {
            throw new IllegalArgumentException("craft.make 的 batches 必须为正数");
        }
        String actorRef = firstNonBlank(textOrNull(body, "actor"), ctx.actorRef());
        if (actorRef == null) {
            throw new IllegalArgumentException("craft.make 缺少 actor");
        }
        Long locationId = body.hasNonNull("locationId") ? longOrNull(body, "locationId", null) : null;
        String locationName = firstNonBlank(textOrNull(body, "locationName"), ctx.locationName());
        CraftJobResult result = craftService.start(new CraftStartRequest(ctx.projectId(), actorRef,
                recipeKey, batches, textOrNull(body, "facilityRef"), locationId, locationName,
                ctx.eventId(), ctx.gameSecond(), idem));
        log.info("[效果] 制作开工落地: job={} {} x{} 预计完工={}",
                result.jobId(), result.recipeKey(), result.batches(), result.finishGameSecond());
        return EffectResult.applied(index, op, null, null);
    }

    // ==================== item.equip / item.repair / combat.strike ====================

    /**
     * 穿/脱：改槽位 + 重写 {@code equip:*} 事实，同一事务。
     *
     * <p>降级口径与前几个一致：物品域未装配 → SKIPPED；损耗域关闭 → SKIPPED_DISABLED。</p>
     */
    private EffectResult itemEquip(int index, String op, JsonNode body, EffectContext ctx) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        if (equipmentService == null || !equipmentService.enabled()) {
            log.info("[效果] 损耗域未启用，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.disabled(index, op, "损耗域未启用");
        }
        String actorRef = firstNonBlank(textOrNull(body, "actor"), ctx.actorRef());
        if (actorRef == null) {
            throw new IllegalArgumentException("item.equip 缺少 actor");
        }
        String itemKey = text(body, "itemKey", true);
        boolean equipped = body.path("equipped").asBoolean(true);
        String slot = textOrNull(body, "slot");
        if (equipped) {
            equipmentService.equip(ctx.projectId(), actorRef, slot, itemKey, ctx.gameSecond());
        } else {
            equipmentService.unequip(ctx.projectId(), actorRef, slot, ctx.gameSecond());
        }
        log.info("[效果] 装备变更落地: {} {} {}（slot={}）", actorRef,
                equipped ? "穿上" : "脱下", itemKey, slot);
        return EffectResult.applied(index, op, null, null);
    }

    /**
     * 修理：耐久补回去。
     *
     * <p>东西不在身上时抛错而不是静默成功——「修了个不存在的东西」是假事件。</p>
     */
    private EffectResult itemRepair(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        if (wearProperties == null || !wearProperties.isEnabled()) {
            log.info("[效果] 损耗域未启用，降级跳过: op={} key={}", op, textOrNull(body, "itemKey"));
            return EffectResult.disabled(index, op, "损耗域未启用");
        }
        String actorRef = firstNonBlank(textOrNull(body, "actor"), ctx.actorRef());
        if (actorRef == null) {
            throw new IllegalArgumentException("item.repair 缺少 actor");
        }
        String itemKey = text(body, "itemKey", true);
        int points = intOr(body, "points", Math.max(1, wearProperties.getRepairPoints()));
        DurabilityChange change = inventoryPort.restore(ctx.projectId(), actorRef, itemKey, points,
                ctx.eventId(), ctx.gameSecond(), idem);
        if (!change.present()) {
            throw new IllegalArgumentException("要修的东西不在身上：" + itemKey);
        }
        log.info("[效果] 修理落地: {} {} 耐久 {}→{}", actorRef, itemKey, change.before(), change.after());
        return EffectResult.applied(index, op, null, null);
    }

    /**
     * 一击：扣血 + 磨损 + 顺手抽刀 + 让对方记住，全在一个事务里。
     *
     * <p>没打出去（目标不在/空手/家伙断了）不抛异常：那是世界的真实结果，
     * 由裁决层写成 {@code FAIL} 的可见文本；这里只记日志，不制造「执行失败」。</p>
     */
    private EffectResult combatStrike(int index, String op, JsonNode body, EffectContext ctx, String idem) {
        if (inventoryPort == null) {
            log.info("[效果] 物品域未装配，降级跳过: op={} target={}", op, textOrNull(body, "target"));
            return EffectResult.skipped(index, op, "InventoryPort 未装配");
        }
        if (combatService == null || !combatService.enabled()) {
            log.info("[效果] 损耗域未启用，降级跳过: op={} target={}", op, textOrNull(body, "target"));
            return EffectResult.disabled(index, op, "损耗域未启用");
        }
        String attacker = firstNonBlank(textOrNull(body, "attacker"), ctx.actorRef());
        String target = text(body, "target", true);
        if (attacker == null) {
            throw new IllegalArgumentException("combat.strike 缺少 attacker");
        }
        CombatResult result = combatService.strike(new CombatRequest(ctx.projectId(), attacker, target,
                textOrNull(body, "weaponKey"), ctx.gameSecond(), ctx.eventId(), idem));
        log.info("[效果] 战斗落地: {} → {} 伤害 {} 原因 {}（武器 {} 耐久 {}→{}）",
                attacker, target, result.damage(), result.reason() == null ? "-" : result.reason(),
                result.weaponItemKey(), result.durabilityBefore(), result.durabilityAfter());
        return EffectResult.applied(index, op, null, null);
    }

    // ==================== 既有领域服务 ====================
    private EffectResult relationDelta(int index, String op, JsonNode body, EffectContext ctx) {
        if (relationDynamicsService == null) {
            throw new IllegalStateException("RelationDynamicsService 未装配");
        }
        String fromRef = firstNonBlank(textOrNull(body, "from"), ctx.actorRef());
        String toRef = textOrNull(body, "to");
        if (fromRef == null || toRef == null) {
            throw new IllegalArgumentException("relation.delta 缺少 from/to");
        }
        int affinity = intOr(body, "affinity", 0);
        int trust = body.hasNonNull("trust") ? intOr(body, "trust", affinity / 2) : affinity / 2;
        relationDynamicsService.updateRelation(ctx.projectId(), fromRef, toRef, affinity, trust,
                textOrNull(body, "reason"));
        return EffectResult.applied(index, op, null, null);
    }

    private EffectResult memoryAdd(int index, String op, JsonNode body, EffectContext ctx) {
        if (agentMemoryService == null) {
            throw new IllegalStateException("AgentMemoryService 未装配");
        }
        String ref = firstNonBlank(textOrNull(body, "ref"), ctx.actorRef());
        String content = text(body, "content", true);
        if (ref == null) {
            throw new IllegalArgumentException("memory.add 缺少 ref");
        }
        agentMemoryService.recordObservation(ctx.projectId(), ref, content,
                intOr(body, "importance", 3), decisionSource(textOrNull(body, "kind")));
        return EffectResult.applied(index, op, null, null);
    }

    private EffectResult infoPacket(int index, String op, JsonNode body, EffectContext ctx) {
        if (infoDiffusionService == null) {
            throw new IllegalStateException("InfoDiffusionService 未装配");
        }
        Long eventId = requireEventId(ctx);
        String content = text(body, "content", true);
        String subject = textOrNull(body, "subject");
        String visibility = firstNonBlank(textOrNull(body, "visibility"), "location");
        double reliability = doubleOr(body, "reliability", 0.7d);
        String locationName = firstNonBlank(textOrNull(body, "locationName"), ctx.locationName());
        infoDiffusionService.publishPacket(ctx.projectId(), eventId, content, subject,
                visibility, reliability, locationName, textOrNull(body, "factKey"), ctx.gameSecond());
        return EffectResult.applied(index, op, null, null);
    }

    private EffectResult entityUpsert(int index, String op, JsonNode body, EffectContext ctx) {
        if (worldMemoryService == null) {
            throw new IllegalStateException("WorldMemoryService 未装配");
        }
        String subject = text(body, "subject", true);
        String content = text(body, "content", true);
        String entityType = firstNonBlank(textOrNull(body, "entityType"), "concept");
        long day = Math.floorDiv(ctx.gameSecond(), 86400L);
        worldMemoryService.upsertEntityState(ctx.projectId(), subject, entityType, subject,
                content, null, day, "effect", ctx.eventId());
        return EffectResult.applied(index, op, null, null);
    }

    private EffectResult stateUrgency(int index, String op, JsonNode body, EffectContext ctx) {
        if (npcStateService == null) {
            throw new IllegalStateException("NpcStateService 未装配");
        }
        String ref = firstNonBlank(textOrNull(body, "ref"), ctx.actorRef());
        if (ref == null) {
            throw new IllegalArgumentException("state.urgency 缺少 ref");
        }
        npcStateService.applyUrgencyDelta(ctx.projectId(), ref, intOr(body, "delta", 0),
                textOrNull(body, "reason"));
        return EffectResult.applied(index, op, null, null);
    }

    private EffectResult schedulePlan(int index, String op, JsonNode body, EffectContext ctx) {
        if (agentRuntime == null) {
            throw new IllegalStateException("AgentRuntime 未装配");
        }
        String ref = firstNonBlank(textOrNull(body, "ref"), ctx.actorRef());
        if (ref == null) {
            throw new IllegalArgumentException("schedule.plan 缺少 ref");
        }
        String actionJson = jsonText(body.get("actionJson"));
        if (actionJson == null || actionJson.isBlank()) {
            throw new IllegalArgumentException("schedule.plan 缺少 actionJson");
        }
        Long planId = agentRuntime.planLater(ctx.projectId(), ref, actionJson,
                intOr(body, "delayMinutes", 10), ctx.gameSecond(), ctx.eventId());
        return EffectResult.applied(index, op, null, planId);
    }

    // ==================== 辅助 ====================

    private static JsonNode bodyOf(EffectOp op) {
        JsonNode body = op.body();
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("效果 op 的 body 必须是 JSON 对象：" + op.op());
        }
        return body;
    }

    private static String idempotency(EffectOp op, EffectContext ctx, int index) {
        String key = op.idempotencyKey();
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        if (ctx.planId() != null) {
            return "plan:" + ctx.planId() + ":op:" + index;
        }
        if (ctx.eventId() != null) {
            return "event:" + ctx.eventId() + ":op:" + index;
        }
        return "effect:" + ctx.projectId() + ":op:" + index;
    }

    private ActorWorldFact existingFact(Long projectId, String factKey) {
        if (worldFactQuery == null) {
            return null;
        }
        Optional<ActorWorldFact> found = worldFactQuery.byKey(projectId, factKey);
        return found == null ? null : found.orElse(null);
    }

    private static Long requireEventId(EffectContext ctx) {
        if (ctx.eventId() == null) {
            throw new IllegalArgumentException("事实/信息包效果缺少来源事件 ID（A17）");
        }
        return ctx.eventId();
    }

    private static FactAuthority authorityOf(String raw, ActorWorldFact existing) {
        String value = raw;
        if ((value == null || value.isBlank()) && existing != null) {
            value = existing.getAuthority();
        }
        if (value == null || value.isBlank()) {
            return FactAuthority.BRAIN;
        }
        try {
            return FactAuthority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法事实权威：" + raw);
        }
    }

    private static DecisionSource decisionSource(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return DecisionSource.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode body, String field, boolean required) {
        String value = textOrNull(body, field);
        if ((value == null || value.isBlank()) && required) {
            throw new IllegalArgumentException("效果 body 缺少字段：" + field);
        }
        return value;
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body == null ? null : body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String value = n.isTextual() ? n.asText() : n.toString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longOrNull(JsonNode body, String field, Long fallback) {
        JsonNode n = body == null ? null : body.get(field);
        if (n == null || n.isNull()) {
            return fallback;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        String text = n.asText();
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("效果字段不是整数：" + field + "=" + text);
        }
    }

    private static int intOr(JsonNode body, String field, int fallback) {
        Long value = longOrNull(body, field, null);
        return value == null ? fallback : value.intValue();
    }

    private static double doubleOr(JsonNode body, String field, double fallback) {
        JsonNode n = body == null ? null : body.get(field);
        if (n == null || n.isNull()) {
            return fallback;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        try {
            return Double.parseDouble(n.asText());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String jsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
