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

package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.common.AfterCommit;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 学习组件-反思（阶段 D /R4，domain/agent/learning）——洞察 + 证据指针 + 目标状态 + staleness 复核。
 * <p>定位：从近期记忆生成<b>洞察</b>（洞察+证据指针
 * 写记忆流 kind=reflection）；对齐 Smallville 反思：重要度阈值触发（近期记忆重要度和≥阈值）。</p>
 * <ul>
 * <li><b>反思节奏上限（R4）</b>：每角色每游戏日 ≤2 次（防自强化叙事失控）——计数落
 * actor_agent_profile（last_reflect_game_day + reflect_count_today，跨日自动重置）；</li>
 * <li><b>洞察 + 证据指针</b>：AI 生成 insights[]（{content, importance}），证据=本次输入 top-N 记忆
 * id 列表写 evidence_ids 列；</li>
 * <li><b>目标状态更新</b>：反思 Prompt 附该角色 active 目标与近期证据，AI 判断 goal_updates[]
 * （{goal_id,status,reason}）——达成/放弃流转（与手动 CRUD 双通道）；</li>
 * <li><b>R3 staleness 复核</b>：反思输入优先纳入被标记「可能过时」的高重要事实，
 * 反思完成后刷新这些记忆的 last_access_at（视为已复核、清除标记）。</li>
 * </ul>
 * <p>触发：WorldEngine 每跨 K 游戏小时调用 {@code LearningService.scheduleConsolidation} →
 * 本服务对项目内特殊 NPC（reflection_enabled 且非 programmatic）逐一执行反思；run_mode=minimal
 * 时跳过（系统自动能力降级）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionService {

    /** 反思节奏上限：每角色每游戏日 ≤2 次（R4，防自强化叙事） */
    @Value("${holzyn.actor.learning.reflection.max-per-game-day:2}")
    private int maxPerGameDay;

    /** 反思触发阈值：近期记忆重要度之和 ≥ 该值才触发（对齐 Smallville） */
    @Value("${holzyn.actor.learning.reflection.importance-threshold:10}")
    private int importanceThreshold;

    /** 反思输入最近记忆条数（top-N 证据） */
    @Value("${holzyn.actor.learning.reflection.recent-memories:10}")
    private int recentMemories;

    /**
     * 反思周期预算：
     * 0（默认）=自动浮动——特殊 NPC 数 × max-per-game-day（预算=本周期理论上限，正常永不饿死，
     * 世界人数多时自动放大）；&gt;0 = 固定覆盖（运维逃生门）。
     * env 逃生门 = HOLOZYN_ACTOR_LEARNING_REFLECTION_BUDGET_PER_CYCLE（松弛绑定删连字符）。
     */
    @Value("${holzyn.actor.learning.reflection.budget-per-cycle:0}")
    private int budgetPerCycle;

    /** AI 生成最大输出 token */
    private static final int AI_MAX_TOKENS = 1024;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    private final ActorMemoryRepository memoryRepository;
    private final ActorCharacterGoalRepository goalRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorWorldEngineRepository engineRepository;
    private final ActorProjectRepository projectRepository;
    private final BrainProfileService brainProfileService;
    private final WorldClockService worldClockService;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final AgentMemoryService agentMemoryService;
    private final PlanningService planningService;
    private final BudgetGuard budgetGuard;

    /**
     * L3 核心记忆服务（反思挂钩：反思完成后触发核心块自编辑评估——双挂钩之二；
     * 字段注入保持构造器不变，存量单测零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.CoreMemoryService coreMemoryService;

    /**
     * 归档服务（反思降级挂钩：反思成功后低重要度证据记忆降级归档 reason=downgraded；
     * 字段注入保持构造器不变，存量单测零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryArchiveService memoryArchiveService;

    /** 沙盘状态服务（S2 npc:thought T1 反思源：洞察写入想法气泡；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * NPC 类型解析器（注入：反思属「只有特殊型才做」——npc:{id} 不再编码类型，
     * 是否特殊型必须查 actor_profile.type；字段注入保持构造器不变，纯单测直构时为 null
     * → 按特殊型处理[与 NpcTypeResolver 解析失败回退 special 同口径]）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    /**
     * AI 调用 trace 仓库（自省漂移闭环反馈：反思输入注入本角色最近「自省漂移」摘要——
     * 反思结论 vs 实际决策的一致性校验结果，让角色下次反思「看到自己知道但没做到」；
     * 字段注入保持构造器不变，null=未装配[单测]摘要为空段）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository aiCallLogRepository;

    /** 自省漂移摘要最多注入条数（反思输入段；防模板膨胀）。 */
    private static final int SELF_DRIFT_SUMMARY_MAX = 3;

    /**
     * 主动记忆检索（ProactAgent「Ask Only When Needed」，P1 ②-1）：反思入口自问环节——
     * AI 按当前反思情境判定是否需要主动想起/查证，命中【主动想起】段注入反思模板
     * {{proactive_memory}} 占位符（反思证据纳入主动想起的过往/见闻）；fail-open 零阻断；
     * 字段注入保持构造器不变，null=未装配[单测]跳过自问）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService proactiveRetrievalService;

    /**
     * 能力自评卡（P1 ①-2）：反思联动微调——反思成功后由 AI 按近期经历微调角色对自己
     * 各领域能力的主观评估（self_efficacy 1-5），写回 persona；AfterCommit 在写阶段全部
     * 提交后执行（对齐 L3 自编辑先例）；fail-open 零阻断；字段注入保持构造器不变，
     * null=未装配[单测]跳过微调）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.agent.learning.SelfEfficacyService selfEfficacyService;

    /**
     * 统一 AI 上下文注入器（批 4 记忆与反思链·段 2F 对齐）：反思输入前置段 1A 世界观 +
     * 段 1C 角色核心卡 + 段 2D 当前世界动态 + 段 2E 当前状态——AI 反思时知道「世界是什么、
     * 我是谁、此刻什么时节天气、我状态如何」，洞察不脱离角色与世界；
     * 字段注入保持构造器不变，null=未装配[单测]跳过前缀（既有行为零改动）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 记忆-知识桥接层（P1 ②-3）：反思写阶段全部提交后，把本次反思洞察交给独立 AI 判定
     * 抽取「可复用事实型知识候选」（scene=knowledge_bridge）→ 全自动确认 → 写入角色级
     * 「经历沉淀」知识文档（防知识卡僵化）；独立 AI 调用，同 AfterCommit 时序（绝不在
     * 任何锁存活期间执行）；fail-open 零阻断；字段注入保持构造器不变，null=未装配[单测]
     * 跳过桥接）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService knowledgeBridgeService;

    /**
     * 反思单角色（R4 节奏上限 + 重要度阈值 + AI 洞察 + 证据指针 + 目标状态 + staleness 复核）。
     * <p><b>记忆写并发治理</b>：本方法<b>不再带 {@code @Transactional}</b>——
     * 反思是「AI（秒级）→ 记忆写」的长链路，旧实现把 AI1（reflection 约 48s）+ 写 actor_memory
     * （INSERT/UPDATE） + <b>AI2（L3 自编辑，约 82s）</b>全部包在一个事务里：AI2 期间
     * actor_memory 行锁被持有数十秒 → 并发写入方（其它反思/日计划/蒸馏）撞
     * {@code LOCK_TIMEOUT=10000} 连锁超时（实测 79 次锁超时 + 26 次反思失败，被放弃的 AI 结果
     * 已付费）。拆法（对齐 架构不变量「含 AI 方法一律无事务」）：
     * <ol>
     * <li>AI1 在事务外执行（重试只重试 AI1，<b>绝不重付已成功的 AI</b>）；</li>
     * <li>写阶段逐个委托<b>下游短事务方法</b>（{@code AgentMemoryService.recordReflection/recheckMemories}、
     * 各自毫秒级提交，不跨 AI 存活；</li>
     * <li><b>AI2（L3 自编辑）用 {@code AfterCommit} 包裹</b>：无外层事务时立即执行（即写阶段全部
     * 提交之后），有外层事务时延迟到其提交后——任何情况下都不再持有 actor_memory 行锁。</li>
     * </ol>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{actor_profile.id}}）
     *
     * @param gameHour 当前游戏小时（游戏日编号基准；可空=世界时钟）
     *
     * @return 是否执行了反思（false=未到节奏/阈值/非特殊/预算不可用/AI 失败）
     */
    public boolean reflect(Long projectId, String characterRef, Long gameHour) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——反思属「只有特殊型才做」的行为
        // （普通型走人群程序化，无 AI 反思），是否特殊型改用 NpcTypeResolver 查 actor_profile.type
        if (projectId == null || characterRef == null
                || (npcTypeResolver != null && !npcTypeResolver.isSpecial(projectId, characterRef))) {
            return false;
        }
        // run_mode=minimal：系统自动能力降级（反思关闭）
        String runMode = runModeOf(projectId);
        if (RunModes.isMinimal(runMode)) {
            return false;
        }
        Long gh = gameHour != null ? gameHour : worldClockService.currentGameHour(projectId);
        if (gh == null) {
            return false;
        }
        long gameDay = Math.floorDiv(gh, 24);

        // 脑力配置：reflection_enabled 且非 programmatic 才反思
        ActorAgentProfile profile = brainProfileService.resolveByRef(characterRef);
        if (profile == null || Integer.valueOf(0).equals(profile.getReflectionEnabled())
                || "programmatic".equals(profile.getBrainLevel())) {
            return false;
        }

        // 节奏上限：本游戏日已反思次数 < 上限
        if (!canReflect(profile, gameDay, maxPerGameDay)) {
            return false;
        }

        // 检索近期记忆（fact/observation/reflection 之外的经历 + 被标记的 staleness 事实优先）
        List<ActorMemory> recent = recentMemories(projectId, characterRef, gh);
        if (recent.isEmpty()) {
            return false;
        }
        int importanceSum = recent.stream()
                .mapToInt(m -> m.getImportance() == null ? 1 : m.getImportance())
                .sum();
        if (importanceSum < importanceThreshold) {
            log.debug("[反思] 重要度不足（{}<{}），跳过: ref={}", importanceSum, importanceThreshold, characterRef);
            return false;
        }

        // 预算：消耗门控（无窗口自开，对齐 AgentRuntime ownsTick 模式；用尽则跳过）
        if (!tryConsumeBudget(projectId)) {
            log.debug("[反思] 反思预算不可用（无窗口/用尽），跳过: ref={}", characterRef);
            return false;
        }

        boolean ok = doReflect(projectId, characterRef, gh, gameDay, recent, profile);
        if (ok) {
            // 节奏计数：跨游戏日重置
            if (profile.getLastReflectGameDay() == null || profile.getLastReflectGameDay() != gameDay) {
                profile.setLastReflectGameDay(gameDay);
                profile.setReflectCountToday(1);
            } else {
                profile.setReflectCountToday((profile.getReflectCountToday() == null ? 0 : profile.getReflectCountToday()) + 1);
            }
            brainProfileService.save(profile);
        }
        return ok;
    }

    /**
     * 反思核心：AI 生成洞察 + 目标状态更新 + staleness 复核刷新。
     * <p><b>事务边界</b>：本方法全程无事务——AI1 重试循环只包含 AI 调用
     * （+用量记账），成功后才进入写阶段（下游短事务方法逐个提交，毫秒级、不跨 AI 存活）；
     * AI2（L3 自编辑）经 {@code AfterCommit} 延迟到写阶段全部提交后执行。写阶段任何一步失败
     * 只记日志并终止（<b>不重付已成功的 AI1</b>，消除旧实现的「锁超时→整链重试→再付 AI 费」）。
     */
    private boolean doReflect(Long projectId, String characterRef, Long gameHour, long gameDay,
                              List<ActorMemory> recent, ActorAgentProfile profile) {
        Long userId = projectOwner(projectId);
        if (userId == null) {
            return false;
        }
        String characterInfo = planningService.characterInfoText(characterRef);
        String goals = planningService.goalsSummary(projectId, characterRef);
        // 优先复核被标记的记忆（R3）：证据 id 列表先取 stale，再补其他
        List<Long> evidenceIds = new ArrayList<>();
        List<String> evidenceText = new ArrayList<>();
        for (ActorMemory m : recent) {
            if (evidenceIds.size() >= recentMemories) {
                break;
            }
            if (m.isStale()) {
                evidenceIds.add(m.getId());
                evidenceText.add(memoryLine(m));
            }
        }
        for (ActorMemory m : recent) {
            if (evidenceIds.size() >= recentMemories) {
                break;
            }
            if (!evidenceIds.contains(m.getId())) {
                evidenceIds.add(m.getId());
                evidenceText.add(memoryLine(m));
            }
        }
        if (evidenceText.isEmpty()) {
            return false;
        }

        Map<String, Object> placeholders = new java.util.LinkedHashMap<>();
        placeholders.put("character_info", characterInfo);
        placeholders.put("recent_memories", String.join("\n", evidenceText));
        placeholders.put("goals", goals.isBlank() ? "（暂无 active 目标）" : goals);
        placeholders.put("game_time", WorldClockService.formatGameTime(gameHour));
        // 自省漂移闭环反馈（候选 A）：注入本角色最近「自省漂移」trace 摘要——反思输入
        // 看到「上次反思后决策是否言行一致」，让角色下次反思时处理自己的漂移（KAPRO
        // 认知-行为鸿沟闭环）；无漂移记录/仓库未装配 → 空占位（模板零噪音）
        placeholders.put("self_drift_summary", selfDriftSummary(projectId, characterRef));
        // 主动记忆检索（P1 ②-1）：反思入口自问环节——AI 按当前反思情境判定是否需要主动
        // 想起/查证，命中【主动想起】段注入 {{proactive_memory}} 占位符（反思证据纳入
        // 主动想起的过往/见闻，让反思不局限于「最近 N 条」窗口）；未命中/降级 → 空占位
        placeholders.put("proactive_memory", proactiveMemoryForReflection(projectId, characterRef, userId,
                characterInfo, String.join("\n", evidenceText), goals, gameHour));
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_REFLECTION, placeholders);
        // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：反思输入前置段 1A 世界观 + 段 1C 角色核心卡
        // + 段 2D 当前世界动态 + 段 2E 当前状态——AI 从近期记忆提炼洞察时知道「世界是什么、我是谁、
        // 此刻什么时节天气、我状态如何」（洞察不脱离角色与世界；未装配/组装失败零阻断）
        final String finalPrompt;
        if (aiContextInjector != null) {
            String injected = aiContextInjector.memoryChainContext(projectId, characterRef, gameHour * 3600L);
            finalPrompt = (injected != null && !injected.isBlank()) ? injected + "\n\n" + prompt : prompt;
        } else {
            finalPrompt = prompt;
        }
        String sys = promptTemplateService.systemMessage(userId, projectId, PromptTemplateService.CODE_REFLECTION, null);

        // ============ 阶段 1：AI1（事务外；重试只重试 AI，不重付已成功结果） ============
        AiChatResult result = null;
        Exception lastError = null;
        ReflectionResult rr = null;
        boolean aiOk = false;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
                result = AiCallContext.run("reflection", NpcRefs.idOf(characterRef), () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, chatRequest(finalPrompt, sys)));
                usageLogService.record(userId, projectId, NpcRefs.idOf(characterRef), result.providerId(), result.model(),
                        "reflection", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
                rr = parseReflection(result.content());
                // 空输出（AI 返回垃圾/无洞察无目标更新）= 本次反思无效 → 走重试（不写空洞察、不占节奏位）
                if (rr.insights().isEmpty() && rr.goalUpdates().isEmpty()) {
                    throw new java.lang.IllegalStateException("反思输出为空（无洞察/无目标更新）");
                }
                aiOk = true;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("[反思] AI 生成第 {} 次失败: ref={} : {}", attempt, characterRef, e.getMessage());
            }
        }
        if (!aiOk) {
            log.warn("[反思] AI 生成失败（{}），跳过本轮反思: ref={}",
                    lastError == null ? "未知错误" : lastError.getMessage(), characterRef);
            return false;
        }

        // ============ 阶段 2：写阶段 ============
        try {
            // 写入洞察记忆（evidence_ids 指针；recordReflection 自带短事务 = INSERT + 预算淘汰一次提交）
            for (Insight ins : rr.insights()) {
                agentMemoryService.recordReflection(projectId, characterRef, ins.content(), ins.importance(), evidenceIds);
            }
            // S2 沙盘 npc:thought（T1 反思源）：把首条洞察作为内心想法写入并广播（去重；失败静默不阻断反思）
            if (!rr.insights().isEmpty() && sandboxStateService != null) {
                try {
                    sandboxStateService.publishThought(projectId, characterRef,
                            "我回想起了：" + rr.insights().get(0).content(), "reflection", gameHour * 3600L);
                } catch (Exception e) {
                    log.debug("[反思] 沙盘想法写入失败（静默）: ref={} : {}", characterRef, e.getMessage());
                }
            }
            // 目标状态流转（反思 AI 判断 + 手动双通道；goal 仓库 save 各自短事务）
            applyGoalUpdates(projectId, characterRef, rr.goalUpdates());
            // R3 staleness 复核：刷新本次证据记忆的 last_access_at（清除「可能过时」标记；自带短事务）
            agentMemoryService.recheckMemories(projectId, evidenceIds);
            // 反思降级（设计 §8.1 downgraded）：证据已内化进洞察后，低重要度证据降级归档
            // （原文归档保真，不参与日常注入；高重要证据仍留 L2）——失败静默不阻断反思结果
            if (memoryArchiveService != null) {
                try {
                    memoryArchiveService.downgradeEvidence(projectId, characterRef, evidenceIds);
                } catch (Exception e) {
                    log.warn("[反思] 证据降级归档失败（静默跳过）: ref={} : {}", characterRef, e.getMessage());
                }
            }
        } catch (Exception e) {
            // 写阶段失败：只终止，不重付已成功的 AI1
            log.warn("[反思] 写阶段失败（跳过本轮，不重付 AI）: ref={} : {}", characterRef, e.getMessage());
            return false;
        }

        // ============ 阶段 3：AI2 ============
        // L3 反思挂钩（双挂钩之二）：反思完成后触发核心块自编辑评估——把当前核心块 +
        // 反思证据/洞察交给 AI 判断身份级信息是否需要更新（反思节奏已受上限约束，成本有界）；
        // 失败静默，不阻断反思结果。⚠️ 此段是独立的 AI 调用，绝不可以在写阶段任何锁存活期间执行。
        // 能力自评卡（P1 ①-2）挂钩（双挂钩之三）：反思完成后按近期经历微调角色自评——
        // 同 AfterCommit 时序（写阶段全部提交后执行，绝不在锁存活期间做 AI 调用）。
        // 记忆-知识桥接层（P1 ②-3）挂钩（双挂钩之四）：反思洞察 → AI 判定抽取「可复用事实型
        // 知识候选」→ 全自动确认 → 写入角色级「经历沉淀」知识文档（防知识卡僵化；独立 AI
        // 调用，同 AfterCommit 时序）。
        final String reflectionContext = buildReflectionContext(evidenceText, rr.insights());
        final List<String> insightContents = rr.insights().stream()
                .map(Insight::content).filter(java.util.Objects::nonNull).toList();
        final Long uid = userId;
        final Long pid = projectId;
        final String ref = characterRef;
        AfterCommit.runAfterCommit("反思 L3 自编辑", () -> {
            if (coreMemoryService != null) {
                coreMemoryService.maybeSelfEditFromReflection(uid, pid, ref, reflectionContext);
            }
            if (selfEfficacyService != null) {
                selfEfficacyService.maybeAdjustFromReflection(pid, ref, reflectionContext);
            }
            if (knowledgeBridgeService != null && !insightContents.isEmpty()) {
                try {
                    knowledgeBridgeService.runForReflection(pid, ref, uid, characterInfo, insightContents);
                } catch (Exception e) {
                    log.warn("[反思] 知识桥接失败（静默跳过）: ref={} : {}", ref, e.getMessage());
                }
            }
        });
        log.info("[反思] 完成：project={} ref={} 游戏日={} 洞察={} 目标更新={} 证据={}",
                projectId, characterRef, gameDay, rr.insights().size(), rr.goalUpdates().size(), evidenceIds.size());
        return true;
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 反思节奏判定（R4，静态可测）：同一游戏日已反思次数是否 < 上限。
     *
     * @param profile 脑力配置（含 last_reflect_game_day / reflect_count_today）
     *
     * @param currentGameDay 当前游戏日编号（floorDiv(gameHour,24)）
     *
     * @param maxPerDay 每游戏日上限
     *
     * @return true=允许反思
     */
    static boolean canReflect(ActorAgentProfile profile, long currentGameDay, int maxPerDay) {
        if (profile == null || maxPerDay <= 0) {
            return false;
        }
        if (profile.getLastReflectGameDay() == null || profile.getLastReflectGameDay() != currentGameDay) {
            return true; // 跨游戏日：重置
        }
        int count = profile.getReflectCountToday() == null ? 0 : profile.getReflectCountToday();
        return count < maxPerDay;
    }

    /**
     * 解析反思 AI 输出 JSON（静态可测）：
     * 失败/非法返回空洞察列表。
     */
    static ReflectionResult parseReflection(String content) {
        if (content == null || content.isBlank()) {
            return new ReflectionResult(List.of(), List.of());
        }
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return new ReflectionResult(List.of(), List.of());
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.isObject()) {
                return new ReflectionResult(List.of(), List.of());
            }
            List<Insight> insights = new ArrayList<>();
            JsonNode arr = node.path("insights");
            if (arr.isArray()) {
                for (JsonNode it : arr) {
                    String c = it.path("content").asText("");
                    if (!c.isBlank()) {
                        int imp = importanceOf(it.path("importance"));
                        insights.add(new Insight(c.trim(), Math.max(1, Math.min(5, imp))));
                    }
                }
            }
            List<GoalDraft> updates = new ArrayList<>();
            JsonNode up = node.path("goal_updates");
            if (up.isArray()) {
                for (JsonNode it : up) {
                    if (it.path("goal_id").isIntegralNumber()) {
                        long id = it.path("goal_id").asLong();
                        String status = it.path("status").asText("").trim();
                        if (id > 0 && List.of("active", "done", "abandoned").contains(status)) {
                            updates.add(new GoalDraft(id, status, it.path("reason").asText(null)));
                        }
                    }
                }
            }
            return new ReflectionResult(insights, updates);
        } catch (Exception e) {
            return new ReflectionResult(List.of(), List.of());
        }
    }

    /** importance 文本解析（"3" → 3；失败回退 3） */
    private static int parseImportance(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 3;
        }
    }

    /** importance 节点解析：数字/文本/缺省均安全（Jackson 3 无 isText，用 isNumber + isValueNode 判定） */
    private static int importanceOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 3;
        }
        if (node.isNumber()) {
            return node.asInt(3);
        }
        return parseImportance(node.asText("3"));
    }

    // ==================== 私有辅助 ====================

    /**
     * 检索反思输入证据：该角色近期 fact/observation/reflection 记忆（重要度 desc + 较新优先，top-N），
     * 并就地标记 staleness（反射优先复核被标记事实）。无记忆返回空列表。
     */
    private List<ActorMemory> recentMemories(Long projectId, String characterRef, Long gameHour) {
        List<ActorMemory> all = memoryRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(projectId, characterRef, 0);
        if (all.isEmpty()) {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            Long charId = NpcRefs.idOf(characterRef);
            if (charId != null) {
                all = memoryRepository
                        .findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(projectId, charId, 0);
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        // staleness 标记（依赖 AgentMemoryService 内部逻辑不可达 → 用世界时钟换算简化判定：
        // 高重要 + 距上次访问超 N 游戏日）
        Long nowGameHour = worldClockService.gameHourAt(projectId, java.time.LocalDateTime.now());
        for (ActorMemory m : all) {
            boolean stale = false;
            if (m.getImportance() != null && m.getImportance() >= 3 && nowGameHour != null) {
                java.time.LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
                if (base != null) {
                    Long baseGameHour = worldClockService.gameHourAt(projectId, base);
                    if (baseGameHour != null
                            && AgentMemoryService.staleByGameDays(
                            Math.floorDiv(baseGameHour, 24), Math.floorDiv(nowGameHour, 24), 3)) {
                        stale = true;
                    }
                }
            }
            m.setStale(stale);
        }
        // 重要度 desc + 较新优先
        all.sort(java.util.Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance()).reversed()
                .thenComparing(m -> m.getCreatedAt(), java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .reversed());
        return all.size() <= recentMemories ? all : new ArrayList<>(all.subList(0, recentMemories));
    }

    /** 记忆单行文本（反思输入） */
    private String memoryLine(ActorMemory m) {
        return "- [重要度" + (m.getImportance() == null ? 1 : m.getImportance()) + "] "
                + (m.isStale() ? "（可能过时）" : "") + m.getContent();
    }

    /**
     * 主动记忆检索反思段（P1 ②-1）：委托 {@link ProactiveRetrievalService#maybeRetrieve}——
     * AI 按当前反思情境（角色档案 + 近期证据 + 目标 + 游戏时刻，截断收敛）判定是否需要
     * 主动想起/查证，命中返回【主动想起】段；未装配/判定失败/冷却中/无命中 → 空串
     * （模板零噪音，fail-open 零阻断）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param userId 归属用户 ID（可空）
     *
     * @param characterInfo 角色档案文本（判定情境组成部分）
     *
     * @param evidenceText 近期证据文本（判定情境组成部分）
     *
     * @param goals 目标摘要文本（判定情境组成部分）
     *
     * @param gameHour 当前游戏小时（判定情境组成部分）
     *
     * @return 【主动想起】段文本（空串=未命中/降级）
     */
    private String proactiveMemoryForReflection(Long projectId, String characterRef, Long userId,
                                                String characterInfo, String evidenceText, String goals,
                                                Long gameHour) {
        if (proactiveRetrievalService == null || projectId == null || characterRef == null) {
            return "";
        }
        try {
            // 判定情境 = 角色档案 + 证据 + 目标 + 时刻（截断收敛，防输入膨胀）
            StringBuilder probe = new StringBuilder();
            if (characterInfo != null && !characterInfo.isBlank()) {
                probe.append(characterInfo);
            }
            if (evidenceText != null && !evidenceText.isBlank()) {
                probe.append("\n近期经历：").append(evidenceText);
            }
            if (goals != null && !goals.isBlank()) {
                probe.append("\n目标：").append(goals);
            }
            if (gameHour != null) {
                probe.append("\n当前时刻：").append(WorldClockService.formatGameTime(gameHour));
            }
            if (probe.length() > 1200) {
                probe.setLength(1200);
            }
            var result = proactiveRetrievalService.maybeRetrieve(projectId, characterRef, userId, probe.toString());
            return result == null || result.segment() == null ? "" : result.segment();
        } catch (Exception e) {
            log.debug("[反思] 主动记忆检索失败（空段）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 自省漂移闭环摘要（候选 A）：查本角色最近「自省漂移」trace（stepType=self_check
     * 且 content 含「自省漂移」+ ref=npc:{id} 前缀），取最近 SELF_DRIFT_SUMMARY_MAX 条
     * 拼成反思输入段。无记录/仓库未装配 → 空串（模板零噪音）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（npc:{id}；content 前缀检索用）
     *
     * @return 自省漂移摘要文本（空串=无记录）
     */
    private String selfDriftSummary(Long projectId, String characterRef) {
        if (aiCallLogRepository == null || projectId == null || characterRef == null) {
            return "";
        }
        try {
            java.util.List<com.holzyn.actor.domain.debug.entity.ActorAiCallLog> logs =
                    aiCallLogRepository.findTop10ByProjectIdAndStepTypeOrderByIdDesc(
                            projectId, com.holzyn.actor.domain.agent.brain.strategy.SelfCheckStrategy.SCENE_SELF_CHECK);
            if (logs.isEmpty()) {
                return "";
            }
            String prefix = "ref=" + characterRef;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (com.holzyn.actor.domain.debug.entity.ActorAiCallLog log : logs) {
                if (n >= SELF_DRIFT_SUMMARY_MAX) {
                    break;
                }
                String content = log.getContent();
                if (content == null || !content.startsWith(prefix) || !content.contains("自省漂移")) {
                    continue;
                }
                sb.append("- ").append(content.substring(prefix.length()).trim()).append('\n');
                n++;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("[反思] 自省漂移摘要读取失败（空段）: {}", e.getMessage());
            return "";
        }
    }

    /** 应用目标状态更新（反思 AI 判断：done/abandoned） */
    private void applyGoalUpdates(Long projectId, String characterRef, List<GoalDraft> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (GoalDraft u : updates) {
            ActorCharacterGoal g = goalRepository.findById(u.goalId()).orElse(null);
            if (g == null || !projectId.equals(g.getProjectId()) || !characterRef.equals(g.getAgentRef())) {
                continue;
            }
            if (!"active".equals(g.getStatus())) {
                continue;
            }
            g.setStatus(u.status());
            if ("active".equals(u.status())) {
                g.setFinishedAt(null);
            }
            goalRepository.save(g);
        }
    }

    /** 组装 AI 请求（system 提示词存在则前置） */
    private AiChatRequest chatRequest(String prompt, String sys) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        if (sys != null && !sys.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", sys));
        }
        messages.add(new AiChatRequest.ChatMessage("user", prompt));
        return new AiChatRequest(null, messages, 0.3, AI_MAX_TOKENS, true);
    }

    /** 项目归属用户（AI 调用凭据） */
    private Long projectOwner(Long projectId) {
        return projectRepository.findById(projectId)
                .map(com.holzyn.actor.domain.project.entity.ActorProject::getUserId)
                .orElse(null);
    }

    /** 项目运行模式（无引擎行回退 standard） */
    private String runModeOf(Long projectId) {
        ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElse(null);
        return engine == null ? RunModes.STANDARD : RunModes.normalize(engine.getRunMode());
    }

    /**
     * 开启一个反思周期预算窗口（LearningServiceSkeleton.scheduleConsolidation 周期循环前调用，
     * 循环结束后由 {@link #closeCycleBudget} 关闭）。预算=浮动（见 {@link #budgetPerCycle}）。
     *
     * @param projectId 项目 ID
     */
    public void openCycleBudget(Long projectId) {
        if (projectId == null) {
            return;
        }
        budgetGuard.beginReflection(projectId, resolveCycleBudget(projectId));
    }

    /**
     * 关闭反思周期预算窗口（scheduleConsolidation 周期循环 finally 调用）。
     *
     * @param projectId 项目 ID
     */
    public void closeCycleBudget(Long projectId) {
        budgetGuard.endReflection(projectId);
    }

    /**
     * 解析反思周期预算（浮动算法）：
     * 配置 {@code budget-per-cycle} &gt; 0 → 固定覆盖；否则自动 = 项目特殊 NPC 数 × max-per-game-day
     * （下限 1——预算=理论上限：每角色每游戏日最多反思 maxPerGameDay 次，正常节奏下永不因预算饿死）。
     *
     * @param projectId 项目 ID
     *
     * @return 本次反思周期预算（≥1）
     */
    private int resolveCycleBudget(Long projectId) {
        if (budgetPerCycle > 0) {
            return budgetPerCycle;
        }
        return Math.max(1, specialNpcCount(projectId) * maxPerGameDay);
    }

    /**
     * 项目内特殊 NPC 数量（反思只对特殊型执行；普通型走人群程序化无反思）。
     * 仓库未装配（单测直构）返回 0。
     *
     * @param projectId 项目 ID
     *
     * @return 特殊 NPC 数（≥0）
     */
    private int specialNpcCount(Long projectId) {
        if (projectId == null || characterRepository == null) {
            return 0;
        }
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars == null || chars.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (ActorCharacter ch : chars) {
            if (ch != null && !NpcRefs.isCommonType(ch.getType())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 预算消耗：反思改走<b>独立预算命名空间</b>——
     * 优先消费既有反思窗口（WorldEngine 每游戏日巩固周期经 {@link #openCycleBudget} 开窗）；
     * 无窗口（手动/单角色路径）自开自关一个窗口（自动浮动预算，对齐旧 SELF_BUDGET 自开模式语义）。
     * <b>任何情况下都不再消费引擎 tick 共享预算</b>——那是 24 角色决策链的预算，反思与其竞争
     * 必被饿死。
     *
     * @param projectId 项目 ID
     *
     * @return true=预算充足并已消耗
     */
    private boolean tryConsumeBudget(Long projectId) {
        if (projectId == null) {
            return false;
        }
        boolean owns = !budgetGuard.hasReflectionWindow(projectId);
        if (owns) {
            budgetGuard.beginReflection(projectId, resolveCycleBudget(projectId));
        }
        boolean ok;
        try {
            ok = budgetGuard.tryConsumeReflection(projectId);
        } finally {
            if (owns) {
                budgetGuard.endReflection(projectId);
            }
        }
        return ok;
    }

    /** 反思输出解析结果 */
    record ReflectionResult(List<Insight> insights, List<GoalDraft> goalUpdates) {
    }

    /**
     * 组装反思上下文文本（L3 自编辑反思挂钩输入）：证据记忆 + 本次反思洞察摘要。
     */
    private static String buildReflectionContext(List<String> evidenceText, List<Insight> insights) {
        StringBuilder sb = new StringBuilder();
        if (evidenceText != null && !evidenceText.isEmpty()) {
            sb.append("本次反思依据的记忆：\n");
            int n = 0;
            for (String t : evidenceText) {
                if (n >= 10) {
                    break;
                }
                sb.append("- ").append(t).append("\n");
                n++;
            }
        }
        if (insights != null && !insights.isEmpty()) {
            sb.append("本次反思产出的洞察：\n");
            for (Insight in : insights) {
                sb.append("- [重要度").append(in.importance()).append("] ").append(in.content()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 洞察 */
    record Insight(String content, Integer importance) {
    }

    /** 目标状态更新 */
    record GoalDraft(long goalId, String status, String reason) {
    }
}
