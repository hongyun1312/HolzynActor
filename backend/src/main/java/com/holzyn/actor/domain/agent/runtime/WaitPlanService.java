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

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 等待类计划服务。
 * <p>职责（主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.5 真人感五维度之
 * 「时间感」+ / /）：AI 决策输出可选 {@code wait_minutes}（等待游戏分钟数）→
 * 计划按<b>游戏时刻</b>排期（actor_plan 新增列 planned_game_second，与真实时间 planned_time
 * 并存）→ 引擎按推进位置到点触发<b>重新 AI 决策</b>（：动作 Y 仅作提示，AI 再决定做什么，
 * 情境显式注入「你之前决定等到此刻再去：{原动作}，现在时候到了」）。</p>
 * <p>护栏：等待时长钳制（{@code wait-max-game-minutes} 默认 720=12 游戏小时）；
 * 连续等待上限（{@code wait-max-consecutive} 默认 2：同一角色连续两次等待后， 到期
 * <b>直接执行原动作</b>不再重新决策，防「等待→等待」死循环——24 小时不间断运行健壮性）；
 * 激活闸门（未激活项目到期计划保持 planned 不触发，世界恢复后自然续走）。</p>
 * <p>设计依据（联网调研）：NeurIPS 2025 Delay-of-Gratification 多 agent 生存微基准
 * （wait_until/wait_for 显式行动原语）/ Enforcing Temporal Constraints for LLM Agents
 * （arXiv:2512.23738，等待=延迟队列、硬约束走后端调度器）/ Your LLM Agents are Temporally Blind
 * （arXiv:2510.23853，prompt 显式注入时间与等待语境）——等待语义由<b>后端调度器</b>承载
 * （游戏时刻到点触发），AI 只负责「要不要等、等多久、到点做什么」。</p>
 * <p>成本：到点重新决策 = 一次完整决策（预算门控，无外部预算窗口时 ownsTick 自开）——
 * 由 wait-max-consecutive 与冷却天然约束；其余判定纯程序化零 AI。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitPlanService {

    /** 等待计划 action_json 内的等待标记（AgentRuntime 创建等待计划时写入，供审计/去重） */
    static final String WAIT_MARKER = "_waitPlan";

    /** 连续等待计数器：key=projectId:npcRef → 连续等待次数（进程内存态，重启丢失可接受） */
    private final ConcurrentHashMap<String, Integer> consecutiveWait = new ConcurrentHashMap<>();

    /** 计数器条目上限（防长期运行内存膨胀） */
    private static final int CACHE_LIMIT = 10000;

    private final RealtimeDecisionProperties props;
    private final ActorActionPlanRepository planRepository;
    private final ActorCharacterRepository characterRepository;

    /**
     * 智能体运行时（到点重新决策触发；@Lazy 字段注入破 Bean 环——本服务被 AgentRuntime 字段注入，
     * 构造函数再依赖 AgentRuntime 会构成构造期循环[启动实测抓修 ]，改为 @Lazy 字段
     * 运行时经代理解析真实 Bean；null=未装配[纯数据单测]跳过触发）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private AgentRuntime agentRuntime;

    /** 世界时钟（当前游戏时刻基准；null=未知，等待计划创建回退 null 立即执行） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 实时决策激活闸门 */
    @Autowired(required = false)
    private RealtimeDecisionActivationService realtimeDecisionActivationService;

    /** 个性注入服务（到点重新决策的情境组装【角色个性】段；null=回退纯刺激情境） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private PersonalityInjectionService personalityInjectionService;

    /** 行动组件（连续等待超限时直接执行原动作；与 AgentRuntime 同依赖，无环） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.action.AgentActionService agentActionService;

    // ==================== R1 引擎 AI 异步化：到点重决策异步执行器 ====================
    // 到点重新决策 = 一次完整 STIMULUS 决策链（含 AI，10~40s/次）。长时间冻结/回补后可能有大量
    // 计划同时到期，若在引擎线程同步触发（runDueWaitPlans 在小时边界 tick 内调用），会把调度线程
    // 占死（与 R1 已修的 reactCrowds 同步空间 AI 同族）。改为虚拟线程异步触发：
    // AgentRuntime.trigger 内部自带 tick 屏障登记 → 引擎 tick 的 awaitQuiescence 最多等 30s 兜底，
    // 迟到落库可接受（真实的人也不会整点齐刷刷立刻行动）。
    private volatile ExecutorService redeciExecutor;

    private ExecutorService redeciExecutor() {
        ExecutorService ex = redeciExecutor;
        if (ex != null) {
            return ex;
        }
        synchronized (this) {
            if (redeciExecutor == null) {
                redeciExecutor = Executors.newVirtualThreadPerTaskExecutor();
                log.info("[等待计划] 到点重决策异步执行器已创建（虚拟线程每任务一线程）");
            }
            return redeciExecutor;
        }
    }

    /** 引擎停机：关闭到点重决策异步执行器（虚拟线程 daemon，仅及时释放资源）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownRedeciExecutor() {
        ExecutorService ex = redeciExecutor;
        if (ex != null) {
            ex.shutdownNow();
            log.info("[等待计划] 到点重决策异步执行器已关闭");
        }
    }

    // ==================== 等待计划创建（AgentRuntime.loop 调用） ====================

    /**
     * 尝试把决策排期为等待类计划。
     * <p>语义：AI 决策带 wait_minutes（>0）且时间感开关开启且未超连续等待上限 →
     * 计算计划游戏时刻（当前 + wait_minutes×60，钳制 wait-max-game-minutes）并返回；
     * 否则返回 null（调用方立即执行）。连续等待计数在此递增。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（special:{id}）
     *
     * @param gameSecond 调用方提供的当前游戏秒（**仅在世界时钟不可用[未装配/纯数据单测]时回退使用**；
     * 正常以世界时钟引擎推进位置为唯一基准，与到期检查同口径，防双口径永不到期）
     *
     * @param waitMinutes AI 建议等待游戏分钟数（>0）
     *
     * @param decision 决策 Map（等待标记写入审计）
     *
     * @return 计划游戏时刻（秒）或 null（不排期，立即执行）
     */
    public Long scheduleWaitPlan(Long projectId, String npcRef, Long gameSecond, int waitMinutes,
                                 Map<String, Object> decision) {
        if (!props.isTimeSenseActionEnabled() || projectId == null || npcRef == null || waitMinutes <= 0) {
            return null;
        }
        // 连续等待上限：超限不再排期（立即执行，防死循环）
        if (consecutiveCount(projectId, npcRef) >= props.getWaitMaxConsecutive()) {
            log.info("[等待计划] 连续等待超限（上限 {}），立即执行不再排期: ref={}",
                    props.getWaitMaxConsecutive(), npcRef);
            return null;
        }
        // 等待时刻基准统一为「引擎推进位置」（与 runDueWaitPlans 到期检查同口径）：
        // ⚠️ 冒烟抓修——此前直接用调用方传入的 gameSecond（即时决策=事件时刻[流动时钟]），
        // 与引擎位置（last_game_second，WorldEngine 每小时边界 hour×3600）双口径不一致，
        // 导致 planned_game_second 远大于引擎位置 → 等待计划永不到期；
        // 统一后创建与到期都以引擎位置为基准，引擎推进到点即触发。
        Long now = currentGameSecond(projectId);
        if (now == null) {
            now = gameSecond; // 世界时钟不可用（纯数据单测/未装配）→ 回退调用方提供的时刻
        }
        if (now == null) {
            return null; // 游戏时刻未知 → 无法排期，立即执行
        }
        int capped = Math.min(Math.max(1, waitMinutes), props.getWaitMaxGameMinutes());
        Long until = now + capped * 60L;
        consecutiveWait.merge(key(projectId, npcRef), 1, Integer::sum);
        enforceLimit();
        if (decision != null) {
            decision.put(WAIT_MARKER, true);
            decision.put("waitUntilGameSecond", until);
        }
        log.info("[等待计划] 排期等待: ref={} 等待 {} 游戏分钟 → 到点 {}（重新决策）",
                npcRef, capped, until);
        return until;
    }

    /**
     * 非等待行动执行后重置连续等待计数（AgentRuntime.loop 立即执行路径调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（special:{id}）
     */
    public void noteActionExecuted(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null) {
            return;
        }
        consecutiveWait.remove(key(projectId, npcRef));
    }

    /** 当前连续等待次数（测试断言用）。 */
    public int consecutiveCount(Long projectId, String npcRef) {
        Integer n = consecutiveWait.get(key(projectId, npcRef));
        return n == null ? 0 : n;
    }

    // ==================== 到点处理（WorldEngine 每游戏小时边界调用） ====================

    /**
     * 到点等待计划处理：查 planned 且 planned_game_second ≤ 当前游戏秒的计划 → 逐条
     * ① 激活闸门未通过 → 保持 planned 不触发（世界恢复后自然续走）；
     * ② 连续等待超限 → 直接执行原动作（AgentActionService，不再重新决策）；
     * ③ 否则标记原计划 done + 触发一次重新 AI 决策（情境 =【角色个性】+「你之前决定等到此刻再去做：
     * {原动作}，现在时候到了」+ 决策要求；STIMULUS 触发源直接触发，不经过打断/探针/冷却——
     * 角色自己的计划到期，非外部刺激）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（引擎推进位置；null → 跳过）
     */
    public void runDueWaitPlans(Long projectId, Long gameSecond) {
        if (!props.isTimeSenseActionEnabled() || projectId == null || gameSecond == null) {
            return;
        }
        List<ActorActionPlan> due;
        try {
            due = planRepository.findByStatusAndPlannedGameSecondLessThanEqual("planned", gameSecond);
        } catch (Exception e) {
            log.warn("[等待计划] 到期查询失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            return;
        }
        for (ActorActionPlan plan : due) {
            try {
                if (plan.getCharacterId() == null) {
                    continue;
                }
                ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
                if (ch == null || !projectId.equals(ch.getProjectId())) {
                    continue;
                }
                String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
                // ① 激活闸门：未激活不触发（世界暂停，游戏时刻也不推进会自然不到期）
                if (realtimeDecisionActivationService != null
                        && !realtimeDecisionActivationService.isActive(projectId)) {
                    continue;
                }
                String originalAction = originalActionOf(plan);
                // ② 连续等待超限 → 直接执行原动作（安全阀，防死循环）
                if (consecutiveCount(projectId, ref) >= props.getWaitMaxConsecutive()) {
                    log.info("[等待计划] 连续等待超限，直接执行原动作: plan={} ref={} 动作={}",
                            plan.getId(), ref, abbreviate(originalAction, 40));
                    consecutiveWait.remove(key(projectId, ref));
                    agentActionService.execute(plan);
                    continue;
                }
                // ③ 标记原计划完成 + 到点重新 AI 决策
                plan.setStatus("done");
                plan.setExecutedAt(LocalDateTime.now());
                planRepository.save(plan);
                triggerRedeciDecision(projectId, ref, ch, plan.getId(), originalAction, gameSecond);
            } catch (Exception e) {
                log.warn("[等待计划] 到期处理失败（隔离继续）: plan={} : {}", plan.getId(), e.getMessage());
            }
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 到点重新 AI 决策（STIMULUS 直接触发：情境 = 个性段 + 等待到期刺激 + 决策要求）。
     * ⚠️ R1：改异步执行——完整决策链含 AI（10~40s/次），冻结/回补后大量计划同时
     * 到期时同步触发会占死引擎线程；触发本身注册进 tick 屏障（AgentRuntime 内部），引擎 tick 的
     * awaitQuiescence 最多等 30s 兜底，迟到落库可接受。
     */
    private void triggerRedeciDecision(Long projectId, String ref, ActorCharacter ch, Long planId,
                                       String originalAction, Long gameSecond) {
        String stimulus = "你之前决定等到此刻再去做的事：「" + originalAction + "」。现在时候到了，"
                + "请决定现在去做什么（可以按原计划去做，也可以根据情况调整）。";
        String segment = "";
        if (personalityInjectionService != null) {
            try {
                segment = personalityInjectionService.buildPersonalitySegment(projectId, ref, gameSecond, null);
            } catch (Exception e) {
                log.warn("[等待计划] 个性段组装失败（回退纯刺激情境）: ref={} : {}", ref, e.getMessage());
            }
        }
        String situation = PersonalityInjectionService.composeStimulusSituation(segment, stimulus);
        DecisionContext ctx = DecisionContext.builder()
                .projectId(projectId)
                .userId(currentUserProvider == null ? 1L : currentUserProvider.currentUserId())
                .gameSecond(gameSecond)
                .situation(situation)
                .stimulusType(StimulusType.WAIT_PLAN_DUE.code())
                .stimulusText(stimulus)
                .reason("等待计划到期（plan=" + planId + "）：" + abbreviate(originalAction, 80))
                .build();
        log.info("[等待计划] 到点重新决策: project={} ref={} plan={} 原动作={}",
                projectId, ref, planId, abbreviate(originalAction, 40));
        ExecutorService ex = redeciExecutor();
        try {
            ex.submit(() -> {
                try {
                    agentRuntime.trigger(AgentTrigger.STIMULUS, ref, ctx);
                } catch (Exception e) {
                    log.warn("[等待计划] 到点重决策异步触发失败（隔离继续）: project={} ref={} plan={} : {}",
                            projectId, ref, planId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[等待计划] 到点重决策异步提交失败（跳过该计划）: project={} ref={} plan={} : {}",
                    projectId, ref, planId, e.getMessage());
        }
    }

    /** 从计划 action_json 解析原动作描述（失败回退通用文本）。 */
    private String originalActionOf(ActorActionPlan plan) {
        try {
            if (plan.getActionJson() != null && !plan.getActionJson().isBlank()) {
                tools.jackson.databind.JsonNode node =
                        new tools.jackson.databind.ObjectMapper().readTree(plan.getActionJson());
                String action = node.path("action").asText("");
                if (action != null && !action.isBlank()) {
                    return action.trim();
                }
            }
        } catch (Exception ignored) {
            // 解析失败回退通用文本
        }
        return "那件原本打算去做的事";
    }

    /** 当前游戏秒（引擎推进位置；失败返回 null）。 */
    private Long currentGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 计数器键（projectId:npcRef）。 */
    private static String key(Long projectId, String npcRef) {
        return projectId + ":" + npcRef;
    }

    /** 计数器条目上限保护（惰性清理最旧一条，防内存膨胀）。 */
    private void enforceLimit() {
        if (consecutiveWait.size() > CACHE_LIMIT) {
            consecutiveWait.keySet().stream().findFirst().ifPresent(consecutiveWait::remove);
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
}
