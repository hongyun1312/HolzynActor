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

package com.holzyn.actor.domain.agent.action;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.action.entity.ActorActionLog;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.action.service.ActionSseHub;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.learning.LearningService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行动组件（阶段 C，domain/agent/action）——行动执行收编 {@code ActionEngine.execute}。
 * <p>定位：把决策转译为
 * <b>世界状态变更 + 世界事件发布 + 记忆沉淀</b>——修「行动是死环」（L-01/L-05）。</p>
 * <p>收编内容（原 {@code ActionEngine.execute/publishActionEvent/recordActionMemory}，行为不变）：</p>
 * <ul>
 * <li><b>执行</b>：改角色 current_activity/location + 写 actor_action_log 时间线节点 + SSE 广播；</li>
 * <li><b>事件化</b>：执行成功后经 {@link WorldEventBus#publish} 发布 action 事件
 * （kind=action、visibility=location、payload=决策 JSON、source_key=plan:{id} 幂等）；
 * 发布开关关闭时 publish 返回 null（回到 B1 前行为）；</li>
 * <li><b>记忆沉淀</b>：行动重要度（决策 urgency，缺省回退角色重要度）≥阈值（默认 4）
 * 为当事人写 observation 经历记忆（special:{id} 双轨）；</li>
 * <li><b>trace</b>：无外层 withTrace 时自开 trace_id，执行/事件发布/记忆沉淀环节
 * </ul>
 * <p>普通 NPC 批量行动（crowd 状态机收敛到本组件）与行动效果建模 留后续阶段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentActionService {

    /** 字段最大长度（角色状态持久化） */
    private static final int FIELD_MAX = 255;

    private final ActorCharacterRepository characterRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorActionLogRepository logRepository;
    private final ActorProjectRepository projectRepository;
    private final ActionSseHub actionSseHub;
    private final CurrentUserProvider currentUserProvider;
    private final WorldEventBus worldEventBus;
    private final AgentMemoryService agentMemoryService;
    private final ObjectMapper objectMapper;

    /** 学习组件（行动挂钩：字段注入保持构造器不变，存量单测零改动） */
    @Autowired(required = false)
    private LearningService learningService;

    /** 位置归一（字段注入保持构造器不变；行动 move 目标归一落 location_id） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.LocationNormalizationService locationNormalizationService;

    /** 世界沙盘 S1：move 行动挂钩（字段注入保持构造器不变；角色位置变更 → 沙盘状态坐标更新落库） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService worldSandboxStateService;

    /**
     * 连续移动状态机（位置移动与地图生成系统 · M2：move 行动 → 发起真实移动[walking→arrived 插值推进]；
     * 字段注入保持构造器不变，null=未装配[单测]回退既有 onNpcMoved 瞬移语义——零破坏既有行为）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /** 状态；字段注入+try 隔离对齐 onNpcMoved） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /**
     * 链式反应服务（实时行为决策系统：行动执行后触发同地点其他特殊 NPC + 关系密切异地相关人物
     * CHAIN_REACTION 完整即时决策 + 人群重大行动围观零 AI；@Lazy 破 Bean 环[经 AgentRuntime→
     * 实时决策服务→本服务]，null=未装配跳过链式）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.ChainReactionService chainReactionService;

    /**
     * 宏观社会演化系统：微观入账挂钩（NPC 行动→宏观指标折算流水：规则映射
     * 同步写 + 重大行动[urgency≥4]事务提交后异步 AI 判定叠加；字段注入保持构造器不变，
     * null=未装配跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MacroLedgerService macroLedgerService;

    /**
     * 中观协调服务（：行动执行后指令响应回收——仅特殊 NPC 有效指令时零成本判定；
     * 字段注入保持构造器不变，null=未装配[单测]跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MesoCoordinationService mesoCoordinationService;

    /**
     * 冲突协调服务（行动执行前冲突检测/资源登记/协商——本路径为事务内
     * 确定性兜底[allowAi=false]，AI 协商由 AgentRuntime 事务外路径承担；null=未装配[单测]跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.ActionCoordinatorService actionCoordinatorService;

    /**
     * 决策 state delta 服务（行动执行完成后落显式状态变更记录[幂等键=plan id]；
     * null=未装配[单测]跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.StateDeltaService stateDeltaService;

    /** 世界时钟 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /**
     * 作息运行态服务。
     * <p>长跑缺陷 修复配套：{@code actor_state.activity_state} 此前只有
     * 两个写入方——{@code NpcStateService.applyCrowdAction}（人群程序化路径）与
     * {@code WorldEngine.programmaticUpdate}（作息程序化路径），而
     * {@code holzyn.actor.growth.dispatch.all-npc-hourly=true}（全员走 AI 决策）时这两个路径
     * <b>都不再执行</b>→ 实测 23/23 角色 {@code activity_state} 恒为初始值 {@code idle}，
     * 导演视角与感知读方看不到「此刻在做什么」。本类是所有 AI 行动的唯一执行出口，
     * 故在此按映射表唯一来源 {@code toActivityState} 回写。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcActivityStateService npcActivityStateService;

    /** 行动记忆沉淀重要度阈值（B4：行动重要度≥该值写 observation 记忆，默认 4；行动重要度=决策 urgency） */
    @Value("${holzyn.actor.memory.action-importance-threshold:4}")
    private int actionMemoryImportanceThreshold;

    /** 动态关系开关（：行动挂钩前置门控；false 不解析目标角色省查询） */
    @Value("${holzyn.actor.learning.relations.enabled:true}")
    private boolean relationsEnabled;

    /** 行动 type → 好感增量映射（启发式：正向互动加分/冲突减分/中性 0） */
    private static final Map<String, Integer> ACTION_DELTA = Map.of(
            "help", 3, "interact", 3, "trade", 3,
            "speak", 1, "rest", 1,
            "fight", -4, "flee", -2);

    /**
     * 执行行动计划（scheduled 定时任务与立即触发共用）。
     * <p>与旧 {@code ActionEngine.execute} 行为一致（原样收编）；B5：无外层 withTrace 时
     * 自开 trace_id，执行/事件发布/记忆沉淀非 AI 环节经 {@link AiCallContext#recordStep}
     * 记 step_type=action/event/memory。</p>
     *
     * @param plan 行动计划（planned/executing）
     */
    @Transactional
    public void execute(ActorActionPlan plan) {
        boolean ownsTrace = AiCallContext.currentTraceId() == null;
        if (ownsTrace) {
            AiCallContext.beginTrace();
        }
        plan.setStatus("executing");
        plan.setExecutedAt(LocalDateTime.now());
        planRepository.save(plan);
        long execStart = System.currentTimeMillis();
        try {
            JsonNode decision = objectMapper.readTree(plan.getActionJson());
            // 冲突协调（事务内确定性兜底路径：覆盖手动/恢复/定时等绕过 AgentRuntime
            // 决策循环的直达执行；AI 协商由 AgentRuntime 事务外路径承担——同步 AI 不进事务，
            // S4 教训）；后的决策节点替换执行（冲突记录落 actor_conflict_record）
            if (actionCoordinatorService != null) {
                try {
                    Long pid = planProjectId(plan);
                    if (pid != null) {
                        var adj = actionCoordinatorService.resolveConflict(pid,
                                com.holzyn.actor.common.NpcRefs.of(plan.getCharacterId()), null, decision,
                                currentGameSecond(pid), false);
                        if (adj != null && adj.changed() && adj.decision() != null) {
                            decision = objectMapper.valueToTree(adj.decision());
                            log.info("[Action] 冲突协调调整行动: plan={} char={} 新动作={}",
                                    plan.getId(), plan.getCharacterId(),
                                    truncate(decision.path("action").asText(""), 60));
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Action] 冲突协调失败（原样执行）: plan={} : {}", plan.getId(), e.getMessage());
                }
            }
            String action = decision.path("action").asText("");
            String target = decision.path("target").asText("");
            String type = decision.path("type").asText("custom");
            String reason = decision.path("reason").asText("");

            // 角色状态：current_activity = 动作描述；location = 目标（优先 params.move.to）
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            // state delta：执行前角色状态快照（与执行后 diff 落 changes_json）
            var stateBefore = stateDeltaService != null && ch != null
                    ? stateSnapshot(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId())) : null;
            String locationBefore = ch == null ? null : ch.getLocation();
            if (ch != null) {
                ch.setCurrentActivity(truncate(action));
                String loc = target;
                JsonNode moveTo = decision.path("params").path("move").path("to");
                if (moveTo != null && !moveTo.isMissingNode() && !moveTo.asText("").isBlank()) {
                    loc = moveTo.asText();
                }
                if (loc != null && !loc.isBlank()) {
                    ch.setLocation(truncate(loc));
                    // ：行动 move 目标按地点表归一（目标优先匹配地点表，未命中自由文本兜底）
                    ch.setLocationId(normalizeLocationId(ch.getProjectId(), ch.getLocation()));
                    // 位置移动与地图生成系统 · M2：move 行动 → 发起真实移动（walking→arrived 插值推进；
                    // 坐标权威由 MovementService.tick 驱动，SSE npc:walking/arrived）。装配时才真实移动，
                    // 未装配回退 S1 瞬移挂钩（onNpcMoved），零破坏既有行为（fail-open）。
                    if (movementService != null) {
                        try {
                            movementService.startMove(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                                    "special", com.holzyn.actor.domain.worldmap.entity.ActorMovement.REASON_DECISION,
                                    ch.getLocationId(), ch.getLocation(), currentGameSecond(ch.getProjectId()));
                        } catch (Exception e) {
                            log.warn("[Action] 真实移动发起失败（回退瞬移落位）: char={} : {}", ch.getId(), e.getMessage());
                            fallbackOnNpcMoved(ch, action);
                        }
                    } else {
                        fallbackOnNpcMoved(ch, action);
                    }
                }
                characterRepository.save(ch);
                // 长跑缺陷 修复：AI 行动 → actor_state.activity_state 回写。
                // 背景：all-npc-hourly=true（全员走 AI 决策）时，程序化两条路径（applyCrowdAction /
                // programmaticUpdate）都不再执行，activity_state 没有任何写入方 → 实测 23/23 恒 idle。
                // 口径：映射表唯一来源 toActivityState（walk/rest/talk/idle），与 last_action 同帧落库；
                // 失败零阻断（作息运行态是旁路增强，不影响行动主流程）。
                if (npcActivityStateService != null) {
                    try {
                        npcActivityStateService.setActivity(ch.getProjectId(),
                                com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                                com.holzyn.actor.domain.npc.state.service.NpcActivityStateService
                                        .toActivityState(ch.getCurrentActivity()),
                                truncate(action));
                    } catch (Exception e) {
                        log.warn("[Action] 行动态回写 actor_state 失败（隔离继续）: char={} : {}",
                                ch.getId(), e.getMessage());
                    }
                }
            }

            // 状态
            // 关键词匹配恢复映射；字段注入 + try 隔离，失败不影响行动主流程）
            if (npcStateService != null && ch != null) {
                try {
                    npcStateService.onNpcAction(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), "special",
                            action, type, null);
                } catch (Exception e) {
                    log.warn("[Action] 状态恢复挂钩失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }

            // 决策 state delta：行动执行完成后落显式状态变更记录
            // （幂等键=plan id；changes_json=行动/位置/执行前后状态差值；打断恢复校验/调试回看数据源）
            if (stateDeltaService != null && ch != null && ch.getProjectId() != null) {
                try {
                    var stateAfter = stateSnapshot(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()));
                    stateDeltaService.record(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                            plan.getId(), decision, locationBefore, stateBefore, stateAfter,
                            currentGameSecond(ch.getProjectId()));
                } catch (Exception e) {
                    log.warn("[Action] state delta 记录失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }

            // 写行动日志（时间线节点）
            ActorActionLog logEntry = new ActorActionLog();
            logEntry.setCharacterId(plan.getCharacterId());
            logEntry.setPlanId(plan.getId());
            logEntry.setSummary(truncate(action));
            logEntry.setDetail(reason + (type == null || type.isBlank() ? "" : "（类型：" + type + "）"));
            logEntry.setLogTime(LocalDateTime.now());
            // P0-03 决策源可追溯：行动日志透传决策来源（AI/程序化兜底/预算降级；缺失不产生行动日志）
            com.holzyn.actor.domain.agent.runtime.DecisionSource ds =
                    com.holzyn.actor.domain.agent.runtime.DecisionSources.of(decision);
            if (ds != null) {
                logEntry.setDecisionSource(ds.name());
            }
            logRepository.save(logEntry);

            plan.setStatus("done");
            planRepository.save(plan);
            log.info("[Action] 行动执行完成：plan={} char={} 动作={}", plan.getId(), plan.getCharacterId(), truncate(action, 40));

            // SSE 广播行动事件（时间线实时刷新）
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "action");
            event.put("planId", plan.getId());
            event.put("characterId", plan.getCharacterId());
            event.put("action", action);
            event.put("target", target);
            event.put("reason", reason);
            event.put("triggerType", plan.getTriggerType());
            event.put("status", "done");
            event.put("time", LocalDateTime.now().toString());
            actionSseHub.broadcast(event);

            // B5：行动执行环节（非 AI）记 trace 行
            AiCallContext.recordStep(ch == null ? null : ch.getProjectId(), "action", "action",
                    "行动执行：改状态/写日志/广播（plan:" + plan.getId() + " " + truncate(action, 60) + "）",
                    System.currentTimeMillis() - execStart);

            // B1（世界事件总线）：发布行动事件（kind=action，修断链1 L-01）；幂等键 plan:{id}。
            publishActionEvent(plan, decision);

            // B4（行动→记忆沉淀）：行动重要度≥阈值写 observation 经历记忆；低重要度不沉淀。
            recordActionMemory(plan, decision);

            // （L1 工作记忆）：行动执行后写一条 task 关注点（当前行动/目标；会话热缓冲注入用）
            recordWorkingTask(plan, decision);

            // （行动→关系好感）：决策 target 命中项目内角色时，按行动 type 启发式增减好感
            // （帮助/互动/交易正向、冲突/逃跑负向、移动/自定义中性 0；0 仅刷新互动时间不新建行）。
            applyActionRelationDelta(ch, decision);

            // 链式反应：行动执行后 → 同地点其他特殊 NPC +
            // 关系密切（好感/信任≥阈值）异地相关人物触发 CHAIN_REACTION 完整即时决策（一跳防环：
            // 链式产出的响应行动不再传播）；重大行动（urgency≥阈值）同地点人群程序化围观（零 AI）。
            // 旁路增强：失败仅告警不阻断行动执行主流程。
            if (chainReactionService != null && ch != null) {
                try {
                    chainReactionService.onActionExecuted(ch, plan, decision);
                } catch (Exception e) {
                    log.warn("[Action] 链式反应挂钩失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }

            // 宏观社会演化系统：微观入账——NPC 行动折算宏观指标（规则映射同步
            // 写流水 + 重大行动[urgency≥4]事务提交后异步 AI 判定叠加 scene=macro_impact 独立预算；
            // 旁路增强：失败仅告警不阻断行动执行主流程）
            if (macroLedgerService != null && ch != null && ch.getProjectId() != null) {
                try {
                    int fallbackUrgency = ch.getImportance() == null ? 3 : ch.getImportance();
                    int urgency = decision == null ? fallbackUrgency
                            : decision.path("urgency").asInt(fallbackUrgency);
                    macroLedgerService.recordAction(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                            ch.getName(), action, type, target, urgency, null);
                } catch (Exception e) {
                    log.warn("[Action] 宏观入账挂钩失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }

            // ：中观指令响应回收——该角色所属组织近期指令 vs 本次行动 → comply/partial/defy/ignore
            // （仅特殊 NPC；无有效指令零成本返回；旁路增强：失败仅告警不阻断行动执行主流程）
            if (mesoCoordinationService != null && ch != null && ch.getProjectId() != null) {
                try {
                    mesoCoordinationService.recordActionResponse(ch.getProjectId(),
                            com.holzyn.actor.common.NpcRefs.of(ch.getId()), ch.getId(), action, type, null);
                } catch (Exception e) {
                    log.warn("[Action] 中观响应回收挂钩失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Action] 行动执行失败: plan={} : {}", plan.getId(), e.getMessage());
            plan.setStatus("cancelled");
            planRepository.save(plan);
        } finally {
            if (ownsTrace) {
                AiCallContext.endTrace();
            }
        }
    }

    /**
     * 发布行动世界事件（，原样迁入）。
     * <p>kind=action、source=action、visibility=location（同地点知晓，地点名取角色当前所在地快照）、
     * characterRefs=[special:{id}]、payload=行动决策 JSON、source_key=plan:{id}（幂等，防重复发布）。
     * 发布失败仅告警不阻断行动执行（事件流是旁路增强）。</p>
     */
    private void publishActionEvent(ActorActionPlan plan, JsonNode decision) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return;
            }
            Long projectId = ch.getProjectId();
            Long ownerId = projectRepository.findById(projectId)
                    .map(ActorProject::getUserId)
                    .orElse(currentUserProvider.currentUserId());
            String action = decision == null ? "" : decision.path("action").asText("");
            String target = decision == null ? "" : decision.path("target").asText("");
            String reason = decision == null ? "" : decision.path("reason").asText("");
            String type = decision == null ? "custom" : decision.path("type").asText("custom");
            String title = (ch.getName() == null ? "角色" : ch.getName())
                    + "：" + truncate(action, 80);
            StringBuilder content = new StringBuilder(action);
            if (target != null && !target.isBlank()) {
                content.append("（目标：").append(target).append("）");
            }
            if (reason != null && !reason.isBlank()) {
                content.append("\n理由：").append(reason);
            }
            worldEventBus.publish(ownerId, projectId, EventSpec.builder()
                    .kind("action")
                    .source("action")
                    .characterId(plan.getCharacterId())
                    .characterRefs(List.of(com.holzyn.actor.common.NpcRefs.of(plan.getCharacterId())))
                    .locationName(truncate(ch.getLocation(), 100))
                    .visibility("location")
                    .title(title)
                    .content(content.toString())
                    .payloadJson(plan.getActionJson())
                    // P0-03 决策源可追溯：行动事件透传决策来源
                    .decisionSource(sourceName(decision))
                    .sourceKey("plan:" + plan.getId())
                    .build());
            // B5：行动事件发布环节（非 AI）记 trace 行
            AiCallContext.recordStep(projectId, "action", "event",
                    "行动事件发布（kind=action source_key=plan:" + plan.getId() + "）", 0);
            log.debug("[Action] 行动事件已发布：plan={} char={} type={}", plan.getId(), plan.getCharacterId(), type);
        } catch (Exception e) {
            log.warn("[Action] 行动事件发布失败: plan={} : {}", plan.getId(), e.getMessage());
        }
    }

    /**
     * 行动→记忆沉淀（，原样迁入）：行动重要度≥阈值（默认 4）为当事人写 observation 经历记忆。
     * <p>行动重要度取决策 urgency（1-5，AI 决策的紧急度；程序化兜底=角色重要度），
     * 内容为「动作（目标：…）」；写入经 {@link AgentMemoryService#recordObservation}
     * （special:{id} 双轨 + 去重 + 预算淘汰）。失败仅告警不阻断行动执行。</p>
     */
    private void recordActionMemory(ActorActionPlan plan, JsonNode decision) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return;
            }
            int fallbackUrgency = ch.getImportance() == null ? 3 : ch.getImportance();
            int urgency = decision == null ? fallbackUrgency
                    : decision.path("urgency").asInt(fallbackUrgency);
            if (urgency < actionMemoryImportanceThreshold) {
                return; // 低重要度行动不沉淀（阈值控制记忆膨胀）
            }
            String action = decision == null ? "" : decision.path("action").asText("");
            if (action.isBlank()) {
                return;
            }
            String target = decision == null ? "" : decision.path("target").asText("");
            String content = action + (target.isBlank() ? "" : "（目标：" + target + "）");
            // P0-03 决策源可追溯：记忆沉淀透传决策来源（兜底/预算降级产出打标，学习管线据此隔离）
            agentMemoryService.recordObservation(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                    truncate(content, FIELD_MAX), Math.max(1, Math.min(5, urgency)),
                    com.holzyn.actor.domain.agent.runtime.DecisionSources.of(decision));
            log.debug("[Action] 行动记忆已沉淀：plan={} char={} 重要度={}", plan.getId(), plan.getCharacterId(), urgency);
        } catch (Exception e) {
            log.warn("[Action] 行动记忆沉淀失败: plan={} : {}", plan.getId(), e.getMessage());
        }
    }

    /**
     * 行动→L1 工作记忆：行动执行后写一条 task 工作项
     * （「当前行动：{动作}（目标：…）」，重要度=决策 urgency），供会话热缓冲注入与会话结束巩固。
     * 失败仅告警不阻断行动执行（L1 为旁路增强）。
     */
    private void recordWorkingTask(ActorActionPlan plan, JsonNode decision) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return;
            }
            String action = decision == null ? "" : decision.path("action").asText("");
            if (action.isBlank()) {
                return;
            }
            String target = decision == null ? "" : decision.path("target").asText("");
            int fallbackUrgency = ch.getImportance() == null ? 3 : ch.getImportance();
            int urgency = decision == null ? fallbackUrgency
                    : decision.path("urgency").asInt(fallbackUrgency);
            String content = "当前行动：" + action + (target.isBlank() ? "" : "（目标：" + target + "）");
            agentMemoryService.recordWorkingTask(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                    truncate(content, FIELD_MAX), Math.max(1, Math.min(5, urgency)));
            log.debug("[Action] 行动 L1 关注点已写：plan={} char={} 重要度={}", plan.getId(), plan.getCharacterId(), urgency);
        } catch (Exception e) {
            log.warn("[Action] 行动 L1 关注点写入失败: plan={} : {}", plan.getId(), e.getMessage());
        }
    }

    /**
     * 行动→关系好感挂钩：决策 target 命中项目内角色（特殊/普通 NPC，名称解析）时，
     * 按行动 type 映射好感增量单向应用（行动方 → 目标方）；命中失败/中性 0/关闭开关不扰动。
     * 失败仅告警不阻断行动执行（关系演化为旁路增强）。
     *
     * @param ch 行动角色实体（null 跳过）
     *
     * @param decision 决策 JSON（type/target/action 字段）
     */
    private void applyActionRelationDelta(ActorCharacter ch, JsonNode decision) {
        if (!relationsEnabled || learningService == null || ch == null || ch.getProjectId() == null) {
            return;
        }
        try {
            String target = decision == null ? "" : decision.path("target").asText("");
            if (target.isBlank()) {
                return;
            }
            String type = decision == null ? "custom" : decision.path("type").asText("custom");
            int delta = ACTION_DELTA.getOrDefault(type, 0);
            String action = decision == null ? "" : decision.path("action").asText("");
            learningService.updateRelation(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), target, delta,
                    "行动：" + truncate(action, 80));
        } catch (Exception e) {
            log.warn("[Action] 行动→关系好感挂钩失败: char={} : {}", ch.getId(), e.getMessage());
        }
    }

    /**
     * S1 瞬移落位兜底（未装配 MovementService / 真实移动发起失败）：世界沙盘
     * 角色地点变更 → 沙盘状态坐标/朝向/动作/游戏时刻更新落库（既有 onNpcMoved 语义，零破坏）。
     *
     * @param ch 角色实体（location 已更新为目标地点）
     *
     * @param action 动作描述（可空）
     */
    private void fallbackOnNpcMoved(ActorCharacter ch, String action) {
        if (worldSandboxStateService != null && ch != null && ch.getProjectId() != null) {
            try {
                worldSandboxStateService.onNpcMoved(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                        "special", ch.getLocation(), truncate(action), null);
            } catch (Exception e) {
                log.warn("[Action] 沙盘状态更新失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * / 辅助：计划所属项目（按角色实体解析；角色缺失返回 null）。
     */
    private Long planProjectId(ActorActionPlan plan) {
        if (plan == null || plan.getCharacterId() == null) {
            return null;
        }
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            return ch == null ? null : ch.getProjectId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * / 辅助：当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。
     */
    private Long currentGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 辅助：角色状态快照（state delta 执行前后 diff 用；状态服务未装配/行缺失返回 null）。
     */
    private com.holzyn.actor.domain.npc.state.entity.ActorNpcState stateSnapshot(Long projectId, String npcRef) {
        if (npcStateService == null) {
            return null;
        }
        try {
            return npcStateService.stateFor(projectId, npcRef);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 字符串截断到 255 字符（角色状态字段长度约束）。
     */
    private String truncate(String s) {
        return truncate(s, FIELD_MAX);
    }

    /**
     * 字符串截断到指定长度（角色状态/事件标题/地点快照字段约束）。
     */
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

    /**
     * 决策来源列值（P0-03）：从行动决策 JSON 读 {@code DecisionSource} 枚举名；
     * 未打标/非法 → null（事件/记忆留空，向后兼容旧数据）。
     *
     * @param decision 行动决策 JSON（可空）
     *
     * @return 枚举名或 null
     */
    private static String sourceName(tools.jackson.databind.JsonNode decision) {
        com.holzyn.actor.domain.agent.runtime.DecisionSource ds =
                com.holzyn.actor.domain.agent.runtime.DecisionSources.of(decision);
        return ds == null ? null : ds.name();
    }

    /**
     * 位置归一落地点 ID（；归一服务缺失/未命中返回 null，自由文本兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param location 自由文本位置（可空）
     *
     * @return 地点表 ID 或 null
     */
    private Long normalizeLocationId(Long projectId, String location) {
        if (locationNormalizationService == null || projectId == null || location == null || location.isBlank()) {
            return null;
        }
        try {
            return locationNormalizationService.normalizedId(projectId, location);
        } catch (Exception e) {
            log.warn("[位置归一] 行动目标位置归一失败（保留自由文本）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }
}
