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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时行为决策服务 ——即时通道统一
 * 检测/仲裁/触发入口。
 * <p>定位：在既有周期通道
 * （WorldEngine 每游戏小时决策机会）之外建立<b>刺激驱动的即时通道</b>——角色遇到
 * 状态紧急 / 世界事件 / 他角色行动 / 他角色靠近 等刺激时，<b>即时</b>触发一次决策循环
 * （{@link AgentTrigger#STIMULUS}，毫秒级/5 游戏分钟粒度），无刺激延续当前行动（行为连续性）。</p>
 * <p>刺激源检测：</p>
 * <ul>
 * <li><b>刺激① 状态紧急升档</b>：{@link #onStateUrgencyRise}——NpcStateService.advance
 * 感知 tick（5 游戏分钟）内行内 urgency 升档时回调（降档/持平不触发）；</li>
 * <li><b>刺激② 世界事件/重大行动</b>：{@link #onEvent}——WorldEventBus 旁路订阅（毫秒级，
 * 事件重要度 ≥ {@link RealtimeDecisionProperties#getEventImportanceThreshold()} 才触发）；</li>
 * <li><b>刺激③ 他角色行动/靠近</b>：{@link #onEvent}（kind=action 行动事件，毫秒级）+
 * {@link #onNpcApproach}（WorldSandboxStateService.onNpcMoved 挂钩，靠近/到达实时）。</li>
 * </ul>
 * <p>触发链路（§4.2）：冷却检查（每角色每刺激源游戏时间冷却，紧急突破）→ 预算预检（只读，
 * 实际 AI 消耗由 AgentRuntime 内部统一记账）→ 组装即时决策上下文（刺激信息 + reason）→
 * 异步虚拟线程 {@link AgentRuntime#trigger(AgentTrigger, String, DecisionContext)} →
 * 完整决策管线（感知→记忆→思考→行动→学习，行动真执行，沙盘可见）。</p>
 * <p>成本护栏：① 预算门控（复用 BudgetGuard：引擎 tick 窗口存在时
 * 与周期通道共享窗口预算，耗尽即跳过；窗口不存在[引擎空闲]时放行，由 AgentRuntime 门面
 * 自开窗口[ownsTick 模式]）② 每角色冷却（cooldown-game-minutes 默认 10 游戏分钟，
 * urgency≥2 紧急突破）③ 事件重要度阈值（默认 3）。人群分层 / 链式防抖 按里程碑接入。</p>
 * <p>冷却存储：进程内内存（ConcurrentHashMap，key=projectId:npcRef:stimulusType →
 * 上次触发游戏秒）；单实例运行约定下重启丢失可接受（零新表）；时间基准=引擎推进位置
 * （currentEngineGameSecond），世界暂停时冷却自然冻结。</p>
 * <p>触发对象判定：当事人（character_ids 中 npc 前缀）→
 * 同地点在场者（location_id/locationName 匹配特殊 NPC 位置）→ visibility=global 时全部特殊 NPC；
 * 同角色只触发一次（按层序去重），层级即预算/冷却内的处理顺序。人群（普通型 common）不触发即时决策
 * 。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeDecisionService implements WorldEventSubscriber {

    /** 冷却 Map 条目上限（防长期运行内存膨胀） */
    private static final int COOLDOWN_CACHE_LIMIT = 5000;

    /** 刺激文本摘要上限（reason/日志用；刺激全文仍完整进 DecisionContext.situation） */
    private static final int STIMULUS_ABBREVIATE = 120;

    /** urgency 等级中文（0 正常 → 3 健康告急） */
    private static final String[] URGENCY_LEVEL_TEXT = {"正常", "轻度", "强烈", "健康告急"};

    /**
     * 钳制告警容差（游戏秒，=25 游戏小时）：引擎自身输入（小时边界 hour×3600 / 重启回补
     * 大步进 ≤ max_step_hours=24h）在时钟落库前瞬态超前属正常推进序列，静默钳制；超过此值
     * 视为真流动时钟污染才 WARN（24h 压测实证污染 87 游戏天 ≫ 此值）。
     */
    private static final long SANITIZE_WARN_TOLERANCE_SECONDS = 25 * 3600L;

    private final RealtimeDecisionProperties props;
    private final WorldEventBus worldEventBus;
    private final AgentRuntime agentRuntime;
    private final BudgetGuard budgetGuard;
    private final ActorCharacterRepository characterRepository;

    /**
     * 实时决策激活闸门（用户需求 后端启动不自动运行实时决策——默认关，
     * 仅打开沙盘/演化页或手动常开时激活；未激活 → 全部刺激入口直接跳过不触发）
     */
    private final RealtimeDecisionActivationService realtimeDecisionActivationService;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码类型——即时决策仅特殊 NPC 参与
     * [人群走分层概率化 0 AI]，「是否为特殊型」必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /** 世界时钟（游戏时刻基准；null=纯数据单测/未装配，冷却按 0 基准防同刻重复） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 内部状态仓库（紧急突破冷却判断：读 actor_state.urgency_level；null=不突破） */
    @Autowired(required = false)
    private ActorNpcStateRepository npcStateRepository;

    /**
     * 打断与恢复服务（实时行为决策系统：刺激通过仲裁后记录被打断任务 + 响应行动完成后
     * AI 恢复决策三态执行；@Lazy 破 Bean 环[本服务→恢复服务→AgentActionService→沙盘→本服务]，
     * null=未装配跳过打断恢复）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private InterruptRecoveryService interruptRecoveryService;

    /**
     * 个性注入服务（实时行为决策系统：全量【角色个性】段组装[persona+L3+brainProfile+关系+时间感]，
     * 供打断前探针与即时决策情境复用；null=未装配回退 行为[纯刺激情境]）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private PersonalityInjectionService personalityInjectionService;

    /**
     * 独处判定服务（实时行为决策系统：周期/即时决策情境注入【独处】段——同地点无其他特殊 NPC
     * 且无人群 NPC 时，AI 决定独处行为；null=未装配跳过独处注入，行为回到）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private SolitudeService solitudeService;

    /**
     * tick 屏障（阶段⑤：窄屏障成员——刺激即时决策会写状态，故入屏障；
     * 屏障已过时改为排队到下一 tick 的派发相）。null=未装配（单测）→ 退化为改造前自由并发。
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private TickBarrier tickBarrier;

    /**
     * 刺激日志服务（实时行为决策系统：刺激时间线 + 护栏命中记录，调试前端刺激日志页数据源；
     * null=未装配跳过记录，不影响决策主流程）
     */
    @Autowired(required = false)
    private StimulusLogService stimulusLogService;

    /**
     * 前瞻推演服务（STIMULUS 即时决策触发判定命中 → 情境追加【前瞻推演】段
     * [同调用插段，含宏观快照摘要] + 打开前瞻会话[决策产出后由 DecisionService 落库]；
     * null=未装配跳过，行为回到 前）
     */
    @Autowired(required = false)
    private ForesightService foresightService;

    /**
     * AI 强度闸门（微层接线， 实时决策按项目档位生效——programmatic=零 AI
     * [跳过打断前探针，决策由 AgentRuntime 程序化兜底；预算预检放行] / light=自开预算减半 +
     * 共享窗口只放行前半 / full=标准 / max=预算预检放行[全 AI 必调]；字段注入保持构造器不变）
     */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 无外层预算窗口时自开预算的缺省值（探针+即时决策同窗共享；与 AgentRuntime 门面同源配置） */
    @Value("${holzyn.actor.agent.budget-per-tick:5}")
    private int defaultBudgetPerTick;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 冷却表：key=projectId:npcRef:stimulusType → 上次触发游戏秒（进程内存态） */
    private final ConcurrentHashMap<String, Long> lastTriggered = new ConcurrentHashMap<>();

    /** 异步即时决策执行器（虚拟线程，不阻塞事件发布方/感知 tick） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 在途异步即时决策任务计数（供测试 awaitIdle 排空断言） */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * 启动注册：总开关开启时注册为世界事件总线旁路订阅者（对齐 DebugEventHub 模式，
     * 发布后回调 onEvent；总线对订阅者异常隔离，不影响主广播）。
     */
    @PostConstruct
    void register() {
        if (props.isEnabled()) {
            worldEventBus.subscribe(this);
            log.info("[实时决策] 已注册世界事件总线订阅者（刺激②/③实时接入）");
        }
    }

    // ==================== 刺激检测入口（三源） ====================

    /**
     * 世界事件总线订阅回调（刺激②世界事件/重大行动 + 刺激③他角色行动 kind=action）。
     * <p>过滤：总开关 → 事件重要度阈值（低重要度只记录不触发，成本护栏③）→
     * 触发对象三层解析（当事人/在场/全局）→ 逐角色 onStimulus（每角色独立冷却/预算）。</p>
     *
     * @param event 已落库的世界事件
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!props.isEnabled() || event == null || event.getProjectId() == null) {
            return;
        }
        // 激活闸门：未激活（默认）→ 不触发即时决策（世界暂停时无反应）
        if (!realtimeDecisionActivationService.isActive(event.getProjectId())) {
            return;
        }
        Integer importance = event.getImportance();
        if (importance == null || importance < props.getEventImportanceThreshold()) {
            log.debug("[实时决策] 事件重要度低于阈值，仅记录不触发: project={} id={} importance={} 阈值={}",
                    event.getProjectId(), event.getId(), importance, props.getEventImportanceThreshold());
            return;
        }
        Long projectId = event.getProjectId();
        // 长跑抓修：刺激目标名单排除已故角色（死人不应被刺激/触发反应）
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        List<String> targets = resolveEventTargets(event, chars);
        // 他角色行动（kind=action）：行动者自身不触发——"他角色"语义=其他人对此次行动做出反应，
        // 行动者已为自己的行动完成过决策，再触发即自我二次决策（精分风险）；冷却防的是同源重复，
        // 但语义上应直接排除（event.characterId=行动者，无则信任 character_ids 全量）
        StimulusType type = eventStimulusType(event);
        // 链式反应：链式开启时 kind=action 事件的同地点触发由
        // ChainReactionService 统一接管（同地点 + 关系网扩散，一跳防环）——本事件分支跳过，
        // 防同一行动被 NPC_ACTION 与 CHAIN_REACTION 双触发
        if (type == StimulusType.NPC_ACTION && props.isChainReactionEnabled()) {
            log.debug("[实时决策] 链式开启，kind=action 事件分支由链式反应接管（跳过）: event={}", event.getId());
            return;
        }
        if (type == StimulusType.NPC_ACTION && event.getCharacterId() != null) {
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（不再手写前缀字面量；null 安全比较防 id 非法时 NPE）
            String actorRef = NpcRefs.of(event.getCharacterId());
            targets = targets.stream().filter(r -> !java.util.Objects.equals(actorRef, r)).toList();
        }
        if (targets.isEmpty()) {
            return;
        }
        String text = eventStimulusText(event);
        Long gameSecond = event.getGameHour() == null ? null : event.getGameHour() * 3600L;
        // 社交差异化：刺激相关人物名（事件当事人 + 行动者自身）→ 个性段关系注入「相关人物优先」
        List<String> relatedNames = relatedNamesOf(chars, event.getCharacterIds(), event.getCharacterId());
        for (String ref : targets) {
            onStimulus(projectId, ref, type, text, gameSecond, event.getImportance(), relatedNames);
        }
    }

    /**
     * 他角色靠近/到达挂钩（刺激③位置变化；WorldSandboxStateService.onNpcMoved 调用）。
     * <p>语义：某 NPC 到达目标地点 → 该地点在场的<b>其他</b>特殊 NPC 获得即时决策机会
     * （移动者自身不触发；人群不触发）；为 链式反应打基础。</p>
     *
     * @param projectId 项目 ID
     *
     * @param moverRef 移动者 NPC 引用（排除自身）
     *
     * @param locationName 目标地点名
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    public void onNpcApproach(Long projectId, String moverRef, String locationName, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || locationName == null || locationName.isBlank()) {
            return;
        }
        // 激活闸门：未激活（默认）→ 不触发即时决策
        if (!realtimeDecisionActivationService.isActive(projectId)) {
            return;
        }
        // 长跑抓修：靠近刺激目标名单排除已故角色
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        List<String> targets = resolveLocationTargets(locationName, chars, moverRef);
        if (targets.isEmpty()) {
            return;
        }
        String moverName = nameOf(chars, moverRef);
        String text = buildApproachText(moverName, locationName);
        // 社交差异化：靠近者是相关人物（个性段「与{移动者}的关系」优先注入）
        List<String> relatedNames = moverName == null ? null : List.of(moverName);
        for (String ref : targets) {
            onStimulus(projectId, ref, StimulusType.NPC_APPROACH, text, gameSecond, null, relatedNames);
        }
    }

    /**
     * 状态紧急升档回调（刺激①；NpcStateService.advance 感知 tick 内行内 urgency 升档时调用）。
     * <p>语义：仅升档（toLevel &gt; fromLevel）触发——降档/持平不重复决策（行为连续性）；
     * 5 游戏分钟粒度由引擎感知 tick 承载。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 该 NPC 引用（特殊角色）
     *
     * @param fromLevel 原 urgency 级别（0-3）
     *
     * @param toLevel 新 urgency 级别（0-3）
     *
     * @param gameSecond 游戏时刻（秒，可空=世界时钟当前值）
     */
    public void onStateUrgencyRise(Long projectId, String npcRef, int fromLevel, int toLevel, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        // 激活闸门：未激活（默认）→ 不触发即时决策
        if (!realtimeDecisionActivationService.isActive(projectId)) {
            return;
        }
        if (toLevel <= fromLevel) {
            return;
        }
        String text = "状态紧急程度提升（" + urgencyLevelText(fromLevel) + " → " + urgencyLevelText(toLevel) + "）";
        onStimulus(projectId, npcRef, StimulusType.STATE_URGENCY, text, gameSecond, null, null);
    }

    /**
     * 链式反应触发入口。
     * <p>语义：特殊 NPC 行动执行后，同地点其他特殊 NPC + 关系密切异地相关人物收到
     * {@link StimulusType#CHAIN_REACTION} 刺激 → 走<b>完整即时决策管线</b>
     * 。
     * 与普通即时决策共享冷却（CHAIN_REACTION 独立冷却键=每角色每刺激源防抖）、预算、探针/打断/恢复
     * 护栏；<b>不落 actor_event</b>。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 被触发角色引用（npc:{id}；仅特殊型参与）
     *
     * @param stimulusText 链式刺激文本（{你看到}{行动者}在{地点}：{行动}）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @param relatedNames 相关人物名（行动者；社交差异化关系注入优先）
     */
    public void onChainReaction(Long projectId, String npcRef, String stimulusText, Long gameSecond,
                                List<String> relatedNames) {
        // 链式为非紧急常规刺激：importance=null → 探针按性格判断是否放下手头事
        onStimulus(projectId, npcRef, StimulusType.CHAIN_REACTION, stimulusText, gameSecond, null, relatedNames);
    }

    // ==================== 即时决策仲裁与触发（核心） ====================

    /**
     * 即时决策统一入口：委托 7 参版本（importance/relatedNames 缺省）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 触发角色引用（npc:{id}；仅特殊型触发，普通型忽略）
     *
     * @param type 刺激类型
     *
     * @param stimulusText 刺激文本（进 DecisionContext.situation/stimulusText，完整保留）
     *
     * @param gameSecond 游戏时刻（秒，可空=世界时钟当前值）
     */
    public void onStimulus(Long projectId, String npcRef, StimulusType type, String stimulusText, Long gameSecond) {
        onStimulus(projectId, npcRef, type, stimulusText, gameSecond, null, null);
    }

    /**
     * 即时决策统一入口（检测→仲裁→异步触发）：冷却 → 预算预检 → 记录冷却 → 异步执行
     * 。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 触发角色引用（npc:{id}；仅特殊型触发，普通型忽略）
     *
     * @param type 刺激类型
     *
     * @param stimulusText 刺激文本（进 DecisionContext.situation/stimulusText，完整保留）
     *
     * @param gameSecond 游戏时刻（秒，可空=世界时钟当前值）
     *
     * @param importance 事件重要度
     *
     * @param relatedNames 刺激相关人物名（事件当事人/行动者/靠近者；可空=无特定关联人物）
     */
    private void onStimulus(Long projectId, String npcRef, StimulusType type, String stimulusText,
                            Long gameSecond, Integer importance, List<String> relatedNames) {
        if (!props.isEnabled() || projectId == null || npcRef == null || type == null) {
            return;
        }
        // 激活闸门（用户需求）：统一入口兜底门控——三入口（onEvent/onNpcApproach/
        // onStateUrgencyRise）已各自检查，此处覆盖 链式（onChainReaction）等新入口直达路径，
        // 保证未激活项目任何刺激都不触发即时决策（世界暂停不耗 AI）
        if (!realtimeDecisionActivationService.isActive(projectId)) {
            log.debug("[实时决策] 激活闸门：项目未激活，跳过刺激触发: project={} ref={} type={}",
                    projectId, npcRef, type.code());
            logStimulus(projectId, gameSecond, type.code(), npcRef, stimulusText, "not_active", "实时决策未激活（世界暂停）");
            return;
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——「即时决策仅特殊型参与、人群走分层
        // 概率化 0 AI」属只有特殊型才做的行为，是否特殊型改用 NpcTypeResolver 查 actor_profile.type
        // （查询失败回退 special，与历史「非 special 前缀才排除」的保守方向一致）
        if (!npcTypeResolver.isSpecial(projectId, npcRef)) {
            log.debug("[实时决策] 非特殊角色不触发即时决策（人群走分层概率化，0 AI）: ref={}", npcRef);
            logStimulus(projectId, gameSecond, type.code(), npcRef, stimulusText, "crowd", "人群走分层概率化，0 AI");
            return;
        }
        // （状态循环修复）：决策上下文游戏时刻钳制到引擎推进位置——事件链传入的
        // gameSecond 可能来自流动时钟（引擎停止期间仍按速率×真实流逝虚跑，24h 压测实证可达 87 游戏天），
        // 不钳制会把流动时钟值经 感知注入/计划排期 流入 actor_state 造成状态冻结（陆沉舟 9/10 实证）
        Long nowSecond = sanitizeGameSecond(projectId, gameSecond);

        // ① 冷却检查（每角色每刺激源游戏时间冷却；紧急状态 urgency≥2 可突破——成本护栏②）
        String cooldownKey = cooldownKey(projectId, npcRef, type);
        Long last = lastTriggered.get(cooldownKey);
        boolean urgent = isUrgent(projectId, npcRef);
        if (last != null && coolingActive(last, nowSecond, cooldownSeconds(type)) && !urgent) {
            log.debug("[实时决策] 冷却期内跳过即时决策: project={} ref={} type={} 距上次={} 秒",
                    projectId, npcRef, type.code(), nowSecond - last);
            logStimulus(projectId, nowSecond, type.code(), npcRef, stimulusText, "cooldown",
                    "冷却期内跳过（" + (nowSecond - last) + " 秒前刚触发）");
            return;
        }

        // ② 预算预检（只读不扣减——实际 AI 消耗由异步任务内统一记账，避免重复扣减；
        // 引擎 tick 窗口存在时与周期通道共享窗口，窗口耗尽即跳过；窗口不存在[引擎空闲]放行，
        // 由异步任务 ownsTick 自开[探针+即时决策同窗]——成本护栏①；
        // 微层接线：programmatic[决策零 AI 不耗预算] 与 max[全 AI 必调] 两档放行预检；
        // light 档共享窗口只放行前半[预算减半语义]）
        long budgetTotal = budgetGuard.budgetTotal(projectId);
        boolean intensityUnlimited = aiIntensityGate != null
                && (!aiIntensityGate.allowsAi(projectId) || aiIntensityGate.isMax(projectId));
        if (budgetTotal >= 0 && budgetGuard.remaining(projectId) <= 0 && !intensityUnlimited) {
            log.debug("[实时决策] 引擎预算窗口内预算已耗尽，跳过即时决策: project={} ref={} type={}",
                    projectId, npcRef, type.code());
            logStimulus(projectId, nowSecond, type.code(), npcRef, stimulusText, "budget",
                    "引擎 tick 预算窗口内预算耗尽");
            return;
        }
        if (budgetTotal >= 0 && aiIntensityGate != null && aiIntensityGate.isLight(projectId)
                && budgetGuard.remaining(projectId) <= (int) Math.ceil(budgetTotal * 0.5)) {
            log.debug("[实时决策] AI 强度 light 档共享窗口前半已耗尽，跳过即时决策: project={} ref={} type={}",
                    projectId, npcRef, type.code());
            logStimulus(projectId, nowSecond, type.code(), npcRef, stimulusText, "budget",
                    "AI 强度 light 档预算减半（共享窗口前半已用）");
            return;
        }

        // ③ 记录冷却（触发前写，防并发同刺激重复触发；null→0 为「未知时刻」基准，同刻重复仍被防抖）
        lastTriggered.put(cooldownKey, nowSecond == null ? 0L : nowSecond);
        enforceCooldownLimit();

        // ④ 组装基础决策上下文（situation=刺激原文；个性情境/打断记录在异步任务内组装后重建 ctx）
        String reason = "刺激：" + type.code() + "：" + abbreviate(stimulusText, STIMULUS_ABBREVIATE);
        DecisionContext base = DecisionContext.builder()
                .projectId(projectId)
                .userId(currentUserId())
                .gameSecond(nowSecond)
                .situation(stimulusText)
                .stimulusType(type.code())
                .stimulusText(stimulusText)
                .reason(reason)
                .build();
        submitAsync(projectId, npcRef, type, base, importance, relatedNames);
    }

    // ==================== 触发对象解析（静态纯逻辑，可单测） ====================

    /**
     * 事件触发对象三层解析：① 当事人（character_ids 中 special 前缀）→
     * ② 同地点在场者（location_id 匹配角色 location_id；或 locationName 匹配角色 location 名）→
     * ③ visibility=global 时全部特殊 NPC；同角色只出现一次（LinkedHashSet 去重），
     * 层级即处理顺序（高优先层先触发）。
     *
     * @param event 事件实体
     *
     * @param characters 项目特殊 NPC 列表（可空/空）
     *
     * @return 有序去重的触发对象 ref 列表（npc:{id}）
     */
    static List<String> resolveEventTargets(ActorEvent event, List<ActorCharacter> characters) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (event == null) {
            return out;
        }
        // ① 当事人（character_ids 逗号分隔 ref，兼容单值；提供角色列表时过滤已删除/不存在的角色）
        if (event.getCharacterIds() != null) {
            for (String part : event.getCharacterIds().split(",")) {
                String ref = part == null ? "" : part.trim();
                // ：npc:{id} 不再编码类型，此处按意图 A 处理——只要求是能解析成 actor_profile 行的
                // 统一引用（special/common 都成立）；「是否特殊型」由入口 isSpecial 统一把关
                if (!NpcRefs.isNpcRef(ref)) {
                    continue;
                }
                if (characters != null && !characters.isEmpty() && !isActiveCharacter(characters, ref)) {
                    continue;
                }
                if (seen.add(ref)) {
                    out.add(ref);
                }
            }
        }
        if (characters == null) {
            return out;
        }
        // ② 同地点在场者
        for (ActorCharacter ch : characters) {
            if (ch == null || Integer.valueOf(1).equals(ch.getDeleted())) {
                continue;
            }
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（不再手写前缀字面量；id 非法时跳过该行）
            String ref = NpcRefs.of(ch.getId());
            if (ref == null) {
                continue;
            }
            if (!seen.contains(ref) && locationMatch(event, ch)) {
                seen.add(ref);
                out.add(ref);
            }
        }
        // ③ 全局可见事件 → 全部特殊 NPC（不含已在前两层者）
        if (isGlobal(event.getVisibility())) {
            for (ActorCharacter ch : characters) {
                if (ch == null || Integer.valueOf(1).equals(ch.getDeleted())) {
                    continue;
                }
                String ref = NpcRefs.of(ch.getId());
                if (ref != null && seen.add(ref)) {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    /**
     * 同地点在场者解析（onNpcApproach 用）：匹配指定地点名的特殊 NPC（排除移动者自身）。
     *
     * @param locationName 目标地点名
     *
     * @param characters 项目特殊 NPC 列表
     *
     * @param excludeRef 排除的 NPC 引用（移动者自身，可空）
     *
     * @return 有序去重的触发对象 ref 列表
     */
    static List<String> resolveLocationTargets(String locationName, List<ActorCharacter> characters,
                                               String excludeRef) {
        List<String> out = new ArrayList<>();
        if (locationName == null || locationName.isBlank() || characters == null) {
            return out;
        }
        for (ActorCharacter ch : characters) {
            if (ch == null || Integer.valueOf(1).equals(ch.getDeleted())) {
                continue;
            }
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（不再手写前缀字面量；id 非法时跳过该行）
            String ref = NpcRefs.of(ch.getId());
            if (ref == null || ref.equals(excludeRef)) {
                continue;
            }
            if (locationEquals(locationName, ch.getLocation())) {
                out.add(ref);
            }
        }
        return out;
    }

    /**
     * 角色是否有效触发对象（存在于列表且未删除）。
     *
     * @param characters 项目特殊 NPC 列表
     *
     * @param ref 角色引用（npc:{id}）
     *
     * @return true 表示有效（列表为空时信任事件数据返回 true）
     */
    static boolean isActiveCharacter(List<ActorCharacter> characters, String ref) {
        if (characters == null || characters.isEmpty()) {
            return true;
        }
        for (ActorCharacter ch : characters) {
            // ：比较口径统一走 NpcRefs.of（null 安全比较，防 id 非法时 NPE）
            if (ch != null && java.util.Objects.equals(NpcRefs.of(ch.getId()), ref)
                    && !Integer.valueOf(1).equals(ch.getDeleted())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 静态纯逻辑辅助（可单测） ====================

    /**
     * 事件 → 刺激类型：kind=action 视为「他角色行动」，其余（world/evolution/system/event/manual 等）
     * 视为「世界事件/重大行动」。
     *
     * @param event 事件实体（可空）
     *
     * @return 刺激类型（null 事件回退 WORLD_EVENT）
     */
    static StimulusType eventStimulusType(ActorEvent event) {
        if (event != null && "action".equalsIgnoreCase(normalize(event.getKind()))) {
            return StimulusType.NPC_ACTION;
        }
        return StimulusType.WORLD_EVENT;
    }

    /**
     * 事件 → 刺激文本（标题 + 正文摘要）。
     *
     * @param event 事件实体（可空）
     *
     * @return 刺激文本（非空）
     */
    static String eventStimulusText(ActorEvent event) {
        if (event == null) {
            return "发生了一起事件";
        }
        StringBuilder sb = new StringBuilder();
        if (event.getTitle() != null && !event.getTitle().isBlank()) {
            sb.append(event.getTitle().trim());
        }
        if (event.getContent() != null && !event.getContent().isBlank()) {
            if (sb.length() > 0) {
                sb.append("：");
            }
            sb.append(abbreviate(event.getContent().trim(), 160));
        }
        return sb.length() > 0 ? sb.toString() : "发生了一起事件";
    }

    /**
     * 靠近刺激文本组装（{角色名}来到了{地点}）。
     *
     * @param moverName 移动者名称（可空=引用）
     *
     * @param locationName 目标地点名
     *
     * @return 刺激文本
     */
    static String buildApproachText(String moverName, String locationName) {
        String who = (moverName == null || moverName.isBlank()) ? "有人" : moverName.trim();
        return who + "来到了" + (locationName == null ? "" : locationName.trim());
    }

    /**
     * 冷却是否生效（游戏时间差小于冷却时长）。
     *
     * @param lastTriggerSecond 上次触发游戏秒（null=0 未知基准）
     *
     * @param currentSecond 当前游戏秒（null=0 未知基准）
     *
     * @param cooldownSeconds 冷却时长（游戏秒，&lt;=0 视为不冷却）
     *
     * @return true 表示仍在冷却期内
     */
    static boolean coolingActive(Long lastTriggerSecond, Long currentSecond, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return false;
        }
        long last = lastTriggerSecond == null ? 0L : lastTriggerSecond;
        long now = currentSecond == null ? 0L : currentSecond;
        return now - last < cooldownSeconds;
    }

    /**
     * 事件地点匹配角色（location_id 精确匹配优先；其次 locationName 与角色 location 名归一化相等）。
     *
     * @param event 事件实体
     *
     * @param ch 角色实体
     *
     * @return true 表示该角色与事件同地点
     */
    static boolean locationMatch(ActorEvent event, ActorCharacter ch) {
        if (event == null || ch == null) {
            return false;
        }
        if (event.getLocationId() != null && ch.getLocationId() != null) {
            return event.getLocationId().equals(ch.getLocationId());
        }
        return locationEquals(event.getLocationName(), ch.getLocation());
    }

    /**
     * 地点名归一化相等（trim + 忽略大小写）。
     *
     * @param a 地点名 a（可空）
     *
     * @param b 地点名 b（可空）
     *
     * @return true 表示归一化后相等
     */
    static boolean locationEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * 可见性是否为 global（空白/非法回退非全局）。
     *
     * @param visibility 可见性文本
     *
     * @return true 表示全局
     */
    static boolean isGlobal(String visibility) {
        return "global".equalsIgnoreCase(normalize(visibility));
    }

    /**
     * 冷却键（projectId:npcRef:stimulusType）。
     */
    static String cooldownKey(Long projectId, String npcRef, StimulusType type) {
        return projectId + ":" + npcRef + ":" + (type == null ? "?" : type.code());
    }

    // ==================== 私有辅助 ====================

    /**
     * 异步执行即时决策（虚拟线程，不阻塞事件发布方/感知 tick；单角色失败隔离）。
     * <p>全流程：
     * ① 组装全量【角色个性】段；
     * ② <b>打断前 AI 探针</b>（/ 常规刺激：事件重要度 &lt; 强制阈值 且非紧急——由 AI 按性格判断
     * 是否值得放下手头事；FALSE=继续手头事不打断不触发；TRUE/null[兜底默认打断]=继续）；
     * / （事件重要度≥强制阈值 或 urgency≥2）<b>强制打断</b>跳过探针；③ 打断记录创建
     * （探针通过后）；④ 个性化情境拼装（个性段 + 刺激 + 决策要求）→ 重建 ctx（回填 interruptedPlanId）；
     * ⑤ {@link #triggerWithRetry} 防重入重试触发。</p>
     * <p>探针路径预算：异步任务内 ownsTick 自开窗口（与 AgentRuntime 门面同源缺省值），
     * 探针 tryConsume 与即时决策同窗共享（同流同预算）；强制打断路径保持 行为
     * （由 AgentRuntime 门面 ownsTick 自开）。 冒烟抓修保留：多角色并发刺激被全局防重入拦截时
     * 自动重试（重试次数/间隔可配）。</p>
     */
    private void submitAsync(Long projectId, String npcRef, StimulusType type, DecisionContext base,
                             Integer importance, List<String> relatedNames) {
        // ==================== 阶段⑤：tick 屏障（窄屏障成员 + 刺激排队） ====================
        // 本方法会写 actor_state/行动落库，属"窄屏障"范围。两条规则：
        // ① 屏障已过（本 tick 的在途任务已排空）→ 不立即执行，排队到**下一 tick 的派发相**，
        // 保证严格因果顺序（反应落在"感知时刻"之后、"下一个时刻"之前）；代价 ≤1 tick＝5 游戏分钟。
        // ② 未过去 → 立即执行并 register，本 tick 的屏障会等它结束再推进下一 tick。
        if (tickBarrier != null && tickBarrier.shouldDefer(projectId)) {
            tickBarrier.defer(projectId, () -> submitAsync(projectId, npcRef, type, base, importance, relatedNames));
            log.info("[实时决策] 屏障已过，刺激排队到下一 tick 派发: project={} ref={} type={}",
                    projectId, npcRef, type.code());
            return;
        }
        if (tickBarrier != null) {
            tickBarrier.register(projectId);
        }
        inFlight.incrementAndGet();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                boolean ownsTick = false;
                try {
                    // ① 全量个性段（相关人物关系优先注入；开关关/未装配/失败→空串）
                    String segment = buildPersonalitySegment(projectId, npcRef, base.gameSecond(), relatedNames);
                    // ② 打断前 AI 探针： / （事件重要度≥阈值 或 urgency≥2）强制打断跳过探针；
                    // / （常规刺激）由 AI 按性格判断是否放下手头事；
                    // 微层接线：programmatic 档零 AI——跳过探针（走兜底默认打断 + 程序化决策）
                    boolean forced = (importance != null && importance >= props.getProbeForceImportanceThreshold())
                            || isUrgent(projectId, npcRef);
                    boolean probeAllowed = aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
                    if (!forced && props.isInterruptProbeEnabled() && probeAllowed && interruptRecoveryService != null) {
                        ownsTick = budgetGuard.budgetTotal(projectId) < 0;
                        if (ownsTick) {
                            // 微层接线：自开窗口预算按档位折算——light=ceil×0.5 / max=不限
                            int ownBudget = aiIntensityGate != null && aiIntensityGate.isMax(projectId)
                                    ? Integer.MAX_VALUE
                                    : (aiIntensityGate != null && aiIntensityGate.isLight(projectId)
                                            ? Math.max(1, (int) Math.ceil(defaultBudgetPerTick * 0.5))
                                            : defaultBudgetPerTick);
                            budgetGuard.beginTick(projectId, ownBudget);
                        }
                        Boolean probe = null;
                        try {
                            probe = interruptRecoveryService.probeInterrupt(projectId, npcRef,
                                    base.gameSecond(), base.stimulusText(), segment);
                        } catch (Exception e) {
                            log.warn("[实时决策] 打断前探针失败（兜底默认打断）: ref={} : {}", npcRef, e.getMessage());
                        }
                        if (Boolean.FALSE.equals(probe)) {
                            log.info("[实时决策] 按性格选择不打断（继续手头事）: project={} ref={} type={} 刺激={}",
                                    projectId, npcRef, type.code(), abbreviate(base.stimulusText(), 60));
                            return; // 不记录打断、不触发即时决策（真人感「不理会」）
                        }
                        // probe=TRUE 或 null
                    }
                    // ③ 打断记录（探针通过/强制打断后；失败隔离不影响即时决策），返回的被打断计划
                    // ID 回填 DecisionContext.interruptedPlanId
                    Long interruptedPlanId = null;
                    if (interruptRecoveryService != null) {
                        try {
                            interruptedPlanId = interruptRecoveryService.interrupt(projectId, npcRef, base.gameSecond());
                        } catch (Exception e) {
                            log.warn("[实时决策] 打断记录失败（隔离继续）: ref={} : {}", npcRef, e.getMessage());
                        }
                    }
                    // ④ 个性化情境拼装
                    String situation = (segment != null && !segment.isBlank())
                            ? PersonalityInjectionService.composeStimulusSituation(segment, base.stimulusText())
                            : base.situation();
                    // ④.5 独处行为：同地点无其他特殊 NPC 且无人群 NPC → 追加【独处】段
                    // （：周期/即时决策双轨注入，AI 自行决定独处时的真实行为；
                    // 判定失败/未装配跳过不阻断，行为回到）
                    if (solitudeService != null && props.isSolitudeEnabled()) {
                        try {
                            String solitude = solitudeService.solitudeSegment(projectId, npcRef);
                            if (solitude != null && !solitude.isBlank()) {
                                situation = situation + "\n\n" + solitude;
                            }
                        } catch (Exception e) {
                            log.warn("[实时决策] 独处段注入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                        }
                    }
                    // ④.75 前瞻推演（，现状优化轨道 §7.2）：确定性规则触发
                    // （事件重要度≥阈值 ∪ urgency≥阈值 ∪ 刺激文本命中资源/关系/位置关键词）→
                    // 情境追加【前瞻推演】段（同调用插段——AI 先输出候选行动及短期后果再选最终行动，
                    // 含宏观快照摘要注入），并打开前瞻会话（决策产出后由 DecisionService.recordIfOpen
                    // 落 actor_foresight_record；未触发/失败零阻断，行为回到 前）
                    if (foresightService != null && props.getForesight() != null && props.getForesight().isEnabled()) {
                        try {
                            boolean urgentNow = isUrgent(projectId, npcRef);
                            String fReason = ForesightService.triggerReason(type, importance, urgentNow,
                                    base.stimulusText(), props.getForesight());
                            if (fReason != null && !fReason.isBlank()) {
                                String fSection = foresightService.buildSection(projectId, npcRef, base.gameSecond());
                                if (fSection != null && !fSection.isBlank()) {
                                    situation = situation + "\n\n" + fSection;
                                    foresightService.open(projectId, npcRef, null, base.gameSecond(),
                                            fReason, base.stimulusText(), fSection);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[实时决策] 前瞻段注入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                        }
                    }
                    DecisionContext ctx = DecisionContext.builder()
                            .projectId(base.projectId())
                            .userId(base.userId())
                            .conversationId(base.conversationId())
                            .gameSecond(base.gameSecond())
                            .query(base.query())
                            .reason(base.reason())
                            .situation(situation)
                            .stimulusType(base.stimulusType())
                            .stimulusText(base.stimulusText())
                            .interruptedPlanId(interruptedPlanId)
                            .build();
                    // ⑤ 防重入重试触发
                    log.info("[实时决策] 触发即时决策: project={} ref={} type={} 刺激={}",
                            projectId, npcRef, type.code(), abbreviate(base.stimulusText(), 60));
                    logStimulus(projectId, base.gameSecond(), type.code(), npcRef, base.stimulusText(),
                            "triggered", "即时决策触发（探针" + (forced ? "强制打断" : "AI 判断") + "）");
                    AgentResult result = triggerWithRetry(projectId, npcRef, ctx);
                    if (result != null && result.reentrant()) {
                        log.warn("[实时决策] 即时决策重试耗尽仍被防重入拦截: project={} ref={} type={}",
                                projectId, npcRef, type.code());
                    }
                } catch (Exception e) {
                    // 单角色即时决策失败不阻断其他角色/刺激（AI 失败由 AgentRuntime 内部程序化兜底）
                    log.warn("[实时决策] 即时决策执行失败: project={} ref={} type={} : {}",
                            projectId, npcRef, type.code(), e.getMessage());
                } finally {
                    // ：前瞻会话兜底清理（recordIfOpen 已消费则幂等；防虚拟线程复用泄漏）
                    if (foresightService != null) {
                        try {
                            foresightService.close();
                        } catch (Exception ignored) {
                            // 清理失败不影响决策主流程
                        }
                    }
                    if (ownsTick) {
                        budgetGuard.endTick(projectId);
                    }
                    inFlight.decrementAndGet();
                    // 阶段⑤：本 tick 在途工作完成——屏障据此判断"可否推进下一 tick"
                    if (tickBarrier != null) {
                        tickBarrier.complete(projectId);
                    }
                }
            });
            submitted = true;
        } finally {
            if (!submitted) {
                // 提交失败（线程池拒绝等）：回滚在途登记，避免屏障永久等待（自愈之外的第二道保险）
                inFlight.decrementAndGet();
                if (tickBarrier != null) {
                    tickBarrier.complete(projectId);
                }
            }
        }
    }

    /**
     * 带防重入重试的即时决策触发（包内可见供单测确定性验证）：
     * 结果 reentrant 时按 {@code stimulus-retry-max-attempts} 间隔 {@code stimulus-retry-wait-ms}
     * 自动重试，保证每个刺激最终完成完整决策管线（行动真执行 + 恢复判断）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用
     *
     * @param ctx 决策上下文（重试复用同一 ctx，不重复走冷却/预算预检/打断记录）
     *
     * @return 最后一次触发结果（可能为 reentrant=重试耗尽）
     */
    AgentResult triggerWithRetry(Long projectId, String npcRef, DecisionContext ctx) {
        int maxAttempts = Math.max(0, props.getStimulusRetryMaxAttempts());
        long waitMs = Math.max(0, props.getStimulusRetryWaitMs());
        AgentResult result = null;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            result = agentRuntime.trigger(AgentTrigger.STIMULUS, npcRef, ctx);
            if (result == null || !result.reentrant()) {
                return result;
            }
            if (attempt < maxAttempts) {
                log.info("[实时决策] 即时决策被防重入拦截，{}ms 后重试（{}/{}）: ref={} type={}",
                        waitMs, attempt + 1, maxAttempts, npcRef, ctx == null ? "?" : ctx.stimulusType());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result;
    }

    /** 紧急突破判定：当前 urgency ≥ 2（强烈/健康告急）时冷却可突破（成本护栏②）。 */
    private boolean isUrgent(Long projectId, String npcRef) {
        if (npcStateRepository == null || projectId == null || npcRef == null) {
            return false;
        }
        try {
            ActorNpcState st = npcStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            return st != null && st.getUrgencyLevel() != null && st.getUrgencyLevel() >= 2;
        } catch (Exception e) {
            log.debug("[实时决策] 紧急状态读取失败（按非紧急处理）: ref={} : {}", npcRef, e.getMessage());
            return false;
        }
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
     * 决策上下文游戏时刻钳制：
     * 传入时刻为空 → 引擎推进位置；传入时刻超过引擎推进位置 → 钳制回引擎位置并告警
     * （流动时钟在引擎停止/慢速时远超模拟位置，必须一律以引擎离散模拟位置为单一时间源）。
     * <b>2 游戏小时内静默钳制</b>（引擎自身小时边界决策上下文 hour×3600 在时钟落库前瞬态超前，
     * 属正常推进序列，不告警防 WARN 刷屏污染压测统计），超过才 WARN（真流动时钟污染）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 调用方传入的游戏时刻（秒，可空）
     *
     * @return 钳制后的游戏时刻（引擎不可读/未超限时原样返回；均可空）
     */
    private Long sanitizeGameSecond(Long projectId, Long gameSecond) {
        if (gameSecond == null) {
            return currentEngineGameSecond(projectId);
        }
        Long engine = currentEngineGameSecond(projectId);
        if (engine == null) {
            return gameSecond;
        }
        if (gameSecond > engine) {
            if (gameSecond - engine > SANITIZE_WARN_TOLERANCE_SECONDS) {
                log.warn("[实时决策] 刺激游戏时刻超过引擎推进位置，已钳制（P0-2 流动时钟污染防护）: "
                        + "project={} 传入={} 引擎={}", projectId, gameSecond, engine);
            } else {
                log.debug("[实时决策] 刺激游戏时刻轻微超前引擎位置，静默钳制（引擎小时边界瞬态）: "
                        + "project={} 传入={} 引擎={}", projectId, gameSecond, engine);
            }
            return engine;
        }
        return gameSecond;
    }

    /**
     * 冷却时长（游戏秒；配置 <=0 视为不冷却）。
     * <p>环境刺激：{@link StimulusType#ENVIRONMENT} 使用独立冷却
     * {@code environment-cooldown-game-minutes}（默认 30，护栏①）——天气骤变/扩散到达等
     * 环境刺激高频，独立键[StimulusType 即键] + 独立时长防刷屏，不挤占普通刺激的 10 分钟冷却。</p>
     */
    private int cooldownSeconds(StimulusType type) {
        if (type == StimulusType.ENVIRONMENT && props.getEnvironmentCooldownGameMinutes() > 0) {
            return props.getEnvironmentCooldownGameMinutes() * 60;
        }
        int minutes = props.getCooldownGameMinutes();
        return minutes > 0 ? minutes * 60 : 0;
    }

    /** 当前用户 ID（本地单用户 id=1；未装配回退 1L）。 */
    private Long currentUserId() {
        return currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
    }

    /** 角色名称（按 ref 从列表查找；找不到回退 ref）。 */
    private static String nameOf(List<ActorCharacter> chars, String ref) {
        if (chars != null && ref != null) {
            for (ActorCharacter ch : chars) {
                // ：比较口径统一走 NpcRefs.of（null 安全比较，防 id 非法时 NPE）
                if (ch != null && java.util.Objects.equals(NpcRefs.of(ch.getId()), ref)
                        && ch.getName() != null) {
                    return ch.getName();
                }
            }
        }
        return ref;
    }

    /**
     * 刺激相关人物名解析。
     * <p>来源：① 事件 character_ids 中 npc:{id} 当事人（去重保序）；② kind=action 的
     * 行动者自身（event.characterId）。返回按项目角色列表解析后的角色名；无相关人物返回空列表。</p>
     *
     * @param chars 项目特殊 NPC 列表（可空）
     *
     * @param characterIds 事件 character_ids 原文（可空，逗号分隔 ref）
     *
     * @param actorId kind=action 事件行动者 ID（可空）
     *
     * @return 相关人物名列表（去重保序，可能为空）
     */
    private static List<String> relatedNamesOf(List<ActorCharacter> chars, String characterIds, Long actorId) {
        if ((characterIds == null || characterIds.isBlank()) && actorId == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (characterIds != null) {
            for (String part : characterIds.split(",")) {
                String ref = part == null ? "" : part.trim();
                // ：npc:{id} 不再编码类型，此处按意图 A 处理——只要求是能解析成 actor_profile 行的
                // 统一引用（special/common 都成立；@param 语义=当事人名，与类型无关）
                if (!NpcRefs.isNpcRef(ref)) {
                    continue;
                }
                String name = nameOf(chars, ref);
                if (name != null && !name.isBlank() && seen.add(name)) {
                    out.add(name);
                }
            }
        }
        if (actorId != null) {
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of
            String name = nameOf(chars, NpcRefs.of(actorId));
            if (name != null && !name.isBlank() && seen.add(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * 全量【角色个性】段组装（委托 {@link PersonalityInjectionService}；未装配/开关关/异常返回空串，
     * 调用方回退 纯刺激情境——零 AI 成本，不阻断即时决策主流程）。
     */
    private String buildPersonalitySegment(Long projectId, String npcRef, Long gameSecond,
                                           List<String> relatedNames) {
        if (personalityInjectionService == null || projectId == null || npcRef == null) {
            return "";
        }
        try {
            String seg = personalityInjectionService.buildPersonalitySegment(
                    projectId, npcRef, gameSecond, relatedNames);
            return seg == null ? "" : seg;
        } catch (Exception e) {
            log.warn("[实时决策] 个性段组装失败（回退纯刺激情境）: ref={} : {}", npcRef, e.getMessage());
            return "";
        }
    }

    /** urgency 等级中文描述（越界钳制）。 */
    static String urgencyLevelText(int level) {
        int idx = Math.max(0, Math.min(URGENCY_LEVEL_TEXT.length - 1, level));
        return URGENCY_LEVEL_TEXT[idx];
    }

    /** 刺激日志记录。 */
    private void logStimulus(Long projectId, Long gameSecond, String type, String npcRef, String text,
                             String result, String detail) {
        if (stimulusLogService != null) {
            try {
                stimulusLogService.log(projectId, gameSecond, type, npcRef, text, result, detail);
            } catch (Exception e) {
                log.debug("[实时决策] 刺激日志记录失败（跳过）: {}", e.getMessage());
            }
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

    /** 空白/非法回退空串（小写）。 */
    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 冷却表条目上限保护（防长期运行内存膨胀；触发时惰性清理最旧一条）。 */
    private void enforceCooldownLimit() {
        if (lastTriggered.size() > COOLDOWN_CACHE_LIMIT) {
            lastTriggered.keySet().stream().findFirst().ifPresent(lastTriggered::remove);
        }
    }

    /**
     * 等待在途异步即时决策排空（测试断言用；超时后返回，不阻塞业务）。
     *
     * @param timeoutMs 最大等待毫秒
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
