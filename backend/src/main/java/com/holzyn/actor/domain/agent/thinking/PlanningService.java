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

package com.holzyn.actor.domain.agent.thinking;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 思考规划组件（阶段 D，domain/agent/thinking）——目标/日计划/重排。
 * <p>定位：把 Perception+Memory 输入转化为
 * <b>有方向、可连贯</b>的行动——单步决策（{@link DecisionService}）之上叠加：</p>
 * <ul>
 * <li><b>目标</b>（{@code actor_character_goal}）：AI 全自动生成（跨日/新建，预算内失败静默）
 * + 手动 CRUD；状态流转 = 反思 AI 判断 + 手动双通道；</li>
 * <li><b>日计划/行动序列</b>（对齐 Smallville 层级规划）：记忆流 kind=plan（单条 JSON：
 * date_game_day + items[{hour,action,target,location,state}]），WorldEngine 跨日自动生成 +
 * 决策时惰性补生成（双保险）；</li>
 * <li><b>计划中断重排</b>：世界事件打断时 AI 重排当前时段后的条目（plan_replan 模板）；</li>
 * <li><b>感知注入</b>：{@link #goalsSummary}/{@link #planSummary} 供 PerceptionService 组装
 * 【当前目标】+【今日计划】段（行动决策/对话都带方向性）。</li>
 * </ul>
 * <p>预算/模式：AI 调用一律经 {@link BudgetGuard#tryConsume} 门控（无窗口/用尽回退机制化）；
 * run_mode=minimal 时系统自动能力降为机制化（目标不自动生成/计划机制生成），standard 正常 AI，
 * ultimate 全量 AI。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanningService {

    /** AI 生成最大输出 token */
    private static final int AI_MAX_TOKENS = 1024;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 感知注入的 active 目标上限 */
    private static final int GOALS_SUMMARY_MAX = 2;

    /** 无外层预算窗口时自开的预算值（手动「AI 生成目标/日计划」路径，对齐 AgentRuntime ownsTick 模式） */
    private static final int SELF_BUDGET = 5;

    /** 日计划条目数（对齐 Smallville 5~8 条） */
    private static final int PLAN_ITEMS_MIN = 5;
    private static final int PLAN_ITEMS_MAX = 8;

    private final ActorCharacterGoalRepository goalRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorCharacterRepository characterRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    private final ActorCharacterRepository npcRepository;
    private final ActorWorldEngineRepository engineRepository;
    private final ActorProjectRepository projectRepository;
    private final BrainProfileService brainProfileService;
    private final WorldClockService worldClockService;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final AgentMemoryService agentMemoryService;
    private final BudgetGuard budgetGuard;
    private final ObjectMapper objectMapper;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/crowd——目标生成/日计划/重排/目标摘要
     * 等「只对特殊型生效」的分支必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /**
     * 统一 AI 上下文注入器（日计划/重排属「角色视角」生成——world_setting 占位符
     * 升级为注入器按 actor_world_setting 拼装的世界观简报；字段注入保持构造器不变；
     * null=未装配回退 project.summary 旧口径）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== 目标 ====================

    /**
     * 列出角色目标（前端目标 Tab 用，全部状态）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @return 目标列表（新在前）
     */
    public List<ActorCharacterGoal> listGoals(Long projectId, String characterRef) {
        requireProject(projectId);
        if (characterRef == null || characterRef.isBlank()) {
            return List.of();
        }
        return goalRepository.findByProjectIdAndAgentRefAndDeleted(projectId, characterRef.trim(), 0);
    }

    /**
     * 手动创建目标。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param title 目标标题
     *
     * @param description 目标描述（可空）
     *
     * @param priority 优先级（1-5，可空=3）
     *
     * @return 新建目标
     */
    @Transactional
    public ActorCharacterGoal createGoal(Long projectId, String characterRef, String title, String description,
                                         Integer priority) {
        requireProject(projectId);
        if (characterRef == null || characterRef.isBlank()) {
            throw new BizException(400, "角色引用不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new BizException(400, "目标标题不能为空");
        }
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setProjectId(projectId);
        g.setAgentRef(characterRef.trim());
        g.setTitle(title.trim());
        g.setDescription(description == null || description.isBlank() ? null : description.trim());
        g.setPriority(priority == null ? 3 : Math.max(1, Math.min(5, priority)));
        g.setStatus("active");
        return goalRepository.save(g);
    }

    /**
     * 手动更新目标状态（active/done/abandoned）或标题/描述/优先级。
     *
     * @param goalId 目标 ID
     *
     * @param status 目标状态（可空=不改）
     *
     * @param title 标题（可空=不改）
     *
     * @param priority 优先级（可空=不改）
     *
     * @return 更新后的目标
     */
    @Transactional
    public ActorCharacterGoal updateGoal(Long goalId, String status, String title, Integer priority) {
        ActorCharacterGoal g = goalRepository.findById(goalId)
                .orElseThrow(() -> new BizException(404, "目标不存在：" + goalId));
        requireProject(g.getProjectId());
        if (status != null && !status.isBlank()) {
            if (!List.of("active", "done", "abandoned").contains(status)) {
                throw new BizException(400, "目标状态非法：" + status);
            }
            g.setStatus(status);
            if ("active".equals(status)) {
                g.setFinishedAt(null);
            }
        }
        if (title != null && !title.isBlank()) {
            g.setTitle(title.trim());
        }
        if (priority != null) {
            g.setPriority(Math.max(1, Math.min(5, priority)));
        }
        return goalRepository.save(g);
    }

    /**
     * 手动删除目标（软删）。
     *
     * @param goalId 目标 ID
     */
    @Transactional
    public void deleteGoal(Long goalId) {
        ActorCharacterGoal g = goalRepository.findById(goalId)
                .orElseThrow(() -> new BizException(404, "目标不存在：" + goalId));
        requireProject(g.getProjectId());
        g.setDeleted(1);
        goalRepository.save(g);
    }

    /**
     * AI 生成目标（goal_gen 模板，scene=goal_gen 用量）：按角色档案+世界观+当前状态生成 1~3 个
     * active 目标落库。run_mode=minimal 或预算不可用/失败时静默跳过（不阻断）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param gameHour 当前游戏小时（可空=世界时钟当前值）
     *
     * @return 新建目标数（0=未生成）
     */
    // ⚠️ jstack 抓修：本方法体内有 AI 调用（goal_gen，HTTP 最长 130s），原 @Transactional
    // 让事务开在 AI 之前——事务全程持有表锁，且被 AgentRuntime 决策链（@Transactional）嵌套调用时把
    // 「决策事务」拉长到跨 AI 存活。
    // 改为「无事务读 → AI 在事务外 → 短事务写」：读走只读查询，写由 repository 自身的短事务兜住
    // （多条目标各自提交；AI 已付费成功，部分落库也优于整批回滚丢掉结果）。
    public int generateGoal(Long projectId, String characterRef, Long gameHour) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type，普通型不生成目标）
        if (projectId == null || characterRef == null || !npcTypeResolver.isSpecial(projectId, characterRef)) {
            return 0;
        }
        String runMode = runModeOf(projectId);
        if (RunModes.isMinimal(runMode)) {
            log.debug("[规划] minimal 模式目标不自动生成: project={} ref={}", projectId, characterRef);
            return 0;
        }
        // 已有 active 目标则不再重复生成
        List<ActorCharacterGoal> active = goalRepository
                .findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(projectId, characterRef, "active", 0);
        if (!active.isEmpty()) {
            return 0;
        }
        if (!tryConsumeBudget(projectId)) {
            log.debug("[规划] 目标生成预算不可用（无窗口/用尽），跳过: project={} ref={}", projectId, characterRef);
            return 0;
        }
        ActorCharacter ch = resolveSpecial(characterRef);
        if (ch == null) {
            return 0;
        }
        Long userId = projectOwner(projectId);
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("character_info", characterInfo(ch));
        placeholders.put("world_setting", worldSetting(projectId));
        placeholders.put("current_state", currentState(ch, gameHour));
        placeholders.put("existing_goals", "（暂无）");
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_GOAL_GEN, placeholders);
        String sys = promptTemplateService.systemMessage(userId, projectId, PromptTemplateService.CODE_GOAL_GEN, null);
        // 批5 世界与叙事（2026-10-10）：目标生成属「角色视角」生成——前置 V2.0 段1A+1C+2D+2E
        // （memoryChainContext：世界观+角色核心卡+当前世界动态+当前状态；未装配/失败零阻断）
        String goalCtx = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, characterRef,
                        gameHour == null ? null : gameHour * 3600L);
        String finalPrompt = goalCtx.isBlank() ? prompt : goalCtx + "\n\n" + prompt;
        try {
            AiChatResult result = AiCallContext.run("goal_gen", ch.getId(), () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            chatRequest(finalPrompt, sys)));
            usageLogService.record(userId, projectId, ch.getId(), result.providerId(), result.model(), "goal_gen",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            List<GoalDraft> drafts = parseGoals(result.content());
            int created = 0;
            for (GoalDraft d : drafts) {
                if (created >= 3) {
                    break;
                }
                if (d.title() == null || d.title().isBlank()) {
                    continue;
                }
                ActorCharacterGoal g = new ActorCharacterGoal();
                g.setProjectId(projectId);
                g.setAgentRef(characterRef.trim());
                g.setTitle(truncate(d.title().trim(), 200));
                g.setDescription(d.description() == null || d.description().isBlank() ? null : d.description().trim());
                g.setPriority(d.priority() == null ? 3 : Math.max(1, Math.min(5, d.priority())));
                g.setStatus("active");
                goalRepository.save(g);
                created++;
            }
            log.info("[规划] 目标 AI 生成：project={} ref={} 新建 {} 个", projectId, characterRef, created);
            return created;
        } catch (Exception e) {
            log.warn("[规划] 目标 AI 生成失败（静默跳过）: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return 0;
        }
    }

    // ==================== 日计划（记忆流 kind=plan） ====================

    /**
     * 惰性补生成（双保险）：决策/跨日前调用——若该角色当天尚无计划记忆则生成日计划。
     * 供 WorldEngine 跨日边界与 AgentRuntime 决策循环（惰性）调用。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameHour 当前游戏小时（计划所属游戏日编号基准）
     *
     * @return 生成的计划 JSON 文本（当天已有计划返回 null）
     */
    // ⚠️ jstack 抓修：本方法只做「查有没有 → 委托 planDay」，而 planDay 内含 AI 调用；
    // 原 @Transactional 会把一个纯读检查升级成包住整次 AI 生成的长事务（同 planDay 的问题）。
    // 去事务后语义不变：findPlanForDay 为只读查询，写入由 planDay 内部的短事务负责。
    public String ensurePlanDay(Long projectId, String characterRef, Long gameHour) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        Long gh = gameHour != null ? gameHour : worldClockService.currentGameHour(projectId);
        if (gh == null) {
            gh = 0L;
        }
        long gameDay = Math.floorDiv(gh, 24);
        if (findPlanForDay(projectId, characterRef.trim(), gameDay) != null) {
            return null; // 当天已有计划
        }
        return planDay(projectId, characterRef.trim(), gameDay);
    }

    /**
     * 生成/刷新某游戏日的日计划（kind=plan 记忆流）：AI（plan_day 模板，预算门控）优先，
     * 失败/minimal 回退机制化确定性计划（基于角色/时段规则）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameDay 游戏日编号（floorDiv(gameHour,24)）
     *
     * @return 生成的计划 JSON 文本
     */
    // ⚠️ jstack 抓修（本方法即世界冻结的直接现场）：原 @Transactional 让事务包住
    // plan_day AI 调用（该场景 avg 42s/max 65s，HTTP 上限 130s）。被 AgentRuntime.loop 的
    // 「惰性补计划」路径嵌套调用时，一次日计划生成就独占数据库事务 40~65 秒：期间其它角色的
    // actor_state 写全部排队（锁超时），jstack 里 world-dispatch-2 线程正 RUNNABLE 在本方法的
    // AI 读取上、而世界引擎线程在 FutureTask.get() 等它。去事务后：AI 完全在事务外跑，写阶段
    // 由 agentMemoryService.recordPlan（已加 @Transactional）以毫秒级短事务完成。
    public String planDay(Long projectId, String characterRef, long gameDay) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        String runMode = runModeOf(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type；
        // 普通型只走机制计划，无角色档案可注入 AI）
        ActorCharacter ch = npcTypeResolver.isSpecial(projectId, characterRef) ? resolveSpecial(characterRef) : null;
        PlanData plan;
        // AI 生成（非 minimal 且预算可用；无窗口时自开）
        if (!RunModes.isMinimal(runMode) && tryConsumeBudget(projectId)) {
            plan = generatePlanAi(projectId, characterRef, ch, gameDay);
            if (plan == null) {
                plan = mechanismPlan(gameDay, seedOf(characterRef));
            }
        } else {
            plan = mechanismPlan(gameDay, seedOf(characterRef));
        }
        // 归属日归一化：AI 模板以「gameDay+1（展示日号）」为占位，AI 输出 date_game_day=gameDay+1，
        // 而查询侧统一用 0 基 floorDiv(gameHour,24) —— 归一化回 0 基，避免 findPlanForDay 匹配不上
        //
        if (plan.gameDay() != gameDay) {
            plan = plan.withGameDay(gameDay);
        }
        String json = toJson(plan);
        agentMemoryService.recordPlan(projectId, characterRef, json,
                plan.importance() == null ? 3 : plan.importance());
        log.info("[规划] 日计划生成：project={} ref={} 游戏日={} 条目={} AI={}",
                projectId, characterRef, gameDay, plan.items().size(), plan.fromAi());
        return json;
    }

    /**
     * 计划中断重排（世界事件打断时）：以「当前小时 + 事件摘要」为输入，AI 重排当前时段后的
     * 条目并写入一条新的 kind=plan 记忆（覆盖式推进，当天后续执行按新计划）。AI 失败回退机制
     * 计划（至少保证有可执行计划）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameHour 当前游戏小时
     *
     * @param eventText 打断事件摘要（可空）
     *
     * @return 重排后的计划 JSON 文本（当天无原计划时返回 null）
     */
    // ⚠️ jstack 抓修：同 planDay——本方法含 replan AI 调用，事务不得跨 AI 存活。
    public String replanOnDisruption(Long projectId, String characterRef, Long gameHour, String eventText) {
        if (projectId == null || characterRef == null || characterRef.isBlank() || gameHour == null) {
            return null;
        }
        long gameDay = Math.floorDiv(gameHour, 24);
        ActorMemory oldPlan = findPlanForDay(projectId, characterRef.trim(), gameDay);
        if (oldPlan == null) {
            return null; // 当天无计划，无需重排
        }
        String runMode = runModeOf(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type；
        // 普通型只走机制计划，无角色档案可注入 AI）
        ActorCharacter ch = npcTypeResolver.isSpecial(projectId, characterRef) ? resolveSpecial(characterRef) : null;
        PlanData plan;
        if (!RunModes.isMinimal(runMode) && tryConsumeBudget(projectId)) {
            plan = generateReplanAi(projectId, characterRef, ch, gameDay, (int) Math.floorMod(gameHour, 24), eventText);
            if (plan == null) {
                plan = mechanismPlan(gameDay, seedOf(characterRef));
            }
        } else {
            plan = mechanismPlan(gameDay, seedOf(characterRef));
        }
        // 归属日归一化（同 planDay：AI 模板输出 date_game_day=gameDay+1，归一化回 0 基查询口径）
        if (plan.gameDay() != gameDay) {
            plan = plan.withGameDay(gameDay);
        }
        String json = toJson(plan);
        agentMemoryService.recordPlan(projectId, characterRef, json,
                plan.importance() == null ? 3 : plan.importance());
        log.info("[规划] 计划中断重排：project={} ref={} 游戏日={} 事件={}",
                projectId, characterRef, gameDay, eventText == null ? "" : eventText);
        return json;
    }

    // ==================== 感知注入文本（目标 + 计划） ====================

    /**
     * 感知【当前目标】摘要：该角色 active 目标 top2（按优先级 desc + 新）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @return 摘要文本（无目标返回空串）；普通型无目标表返回空串
     */
    public String goalsSummary(Long projectId, String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否特殊型查 actor_profile.type，普通型无目标）
        if (projectId == null || characterRef == null || !npcTypeResolver.isSpecial(projectId, characterRef)) {
            return "";
        }
        try {
            List<ActorCharacterGoal> goals = goalRepository
                    .findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(projectId, characterRef, "active", 0);
            if (goals.isEmpty()) {
                return "";
            }
            goals.sort(java.util.Comparator
                    .comparingInt((ActorCharacterGoal g) -> g.getPriority() == null ? 3 : g.getPriority()).reversed()
                    .thenComparing(g -> g.getCreatedAt() == null ? java.time.LocalDateTime.MIN : g.getCreatedAt(),
                            java.util.Comparator.reverseOrder()));
            StringBuilder sb = new StringBuilder("【当前目标】");
            int n = 0;
            for (ActorCharacterGoal g : goals) {
                if (n >= GOALS_SUMMARY_MAX) {
                    break;
                }
                sb.append("\n- [").append(g.getPriority() == null ? 3 : g.getPriority()).append("] ").append(g.getTitle());
                if (g.getDescription() != null && !g.getDescription().isBlank()) {
                    // 2026-10-10 决策：不设字数硬边界，目标描述全量注入（不再截断 40 字）
                    sb.append("（").append(g.getDescription()).append("）");
                }
                n++;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[规划] 目标摘要查询失败: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 感知【今日计划】摘要：该角色当天计划的当前时段条目（含未来 1~2 条预告）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameHour 当前游戏小时（可空=世界时钟）
     *
     * @return 摘要文本（无计划返回空串）
     */
    public String planSummary(Long projectId, String characterRef, Long gameHour) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        Long gh = gameHour != null ? gameHour : worldClockService.currentGameHour(projectId);
        if (gh == null) {
            return "";
        }
        long gameDay = Math.floorDiv(gh, 24);
        try {
            ActorMemory planMem = findPlanForDay(projectId, characterRef.trim(), gameDay);
            if (planMem == null) {
                return "";
            }
            PlanData plan = parsePlan(planMem.getContent());
            if (plan == null || plan.items() == null || plan.items().isEmpty()) {
                return "";
            }
            int hourOfDay = (int) Math.floorMod(gh, 24);
            PlanItem current = null;
            List<PlanItem> upcoming = new ArrayList<>();
            for (PlanItem it : plan.items()) {
                if (it.hour() <= hourOfDay) {
                    current = it;
                } else if (upcoming.size() < 2) {
                    upcoming.add(it);
                }
            }
            StringBuilder sb = new StringBuilder("【今日计划】");
            if (current != null) {
                sb.append("\n- 当前（").append(current.hour()).append("时）：")
                        .append(current.action() == null || current.action().isBlank() ? "日常活动" : current.action());
            } else if (!plan.items().isEmpty()) {
                PlanItem first = plan.items().get(0);
                sb.append("\n- 即将（").append(first.hour()).append("时）：")
                        .append(first.action() == null || first.action().isBlank() ? "日常活动" : first.action());
            }
            // 即将条目（current==null 时第一条已是「即将」，后续条目才进「稍后」，避免重复）
            int skip = (current == null && !upcoming.isEmpty()) ? 1 : 0;
            for (int i = skip; i < upcoming.size(); i++) {
                PlanItem it = upcoming.get(i);
                sb.append("\n- 稍后（").append(it.hour()).append("时）：")
                        .append(it.action() == null || it.action().isBlank() ? "日常活动" : it.action());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[规划] 计划摘要查询失败: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return "";
        }
    }

    /**
     * 当日计划全文（角色页「目标/计划」Tab 展示用）：返回当天 kind=plan 记忆的 JSON 内容
     * （含 date_game_day/fromAi/items）；无计划返回空串。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @return 计划 JSON 文本（无计划空串）
     */
    public String planToday(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        Long gh = worldClockService.currentGameHour(projectId);
        if (gh == null) {
            return "";
        }
        long gameDay = Math.floorDiv(gh, 24);
        ActorMemory planMem = findPlanForDay(projectId, characterRef.trim(), gameDay);
        return planMem == null ? "" : planMem.getContent();
    }

    // ==================== AI 生成内部 ====================

    /** AI 生成日计划（plan_day 模板，scene=plan_day 用量）；失败返回 null（调用方回退机制） */
    private PlanData generatePlanAi(Long projectId, String characterRef, ActorCharacter ch, long gameDay) {
        Long userId = projectOwner(projectId);
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("character_info", characterInfo(ch));
        placeholders.put("world_setting", worldSetting(projectId));
        placeholders.put("current_state", currentState(ch, gameDay * 24L + 8));
        placeholders.put("goals", goalsSummary(projectId, characterRef));
        placeholders.put("date_game_day", gameDay + 1);
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_PLAN_DAY, placeholders);
        String sys = promptTemplateService.systemMessage(userId, projectId, PromptTemplateService.CODE_PLAN_DAY, null);
        // 批5 世界与叙事（2026-10-10）：日计划生成属「角色视角」生成——前置 V2.0 段1A+1C+2D+2E
        // （memoryChainContext：世界观+角色核心卡+当前世界动态+当前状态；未装配/失败零阻断）
        String planCtx = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, characterRef, (gameDay * 24L + 8) * 3600L);
        String finalPrompt = planCtx.isBlank() ? prompt : planCtx + "\n\n" + prompt;
        try {
            AiChatResult result = AiCallContext.run("plan_day", ch == null ? null : ch.getId(), () ->
                    aiChatGateway.chatCompletion(userId, projectId, null, chatRequest(finalPrompt, sys)));
            usageLogService.record(userId, projectId, ch == null ? null : ch.getId(), result.providerId(), result.model(),
                    "plan_day", result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            PlanData plan = parsePlan(result.content());
            if (plan == null || plan.items() == null || plan.items().isEmpty()) {
                return null;
            }
            return plan.withFromAi(true);
        } catch (Exception e) {
            log.warn("[规划] 日计划 AI 生成失败，回退机制: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return null;
        }
    }

    /** AI 重排（plan_replan 模板，scene=plan_replan 用量）；失败返回 null */
    private PlanData generateReplanAi(Long projectId, String characterRef, ActorCharacter ch, long gameDay,
                                      int hourOfDay, String eventText) {
        Long userId = projectOwner(projectId);
        ActorMemory oldPlan = findPlanForDay(projectId, characterRef.trim(), gameDay);
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("character_info", characterInfo(ch));
        placeholders.put("world_setting", worldSetting(projectId));
        placeholders.put("current_state", currentState(ch, gameDay * 24L + hourOfDay));
        placeholders.put("goals", goalsSummary(projectId, characterRef));
        placeholders.put("date_game_day", gameDay + 1);
        placeholders.put("current_hour", hourOfDay);
        placeholders.put("disruption_event", eventText == null || eventText.isBlank() ? "（无）" : eventText);
        placeholders.put("old_plan", oldPlan == null ? "（无）" : oldPlan.getContent());
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_PLAN_REPLAN, placeholders);
        String sys = promptTemplateService.systemMessage(userId, projectId, PromptTemplateService.CODE_PLAN_REPLAN, null);
        // 批5 世界与叙事（2026-10-10）：计划中断重排属「角色视角」生成——前置 V2.0 段1A+1C+2D+2E
        // （memoryChainContext：世界观+角色核心卡+当前世界动态+当前状态；未装配/失败零阻断）
        String replanCtx = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, characterRef,
                        (gameDay * 24L + hourOfDay) * 3600L);
        String finalPrompt = replanCtx.isBlank() ? prompt : replanCtx + "\n\n" + prompt;
        try {
            AiChatResult result = AiCallContext.run("plan_replan", ch == null ? null : ch.getId(), () ->
                    aiChatGateway.chatCompletion(userId, projectId, null, chatRequest(finalPrompt, sys)));
            usageLogService.record(userId, projectId, ch == null ? null : ch.getId(), result.providerId(), result.model(),
                    "plan_replan", result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            PlanData plan = parsePlan(result.content());
            if (plan == null || plan.items() == null || plan.items().isEmpty()) {
                return null;
            }
            return plan.withFromAi(true);
        } catch (Exception e) {
            log.warn("[规划] 计划重排 AI 生成失败，回退机制: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return null;
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

    // ==================== 机制化计划（零 AI 成本，确定性） ====================

    /**
     * 机制化日计划（零 AI 成本）：按游戏日/角色 seed 确定性生成 5~8 个时段条目——
     * 对齐既有作息规则（0-5 休息 / 6-7 出行 / 8-11 劳作 / 12-13 午休 / 14-17 劳作 / 18-19 归途 / 20-22 社交 / 23 就寝），
     * 个体差异由 seed 微调（去「同一小时反复重算相同状态」的单一化，）。静态可测。
     *
     * @param gameDay 游戏日编号（>=0）
     *
     * @param seed 角色稳定种子（如 characterRef.hashCode 或 npc.id）
     *
     * @return 计划数据
     */
    static PlanData mechanismPlan(long gameDay, int seed) {
        Random rng = new Random(seed * 31L + gameDay);
        int n = PLAN_ITEMS_MIN + rng.nextInt(PLAN_ITEMS_MAX - PLAN_ITEMS_MIN + 1);
        List<PlanItem> items = new ArrayList<>();
        int[] anchors = {6, 8, 11, 13, 15, 18, 20, 23};
        int idx = 0;
        for (int i = 0; i < n && idx < anchors.length; i++) {
            int hour = anchors[idx] + (idx == 0 && rng.nextBoolean() ? 0 : 0);
            String action = switch (idx) {
                case 0 -> "晨起出门，前往日常活动地点";
                case 1 -> "从事上午的工作/劳作";
                case 2 -> rng.nextBoolean() ? "采购或处理生活事务" : "与邻里交谈交换消息";
                case 3 -> "午间小憩，短暂歇息";
                case 4 -> "继续下午的事务";
                case 5 -> "收工归途，返回住处";
                case 6 -> rng.nextBoolean() ? "在茶楼/广场与熟人攀谈" : "整理今日见闻，做准备";
                default -> "就寝歇息";
            };
            items.add(new PlanItem(hour, action, "驻地附近", null, idx == 2 ? "talk" : (idx == 3 ? "rest" : "stop")));
            idx++;
        }
        return new PlanData(gameDay, items, 3, false);
    }

    /** 角色稳定种子（统一引用 {@code npc:{id}} 用 id，其他用 hash） */
    static int seedOf(String characterRef) {
        if (characterRef == null) {
            return 1;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        // （保持原有 & 0x7fffffff 位运算与「非法 → hash 兜底」语义）
        Long id = NpcRefs.idOf(characterRef);
        if (id != null) {
            return (int) (id & 0x7fffffff);
        }
        return Math.abs(characterRef.hashCode()) + 1;
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 解析 AI 目标输出 JSON 数组（静态可测）：剥代码块/说明文字，提取 [{title,description,priority}]。
     * 失败/非法返回空列表。
     */
    static List<GoalDraft> parseGoals(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode node;
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return List.of();
            }
            node = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        List<GoalDraft> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode it : node) {
                out.add(new GoalDraft(it.path("title").asText(null),
                        it.path("description").asText(null),
                        it.path("priority").isInt() ? it.path("priority").asInt(3) : null));
            }
        } else if (node != null && node.isObject()) {
            // 兼容单对象输出
            out.add(new GoalDraft(node.path("title").asText(null),
                    node.path("description").asText(null),
                    node.path("priority").isInt() ? node.path("priority").asInt(3) : null));
        }
        return out;
    }

    /**
     * 解析日计划 JSON（静态可测）：{"date_game_day":N,"items":[{"hour":8,"action":"...","target":"...","location":"...","state":"..."}]}。
     * 非法/缺 items 返回 null。
     */
    static PlanData parsePlan(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(content);
            if (node == null || !node.isObject()) {
                return null;
            }
            long gameDay = node.path("date_game_day").asLong(0);
            boolean fromAi = node.path("fromAi").asBoolean(false);
            List<PlanItem> items = new ArrayList<>();
            JsonNode arr = node.path("items");
            if (!arr.isArray()) {
                return null;
            }
            for (JsonNode it : arr) {
                if (!it.path("hour").isInt()) {
                    continue;
                }
                int hour = Math.floorMod(it.path("hour").asInt(0), 24);
                items.add(new PlanItem(hour,
                        it.path("action").asText(null),
                        it.path("target").asText(null),
                        it.path("location").asText(null),
                        it.path("state").asText(null)));
            }
            if (items.isEmpty()) {
                return null;
            }
            return new PlanData(gameDay, items, 3, fromAi);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查找某游戏日的计划记忆（type=plan + date_game_day 匹配）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameDay 游戏日编号
     *
     * @return 匹配的计划记忆（无返回 null）
     */
    ActorMemory findPlanForDay(Long projectId, String characterRef, long gameDay) {
        List<ActorMemory> plans = memoryRepository
                .findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                        projectId, characterRef, ActorMemoryTypes.TYPE_PLAN, 0);
        if (plans.isEmpty()) {
            // 兼容旧双轨（仅 character_id 的计划）
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            Long specialId = NpcRefs.idOf(characterRef);
            if (specialId != null) {
                plans = memoryRepository
                        .findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                                projectId, specialId, ActorMemoryTypes.TYPE_PLAN, 0);
            }
        }
        for (ActorMemory m : plans) {
            PlanData p = parsePlan(m.getContent());
            if (p != null && p.gameDay() == gameDay) {
                return m;
            }
        }
        return null;
    }

    // ==================== 私有辅助 ====================

    /** 解析角色档案实体 */
    private ActorCharacter resolveSpecial(String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        return characterRepository.findById(id).orElse(null);
    }

    /** 角色档案摘要（AI 生成输入） */
    private String characterInfo(ActorCharacter ch) {
        if (ch == null) {
            return "（角色信息缺失）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(ch.getName());
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append("（").append(ch.getTitle()).append("）");
        }
        sb.append("；重要度：").append(ch.getImportance() == null ? 1 : ch.getImportance());
        if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
            sb.append("；当前在做：").append(ch.getCurrentActivity());
        }
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("；位置：").append(ch.getLocation());
        }
        String persona = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
        if (persona != null && !persona.isBlank()) {
            try {
                JsonNode p = objectMapper.readTree(persona);
                java.util.List<String> bits = new ArrayList<>();
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "personality", "traits_summary");
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "values", "life_goals");
                com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 3, p, "dims", "habits", "micro_movements");
                if (!bits.isEmpty()) {
                    sb.append("；人设：").append(String.join("、", bits));
                }
            } catch (Exception ignored) {
                // 忽略角色卡解析失败
            }
        }
        return sb.toString();
    }

    /**
     * 角色档案摘要（characterRef 版，公开——ReflectionService 反思输入用）。
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @return 档案摘要文本
     */
    public String characterInfoText(String characterRef) {
        if (characterRef == null || characterRef.isBlank()) {
            return "（角色信息缺失）";
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type；
        // 上游无 projectId → 走 null=不做项目校验，失败回退 special 口径）
        if (npcTypeResolver.isCommon(null, characterRef)) {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
            Long id = NpcRefs.idOf(characterRef);
            ActorCharacter npc = id == null ? null : npcRepository.findById(id).orElse(null);
            if (npc == null) {
                return "（角色信息缺失）";
            }
            return "姓名：" + npc.getName()
                    + (npc.getRace() == null || npc.getRace().isBlank() ? "" : "；种族：" + npc.getRace())
                    + (npc.getOccupation() == null || npc.getOccupation().isBlank() ? "" : "；职业：" + npc.getOccupation())
                    + (npc.getAffiliation() == null || npc.getAffiliation().isBlank() ? "" : "；归属：" + npc.getAffiliation())
                    + (npc.getLocation() == null || npc.getLocation().isBlank() ? "" : "；位置：" + npc.getLocation())
                    + (npc.getDetail() == null || npc.getDetail().isBlank() ? "" : "；详情：" + npc.getDetail());
        }
        ActorCharacter ch = resolveSpecial(characterRef);
        return characterInfo(ch);
    }

    /** 当前状态文本（AI 生成输入；特殊型=角色状态/普通型=居民档案） */
    private String currentState(ActorCharacter ch, Long gameHour) {
        if (ch == null) {
            return "（当前状态未知）";
        }
        String time = gameHour == null ? "未知时刻" : WorldClockService.formatGameTime(gameHour);
        return "当前游戏时刻：" + time
                + "；角色状态：" + (ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "空闲" : ch.getCurrentActivity())
                + (ch.getLocation() == null || ch.getLocation().isBlank() ? "" : "；位置：" + ch.getLocation());
    }

    /** 世界观设定文本（截断；优先注入器按 actor_world_setting 拼装的世界观简报，回退 project.summary） */
    private String worldSetting(Long projectId) {
        if (aiContextInjector != null) {
            try {
                String brief = aiContextInjector.worldBrief(projectId);
                if (brief != null && !brief.isBlank()) {
                    return brief;
                }
            } catch (Exception e) {
                log.debug("[规划] 世界观简报读取失败（回退 project.summary）: project={} : {}", projectId, e.getMessage());
            }
        }
        return projectRepository.findById(projectId)
                .map(p -> p.getSummary() == null ? "" : p.getSummary())
                .orElse("")
                .trim();
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
     * 预算消耗（手动/事件路径自开窗口，对齐 AgentRuntime ownsTick 模式）：
     * 无外层预算窗口（世界引擎未在 tick）时自开一个默认窗口并消耗一次后立即关闭，
     * 否则复用共享窗口（世界引擎 tick 进行中，预算统一进门面，修）。
     *
     * @param projectId 项目 ID
     *
     * @return true=预算充足并已消耗
     */
    private boolean tryConsumeBudget(Long projectId) {
        if (projectId == null) {
            return false;
        }
        boolean ownsTick = budgetGuard.budgetTotal(projectId) < 0;
        if (ownsTick) {
            budgetGuard.beginTick(projectId, SELF_BUDGET);
        }
        boolean ok;
        try {
            ok = budgetGuard.tryConsume(projectId);
        } finally {
            if (ownsTick) {
                budgetGuard.endTick(projectId);
            }
        }
        return ok;
    }

    /** 校验项目归属当前用户（越权抛 404；单用户 id=1 无登录场景走 project 存在性校验） */
    private void requireProject(Long projectId) {
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }

    /** 数组字段到摘要片段 */
    private void appendBits(java.util.List<String> bits, JsonNode node, int max) {
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String v = n.asText("");
                if (!v.isBlank() && bits.size() < max) {
                    bits.add(v);
                }
            });
        }
    }

    /** 截断 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            return t.substring(0, max);
        }
        return t;
    }

    /** 计划 JSON 序列化 */
    private String toJson(PlanData plan) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date_game_day", plan.gameDay());
            m.put("fromAi", plan.fromAi());
            List<Map<String, Object>> items = new ArrayList<>();
            for (PlanItem it : plan.items()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("hour", it.hour());
                im.put("action", it.action());
                im.put("target", it.target());
                im.put("location", it.location());
                im.put("state", it.state());
                items.add(im);
            }
            m.put("items", items);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"date_game_day\":" + plan.gameDay() + ",\"items\":[]}";
        }
    }

    // ==================== 内部记录 ====================

    /** 目标草稿（AI 解析结果） */
    record GoalDraft(String title, String description, Integer priority) {
    }

    /** 计划条目 */
    record PlanItem(int hour, String action, String target, String location, String state) {
    }

    /** 计划数据（游戏日 + 条目 + 重要度 + 是否 AI） */
    record PlanData(long gameDay, List<PlanItem> items, Integer importance, boolean fromAi) {
        PlanData withFromAi(boolean ai) {
            return new PlanData(gameDay, items, importance, ai);
        }

        PlanData withGameDay(long day) {
            return new PlanData(day, items, importance, fromAi);
        }
    }
}
