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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.entity.ActorConflictRecord;
import com.holzyn.actor.domain.agent.runtime.entity.ActorResourceOccupancy;
import com.holzyn.actor.domain.agent.runtime.repository.ActorConflictRecordRepository;
import com.holzyn.actor.domain.agent.runtime.repository.ActorResourceOccupancyRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策与协调 意图冲突检测与协商服务（，domain/agent/runtime）。
 * <p>职责：行动执行前检查
 * 目标对象/地点/资源是否被其他 NPC 占用——<ul>
 * <li><b>既有判定（零新表复用）</b>：① 同地点占用——其他特殊 NPC 的进行中计划
 * （actor_plan status=planned/executing）目标同地点；② 目标角色正忙——决策 target
 * 命中项目内特殊 NPC 名且其 current_activity 非空；</li>
 * <li><b>资源登记</b>：③ actor_resource_occupancy——行动目标命中地点性
 * 资源时登记占用（起止游戏秒），到期自动失效、换目标自动释放自身旧占用；</li>
 * </ul>
 * 冲突 → 按 <b>importance×urgency 优先级排序</b>，高者先执行（takeover 强占）；本方可让时
 * 预算内 LLM 二次协商（scene=negotiate，{@link #CODE_NEGOTIATE} 模板），失败回退确定性
 * 等待/改道兜底（fallback_wait/fallback_redirect）——全部落 {@code actor_conflict_record}。</p>
 * <p>AI 协商位置：AgentRuntime 决策循环（事务外，allowAi=true）——同步 AI 不进事务
 * （S4 教训：事务内同步 AI 拖死）；AgentActionService.execute 兜底路径（事务内，
 * allowAi=false，仅确定性处置）。后的决策带 {@link #COORDINATED_MARKER} 防重入。</p>
 */
@Slf4j
@Service
public class ActionCoordinatorService {

    /** 协商 AI 调用场景（trace/用量记账 scene=negotiate，对齐内置模板编码） */
    public static final String SCENE_NEGOTIATE = "negotiate";

    /** 协调标记（调整后的决策携带此键 → 不再二次检测，防递归调整） */
    public static final String COORDINATED_MARKER = "_coordinated";

    /** 冲突类型：同地点占用 */
    public static final String TYPE_LOCATION_OCCUPIED = "location_occupied";
    /** 冲突类型：目标角色正忙 */
    public static final String TYPE_OBJECT_BUSY = "object_busy";
    /** 冲突类型：资源占用 */
    public static final String TYPE_RESOURCE_OCCUPIED = "resource_occupied";

    /** 处置结果：强占 */
    public static final String RES_TAKEOVER = "takeover";
    /** 处置结果：协商（让步/等待/改道） */
    public static final String RES_NEGOTIATED = "negotiated";
    /** 处置结果：确定性等待兜底 */
    public static final String RES_FALLBACK_WAIT = "fallback_wait";
    /** 处置结果：确定性改道兜底 */
    public static final String RES_FALLBACK_REDIRECT = "fallback_redirect";

    private final ActorResourceOccupancyRepository occupancyRepository;
    private final ActorConflictRecordRepository conflictRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorCharacterRepository characterRepository;
    private final RealtimeDecisionProperties realtimeProperties;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final BudgetGuard budgetGuard;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    /** AI 强度闸门（协商 AI 预算外放行判定；null=未装配按全量放行） */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /**
     * 决策模型 冲突仲裁判定（默认关场景；开启且采纳时优先，失败/升级回退 Qwen 协商；
     * 字段注入保持构造器不变，null=未装配[单测]直接走 Qwen）
     */
    @Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.NegotiateJudge decisionJudge;

    /** 世界时钟（游戏秒回退；null=纯数据单测/未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 统一 AI 上下文注入器（协商属「角色视角」判定——AI 以我方角色视角决定让路/等待/改道，
     * 前置世界观简报 + 我方角色核心卡；null=未装配跳过注入，不影响原调用）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 构造（Spring @Autowired 全量注入；测试可 mock）。
     */
    @Autowired
    public ActionCoordinatorService(ActorResourceOccupancyRepository occupancyRepository,
                                    ActorConflictRecordRepository conflictRepository,
                                    ActorActionPlanRepository planRepository,
                                    ActorCharacterRepository characterRepository,
                                    RealtimeDecisionProperties realtimeProperties,
                                    AiSpringChatGateway aiChatGateway,
                                    PromptTemplateService promptTemplateService,
                                    UsageLogService usageLogService,
                                    BudgetGuard budgetGuard,
                                    CurrentUserProvider currentUserProvider,
                                    ObjectMapper objectMapper) {
        this.occupancyRepository = occupancyRepository;
        this.conflictRepository = conflictRepository;
        this.planRepository = planRepository;
        this.characterRepository = characterRepository;
        this.realtimeProperties = realtimeProperties;
        this.aiChatGateway = aiChatGateway;
        this.promptTemplateService = promptTemplateService;
        this.usageLogService = usageLogService;
        this.budgetGuard = budgetGuard;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 冲突检测与处置统一入口（行动执行前调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 行动角色引用（npc:{id}）
     *
     * @param npcName 行动角色名（可空）
     *
     * @param decision 决策 JSON 节点（type/action/target/urgency/duration/params）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     *
     * @param aiAllowed 是否允许 AI 协商（AgentRuntime 事务外=true；execute 事务内=false 仅确定性）
     *
     * @return 调整结果（changed=false 表示无需调整；decision()=调整后决策 Map，null=未调整）
     */
    public DecisionAdjust resolveConflict(Long projectId, String npcRef, String npcName,
                                          JsonNode decision, Long gameSecond, boolean aiAllowed) {
        if (!enabled() || projectId == null || npcRef == null || npcRef.isBlank()
                || decision == null || !decision.isObject()) {
            return DecisionAdjust.unchanged();
        }
        // 协调标记防重入：已协调过的决策不再二次检测（防协商调整后的行动再被拦）
        if (decision.has(COORDINATED_MARKER)) {
            return DecisionAdjust.unchanged();
        }
        try {
            String target = targetOf(decision);
            if (target == null || target.isBlank()) {
                return DecisionAdjust.unchanged(); // 无明确目标（驻地附近/无落点）不检测
            }
            Long nowSecond = gameSecond != null ? gameSecond : currentGameSecond(projectId);
            int myPriority = priority(projectId, npcRef, decision);

            // ① 既有判定：同地点被其他 NPC 进行中计划占用
            PlanOccupant planOccupant = planOccupant(projectId, npcRef, target);
            // ② 既有判定：目标角色正忙
            CharacterBusy busy = targetCharacterBusy(projectId, npcRef, target);
            // ③ 资源登记：该资源当前被其他 NPC 占用
            Occupant resourceOccupant = resourceOccupant(projectId, npcRef, target, nowSecond);

            // 冲突归并：优先资源占用 → 计划占用 → 目标角色忙（记录一种即可）
            String conflictType = null;
            String opponentRef = null;
            String opponentName = null;
            int opponentPriority = 0;
            if (resourceOccupant != null) {
                conflictType = TYPE_RESOURCE_OCCUPIED;
                opponentRef = resourceOccupant.ref();
                opponentName = resourceOccupant.name();
                opponentPriority = resourceOccupant.priority();
            } else if (planOccupant != null) {
                conflictType = TYPE_LOCATION_OCCUPIED;
                opponentRef = planOccupant.ref();
                opponentName = planOccupant.name();
                opponentPriority = planOccupant.priority();
            } else if (busy != null) {
                conflictType = TYPE_OBJECT_BUSY;
                opponentRef = busy.ref();
                opponentName = busy.name();
                opponentPriority = busy.priority();
            }

            if (conflictType == null) {
                // 无冲突 → 登记资源占用（地点性目标）并原样放行
                registerOccupancy(projectId, npcRef, npcName, target, nowSecond, decision);
                return DecisionAdjust.unchanged();
            }

            // 冲突 → 优先级排序：我方更高 → 强占（释放对方占用/登记自己）原样执行
            if (myPriority > opponentPriority) {
                releaseOpponent(resourceOccupant);
                registerOccupancy(projectId, npcRef, npcName, target, nowSecond, decision);
                record(projectId, npcRef, npcName, target, conflictType, opponentRef, opponentName,
                        myPriority, opponentPriority, RES_TAKEOVER, null,
                        decision.path("action").asText(""), decision.path("action").asText(""));
                log.info("[冲突协调] 我方优先级更高，强占资源: project={} ref={} 目标={} 优先级 {}>{}",
                        projectId, npcRef, target, myPriority, opponentPriority);
                return DecisionAdjust.unchanged();
            }

            // 本方可让 → AI 协商（预算内，AgentRuntime 事务外）或确定性兜底（等待/改道）
            Map<String, Object> negotiated = aiAllowed ? negotiate(projectId, npcRef, npcName,
                    opponentRef, opponentName, target, myPriority, opponentPriority, conflictType, decision) : null;
            Map<String, Object> adjusted;
            String resolution;
            if (negotiated != null) {
                adjusted = negotiated;
                resolution = RES_NEGOTIATED;
            } else {
                adjusted = fallbackAdjust(target, npcName, myPriority);
                resolution = RES_FALLBACK_WAIT;
            }
            record(projectId, npcRef, npcName, target, conflictType, opponentRef, opponentName,
                    myPriority, opponentPriority, resolution,
                    negotiated == null ? null : toJson(negotiated),
                    decision.path("action").asText(""),
                    String.valueOf(adjusted.getOrDefault("action", "")));
            log.info("[冲突协调] 冲突处置={}: project={} ref={} 目标={} 对手={}",
                    resolution, projectId, npcRef, target, opponentName);
            return new DecisionAdjust(true, adjusted);
        } catch (Exception e) {
            log.warn("[冲突协调] 冲突检测失败（原样放行）: ref={} : {}", npcRef, e.getMessage());
            return DecisionAdjust.unchanged();
        }
    }

    // ==================== 冲突检测（确定性） ====================

    /** 资源占用判定（actor_resource_occupancy active 且未到期且占用者非本人）。 */
    private Occupant resourceOccupant(Long projectId, String npcRef, String target, Long nowSecond) {
        if (!occupancyEnabled()) {
            return null;
        }
        String key = resourceKey(target);
        List<ActorResourceOccupancy> rows =
                occupancyRepository.findByProjectIdAndResourceKeyAndStatusOrderByIdDesc(projectId, key, "active");
        for (ActorResourceOccupancy o : rows) {
            if (o.getNpcRef() != null && o.getNpcRef().equals(npcRef)) {
                continue; // 本人占用不算冲突
            }
            if (o.getUntilGameSecond() != null && nowSecond != null && o.getUntilGameSecond() < nowSecond) {
                continue; // 已到期自动失效
            }
            int priority = priorityOf(o.getNpcRef(), 3);
            return new Occupant(o.getNpcRef(), o.getNpcName(), priority, o);
        }
        return null;
    }

    /** 既有判定：同地点被其他 NPC 进行中计划占用（actor_plan status=planned/executing 目标同地点）。 */
    private PlanOccupant planOccupant(Long projectId, String npcRef, String target) {
        String key = resourceKey(target);
        // 长跑抓修：排除已故角色（死人无计划、不占用资源）
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        for (ActorCharacter ch : chars) {
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（与调用方 npcRef 同口径比较，不再手写前缀字面量）
            String ref = NpcRefs.of(ch.getId());
            if (ref == null || ref.equals(npcRef)) {
                continue;
            }
            List<ActorActionPlan> plans = planRepository
                    .findByCharacterIdAndStatusInOrderByIdDesc(ch.getId(), List.of("planned", "executing"));
            for (ActorActionPlan p : plans) {
                String pt = planTarget(p.getActionJson());
                if (pt != null && key.equals(resourceKey(pt))) {
                    return new PlanOccupant(ref, ch.getName(), priorityOf(ref, 3));
                }
            }
        }
        return null;
    }

    /** 既有判定：决策 target 命中项目内特殊 NPC 名且其 current_activity 非空（正忙）。 */
    private CharacterBusy targetCharacterBusy(Long projectId, String npcRef, String target) {
        // 长跑抓修：排除已故角色（死人不算「正忙」）
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        for (ActorCharacter ch : chars) {
            if (ch.getName() == null || ch.getName().isBlank()) {
                continue;
            }
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（与调用方 npcRef 同口径比较，不再手写前缀字面量）
            String ref = NpcRefs.of(ch.getId());
            if (ref == null || ref.equals(npcRef)) {
                continue;
            }
            if (ch.getName().trim().equals(target.trim())
                    || target.contains(ch.getName().trim())) {
                boolean busy = ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank();
                if (busy) {
                    return new CharacterBusy(ref, ch.getName(), priorityOf(ref, 3));
                }
            }
        }
        return null;
    }

    // ==================== 资源登记 ====================

    /** 登记资源占用：先释放自身旧占用（换目标自动释放），再登记新占用（到期=开始+行动耗时，封顶）。 */
    private void registerOccupancy(Long projectId, String npcRef, String npcName, String target,
                                   Long nowSecond, JsonNode decision) {
        if (!occupancyEnabled()) {
            return;
        }
        String key = resourceKey(target);
        // 释放自身全部旧占用（换地点/改目标即释放）
        List<ActorResourceOccupancy> mine =
                occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, npcRef, "active");
        for (ActorResourceOccupancy old : mine) {
            old.setStatus("released");
            occupancyRepository.save(old);
        }
        long durationSec = Math.max(1, decision.path("duration").asInt(30)) * 60L;
        long maxSec = Math.max(1, occupancyMaxMinutes()) * 60L;
        long until = nowSecond == null ? Long.MAX_VALUE : nowSecond + Math.min(durationSec, maxSec);
        ActorResourceOccupancy occ = new ActorResourceOccupancy();
        occ.setProjectId(projectId);
        occ.setResourceKey(key);
        occ.setResourceName(abbreviate(target, 200));
        occ.setResourceType("location");
        occ.setNpcRef(npcRef);
        occ.setNpcName(npcName);
        occ.setSinceGameSecond(nowSecond);
        occ.setUntilGameSecond(nowSecond == null ? null : until);
        occ.setStatus("active");
        occupancyRepository.save(occ);
        log.debug("[冲突协调] 登记资源占用: project={} ref={} 资源={} 到={}", projectId, npcRef, key, until);
    }

    /** 强占路径释放对方占用（无则跳过）。 */
    private void releaseOpponent(Occupant opponent) {
        if (opponent == null || opponent.occupancy() == null) {
            return;
        }
        ActorResourceOccupancy occ = opponent.occupancy();
        occ.setStatus("released");
        occupancyRepository.save(occ);
        log.debug("[冲突协调] 强占释放对方占用: ref={} 资源={}", occ.getNpcRef(), occ.getResourceKey());
    }

    // ==================== AI 协商（scene=negotiate，预算门控） ====================

    /**
     * 冲突二次协商（预算内；AgentRuntime 事务外路径调用）：渲染 negotiate 模板 → AI 输出
     * {decision: proceed|wait|yield|redirect, action, target, reason} → 解析为调整后决策；
     * 预算不足/AI 失败/解析失败 → null（确定性兜底）。proceed=仍去（登记占用）；
     * wait=稍候再去；yield=放弃去该处；redirect=改道其他目标。
     */
    private Map<String, Object> negotiate(Long projectId, String npcRef, String npcName,
                                          String opponentRef, String opponentName, String target,
                                          int myPriority, int opponentPriority, String conflictType,
                                          JsonNode decision) {
        if (realtimeProperties == null || realtimeProperties.getCoordination() == null
                || !realtimeProperties.getCoordination().isNegotiateAiEnabled()) {
            return null;
        }
        // 预算门控（与决策循环同窗口；AI 强度 programmatic 档不放行 AI）
        boolean aiAllowed = aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
        if (!aiAllowed || !budgetGuard.tryConsume(projectId)) {
            return null;
        }
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            String opponentNameText = (opponentName == null || opponentName.isBlank()) ? "对方" : opponentName;
            String myIntent = decision.path("action").asText("");
            // ：决策模型 冲突仲裁优先（默认关场景；采纳即用，否则回退 Qwen 协商）
            if (decisionJudge != null) {
                try {
                    String stateText = "我（" + (npcName == null || npcName.isBlank() ? "该角色" : npcName)
                            + "）想去" + target + "（意图：" + abbreviate(myIntent, 120)
                            + "，紧要程度 " + myPriority + "），但" + opponentNameText + "也在使用/要去那里"
                            + "（紧要程度 " + opponentPriority + "，意图："
                            + abbreviate(opponentIntent(projectId, opponentRef, conflictType, target), 120) + "）。";
                    String chosen = decisionJudge.decide(projectId, com.holzyn.actor.common.NpcRefs.idOf(npcRef),
                            stateText);
                    if (chosen != null) {
                        Map<String, Object> adjusted = decisionAdjusted(chosen, npcName, target, decision);
                        if (adjusted != null) {
                            log.info("[冲突协调] 决策模型 仲裁：ref={} → {}（目标 {}）", npcRef, chosen, target);
                        }
                        return adjusted; // proceed → null（原样执行放行）
                    }
                } catch (Exception e) {
                    log.warn("[冲突协调] 决策模型 仲裁异常（回退 Qwen 协商）: ref={} : {}", npcRef, e.getMessage());
                }
            }
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("my_name", npcName == null || npcName.isBlank() ? "我" : npcName);
            ph.put("my_intent", abbreviate(myIntent, 120));
            ph.put("opponent_name", opponentNameText);
            ph.put("opponent_intent", opponentIntent(projectId, opponentRef, conflictType, target));
            ph.put("resource_name", target);
            ph.put("my_priority", myPriority);
            ph.put("opponent_priority", opponentPriority);
            String prompt = promptTemplateService.render(userId, projectId, CODE_NEGOTIATE(), ph);
            // 上下文注入（2026-10-07）：协商属「角色视角」判定——AI 以我方角色视角决定
            // 让路/等待/改道，前置世界观简报 + 我方角色核心卡（未装配/失败零阻断；
            // 决策模型 仲裁路径已由 DecisionGate 统一富化）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.compose(projectId, npcRef);
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    0.7, realtimeProperties.getCoordination().getNegotiateMaxTokens(), true);
            // trace 包裹（scene=negotiate；与既有 AI 调用埋点一致）
            long startMs = System.currentTimeMillis();
            AiChatResult result = com.holzyn.actor.domain.debug.service.AiCallContext.run(
                    SCENE_NEGOTIATE, null, () -> aiChatGateway.chatCompletion(userId, projectId, null, req));
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                    SCENE_NEGOTIATE, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));
            String json = JsonUtil.extractJson(result.content());
            if (json == null) {
                throw new BizException("AI 未返回有效 JSON");
            }
            return applyNegotiation(objectMapper.readTree(json), npcName, target, decision);
        } catch (Exception e) {
            log.warn("[冲突协调] 协商 AI 失败（确定性兜底）: ref={} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /**
     * 决策模型 仲裁选择 → 调整后决策（proceed=null 放行 / wait / yield / redirect；
     * 语义与 {@link #applyNegotiation} 各分支对齐，动作文本用 决策模型 版默认措辞）。
     */
    private Map<String, Object> decisionAdjusted(String chosen, String npcName, String target,
                                                JsonNode decision) {
        String who = npcName == null || npcName.isBlank() ? "我" : npcName;
        switch (chosen) {
            case "proceed" -> {
                return null; // 仍去：原样执行（调用方放行并登记占用）
            }
            case "wait" -> {
                Map<String, Object> adjusted = new LinkedHashMap<>();
                adjusted.put("type", "rest");
                adjusted.put("action", who + "在" + target + "附近稍候片刻，稍后再去");
                adjusted.put("target", decision.path("target").asText(""));
                adjusted.put("reason", "对方正在使用" + target + "（决策模型 仲裁：稍候再去）");
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", Map.of("move", Map.of("to", target)));
                adjusted.put("waitMinutes", fallbackWaitGameMinutes());
                adjusted.put(COORDINATED_MARKER, true);
                return adjusted;
            }
            case "yield" -> {
                Map<String, Object> adjusted = new LinkedHashMap<>();
                adjusted.put("type", "custom");
                adjusted.put("action", who + "改做别的事，暂不前往" + target);
                adjusted.put("target", "驻地附近");
                adjusted.put("reason", "把" + target + "让给对方（决策模型 仲裁）");
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", Map.of());
                adjusted.put(COORDINATED_MARKER, true);
                return adjusted;
            }
            case "redirect" -> {
                Map<String, Object> adjusted = new LinkedHashMap<>();
                adjusted.put("type", "move");
                adjusted.put("action", who + "改去别处");
                adjusted.put("target", "驻地附近");
                adjusted.put("reason", target + "被占用，改去他处（决策模型 仲裁）");
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", Map.of());
                adjusted.put(COORDINATED_MARKER, true);
                return adjusted;
            }
            default -> {
                return null; // 非法选择：确定性兜底
            }
        }
    }

    /** 协商输出 → 调整后决策（decision=proceed/wait/yield/redirect；非法/缺失回退 null）。 */
    private Map<String, Object> applyNegotiation(JsonNode node, String npcName, String target,
                                                 JsonNode decision) {        if (node == null || !node.isObject()) {
            return null;
        }
        String choice = node.path("decision").asText("").trim().toLowerCase();
        String action = node.path("action").asText("");
        String newTarget = node.path("target").asText("");
        String reason = node.path("reason").asText("");
        Map<String, Object> adjusted = new LinkedHashMap<>();
        String who = npcName == null || npcName.isBlank() ? "我" : npcName;
        switch (choice) {
            case "proceed" -> {
                return null; // 仍去：原样执行（调用方放行并登记占用）
            }
            case "wait" -> {
                adjusted.put("type", "rest");
                adjusted.put("action", action.isBlank()
                        ? who + "在" + target + "附近稍候片刻，稍后再去" : action);
                adjusted.put("target", decision.path("target").asText(""));
                adjusted.put("reason", reason.isBlank() ? "对方正在使用" + target + "，我稍候再去" : reason);
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", Map.of("move", Map.of("to", target)));
                // 等待语义 → 等待类计划（到点重新决策；AgentRuntime 据此排期，非立即执行）
                adjusted.put("waitMinutes", fallbackWaitGameMinutes());
            }
            case "yield" -> {
                adjusted.put("type", "custom");
                adjusted.put("action", action.isBlank() ? who + "改做别的事，暂不前往" + target : action);
                adjusted.put("target", newTarget.isBlank() ? "驻地附近" : newTarget);
                adjusted.put("reason", reason.isBlank() ? "把" + target + "让给对方" : reason);
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", Map.of());
            }
            case "redirect" -> {
                adjusted.put("type", "move");
                adjusted.put("action", action.isBlank() ? who + "改去别处" : action);
                adjusted.put("target", newTarget.isBlank() ? "驻地附近" : newTarget);
                adjusted.put("reason", reason.isBlank() ? target + "被占用，改去他处" : reason);
                adjusted.put("urgency", decision.path("urgency").asInt(3));
                adjusted.put("duration", decision.path("duration").asInt(30));
                adjusted.put("params", newTarget.isBlank() ? Map.of() : Map.of("move", Map.of("to", newTarget)));
            }
            default -> {
                return null; // 非法选择：确定性兜底
            }
        }
        adjusted.put(COORDINATED_MARKER, true);
        return adjusted;
    }

    /** 确定性兜底：等待（稍候再去；带 waitMinutes → 等待类计划排期，到点重新决策）。 */
    private Map<String, Object> fallbackAdjust(String target, String npcName, int priority) {
        String who = npcName == null || npcName.isBlank() ? "我" : npcName;
        Map<String, Object> adjusted = new LinkedHashMap<>();
        adjusted.put("type", "rest");
        adjusted.put("action", who + "见" + target + "正有人使用，先在附近稍候片刻，稍后再去");
        adjusted.put("target", target);
        adjusted.put("reason", "目标地点被其他 NPC 占用（优先级低于对方），确定性兜底：等待");
        adjusted.put("urgency", Math.max(1, Math.min(5, priority)));
        adjusted.put("duration", 30);
        adjusted.put("params", Map.of("move", Map.of("to", target)));
        adjusted.put("waitMinutes", fallbackWaitGameMinutes());
        adjusted.put(COORDINATED_MARKER, true);
        return adjusted;
    }

    /** 确定性兜底等待时长（游戏分钟；配置缺失回退 20）。 */
    private int fallbackWaitGameMinutes() {
        if (realtimeProperties == null || realtimeProperties.getCoordination() == null) {
            return 20;
        }
        return realtimeProperties.getCoordination().getFallbackWaitGameMinutes();
    }

    // ==================== 记录（actor_conflict_record） ====================

    private void record(Long projectId, String npcRef, String npcName, String target, String conflictType,
                        String opponentRef, String opponentName, int myPriority, int opponentPriority,
                        String resolution, String negotiateJson, String actionBefore, String actionAfter) {
        try {
            ActorConflictRecord rec = new ActorConflictRecord();
            rec.setProjectId(projectId);
            rec.setGameSecond(currentGameSecond(projectId));
            rec.setNpcRef(npcRef);
            rec.setNpcName(npcName);
            rec.setResourceKey(abbreviate(resourceKey(target), 200));
            rec.setResourceName(abbreviate(target, 200));
            rec.setConflictType(conflictType);
            rec.setOpponentRef(opponentRef);
            rec.setOpponentName(opponentName);
            rec.setMyPriority(myPriority);
            rec.setOpponentPriority(opponentPriority);
            rec.setResolution(resolution);
            rec.setNegotiateJson(negotiateJson);
            rec.setActionBefore(abbreviate(actionBefore, 400));
            rec.setActionAfter(abbreviate(actionAfter, 400));
            conflictRepository.save(rec);
        } catch (Exception e) {
            log.warn("[冲突协调] 冲突记录失败（不影响行动主流程）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    // ==================== 查询（调试/冒烟） ====================

    /** 冲突记录列表（按角色过滤；角色空=全项目；limit 截断）。 */
    public List<ActorConflictRecord> listConflicts(Long projectId, String npcRef, Integer limit) {
        List<ActorConflictRecord> all = npcRef == null || npcRef.isBlank()
                ? conflictRepository.findByProjectIdOrderByIdDesc(projectId)
                : conflictRepository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
        if (limit != null && limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    /** 资源占用列表（全项目；active 优先）。 */
    public List<ActorResourceOccupancy> listOccupancy(Long projectId, Integer limit) {
        List<ActorResourceOccupancy> all = occupancyRepository.findByProjectIdOrderByIdDesc(projectId);
        if (limit != null && limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    // ==================== 私有辅助 ====================

    /** 优先级（我方=角色重要度 × 决策 urgency）。 */
    private int priority(Long projectId, String npcRef, JsonNode decision) {
        int importance = importanceOf(npcRef);
        int urgency = decision == null ? 3 : Math.max(1, Math.min(5, decision.path("urgency").asInt(3)));
        return importance * urgency;
    }

    /** 角色重要度（查询失败回退 3）。 */
    private int importanceOf(String npcRef) {
        try {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用能解析成 actor_profile 行即可读
            // 重要度（special/common 都成立），解析主键统一走 NpcRefs.idOf；null 保持既有「回退 3」语义
            Long id = NpcRefs.idOf(npcRef);
            if (id != null) {
                ActorCharacter ch = characterRepository.findById(id).orElse(null);
                return ch == null || ch.getImportance() == null ? 3 : Math.max(1, ch.getImportance());
            }
        } catch (Exception ignored) {
            // 查询失败回退默认
        }
        return 3;
    }

    /** 对手优先级（重要度 × 3 缺省；资源占用者可读其行动 urgency——当前用缺省 3）。 */
    private int priorityOf(String npcRef, int urgency) {
        return importanceOf(npcRef) * Math.max(1, Math.min(5, urgency));
    }

    /** 决策目标地点（params.move.to 优先，其次 target）。 */
    private static String targetOf(JsonNode decision) {
        String move = decision.path("params").path("move").path("to").asText("");
        return move.isBlank() ? decision.path("target").asText("") : move;
    }

    /** 计划行动 JSON 的目标（解析失败返回空）。 */
    private String planTarget(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return "";
        }
        try {
            JsonNode n = objectMapper.readTree(actionJson);
            return targetOf(n);
        } catch (Exception e) {
            return "";
        }
    }

    /** 协商提示中的对手意图（按冲突类型生成描述）。 */
    private String opponentIntent(Long projectId, String opponentRef, String conflictType, String target) {
        if (TYPE_RESOURCE_OCCUPIED.equals(conflictType) || TYPE_LOCATION_OCCUPIED.equals(conflictType)) {
            List<ActorResourceOccupancy> rows = opponentRef == null ? List.of()
                    : occupancyRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, opponentRef, "active");
            if (!rows.isEmpty()) {
                return "正在" + rows.get(0).getResourceName() + "（占用中）";
            }
            return "正在使用" + target;
        }
        return "正在忙（当前有活动）";
    }

    /** 资源键归一化（trim + 小写；null/空白回退原样）。 */
    static String resourceKey(String target) {
        return target == null ? "" : target.trim().toLowerCase();
    }

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 冲突检测总开关。 */
    private boolean enabled() {
        return realtimeProperties == null || realtimeProperties.getCoordination() == null
                || realtimeProperties.getCoordination().isConflictDetectEnabled();
    }

    /** 资源登记开关。 */
    private boolean occupancyEnabled() {
        return realtimeProperties == null || realtimeProperties.getCoordination() == null
                || realtimeProperties.getCoordination().isOccupancyEnabled();
    }

    /** 占用时长上限（游戏分钟）。 */
    private int occupancyMaxMinutes() {
        if (realtimeProperties == null || realtimeProperties.getCoordination() == null) {
            return 120;
        }
        return realtimeProperties.getCoordination().getOccupancyMaxGameMinutes();
    }

    /** negotiate 模板编码（PromptTemplateService 常量）。 */
    private String CODE_NEGOTIATE() {
        return "negotiate";
    }

    /** Map → JSON（失败回退 "{}"）。 */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    /** 冲突处置结果（不可变）。 */
    public record DecisionAdjust(boolean changed, Map<String, Object> decision) {
        static DecisionAdjust unchanged() {
            return new DecisionAdjust(false, null);
        }
    }

    /** 资源占用对手信息。 */
    record Occupant(String ref, String name, int priority, ActorResourceOccupancy occupancy) {
    }

    /** 计划占用对手信息。 */
    record PlanOccupant(String ref, String name, int priority) {
    }

    /** 目标角色正忙信息。 */
    record CharacterBusy(String ref, String name, int priority) {
    }
}
