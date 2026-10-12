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
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.entity.ActorInterruptRecord;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorInterruptRecordRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.RequiredArgsConstructor;
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
 * 打断与恢复服务。
 * <p>职责：在即时通道（刺激→打断→决策→恢复）中承接<b>打断与恢复</b>环节——
 * ① {@link #interrupt}：刺激通过仲裁后、即时决策触发前，把角色当前行动记录为被打断任务
 * 即时决策响应行动执行完后，由 AI（interrupt_resume 模板，依据被打断任务/当前状态/刺激/性格）
 * 自行决定恢复原任务 / 调整为新任务 / 放弃（三态），AI 不可用/超预算/解析失败时回退
 * - 共性框架确定性兜底（未过期且原任务仍合理 → resume，否则 abandon）。</p>
 * <p>设计依据：
 * 打断=当前行动记录（current_activity + 位置快照 + 游戏时刻 + 过期窗口）；恢复=即时行动结束后
 * 判断——原计划未过期且状态仍合理 → 恢复（resume，回原地点继续原活动，新建计划执行）；
 * 否则调整（adjust）或丢弃（abandon）。行为连续性：无刺激不重复决策；
 * 恢复决策与即时决策同流同预算（AgentRuntime STIMULUS 决策循环内执行，复用同一 BudgetGuard 窗口），
 * 不重复计冷却，AI 调用纳入审计（scene=interrupt_resume）。</p>
 * <p>成本护栏：恢复决策 AI 调用经 {@link BudgetGuard#tryConsume} 门控（同即时决策窗口）；
 * 打断记录创建为纯程序化零 AI；共性框架兜底零 AI。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterruptRecoveryService {

    /** 恢复决策 AI 调用场景（trace/用量记账 scene=interrupt_resume，对齐内置模板编码） */
    private static final String SCENE_RESUME = "interrupt_resume";

    /** 打断前探针 AI 调用场景 */
    private static final String SCENE_PROBE = "interrupt_probe";

    /** 恢复决策最大输出 token */
    private static final int RESUME_MAX_TOKENS = 256;

    /** 打断前探针最大输出 token */
    private static final int PROBE_MAX_TOKENS = 128;

    /** 恢复决策生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 字段最大长度（对齐角色状态字段约束） */
    private static final int FIELD_MAX = 255;

    /** 恢复计划触发源标记（actor_plan.trigger_type，区分即时响应与恢复/调整执行） */
    private static final String TRIGGER_TYPE_RESUME = "stimulus_resume";

    private final ActorInterruptRecordRepository interruptRepository;
    private final ActorCharacterRepository characterRepository;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/crowd——「是否特殊型」决定打断记录/
     * 恢复决策/打断前探针是否生效，类型必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    private final ActorActionPlanRepository planRepository;
    private final AgentActionService agentActionService;
    private final BudgetGuard budgetGuard;
    private final RealtimeDecisionProperties props;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    /** 世界时钟（当前游戏秒回退；null=纯数据单测/未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** L3 核心记忆（轻量性格注入：renderCoreSegment；null=未装配跳过，不阻断恢复决策） */
    @Autowired(required = false)
    private CoreMemoryService coreMemoryService;

    /** 角色卡仓库（性格/价值观/行为模式摘要；null=未装配跳过） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;

    /** NPC 状态服务（【当前状态】段：需求/心情/情绪/urgency；null=跳过状态段） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /**
     * 个性注入服务（实时行为决策系统：全量【角色个性】段组装[persona+L3+brainProfile+关系+时间感]；
     * 打断前探针与恢复决策共用；null=未装配回退 轻量性格组装）
     */
    @Autowired(required = false)
    private PersonalityInjectionService personalityInjectionService;

    /**
     * 决策 state delta 服务（恢复决策上下文注入「原计划 delta 校验」
     * [时效/目标状态]，共性框架兜底按 delta 时效判 abandon；null=未装配跳过，行为回到 前）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.StateDeltaService stateDeltaService;

    /** ToM 注入开关（realtime-decision.coordination.tom-inject-enabled；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.realtime-decision.coordination.tom-inject-enabled:true}")
    private boolean tomInjectEnabled = true;

    // ==================== 打断记录创建（interrupt） ====================

    /**
     * 记录打断（刺激通过仲裁后、即时决策触发前调用）：把角色当前行动（current_activity + 位置）
     * 快照为 {@code actor_interrupt_record} 行（status=active，含过期窗口）。
     * <p>创建条件：① 角色存在非空当前活动；② 无未消费的 active 打断记录
     * （已存在未过期 active → 不覆盖返回其 planId；已过期 → 先丢弃旧记录再建新，防阻塞）。
     * 仅特殊型 NPC（普通型忽略）；零 AI 成本。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（统一 {@code npc:{id}}）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     *
     * @return 被打断计划 ID（无对应计划行/未创建返回 null；供 DecisionContext.interruptedPlanId 回填）
     */
    public Long interrupt(Long projectId, String npcRef, Long gameSecond) {
        if (!props.isInterruptEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return null;
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!npcTypeResolver.isSpecial(projectId, npcRef)) {
            return null; // 普通型不打断（走分层概率化，0 AI）
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long characterId = NpcRefs.idOf(npcRef);
        if (characterId == null) {
            return null; // 非法引用（保持原有 null → 不创建 的处理）
        }
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null || ch.getProjectId() == null) {
            return null;
        }
        String activity = ch.getCurrentActivity();
        if (activity == null || activity.isBlank()) {
            return null; // 无当前活动不创建
        }
        Long nowSecond = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        // 无 active 不覆盖：已存在未过期 active → 跳过（返回其 planId 供 ctx）；
        // 已过期 active → 丢弃旧记录后允许新建（防中断后未恢复导致永久阻塞）
        List<ActorInterruptRecord> actives =
                interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, npcRef, "active");
        if (!actives.isEmpty()) {
            ActorInterruptRecord existing = actives.get(0);
            if (!expired(existing, nowSecond)) {
                return existing.getPlanId();
            }
            discard(existing, nowSecond);
        }
        ActorInterruptRecord rec = new ActorInterruptRecord();
        rec.setProjectId(projectId);
        // ：类型值字面量统一走 NpcRefs.TYPE_SPECIAL（npc_type 存库，单前缀不再编码类型）
        rec.setNpcType(NpcRefs.TYPE_SPECIAL);
        rec.setNpcRef(npcRef);
        rec.setPlanId(null); // ：当前行动通常无对应计划行（计划同步执行完成），活动文本即被打断任务
        rec.setInterruptedActivity(truncate(activity, FIELD_MAX));
        rec.setInterruptedLocation(truncate(ch.getLocation(), 100));
        rec.setInterruptedAtGameSecond(nowSecond);
        rec.setExpireAtGameSecond(nowSecond == null ? null : nowSecond + expireSeconds());
        rec.setStatus("active");
        interruptRepository.save(rec);
        log.info("[打断恢复] 记录被打断任务: project={} ref={} 活动={} 地点={} 时刻={}",
                projectId, npcRef, abbreviate(activity, 40), ch.getLocation(), nowSecond);
        return rec.getPlanId();
    }

    // ==================== 恢复判断与执行（maybeResume） ====================

    /**
     * 恢复判断入口（即时决策响应行动执行完后调用，与即时决策同流同预算）：
     * 读取该角色 active 打断记录 → AI 恢复决策（interrupt_resume，预算门控）→ 三态执行；
     * AI 不可用/超预算/解析失败 → - 共性框架确定性兜底。
     * <p>三态执行：resume=回被打断地点继续原活动（新建计划执行，trigger_type=
     * stimulus_resume）；adjust=按 AI 新 action/target 新建计划执行；abandon=仅标记丢弃。
     * 无 active 记录 / 开关关闭 / 人群 → 直接返回（零成本）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（统一 {@code npc:{id}}）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     *
     * @param stimulusText 打断刺激文本（进恢复决策提示词，可空）
     */
    public void maybeResume(Long projectId, String npcRef, Long gameSecond, String stimulusText) {
        if (!props.isInterruptEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!npcTypeResolver.isSpecial(projectId, npcRef)) {
            return;
        }
        List<ActorInterruptRecord> actives =
                interruptRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, npcRef, "active");
        if (actives.isEmpty()) {
            return; // 无被打断任务（非刺激触发的决策循环等）
        }
        ActorInterruptRecord rec = actives.get(0);
        Long nowSecond = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        // ：delta 时效校验——最近决策 delta 已过时效 → 直接 abandon
        //
        if (stateDeltaService != null) {
            try {
                var validity = stateDeltaService.validityOf(projectId, npcRef, nowSecond);
                if (validity != null && validity.expired()) {
                    log.info("[打断恢复] delta 校验：原计划已过时效，直接放弃恢复: project={} ref={}",
                            projectId, npcRef);
                    applyResumeDecision(projectId, npcRef, rec,
                            new ResumeDecisionParse.ResumeDecision("abandon", null, null,
                                    "共性框架兜底：原计划已过时效（state delta 校验），放弃恢复"), nowSecond);
                    return;
                }
            } catch (Exception e) {
                log.debug("[打断恢复] delta 时效校验失败（按原逻辑继续）: ref={} : {}", npcRef, e.getMessage());
            }
        }
        // AI 恢复决策（预算门控；AI 不可用/失败回退共性框架）
        ResumeDecisionParse.ResumeDecision decision = tryAiResumeDecision(projectId, npcRef, rec, stimulusText, nowSecond);
        if (decision == null) {
            decision = fallbackResumeDecision(rec, nowSecond);
            log.debug("[打断恢复] AI 恢复决策不可用，共性框架兜底: project={} ref={} 决策={}",
                    projectId, npcRef, decision == null ? "?" : decision.decision());
        }
        applyResumeDecision(projectId, npcRef, rec, decision, nowSecond);
    }

    /**
     * 三态执行（resume/adjust/abandon）：先标记消费（status/consumedAt），再按态执行——
     * resume 回原地点继续原活动 / adjust 执行 AI 新任务 / abandon 仅标记丢弃。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用
     *
     * @param rec 被打断任务记录（原地更新状态）
     *
     * @param decision 恢复决策（null 视为 abandon 防御）
     *
     * @param nowSecond 当前游戏秒（消费时刻审计）
     */
    private void applyResumeDecision(Long projectId, String npcRef, ActorInterruptRecord rec,
                                     ResumeDecisionParse.ResumeDecision decision, Long nowSecond) {
        if (decision == null) {
            rec.setStatus("discarded");
            rec.setConsumedAtGameSecond(nowSecond);
            interruptRepository.save(rec);
            return;
        }
        rec.setConsumedAtGameSecond(nowSecond);
        switch (decision.decision()) {
            case "resume" -> {
                rec.setStatus("restored");
                interruptRepository.save(rec);
                log.info("[打断恢复] AI 决定恢复原任务: project={} ref={} 活动={}",
                        projectId, npcRef, abbreviate(rec.getInterruptedActivity(), 40));
                executeResumePlan(projectId, npcRef, rec.getInterruptedActivity(),
                        rec.getInterruptedLocation(), decision.reason());
            }
            case "adjust" -> {
                rec.setStatus("adjusted");
                interruptRepository.save(rec);
                log.info("[打断恢复] AI 决定调整为新任务: project={} ref={} 新行动={}",
                        projectId, npcRef, abbreviate(decision.action(), 40));
                executeResumePlan(projectId, npcRef, decision.action(), decision.target(), decision.reason());
            }
            default -> { // abandon（含 null action 的 adjust 防御）
                rec.setStatus("discarded");
                interruptRepository.save(rec);
                log.info("[打断恢复] AI 决定放弃原任务: project={} ref={} 活动={} 原因={}",
                        projectId, npcRef, abbreviate(rec.getInterruptedActivity(), 40),
                        decision.reason() == null ? "" : decision.reason());
            }
        }
    }

    /**
     * 恢复/调整任务执行：新建行动计划（trigger_type=stimulus_resume）并经 AgentActionService
     * 真执行（改 current_activity/location + 写日志 + SSE + 事件 + 记忆/关系挂钩 + 沙盘同步）。
     * <p>resume 语义=回被打断地点继续原活动：target=被打断时地点（缺失回退「驻地附近」），
     * action=原活动文本；adjust 语义=按 AI 新 action/target 执行。执行失败仅告警（恢复为旁路增强）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用
     *
     * @param action 活动文本（空白则跳过）
     *
     * @param target 目标地点/对象（可空）
     *
     * @param reason 决策理由（可空，审计）
     */
    private void executeResumePlan(Long projectId, String npcRef, String action, String target, String reason) {
        if (projectId == null || npcRef == null || action == null || action.isBlank()) {
            return;
        }
        try {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            Long characterId = NpcRefs.idOf(npcRef);
            if (characterId == null) {
                return;
            }
            String t = (target == null || target.isBlank()) ? "驻地附近" : target;
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("type", "schedule");
            decision.put("action", action);
            decision.put("target", t);
            decision.put("reason", reason == null || reason.isBlank() ? "被打断任务恢复/调整" : reason);
            decision.put("urgency", 3);
            decision.put("duration", 30);
            decision.put("params", Map.of("move", Map.of("to", t)));
            ActorActionPlan plan = new ActorActionPlan();
            plan.setCharacterId(characterId);
            plan.setTriggerType(TRIGGER_TYPE_RESUME);
            plan.setActionJson(toJson(decision));
            plan.setStatus("planned");
            planRepository.save(plan);
            agentActionService.execute(plan);
            AiCallContext.recordStep(projectId, "agent_decision", "resume",
                    "打断恢复执行（plan=" + plan.getId() + " " + abbreviate(action, 60) + "）", 0);
        } catch (Exception e) {
            log.warn("[打断恢复] 恢复/调整任务执行失败（隔离继续）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    // ==================== 打断前 AI 探针 ====================

    /**
     * 打断前 AI 探针（实时行为决策系统 决策： / 常规刺激在记录打断与触发即时决策<b>之前</b>，
     * 由 AI 按角色性格/当前活动/刺激判断是否值得放下手头的事）。
     * <p>语义：返回 TRUE=打断；FALSE=不打断（角色继续手头事，
     * 不记录打断、不触发即时决策——真人感「不理会」）；null=AI 不可用/超预算/解析失败
     * 。
     * / （事件重要度≥4 / urgency≥2）由上层强制打断<b>不走本探针</b>。</p>
     * <p>成本：预算经 {@link BudgetGuard#tryConsume} 门控（与即时决策同一窗口，同流同预算）；
     * scene=interrupt_probe 审计；重试≤2；探针最大输出 128 token。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（统一 {@code npc:{id}}）
     *
     * @param gameSecond 当前游戏秒（保留参数，供后续扩展；可空）
     *
     * @param stimulusText 打断刺激文本
     *
     * @param personalitySegment 全量【角色个性】段
     *
     * @return TRUE 打断 / FALSE 不打断 / null 不可用（上层兜底默认打断）
     */
    public Boolean probeInterrupt(Long projectId, String npcRef, Long gameSecond, String stimulusText,
                                  String personalitySegment) {
        if (!props.isInterruptProbeEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return null; // 探针未启用 → 上层框架处理（默认打断）
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type）
        if (!npcTypeResolver.isSpecial(projectId, npcRef)) {
            return null; // 普通型不探针（走分层概率化，0 AI）
        }
        if (!budgetGuard.tryConsume(projectId)) {
            return null; // 预算门控（与即时决策同窗口）
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long characterId = NpcRefs.idOf(npcRef);
        if (characterId == null) {
            return null;
        }
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null || ch.getProjectId() == null) {
            return null;
        }
        String characterInfo = (personalitySegment != null && !personalitySegment.isBlank())
                ? personalitySegment : buildCharacterInfo(ch);
        String currentActivity = ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "（手头无事）" : ch.getCurrentActivity();
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                final int att = attempt;
                return AiCallContext.run(SCENE_PROBE, ch.getId(), att, () -> {
                    String prompt = promptService.buildInterruptProbePrompt(userId, projectId,
                            characterInfo, currentActivity, stimulusText);
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                            new AiChatRequest.ChatMessage("user", prompt)), 0.7, PROBE_MAX_TOKENS, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, ch.getId(), result.providerId(), result.model(),
                            SCENE_PROBE, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new BizException("AI 未返回有效 JSON");
                    }
                    InterruptProbeParse.InterruptProbe probe = InterruptProbeParse.parse(json);
                    return probe == null ? null : Boolean.valueOf(probe.interrupt());
                });
            } catch (Exception e) {
                log.warn("[打断恢复] 打断前探针生成第 {} 次失败: ref={} : {}", attempt, npcRef, e.getMessage());
            }
        }
        return null;
    }

    // ==================== AI 恢复决策（interrupt_resume） ====================

    /**
     * AI 恢复决策（预算门控：BudgetGuard.tryConsume，与即时决策同窗口）：
     * 渲染 interrupt_resume 模板 → json_object AI 调用（trace/用量 scene=interrupt_resume）→
     * {@link ResumeDecisionParse} 解析；失败/空决策/预算不足 → 返回 null（共性框架兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用
     *
     * @param rec 被打断任务记录（任务文本来源）
     *
     * @param stimulusText 打断刺激文本
     *
     * @param nowSecond 当前游戏秒
     *
     * @return 恢复决策（null=不可用）
     */
    private ResumeDecisionParse.ResumeDecision tryAiResumeDecision(Long projectId, String npcRef,
                                                                   ActorInterruptRecord rec, String stimulusText,
                                                                   Long nowSecond) {
        if (!budgetGuard.tryConsume(projectId)) {
            return null; // 预算门控（同即时决策窗口，成本护栏）
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long characterId = NpcRefs.idOf(npcRef);
        if (characterId == null) {
            return null;
        }
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null || ch.getProjectId() == null) {
            return null;
        }
        String characterInfo = buildCharacterInfo(ch);
        String interruptedTask = buildInterruptedTaskText(projectId, npcRef, rec, nowSecond);
        String currentState = buildCurrentState(ch, nowSecond);
        // ：恢复决策 ToM 推断深度段（按角色卡 OCEAN 宜人性×神经质；开关关/无卡=空串）
        String tomSection = tomInjectEnabled ? tomSectionOf(ch) : "";
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return AiCallContext.run(SCENE_RESUME, ch.getId(), attempt, () -> {
                    String prompt = promptService.buildInterruptResumePrompt(userId, projectId,
                            characterInfo, stimulusText, interruptedTask, currentState, tomSection);
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                            new AiChatRequest.ChatMessage("user", prompt)), 0.7, RESUME_MAX_TOKENS, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, ch.getId(), result.providerId(), result.model(),
                            SCENE_RESUME, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new BizException("AI 未返回有效 JSON");
                    }
                    return ResumeDecisionParse.parse(json);
                });
            } catch (Exception e) {
                log.warn("[打断恢复] 恢复决策生成第 {} 次失败: ref={} : {}", attempt, npcRef, e.getMessage());
            }
        }
        return null;
    }

    // ==================== 共性框架兜底 ====================

    /**
     * - 共性框架确定性兜底（AI 不可用/超预算/解析失败）：未过期（now &lt; expireAt）且
     * 原任务非空仍合理 → resume（回原地点继续原活动）；已过期/原任务为空 → abandon。
     * 对齐主开发方案 §4.3：共性优先级框架作确定性兜底，个性修正 起由 AI 决策承载。
     *
     * @param rec 被打断任务记录
     *
     * @param nowSecond 当前游戏秒
     *
     * @return 恢复决策（恒非空）
     */
    static ResumeDecisionParse.ResumeDecision fallbackResumeDecision(ActorInterruptRecord rec, Long nowSecond) {
        if (rec == null) {
            return new ResumeDecisionParse.ResumeDecision("abandon", null, null, "共性框架兜底：无打断记录");
        }
        if (expired(rec, nowSecond)) {
            return new ResumeDecisionParse.ResumeDecision("abandon", null, null,
                    "共性框架兜底：打断任务已超过恢复窗口，放弃恢复");
        }
        if (rec.getInterruptedActivity() == null || rec.getInterruptedActivity().isBlank()) {
            return new ResumeDecisionParse.ResumeDecision("abandon", null, null,
                    "共性框架兜底：原任务为空，放弃恢复");
        }
        return new ResumeDecisionParse.ResumeDecision("resume", rec.getInterruptedActivity(),
                rec.getInterruptedLocation(), "共性框架兜底：未过期且原任务仍合理，恢复原任务");
    }

    /**
     * 打断记录是否已过期（当前游戏秒 ≥ 过期时刻；记录/时刻缺失视为未过期）。
     *
     * @param rec 打断记录（可空）
     *
     * @param nowSecond 当前游戏秒（可空）
     *
     * @return true 表示已过期
     */
    static boolean expired(ActorInterruptRecord rec, Long nowSecond) {
        if (rec == null || rec.getExpireAtGameSecond() == null || nowSecond == null) {
            return false;
        }
        return nowSecond >= rec.getExpireAtGameSecond();
    }

    // ==================== 提示词上下文组装 ====================

    /**
     * 人物信息段（恢复决策「你的人物信息」）：<b>升级为全量【角色个性】段</b>
     * （PersonalityInjectionService：身份基线 + persona 性格/OCEAN/言语风格 + L3 核心记忆 +
     * brainProfile 行事倾向 + 关系摘要 + 时间感；兑现 决策记录第 7 条「全量注入留 深化」）。
     * 个性注入服务未装配/失败时回退 轻量组装（姓名/头衔 + 角色卡性格/价值观/行为模式 +
     * L3 renderCoreSegment）。零新表，复用既有素材源；失败降级不阻断。
     */
    private String buildCharacterInfo(ActorCharacter ch) {
        if (personalityInjectionService != null) {
            try {
                String seg = personalityInjectionService.buildPersonalitySegment(
                        ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), null, null);
                if (seg != null && !seg.isBlank()) {
                    return seg;
                }
            } catch (Exception ignored) {
                // 个性注入失败回退 轻量组装
            }
        }
        return buildCharacterInfoLight(ch);
    }

    /**
     * 轻量人物信息组装（个性注入服务未装配/失败时的兜底）：姓名/头衔 + 角色卡性格/价值观/
     * 行为模式 + L3 核心记忆（renderCoreSegment 轻量）。
     */
    private String buildCharacterInfoLight(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(ch.getName() == null ? "?" : ch.getName());
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append("（").append(ch.getTitle().trim()).append("）");
        }
        if (personaService != null) {
            try {
                String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
                if (persona != null && !persona.isBlank()) {
                    JsonNode p = objectMapper.readTree(persona);
                    List<String> bits = new ArrayList<>();
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "personality", "traits_summary");
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "values", "value_weights");
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "values", "life_goals");
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "habits", "micro_movements");
                    if (!bits.isEmpty()) {
                        sb.append("；性格/经历：").append(String.join("、", bits));
                    }
                }
            } catch (Exception ignored) {
                // 忽略角色卡解析失败
            }
        }
        if (coreMemoryService != null) {
            try {
                String core = coreMemoryService.renderCoreSegment(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()));
                if (core != null && !core.isBlank()) {
                    sb.append("；核心信念/价值观：").append(core.length() > 300 ? core.substring(0, 300) : core);
                }
            } catch (Exception ignored) {
                // 忽略 L3 注入失败
            }
        }
        return sb.toString();
    }

    /**
     * 被打断任务描述（恢复决策「被打断的任务」）：活动 + 地点 + 已中断游戏时长。
     * <p>追加「原计划 delta 校验」段——最近决策 delta 的时效性
     * （仍有效/已过时效 + 原计划行动/目标），AI 恢复决策据此判断原任务是否还有意义。</p>
     */
    private String buildInterruptedTaskText(Long projectId, String npcRef, ActorInterruptRecord rec, Long nowSecond) {
        StringBuilder sb = new StringBuilder();
        if (rec.getInterruptedActivity() != null && !rec.getInterruptedActivity().isBlank()) {
            sb.append(rec.getInterruptedActivity().trim());
        } else {
            sb.append("（未记录具体活动）");
        }
        if (rec.getInterruptedLocation() != null && !rec.getInterruptedLocation().isBlank()) {
            sb.append("，地点：").append(rec.getInterruptedLocation().trim());
        }
        if (rec.getInterruptedAtGameSecond() != null && nowSecond != null) {
            long minutes = Math.max(0, (nowSecond - rec.getInterruptedAtGameSecond()) / 60L);
            sb.append("，已中断约 ").append(minutes).append(" 游戏分钟");
        }
        // ：delta 校验上下文
        if (stateDeltaService != null) {
            try {
                String deltaCtx = stateDeltaService.resumeContext(projectId, npcRef, nowSecond);
                if (deltaCtx != null && !deltaCtx.isBlank()) {
                    sb.append("；").append(deltaCtx);
                }
            } catch (Exception e) {
                log.debug("[打断恢复] delta 校验上下文注入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 当前状态段（恢复决策「当前状态」）：优先 NpcStateService.stateSegment（需求/心情/情绪/urgency），
     * 失败回退角色 current_activity/location 基础描述。
     */
    private String buildCurrentState(ActorCharacter ch, Long gameSecond) {
        if (npcStateService != null) {
            try {
                String seg = npcStateService.stateSegment(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), gameSecond);
                if (seg != null && !seg.isBlank()) {
                    return seg;
                }
            } catch (Exception ignored) {
                // 失败回退基础描述
            }
        }
        return "当前在做：" + (ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "空闲" : ch.getCurrentActivity())
                + (ch.getLocation() == null || ch.getLocation().isBlank() ? "" : "；位置：" + ch.getLocation());
    }

    // ==================== 私有辅助 ====================

    /** 标记记录为 discarded（过期替换/防御路径）。 */
    private void discard(ActorInterruptRecord rec, Long nowSecond) {
        rec.setStatus("discarded");
        rec.setConsumedAtGameSecond(nowSecond);
        interruptRepository.save(rec);
        log.debug("[打断恢复] 过期 active 记录已丢弃: id={} ref={}", rec.getId(), rec.getNpcRef());
    }

    /** 过期窗口（游戏秒；配置 <=0 视为不设过期）。 */
    private int expireSeconds() {
        int minutes = props.getInterruptExpireGameMinutes();
        return minutes > 0 ? minutes * 60 : 0;
    }

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentEngineGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 恢复决策 ToM 推断深度段（按角色卡 OCEAN 宜人性×神经质；角色卡缺失/解析失败
     * 返回空串=不注入，零阻断）。
     */
    private String tomSectionOf(ActorCharacter ch) {
        if (personaService == null) {
            return "";
        }
        try {
            String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
            return com.holzyn.actor.domain.agent.runtime.TomInference.segmentOf(persona);
        } catch (Exception e) {
            return "";
        }
    }

    /** 数组字段到摘要片段。 */
    private void appendBits(List<String> bits, JsonNode node, int max) {
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String v = n.asText("");
                if (!v.isBlank() && bits.size() < max) {
                    bits.add(v);
                }
            });
        }
    }

    /** 决策 Map → JSON（失败兜底占位文本，不阻断计划创建）。 */
    private String toJson(Map<String, Object> decision) {
        try {
            return objectMapper.writeValueAsString(decision == null ? Map.of() : decision);
        } catch (Exception e) {
            return "{\"type\":\"schedule\",\"action\":\"继续日常活动\",\"target\":\"驻地附近\",\"reason\":\"序列化失败\",\"urgency\":3}";
        }
    }

    /** 字符串截断到指定长度。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
