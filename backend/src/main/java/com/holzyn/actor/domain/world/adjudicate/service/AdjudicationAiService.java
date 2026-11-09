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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.prompt.PromptResources;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 罕见 AI 裁决（§4.7）。
 *
 * <p>只在规则返回 {@code UNSURE} 且行动重要度 ≥ {@code adjudicate.ai-min-importance} 时调用一次；
 * 单轮、闭集、temperature=0、最多 {@code ai-max-effects} 条效果。任何失败/解析失败统一降级
 * {@code PARTIAL}，绝不 FAIL（用户决策）。</p>
 *
 * <p><b>架构不变量</b>：本类含 AI 调用，公开方法<b>不得带 {@code @Transactional}</b>；
 * 由 {@code ActionAdjudicationService} 在进入 {@code AgentActionService.execute} 事务前调用。</p>
 */
@Slf4j
@Service
public class AdjudicationAiService {

    /** 场景码（PromptSpecRegistry / 用量日志）。 */
    public static final String SCENE = "action_adjudicate";

    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    @Autowired(required = false)
    private ActorProjectRepository projectRepository;

    @Autowired(required = false)
    private WorldLoopProperties properties;

    /**
     * 调一次 AI 裁决。
     *
     * @param ctx 上下文
     *
     * @return 三态裁决；不可用时 PARTIAL + AI_UNAVAILABLE
     */
    public ActionVerdict adjudicate(AdjudicationContext ctx) {
        if (ctx == null || aiChatGateway == null) {
            return ActionVerdict.of(Verdict.PARTIAL, "AI_UNAVAILABLE",
                    "他试了，但结果一时说不清。", 0, List.of(), "fallback");
        }
        try {
            String system = PromptResources.system(SCENE);
            if (system == null || system.isBlank()) {
                system = "你是世界的行为裁决员，只输出 JSON；无法确定时给 PARTIAL，不编造世界事实。";
            }
            String user = renderUser(ctx);
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", user)),
                    0.0, 500, true);
            Long userId = projectId(ctx) == null ? null : projectRepository == null ? null
                    : projectRepository.findById(projectId(ctx)).map(ActorProject::getUserId).orElse(null);
            Long actorId = ctx.getActor() == null ? null : ctx.getActor().getId();
            AiChatResult result = AiCallContext.run(SCENE, actorId,
                    (Supplier<AiChatResult>) () -> aiChatGateway.chatCompletion(
                            userId, projectId(ctx), null, req));
            String text = result == null || result.content() == null ? null : result.content().trim();
            if (text == null || text.isBlank()) {
                return ActionVerdict.of(Verdict.PARTIAL, "AI_UNAVAILABLE",
                        "他试了，但结果一时说不清。", 0, List.of(), "fallback");
            }
            int maxEffects = properties == null || properties.getAdjudicate() == null
                    ? 4 : properties.getAdjudicate().getAiMaxEffects();
            return AdjudicationParse.parse(text, maxEffects);
        } catch (Exception e) {
            log.warn("[裁决][AI] 调用失败（降级 PARTIAL）: project={} : {}",
                    projectId(ctx), e.getMessage());
            return ActionVerdict.of(Verdict.PARTIAL, "AI_UNAVAILABLE",
                    "他试了，但结果一时说不清。", 0, List.of(), "fallback");
        }
    }

    private static Long projectId(AdjudicationContext ctx) {
        return ctx == null ? null : ctx.getProjectId();
    }

    /** 渲染 user 模板（占位符精确替换；缺资源时退回纯文本上下文）。 */
    private String renderUser(AdjudicationContext ctx) {
        String template = PromptResources.user(SCENE);
        if (template == null || template.isBlank()) {
            template = "行动者：{{actor_name}}\n行动：{{action}}\n意图：{{intent}}\n"
                    + "地点：{{location}}\n已知事实：{{known_facts}}\n规则：{{rule_hint}}";
        }
        String actorName = ctx.getActor() == null || ctx.getActor().getName() == null
                ? "某人" : ctx.getActor().getName();
        String actorRef = ctx.getActor() == null ? "" : "npc:" + ctx.getActor().getId();
        String action = ctx.getDecision() == null ? "" : ctx.getDecision().path("action").asText("");
        String state = "精力=" + (ctx.getEnergy() == null ? "未知" : ctx.getEnergy())
                + "；声望=" + (ctx.getReputation() == null ? "无记录" : ctx.getReputation());
        String known = knownFacts(ctx);
        String inventory = ctx.isInventoryKnown()
                ? (ctx.getInventory() == null || ctx.getInventory().isEmpty() ? "空"
                : ctx.getInventory().stream()
                        .map(i -> i.name() + "x" + i.quantity())
                        .collect(Collectors.joining("、")))
                : "未知（当前世界还没有物品系统）";
        String relation = ctx.getTargetName() == null || ctx.getTargetName().isBlank()
                ? "（无目标）" : ctx.getTargetName() + "：affinity=" + ctx.getRelationToTarget();
        return template
                .replace("{{actor_name}}", actorName)
                .replace("{{actor_ref}}", actorRef)
                .replace("{{intent}}", ctx.getIntent() == null ? "OTHER" : ctx.getIntent().name())
                .replace("{{action}}", action)
                .replace("{{location}}", nullSafe(ctx.getLocationName()))
                .replace("{{game_time}}", "游戏秒 " + ctx.getGameSecond())
                .replace("{{state}}", state)
                .replace("{{relation}}", relation)
                .replace("{{known_facts}}", known)
                .replace("{{inventory}}", inventory)
                .replace("{{market}}", market(ctx))
                .replace("{{craft}}", craft(ctx))
                .replace("{{wear}}", wear(ctx))
                .replace("{{rule_hint}}", ruleHint(ctx));
    }

    /**
     * 交易段：柜台有什么、多少钱、我身上有多少钱。
     *
     * <p>同一世界状态下的文本必须逐字节稳定（利于前缀缓存），所以只用
     * 「卖家 ref + 物品名×数量 + 单价 + 资金」这四类确定量，不掺时间戳/随机。</p>
     */
    private static String market(AdjudicationContext ctx) {
        if (ctx.getSellerRef() == null || !ctx.isCounterpartyStockKnown()) {
            return "（无市场数据）";
        }
        String currency = ctx.getCurrencyKey() == null ? "coin" : ctx.getCurrencyKey();
        String stock = ctx.getCounterpartyStock() == null || ctx.getCounterpartyStock().isEmpty()
                ? "柜台空"
                : ctx.getCounterpartyStock().stream()
                        .map(i -> i.name() + "x" + i.quantity())
                        .collect(Collectors.joining("、"));
        String price = ctx.getUnitPrice() == null ? "未标价" : ctx.getUnitPrice() + " " + currency;
        String funds = ctx.getFunds() == null ? "未知" : ctx.getFunds() + " " + currency;
        return "柜台 " + ctx.getSellerRef() + "：" + stock + "；单价 " + price
                + "；我身上 " + funds + (ctx.isCredit() ? "；这一笔是赊账" : "");
    }

    /**
     * 制作段：要做什么、要哪些材料、要什么设施、手上还有没有活。
     *
     * <p>与交易段同口径：只用确定量（配方键/产出/材料总量/设施名/耗时），
     * 同一世界状态下文本逐字节稳定，利于前缀缓存。</p>
     */
    private static String craft(AdjudicationContext ctx) {
        if (!ctx.isCraftDataKnown()) {
            return "（无制作数据）";
        }
        CraftRecipeView recipe = ctx.getRecipe();
        if (recipe == null) {
            return "（不知道这东西该怎么配）";
        }
        int batches = Math.max(1, ctx.getCraftBatches());
        String inputs = recipe.inputs() == null || recipe.inputs().isEmpty() ? "无需材料"
                : recipe.inputs().stream()
                        .map(i -> i.itemKey() + "x" + i.totalFor(batches))
                        .collect(Collectors.joining("、"));
        String facility;
        if (!recipe.needsFacility()) {
            facility = "随处可做";
        } else if (ctx.getCraftFacilityRef() == null) {
            facility = "缺「" + recipe.facilityType() + "」";
        } else {
            facility = recipe.facilityType() + "（" + nullSafe(ctx.getCraftFacilityName()) + "）";
        }
        String busy = ctx.getCraftBusyRecipeKey() == null ? ""
                : "；手上还有「" + ctx.getCraftBusyRecipeKey() + "」没做完";
        return recipe.recipeKey() + " → " + recipe.outputItemKey() + "x" + recipe.totalOutput(batches)
                + "；材料 " + inputs + "；设施 " + facility
                + "；耗时 " + recipe.totalMinutes(batches) + " 分钟" + busy;
    }

    /**
     * 装备段：手上拿着什么、成色如何、身上还带着什么。
     *
     * <p>与交易/制作段同口径：只用确定量（物品名/槽位/成色/耐久），同一世界状态下
     * 文本逐字节稳定，利于前缀缓存。</p>
     */
    private static String wear(AdjudicationContext ctx) {
        if (!ctx.isWearDataKnown()) {
            return "（无损耗/装备数据）";
        }
        if (!ctx.isEquipmentKnown()) {
            return "（不知道他手上拿着什么）";
        }
        if (ctx.getEquipment() == null || ctx.getEquipment().isEmpty()) {
            return "两手空空";
        }
        StringBuilder sb = new StringBuilder();
        for (com.holzyn.actor.domain.equip.model.EquipmentView view : ctx.getEquipment()) {
            if (view == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(view.slot()).append('：').append(view.name())
                    .append("（成色 ").append(view.condition())
                    .append('，').append(view.conditionLabel())
                    .append("，耐久 ").append(view.durability()).append('）');
        }
        return sb.length() == 0 ? "两手空空" : sb.toString();
    }

    private static String knownFacts(AdjudicationContext ctx) {        if (ctx.getKnownFacts() == null || ctx.getKnownFacts().isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorWorldFact f : ctx.getKnownFacts()) {
            if (f == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(nullSafe(f.getName())).append("：").append(nullSafe(f.getStateText()));
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    /** 规则初步结论提示（UNSURE 的原因）。 */
    private static String ruleHint(AdjudicationContext ctx) {
        if (ctx.getIntent() == null) {
            return "规则无法归类，请按常识判断。";
        }
        return switch (ctx.getIntent()) {
            case TRADE -> "柜台库存/价格/资金里有未知项；不要凭空创造库存或金钱。";
            case CRAFT -> "配方/材料/设施里有未知项；不要凭空创造材料或产物，也不要替他把活直接做成。";
            case ATTACK -> "装备/耐久里有未知项；不要凭空创造武器，也不要把人砍死（本期没有死亡）。";
            case REPAIR -> "他手上/身上的东西或耐久里有未知项；不要凭空修好一件不在身上的东西。";
            case EQUIP -> "他身上到底带着什么里有未知项；不要凭空给他一件装备。";
            default -> "规则未发现明确阻碍。";
        };
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
