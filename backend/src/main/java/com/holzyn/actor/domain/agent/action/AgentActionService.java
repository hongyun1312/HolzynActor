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
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
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
import org.springframework.transaction.annotation.Propagation;
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

    /** 行动事件仓库（裁决回填 event_id；字段注入保持既有构造器不变） */
    @Autowired(required = false)
    private ActorEventRepository eventRepository;

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

    /** 主动性到达通知（@Lazy 破环；null=未装配跳过）。 */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.agent.initiative.service.NpcInitiativeService initiativeService;

    /**
     * 目标引用解析器（三方对账：AI 只给「去哪」，坐标由服务端算）。
     * 字段注入保持构造器不变；null=未装配[单测]时跳过权威落点，既有行为不变。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.TargetResolver targetResolver;

    /**
     * 米制地图移动引擎（{@code actor_npc_position} 的发起入口）。让 AI 决策也走这条管线：
     * 与日程移动同一个引擎、同一份坐标权威——这正是「AI 让人去哪」与「图上人在哪」合一的关键。
     * 字段注入保持构造器不变；null=未装配[单测]时只驱动旧沙盘域。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcMovementService npcMovementService;

    /**
     * AI 意图驻留锁：AI 决定「去哪」之后，日程不得立刻把人抢走。
     * 字段注入保持构造器不变；null=未装配[单测]时不设窗口（行为与旧版一致）。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcIntentLock intentLock;

    /** 意图驻留窗口（游戏秒）：到站后留多少时间让「他确实是为这件事来的」体现出来。 */
    private long dwellGameSeconds() {
        long minutes = 30L;
        if (npcMovementService != null) {
            minutes = Math.max(0L, npcMovementService.intentDwellGameMinutes());
        }
        return minutes * 60L;
    }

    /**
     * 决策台账（落点解析留痕回填）。字段注入保持构造器不变；null=未装配时不留痕。
     *
     * <p>用 {@code @Lazy}：本类在 AgentRuntime → ChainReaction → 实时决策 的调用链里，
     * 台账服务依赖面较宽，直接注入存在成环风险（`AGENTS.md` §8.1 第 5 条）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.DecisionLedgerService decisionLedgerService;

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
        // 已删除角色的存量计划必须就地取消：角色删除是软删（deleted=1），若只过滤决策派发，
        // 删除前排下的 planned 计划仍会被定时执行器/等待计划扫到，出现「已删除的人还在镇上行动」
        // 的幽灵行为，并挤占在场角色的行动预算。执行入口是唯一收口点，在这里取消一次即可。
        ActorCharacter planChar = characterRepository.findById(plan.getCharacterId()).orElse(null);
        if (planChar == null || Integer.valueOf(1).equals(planChar.getDeleted())) {
            plan.setStatus("cancelled");
            plan.setExecutedAt(LocalDateTime.now());
            planRepository.save(plan);
            log.info("[Action] 取消已删除角色的行动计划: plan={} char={}", plan.getId(), plan.getCharacterId());
            return;
        }
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
            String action = com.holzyn.actor.domain.agent.runtime.DecisionLedgerService
                    .cleanActionText(decision.path("action").asText(""));
            // 解包后仍无人话（模型把契约塞成了抽不出字符串的结构/字段全空）时，
            // 不能把它当成一个「动作」执行——否则空活动写进 current_activity、空标题进事件、
            // 空目的地进入路线解析。就地取消并留原因码，绝不伪造行动（宁可这一小时什么都没发生，
            // 也不产出一条人类读不懂的世界事件）。
            if (action == null || action.isBlank()) {
                plan.setStatus("cancelled");
                plan.setReasonCode("ACTION_TEXT_EMPTY");
                plan.setExecutedAt(LocalDateTime.now());
                planRepository.save(plan);
                log.warn("[Action] 决策动作文本为空（不可解包），取消计划不执行: plan={} char={}",
                        plan.getId(), plan.getCharacterId());
                return;
            }
            String target = decision.path("target").asText("");
            String type = decision.path("type").asText("custom");
            String reason = decision.path("reason").asText("");

            // 角色状态：current_activity = 动作描述；location = 目标（优先 params.move.to）
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            // state delta：执行前角色状态快照（与执行后 diff 落 changes_json）
            var stateBefore = stateDeltaService != null && ch != null
                    ? stateSnapshot(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId())) : null;
            String locationBefore = ch == null ? null : ch.getLocation();
            boolean authorityArrived = false;
            if (ch != null) {
                ch.setCurrentActivity(truncate(action));
                String loc = target;
                JsonNode moveTo = decision.path("params").path("move").path("to");
                if (moveTo != null && !moveTo.isMissingNode() && !moveTo.asText("").isBlank()) {
                    loc = moveTo.asText();
                }
                // 角色目标 ≠ 地点：speak_to/interact/trade 的 target 是「人」，直接把人物名写进
                // location 会把角色变成「住在沈伯远里」（实测 npc:65 行动后 location=沈伯远），
                // 后续【当前位置与方向】/同地点可见性/事实过滤全部失真。口径：
                // target 命中项目内角色名 → 采用该角色当前所在地；对方无地点或是自己 → 不改位置。
                boolean personTarget = false;
                if (loc != null && !loc.isBlank()) {
                    String personLoc = personLocationOf(ch.getProjectId(), loc, ch.getId());
                    if (personLoc != null) {
                        personTarget = true;
                        if (personLoc.isBlank()) {
                            log.info("[Action] 行动目标是角色且无已知位置，位置保持原值: char={} target={}",
                                    ch.getId(), loc);
                            loc = null;
                        } else {
                            loc = personLoc;
                        }
                    }
                }
                // 行动计划里 target 经常是空串——实测 type=move 的
                // 「顺路去米行讨碗水喝」target=""，执行侧见空目标直接跳过移动，于是
                // AI 说去哪、地图上的人一步不动。目的地就在动作文本里，剥一次比再调一次模型便宜；
                // 剥不出来就老实不动（绝不按地名哈希造坐标）。
                // 闭环实测：目标是角色且对方无地点时**不做文本兜底**——
                // 「向沈伯远问安」会被 TargetResolver 剥成「沈伯远」，又把人物名写回位置。
                if (!personTarget && (loc == null || loc.isBlank())) {
                    String derived = com.holzyn.actor.domain.mapnav.service.TargetResolver
                            .destinationText(action);
                    if (!derived.isEmpty()) {
                        loc = derived;
                        log.info("[Action] 行动未给 target，从动作文本取目的地: char={} action={} → {}",
                                ch.getId(), truncate(action, 40), derived);
                    }
                }
                // 地点写入规范化（整世界长跑实测）：决策的 target 从动作文本剥出的
                // 目的地经常是动作片段（「椅背」「肩上拉了拉」「灶上烧水」），直接写进 location
                // 会让角色「住在椅背里」——事实可见性、同地点自主对话、交易柜台、制作设施全部
                // 按地名比对，写成不存在的地名就静默落空（不报错，只是世界看起来正常却什么都不发生）。
                // 口径：只有能归一到地点表的名字才允许改写位置；归不上就保持原值、也不发起移动。
                if (loc != null && !loc.isBlank()) {
                    String canonical = canonicalLocation(ch.getProjectId(), loc);
                    if (canonical == null) {
                        log.info("[Action] 目的地不是已知地点，位置保持不变: char={} action={} target={}",
                                ch.getId(), truncate(action, 30), truncate(loc, 20));
                        loc = null;
                    } else {
                        loc = canonical;
                    }
                }
                if (loc != null && !loc.isBlank()) {
                    ch.setLocation(truncate(loc));
                    // 行动 move 目标已按地点表归一（命中=规范名；未命中上面已拦下）
                    ch.setLocationId(normalizeLocationId(ch.getProjectId(), ch.getLocation()));
                    // 三方对账：**权威先写**——把「AI 让人去哪」落到
                    // actor_npc_position 这条唯一权威上。
                    // （单一真相）：权威移动建立成功后**不再**发起沙盘移动。此前一次行动
                    // 同时写 actor_movement（沙盘 0..500）与 actor_npc_path（米制权威）两份轨迹，
                    // 感知/渲染/对账读权威、途中遭遇读沙盘，两套坐标各说一套。现在沙盘只在
                    // 「权威建不起来」（无地图 / 目标解析失败）时作为旧世界兼容通道。
                    boolean authorityStarted = moveByAuthority(ch, loc, currentGameSecond(ch.getProjectId()));
                    authorityArrived = authorityStarted;
                    if (!authorityStarted) {
                        log.info("[Action] 权威落点未建立（目标未解析或寻路失败），位置保持原值: char={} target={}",
                                ch.getId(), loc);
                        // 位置移动与地图生成系统：旧沙盘移动（walking→arrived 插值推进；
                        // 坐标由 MovementService.tick 驱动，SSE npc:walking/arrived）。
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
                    } else if (movementService != null) {
                        log.info("[Action] 权威移动已建立，跳过沙盘移动（单一真相）: char={} target={}",
                                ch.getId(), truncate(loc, 30));
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
            // 保留事件 ID 供 EffectApplier 写 fact_log.source_event_id（先发事件再落效果）。
            Long actionEventId = publishActionEvent(plan, decision);
            if (actionEventId != null) {
                plan.setEventId(actionEventId);
                planRepository.save(plan);
            }
            // Authority 落位是瞬时到达；沙盘 walking 由 onNpcMoved 在真实到达时回调。
            if (authorityArrived && initiativeService != null
                    && "initiative".equalsIgnoreCase(plan.getTriggerType())) {
                initiativeService.onAuthorityArrived(plan);
            }

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
     * 带裁决结论执行行动计划。
     *
     * <p>FAIL：只取消计划并发布一条可见失败事件（kind=action、source=adjudication、
     * sourceKey=plan:{id}:verdict），不产生原行动效果；SUCCESS/PARTIAL：原样执行，
     * 并把 verdict/reason_code/effects_json/event_id 回填到 plan。</p>
     *
     * @param plan    行动计划
     * @param verdict 事务外算好的裁决（null=未裁决，等价旧 execute）
     */
    @Transactional
    public void executeWithVerdict(ActorActionPlan plan, ActionVerdict verdict) {
        if (verdict == null) {
            execute(plan);
            return;
        }
        if (!verdict.executable()) {
            plan.setStatus("cancelled");
            plan.setExecutedAt(LocalDateTime.now());
            applyVerdict(plan, verdict);
            Long eventId = publishFailureEvent(plan, verdict);
            plan.setEventId(eventId);
            planRepository.save(plan);
            log.info("[Action] 裁决 FAIL，行动中止: plan={} char={} reason={}",
                    plan.getId(), plan.getCharacterId(), verdict.reasonCode());
            return;
        }
        applyVerdict(plan, verdict);
        execute(plan);
        if ("done".equals(plan.getStatus())) {
            backfillEventId(plan);
        }
    }

    /**
     * 效果落地失败收口：整批效果已回滚，用新事务把计划置 cancelled 并写
     * {@code EFFECT_FAILED:{index}}；不发布可见失败事件（设计：事实层异常事件不落）。
     *
     * @param planId 计划 ID
     * @param index  失败 op 下标
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEffectFailed(Long planId, int index) {
        if (planId == null) {
            return;
        }
        ActorActionPlan plan = planRepository.findById(planId).orElse(null);
        if (plan == null) {
            return;
        }
        plan.setStatus("cancelled");
        plan.setReasonCode(truncate("EFFECT_FAILED:" + index, 40));
        plan.setExecutedAt(LocalDateTime.now());
        planRepository.save(plan);
        log.warn("[Action] 效果落地失败，计划取消: plan={} index={} reason=EFFECT_FAILED:{}",
                planId, index, index);
    }

    /** 把裁决四列写到 plan（effects 只落草稿，才应用）。 */
    private void applyVerdict(ActorActionPlan plan, ActionVerdict verdict) {
        plan.setVerdict(verdict.verdict().name().toLowerCase(java.util.Locale.ROOT));
        plan.setReasonCode(truncate(verdict.reasonCode(), 40));
        try {
            plan.setEffectsJson(verdict.effects().isEmpty()
                    ? "[]" : objectMapper.writeValueAsString(verdict.effects()));
        } catch (Exception e) {
            plan.setEffectsJson("[]");
        }
    }

    /** 执行成功后按 source_key 回查行动事件 id（唯一幂等键，避免再改 execute 返回值）。 */
    private void backfillEventId(ActorActionPlan plan) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return;
            }
            eventRepository.findTopByProjectIdAndSourceKey(ch.getProjectId(), "plan:" + plan.getId())
                    .ifPresent(event -> {
                        plan.setEventId(event.getId());
                        planRepository.save(plan);
                    });
        } catch (Exception e) {
            log.warn("[Action] 行动事件 id 回填失败（不影响执行）: plan={} : {}", plan.getId(), e.getMessage());
        }
    }

    /** 发布可见失败事件（不写行动完成日志、不改状态/位置）。 */
    private Long publishFailureEvent(ActorActionPlan plan, ActionVerdict verdict) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return null;
            }
            Long projectId = ch.getProjectId();
            Long ownerId = projectRepository.findById(projectId)
                    .map(ActorProject::getUserId)
                    .orElse(currentUserProvider.currentUserId());
            JsonNode decision = readDecision(plan.getActionJson());
            // 失败事件标题必须是人话——此前直接用原始 JSON 当标题
            // （`{ "action": "傍晚…" }（未成）`），时间线与编年史都被污染。
            String action = com.holzyn.actor.domain.agent.runtime.DecisionLedgerService
                    .cleanActionText(decision.path("action").asText(""));
            String title = (ch.getName() == null ? "角色" : ch.getName()) + "："
                    + (action == null || action.isBlank() ? "这趟没能成行" : truncate(action, 80)) + "（未成）";
            String content = verdict.observedText().isBlank()
                    ? "这件事没能做成。" : verdict.observedText();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("verdict", "fail");
            payload.put("reasonCode", verdict.reasonCode());
            payload.put("observedText", verdict.observedText());
            payload.put("planId", plan.getId());
            payload.put("adjudicatedBy", verdict.adjudicatedBy());
            ActorEvent event = worldEventBus.publish(ownerId, projectId, EventSpec.builder()
                    .kind("action")
                    .source("adjudication")
                    .characterId(plan.getCharacterId())
                    .characterRefs(List.of(com.holzyn.actor.common.NpcRefs.of(plan.getCharacterId())))
                    .locationName(truncate(ch.getLocation(), 100))
                    .visibility("location")
                    .title(title)
                    .content(content + "\n原因：" + verdict.reasonCode())
                    .payloadJson(objectMapper.writeValueAsString(payload))
                    .sourceKey("plan:" + plan.getId() + ":verdict")
                    .build());
            return event == null ? null : event.getId();
        } catch (Exception e) {
            log.warn("[Action] 失败事件发布失败（计划仍取消）: plan={} : {}", plan.getId(), e.getMessage());
            return null;
        }
    }

    /** 解析计划 JSON；坏 JSON 返回空对象（失败事件仍需可发）。 */
    private JsonNode readDecision(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 发布行动世界事件（，原样迁入）。
     * <p>kind=action、source=action、visibility=location（同地点知晓，地点名取角色当前所在地快照）、
     * characterRefs=[special:{id}]、payload=行动决策 JSON、source_key=plan:{id}（幂等，防重复发布）。
     * 发布失败仅告警不阻断行动执行（事件流是旁路增强）。</p>
     */
    private Long publishActionEvent(ActorActionPlan plan, JsonNode decision) {
        try {
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch == null || ch.getProjectId() == null) {
                return null;
            }
            Long projectId = ch.getProjectId();
            Long ownerId = projectRepository.findById(projectId)
                    .map(ActorProject::getUserId)
                    .orElse(currentUserProvider.currentUserId());
            String action = com.holzyn.actor.common.ActionTexts.unwrap(
                    decision == null ? "" : decision.path("action").asText(""));
            if (action == null) {
                action = "";
            }
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
            EventSpec.EventSpecBuilder spec = EventSpec.builder()
                    .kind("action")
                    .source("action")
                    .characterId(plan.getCharacterId())
                    .characterRefs(List.of(com.holzyn.actor.common.NpcRefs.of(plan.getCharacterId())))
                    .locationName(truncate(ch.getLocation(), 100))
                    .visibility("location")
                    .title(title)
                    .content(content.toString())
                    .payloadJson(plan.getActionJson())
                    // 主动性计划把契约/传闻父事件挂到行动事件上；
                    // 闭环修复：AI 深思为到期事实做的行动带 _worldFact.sourceEventId，
                    // 同样挂父链（「对话/承诺 → NPC 决策 → 行动」不断边）
                    .causeEventId(actionCausalParent(decision))
                    // P0-03 决策源可追溯：行动事件透传决策来源
                    .decisionSource(sourceName(decision))
                    .sourceKey("plan:" + plan.getId());
            // 时间一致性：主动性计划的 _initiative.createdGameSecond 是「本次扫描/模拟时刻」，
            // 比执行瞬间持久化的世界时钟更接近事件真正发生的小时（引擎在 tick 结束后才拨钟）。
            // 缺省仍由事件总线从世界时钟解析，行为与既有路径一致。
            Long initiativeGs = initiativeGameSecond(decision);
            if (initiativeGs != null) {
                spec.gameHour(initiativeGs / 3600L);
            }
            ActorEvent published = worldEventBus.publish(ownerId, projectId, spec.build());
            // B5：行动事件发布环节（非 AI）记 trace 行。
            // 场景名必须与真实决策调用（scene=action）区分：否则 90% 以上的 action 日志是
            // step_type=event、input 为空的标记行，按场景做 Prompt/缓存分析时会被误当成决策调用。
            AiCallContext.recordStep(projectId, "action_event", "event",
                    "行动事件发布（kind=action source_key=plan:" + plan.getId() + "）", 0);
            log.debug("[Action] 行动事件已发布：plan={} char={} type={}", plan.getId(), plan.getCharacterId(), type);
            return published == null ? null : published.getId();
        } catch (Exception e) {
            log.warn("[Action] 行动事件发布失败: plan={} : {}", plan.getId(), e.getMessage());
            return null;
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
            String action = com.holzyn.actor.common.ActionTexts.unwrap(
                    decision == null ? "" : decision.path("action").asText(""));
            if (action == null || action.isBlank()) {
                return;
            }
            // 系统模板句不沉淀为「经历」——「觅食充饥」「…稍候片刻，稍后再去」是兜底
            // 与占位改写，不是角色想过的事。写进 L2 后每轮又被检索回来，实测形成自我强化的
            // 单调循环（21 条记忆含「觅食充饥」）。同一份黑名单见 MemoryWorkingService（L1→L2 复核）。
            if (com.holzyn.actor.common.ActionTexts.isRoutineTemplate(action)) {
                log.debug("[Action] 模板句不沉淀经历记忆: plan={} char={} 动作={}",
                        plan.getId(), plan.getCharacterId(), truncate(action, 30));
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
            String action = com.holzyn.actor.common.ActionTexts.unwrap(
                    decision == null ? "" : decision.path("action").asText(""));
            if (action == null || action.isBlank()) {
                return;
            }
            // 模板句不写 L1 关注点（否则会话结束巩固时又被提升进 L2，黑名单在下一层重复一次）
            if (com.holzyn.actor.common.ActionTexts.isRoutineTemplate(action)) {
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
            String action = com.holzyn.actor.common.ActionTexts.unwrap(
                    decision == null ? "" : decision.path("action").asText(""));
            learningService.updateRelation(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()), target, delta,
                    "行动：" + truncate(action == null ? "" : action, 80));
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
     * 落点闭环：AI 给的目标 → 服务端解析 → 写进位置权威（{@code actor_npc_position}）。
     *
     * <p><b>为什么坐标不能在模型那边算</b>：模型没有地图坐标系，它给出的 {@code (x,z)} 看着像村里、
     * 落到图上是墙里或水里。所以契约是「模型只说去哪」，由 {@code TargetResolver} 换成落点；
     * 换算过程（{@code resolvedBy}/{@code snapped}）回填决策台账，让「模型守不守约」变成可统计的数。</p>
     *
     * <p><b>为什么解析不到就什么都不做</b>：解析不到说明模型给了一个地图上不存在的地方。
     * 这时候凭空造一个坐标（比如「按地名哈希」）比不动更糟——错的位置会被后续所有感知、
     * 渲染与记忆当真。保持原位是唯一诚实的降级。</p>
     *
     * @param ch         角色
     *
     * @param loc        目标（AI 给的地点名/引用）
     *
     * @param gameSecond 当前游戏秒（可空）
     *
     * @return true=已在权威表上建立轨迹（位置会动）；false=未建立（角色保持原位）
     */
    private boolean moveByAuthority(ActorCharacter ch, String loc, Long gameSecond) {
        if (ch == null || targetResolver == null || npcMovementService == null
                || ch.getProjectId() == null || loc == null || loc.isBlank()) {
            return false;
        }
        try {
            com.holzyn.actor.domain.mapnav.service.TargetResolver.Resolved r =
                    targetResolver.resolve(ch.getProjectId(), loc.trim()).orElse(null);
            if (r == null) {
                log.debug("[Action] 目标引用无法解析（保持原位）: char={} target={}", ch.getId(), loc);
                return false;
            }
            long gs = gameSecond == null ? 0L : gameSecond;
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            // 路径行记「解析后的要素 code」而不是模型原话：到达时坐标行的 featureCode 就是它，
            // 后续【空间约束】/【空间选项】才查得到地点；模型原话（「米行」）只进日志。
            String ref = r.getFeatureCode() != null && !r.getFeatureCode().isBlank()
                    ? r.getFeatureCode() : r.getTargetRef();
            com.holzyn.actor.domain.mapnav.entity.ActorNpcPath path = npcMovementService.startMove(
                    ch.getProjectId(), npcRef, r.getX(), r.getZ(), ref, "decision", gs);
            if (decisionLedgerService != null) {
                decisionLedgerService.attachResolved(ch.getProjectId(), npcRef, gameSecond,
                        resolvedJson(r, path));
            }
            // （意图 vs 日程）：AI 让人去哪，就让他「到得了、站得住」——
            // 实测走到米行 100 游戏秒后就被日程叫走，等于 AI 的意图只是给日程让路。
            // 这里给意图加一个到站后的驻留窗口，日程在窗口内不抢人（见 NpcMovementService.tick）。
            if (path != null && intentLock != null) {
                long until = (path.getArriveGameSecond() == null ? gs : path.getArriveGameSecond())
                        + dwellGameSeconds();
                intentLock.hold(ch.getProjectId(), npcRef, until, ref, loc,
                        "special");
                log.info("[Action] AI 意图驻留窗口建立：char={} 目标={}（{}）至游戏秒 {}",
                        ch.getId(), truncate(loc, 24), ref, until);
            }
            return path != null;
        } catch (Exception e) {
            log.warn("[Action] 权威落点推进失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
            return false;
        }
    }

    /** 落点留痕 JSON（手工拼：字段少且固定，避免为一个观测字段引入序列化依赖）。 */    private static String resolvedJson(com.holzyn.actor.domain.mapnav.service.TargetResolver.Resolved r,
                                       com.holzyn.actor.domain.mapnav.entity.ActorNpcPath path) {
        long eta = path == null || path.getArriveGameSecond() == null ? 0L : path.getArriveGameSecond();
        double len = path == null || path.getLengthM() == null ? 0d : path.getLengthM();
        return String.format(java.util.Locale.ROOT,
                "{\"targetRef\":\"%s\",\"x\":%.2f,\"z\":%.2f,\"featureCode\":\"%s\","
                        + "\"resolvedBy\":\"%s\",\"snapped\":%s,\"pathLengthM\":%.2f,\"etaGameSecond\":%d}",
                safe(r.getTargetRef()), r.getX(), r.getZ(), safe(r.getFeatureCode()),
                safe(r.getResolvedBy()), r.isSnapped(), len, eta);
    }

    /** JSON 字符串字段转义（ref/名里极少有引号，但有就坏整串）。 */
    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
     * 行动事件的因果父事件（+ 闭环修复）。
     *
     * <p>优先级：{@code _initiative.causeEventId}（主动性计划携带的契约/传闻父事件）→
     * {@code _worldFact.sourceEventId}（AI 深思为到期事实做决策时挂上的来源事件）。
     * 两者都没有 → null（根事件，绝不伪造父链）。</p>
     *
     * @param decision 行动决策 JSON
     *
     * @return 父事件 ID；无因果引用返回 null
     */
    private static Long actionCausalParent(tools.jackson.databind.JsonNode decision) {
        Long initiative = causalField(decision, "_initiative", "causeEventId");
        return initiative != null ? initiative : causalField(decision, "_worldFact", "sourceEventId");
    }

    /**
     * 主动性计划的扫描/模拟游戏秒（{@code _initiative.createdGameSecond}）。
     *
     * <p>用于行动事件的 gameHour：引擎在 tick 结束后才把世界时钟拨到目标小时，
     * 若按执行瞬间的时钟写，会出现「扫描日志 16:00、事件显示 14:00」的时间错位。</p>
     *
     * @param decision 行动决策 JSON
     *
     * @return 游戏秒；非 initiative/缺失/非法返回 null
     */
    private static Long initiativeGameSecond(tools.jackson.databind.JsonNode decision) {
        return causalField(decision, "_initiative", "createdGameSecond");
    }

    /**
     * 从决策 JSON 的指定元数据节点读因果字段。
     *
     * @param decision 行动决策 JSON
     *
     * @param metaKey  元数据节点名（_initiative / _worldFact）
     *
     * @param field    字段名（causeEventId / sourceEventId）
     *
     * @return 事件 ID；缺失/非正数返回 null
     */
    private static Long causalField(tools.jackson.databind.JsonNode decision, String metaKey, String field) {
        if (decision == null) {
            return null;
        }
        tools.jackson.databind.JsonNode meta = decision.get(metaKey);
        if (meta == null || meta.isNull()) {
            return null;
        }
        tools.jackson.databind.JsonNode value = meta.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        long id = value.asLong(0L);
        return id > 0 ? id : null;
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
     * 目标文本是否命中项目内角色名；命中则返回该角色当前所在地。
     *
     * <p>用途：行动决策的 {@code target} 对 speak_to/interact/trade 是「人」而不是「地点」，
     * 执行侧不能把人物名当作新位置写入 {@code actor_profile.location}（实测会把角色
     * 变成「住在沈伯远里」，污染感知/同地点可见性/事实过滤）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param target    决策目标文本
     *
     * @param selfId    行动者自身 ID（同名/自己 → 返回空串=不改位置）
     *
     * @return null=不是角色名（按地点处理）；空串=是角色但无已知地点；其它=该角色所在地
     */
    private String personLocationOf(Long projectId, String target, Long selfId) {
        if (projectId == null || target == null || target.isBlank()) {
            return null;
        }
        String name = target.trim();
        try {
            for (ActorCharacter other : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                if (other == null || other.getName() == null || !name.equals(other.getName().trim())) {
                    continue;
                }
                if (selfId != null && selfId.equals(other.getId())) {
                    return "";
                }
                String loc = other.getLocation();
                return loc == null ? "" : loc.trim();
            }
        } catch (Exception e) {
            log.debug("[Action] 角色目标位置解析失败（按地点处理）: target={} : {}", target, e.getMessage());
        }
        return null;
    }

    /**
     * 位置严格归一（运行期改写位置前必须过这一关）：命中地点表返回规范名，归不上返回 null。
     *
     * <p>与 {@link #normalizeLocationId} 的区别：后者未命中时落 null 但仍把自由文本写进
     * {@code location}（创建期兜底）；运行期不能这样——「椅背」「肩上拉了拉」这类动作片段
     * 一旦进 location，所有按地名比对的联动都会静默失效。</p>
     *
     * @param projectId 项目 ID
     *
     * @param location 自由文本位置（可空）
     *
     * @return 地点表规范名；未命中/入参空/归一服务缺失返回 null
     */
    private String canonicalLocation(Long projectId, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        if (locationNormalizationService == null || projectId == null) {
            // 归一服务未装配（单测/裁剪部署）：保持旧口径原样放行，不改变既有行为
            return location.trim();
        }
        try {
            com.holzyn.actor.domain.world.service.LocationNormalizationService.LocationRef ref =
                    locationNormalizationService.canonical(projectId, location);
            return ref == null ? null : ref.name();
        } catch (Exception e) {
            log.warn("[位置归一] 行动目的地严格归一失败（保持原位置）: project={} : {}", projectId, e.getMessage());
            return null;
        }
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
