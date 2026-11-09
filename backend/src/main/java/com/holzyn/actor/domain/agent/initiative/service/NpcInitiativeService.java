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

package com.holzyn.actor.domain.agent.initiative.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.initiative.model.InitiativeCandidate;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.adjudicate.service.ActionAdjudicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 主动性扫描。
 *
 * <p>职责：每游戏小时由 {@link WorldEngine} 调用一次，按三类动机给 NPC 打分：
 * 契约到期/逾期、久未见面、传闻缠身；按项目级每小时上限发起
 * {@code actor_plan(trigger_type=initiative)}，复用既有执行链，不新增执行器。</p>
 *
 * <p><b>零 AI</b>：扫描只读事实/认知/关系/事件，所有分数为确定性规则；
 * 计划执行走 {@link ActionAdjudicationService} 的 initiative 规则分支，禁止 AI 裁决。</p>
 *
 * <p><b>因果</b>：计划的 {@code actionJson._initiative.causeEventId} 指向契约事件/传闻事实事件；
 * 到达通知事件再以它为 {@code cause_event_id}，形成 depth=1 的拜访链。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcInitiativeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long DAY_SECONDS = 86_400L;
    private static final long HOUR_SECONDS = 3_600L;
    private static final long CONTRACT_LEAD_SECONDS = DAY_SECONDS;

    private static final Set<String> RUMOR_FACT_TYPES = Set.of("reputation", "secret", "contract");

    /** 最近事件扫描上限（每个 NPC；避免长跑后全表遍历） */
    private static final int EVENT_SCAN_LIMIT = 80;

    /** 每个 NPC 的认知扫描上限 */
    private static final int KNOWLEDGE_SCAN_LIMIT = 120;

    private final ActorCharacterRepository characterRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorWorldFactRepository factRepository;
    private final ActorWorldFactKnowledgeRepository knowledgeRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorEventRepository eventRepository;
    private final WorldFactQuery worldFactQuery;
    private final WorldEventBus worldEventBus;
    private final WorldLoopProperties properties;

    /** 执行链（规则裁决；@Lazy 破环：ActionAdjudication → AgentAction → Sandbox → Initiative）。 */
    @Autowired(required = false)
    @Lazy
    private ActionAdjudicationService actionAdjudicationService;

    /** 世界时钟（仅用于 authority 到达钩子未传 gameSecond 时补当前时刻；字段注入不改构造器）。 */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 物品端口（对接：断粮/家伙坏了/有货要出手——这三类发起理由只有物品域知道）。
     *
     * <p>字段注入保持既有构造器不变（AGENTS §8.1.2）；未装配时整段不扫。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.item.port.InventoryPort inventoryPort;

    /** 物品域配置（关着不扫物品类发起，保证关开关=回基线） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.item.config.ItemProperties itemProperties;

    /** 状态门面（只读饥饿值；未装配则不判「断粮」） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /** 市场配置（取货币物品键；未装配按 coin） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.market.config.MarketProperties marketProperties;

    /** 单次扫描最多看多少个 NPC 的物品（防「人多世界」把每小时扫描拖成 O(N) 全表） */
    private static final int ITEM_SCAN_LIMIT = 40;

    /** 低于该饥饿值且身上没吃的 → 判「断粮」 */
    private static final int HUNGRY_THRESHOLD = 40;

    /** 成色低于该值 → 判「家伙快不能用了」 */
    private static final int WORN_OUT_CONDITION = 25;

    /** 某样东西攒到这么多件才算「有货要出手」 */
    private static final int SURPLUS_QTY = 5;

    /** 同一项目同一游戏小时只扫描一次。 */
    private final Map<Long, Long> lastScanHour = new ConcurrentHashMap<>();

    /** 已通知的到达计划（防 authority + sandbox 双钩子重复通知）。 */
    private final Set<Long> notifiedPlanIds = ConcurrentHashMap.newKeySet();

    /**
     * 每小时扫描一次（引擎小时钩子）。
     *
     * @param projectId  项目 ID
     * @param gameSecond 当前游戏秒
     *
     * @return 本次创建的 initiative 计划数
     */
    public int scan(Long projectId, long gameSecond) {
        if (!enabled() || projectId == null) {
            return 0;
        }
        long hour = Math.floorDiv(gameSecond, HOUR_SECONDS);
        Long previous = lastScanHour.put(projectId, hour);
        if (previous != null && previous == hour) {
            return 0;
        }
        ActorCharacter player = playerOf(projectId);
        if (player == null || blank(player.getLocation())) {
            return 0;
        }
        String playerRef = NpcRefs.of(player.getId());
        if (playerRef == null) {
            return 0;
        }

        List<InitiativeCandidate> candidates = new ArrayList<>();
        collectContracts(projectId, playerRef, gameSecond, player, candidates);
        collectRumors(projectId, playerRef, gameSecond, player, candidates);
        collectAbsence(projectId, playerRef, gameSecond, player, candidates);
        collectItemNeeds(projectId, playerRef, gameSecond, player, candidates);

        int minUrgency = Math.max(1, properties.getInitiative().getMinUrgency());
        int maxPerHour = Math.max(1, properties.getInitiative().getMaxPerHour());
        List<InitiativeCandidate> chosen = new ArrayList<>();
        Set<Long> chosenNpc = new LinkedHashSet<>();
        candidates.stream()
                // 已发起过的事实不再占用本小时名额（否则老契约会长期挤掉新契约/传闻）
                .filter(c -> !alreadyInitiated(c, c.npcId()))
                .filter(c -> c.urgency() >= minUrgency)
                .sorted(Comparator.comparingDouble(InitiativeCandidate::score).reversed())
                .forEach(c -> {
                    if (chosen.size() < maxPerHour && chosenNpc.add(c.npcId())) {
                        chosen.add(c);
                    }
                });

        int created = 0;
        for (InitiativeCandidate candidate : chosen) {
            try {
                created += createPlan(candidate, player, gameSecond);
            } catch (Exception e) {
                log.warn("[主动性] 发起失败（跳过该候选）: npc={} kind={} : {}",
                        candidate.npcRef(), candidate.kind(), e.getMessage());
            }
        }
        if (!chosen.isEmpty()) {
            log.info("[主动性] 扫描完成：project={} 候选={} 发起={} gameSecond={}",
                    projectId, candidates.size(), created, gameSecond);
        }
        return created;
    }

    /**
     * 到达回调（沙盘真实到达；Authority 立即落位由 AgentActionService 调用）。
     *
     * @param projectId   项目 ID
     * @param npcRef      到达 NPC
     * @param locationName 到达地点
     * @param gameSecond  游戏秒
     */
    public void onNpcArrived(Long projectId, String npcRef, String locationName, Long gameSecond) {
        if (!enabled() || projectId == null || npcRef == null) {
            return;
        }
        Long npcId = idOf(npcRef);
        if (npcId == null) {
            return;
        }
        List<ActorActionPlan> plans = planRepository.findByCharacterIdOrderByIdDesc(npcId);
        int scanned = 0;
        for (ActorActionPlan plan : plans) {
            if (plan == null || !"initiative".equalsIgnoreCase(plan.getTriggerType())) {
                continue;
            }
            if (++scanned > 10) {
                break;
            }
            if (notifyForPlan(plan, locationName, gameSecond)) {
                return;
            }
        }
    }

    /**
     * Authority 移动/同地点执行后的到达回调。
     *
     * @param plan 已执行的 initiative 计划
     */
    public void onAuthorityArrived(ActorActionPlan plan) {
        if (!enabled() || plan == null) {
            return;
        }
        // 时间一致性：优先用计划创建时的扫描时刻（_initiative.createdGameSecond），
        // 而不是执行瞬间的持久化时钟——引擎 tick 结束才拨钟，否则会出现
        // 「扫描日志 16:00、到达事件显示 14:00」（闭环实测 §5.6）。
        // 缺失时回退 null=notifyForPlan 从世界时钟补，旧计划行为不变。
        notifyForPlan(plan, null, planGameSecond(plan));
    }

    /**
     * 主动性计划携带的扫描游戏秒（{@code _initiative.createdGameSecond}）。
     *
     * @param plan 行动计划
     *
     * @return 游戏秒；非 initiative/缺失/非法返回 null
     */
    private Long planGameSecond(ActorActionPlan plan) {
        try {
            JsonNode decision = read(plan.getActionJson());
            JsonNode meta = decision == null ? null : decision.get("_initiative");
            if (meta == null || meta.isNull()) {
                return null;
            }
            Long gs = longValue(meta, "createdGameSecond");
            return gs == null || gs <= 0 ? null : gs;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 候选收集 ====================

    private void collectContracts(Long projectId, String playerRef, long gameSecond,
                                  ActorCharacter player, List<InitiativeCandidate> out) {
        List<ActorWorldFact> contracts = safe(worldFactQuery.byType(projectId, "contract", "active", 200));
        for (ActorWorldFact fact : contracts) {
            if (fact == null || fact.getValidToGameSecond() == null) {
                continue;
            }
            long due = fact.getValidToGameSecond();
            if (due > gameSecond + CONTRACT_LEAD_SECONDS) {
                continue;
            }
            String creditorRef = partyOtherThan(fact.getAttributesJson(), playerRef);
            ActorCharacter creditor = characterByRef(projectId, creditorRef);
            if (creditor == null || creditor.isPlayer() || creditor.isDeceased()) {
                continue;
            }
            int urgency = due < gameSecond ? 5 : 4;
            String reason = due < gameSecond ? "契约已经到期，来问个说法" : "契约快到期了，来提醒你";
            out.add(new InitiativeCandidate(creditor.getId(), NpcRefs.of(creditor.getId()), "contract",
                    urgency, fact.getSourceEventId(), fact.getFactKey(), reason,
                    score(projectId, creditor, player, urgency)));
        }
    }

    private void collectRumors(Long projectId, String playerRef, long gameSecond,
                               ActorCharacter player, List<InitiativeCandidate> out) {
        Set<Long> seen = new LinkedHashSet<>();
        for (ActorCharacter npc : characters(projectId)) {
            if (npc == null || npc.isPlayer() || npc.isDeceased()) {
                continue;
            }
            String npcRef = NpcRefs.of(npc.getId());
            if (npcRef == null) {
                continue;
            }
            List<ActorWorldFactKnowledge> rows = knowledgeRepository
                    .findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                            projectId, npcRef, PageRequest.of(0, KNOWLEDGE_SCAN_LIMIT));
            for (ActorWorldFactKnowledge row : rows) {
                if (row == null || !"rumor".equalsIgnoreCase(row.getSourceType())
                        || row.getFactId() == null || seen.contains(row.getFactId())) {
                    continue;
                }
                ActorWorldFact fact = factRepository.findById(row.getFactId()).orElse(null);
                if (fact == null || !"active".equalsIgnoreCase(fact.getStatus())
                        || !RUMOR_FACT_TYPES.contains(fact.getFactType())
                        || !mentionsPlayer(fact, playerRef)) {
                    continue;
                }
                seen.add(fact.getId());
                int urgency = "reputation".equalsIgnoreCase(fact.getFactType()) ? 4 : 3;
                out.add(new InitiativeCandidate(npc.getId(), npcRef, "rumor", urgency,
                        fact.getSourceEventId(), fact.getFactKey(), "听到关于你的传闻，来问清楚",
                        score(projectId, npc, player, urgency)));
                break;
            }
        }
    }

    private void collectAbsence(Long projectId, String playerRef, long gameSecond,
                                ActorCharacter player, List<InitiativeCandidate> out) {
        int absenceDays = Math.max(1, properties.getInitiative().getAbsenceDays());
        long absenceSeconds = absenceDays * DAY_SECONDS;
        for (ActorCharacterRelation rel : safe(relationRepository.findByProjectId(projectId))) {
            if (rel == null) {
                continue;
            }
            String npcRef = playerRef.equals(rel.getFromRef()) ? rel.getToRef()
                    : playerRef.equals(rel.getToRef()) ? rel.getFromRef() : null;
            if (npcRef == null || npcRef.equals(playerRef)) {
                continue;
            }
            ActorCharacter npc = characterByRef(projectId, npcRef);
            if (npc == null || npc.isPlayer() || npc.isDeceased()) {
                continue;
            }
            Interaction last = lastInteraction(projectId, npc.getId(), playerRef);
            if (last == null || gameSecond - last.gameSecond() < absenceSeconds) {
                continue;
            }
            out.add(new InitiativeCandidate(npc.getId(), NpcRefs.of(npc.getId()), "absence", 3,
                    last.eventId(), null, "许久没见，来看看你", score(projectId, npc, player, 3)));
        }
    }

    // ==================== 物品类发起（对接） ====================

    /**
     * 物品驱动的三类发起理由：断粮、家伙快不能用了、手里有货想出手。
     *
     * <p><b>为什么需要这一段</b>：主动性原本只看契约、传闻、久别——全是「社会关系」。
     * 可真实世界里，一个人主动找上门，最常见的原因其实是<b>日子过不下去了</b>：
     * 家里没粮了、吃饭的家伙快断了、手里攒了点货想换钱。这三件事全在物品域里，
     * 不接过来，就永远只会因为「关系」来找你。</p>
     *
     * <p><b>为什么用固定 factKey 去重</b>：{@code alreadyInitiated} 靠 factKey 判「同一个人
     * 为同一件事只来一次」。物品类没有事实行，就用 {@code item:{kind}:{ref}} 当稳定键——
     * 否则断粮的人会每小时来一次，变成骚扰。</p>
     *
     * <p>每个 NPC 一次只取最靠前的一条（断粮 &gt; 家伙坏了 &gt; 有货要出手），
     * 免得同一个人同时因为三件事来找你。</p>
     */
    private void collectItemNeeds(Long projectId, String playerRef, long gameSecond,
                                  ActorCharacter player, List<InitiativeCandidate> out) {
        if (inventoryPort == null || (itemProperties != null && !itemProperties.isEnabled())) {
            return;
        }
        String currency = currencyKey();
        int scanned = 0;
        for (ActorCharacter npc : characters(projectId)) {
            if (npc == null || npc.isPlayer() || npc.isDeceased()) {
                continue;
            }
            if (++scanned > ITEM_SCAN_LIMIT) {
                break;
            }
            String npcRef = NpcRefs.of(npc.getId());
            if (npcRef == null) {
                continue;
            }
            try {
                List<com.holzyn.actor.domain.item.model.ItemView> items =
                        inventoryPort.list(projectId, npcRef);
                InitiativeCandidate candidate = itemCandidateOf(projectId, npc, npcRef, player,
                        items, currency);
                if (candidate != null) {
                    out.add(candidate);
                }
            } catch (RuntimeException e) {
                log.debug("[主动性] 物品类候选扫描失败（跳过该人）: ref={} : {}", npcRef, e.getMessage());
            }
        }
    }

    /** 单个 NPC 的物品类候选（断粮 &gt; 家伙坏了 &gt; 有货要出手；都没有返回 null）。 */
    private InitiativeCandidate itemCandidateOf(Long projectId, ActorCharacter npc, String npcRef,
                                                ActorCharacter player,
                                                List<com.holzyn.actor.domain.item.model.ItemView> items,
                                                String currency) {
        // ① 断粮：饿了且身上一口吃的都没有
        Integer hunger = hungerOf(projectId, npcRef);
        if (hunger != null && hunger < HUNGRY_THRESHOLD
                && com.holzyn.actor.domain.item.model.ItemCarryText.bestFoodOf(items) == null) {
            return new InitiativeCandidate(npc.getId(), npcRef, "item", 3, null,
                    "item:food:" + npcRef, "家里断粮了，想问问你能不能匀点吃的",
                    score(projectId, npc, player, 3));
        }
        // ② 家伙快不能用了：随身某件已经卷刃/快断了
        com.holzyn.actor.domain.item.model.ItemView worn =
                com.holzyn.actor.domain.item.model.ItemCarryText.wornOutOf(items, WORN_OUT_CONDITION);
        if (worn != null) {
            return new InitiativeCandidate(npc.getId(), npcRef, "item", 2, null,
                    "item:tool:" + npcRef, "「" + worn.name() + "」快不能用了，想请你帮衬一把",
                    score(projectId, npc, player, 2));
        }
        // ③ 有货要出手：攒了一批货、手里却没几个钱
        com.holzyn.actor.domain.item.model.ItemView surplus =
                com.holzyn.actor.domain.item.model.ItemCarryText.surplusOf(items, currency, SURPLUS_QTY);
        if (surplus != null && inventoryPort.quantityOf(projectId, npcRef, currency) < 20) {
            return new InitiativeCandidate(npc.getId(), npcRef, "item", 2, null,
                    "item:sell:" + npcRef, "手里攒了些" + surplus.name() + "，想问问你要不要",
                    score(projectId, npc, player, 2));
        }
        return null;
    }

    /** 饥饿值（无状态行返回 null，不臆断「断粮」）。 */
    private Integer hungerOf(Long projectId, String npcRef) {
        if (npcStateService == null) {
            return null;
        }
        try {
            var state = npcStateService.stateFor(projectId, npcRef);
            return state == null || state.getHunger() == null ? null : state.getHunger();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 计价货币物品键（与市场同源；未装配按 coin）。 */
    private String currencyKey() {
        if (marketProperties == null || marketProperties.getCurrencyItemKey() == null
                || marketProperties.getCurrencyItemKey().isBlank()) {
            return "coin";
        }
        return marketProperties.getCurrencyItemKey().trim();
    }

    // ==================== 计划与通知 ====================

    private int createPlan(InitiativeCandidate candidate, ActorCharacter player, long gameSecond) {
        ActorCharacter npc = characterRepository.findById(candidate.npcId()).orElse(null);
        if (npc == null || npc.isDeceased()) {
            return 0;
        }
        if (alreadyInitiated(candidate, npc.getId())) {
            return 0;
        }
        String destination = player.getLocation();
        if (blank(destination)) {
            return 0;
        }
        boolean alreadyThere = sameLocation(npc.getLocation(), destination);
        ActorActionPlan plan = new ActorActionPlan();
        plan.setCharacterId(npc.getId());
        plan.setTriggerType("initiative");
        plan.setStatus("planned");
        plan.setActionJson(actionJson(candidate, player, npc, destination, alreadyThere, gameSecond));
        planRepository.save(plan);
        if (actionAdjudicationService != null) {
            actionAdjudicationService.executeGated(plan);
        } else {
            log.warn("[主动性] 裁决执行链未装配，计划仅落库: plan={}", plan.getId());
        }
        // 同地点无需移动，执行即到达；跨地点由 Authority/Sandbox 到达钩子通知。
        if (alreadyThere) {
            notifyForPlan(plan, destination, gameSecond);
        }
        return 1;
    }

    /**
     * 同一 NPC 对同一事实只发起一次（跨小时扫描幂等）。
     *
     * <p>扫描每小时运行一次；契约未到期前会连续多轮命中同一 factKey。
     * 已有未取消的 initiative 计划（含 done）即视为已发起，避免重复拜访/重复事件。</p>
     */
    private boolean alreadyInitiated(InitiativeCandidate candidate, Long npcId) {
        if (blank(candidate.factKey()) || npcId == null) {
            return false;
        }
        List<ActorActionPlan> plans = safe(planRepository.findByCharacterIdOrderByIdDesc(npcId));
        int checked = 0;
        for (ActorActionPlan plan : plans) {
            if (plan == null || !"initiative".equalsIgnoreCase(plan.getTriggerType())
                    || "cancelled".equalsIgnoreCase(plan.getStatus())) {
                continue;
            }
            JsonNode meta = read(plan.getActionJson());
            JsonNode initiative = meta == null ? null : meta.get("_initiative");
            if (initiative != null && candidate.factKey().equals(text(initiative, "factKey"))) {
                return true;
            }
            if (++checked >= 20) {
                break;
            }
        }
        return false;
    }

    private String actionJson(InitiativeCandidate candidate, ActorCharacter player, ActorCharacter npc,
                              String destination, boolean alreadyThere, long gameSecond) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", alreadyThere ? "speak" : "move");
        root.put("action", alreadyThere
                ? "去找" + safeName(player) + "说几句话"
                : "前往" + destination + "找" + safeName(player));
        root.put("target", alreadyThere ? safeName(player) : destination);
        root.put("reason", candidate.reason());
        root.put("urgency", candidate.urgency());
        root.put("duration", alreadyThere ? 10 : 30);
        ObjectNode meta = root.putObject("_initiative");
        meta.put("kind", candidate.kind());
        meta.put("npcRef", candidate.npcRef());
        meta.put("playerRef", NpcRefs.of(player.getId()));
        meta.put("destination", destination);
        meta.put("factKey", candidate.factKey());
        meta.put("causeEventId", candidate.causeEventId());
        meta.put("createdGameSecond", gameSecond);
        return root.toString();
    }

    private boolean notifyForPlan(ActorActionPlan plan, String locationName, Long gameSecond) {
        if (plan == null || plan.getId() == null || !notifiedPlanIds.add(plan.getId())) {
            return false;
        }
        try {
            JsonNode decision = read(plan.getActionJson());
            JsonNode meta = decision == null ? null : decision.get("_initiative");
            if (meta == null || meta.isNull()) {
                selfRemove(plan.getId());
                return false;
            }
            if (!properties.getInitiative().isVisitNotify()) {
                selfRemove(plan.getId());
                return false;
            }
            String destination = text(meta, "destination");
            if (locationName != null && !blank(locationName) && !sameLocation(locationName, destination)) {
                selfRemove(plan.getId());
                return false;
            }
            String npcRef = text(meta, "npcRef");
            String playerRef = text(meta, "playerRef");
            Long npcId = idOf(npcRef);
            ActorCharacter npc = npcId == null ? null : characterRepository.findById(npcId).orElse(null);
            Long projectId = npc == null ? null : npc.getProjectId();
            ActorCharacter player = characterByRef(projectId, playerRef);
            if (npc == null || player == null) {
                selfRemove(plan.getId());
                return false;
            }
            Long ownerId = projectRepository.findById(projectId)
                    .map(ActorProject::getUserId).orElse(null);
            if (ownerId == null) {
                selfRemove(plan.getId());
                return false;
            }
            String loc = locationName == null || locationName.isBlank()
                    ? (blank(player.getLocation()) ? npc.getLocation() : player.getLocation())
                    : locationName;
            Long causeEventId = longValue(meta, "causeEventId");
            // onAuthorityArrived 不传时刻：不能用 0（会显示成 0001 年），从世界时钟补当前游戏秒。
            long gs = gameSecond != null ? gameSecond : currentGameSecond(projectId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", plan.getId());
            payload.put("kind", text(meta, "kind"));
            payload.put("npcRef", npcRef);
            payload.put("playerRef", playerRef);
            payload.put("factKey", text(meta, "factKey"));
            payload.put("reason", decision.path("reason").asText(""));
            worldEventBus.publish(ownerId, projectId, EventSpec.builder()
                    .kind("action")
                    .source("initiative")
                    .characterId(npc.getId())
                    .characterRefs(List.of(npcRef, playerRef))
                    .locationName(loc)
                    .visibility("private")
                    .title(safeName(npc) + "来找你")
                    .content(safeName(npc) + "来到" + (loc == null ? "你所在的地方" : loc)
                            + "，想和你谈谈：" + decision.path("reason").asText(""))
                    .payloadJson(MAPPER.writeValueAsString(payload))
                    .importance(4)
                    .gameHour(gs / HOUR_SECONDS)
                    .causeEventId(causeEventId)
                    .sourceKey("initiative:arrived:" + plan.getId())
                    .build());
            log.info("[主动性] 到达通知：project={} npc={} plan={} loc={}",
                    projectId, npcRef, plan.getId(), loc);
            return true;
        } catch (Exception e) {
            selfRemove(plan.getId());
            log.warn("[主动性] 到达通知失败（隔离继续）: plan={} : {}", plan.getId(), e.getMessage());
            return false;
        }
    }

    private void selfRemove(Long planId) {
        if (planId != null) {
            notifiedPlanIds.remove(planId);
        }
    }

    // ==================== 查询/工具 ====================

    /** 当前引擎游戏秒（失败退 0；仅影响到达事件时间显示，不阻断通知）。 */
    private long currentGameSecond(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return 0L;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean enabled() {
        return properties != null && properties.isEnabled()
                && properties.getInitiative() != null && properties.getInitiative().isEnabled();
    }

    private ActorCharacter playerOf(Long projectId) {
        return characterRepository
                .findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(projectId, "player", 0)
                .orElse(null);
    }

    private List<ActorCharacter> characters(Long projectId) {
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
    }

    private ActorCharacter characterByRef(Long projectId, String ref) {
        Long id = idOf(ref);
        if (id == null) {
            return null;
        }
        ActorCharacter ch = characterRepository.findById(id).orElse(null);
        return ch != null && projectId.equals(ch.getProjectId()) && !Integer.valueOf(1).equals(ch.getDeleted())
                ? ch : null;
    }

    private Long idOf(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String raw = ref.startsWith("npc:") ? ref.substring(4)
                : ref.startsWith("special:") ? ref.substring(8) : ref;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double score(Long projectId, ActorCharacter npc, ActorCharacter player, int urgency) {
        double closeness = 0.3d;
        for (ActorCharacterRelation rel : safe(relationRepository.findByProjectId(projectId))) {
            if (rel == null) {
                continue;
            }
            String npcRef = NpcRefs.of(npc.getId());
            String playerRef = NpcRefs.of(player.getId());
            boolean pair = (npcRef.equals(rel.getFromRef()) && playerRef.equals(rel.getToRef()))
                    || (npcRef.equals(rel.getToRef()) && playerRef.equals(rel.getFromRef()));
            if (pair) {
                double affinity = rel.getAffinity() == null ? 0d : rel.getAffinity() / 100d;
                double trust = rel.getTrust() == null ? 0d : rel.getTrust() / 100d;
                closeness = Math.max(0d, (affinity + trust + 2d) / 4d);
                break;
            }
        }
        double distance = sameLocation(npc.getLocation(), player.getLocation()) ? 1d : 0.3d;
        return urgency * 100d + closeness * 10d + distance;
    }

    private Interaction lastInteraction(Long projectId, Long npcId, String playerRef) {
        List<ActorEvent> events = eventRepository
                .findByProjectIdAndCharacterRef(projectId, npcId, NpcRefs.of(npcId));
        int scanned = 0;
        for (ActorEvent event : events) {
            if (event == null || event.getGameHour() == null) {
                continue;
            }
            if (++scanned > EVENT_SCAN_LIMIT) {
                break;
            }
            if (!"dialog".equalsIgnoreCase(event.getKind()) && !"action".equalsIgnoreCase(event.getKind())) {
                continue;
            }
            if (event.getCharacterIds() != null && event.getCharacterIds().contains(playerRef)) {
                return new Interaction(event.getId(), event.getGameHour() * HOUR_SECONDS);
            }
        }
        return null;
    }

    private String partyOtherThan(String attributesJson, String excludeRef) {
        JsonNode parties = parties(attributesJson);
        if (parties == null) {
            return null;
        }
        for (JsonNode node : parties) {
            String ref = node == null || node.isNull() ? null : node.asText();
            if (!blank(ref) && !excludeRef.equals(ref.trim())) {
                return ref.trim();
            }
        }
        return null;
    }

    private boolean mentionsPlayer(ActorWorldFact fact, String playerRef) {
        JsonNode parties = parties(fact.getAttributesJson());
        if (parties == null) {
            return false;
        }
        for (JsonNode node : parties) {
            if (node != null && playerRef.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parties(String attributesJson) {
        if (blank(attributesJson)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(attributesJson);
            JsonNode parties = root == null ? null : root.get("parties");
            return parties != null && parties.isArray() ? parties : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode read(String json) {
        if (blank(json)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            // 兼容历史计划把 JSON 再包一层字符串的写法（"{\"type\":...}"）
            if (node != null && node.isTextual() && node.asText().stripLeading().startsWith("{")) {
                return MAPPER.readTree(node.asText());
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private String safeName(ActorCharacter ch) {
        return ch == null || blank(ch.getName()) ? "某人" : ch.getName();
    }

    private boolean sameLocation(String a, String b) {
        return !blank(a) && !blank(b) && a.trim().equalsIgnoreCase(b.trim());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 最近一次互动的因果锚点。 */
    private record Interaction(Long eventId, long gameSecond) {
    }
}
