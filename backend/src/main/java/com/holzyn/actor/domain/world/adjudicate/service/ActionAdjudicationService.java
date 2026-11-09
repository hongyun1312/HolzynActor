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
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.craft.entity.ActorCraftJob;
import com.holzyn.actor.domain.craft.facility.CraftFacilities;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.service.CraftService;
import com.holzyn.actor.domain.equip.model.EquipmentSlot;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.market.model.MarketQuote;
import com.holzyn.actor.domain.market.service.MarketService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.effect.model.EffectApplyException;
import com.holzyn.actor.domain.world.effect.service.ActionEffectService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 行动裁决入口（§4.7）。
 *
 * <p><b>事务边界</b>：本类<b>不加 {@code @Transactional}</b>——先做确定性规则，必要时在事务外调一次
 * AI，最后才进入 {@link AgentActionService#executeWithVerdict} 的事务。这样 H2 行锁不会跨 AI 时长。</p>
 *
 * <p><b>开关等价</b>：{@code holzyn.actor.world-loop.enabled=false} 或
 * {@code adjudicate.enabled=false} 时直接走旧 {@link AgentActionService#execute} 透传：
 * 不读裁决上下文、不写 verdict/reason/effects/event_id，行为与之前逐字节一致。</p>
 */
@Slf4j
@Service
public class ActionAdjudicationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 低重要度 UNSURE 的放行原因码（不调 AI）。 */
    static final String LOW_IMPORTANCE_PASS = "LOW_IMPORTANCE_PASS";

    @Autowired(required = false)
    private WorldLoopProperties properties;

    @Autowired(required = false)
    private IntentClassifier intentClassifier;

    @Autowired(required = false)
    private ActionAdjudicator adjudicator;

    @Autowired(required = false)
    private AdjudicationAiService aiService;

    @Autowired(required = false)
    private AgentActionService agentActionService;

    /** 行动执行 + 效果落地（同一事务；null=只走执行） */
    @Autowired(required = false)
    private ActionEffectService actionEffectService;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Autowired(required = false)
    private ActorCharacterRelationRepository relationRepository;

    @Autowired(required = false)
    private NpcStateService npcStateService;

    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 物品清单（null=物品域未启用/未装配 → 裁决按「未知」处理，行为回） */
    @Autowired(required = false)
    private InventoryPort inventoryPort;

    /** 市场（报价/货架/资金；null 或未启用 → TRADE 保持「未知」） */
    @Autowired(required = false)
    private MarketService marketService;

    /** 制作（配方/设施/在制作业；null 或未启用 → CRAFT 保持「配方未知」） */
    @Autowired(required = false)
    private CraftService craftService;

    /** 装备（手上那件/身上戴着什么；null 或未启用 → ATTACK 走旧口径） */
    @Autowired(required = false)
    private EquipmentService equipmentService;

    /** 损耗域配置（判定「损耗数据可不可信」） */
    @Autowired(required = false)
    private WearProperties wearProperties;

    /** 物品域配置（装备解析要求物品域也开着） */
    @Autowired(required = false)
    private ItemProperties itemProperties;

    /**
     * 裁决开关是否开启（总开关 + 裁决段）。
     *
     * @return true=走裁决
     */
    public boolean enabled() {
        return properties != null && properties.isEnabled()
                && properties.getAdjudicate() != null && properties.getAdjudicate().isEnabled();
    }

    /**
     * 带裁决执行计划（替代直接 {@code AgentActionService.execute}）。
     *
     * <p>规则/AI 在事务外完成；FAIL 只落可见失败事件并取消计划，不产生原行动效果。</p>
     *
     * @param plan 行动计划
     */
    public void executeGated(ActorActionPlan plan) {
        if (plan == null || agentActionService == null) {
            return;
        }
        if (!enabled()) {
            agentActionService.execute(plan);
            return;
        }
        // 主动性计划只走规则裁决，禁止 AI（scan 零 AI + 成本可控）。
        if ("initiative".equalsIgnoreCase(plan.getTriggerType())) {
            agentActionService.executeWithVerdict(plan, adjudicateInitiative(plan));
            return;
        }
        ActionVerdict verdict;
        try {
            verdict = adjudicateBeforeExecute(plan);
        } catch (Exception e) {
            // 裁决层自身异常不得阻断世界：降级 PARTIAL 原样执行（用户口径：绝不 FAIL）。
            log.warn("[裁决] 组装/裁决异常，降级 PARTIAL 放行: plan={} : {}", plan.getId(), e.getMessage());
            verdict = ActionVerdict.of(Verdict.PARTIAL, "ADJUDICATION_ERROR",
                    "他试了，但结果一时说不清。", 0, List.of(), "fallback");
        }
        if (actionEffectService == null) {
            agentActionService.executeWithVerdict(plan, verdict);
            return;
        }
        try {
            actionEffectService.execute(plan, verdict);
        } catch (EffectApplyException e) {
            // 效果事务已整批回滚；用新事务把计划置 cancelled + EFFECT_FAILED:{i}，不落可见事件。
            agentActionService.markEffectFailed(plan.getId(), e.index());
            log.warn("[裁决] 效果落地失败，已回滚整批: plan={} op[{}]={} : {}",
                    plan.getId(), e.index(), e.op(), e.getMessage());
        }
    }

    /**
     * 主动性规则裁决：只跑规则，UNSURE 统一降为 PARTIAL，绝不调用 AI。
     *
     * @param plan 主动性计划
     *
     * @return 三态裁决（非 UNSURE）
     */
    private ActionVerdict adjudicateInitiative(ActorActionPlan plan) {
        try {
            AdjudicationContext ctx = buildContext(plan);
            if (ctx == null || adjudicator == null) {
                return ActionVerdict.success("R-INITIATIVE");
            }
            ActionVerdict rule = adjudicator.adjudicate(ctx);
            if (rule == null) {
                return ActionVerdict.success("R-INITIATIVE");
            }
            if (rule.verdict() == Verdict.UNSURE) {
                return ActionVerdict.of(Verdict.PARTIAL, "INITIATIVE_UNSURE",
                        rule.observedText(), 0, List.of(), "rule");
            }
            return rule;
        } catch (Exception e) {
            log.warn("[裁决] 主动性规则裁决异常，降级放行: plan={} : {}", plan.getId(), e.getMessage());
            return ActionVerdict.success("R-INITIATIVE-FALLBACK");
        }
    }

    /**
     * 事务外裁决：规则优先，UNSURE 时按重要度决定 AI 或 PARTIAL。
     *
     * @param plan 行动计划
     *
     * @return 三态裁决（绝不返回 UNSURE）
     */
    public ActionVerdict adjudicateBeforeExecute(ActorActionPlan plan) {
        AdjudicationContext ctx = buildContext(plan);
        if (ctx == null) {
            return ActionVerdict.success("R-DEFAULT");
        }
        ActionVerdict rule = adjudicator == null ? ActionVerdict.success("R-DEFAULT") : adjudicator.adjudicate(ctx);
        if (rule == null) {
            rule = ActionVerdict.success("R-DEFAULT");
        }
        if (rule.verdict() != Verdict.UNSURE) {
            return rule;
        }
        int aiMin = properties.getAdjudicate().getAiMinImportance();
        if (ctx.getImportance() < aiMin) {
            return ActionVerdict.of(Verdict.PARTIAL, LOW_IMPORTANCE_PASS,
                    rule.observedText(), 0, List.of(), "rule");
        }
        ActionVerdict ai = aiService == null ? null : aiService.adjudicate(ctx);
        if (ai == null || ai.verdict() == Verdict.UNSURE) {
            return ActionVerdict.of(Verdict.PARTIAL, "AI_UNAVAILABLE",
                    rule.observedText(), 0, List.of(), "fallback");
        }
        return ai;
    }

    /**
     * 组装只读上下文（事务外；所有查询失败按“无记录”降级，不阻断行动）。
     *
     * @param plan 行动计划
     *
     * @return 上下文；角色不存在返回 null
     */
    public AdjudicationContext buildContext(ActorActionPlan plan) {
        ActorCharacter actor = characterRepository == null ? null
                : characterRepository.findById(plan.getCharacterId()).orElse(null);
        if (actor == null || actor.getProjectId() == null) {
            return null;
        }
        Long projectId = actor.getProjectId();
        JsonNode decision = readDecision(plan.getActionJson());
        // 意图分类读的是「动作类型」词表（start_action / move_to / speak_to / wait / continue），
        // 不是语义分类字段。计划 JSON 里两者都有：
        //   {"actionType":"start_action","type":"rest","action":"吃随身的面包充饥",...}
        // 只读 type 会把每一个计划都判成 OTHER（rest/custom/move/speak 都不在词表里），
        // 于是裁决器整条规则链形同虚设：说吃了不扣粮、说买了不成交、说做了不出货。
        // 整世界长跑实测：8 个角色 3 游戏小时零 consume 零 trade。
        String type = decision.path("actionType").asText("");
        if (type.isBlank()) {
            type = decision.path("type").asText("");
        }
        // 动作文本先解包再分类——AI 偶尔把内层契约套成字符串（{"action":"…"}），
        // 不解包就会拿整段 JSON 去认意图/认买卖词，结果为「什么都没在发生」。
        String action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("action").asText(""));
        IntentKind intent = intentClassifier == null ? IntentKind.OTHER
                : intentClassifier.classify(type, action);
        // 旧形态计划（空间候选/发育骨架）只有语义 type（rest/move/custom），没有 actionType：
        // 按词表分类必然 OTHER，于是一律 R-DEFAULT 放行——「在集市口歇脚，等对方买完再进镇买吃食」
        // 这类明明要去买的计划就白白放过去了（复测：29 条含「买」的决策 0 笔成交）。
        // 口径：**只在词表分类落空时**再按自由文本认一次，绝不覆盖已判出的意图
        // （避免把 speak/move 计划错判成 TRADE）。
        if (intent == IntentKind.OTHER && intentClassifier != null
                && decision.path("actionType").asText("").isBlank() && !action.isBlank()) {
            intent = intentClassifier.classify("start_action", action);
        }
        String target = decision.path("target").asText("");
        if (target.isBlank()) {
            target = decision.path("params").path("move").path("to").asText("");
        }
        target = target == null ? "" : target.trim();
        long gameSecond = currentGameSecond(projectId);

        ActorCharacter targetChar = findCharacter(projectId, target);
        String targetRef = targetChar == null ? null : "npc:" + targetChar.getId();
        // 位置只作「明确不在场」的证据：任一方没有 location 记录时返回 null（未知放行），
        // 不能因为 special 角色的 actor_profile.location 为空就误判 TALK NOT_PRESENT。
        Boolean targetPresent = targetChar == null ? null
                : (actor.getLocation() == null || targetChar.getLocation() == null)
                ? null : sameLocation(actor.getLocation(), targetChar.getLocation());

        ActorWorldLocation location = findLocation(projectId, target);
        Boolean targetLocationExists = target.isBlank() ? null : location != null;

        ActorWorldFact route = routeFact(projectId, actor.getLocation(), target, gameSecond);
        ActorWorldFact facility = facilityFact(projectId, location, gameSecond);

        int importance = decision.path("urgency").asInt(
                actor.getImportance() == null ? 3 : actor.getImportance());
        Integer energy = energyOf(projectId, actor);
        int affinity = affinity(projectId, actor.getId(), targetChar, targetRef);
        Integer reputation = reputation(projectId, targetRef);
        List<ActorWorldFact> known = knownFacts(projectId, actor, gameSecond);
        List<ItemView> inventory = List.of();
        boolean inventoryKnown = false;
        String actorRef = NpcRefs.of(actor.getId());
        if (inventoryPort != null) {
            try {
                inventory = inventoryPort.list(projectId, actorRef);
                inventoryKnown = true;
            } catch (RuntimeException e) {
                // 物品域异常不能阻断行动：按「未知」处理，规则会走 UNSURE → 低重要度放行
                log.warn("[裁决] 读取物品清单失败，按未知处理：plan={} : {}", plan.getId(), e.getMessage());
            }
        }

        // 交易双方——买入时柜台=目标（店铺优先，其次摊贩），卖出时柜台=行动者自己
        String targetRefOrLoc = location != null ? "loc:" + location.getId()
                : (targetChar == null ? null : NpcRefs.of(targetChar.getId()));
        // 决策只写了「在卖吃食的摊上买两个烧饼」、没把地点填进 target 时，柜台就是他现在站的地方。
        // 不补这一步，AI 明明说了要买，裁决器却因为「找不到卖家」判 STOCK_UNKNOWN，一分钱不动。
        if (targetRefOrLoc == null && actor.getLocation() != null && !actor.getLocation().isBlank()) {
            ActorWorldLocation here = findLocation(projectId, actor.getLocation());
            if (here != null) {
                targetRefOrLoc = "loc:" + here.getId();
            }
        }
        TradeContext trade = tradeContext(projectId, actorRef, targetRefOrLoc, intent, decision, gameSecond);
        // 制作上下文——配方/设施/在制作业/份数（都在事务外解析，规则层只读）
        CraftContext craft = craftContext(projectId, actor, actorRef, location, intent, decision, gameSecond);
        // 损耗/装备上下文——手上那件/目标健康/待修待穿的东西（同样事务外解析）
        WearContext wear = wearContext(projectId, actorRef, targetRef, intent, decision, inventory,
                inventoryKnown, gameSecond);

        return AdjudicationContext.builder()
                .projectId(projectId)
                .actor(actor)
                .plan(plan)
                .intent(intent)
                .decision(decision)
                .args(decision.path("params"))
                .gameSecond(gameSecond)
                .locationName(actor.getLocation())
                .targetName(target)
                .targetRef(targetRef)
                .targetLocationExists(targetLocationExists)
                .targetPresent(targetPresent)
                .routeFact(route)
                .facilityFact(facility)
                .relationToTarget(affinity)
                .reputation(reputation)
                .importance(Math.max(1, Math.min(5, importance)))
                .energy(energy)
                .knownFacts(known)
                .inventory(inventory)
                .inventoryKnown(inventoryKnown)
                .counterpartyStock(trade.stock())
                .counterpartyStockKnown(trade.stockKnown())
                // 配方/设施/在制作业（制作域未启用时全部保持「未知」→ CRAFT 仍 UNSURE）
                .craftDataKnown(craft.dataKnown())
                .recipe(craft.recipe())
                .recipeKnown(craft.recipe() != null)
                .craftFacilityRef(craft.facilityRef())
                .craftFacilityType(craft.facilityType())
                .craftFacilityName(craft.facilityName())
                .craftLocationId(craft.locationId())
                .craftBatches(craft.batches())
                .craftBusyRecipeKey(craft.busyRecipeKey())
                .craftOccupant(craft.occupant())
                // 损耗/装备（损耗域未启用时 dataKnown=false → ATTACK 回旧口径）
                .wearDataKnown(wear.dataKnown())
                .equipment(wear.equipment())
                .equipmentKnown(wear.equipmentKnown())
                .handEquipment(wear.hand())
                .targetHealth(wear.targetHealth())
                .repairItemKey(wear.repairItemKey())
                .repairTarget(wear.repairTarget())
                .equipItemKey(wear.equipItemKey())
                .equipSlot(wear.equipSlot())
                .equipUnequip(wear.equipUnequip())
                .sellerRef(trade.sellerRef())
                .buyerRef(trade.buyerRef())
                .tradeItemKey(trade.itemKey())
                .tradeQty(trade.qty())
                .unitPrice(trade.unitPrice())
                .currencyKey(trade.currencyKey())
                .funds(trade.funds())
                .credit(trade.credit())
                .buyerReputation(trade.buyerReputation())
                .build();
    }

    /** 「卖出」类措辞：行动者在卖，目标才是买家。 */
    private static final List<String> SELL_MARKERS = List.of("卖", "出售", "售出", "摆摊", "甩卖");

    /**
     * TRADE 的交易上下文（柜台库存/价格/资金/赊账/买方声望）。
     *
     * <p><b>方向</b>：{@code start_action} 的自由文本决定谁是卖方——「卖/出售/摆摊」时
     * 行动者出货、目标是买家；其余按「行动者买」处理。方向搞反会让 NPC 的「摆摊卖货」
     * 变成「从摊位买货」，交易效果直接把货搬反。</p>
     *
     * <p>只在 {@code intent=TRADE} 且市场生效时查；任何一步异常都按「未知」降级（null），
     * 让规则走 UNSURE，而不是把世界判死。</p>
     */
    private TradeContext tradeContext(Long projectId, String actorRef, String targetRef, IntentKind intent,
                                      JsonNode decision, long gameSecond) {
        if (intent != IntentKind.TRADE || marketService == null || !marketService.enabled()) {
            return TradeContext.EMPTY;
        }
        // 买卖方向按动作文本里的词判定，同样先解包（JSON 字面量里不会出现「卖/赊」）
        String action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("action").asText(""));
        if (action == null) {
            action = "";
        }
        boolean actorSells = SELL_MARKERS.stream().anyMatch(action::contains);
        String seller = actorSells ? actorRef : targetRef;
        String buyer = actorSells ? targetRef : actorRef;
        if (seller == null || buyer == null || seller.equals(buyer)) {
            return TradeContext.EMPTY;
        }
        String currencyKey = marketService.currencyKey();
        JsonNode params = decision.path("params");
        String itemKey = textOf(params, "itemKey");
        int qty = Math.max(1, intOf(params, "qty", 1));
        boolean credit = boolOf(params, "credit") || action.contains("赊");
        List<ItemView> stock = List.of();
        boolean stockKnown = false;
        if (inventoryPort != null) {
            try {
                stock = inventoryPort.list(projectId, seller);
                stockKnown = true;
            } catch (RuntimeException e) {
                log.warn("[裁决] 读取柜台库存失败，按未知处理：seller={} : {}", seller, e.getMessage());
            }
        }
        // 决策没写结构化 itemKey 时，从行动文本里认货（「买两件面包」）。
        // 模型输出的是自然语言，params.itemKey 几乎从不出现；只认 params 的话 TRADE 规则
        // 永远停在「能谈但不动账」——整世界长跑实测：3 游戏小时 0 笔成交，柜台货一件没动。
        if ((itemKey == null || itemKey.isBlank()) && stockKnown) {
            ItemView named = namedInText(stock, action);
            if (named != null) {
                itemKey = named.itemKey();
                qty = qtyInText(action, named.name(), qty);
                // 名字没出现在文本里（「买两个素包子」→ 柜台上的面包）时，件数按整句里的数量词取
                if (named.name() == null || !action.contains(named.name())) {
                    qty = qtyInTextAny(action, qty);
                }
            }
        }
        Integer unitPrice = null;
        if (itemKey != null && stockKnown) {
            try {
                MarketQuote quote = marketService.quote(projectId, seller, itemKey, gameSecond);
                unitPrice = quote == null ? null : quote.unitPrice();
            } catch (RuntimeException e) {
                log.warn("[裁决] 读取报价失败，按未知处理：seller={} item={} : {}",
                        seller, itemKey, e.getMessage());
            }
        }
        Integer funds = null;
        if (inventoryPort != null) {
            try {
                funds = inventoryPort.quantityOf(projectId, buyer, currencyKey);
            } catch (RuntimeException e) {
                log.warn("[裁决] 读取资金失败，按未知处理：{} : {}", buyer, e.getMessage());
            }
        }
        return new TradeContext(seller, buyer, itemKey, qty, unitPrice, currencyKey, funds, credit,
                reputation(projectId, buyer), stock, stockKnown);
    }

    /** TRADE 上下文快照（避免给 buildContext 再塞一堆局部变量）。 */
    private record TradeContext(String sellerRef, String buyerRef, String itemKey, int qty,
                                Integer unitPrice, String currencyKey, Integer funds, boolean credit,
                                Integer buyerReputation, List<ItemView> stock, boolean stockKnown) {

        /** 非交易意图市场未生效的空上下文（等价于行为：货架未知）。 */
        static final TradeContext EMPTY = new TradeContext(null, null, null, 1, null, null, null,
                false, null, List.of(), false);
    }

    /**
     * CRAFT 上下文快照。
     *
     * <p>设施解析顺序：<b>目标地点 → 行动者当前地点</b>。理由：NPC 常说「去铁匠铺打把刀」
     * （目标是铺子），也常说「打把柴刀」（目标是东西，人在铺子里）——两种说法都得找到同一座炉子，
     * 否则同一件事会时而成时而不成。</p>
     */
    private record CraftContext(boolean dataKnown, CraftRecipeView recipe, String facilityRef,
                                String facilityType, String facilityName, Long locationId,
                                int batches, String busyRecipeKey, String occupant) {

        /** 制作域未启用非制作意图：等价于之前（配方未知、设施未知）。 */
        static final CraftContext UNKNOWN = new CraftContext(false, null, null, null, null,
                null, 1, null, null);
    }

    /**
     * CRAFT 的配方/设施/在制作业上下文。
     *
     * <p>只在 {@code intent=CRAFT} 且制作域生效时查；任何一步异常都按「未知」降级（null），
     * 让规则走 UNSURE，而不是把世界判死。</p>
     */
    private CraftContext craftContext(Long projectId, ActorCharacter actor, String actorRef,
                                      ActorWorldLocation targetLocation, IntentKind intent,
                                      JsonNode decision, long gameSecond) {
        if (intent != IntentKind.CRAFT || craftService == null || !craftService.enabled()) {
            return CraftContext.UNKNOWN;
        }
        try {
            JsonNode params = decision.path("params");
            // 配方匹配读动作文本，先解包（JSON 字面量里没有「做/炒/熬」这类词）
            String action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("action").asText(""));
            CraftRecipeView recipe = craftService.findRecipe(projectId, textOf(params, "recipeKey"),
                    textOf(params, "itemKey"), action);

            String facilityRef = null;
            String facilityType = null;
            String facilityName = null;
            Long locationId = null;
            // ① 目标地点上的设施
            if (targetLocation != null) {
                ActorWorldFact fact = craftService.resolveFacility(projectId,
                        "loc:" + targetLocation.getId(), targetLocation.getName());
                if (fact != null) {
                    facilityRef = fact.getFactKey();
                    facilityType = CraftFacilities.factTypeOf(fact.getAttributesJson());
                    facilityName = firstNonBlank(fact.getName(), targetLocation.getName());
                    locationId = targetLocation.getId();
                }
            }
            // ② 行动者当前地点的设施（「打把柴刀」这类以东西为目标的说法）
            if (facilityRef == null && actor.getLocation() != null && !actor.getLocation().isBlank()) {
                ActorWorldFact fact = craftService.resolveFacility(projectId, null, actor.getLocation());
                if (fact != null) {
                    facilityRef = fact.getFactKey();
                    facilityType = CraftFacilities.factTypeOf(fact.getAttributesJson());
                    facilityName = firstNonBlank(fact.getName(), actor.getLocation());
                    locationId = fact.getLocationId();
                    if (locationId == null) {
                        ActorWorldLocation here = findLocation(projectId, actor.getLocation());
                        locationId = here == null ? null : here.getId();
                    }
                }
            }

            int batches = craftService.clampBatches(intOf(params, "qty", 1));
            String busy = null;
            Optional<ActorCraftJob> active = craftService.activeJobOf(projectId, actorRef);
            if (active.isPresent()) {
                busy = active.get().getRecipeKey();
            }
            String occupant = facilityRef == null ? null
                    : craftService.occupantOf(projectId, facilityRef, actorRef);
            return new CraftContext(true, recipe, facilityRef, facilityType, facilityName,
                    locationId, batches, busy, occupant);
        } catch (RuntimeException e) {
            log.warn("[裁决] 组装制作上下文失败，按未知处理：actor={} : {}", actorRef, e.getMessage());
            return CraftContext.UNKNOWN;
        }
    }

    /** 取第一个非空白（本地小工具，避免为一处判断引入依赖）。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    // ==================== 损耗装备上下文 ====================

    /** 「脱下」类措辞：行动者在脱，不是在穿。 */
    private static final List<String> UNEQUIP_MARKERS = List.of("脱下", "摘下", "解下", "放下", "收起", "褪下");

    /** 上下文快照（避免给 buildContext 再塞一堆局部变量）。 */
    private record WearContext(boolean dataKnown, List<EquipmentView> equipment, boolean equipmentKnown,
                               EquipmentView hand, Integer targetHealth, String repairItemKey,
                               ItemView repairTarget, String equipItemKey, String equipSlot,
                               boolean equipUnequip) {

        /** 损耗域未启用物品数据不可信：等价于之前（规则层走旧口径）。 */
        static final WearContext UNKNOWN = new WearContext(false, List.of(), false, null, null,
                null, null, null, null, false);
    }

    /**
     * 损耗/装备上下文。
     *
     * <p>只在 {@code intent ∈ {ATTACK, REPAIR, EQUIP}} 且损耗域生效时查装备；
     * 任何一步异常都按「未知」降级，让规则走 UNSURE / 旧口径，而不是把世界判死。</p>
     *
     * <p><b>待修/待穿的东西怎么定</b>：优先取决策 {@code params.itemKey}/{@code params.item}；
     * 没写就取手上那件（「磨刀」不用说磨哪把）。都没写就留空——规则层据此直接放行，
     * 不凭空挑一件去修/去穿。</p>
     */
    private WearContext wearContext(Long projectId, String actorRef, String targetRef, IntentKind intent,
                                    JsonNode decision, List<ItemView> inventory, boolean inventoryKnown,
                                    long gameSecond) {
        boolean dataKnown = wearProperties != null && wearProperties.isEnabled()
                && itemProperties != null && itemProperties.isEnabled()
                && inventoryKnown && inventoryPort != null;
        if (!dataKnown) {
            return WearContext.UNKNOWN;
        }
        boolean needsEquipment = intent == IntentKind.ATTACK || intent == IntentKind.REPAIR
                || intent == IntentKind.EQUIP;
        if (!needsEquipment) {
            return new WearContext(true, List.of(), false, null, null, null, null, null, null, false);
        }
        try {
            List<EquipmentView> equipment = List.of();
            boolean equipmentKnown = false;
            if (equipmentService != null && equipmentService.enabled()) {
                try {
                    equipment = equipmentService.equipmentOf(projectId, actorRef);
                    equipmentKnown = true;
                } catch (RuntimeException e) {
                    log.warn("[裁决] 读取装备失败，按未知处理：actor={} : {}", actorRef, e.getMessage());
                }
            }
            EquipmentView hand = null;
            for (EquipmentView view : equipment) {
                if (view != null && EquipmentSlot.HAND.equals(view.slot())) {
                    hand = view;
                    break;
                }
            }
            JsonNode params = decision.path("params");
            // 动作文本先解包（后续修复/装备/攻击词判定全按人话匹配）
            String action = com.holzyn.actor.common.ActionTexts.unwrap(decision.path("action").asText(""));
            if (action == null) {
                action = "";
            }
            Integer targetHealth = intent == IntentKind.ATTACK ? targetHealthOf(projectId, targetRef) : null;

            String repairItemKey = null;
            ItemView repairTarget = null;
            if (intent == IntentKind.REPAIR) {
                repairItemKey = firstNonBlank(textOf(params, "itemKey"), textOf(params, "item"));
                if (repairItemKey == null && hand != null) {
                    repairItemKey = hand.itemKey();
                }
                repairTarget = wornestOf(inventory, repairItemKey);
            }

            String equipItemKey = null;
            String equipSlot = null;
            boolean unequip = false;
            if (intent == IntentKind.EQUIP) {
                equipItemKey = firstNonBlank(textOf(params, "itemKey"), textOf(params, "item"));
                equipSlot = textOf(params, "slot");
                for (String marker : UNEQUIP_MARKERS) {
                    if (action.contains(marker)) {
                        unequip = true;
                        break;
                    }
                }
                if (equipItemKey == null && unequip && hand != null) {
                    equipItemKey = hand.itemKey();
                    equipSlot = EquipmentSlot.HAND;
                }
            }
            return new WearContext(true, equipment, equipmentKnown, hand, targetHealth,
                    repairItemKey, repairTarget, equipItemKey, equipSlot, unequip);
        } catch (RuntimeException e) {
            log.warn("[裁决] 组装损耗/装备上下文失败，按未知处理：actor={} : {}", actorRef, e.getMessage());
            return WearContext.UNKNOWN;
        }
    }

    /** 目标健康（没有状态记录返回 null，不假设满血）。 */
    private Integer targetHealthOf(Long projectId, String targetRef) {
        if (npcStateService == null || targetRef == null || targetRef.isBlank()) {
            return null;
        }
        try {
            ActorNpcState state = npcStateService.stateFor(projectId, targetRef);
            return state == null || state.getHealth() == null ? null : state.getHealth();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 背包里某物品「最破的那一层」（修东西先修快断的）。 */
    private static ItemView wornestOf(List<ItemView> items, String itemKey) {
        if (items == null || itemKey == null || itemKey.isBlank()) {
            return null;
        }
        String wanted = itemKey.trim();
        ItemView worst = null;
        for (ItemView item : items) {
            if (item == null || item.itemKey() == null
                    || !wanted.equalsIgnoreCase(item.itemKey().trim()) || item.quantity() <= 0) {
                continue;
            }
            if (worst == null || item.durability() < worst.durability()) {
                worst = item;
            }
        }
        return worst;
    }

    // ==================== 查询辅助（全部只读，异常降级） ====================

    private static JsonNode readDecision(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private ActorCharacter findCharacter(Long projectId, String name) {
        if (name == null || name.isBlank() || characterRepository == null) {
            return null;
        }
        try {
            return characterRepository.findByProjectIdAndName(projectId, name).stream()
                    .filter(c -> !Integer.valueOf(1).equals(c.getDeleted()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ActorWorldLocation findLocation(Long projectId, String name) {
        if (name == null || name.isBlank() || locationRepository == null) {
            return null;
        }
        try {
            return locationRepository.findByProjectId(projectId).stream()
                    .filter(l -> l.getName() != null && l.getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ActorWorldFact routeFact(Long projectId, String from, String to, long gameSecond) {
        if (to == null || to.isBlank() || worldFactQuery == null) {
            return null;
        }
        Optional<ActorWorldFact> exact = worldFactQuery.byKey(projectId, "route:" + nullSafe(from) + "-" + to);
        Optional<ActorWorldFact> fallback = exact.isPresent() ? exact
                : worldFactQuery.byKey(projectId, "route:" + to);
        return activeOrNull(fallback, gameSecond);
    }

    private ActorWorldFact facilityFact(Long projectId, ActorWorldLocation location, long gameSecond) {
        if (location == null || worldFactQuery == null) {
            return null;
        }
        Optional<ActorWorldFact> byId = worldFactQuery.byKey(projectId, "facility:loc:" + location.getId());
        Optional<ActorWorldFact> found = byId.isPresent() ? byId
                : worldFactQuery.byKey(projectId, "facility:" + location.getName());
        return activeOrNull(found, gameSecond);
    }

    /** byKey 含已失效事实（幂等/排障用）；规则只能看见当前有效的事实。 */
    private static ActorWorldFact activeOrNull(Optional<ActorWorldFact> fact, long gameSecond) {
        if (fact == null || fact.isEmpty()) {
            return null;
        }
        ActorWorldFact f = fact.get();
        if (f.getStatus() != null && !"active".equalsIgnoreCase(f.getStatus())) {
            return null;
        }
        return f.getValidToGameSecond() != null && f.getValidToGameSecond() <= gameSecond ? null : f;
    }

    private Integer energyOf(Long projectId, ActorCharacter actor) {
        if (npcStateService == null || actor == null) {
            return null;
        }
        try {
            ActorNpcState state = npcStateService.stateFor(projectId, "npc:" + actor.getId());
            return state == null ? null : state.getEnergy();
        } catch (Exception e) {
            return null;
        }
    }

    private int affinity(Long projectId, Long actorId, ActorCharacter target, String targetRef) {
        if (target == null && targetRef == null || relationRepository == null) {
            return 0;
        }
        try {
            String fromRef = "npc:" + actorId;
            for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
                boolean fromMatch = fromRef.equals(r.getFromRef())
                        || (r.getFromCharacterId() != null && r.getFromCharacterId().equals(actorId));
                boolean toMatch = target != null && r.getToCharacterId() != null
                        && r.getToCharacterId().equals(target.getId());
                if (!toMatch && targetRef != null) {
                    toMatch = targetRef.equals(r.getToRef());
                }
                if (fromMatch && toMatch) {
                    return r.getAffinity() == null ? 0 : r.getAffinity();
                }
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private Integer reputation(Long projectId, String targetRef) {
        if (targetRef == null || worldFactQuery == null) {
            return null;
        }
        try {
            Optional<ActorWorldFact> f = worldFactQuery.byKey(projectId, "reputation:" + targetRef);
            if (f.isEmpty()) {
                return null;
            }
            String text = f.get().getStateText();
            if (text != null && text.matches("-?\\d+")) {
                return Integer.parseInt(text);
            }
            JsonNode attrs = f.get().getAttributesJson() == null ? null : MAPPER.readTree(f.get().getAttributesJson());
            if (attrs != null) {
                JsonNode score = attrs.has("score") ? attrs.get("score") : attrs.get("value");
                if (score != null && score.isNumber()) {
                    return score.asInt();
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private List<ActorWorldFact> knownFacts(Long projectId, ActorCharacter actor, long gameSecond) {
        if (worldFactQuery == null) {
            return List.of();
        }
        try {
            List<ActorWorldFact> list = worldFactQuery.knownBy(projectId, "npc:" + actor.getId(), gameSecond, 8);
            return list == null ? List.of() : new ArrayList<>(list);
        } catch (Exception e) {
            return List.of();
        }
    }

    private long currentGameSecond(Long projectId) {
        if (worldClockService == null) {
            return 0L;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean sameLocation(String a, String b) {
        return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 从行动文本里认出货架上的哪件东西（最长名字优先，货币不算货）。
     *
     * <p>只在决策没写 {@code params.itemKey} 时兜底用。为什么要按「最长」：
     * 「面粉」和「面」同时存在时，不能把「买面粉」认成买「面」。</p>
     *
     * @param stock  柜台库存
     *
     * @param action 行动文本
     *
     * @return 命中的物品层；没命中返回 null（不猜）
     */
    private static ItemView namedInText(List<ItemView> stock, String action) {
        if (stock == null || stock.isEmpty() || action == null || action.isBlank()) {
            return null;
        }
        ItemView best = null;
        int bestLen = 0;
        for (ItemView it : stock) {
            if (it == null || it.quantity() <= 0) {
                continue;
            }
            if ("currency".equalsIgnoreCase(it.category())) {
                continue;
            }
            String name = it.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (action.contains(name) && name.length() > bestLen) {
                best = it;
                bestLen = name.length();
            }
        }
        if (best != null) {
            return best;
        }
        // 文本只说了类别（「买些吃食」「买水喝」）时按类别取一件：
        // 生理需求的兜底文本不会点名具体货物，只认名字的话这类「去买点吃的」永远不成交。
        // 复测：模型说的是「馒头热饼馄饨素包子烧饼」——柜台上一件都不叫这些名字，
        // 只认「吃食/口粮」的话 29 条含「买」的决策一条都成交不了。这里把常见吃食词也算成「想吃」，
        // 按类别取柜台上的第一件（买到的是柜台真有的那件，不是凭空造一个「馒头」）。
        boolean wantFood = action.contains("吃食") || action.contains("吃的")
                || action.contains("食物") || action.contains("口粮") || action.contains("干粮")
                || action.contains("馒头") || action.contains("包子") || action.contains("烧饼")
                || action.contains("馍") || action.contains("饼") || action.contains("馄饨")
                || action.contains("面") || action.contains("饭") || action.contains("粥")
                || action.contains("点心") || action.contains("菜") || action.contains("汤");
        boolean wantDrink = action.contains("水") || action.contains("喝的")
                || action.contains("茶") || action.contains("酒") || action.contains("汤");
        if (!wantFood && !wantDrink) {
            return null;
        }
        for (ItemView it : stock) {
            if (it == null || it.quantity() <= 0) {
                continue;
            }
            if ("currency".equalsIgnoreCase(it.category())) {
                continue;
            }
            if (wantFood && "food".equalsIgnoreCase(it.category())) {
                return it;
            }
            if (wantDrink && "drink".equalsIgnoreCase(it.category())) {
                return it;
            }
        }
        return null;
    }

    /**
     * 从行动文本里取件数（「买两件面包」「买 3 斤米」）；取不到就用默认值。
     *
     * @param action   行动文本
     *
     * @param name     物品名
     *
     * @param fallback 默认件数
     *
     * @return 件数（1–20）
     */
    private static int qtyInText(String action, String name, int fallback) {
        if (action == null || name == null || name.isBlank()) {
            return Math.max(1, fallback);
        }
        int idx = action.indexOf(name);
        if (idx <= 0) {
            return Math.max(1, fallback);
        }
        String before = action.substring(Math.max(0, idx - 6), idx);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+|[一二两三四五六七八九十]+)\\s*[件个斤张块碗袋瓶只]?\\s*$")
                .matcher(before);
        if (!m.find()) {
            return Math.max(1, fallback);
        }
        String raw = m.group(1);
        int value;
        if (raw.matches("\\d+")) {
            value = Integer.parseInt(raw);
        } else {
            value = chineseNumber(raw);
        }
        return Math.max(1, Math.min(20, value));
    }

    /**
     * 从整句里取第一个数量词（「买两个素包子」「来一碗热汤」）；取不到用默认值。
     *
     * <p>只在「柜台上的那件东西不叫文本里那个名字」时用：模型说「买两个馒头」，
     * 柜台卖的是面包——件数仍然该是 2，不能因为名字对不上就只买 1 件。</p>
     *
     * @param action   行动文本
     *
     * @param fallback 默认件数
     *
     * @return 件数（1–20）
     */
    private static int qtyInTextAny(String action, int fallback) {
        if (action == null || action.isBlank()) {
            return Math.max(1, fallback);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+|[一二两三四五六七八九十]+)\\s*[件个斤张块碗袋瓶只]")
                .matcher(action);
        if (!m.find()) {
            return Math.max(1, fallback);
        }
        String raw = m.group(1);
        int value = raw.matches("\\d+") ? Integer.parseInt(raw) : chineseNumber(raw);
        return Math.max(1, Math.min(20, value));
    }

    /** 中文数字（一~二十，够买货用）。 */    private static int chineseNumber(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        String digits = "一二三四五六七八九";
        if (text.length() == 1) {
            char c = text.charAt(0);
            if (c == '十') {
                return 10;
            }
            // 「两」是最常用的口语数量词（买两件、来两碗），不在「一二三…」里，必须单独认
            if (c == '两') {
                return 2;
            }
            int i = digits.indexOf(c);
            return i >= 0 ? i + 1 : 1;
        }
        int ten = text.indexOf('十');
        if (ten < 0) {
            char c = text.charAt(0);
            if (c == '两') {
                return 2;
            }
            int i = digits.indexOf(c);
            return i >= 0 ? i + 1 : 1;
        }
        int high = ten == 0 ? 1 : Math.max(1, (text.charAt(0) == '两' ? 2 : digits.indexOf(text.charAt(0)) + 1));
        int low = ten == text.length() - 1 ? 0
                : Math.max(0, (text.charAt(ten + 1) == '两' ? 2 : digits.indexOf(text.charAt(ten + 1)) + 1));
        return high * 10 + low;
    }

    /** 取 params 里的文本字段（缺失/空白返回 null，不猜）。 */
    private static String textOf(JsonNode params, String field) {
        if (params == null) {
            return null;
        }
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 取 params 里的整数字段（缺失/非法返回 fallback）。 */
    private static int intOf(JsonNode params, String field, int fallback) {
        if (params == null) {
            return fallback;
        }
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText("").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 取 params 里的布尔字段（缺失返回 false）。 */
    private static boolean boolOf(JsonNode params, String field) {
        if (params == null) {
            return false;
        }
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText("");
        return "true".equalsIgnoreCase(text.trim()) || "1".equals(text.trim());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
