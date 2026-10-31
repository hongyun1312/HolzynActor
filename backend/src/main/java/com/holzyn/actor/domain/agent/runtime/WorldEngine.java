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
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.service.WorldEventService;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 世界推进引擎（B3，domain/agent/runtime）——「无人值守持续运行」的核心。
 * <p>目标：把「推进」从「活跃窗口门槛 + 每 3 分钟扫描 +
 * 封顶 24h + 双线并发」重构为「<b>无人值守持续运行 + 按游戏小时逐步推进 + 回补 + 统一暂停 + 预算</b>」。
 * 推进产物流入事件 /感知，为自治环主干（感知→决策→行动→事件→记忆）提供持续驱动。</p>
 * <p>核心语义：</p>
 * <ul>
 * <li><b>常驻开关</b>：每项目 world_running（actor_world_clock.world_running，缺省 1=持续运行）；
 * world_running=0 时引擎完全不推进该项目（替代旧「活跃窗口」，修 L-02/）。</li>
 * <li><b>统一暂停</b>：paused=1 跳过（暂停=时间冻结，修）；两开关正交。</li>
 * <li><b>回补基准</b>：last_engine_run_at（真实时刻）→ 真实流逝 × 速率 → 游戏<b>秒</b>，封顶
 * max_step（缺省 86400 游戏秒=24 游戏小时）防长时间离线后雪崩； 起逐 5 游戏分钟步进（修）。</li>
 * <li><b>三周期分层</b>：感知周期（每 5 游戏分钟：
 * 状态惰性追平）/ 主决策周期（每游戏小时：<b>全部特殊 NPC 每游戏小时决策机会</b>——重要度降序、
 * 预算门面内部门控、脑力分层 full/light→AI、programmatic→纯程序化 + 人群作息 + 跨日边界动作）/
 * 记忆周期（每 24 游戏小时=每游戏日：记忆巩固）。</li>
 * <li><b>预算</b>：BudgetGuard（缺省 5/tick，来自 actor_world_engine.budget_per_tick）——AI 调用
 * 用尽后推进继续但全走程序化兜底（不冻结，修）；预算消耗写 actor_ai_call_log（W-3 可观测）。</li>
 * <li><b>逐时人群</b>：普通 NPC 按引擎传入的游戏小时逐时推进。</li>
 * <li><b>跨日世界事件</b>：机制生成草稿优先 + AI 润色可选（T-4 双通道，预算内）→ publish(kind=world)
 * → 预算内注入最近活跃会话触发在场角色回应。</li>
 * </ul>
 * <p>双推进线收敛（修）：{@link WorldSimulationJob} 改造为本引擎的薄触发器（保留类名兼容手动
 * advance 接口），{@code CrowdScheduledJob} 定时器停用（真实钟人群推进由本引擎逐时取代）；
 * 单实例运行约定 + 内存 AtomicBoolean 防重入 + 项目级循环内 try 隔离。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_WORLD_SIM_ENABLED}（总开关，默认 true）/
 * {@code HOLOZYN_ACTOR_WORLD_AI_POLISH_ENABLED}（跨日事件 AI 润色，默认 true）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEngine {

    /** 记忆巩固触发间隔（游戏小时，缺省 24=每跨 1 游戏日；B4 学习钩子占位） */
    static final int CONSOLIDATE_INTERVAL = 24;

    /** 每游戏日小时数（编年史每日提炼时刻换算用） */
    private static final int HOURS_PER_DAY = 24;

    /** 编年史每日批量提炼时刻（游戏日小时，默认 1=01:00 错峰帧生成 00:00；用户 决策） */
    @Value("${holzyn.actor.world-memory.daily-distill-hour:1}")
    private int dailyDistillHour;

    /** 默认速率（每真实小时推进的游戏小时数） */
    private static final int DEFAULT_RATE = 24;

    /** 机制世界事件模板池（按天轮换，保证多样性与可预测） */
    private static final String[] MECHANISM_EVENT_TEMPLATES = {
            "第%d日：%s一带传出异动——商旅传闻有异常动静，城防加强了巡查，街巷议论纷纷。",
            "第%d日：%s下起了罕见的反常天气，行人纷纷避入檐下，集市被迫提早收摊。",
            "第%d日：%s的集市出现一批来路不明的货物，商贩们压低声音议论着它们的来历。",
            "第%d日：%s发生一起争执事件，围观人群散去后，留下了不少猜测与流言。",
            "第%d日：%s的告示栏贴出新的通告，往来行人驻足阅读，随后各自散去。"
    };

    private final ActorWorldClockRepository clockRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorWorldEngineRepository engineRepository;
    private final WorldLocationRepository locationRepository;
    private final WorldClockService worldClockService;
    private final OrdinaryNpcService ordinaryNpcService;
    private final WorldEventService worldEventService;
    private final AgentRuntime agentRuntime;
    private final PerceptionService perceptionService;
    private final WorldEventBus worldEventBus;
    private final BudgetGuard budgetGuard;

    /**
     * 决策台账服务（P0-03 决策源可追溯 / P0-02 诚实化止血）：决策失败→决策缺失留痕
     * （编年史显式「该时段无决策」，不伪造活动文本）+ 每轮预算统计持久化（防 BudgetGuard
     * endTick 后内存态丢失）。字段注入保持构造器不变；null=未装配[单测]跳过，
     * 台账是观测旁路不阻断推进）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DecisionLedgerService decisionLedgerService;

    /**
     * 决策重试作业服务（P0-02 根治 · 可重试性）：AI 决策失败 → 入队可重试作业（指数退避、
     * max 档不限次数），引擎扫描到点重跑决策并<b>回填到原定 gameSecond</b>；成功以
     * AI/AI_RETRY 落台账，最终放弃才落显式缺失。字段注入保持构造器不变；null=未装配
     * [单测]跳过（退回 P0-02 止血 (a) 的缺失留痕行为）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DecisionRetryService decisionRetryService;

    /** 世界模拟总开关（默认 true；false 时引擎扫描直接跳过） */
    @Value("${holzyn.actor.world.sim-enabled:true}")
    private boolean simEnabled;

    /** 跨日世界事件 AI 润色开关（T-4 双通道第二通道，默认 true；false=仅机制生成） */
    @Value("${holzyn.actor.world.ai-polish-enabled:true}")
    private boolean aiPolishEnabled;

    /** 每 tick AI 预算缺省值（actor_world_engine 行缺失时的兜底，默认 5） */
    @Value("${holzyn.actor.world.max-ai-calls:5}")
    private int maxAiCalls;

    /**
     * 单次推进步长封顶缺省值（游戏秒；actor_world_engine 行缺失时的兜底，默认 86400=24 游戏小时，
     * 防离线后雪崩； 起按秒计，DB 列 max_step_hours 保留按小时换算 ×3600）
     */
    @Value("${holzyn.actor.world.max-catch-up-seconds:86400}")
    private int maxCatchUpSeconds;

    /**
     * 每 tick 推进的游戏分钟数（感知周期粒度，默认 5 游戏分钟=12.5 真实秒@rate=24；
     * 引擎按此步进追平流动时钟，小时级钩子仍按游戏小时边界触发）
     */
    @Value("${holzyn.actor.world.tick-game-minutes:5}")
    private int tickGameMinutes;

    /**
     * 时间回补积压告警阈值（游戏秒，默认 86400=24 游戏小时；P0-01 修复新增）。
     * <p>口径：单轮被推迟量 &gt;0 记 INFO；仅当「当前积压（世界落后量）≥ 阈值且创出新高」才升 WARN——
     * 避免离线回补/长耗时轮次的连续刷屏，同时让「积压持续扩大」的真实异常显眼。</p>
     */
    @Value("${holzyn.actor.world.deferral-warn-threshold-game-seconds:86400}")
    private long deferralWarnThresholdGameSeconds = 86400L;

    /**
     * tick 队列容量（游戏秒，P0-01 根治新增；默认 604800=7 游戏天）。
     * <p>范围式队列 {@code [lastGameSecond, authorizedTargetSecond)} 的有界上限——墙钟/Unity
     * 授权前沿不得超过「已消费位置 + 容量」；超出部分显式计入 {@code droppedGameSeconds} 并 WARN
     * （显式丢弃并记录，不再静默丢失）。默认 7 游戏天：rate=24 时覆盖约 7 真实小时离线，rate=4
     * （更写实的慢速档）时约 42 真实小时（近两天），典型停机/周末都在完整回补范围内。</p>
     */
    @Value("${holzyn.actor.world.tick-queue-capacity-game-seconds:604800}")
    private long tickQueueCapacityGameSeconds = 604800L;

    /** 推进防重入标志（单实例运行约定 + 内存互斥，修） */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    /**
     * tick 屏障（， 「每 tick 等所有请求完成后才推进新 tick」——窄屏障 + 刺激排队
     * + 超时放行，详见 {@link TickBarrier} 类注释）。
     * <p>接线位置：{@link #simulate} 的每个感知 tick（5 游戏分钟）包一层屏障——tick 开始时
     * {@code beginTick}（并派发上一 tick 排队下来的刺激），tick 体执行完 {@code awaitQuiescence}
     * 等在途写回链排空，随后 {@code endTick}。字段注入 {@code required=false}：单测未装配时
     * 全部屏障语义退化为改造前的自由并发（不等待、不排队）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TickBarrier tickBarrier;

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：{@link #scanAndAdvance()} 在未挂载世界时必须直接返回。
     *
     * <p>字段注入 {@code required=false}：AGENTS §8.1.2 禁止改既有服务构造器；单测直接
     * {@code new WorldEngine(...)} 时未装配 → 退化为改造前的直驱行为（既有断言不变）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /**
     * 小时级决策扇出执行器（屏障接线配套，惰性创建）。
     * <p>为什么需要：屏障让「本 tick 的耗时」变成世界推进的真实节拍——一个游戏小时里 N 个角色的
     * 周期决策若串行执行，tick 耗时就等于 N×AI 延迟，世界会被自己的决策拖慢。同一游戏小时的角色
     * 决策彼此独立（各自感知、各自预算、各自角色锁），因此改为有界并发扇出：tick 耗时从 Σ 变 max，
     * 而屏障语义不变（扇出全部返回后才 awaitQuiescence）。{@code parallel-dispatch<=1} 时完全
     * 不使用本执行器（等价改造前的串行行为）。</p>
     */
    private volatile java.util.concurrent.ExecutorService dispatchExecutor;

    /**
     * 跨日边界任务异步执行器。
     * <p>为什么需要：编年史提炼（{@code worldMemoryService.dailyDistillIfNeeded}，avg 数十秒级 AI）、
     * 每日世界帧生成（{@code worldFrameService.generateDailyFrameIfNeeded}，AI 主通道）与记忆批量巩固
     * （{@code memoryConsolidationService.batchConsolidate} + 学习周期巩固）原本在引擎线程的
     * 小时边界 tick 内<b>串行同步执行</b>——小时边界 tick 实测可占数十秒，世界推进出现"堵点"。
     * 这三类任务<b>不改状态曲线</b>（不写 {@code actor_state} 的衰减/情绪位，各自幂等），
     * 因此按「窄屏障设计原则」移出屏障、异步执行：引擎立即继续推进，任务在独立虚拟线程上自取自落。
     * 代价是"当游戏日的帧/提炼/巩固可能稍晚于该游戏时刻落地"——对每日级数据完全可接受。</p>
     */
    private volatile java.util.concurrent.ExecutorService dailyTaskExecutor;

    /** 跨日异步任务在途计数 */
    private final java.util.concurrent.atomic.AtomicInteger dailyTasksInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 空间定期兜底「单飞」标记。
     * <p>为什么需要：可发生空间定期兜底（{@code GrowthSpaceService.periodicRecompute}）是纯维护性
     * 工作，每个引擎小时对全项目 NPC 逐个跑一次 AI 生成；长跑实测 20 人 × 均值 31.5 s ≈ 10.5 真实
     * 分钟/引擎小时，而 rate=24 下一个游戏小时只有 2.5 真实分钟 ⇒ 串行跑在引擎线程上时世界只能推进
     * 到标称速率的 ~22%（{@code scheduling-1} jstack 实证阻塞在 okhttp HTTPS 读）。移到异步虚拟线程
     * 通道后，用本标记保证「上一轮未跑完就不再触发下一轮」，避免慢生成把虚拟线程与网关堆满。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean spaceRecomputeRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 本小时角色决策扇出的等待总预算（秒， jstack 抓修）。
     * <p>为什么需要：扇出后用无上限的 {@code Future.get()} 收集结果，等于把「世界推进线程」的
     * 生死交给单次 AI 调用——实测一次 plan_day 悬挂（对端 TCP 连接半死、既不发数据也不关闭）
     * 让 {@code WorldEngine.dispatchHourlyDecisions} 永久 park，世界冻结 14 分钟以上
     * （jstack 实证：scheduling-1 停在 FutureTask.get()，world-dispatch-2 RUNNABLE 于
     * okhttp3 Http1ExchangeCodec.readResponseHeaders）。改为有界等待：整批角色的等待共享
     * 一个总预算，用尽即放弃等待并继续推进——世界永不因单个 AI 卡死停摆，未完决策的角色这一
     * 游戏小时表现为「没动作」，下一小时自然恢复（远优于整个世界停摆）。</p>
     * <p>取值：{@code holzyn.actor.world.dispatch-wait-seconds} 缺省 180 秒——大于单场景 HTTP
     * 上限（请求 130s），即正常慢调用仍能收齐结果，只有真正悬挂才触发放弃。</p>
     */
    @Value("${holzyn.actor.world.dispatch-wait-seconds:180}")
    private long dispatchWaitSeconds = 180L;

    // ==================== P0-01 时间回补可观测（世界时间不得静默丢弃） ====================

    /**
     * projectId → 时间回补统计（内存态峰值/轮次 + 与落库队列状态同源的快照）。
     * <p>P0-01 根治（tick 任务队列化）后：积压/丢弃的<b>权威</b>状态已持久化到时钟行
     * （authorizedTargetSecond=授权前沿、droppedGameSeconds=显式丢弃累计），本表只保留观测用
     * 峰值/推迟轮次（重启清零可接受，不对正确性负责）；{@link #timeBacklogSnapshot} 聚合两者
     * 暴露到调试端点（对齐 P0-03 的 decision_source 度量基座）。</p>
     */
    private final ConcurrentHashMap<Long, TimeBacklogMetric> timeBacklog = new ConcurrentHashMap<>();

    /** 时间回补统计（单项目，内存态）。 */
    private static final class TimeBacklogMetric {

        /** 最近一轮推进结束时仍未消费的积压（游戏秒；0=已追平） */
        long backlogGameSeconds;

        /** 观测到的峰值积压（游戏秒；即 WARN 高水位，积压创新高时更新） */
        long peakBacklogGameSeconds;

        /** 出现推迟（积压>0）的轮次计数 */
        long deferredRounds;

        /** 最近一轮推进结束时的已消费位置（游戏秒；与 backlogGameSeconds 同快照，供调试端点展示） */
        long committedGameSecond;

        /** 显式丢弃累计（游戏秒；队列溢出时累加，与落库 droppedGameSeconds 同源） */
        long droppedGameSeconds;
    }

    // ==================== R1 引擎 AI 异步化/前瞻缓冲 ====================

    /**
     * 前瞻预热总开关（默认 true；false=退回「小时边界同步扇出等待」旧行为——仅供快速回退）。
     * <p>R1 目标：消除小时边界 4~6 分钟世界时钟停顿（同步扇出等待 27 角色 × 40~88s/次 ÷ 并行度 4）。
     * 做法：① 小时中段（整点前 {@link #lookaheadGameMinutes} 游戏分钟）预热触发下一小时全体角色
     * 决策（异步、不进 tick 屏障、预算走独立批次命名空间）——边界到达时决策已就绪或在途；
     * ② 边界对未预热角色（重启冷启动/暂停恢复/新角色）异步补发不等待——边界 tick 永不阻塞；
     * ③ 去重记账 {@link #preparedHour}：每角色每游戏小时恰好一次决策入口（预热命中即跳过）。</p>
     */
    @Value("${holzyn.actor.world.lookahead.enabled:true}")
    private boolean lookaheadEnabled = true;

    /**
     * 前瞻预热提前量（游戏分钟，默认 15；用户 决策）。
     * <p>语义（B1 口径）：预热决策的感知与行动时间戳 = 预热触发 tick 时刻（如 10:45 触发 11 点档
     * 决策，NPC 立即开始 11 点档的活动，行动自然跨过 11:00 边界——行为连续且相位自然错开）。
     * rate=4 时 15 游戏分钟 = 3.75 真实分钟，远大于扇出 p95 88s → 绝大多数角色边界前完成；
     * 感知只旧 15 游戏分钟，突发由刺激通道（RealTimeDecisionService）即时打断，感知新鲜度无感。</p>
     */
    @Value("${holzyn.actor.world.lookahead.game-minutes:15}")
    private int lookaheadGameMinutes = 15;

    /**
     * R1 去重记账：projectId → 角色 ref → 已派发决策的目标游戏小时（内存态）。
     * <p>预热时记录目标小时（如 10:45 预热 → 记 11），边界到达时命中即跳过（不重复触发）；
     * 边界补发时记录当前小时。小时推进后旧键自然被新值覆盖，无需清理；重启清空 →
     * 重启后首个小时边界全量异步补发（冷启动兜底，边界仍零阻塞）。</p>
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>> preparedHour =
            new ConcurrentHashMap<>();

    /**
     * 普通 NPC AI 化。
     * true=普通 NPC 的每小时决策也获得 AI 预算入口（决策模型 空间候选为主 + Qwen 低频兜底，
     * 每 tick 预算抬到「全 NPC 数 + 余量」）；false=回退 现状（预算 5 只够 5 个高重要度
     * 角色，普通 NPC 预算耗尽走程序化 continue）。用户北极星=最真实世界，不考虑 AI 成本。
     */
    @Value("${holzyn.actor.world.all-npc-ai-enabled:true}")
    private boolean allNpcAiEnabled = true;

    /** 认知注意力/高频微决策服务（每 tick 全 NPC 批量，异步虚拟线程；字段注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.CognitiveAttentionService cognitiveAttentionService;

    /** 沙盘状态服务（S2 clock 广播：推进/暂停后推送时钟事件；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * 作息运行态访问器（治理批次 0 · 卡4-A：引擎程序化决策同步写 actor_state——普通 NPC 感知读方
     * 统一读 actor_state；字段注入避免改构造器，null=未装配[单测]跳过同步）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcActivityStateService npcActivityStateService;

    /** 规划组件（注入：跨日自动生成日计划/目标；字段注入避免改构造器破坏存量测试） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.thinking.PlanningService planningService;

    /** 学习组件（注入：跨 K 游戏小时周期巩固/反思；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.learning.LearningService learningService;

    /** 脑力配置服务（注入：跨日规划按 planning_enabled/脑力档门控；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.brain.service.BrainProfileService brainProfileService;

    /** 事件路由（字段注入保持构造器不变；null=回退旧「最近会话」行为） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.event.EventRoutingService eventRoutingService;

    /** 无玩家群聊服务（注入：剧情节点/概率触发 NPC 自发对话；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.roleplay.AutonomousChatService autonomousChatService;

    /** 状态；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /**
     * 世界演化与游戏时间系统：每日世界帧体系挂钩（记忆周期每游戏日 00:00 生成
     * 当日世界帧；幂等 uk project_id+game_day；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldFrameService worldFrameService;

    /**
     * 世界演化与游戏时间系统：信息包扩散引擎挂钩（主决策周期每游戏小时边界
     * 规则扩散——含长离线回补逐小时物化；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.InfoDiffusionService infoDiffusionService;

    /**
     * 记忆系统重构：项目记忆编年史每日提炼挂钩（③-c 游戏日 01:00 错峰帧生成
     * 批量提炼；幂等=当日 source=daily 条目存在即跳过；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldMemoryService worldMemoryService;

    /**
     * 记忆系统重构：记忆批量巩固编排挂钩（③-d 游戏日边界统一巩固：L1 批量提升/
     * 事实合并/编年史补漏归档；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryConsolidationService memoryConsolidationService;

    /**
     * 世界演化与游戏时间系统：实时决策激活闸门（用户需求：后端启动不自动运行
     * 实时行为决策——默认关，仅打开沙盘/演化页或手动常开时世界才推进；null=未装配[单测]跳过闸门，
     * 生产恒装配；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RealtimeDecisionActivationService realtimeDecisionActivationService;

    /**
     * 独处判定服务（实时行为决策系统 独处行为：周期决策情境注入【独处】段——同地点无其他
     * 特殊 NPC 且无人群 NPC 时 AI 决定独处行为；null=未装配跳过，行为回到）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SolitudeService solitudeService;

    /**
     * 等待类计划服务（实时行为决策系统 时间感行动：每游戏小时边界检查等待类计划到点 →
     * 标记完成 + 重新 AI 决策；null=未装配跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WaitPlanService waitPlanService;

    /**
     * 环境刺激服务（实时行为决策系统 环境刺激：②-g 每游戏小时边界复查帧变化[帧指纹幂等]，
     * null=未装配跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EnvironmentStimulusService environmentStimulusService;

    /**
     * 空闲降频服务（实时行为决策系统 空闲降频：②-a 特殊 NPC 有进行中行动且无新刺激时
     * 本小时周期决策跳过[延续行动零 AI]；null=未装配不降频，行为回到 每小时全量决策）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IdleFrequencyService idleFrequencyService;

    /**
     * 宏观社会演化系统 /：宏观多周期调度器挂钩（感知 tick 循环内按游戏日
     * 小时分钟位触发——人口/经济每日 00:05 槽、势力/舆论每旬首日 00:10 槽、文化/科技每月首日
     * 00:15 槽[留 ]；与每日世界帧 00:00/编年史提炼 01:00 错峰；null=未装配跳过[单测]）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MacroScheduler macroScheduler;

    /**
     * AI 强度闸门（微层接线， 决策预算按项目档位折算——programmatic=0[全程序化]/
     * light=ceil(原值×0.5) / full=标准 / max=Int.MAX[全 AI 必调无预算]；与 runMode 正交叠加，
     * 在 run_mode 折算之后生效；字段注入避免改构造器）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 无玩家群聊总开关（默认 true；false=引擎不触发 NPC 自发对话） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.enabled:true}")
    private boolean autonomousChatEnabled;

    /**
     * U1 外部时钟服务（Unity 连接期间引擎自走跳过——时钟权威=Unity 上报驱动；
     * @Lazy 破环：ExternalClockService 构造依赖本引擎，本字段为反向注入；null=未装配[单测]跳过）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.unity.service.ExternalClockService externalClockService;

    /**
     * 生命周期配置（dispatch.all-npc-hourly 决定普通 NPC 是否逐人走引擎决策；
     * null=未装配[单测]按 行为）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.config.GrowthProperties growthProperties;

    /**
     * 阶段机（每游戏日 dailyMaintenance——出生反推/年龄刷新/阶段跨越/机能系数/
     * 死亡掷点；null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.stage.DevelopmentalStageService developmentalStageService;

    /**
     * 认知 LOD（每游戏小时 refreshProject + 升档清单 → 空间 LOD_PROMOTE 重算；
     * null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.lod.BrainTierService brainTierService;

    /**
     * 此刻可发生空间（每小时定期兜底重算 + LOD 升档重算 + 决策前保障；
     * null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.GrowthSpaceService growthSpaceService;

    /**
     * 人格演化（日检顺带周期评估检查——游戏日达 30 的整数倍触发全项目
     * 异步评估；null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.evolution.PersonalityShiftService personalityShiftService;

    /** 婚育流程（日检——心动/求婚/生育意愿/分娩四检查；null=未装配[单测]跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.romance.MarriageService marriageService;

    /**
     * 婴儿行为（每小时——零 AI 状态机/啼哭刺激父母/位置跟随母亲；
     * null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.infant.InfantBehaviorService infantBehaviorService;

    /**
     * 连续移动状态机（位置移动与地图生成系统 · M2：每 tick 插值推进在途移动，坐标权威仍是
     * ActorSandboxState；null=未装配[单测]跳过移动推进）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /**
     * 新管线移动引擎（地图可编辑布局与 NPC 沿路移动 · M2）：日程/决策 → 沿路轨迹 → 坐标权威。
     *
     * <p>与上面的 {@code movementService} 分属两个坐标域——那个服务旧沙盘（500×500 场景格、
     * 中心化），这个只服务 mapgen 米制地图（1024²、西南原点）。两套并存是刻意的：
     * Unity 端还在读沙盘坐标，硬合并会让老客户端的人站到墙里。
     * null=未装配[单测]跳过，行为与改动前一致。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcMovementService npcMovementService;

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 世界是否「持续运行」（world_running 常驻开关）。
     * <p>存量行（B3 升级前已存在的时钟）该列为 NULL → 视为 0（存量默认关，需用户手动开启，
     * ）；新建时钟行默认 1。</p>
     *
     * @param clock 时钟实体（可空）
     *
     * @return true 表示持续运行
     */
    static boolean isWorldRunning(ActorWorldClock clock) {
        return clock != null && Integer.valueOf(1).equals(clock.getWorldRunning());
    }

    /**
     * AI 强度档位预算折算（静态纯逻辑可单测；与 runMode 正交叠加——在 run_mode 折算后
     * 再按项目档位取最严/最宽语义）。
     *
     * @param budgetPerTick run_mode 折算后的预算
     *
     * @param gate AI 强度闸门（null=未装配[单测]原值返回）
     *
     * @param projectId 项目 ID（null=原值返回）
     *
     * @return programmatic→0 / light→ceil(原值×0.5)（至少 1） / max→Int.MAX / full→原值
     */
    static int intensityBudget(int budgetPerTick, AiIntensityGate gate, Long projectId) {
        if (gate == null || projectId == null) {
            return budgetPerTick;
        }
        if (gate.isMax(projectId)) {
            return Integer.MAX_VALUE;
        }
        if (gate.isLight(projectId)) {
            return Math.max(1, (int) Math.ceil(budgetPerTick * 0.5));
        }
        if (!gate.allowsAi(projectId)) {
            return 0;
        }
        return budgetPerTick;
    }

    /**
     * 真实流逝 → 游戏<b>秒</b>数。
     * <p>⚠️ 关键改动：原 engineElapsedGameHours（真实秒 × rate / 3600 取整）在
     * scan-interval-ms=12500（12.5 真实秒）+ rate=24 下恒为 0（12.5s×24/3600=0.083 游戏小时 → floor=0），
     * 引擎会永远 idle——故 把推进粒度从「游戏小时」细化为「游戏<b>秒</b>」：
     * 每 tick 推进 tick-game-minutes=5 游戏分钟（=12.5 真实秒），秒级换算保证 12.5s 扫描下引擎能推进。</p>
     *
     * @param lastRunAt 引擎上次推进的真实时刻（可空=0）
     *
     * @param now 当前真实时刻
     *
     * @param rate 速率（每真实小时推进的游戏小时数，<=0 视为不换算返回 0）
     *
     * @return 自上次推进起的游戏秒数（>=0）
     */
    static long engineElapsedGameSeconds(LocalDateTime lastRunAt, LocalDateTime now, int rate) {
        if (lastRunAt == null || now == null || rate <= 0) {
            return 0L;
        }
        // 毫秒级精度：Duration.getSeconds() 截断亚秒——12.5s 扫描 + rate=24 下每轮少算 0.5s
        // （288 vs 300 游戏秒）。P0-01 根治后无截断残差累积：生产者锚点在每次授权后置为 now，
        // 亚秒残差随锚点前移自然继承到下一轮 elapsed（锚点=now 使 delta 恒为干净正向增量）；
        // 且被 maxStep/屏障封顶截掉的时间显式留在授权前沿（tick 队列）里，不会丢失也不会重复补。
        long millis = Duration.between(lastRunAt, now).toMillis();
        if (millis <= 0) {
            return 0L;
        }
        return millis * rate / 1000L;
    }

    /**
     * 单次推进步长封顶。
     *
     * @param elapsedSeconds 流逝的游戏秒数
     *
     * @param maxStepSeconds 封顶值（<=0 视为不封顶）
     *
     * @return 封顶后的步长（>=0）
     */
    static long stepOf(long elapsedSeconds, long maxStepSeconds) {
        if (elapsedSeconds <= 0) {
            return 0L;
        }
        return maxStepSeconds > 0 ? Math.min(elapsedSeconds, maxStepSeconds) : elapsedSeconds;
    }

    /**
     * 是否跨越游戏日边界（触发「每游戏日」级推进动作：特殊角色 AI 行动/世界事件）。
     *
     * @param fromHour 起始游戏小时
     *
     * @param toHour 结束游戏小时
     *
     * @return true 表示跨了至少一个游戏日
     */
    static boolean crossesDay(long fromHour, long toHour) {
        return Math.floorDiv(fromHour, 24) != Math.floorDiv(toHour, 24);
    }

    /**
     * 程序化角色状态更新（逐时，零 AI 成本）：深夜歇息、白天按位置忙于日常。
     * <p>与旧 WorldSimulationJob 同口径（B3 收编迁入），静态可测。</p>
     *
     * @param location 角色位置（可空）
     *
     * @param hourOfDay 当日小时（0-23）
     *
     * @return 行动描述文本
     */
    static String programmaticActivity(String location, int hourOfDay) {
        int h = Math.floorMod(hourOfDay, 24);
        String period = WorldClockService.periodText(h);
        if (h >= 23 || h <= 5) {
            return period + "在驻地歇息就寝";
        }
        String loc = location == null || location.isBlank() ? "驻地" : location;
        return period + "在" + loc + "忙于日常事务";
    }

    /**
     * 机制生成跨日世界事件草稿（T-4 双通道第一通道，零 AI 成本）：按游戏日序号从模板池轮换
     * + 优先引用世界观地点名（无地点回退「城中」）。静态可测。
     *
     * @param dayIndex 游戏日序号（第几天，>=1）
     *
     * @param hourOfDay 当日小时（0-23）
     *
     * @param locations 世界观地点名列表（可空/空）
     *
     * @return 事件草稿文本
     */
    static String mechanismWorldEventDraft(int dayIndex, int hourOfDay, List<String> locations) {
        int day = Math.max(1, dayIndex);
        String location = "城中";
        if (locations != null) {
            List<String> named = locations.stream()
                    .filter(n -> n != null && !n.isBlank()).toList();
            if (!named.isEmpty()) {
                location = named.get(Math.floorMod(day - 1, named.size()));
            }
        }
        int idx = Math.floorMod(day - 1, MECHANISM_EVENT_TEMPLATES.length);
        String text = String.format(MECHANISM_EVENT_TEMPLATES[idx], day, location);
        // 充当日时刻描述（如「深夜」），增强成文感
        return text + "（" + WorldClockService.periodText(hourOfDay) + "时分发生）";
    }

    /**
     * 机制世界事件选点：与 {@link #mechanismWorldEventDraft} 完全一致的确定性轮换选点
     * （按游戏日序号 floorMod 地点表），供事件携带 location_id/locationName 做按地点路由/感知过滤。
     *
     * @param dayIndex 游戏日序号（>=1）
     *
     * @param locations 项目地点实体列表（可空/空）
     *
     * @return 选中的地点实体；无地点返回 null（草稿回退「城中」，事件不携带地点）
     */
    static ActorWorldLocation mechanismLocation(int dayIndex, List<ActorWorldLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        List<ActorWorldLocation> named = locations.stream()
                .filter(l -> l != null && l.getName() != null && !l.getName().isBlank()).toList();
        if (named.isEmpty()) {
            return null;
        }
        int day = Math.max(1, dayIndex);
        return named.get(Math.floorMod(day - 1, named.size()));
    }

    /**
     * 事件正文 → 标题（取首行，超长截断；空回退「世界事件」）。
     *
     * @param content 事件正文（可空）
     *
     * @return 标题文本
     */
    static String titleOf(String content) {
        if (content == null || content.isBlank()) {
            return "世界事件";
        }
        String[] lines = content.trim().split("\\n");
        String first = lines.length > 0 && !lines[0].isBlank() ? lines[0].trim() : "世界事件";
        return first.length() > 200 ? first.substring(0, 200) : first;
    }

    // ==================== 扫描与推进 ====================

    /**
     * 推进「当前挂载的世界」一轮（调度壳调用，默认每 12.5 真实秒）。
     *
     * <p>单世界语义（V1.1.2-beta.1 M4）：入口先过世界闸门。世界未挂载时不能靠"路由回落到目录库
     * 于是读不到项目"来兜底——目录库的 {@code actor_project} 恰恰有世界的<b>索引行</b>
     * （id/名字/摘要/游戏时间），遍历推进等于拿索引当世界数据跑。未挂载 → 直接返回。</p>
     *
     * <p>为什么保留"遍历项目"的形态而不是写成 {@code advanceProject(current.id())}：世界库里的
     * {@code actor_project} 只镜像<b>本世界自己那一行</b>（设计 §3.5），所以这个列表在挂载态恒为 1 个元素；
     * 保留循环 + 单项目 try 隔离，未来若做"多世界并行"只需换列表来源，不必重写推进路径。</p>
     */
    public void scanAndAdvance() {
        if (!simEnabled || !scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (worldGate == null) {
                advanceCurrentWorld();   // 单测/未装配：保持既有口径
            } else {
                worldGate.tryMaintenance("WorldEngine.scanAndAdvance", this::advanceCurrentWorld);
            }
        } finally {
            scanning.set(false);
        }
    }

    /** 推进体（闸门之后才允许扫库）：挂载态下 actor_project 只有当前世界一行 */
    private void advanceCurrentWorld() {
        List<ActorProject> projects = projectRepository.findByDeletedOrderByIdAsc(0);
        // ：扫描频率提升到 12.5 真实秒，扫描日志降为 debug 防刷屏（推进日志仍 info）
        log.debug("[世界引擎] 扫描：项目 {} 个", projects.size());
        for (ActorProject project : projects) {
            try {
                advanceProject(project.getId());
            } catch (Exception e) {
                // 单项目推进失败不阻断其他项目
                log.warn("[世界引擎] 项目 {} 推进失败: {}", project.getId(), e.getMessage());
            }
        }
    }

    /**
     * 推进单个项目一轮（定时扫描与手动 advance 共用入口）。
     *
     * @param projectId 项目 ID
     *
     * @return 推进结果 Map（status/paused/advancedHours/gameHour/actions/crowds/events/aiCalls/summary）
     */
    public Map<String, Object> advanceProject(Long projectId) {
        return advanceProject(projectId, LocalDateTime.now());
    }

    /**
     * 推进单个项目一轮（可注入当前真实时刻，便于测试）。
     * <p>流程：项目校验 → 统一暂停检查 → world_running 检查（L-02/）→ <b>实时决策激活
     * 闸门</b>→ 引擎配置懒创建 →
     * 回补步长计算（last_engine_run_at 基准，封顶 max_step_hours）→ 逐游戏小时步进（特殊 NPC /
     * 普通 NPC / 跨日世界事件 / 记忆巩固钩子）→ 更新时钟推进位置。</p>
     *
     * @param projectId 项目 ID
     *
     * @param now 当前真实时刻（推进基准时刻）
     *
     * @return 推进结果 Map
     */
    // ⚠️ 屏障改造（世界运行测试阶段⑤真实生产缺陷，已删除原 @Transactional）：
    // 原实现让「一次推进」整体跑在一个事务里——回补数游戏小时 × 20 个角色 × AI 决策全部挂在同一个
    // 事务上，实测把 actor_state 行锁按住数分钟：56 次 "Timeout trying to lock table"、其中 3 次彻底
    // 丢弃已付费的 AI 结果，跨日重活还把调度线程占住 3.5 分钟、世界整体停摆。世界推进是「多秒级长
    // 流程 + 内部几十个独立业务事务」的编排器，不是原子业务操作——改为无事务编排（每个仓储调用/
    // 服务方法各自成事务），tick 内的因果顺序由 TickBarrier 保证而非由事务边界保证。
    public Map<String, Object> advanceProject(Long projectId, LocalDateTime now) {
        return advanceProject(projectId, now, false);
    }

    /**
     * 推进单个项目一轮（force 版本）：force=true 绕过实时决策激活闸门（手动推进端点用——
     * 用户显式操作，非后台自动输出；仍遵守 paused/world_running 语义）。
     *
     * @param projectId 项目 ID
     *
     * @param force true=强制推进（绕过激活闸门）
     *
     * @return 推进结果 Map
     */
    public Map<String, Object> advanceProjectForce(Long projectId, boolean force) {
        return advanceProject(projectId, LocalDateTime.now(), force);
    }

    /**
     * 推进单个项目一轮（内部完整版本）。
     *
     * @param projectId 项目 ID
     *
     * @param now 当前真实时刻
     *
     * @param force 是否绕过实时决策激活闸门（手动推进=true）
     *
     * @return 推进结果 Map
     */
    // 同上：无事务编排（原 @Transactional 已删除，见 advanceProject 重载处的说明）
    public Map<String, Object> advanceProject(Long projectId, LocalDateTime now, boolean force) {
        ActorProject project = projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
        ActorWorldClock clock = worldClockService.requireClock(projectId);

        // 统一暂停语义（修）：暂停=世界时间冻结，跳过推进
        if (Integer.valueOf(1).equals(clock.getPaused())) {
            publishClockSafe(projectId, 1);
            return baseResult(projectId, "paused", clock.getLastGameHour(), 0, 0, 0, 0, "世界已暂停，跳过推进");
        }
        // 常驻开关（修 L-02/）：world_running=0 → 该项目完全不推进
        if (!isWorldRunning(clock)) {
            return baseResult(projectId, "stopped", clock.getLastGameHour(), 0, 0, 0, 0, "世界未开启持续运行，跳过推进");
        }
        // 实时决策激活闸门（用户需求）：未激活 → 世界暂停（等同 world_running=0 语义，
        // 不耗 AI）；激活 = 手动常开 ∪ 沙盘页打开 ∪ 演化会话进行（RealtimeDecisionActivationService）；
        // force=true（手动推进）绕过——用户显式操作不算后台自动输出。
        if (!force && realtimeDecisionActivationService != null && !realtimeDecisionActivationService.isActive(projectId)) {
            publishClockSafe(projectId, 0);
            return baseResult(projectId, "stopped", clock.getLastGameHour(), 0, 0, 0, 0,
                    "实时决策未激活，世界暂停（打开沙盘/演化页或手动开启后继续推进）");
        }
        // U1 外部时钟权威（，单一权威原则）：Unity 连接期间该世界时钟权威=Unity——
        // 推进由 Unity 时钟上报驱动（ExternalClockService.applyClockReportSync →
        // advanceProjectExternal），本方法（真实流逝×rate 自走）跳过，避免与外部拨钟双写器竞争；
        // 断开后自动回退本路径自走。
        if (externalClockService != null && externalClockService.isExternalClockActive(projectId)) {
            return baseResult(projectId, "unity_clock", clock.getLastGameHour(), 0, 0, 0, 0,
                    "Unity 外部时钟驱动中（推进由 Unity 时钟上报驱动，断开后恢复自走）");
        }

        // P0-02 根治（可重试性）：世界运行中排空到点的决策重试作业（时间可以等作业——
        // 失败决策在后台补算回填到原定 gameSecond）。置于 tick 队列处理之前：即使本轮无
        // 流逝（idle）也照常排空，暂停/未运行/未激活闸门已在上方拦截（重试不耗 AI 于停摆世界）。
        drainDecisionRetries(projectId);

        // 引擎配置（懒创建）+ 预算窗口
        ActorWorldEngine engine = requireEngine(projectId);
        int configuredBudget = engine.getBudgetPerTick() == null ? maxAiCalls : engine.getBudgetPerTick();
        // ：run_mode 三档覆盖每 tick 生效预算（minimal 压到最低 / standard 原值 / ultimate 拉满）
        String runMode = RunModes.normalize(engine.getRunMode());
        int budgetPerTick = RunModes.effectiveBudget(runMode, configuredBudget);
        // 微层接线：AI 强度档位再折算（与 runMode 正交，正交叠加后取最严/最宽语义——
        // programmatic=0 全程序化 / light=ceil×0.5 / full=原值 / max=Int.MAX 全 AI 必调）
        budgetPerTick = intensityBudget(budgetPerTick, aiIntensityGate, projectId);
        // 普通 NPC AI 化：all-npc-ai-enabled=true
        // 且 all-npc-hourly=true 时，每 tick 预算抬到「全 NPC 数 + 余量」——20 个角色全部获得
        // 每小时 AI 决策入口（决策模型 空间候选为主路径、Qwen 低频兜底），不再只有重要度前 5 名
        // 能进 AI 链（DB 实证：普通 NPC brain_level=light，但预算=5 只够 5 特殊 NPC）。
        // 用户北极星=最真实世界、不考虑 AI 成本；回退=all-npc-ai-enabled=false 恢复预算 5 现状。
        if (allNpcAiEnabled && growthProperties != null && growthProperties.isEnabled()
                && growthProperties.getDispatch().isAllNpcHourly()) {
            try {
                long charCount = characterRepository
                        .findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).size();
                budgetPerTick = Math.max(budgetPerTick, (int) charCount + 4);
            } catch (Exception e) {
                log.warn("[世界引擎] 普通 NPC AI 预算抬升计算失败（按原预算继续）: project={} : {}",
                        projectId, e.getMessage());
            }
        }
        // 封顶按游戏秒：DB 列 max_step_hours（小时，存量）→ ×3600 换算；行缺失用全局缺省 max-catch-up-seconds。
        // P0-01 根治后其语义=「屏障关闭时单轮消费上限」；屏障开启（默认）时由 maxCatchUpTicks 收敛（更小，先命中）
        Integer stepHoursCfg = engine.getMaxStepHours();
        long maxStepSeconds = stepHoursCfg != null ? stepHoursCfg * 3600L : maxCatchUpSeconds;
        budgetGuard.beginTick(projectId, budgetPerTick);
        try {
            // ==================== P0-01 根治：tick 任务队列（游戏秒为唯一权威） ====================
            // 权威量：last_game_second=已消费位置（世界实际走到哪）；authorizedTargetSecond=授权前沿
            // （墙钟已授权、尚未被世界消费的队列尾）；两者之差=未消费积压（tick 任务队列——每个
            // tick-game-minutes×60 游戏秒槽位即一个待消费 job）。墙钟只是生产者：真实流逝×rate 只用于
            // 把前沿往后推（有界，容量=tickQueueCapacityGameSeconds），已消费位置只由「消费 tick」前进。
            // 被封顶截掉的时间不再消失：容量内的留在队列里逐轮消费（绝不静默丢弃），超出容量的部分显式
            // 计入 droppedGameSeconds（显式丢弃并记录，WARN 可追溯）——对齐审计「tick 才是 job，墙钟
            // 只是 job 生产者」，不再把时间藏在 last_engine_run_at 与 now 的差值里。
            long fromSecond = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
            long fromHour = fromSecond / 3600L;
            int rate = rateOf(clock);
            int tickSeconds = tickGameMinutes > 0 ? tickGameMinutes * 60 : 300;
            // 队列前沿（存量/首次运行 NULL=尚未授权，视为=已消费位置：无积压；防御性不与已消费位置回退）
            long frontier = clock.getAuthorizedTargetSecond() == null
                    ? fromSecond : Math.max(fromSecond, clock.getAuthorizedTargetSecond());
            // 生产者锚点：last_engine_run_at 优先；兼容 B3 前旧库（活跃窗口时代的 last_sim_time）；
            // 两者皆空=首次运行，以当前为基准不补推
            LocalDateTime base = clock.getLastEngineRunAt();
            if (base == null) {
                base = clock.getLastSimTime();
            }
            if (base == null) {
                base = now;
            }
            // 显式丢弃累计（存量行 NULL=0）
            long dropped = clock.getDroppedGameSeconds() == null ? 0L : clock.getDroppedGameSeconds();
            // ---- 生产相：墙钟流逝 × rate → 授权前沿（有界：溢出显式丢弃并记录） ----
            long elapsed = engineElapsedGameSeconds(base, now, rate);
            long droppedThisRound = 0L;
            if (elapsed > 0) {
                long newFrontier = frontier + elapsed;
                long capacity = Math.max(0L, tickQueueCapacityGameSeconds);
                if (capacity > 0 && newFrontier - fromSecond > capacity) {
                    // 溢出：超出容量的部分显式丢弃并记录（世界落后真实流逝的部分显式计数，不再静默丢失）
                    droppedThisRound = newFrontier - fromSecond - capacity;
                    dropped += droppedThisRound;
                    newFrontier = fromSecond + capacity;
                }
                frontier = newFrontier;
            }
            long backlog = frontier - fromSecond;
            if (elapsed <= 0 && backlog <= 0) {
                // 无流逝且队列空：仅刷新生产者锚点（避免下次仍从空基准计算；窄列更新）。
                // ⚠️ 队列非空（重启/上轮积压）时<b>不得</b>走 idle——积压仍需逐轮消费
                clock.setLastEngineRunAt(now);
                clockRepository.updateEngineBase(projectId, now);
                return baseResult(projectId, "idle", fromHour, 0, 0, 0, 0, "无流逝时间，跳过推进");
            }
            if (backlog < tickSeconds) {
                // 不足一个 tick：把前沿落库（亚 tick 余量显式留在队列，不再藏在基准差值里）、
                // 刷新生产者锚点——任意 rate 都能自洽累计，慢速率表现为 tick 更稀疏而非空转/丢时间。
                // ⚠️ 长跑抓修背景：rate×扫描间隔 < tickSeconds 时 simulate 循环体一次都不进（空转），
                // 根治后该情形显式保留在队列前沿，下一轮扫描继续累计，够了自然推进。
                clockRepository.updateEngineFrontier(projectId, frontier, now);
                recordDeferred(projectId, backlog, droppedThisRound, dropped, fromSecond, "queue");
                return baseResult(projectId, "pending", fromHour, 0, 0, 0, 0,
                        "累计中：不足一个 tick（" + backlog + "/" + tickSeconds + " 游戏秒），留待下一轮扫描");
            }
            // ---- 消费相：本轮最多消费 maxCatchUpTicks 个 tick（屏障语义：宁可走慢，不快进雪崩） ----
            // 屏障让每个 tick 的下限耗时变成「AI 写回链最长耗时」，若一次消费几十个 tick（长时间离线/
            // Unity 拨钟/改 rate 造成的追帧），等待会成倍累积、时钟越追越远。本轮只消费
            // maxCatchUpTicks 个 tick（缺省 24 tick=2 游戏小时），余量留在队列由后续扫描继续追平——
            // 未消费 tick 是显式的（队列积压可观测），不会丢失也不会重复补。
            long drainCap = tickBarrier != null && tickBarrier.isEnabled()
                    ? tickBarrier.maxCatchUpTicks() * (long) tickSeconds : maxStepSeconds;
            long toSecond = fromSecond + Math.min(backlog, drainCap);
            if (backlog > drainCap) {
                log.info("[世界引擎] 回补按单轮消费上限收敛: project={} 积压 {} 游戏秒，本轮消费 {}"
                                + "（上限 {} tick），余量留在队列逐轮追平", projectId, backlog, toSecond - fromSecond,
                        tickBarrier != null && tickBarrier.isEnabled() ? tickBarrier.maxCatchUpTicks() : 0);
            }
            EngineStats stats = simulate(project, fromSecond, toSecond, tickSeconds, budgetPerTick, runMode);

            // P0-03 度量基座：持久化本轮预算统计（预算总量/实际消耗/各来源决策数，台账窗口查询）
            // ——修「BudgetGuard 预算耗尽只在内存、endTick 即 remove」；失败隔离不阻断推进
            if (decisionLedgerService != null) {
                try {
                    decisionLedgerService.persistTickStat(projectId, fromSecond, toSecond,
                            budgetPerTick, stats.aiCalls);
                } catch (Exception e) {
                    log.warn("[世界引擎] tick 预算统计持久化失败（隔离继续）: project={} : {}",
                            projectId, e.getMessage());
                }
            }

            // ---- 收尾：已消费位置前进 + 前沿/丢弃量一并原子落库（窄列更新，与配置侧无覆盖冲突） ----
            // P0-01 修复口径：位置/前沿/丢弃必须同事务提交（torn state 会让下一轮重复消费同一段
            // 游戏时间）。生产者锚点=now：墙钟时刻被「本次授权」消费干净，亚秒残差随锚点前移自然继承，
            // 无止血模式的截断余量问题（见 engineElapsedGameSeconds 注释）。
            clock.setLastGameSecond(toSecond);
            clock.setLastGameHour(toSecond / 3600L);
            clock.setLastEngineRunAt(now);
            clock.setLastSimTime(now);
            clock.setLastSummary(stats.summary);
            // 可观测：本轮结束后队列积压（>0=世界落后真实流逝、正在追平；=0=已追平）；丢弃>0 升 WARN
            recordDeferred(projectId, frontier - toSecond, droppedThisRound, dropped, toSecond, "queue");
            // 长跑缺陷 根治：**窄列更新**（只写引擎列）代替整实体 save——
            // 原实现把整个实体（含 rate/paused/锚点等配置列的事务前快照）回写，与配置侧 PUT 并发时
            // 后写者覆盖前写者，实测 rate 被反复打回旧值、日历投影跑偏（偏移 −196s → +1207s）。
            clockRepository.updateEnginePosition(projectId, toSecond, toSecond / 3600L, now,
                    frontier, dropped, stats.summary);

            // S2 沙盘 clock 广播：推进后推送当前时钟（游戏日/小时/暂停态），失败不影响推进
            publishClockSafe(projectId, 0);

            log.info("[世界引擎] 项目={} 推进 {} 游戏秒（≈{} 游戏小时，{}→{}） 队列积压={} 丢弃={} 角色={} 人群={} 事件={} AI={}/{}"
                            + " 屏障={}次/{}ms(超时{} 排队{}) 扇出超时={} 摘要={}",
                    projectId, toSecond - fromSecond, (toSecond - fromSecond) / 3600L, fromHour,
                    toSecond / 3600L, frontier - toSecond, dropped, stats.actions, stats.crowds, stats.events,
                    stats.aiCalls, budgetPerTick, stats.barrierWaits, stats.barrierWaitedMs,
                    stats.barrierTimeouts, stats.barrierDeferred, stats.dispatchTimeouts, stats.summary);
            return baseResult(projectId, "advanced", toSecond / 3600L, (toSecond - fromSecond) / 3600L,
                    stats.actions, stats.crowds, stats.events, stats.aiCalls, stats.summary,
                    stats.dispatchTimeouts);
        } finally {
            // ⚠️ 冒烟抓修（真实生产缺陷）：预算窗口必须收尾关闭——
            // 原实现 beginTick 后从不 endTick，窗口永久泄漏（首个扫描后 budgetTotal>=0 恒成立且
            // remaining=0），导致「世界暂停期间手动/干预行动」tryConsume 恒失败 → 全部退化为
            // 程序化兜底（沈砚秋经商定向驱动无法产生 AI 决策）；后窗口仅在推进期间存在，
            // 暂停期手动行动由 AgentRuntime ownsTick 自开窗口走 AI 决策链。
            budgetGuard.endTick(projectId);
        }
    }

    /**
     * 外部时钟推进（U1 服务端对接层，Unity clock_report 驱动路径，
     * <p>语义：把引擎推进位置从当前 last_game_second 推进到 toSecond（Unity 上报目标），
     * <b>复用 {@link #simulate} 循环</b>——感知/主决策/记忆三周期与每小时钩子全部照常触发
     * （与真实流逝回补同路径，小时级钩子不丢）；暂停/world_running 语义保留
     * （暂停=时间冻结，仅位置允许上报更新）；预算窗口独立开闭（与自走路径同口径）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param toSecond Unity 上报目标游戏秒（外部时钟服务已做防回退/封顶）
     *
     * @return 推进结果 Map（status: paused/stopped/idle/advanced，与 advanceProject 同结构）
     */
    // 同上：无事务编排（原 @Transactional 已删除，见 advanceProject 重载处的说明）
    public Map<String, Object> advanceProjectExternal(Long projectId, long toSecond) {
        ActorProject project = projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
        ActorWorldClock clock = worldClockService.requireClock(projectId);

        // 暂停语义保留（暂停=世界时间冻结；外部时钟激活期间引擎自走已被跳过，此处再兜底）
        if (Integer.valueOf(1).equals(clock.getPaused())) {
            publishClockSafe(projectId, 1);
            return baseResult(projectId, "paused", clock.getLastGameHour(), 0, 0, 0, 0,
                    "世界已暂停，外部时钟推进跳过");
        }
        if (!isWorldRunning(clock)) {
            return baseResult(projectId, "stopped", clock.getLastGameHour(), 0, 0, 0, 0,
                    "世界未开启持续运行，外部时钟推进跳过");
        }

        // P0-02 根治（可重试性）：外部时钟路径同口径排空到点决策重试（暂停/未运行已在上方拦截）
        drainDecisionRetries(projectId);

        long fromSecond = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
        if (toSecond <= fromSecond) {
            return baseResult(projectId, "idle", fromSecond / 3600L, 0, 0, 0, 0,
                    "外部时钟无推进量（目标 ≤ 当前）");
        }

        // 引擎配置 + 预算（与自走路径同口径：run_mode 三档 + AI 强度档位折算）
        ActorWorldEngine engine = requireEngine(projectId);
        long maxStepSeconds = engine.getMaxStepHours() != null
                ? engine.getMaxStepHours() * 3600L : maxCatchUpSeconds;
        int configuredBudget = engine.getBudgetPerTick() == null ? maxAiCalls : engine.getBudgetPerTick();
        String runMode = RunModes.normalize(engine.getRunMode());
        int budgetPerTick = RunModes.effectiveBudget(runMode, configuredBudget);
        budgetPerTick = intensityBudget(budgetPerTick, aiIntensityGate, projectId);
        budgetGuard.beginTick(projectId, budgetPerTick);
        try {
            // ==================== P0-01 根治：外部时钟路径同队列模型 ====================
            // Unity 是时钟权威：上报目标直接作为授权前沿（单调不回退）；已消费位置只由消费前进。
            // 同自走路径：未消费 tick 显式留在队列里，本轮按 maxCatchUpTicks 收敛，余量由后续上报
            // （或队列本身）继续追平——不再依赖 Unity 重新上报才自愈（旧实现封顶后余量即丢）。
            int tickSeconds = tickGameMinutes > 0 ? tickGameMinutes * 60 : 300;
            long frontier = clock.getAuthorizedTargetSecond() == null
                    ? fromSecond : Math.max(fromSecond, clock.getAuthorizedTargetSecond());
            long dropped = clock.getDroppedGameSeconds() == null ? 0L : clock.getDroppedGameSeconds();
            long droppedThisRound = 0L;
            if (toSecond > frontier) {
                // 上报目标推进前沿（有界：超出容量显式丢弃并记录，避免超长拨钟让队列无限膨胀）
                long newFrontier = toSecond;
                long capacity = Math.max(0L, tickQueueCapacityGameSeconds);
                if (capacity > 0 && newFrontier - fromSecond > capacity) {
                    droppedThisRound = newFrontier - fromSecond - capacity;
                    dropped += droppedThisRound;
                    newFrontier = fromSecond + capacity;
                }
                frontier = newFrontier;
            }
            long backlog = frontier - fromSecond;
            // 消费相：本轮最多消费 maxCatchUpTicks 个 tick（Unity 拨钟/重连巨量回补分多轮追上，
            // 避免一次调用把调度线程与屏障占住数分钟）；外部路径保持「任意正增量都消费」——
            // Unity 细粒度时钟上报（亚 tick）也照常推进，不被 tick 粒度卡住
            long drainCap = tickBarrier != null && tickBarrier.isEnabled()
                    ? tickBarrier.maxCatchUpTicks() * (long) tickSeconds : maxStepSeconds;
            long to = fromSecond + Math.min(backlog, drainCap);
            if (to <= fromSecond) {
                return baseResult(projectId, "idle", fromSecond / 3600L, 0, 0, 0, 0,
                        "外部时钟推进量小于最小步长");
            }
            if (backlog > drainCap) {
                log.info("[世界引擎] 外部时钟推进按单轮消费上限收敛: project={} 目标 {} 本轮 {} 游戏秒"
                                + "（上限 {} tick），余量留在队列由后续上报继续收敛", projectId, frontier,
                        to - fromSecond,
                        tickBarrier != null && tickBarrier.isEnabled() ? tickBarrier.maxCatchUpTicks() : 0);
            }
            EngineStats stats = simulate(project, fromSecond, to, tickSeconds, budgetPerTick, runMode);

            // P0-03 度量基座：持久化本轮预算统计（外部时钟路径同口径；失败隔离不阻断推进）
            if (decisionLedgerService != null) {
                try {
                    decisionLedgerService.persistTickStat(projectId, fromSecond, to,
                            budgetPerTick, stats.aiCalls);
                } catch (Exception e) {
                    log.warn("[世界引擎] tick 预算统计持久化失败（隔离继续）: project={} : {}",
                            projectId, e.getMessage());
                }
            }

            // 时钟推进位置（秒级权威 last_game_second）与队列（前沿/丢弃量）一并原子落库；
            // 生产者锚点=now：断开后自走从当前世界位置正确接续（不补 Unity 连接期间的墙钟流逝）
            clock.setLastGameSecond(to);
            clock.setLastGameHour(to / 3600L);
            clock.setLastEngineRunAt(LocalDateTime.now());
            clock.setLastSimTime(LocalDateTime.now());
            clock.setLastSummary(stats.summary);
            recordDeferred(projectId, frontier - to, droppedThisRound, dropped, to, "external");
            // 同：窄列更新（外部时钟权威路径同步改造，两处推进出口口径一致）
            clockRepository.updateEnginePosition(projectId, to, to / 3600L, LocalDateTime.now(),
                    frontier, dropped, stats.summary);
            publishClockSafe(projectId, 0);

            log.info("[世界引擎] 外部时钟推进：项目={} {}→{} 游戏秒（≈{} 游戏小时） 队列积压={} 丢弃={}"
                            + " 角色={} 人群={} 事件={} AI={}/{} 屏障={}次/{}ms(超时{} 排队{}) 扇出超时={} 摘要={}",
                    projectId, fromSecond, to, (to - fromSecond) / 3600L, frontier - to, dropped,
                    stats.actions, stats.crowds, stats.events, stats.aiCalls, budgetPerTick,
                    stats.barrierWaits, stats.barrierWaitedMs, stats.barrierTimeouts, stats.barrierDeferred,
                    stats.dispatchTimeouts, stats.summary);
            return baseResult(projectId, "advanced", to / 3600L, (to - fromSecond) / 3600L,
                    stats.actions, stats.crowds, stats.events, stats.aiCalls, stats.summary,
                    stats.dispatchTimeouts);
        } finally {
            budgetGuard.endTick(projectId);
        }
    }

    /**
     * 逐步推进。
     * <p>（世界演化与游戏时间系统，）循环改造：原「逐游戏小时」循环（引擎在 12.5s 扫描下
     * 恒 idle）细化为「逐 5 游戏分钟 tick」——每 tick 状态惰性追平、跨小时边界执行小时级钩子。</p>
     * <p>（世界演化与游戏时间系统，）三周期调度分层：</p>
     * <ul>
     * <li><b>感知周期</b>（每 tick-game-minutes 游戏分钟）：NPC 状态惰性衰减追平
     * （NpcStateService.advance，参数=游戏秒）+ 签名去重变化推送（服务内部）；</li>
     * <li><b>主决策周期</b>（每游戏小时边界）：<b>全部特殊 NPC 每游戏小时决策机会</b>
     * （按重要度降序处理——预算在 AgentRuntime 门面内部门控[BudgetGuard.tryConsume]，
     * 高重要度角色先消费预算，预算耗尽后剩余角色在门面内走程序化兜底不冻结；
     * 脑力分层 full/light→预算内 AI、programmatic→纯程序化 0 AI）+
     * 人群作息 scheduleProgrammaticByGameHour（保持每小时）+ 跨日边界世界事件/日计划/目标 +
     * 无玩家概率闲聊（minimal 不触发）；</li>
     * <li><b>记忆/学习/总结周期</b>（每 24 游戏小时=每游戏日）：记忆巩固 scheduleConsolidation
     * 。</li>
     * </ul>
     *
     * @param project 项目实体
     *
     * @param fromSecond 起始游戏秒（引擎推进位置）
     *
     * @param toSecond 目标游戏秒
     *
     * @param tickSeconds 感知周期 tick 长度（游戏秒，默认 300=5 游戏分钟）
     *
     * @param budgetPerTick 本次 tick 预算
     *
     * @param runMode 运行模式（minimal/standard/ultimate； 概率闲聊按档门控）
     *
     * @return 推进统计与摘要（aiCalls=本 tick 实际 AI 预算消耗数）
     */
    private EngineStats simulate(ActorProject project, long fromSecond, long toSecond, int tickSeconds,
                                 int budgetPerTick, String runMode) {
        Long userId = project.getUserId();
        Long projectId = project.getId();
        int actions = 0, crowds = 0, events = 0, missingDecisions = 0;
        // 长跑抓修：调度名单必须排除已故（DECEASED）角色——死亡链路只置 stage 标记
        // 不改 deleted，旧查询会把死人拉进每小时决策/感知/闲聊，产出荒谬行动并浪费 AI 预算
        List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        if (tickSeconds <= 0) {
            tickSeconds = 300; // 配置非法回退 5 游戏分钟
        }

        // 每小时决策机会处理顺序：按重要度降序排序（高重要度角色优先进入决策循环 →
        // 预算内优先获得 AI 决策；低重要度/预算耗尽后在门面内程序化兜底，世界不冻结）
        List<ActorCharacter> decisionPriority = characters.stream()
                .sorted(Comparator.comparingInt(WorldEngine::importanceOf).reversed())
                .toList();

        // 小时级钩子触发基准：起始时刻所在游戏小时（首次跨小时边界时触发新小时）
        long lastHour = Math.floorDiv(fromSecond, 3600L);
        // 认知注意力：每 tick 计数器（按 period-ticks 间隔触发全 NPC 批量）
        long tickIndex = 0;
        // 屏障统计：本次推进累计等了多少次/多久、超时几次、重派发了多少排队刺激
        // （进 EngineStats 摘要与日志，世界运行测试据此核对屏障效果）
        int barrierWaits = 0, barrierTimeouts = 0, barrierDeferred = 0;
        long barrierWaitedMs = 0L;
        // 扇出等待超时放弃的角色数（jstack 抓修可观测项）：>0 说明本 tick 有 AI 调用
        // 悬挂到超过 dispatch-wait-seconds 预算，引擎已放弃等待继续推进（世界不停摆，那些角色
        // 本游戏小时表现为「没动作」）。世界运行测试据此判断是否出现 AI 悬挂。
        // ⚠️ R1 起同步等待被「预热+异步补发」取代，该计数恒为 0（字段保留接口兼容）。
        int dispatchTimeouts = 0;
        // R1 前瞻预热：本小时是否已触发下一小时预热（每小时窗口至多触发一次；
        // 跨小时边界时复位——新的目标小时需要新的预热）
        boolean lookaheadTriggered = false;
        // R1 去重统计：边界时因预热命中被跳过（不重复触发）的角色数（可观测：每角色每游戏小时
        // 恰好一次决策入口——预热命中跳过数 + 边界补发派发数 = 角色数）
        int lookaheadSkipped = 0;
        // R1 异步派发统计：预热 + 边界补发实际派发的角色数（摘要可观测）
        int lookaheadDispatched = 0;
        for (long s = fromSecond + tickSeconds; s <= toSecond; s += tickSeconds) {
            // ==================== ⓪ tick 屏障（，：每 tick 等所有请求完成后才推进） ====================
            // 为什么在 tick 循环最外层：本 tick 派生出的异步写回链（角色决策/刺激即时决策/情绪评估）
            // 必须在「世界时间推进到下一 tick」之前全部落地，否则会出现「NPC 基于 tick N 的感知、
            // 把反应写在 tick N+2」的时序错乱。
            // 用 try/finally 而不是顺序调用：② 主决策周期里有 hour==lastHour 的 continue，且 ①/②/③
            // 任意一步抛异常都不能让屏障 epoch 残留（残留会让后续刺激永久排队 → NPC 不再反应）。
            if (tickBarrier != null) {
                tickBarrier.beginTick(projectId);
                // 刺激排队相的派发：上一 tick「屏障已过」之后到达的刺激在这里统一执行
                // （按到达顺序 → 同一 tick 内因果顺序稳定；执行时本 tick 屏障未过，它们会重新登记在途）
                barrierDeferred += runDeferredStimuli(projectId);
            }
            try {
                // ==================== ① 感知周期（每 5 游戏分钟） ====================
                // NPC 状态惰性衰减追平到当前游戏秒（幂等——行内 game_second 已追平则跳过；
                // 世界暂停 game_second 不推进自然冻结；失败隔离不阻断推进）
                if (npcStateService != null) {
                    try {
                        npcStateService.advance(projectId, s);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 状态衰减推进失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ①-M2 连续移动状态机（位置移动与地图生成系统 · M2）：每 tick 推进在途移动——
                // 按当前游戏秒插值更新 ActorSandboxState.posX/posZ/heading；到点 → arrived 落位 +
                // SSE npc:arrived + 到达挂钩（刺激③）+ moveState 复位。零 AI、失败隔离不阻断推进
                // （未装配[单测]/配置关闭时跳过，既有「瞬移」语义不受影响）。
                if (movementService != null) {
                    try {
                        movementService.tick(projectId, s);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 连续移动推进失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ①-M2 新管线移动引擎（米制地图）：日程段切换 → 沿路寻路 → 按游戏秒推进坐标。
                // 零 AI、失败隔离不阻断推进；未装配[单测]或没有地图时直接跳过。
                if (npcMovementService != null) {
                    try {
                        npcMovementService.tick(projectId, s);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 地图移动推进失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // 认知注意力 + 高频微决策：每 period-ticks（默认 2≈10 游戏分钟）
                // tick 触发一次全 NPC 批量（注意力 score + 即时意向 choice，异步虚拟线程执行、
                // 不进 tick 屏障——真实心智活动本就有延迟，不拖慢世界推进；失败零阻断）
                if (cognitiveAttentionService != null && (tickIndex % attentionPeriodTicks() == 0)) {
                    try {
                        cognitiveAttentionService.tickAsync(projectId, s);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 认知注意力批量触发失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // 宏观社会演化系统 /：宏观多周期 tick 判定（感知 tick 内按游戏日
                // 小时分钟位触发 00:05/00:10/00:15 槽——与帧生成/编年史提炼错峰；纯规则引擎 + 独立
                // AI 预算，失败隔离不阻断推进；长离线回补逐日自然物化）
                if (macroScheduler != null) {
                    try {
                        macroScheduler.tickIfDue(projectId, s);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 宏观多周期 tick 失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ==================== ② 主决策周期（每游戏小时边界） ====================
                long hour = Math.floorDiv(s, 3600L);

                // ==================== ②-R1 前瞻预热（，小时边界零停顿的核心） ====================
                // 本小时末段首次跨过「下一小时边界 − 提前量」的 tick（如 10:45 跨过 11:00−15min）
                // 预热触发下一小时全体角色决策（异步、不进 tick 屏障、预算走独立批次命名空间）——
                // 边界到达时决策已就绪或在途，边界 tick 零等待（消除实测 4~6 分钟停顿）。
                // 感知口径（用户 B1 决策）：感知与行动时间戳 = 预热触发 tick 时刻
                // （NPC 提前开始下一小时的活动，行为连续、相位自然错开；突发由刺激通道即时打断）。
                // 注意：必须放在 `hour == lastHour` 的 continue 之前——continue tick 也要有机会触发。
                if (lookaheadEnabled && lookaheadGameMinutes > 0 && !lookaheadTriggered) {
                    long lookaheadSeconds = lookaheadGameMinutes * 60L;
                    if (s >= (hour + 1) * 3600L - lookaheadSeconds) {
                        lookaheadTriggered = true;
                        DispatchOutcome pre = dispatchHourlyDecisions(decisionPriority, projectId, userId,
                                s, hour + 1, budgetPerTick);
                        actions += pre.completed();
                        missingDecisions += pre.missing();
                        lookaheadDispatched += pre.completed();
                        log.info("[世界引擎] 前瞻预热触发：project={} 目标小时={} 派发角色={} 感知时刻={}（提前 {} 游戏分钟）",
                                projectId, hour + 1, pre.completed(), s, lookaheadGameMinutes);
                    }
                }
                if (hour == lastHour) {
                    continue;
                }
                lastHour = hour;
                // 新小时 → 新的目标小时（hour+1）待预热
                lookaheadTriggered = false;
                int hourOfDay = (int) Math.floorMod(hour, 24);
                boolean dayCrossed = crossesDay(hour - 1, hour);

                // ②-a 特殊 NPC： 起每游戏小时全部角色获得决策机会（不再限跨日边界 + 重要度门槛）。
                // 统一经 AgentRuntime——感知→记忆→思考→行动→学习，预算在门面内部门控
                // （BudgetGuard.tryConsume），用尽程序化兜底不冻结（修）；脑力分层 brainProfile
                // 由门面解析（programmatic 纯程序化 0 AI）；失败程序化兜底不阻断推进。
                // R1：改为「预热命中跳过 + 未预热异步补发」——本小时决策已由
                // 前瞻预热提前触发（去重记账 preparedHour 命中即跳过），未预热角色（重启冷启动/
                // 暂停恢复/新角色）在此异步补发不等待；边界 tick 永不阻塞（见 dispatchHourlyDecisions）。
                DispatchOutcome dispatched = dispatchHourlyDecisions(decisionPriority, projectId, userId,
                        hour * 3600L, hour, budgetPerTick);
                actions += dispatched.completed();
                missingDecisions += dispatched.missing();
                dispatchTimeouts += dispatched.timedOut();
                lookaheadSkipped += dispatched.skipped();
                lookaheadDispatched += dispatched.completed();

                // ②-b 普通 NPC： ——
                // dispatch.all-npc-hourly=true 时普通 NPC 已由 ②-a 逐人走引擎决策（感知/思考/行动/
                // 空间全量消费），此处程序化作息批次跳过（避免双写 current_activity 打架）；
                // false=回退 行为：逐游戏小时程序化状态机（引擎传入游戏时刻，废除真实钟驱动路径）
                if (growthProperties == null || !growthProperties.isEnabled()
                        || !growthProperties.getDispatch().isAllNpcHourly()) {
                    try {
                        ordinaryNpcService.scheduleProgrammaticByGameHour(projectId, hourOfDay, hour);
                        crowds++;
                    } catch (Exception e) {
                        log.warn("[世界引擎] 普通型 NPC 推进失败: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ②-h 认知 LOD + 此刻可发生空间（，每游戏小时边界）：
                // ① 认知 LOD 刷新（重要度/事件/同地/关系/相机 → 档位重算 + 升档清单）；
                // ② 升档角色空间按 LOD_PROMOTE 重算（升档=认知焦点扩大，空间必须随之更新）；
                // ③ 全项目空间定期兜底（focus 1 游戏小时 / ambient 6 游戏小时各自周期重算，
                // dormant 跳过零 AI）。均失败隔离不阻断推进。
                if (growthProperties != null && growthProperties.isEnabled()) {
                    List<String> promotions = null;
                    if (brainTierService != null) {
                        try {
                            promotions = brainTierService.refreshProject(projectId, hour * 3600L);
                        } catch (Exception e) {
                            log.warn("[世界引擎] LOD 刷新失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                        }
                    }
                    final List<String> promotedRefs = promotions == null ? List.of() : promotions;
                    // ⚠️ 长跑抓修（世界推进被**同步 AI** 堵死）：②（升档空间重算）与
                    // ③（定期兜底）原本在引擎线程同步执行，单次 `growth_space_generate` 26~57 秒
                    // （约 1800 输出 token）；重启后首个游戏小时全部角色到期 → 串行 22 次 ≈ 10 分钟，
                    // 引擎线程直接卡在 AI 的 HTTP 读取里（jstack 实证：scheduling-1 停在
                    // okhttp readResponseHeaders，world-dispatch-* 全空闲），世界推进冻结、
                    // 日历-引擎偏移从 2704s 涨到 6370s。
                    // 处置：与「日级重活异步」同口径改走 runDailyTaskAsync（虚拟线程、不进 tick 屏障、
                    // 异常隔离）——空间只是决策用的**缓存**，后台补算期间决策读到旧空间完全可接受
                    // （dispatchOneCharacter 内 ensureDecisionSpace 仍会兜底保证空间有效）。
                    if (growthSpaceService != null) {
                        // 长跑缺陷（同步空间 AI 阻塞世界推进）根治：升档 + 定期兜底
                        // 统一走 runDailyTaskAsync（虚拟线程、不进 tick 屏障、异常隔离）——长跑实测
                        // growth_space_generate 26~57s/次，串行跑在引擎线程会让世界只能推进到标称速率
                        // 的 ~22%（jstack 实证 scheduling-1 阻塞在 okhttp HTTPS 读）；异步后世界时钟
                        // 不再等待空间生成，空间只是决策用的缓存，后台补算期间读到旧空间完全可接受。
                        // 配套（同轮）：GrowthProperties 定期兜底周期 60→360 / 360→720 游戏分钟——
                        // 异步只是不再阻塞引擎，兜底本身仍占网关（与角色决策争抢推理容量），
                        // 周期必须与「全项目一轮重算的真实耗时」同量级才不会再拖慢世界推进。
                        // 合并补回：单飞标记——上一轮（升档+兜底）
                        // 未跑完时本轮触发直接跳过，慢生成不得按小时边界堆叠占满虚拟线程与网关。
                        if (!spaceRecomputeRunning.compareAndSet(false, true)) {
                            log.debug("[世界引擎] 空间重算上一轮仍在执行，跳过本轮触发: project={} hour={}",
                                    projectId, hour);
                        } else {
                            runDailyTaskAsync(projectId, "可发生空间重算（升档+定期兜底）", () -> {
                                try {
                                    for (String promotedRef : promotedRefs) {
                                        try {
                                            growthSpaceService.triggerRegenerate(projectId, promotedRef,
                                                    com.holzyn.actor.domain.growth.space.GrowthSpaceTrigger.LOD_PROMOTE,
                                                    hour * 3600L);
                                        } catch (Exception e) {
                                            log.warn("[世界引擎] LOD 升档空间重算失败（隔离继续）: ref={} : {}",
                                                    promotedRef, e.getMessage());
                                        }
                                    }
                                    if (!promotedRefs.isEmpty()) {
                                        log.info("[世界引擎] LOD 升档 {} 个角色（认知焦点扩大，空间重算转异步）: {}",
                                                promotedRefs.size(), promotedRefs);
                                    }
                                    try {
                                        growthSpaceService.periodicRecompute(projectId, hour * 3600L);
                                    } catch (Exception e) {
                                        log.warn("[世界引擎] 空间定期兜底失败（隔离继续）: project={} : {}",
                                                projectId, e.getMessage());
                                    }
                                } finally {
                                    spaceRecomputeRunning.set(false);
                                }
                            });
                        }
                    }
                    // ：婴儿行为状态机每小时推进（零 AI：需求衰减/啼哭刺激父母/
                    // 位置跟随母亲；内部零阻断）
                    if (infantBehaviorService != null) {
                        try {
                            infantBehaviorService.advanceInfants(projectId, hour * 3600L);
                        } catch (Exception e) {
                            log.warn("[世界引擎] 婴儿行为推进失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                        }
                    }
                    // ②-i 全生命周期阶段机：每游戏日 00:00（跨日边界）日检——
                    // 出生反推（存量缺失补齐）/ 年龄刷新 / 阶段跨越（里程碑+AI 叙事+世界事件+空间重算）/
                    // 机能系数刷新 / 死亡掷点（默认关）。日级重活异步执行（与编年史/帧生成同通道，
                    // 不占引擎线程的小时边界 tick；dailyMaintenance 幂等）
                    if (dayCrossed && Math.floorMod(hour, HOURS_PER_DAY) == 0
                            && developmentalStageService != null) {
                        runDailyTaskAsync(projectId, "全生命周期日检", () -> {
                            developmentalStageService.dailyMaintenance(projectId, hour * 3600L);
                            // ：日检顺带人格演化周期检查（游戏日达 30 整数倍 →
                            // 全项目证据充分者异步评估 + 面具固化；内部零阻断）
                            if (personalityShiftService != null) {
                                try {
                                    personalityShiftService.cycleCheck(projectId, hour * 3600L);
                                } catch (Exception e) {
                                    log.warn("[世界引擎] 人格演化周期检查失败（隔离继续）: project={} : {}",
                                            projectId, e.getMessage());
                                }
                            }
                            // ：日检顺带婚育流程（心动/求婚/生育意愿/分娩四检查；
                            // 内部零阻断）
                            if (marriageService != null) {
                                try {
                                    marriageService.dailyCheck(projectId, hour * 3600L);
                                } catch (Exception e) {
                                    log.warn("[世界引擎] 婚育日检失败（隔离继续）: project={} : {}",
                                            projectId, e.getMessage());
                                }
                            }
                        });
                    }
                }

                // ②-c 跨日边界：世界事件「机制生成优先 + AI 润色可选」双通道（T-4，保持）——
                // 保持引擎线程同步执行（润色/路由预算门控依赖本 tick 窗口，且仅 1~2 次 AI 调用，
                // 失败各有既有兜底；实测 ~1 分钟内完成，不构成冻结源）
                if (dayCrossed) {
                    try {
                        advanceWorldEvent(project, hour, hourOfDay, budgetPerTick);
                        events++;
                    } catch (Exception e) {
                        // 事件生成失败不阻断推进（世界仍有角色/人群在动）
                        log.warn("[世界引擎] 跨日世界事件生成失败: project={} : {}", projectId, e.getMessage());
                    }
                    // ：跨日边界为特殊 NPC 自动生成日计划（双保险之跨日通道）与目标（全自动 AI 生成）。
                    // 冒烟抓修（R1「引擎线程同步 AI 钩子清理」补漏）：原为引擎线程同步执行——
                    // plan_day 每角色 40~120s 串行，网关慢时实测把引擎冻结 10+ 分钟（世界卡在日边界，
                    // 反思/世界帧等日级任务全部延后）。与 R1 已清理的 reactCrowds / WaitPlanService /
                    // 宏 tick 同族，改 runDailyTaskAsync 虚拟线程异步（跨日重活屏障外执行先例）——
                    // 引擎立即继续推进，计划/目标异步落库；失败各有既有兜底（机制计划），零阻断。
                    runDailyTaskAsync(projectId, "跨日日计划", () -> {
                        try {
                            advanceDayPlanning(project, hour);
                        } catch (Exception e) {
                            log.warn("[世界引擎] 跨日目标/日计划生成失败（降级继续）: project={} : {}", projectId, e.getMessage());
                        }
                    });
                }

                // ②-d 无玩家群聊：平时概率闲聊（每游戏小时每地点按概率触发，预算门控；
                // run_mode=minimal 不触发概率闲聊——省钱优先；事件回应轮在跨日事件内接线，minimal 保留）
                // 长跑 修复：改为异步入口——群聊剧集在虚拟线程推进，
                // 不再独占 scheduling-1 线程阻塞世界引擎（v1 实测群聊死循环冻结世界 30+ 分钟）
                if (autonomousChatService != null && autonomousChatEnabled && !RunModes.isMinimal(runMode)) {
                    try {
                        autonomousChatService.triggerProbabilityAsync(project, hour);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 无玩家概率闲聊失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ②-e 世界演化与游戏时间系统：信息包扩散——每游戏小时边界规则扩散
                // （InfoDiffusionService：按 spread_speed×距离计算到达范围，物化 actor_info_diffusion；
                // 含长离线回补逐小时物化，失败降级继续不阻断推进）
                // ⚠️ 修复（真跑实证「运行一会就停一下」）：本调用带 @Transactional，
                // 事务内只做规则计算与扩散行物化，**零 AI**；到达事件的区域传闻 AI 改写 + 事件发布
                // 已改为「事务提交后由 diffusion-arrival 线程池异步执行」（原来在本事务内串行同步，
                // AI 在 H2 行锁内跑了 4 分钟以上 → 本线程（scheduling-1）冻结、扩散行整轮不可见；
                // 最坏 14 包×15 区域=210 次串行 AI ≈ 13 分钟，失真缓存键含 gameDay ⇒ 每个游戏日复发）。
                // 详见 InfoDiffusionService.scheduleArrivalPublishes 的现场证据与修法。
                if (infoDiffusionService != null) {
                    try {
                        infoDiffusionService.diffuseProject(projectId, hour * 3600L);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 信息包扩散失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ②-f 实时行为决策系统：时间感行动——等待类计划到点检查
                // （actor_plan.planned_game_second ≤ 当前游戏秒 → 标记完成 + 重新 AI 决策；
                // ：到点重新决策，原动作仅作提示；连续等待超限直接执行原动作；
                // 失败降级继续不阻断推进）
                if (waitPlanService != null) {
                    try {
                        waitPlanService.runDueWaitPlans(projectId, hour * 3600L);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 等待计划到点处理失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ②-g 实时行为决策系统：环境刺激——每游戏小时边界复查当前生效帧
                // 与上次 diff 快照（帧指纹幂等：帧生成/紧急更新已由 WorldFrameService 挂钩主通道
                // diff，此处为安全网捕捉未走帧钩子的就地变化； 「帧 diff + 每小时边界
                // 复查」双轨检测；失败降级继续不阻断推进）
                if (environmentStimulusService != null) {
                    try {
                        environmentStimulusService.checkHourly(projectId, hour * 3600L);
                    } catch (Exception e) {
                        log.warn("[世界引擎] 环境刺激每小时复查失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                }

                // ==================== ③ 记忆/学习/总结周期（每 24 游戏小时=每游戏日，保持） ====================
                // 每跨 K 游戏小时（缺省 24）：记忆巩固/反思/关系/生态钩子（起真实调用
                // LearningService.scheduleConsolidation； 显式标注为记忆周期，节奏不变）。
                // ：跨日重活（巩固/世界帧/编年史提炼/批量巩固）统一改为屏障外异步执行
                // （待收口项②）——不再占住引擎线程的小时边界 tick。
                if (Math.floorMod(hour, CONSOLIDATE_INTERVAL) == 0 && learningService != null) {
                    runDailyTaskAsync(projectId, "周期巩固/反思", () ->
                            learningService.scheduleConsolidation(projectId, hour));
                }
                // ③-b 世界演化与游戏时间系统：每日世界帧体系——每游戏日 00:00（跨日边界
                // 所在时刻，hour 为 24 的倍数）生成当日世界帧（天气/宏观事件/信息包/环境变化，AI 主通道
                // +机制兜底双通道 + 边界校验 + 缓存 24h[uk project_id+game_day 幂等]）；失败降级继续不阻断推进
                if (Math.floorMod(hour, CONSOLIDATE_INTERVAL) == 0 && worldFrameService != null) {
                    runDailyTaskAsync(projectId, "每日世界帧生成", () ->
                            worldFrameService.generateDailyFrameIfNeeded(projectId, Math.floorDiv(hour, 24), hour * 3600L));
                }
                // ③-c 记忆系统重构：编年史每日批量提炼——游戏日 01:00 错峰帧生成
                // （：错峰到 01:00，编年史提炼能引用当日帧天气；幂等=当日 source=daily 存在跳过；
                // AI 独立每日预算 + 机制兜底；失败降级继续不阻断推进）
                if (Math.floorMod(hour, HOURS_PER_DAY) == dailyDistillHour && worldMemoryService != null) {
                    runDailyTaskAsync(projectId, "编年史每日提炼", () ->
                            worldMemoryService.dailyDistillIfNeeded(projectId, Math.floorDiv(hour, 24), hour * 3600L));
                }
                // ③-d 记忆系统重构：批量巩固统一编排——游戏日边界（00:00）触发
                // MemoryConsolidationService.batchConsolidate（L1 草稿批量提升/事实合并/编年史补漏+归档；
                // 与既有 scheduleConsolidation 并列，幂等无副作用；失败降级继续不阻断推进）
                if (Math.floorMod(hour, CONSOLIDATE_INTERVAL) == 0 && memoryConsolidationService != null) {
                    runDailyTaskAsync(projectId, "记忆批量巩固", () ->
                            memoryConsolidationService.batchConsolidate(projectId, hour));
                }
            } finally {
                // 屏障收尾：等本 tick 在途写回链排空 → 标记「屏障已过」（此后新到的刺激
                // 排队到下一 tick 的派发相）→ 结束本 tick。超时按「超时放行」处理（世界永不被慢依赖
                // 冻结；连续超时由 TickBarrier 自愈清零，防某个漏 complete 的 bug 每 tick 白等）。
                if (tickBarrier != null) {
                    TickBarrier.AwaitResult awaited = tickBarrier.awaitQuiescence(projectId);
                    if (awaited.waitedMs() > 0) {
                        barrierWaits++;
                        barrierWaitedMs += awaited.waitedMs();
                    }
                    if (awaited.timedOut()) {
                        barrierTimeouts++;
                    }
                    tickBarrier.endTick(projectId);
                }
            }
            tickIndex++;
        }

        // ：aiCalls 取本 tick 预算守卫实际消耗数（AgentRuntime 内部 AI 调用 + 跨日事件润色/注入等
        // 全部经 BudgetGuard.tryConsume 记账，比「进入决策循环的角色数」更能反映真实 AI 开销）
        int aiCalls = budgetGuard.used(projectId);

        String summary = "游戏推进至" + WorldClockService.formatGameTime(toSecond / 3600L)
                + "，角色行动 " + actions + " 次，普通居民调度 " + crowds + " 次，世界事件 " + events + " 起。"
                + (missingDecisions > 0
                ? "⚠️ 决策缺失 " + missingDecisions + " 次（决策失败，本时段暂无决策记录，不伪造活动文本"
                + "；已入队重试的将补算回填原定时刻）。"
                : "")
                + (lookaheadSkipped > 0 || lookaheadDispatched > 0
                ? "前瞻预热（提前 " + lookaheadGameMinutes + " 游戏分钟），边界跳过 " + lookaheadSkipped
                + " 个已预热角色，异步派发 " + lookaheadDispatched + " 个。"
                : "")
                + (barrierWaits > 0
                ? "屏障等待 " + barrierWaits + " 次/" + barrierWaitedMs + "ms"
                + (barrierTimeouts > 0 ? "（超时放行 " + barrierTimeouts + " 次）" : "")
                + "，排队刺激重派发 " + barrierDeferred + " 个。"
                : "")
                + (dispatchTimeouts > 0
                ? "⚠️ 有 " + dispatchTimeouts + " 个角色因 AI 调用悬挂超过等待预算被跳过决策"
                + "（世界照常推进，其 AI 结果不再计入本小时）。"
                : "");
        return new EngineStats(actions, crowds, events, aiCalls, dispatchTimeouts, barrierWaits, barrierWaitedMs,
                barrierTimeouts, barrierDeferred, lookaheadSkipped, missingDecisions, summary);
    }

    /**
     * 一个游戏小时的「全部 NPC 决策机会」派发（R1 起为<b>异步派发</b>：
     * 预热命中跳过 + 未预热异步补发，引擎线程永不阻塞）。
     * <p><b>R1 改造背景</b>：原实现（有界并发扇出）在小时边界<b>同步等待</b>全部角色
     * AI 决策收齐（并行度 4 × 27 角色 × 40~88s/次 → 边界 tick 实测 4~6 分钟），世界时钟
     * 「冻结 4~6 分钟再跳」。R1：
     * 小时中段预热下一小时决策（感知=预热时刻，B1 口径），边界对未预热角色<b>异步补发不等待</b>，
     * 在途任务跳过不等待（跑完照常落库，不丢已付费 AI 结果）。</p>
     * <p><b>调用方语义</b>（{@link #simulate} 两处）：</p>
     * <ul>
     * <li>预热（目标小时 = 当前小时+1，感知时刻 = 预热触发 tick 秒）——小时中段首次跨过
     * 「下一小时边界 − 提前量」时触发；</li>
     * <li>边界（目标小时 = 当前小时，感知时刻 = 边界整点）——对未预热角色异步补发。</li>
     * </ul>
     * <p><b>去重</b>：{@link #preparedHour} 按「角色 × 目标小时」记账——预热命中（边界时
     * preparedHour[ref] == 当前小时）即跳过，每角色每游戏小时恰好一次决策入口
     * （预热派发数 + 边界补发数 = 角色数，skipped 可观测）。</p>
     * <p><b>执行模型</b>：并行度&gt;1（屏障开启）→ 提交扇出线程池异步执行，任务内标记
     * {@code AgentRuntime.inBatchDispatch()}（不进 tick 屏障、预算走独立批次命名空间
     * {@code BudgetGuard.beginBatch/tryConsumeBatch}）；并行度=1（屏障未装配/已关闭）→
     * 引擎线程串行执行（逐行等价改造前行为，单测兼容）。</p>
     *
     * @param decisionPriority 按重要度降序排序的角色列表
     *
     * @param projectId 项目 ID
     *
     * @param userId 项目所属用户 ID（决策上下文透传）
     *
     * @param gameSecond 决策感知时刻（预热=预热触发 tick 秒；边界=边界整点秒）
     *
     * @param targetHour 本批决策归属的目标游戏小时（去重键；预热=当前小时+1，边界=当前小时）
     *
     * @param batchBudget 异步批次预算（并行路径开 {@code BudgetGuard} 批次窗口用）
     *
     * @return 派发结果：{@code completed}=本批实际派发的角色数（异步路径=提交数，串行路径=完成数），
     * {@code timedOut}=恒 0（异步不等待，字段保留接口兼容），{@code skipped}=预热命中跳过数
     */
    private DispatchOutcome dispatchHourlyDecisions(List<ActorCharacter> decisionPriority, Long projectId, Long userId,
                                                    long gameSecond, long targetHour, int batchBudget) {
        if (decisionPriority == null || decisionPriority.isEmpty()) {
            return new DispatchOutcome(0, 0, 0, 0);
        }
        // 去重记账：预热命中（本目标小时已派发过）跳过；未派发则记录并加入本批
        Map<String, Long> prepared = preparedHour.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
        List<ActorCharacter> toDispatch = new ArrayList<>(decisionPriority.size());
        int skipped = 0;
        for (ActorCharacter ch : decisionPriority) {
            String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            Long preparedHourFor = prepared.get(ref);
            if (preparedHourFor != null && preparedHourFor == targetHour) {
                skipped++;
                continue;
            }
            prepared.put(ref, targetHour);
            toDispatch.add(ch);
        }
        if (toDispatch.isEmpty()) {
            return new DispatchOutcome(0, 0, skipped, 0);
        }
        int parallelism = dispatchParallelism();
        if (parallelism <= 1) {
            // 串行路径：屏障未装配（单测）/已关闭（barrier.enabled=false）/并行度=1 → 引擎线程同步执行
            int serial = 0;
            int missing = 0;
            for (ActorCharacter ch : toDispatch) {
                int r = dispatchOneCharacter(ch, projectId, userId, gameSecond);
                if (r == 2) {
                    missing++; // 决策缺失（决策失败，本时段无决策记录）
                } else if (r == 1) {
                    serial++;
                }
            }
            return new DispatchOutcome(serial, 0, skipped, missing);
        }
        // 异步路径：提交扇出线程池即返回，引擎永不阻塞
        ExecutorService pool = dispatchPool();
        // 批次预算窗口：与引擎 tick 窗口隔离的独立命名空间（任务跨引擎扫描窗口运行时仍可消费）；
        // 引用计数管理重叠批次（预热批次未排空又触发补发），最后一个任务完成时整体关闭
        budgetGuard.beginBatch(projectId, Math.max(1, batchBudget));
        AtomicInteger inFlight = new AtomicInteger(toDispatch.size());
        for (ActorCharacter ch : toDispatch) {
            try {
                pool.submit(() -> {
                    AgentRuntime.markBatchDispatch();
                    try {
                        dispatchOneCharacter(ch, projectId, userId, gameSecond);
                    } finally {
                        AgentRuntime.clearBatchDispatch();
                        if (inFlight.decrementAndGet() == 0) {
                            budgetGuard.endBatch(projectId);
                        }
                    }
                });
            } catch (Exception e) {
                // 提交失败（停机中）：释放批次计数，角色本小时无决策（下一小时预热/补发自然恢复）
                log.warn("[世界引擎] 角色决策异步派发提交失败（该角色本小时跳过）: project={} ref={} : {}",
                        projectId, ch.getId(), e.getMessage());
                inFlight.decrementAndGet();
                if (inFlight.get() == 0) {
                    budgetGuard.endBatch(projectId);
                }
            }
        }
        return new DispatchOutcome(toDispatch.size(), 0, skipped, 0);
    }

    /**
     * 本小时角色决策扇出的等待总预算（秒；配置 holzyn.actor.world.dispatch-wait-seconds，默认 180）。
     * <p>⚠️ R1 起同步等待被「预热+异步补发」取代，本预算不再被使用（字段保留兼容，
     * 供回退旧行为与配置解析测试）。</p>
     */
    private long dispatchWaitBudgetSeconds() {
        return dispatchWaitSeconds > 0 ? dispatchWaitSeconds : 180L;
    }

    /** 认知注意力批量周期（tick 数，默认 2≈10 游戏分钟；growth.attention.period-ticks）。 */
    private long attentionPeriodTicks() {
        if (growthProperties != null && growthProperties.isEnabled()
                && growthProperties.getAttention() != null) {
            int p = growthProperties.getAttention().getPeriodTicks();
            return p > 0 ? p : 2;
        }
        return 2;
    }

    /**
     * 此刻可发生空间：感知组装前空间保障（空间缺失/过期 → 按 LOD 档位生成；有效复用零成本）。
     * <p>感知情境中的空间段由 {@code PerceptionService.buildPerceptionText} 注入，本方法保证
     * 「每小时决策感知时空间恒有效」——任何失败零阻断（感知照常组装，程序化块兜底）。</p>
     */
    private void ensureDecisionSpace(Long projectId, String npcRef, Long gameSecond) {
        if (growthSpaceService == null || projectId == null || npcRef == null) {
            return;
        }
        try {
            growthSpaceService.ensureSpace(projectId, npcRef, gameSecond);
        } catch (Exception e) {
            log.warn("[世界引擎] 此刻可发生空间保障失败（感知照常组装，零阻断）: ref={} : {}",
                    npcRef, e.getMessage());
        }
    }

    /**
     * 单个角色的每小时周期决策（从 {@link #simulate} 串行循环抽出；R1
     * 感知时刻参数由「整点 hour」细化为「gameSecond」——预热批次传预热触发 tick 秒，B1 口径）。
     * <p>内容与改造前逐行一致：空闲降频跳过 → {@link #attemptDecisionOnce} 组装【当前所见】
     * 感知文本 + 追加【独处】段 + {@code AgentRuntime.trigger(ENGINE_TICK)}；任何异常 →
     * <b>P0-02 根治（可重试性）</b>：先入队决策重试作业（指数退避；max 档不限次数），入队
     * 成功 = 本时段暂无决策、等待补算（<b>不</b>立即落缺失、不伪造活动文本）；入队失败
     * （未启用/队列满/服务未装配）→ 决策缺失留痕（P0-02 止血 (a)：current_activity 保持
     * 上一小时值不变，落台账 + 编年史显式「该时段无决策」）。推进永不阻断。</p>
     *
     * @param ch 角色实体
     *
     * @param projectId 项目 ID
     *
     * @param userId 项目所属用户 ID
     *
     * @param gameSecond 决策感知时刻（游戏秒：预热=预热触发 tick 时刻；边界补发=边界整点）
     *
     * @return 1=本小时完成一次决策（AI 或程序化兜底），0=空闲降频跳过（延续当前行动，零 AI），
     * 2=决策缺失（决策失败；已入队重试=本时段暂无决策待补算，或未入队=本时段无决策记录）
     */
    private int dispatchOneCharacter(ActorCharacter ch, Long projectId, Long userId, long gameSecond) {
        long hour = Math.floorDiv(gameSecond, 3600L);
        String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
        try {
            // 空闲降频：有进行中行动
            // （最近决策过，距上次决策 < idle-decision-window-game-minutes）且无新刺激 →
            // 本小时周期决策跳过（延续当前行动零 AI）；行动完成[窗口过后]或有刺激
            // [即时通道立即响应]自动恢复。真人不会每分钟都想一次「我该干嘛」——
            // 省 AI 成本且行为更真实（决策频率随情境自适应，对齐自适应决策频率研究）。
            if (idleFrequencyService != null && idleFrequencyService.shouldSkipPeriodic(
                    projectId, ref, gameSecond)) {
                log.debug("[世界引擎] 空闲降频跳过周期决策: char={} hour={}", ch.getId(), hour);
                return 0;
            }
            attemptDecisionOnce(ch, projectId, userId, gameSecond, false);
            // 治理批次 0 · 卡3-A ①：动作结算复位点（当前行动非移动 → 沙盘 moveState 复位 idle）
            settleMoveState(projectId, ch);
            return 1;
        } catch (Exception e) {
            // P0-02 根治（可重试性）：决策失败 → 先入队重试作业（指数退避；max 档不限次数，
            // 时间可以等作业——该角色的这一小时在上游时间推进后补算回填到原定 gameSecond，
            // 而不是让世界停下来或编造）。入队成功=本时段暂无决策，等待补算。
            if (decisionRetryService != null) {
                try {
                    if (decisionRetryService.enqueue(projectId, ch.getId(), ref, userId,
                            gameSecond, e.getMessage())) {
                        log.warn("[世界引擎] 角色决策失败，已入队重试（P0-02 根治）: char={} hour={} 原定时刻={} 原因={}",
                                ch.getId(), hour, gameSecond, e.getMessage());
                        settleMoveState(projectId, ch);
                        return 2;
                    }
                } catch (Exception re) {
                    log.warn("[世界引擎] 决策重试入队失败（按缺失留痕继续）: char={} : {}", ch.getId(), re.getMessage());
                }
            }
            // P0-02 止血 (a)：入队失败/未启用 → 决策缺失（不伪造新活动文本；current_activity
            // 保持上一小时值不变），落台账 + 编年史显式「该时段无决策」——比编造更接近真实，
            // 且不再产生「在驻地歇息就寝」式的伪造经历被下游感知/记忆/反思吸收。
            log.warn("[世界引擎] 角色决策失败，本时段无决策记录（不伪造活动文本）: char={} : {}",
                    ch.getId(), e.getMessage());
            if (decisionLedgerService != null) {
                try {
                    decisionLedgerService.recordMissing(projectId, ch, gameSecond, e.getMessage());
                } catch (Exception le) {
                    log.warn("[世界引擎] 决策缺失留痕失败（隔离继续）: char={} : {}", ch.getId(), le.getMessage());
                }
            }
            // 卡3-A ①：动作结算复位仍执行（无新决策=延续当前行动；歇息/日常均非移动）
            settleMoveState(projectId, ch);
            return 2;
        }
    }

    /**
     * 单次决策尝试主体（从 {@link #dispatchOneCharacter} 抽出；P0-02 根治复用——
     * 决策重试作业重跑本方法，失败向上抛由调用方处置：入队重试或缺失留痕）。
     * <p>内容=空闲降频判定之后的完整决策入口：此刻可发生空间保障 → 按游戏时刻组装
     * 【当前所见】感知文本 → 追加【独处】段 → {@code AgentRuntime.trigger(ENGINE_TICK)}。
     * 感知按传入 gameSecond 组装（回补推进时决策看到的也是「当时的世界」）。</p>
     *
     * @param ch 角色实体
     *
     * @param projectId 项目 ID
     *
     * @param userId 项目所属用户 ID
     *
     * @param gameSecond 决策感知时刻（游戏秒；重试=原定决策时刻，回填到历史位置）
     *
     * @param retry 是否决策重试补算（true=由重试作业重跑，决策循环据此打标 AI_RETRY）
     */
    private void attemptDecisionOnce(ActorCharacter ch, Long projectId, Long userId, long gameSecond,
                                     boolean retry) {
        long hour = Math.floorDiv(gameSecond, 3600L);
        String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
        // 此刻可发生空间：感知组装前空间保障——buildPerceptionText 内注入的
        // 空间段必须是最新生成结果（空间缺失/过期才 AI 生成，有效复用零成本；失败零阻断）
        ensureDecisionSpace(projectId, ref, gameSecond);
        // B3 统一时间口径：感知按引擎推进到的游戏时刻（gameSecond）组装【当前所见】，
        // 而非读世界时钟当前值——回补推进时决策看到的也是「当时的世界」（修 L-07/）
        String situation = perceptionService.buildPerceptionText(projectId, ref, gameSecond);
        if (situation == null || situation.isBlank()) {
            situation = "当前游戏时刻：" + WorldClockService.formatGameTime(hour)
                    + "；角色当前状态：" + (ch.getCurrentActivity() == null
                    || ch.getCurrentActivity().isBlank() ? "空闲" : ch.getCurrentActivity());
        }
        // 独处行为：同地点无其他
        // 特殊 NPC 且无人群 NPC → 追加【独处】段（AI 自行决定独处时的真实行为；
        // 判定失败/未装配跳过不阻断决策）
        if (solitudeService != null) {
            try {
                String solitude = solitudeService.solitudeSegment(projectId, ref);
                if (solitude != null && !solitude.isBlank()) {
                    situation = situation + "\n\n" + solitude;
                }
            } catch (Exception e) {
                log.warn("[世界引擎] 独处段注入失败（跳过）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
        agentRuntime.trigger(AgentTrigger.ENGINE_TICK, ref,
                DecisionContext.builder()
                        .projectId(projectId)
                        .userId(userId)
                        .gameSecond(gameSecond)
                        .situation(situation)
                        .retry(retry)
                        .reason("世界引擎每游戏小时决策机会")
                        .build());
    }

    /**
     * 小时级决策扇出并行度（1=串行）。
     * <p>取值：屏障未装配（单测场景）或已关闭（{@code barrier.enabled=false}）→ 恒为 1，
     * 即完全退回改造前的串行行为；否则取 {@code barrier.parallel-dispatch}（缺省 4）。</p>
     *
     * @return 并行度（≥1）
     */
    private int dispatchParallelism() {
        TickBarrier barrier = tickBarrier;
        if (barrier == null || !barrier.isEnabled()) {
            return 1;
        }
        return barrier.parallelDispatch();
    }

    // ==================== P0-02 根治：决策重试作业排空（可重试性） ====================

    /** 单轮决策重试排空上限（超出留到下一轮扫描，防单轮重活堆积）。 */
    private static final int RETRY_DRAIN_BATCH = 50;

    /** 决策重试批次预算（并行路径开 {@code BudgetGuard} 批次窗口用；max 档忽略预算不限次数）。 */
    private static final int RETRY_BATCH_BUDGET = 10;

    /**
     * 排空到点的决策重试作业（P0-02 根治 · 可重试性；世界运行中由引擎扫描调用）。
     * <p>执行模型与小时级决策扇出同口径：并行度&gt;1（屏障开启）→ 提交扇出线程池异步执行
     * （任务内标记 {@code AgentRuntime.inBatchDispatch()}，预算走独立批次命名空间，不阻塞
     * 扫描线程）；并行度=1（屏障未装配/已关闭）→ 引擎线程串行执行（单测确定性）。</p>
     * <p>并发防双跑：每个作业经 {@code DecisionRetryService.claim} 原子认领（PENDING→
     * RETRYING），引擎扫描与手动推进并发时只有一个线程执行，同一决策点绝不被重跑两次。</p>
     *
     * @param projectId 项目 ID
     */
    private void drainDecisionRetries(Long projectId) {
        if (decisionRetryService == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            // 崩溃残留回收：认领方进程退出后作业停留在 RETRYING 超期 → 回 PENDING 重新可重试
            decisionRetryService.reclaimStale(projectId, now);
        } catch (Exception e) {
            log.warn("[世界引擎] 决策重试残留回收失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
        List<com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry> due;
        try {
            due = decisionRetryService.dueJobs(projectId, now, RETRY_DRAIN_BATCH);
        } catch (Exception e) {
            log.warn("[世界引擎] 决策重试扫描失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        if (dispatchParallelism() <= 1) {
            // 串行路径：引擎线程逐个重跑（单测/串行配置确定性；AI 调用同步等待）。
            // 同样经原子认领——屏障关闭配置下扫描与手动推进仍可能并发，防同一作业双跑
            for (com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry job : due) {
                try {
                    if (decisionRetryService.claim(job, LocalDateTime.now())) {
                        retryDecisionJob(job);
                    }
                } catch (Exception e) {
                    log.warn("[世界引擎] 决策重试认领失败（跳过）: job={} : {}", job.getId(), e.getMessage());
                }
            }
            return;
        }
        // 异步路径：原子认领后提交扇出线程池（批次预算命名空间，与引擎 tick 窗口隔离），
        // 引擎立即继续推进——时间可以等作业，决策在后台补算回填到原定 gameSecond
        ExecutorService pool = dispatchPool();
        budgetGuard.beginBatch(projectId, Math.max(1, RETRY_BATCH_BUDGET));
        AtomicInteger inFlight = new AtomicInteger(due.size());
        for (com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry job : due) {
            try {
                if (!decisionRetryService.claim(job, LocalDateTime.now())) {
                    // 已被并发另一轮认领/已终态：释放本批计数（该作业由认领方收口）
                    inFlight.decrementAndGet();
                    if (inFlight.get() == 0) {
                        budgetGuard.endBatch(projectId);
                    }
                    continue;
                }
            } catch (Exception e) {
                log.warn("[世界引擎] 决策重试认领失败（跳过）: job={} : {}", job.getId(), e.getMessage());
                inFlight.decrementAndGet();
                if (inFlight.get() == 0) {
                    budgetGuard.endBatch(projectId);
                }
                continue;
            }
            try {
                pool.submit(() -> {
                    AgentRuntime.markBatchDispatch();
                    try {
                        retryDecisionJob(job);
                    } finally {
                        AgentRuntime.clearBatchDispatch();
                        if (inFlight.decrementAndGet() == 0) {
                            budgetGuard.endBatch(projectId);
                        }
                    }
                });
            } catch (Exception e) {
                // 提交失败（停机中）：释放批次计数，作业按失败收口重排（下次扫描再试）
                log.warn("[世界引擎] 决策重试异步派发失败（按失败收口）: job={} : {}", job.getId(), e.getMessage());
                try {
                    decisionRetryService.markFailed(job, "异步派发失败: " + e.getMessage(),
                            LocalDateTime.now(), aiIntensityGate != null && aiIntensityGate.isMax(projectId));
                } catch (Exception me) {
                    log.warn("[世界引擎] 决策重试失败收口异常（隔离继续）: job={} : {}", job.getId(), me.getMessage());
                }
                inFlight.decrementAndGet();
                if (inFlight.get() == 0) {
                    budgetGuard.endBatch(projectId);
                }
            }
        }
    }

    /**
     * 重跑一个决策重试作业（P0-02 根治 · 可重试性）。
     * <p>语义：<b>时间可以等作业</b>——该角色的这一小时在上游时间推进后补算，重跑
     * {@link #attemptDecisionOnce}（感知按<b>原定 gameSecond</b> 组装、ctx 带 retry 标记），
     * 成功时决策点以 AI/AI_RETRY 回填到历史位置（台账写入在 {@code AgentRuntime} 决策循环内，
     * 动作执行在当前时刻落地——补算的代价：后果发生在完成时刻，比编造更接近真实）。</p>
     * <p>收口：成功 → SUCCEEDED；失败 → 指数退避重排（max 档不限次数），重试耗尽 →
     * ABANDONED + 显式决策缺失留痕（编年史「该时段无记录」）；角色已删除/已故 → 直接放弃。</p>
     *
     * @param job 已认领的作业（调用方保证 claim 成功，本方法负责执行与收口；项目从作业行读取）
     */
    private void retryDecisionJob(com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry job) {
        if (job == null || job.getProjectId() == null) {
            return;
        }
        Long project = job.getProjectId();
        try {
            ActorCharacter ch = characterRepository.findById(job.getCharacterId()).orElse(null);
            if (ch == null || Integer.valueOf(1).equals(ch.getDeleted()) || ch.isDeceased()) {
                log.warn("[世界引擎] 决策重试角色不可用（放弃补算）: job={} char={} 原定时刻={}",
                        job.getId(), job.getCharacterId(), job.getGameSecond());
                decisionRetryService.markAbandoned(job, "角色不存在/已删除/已故，放弃补算");
                return;
            }
            attemptDecisionOnce(ch, project, job.getUserId(), job.getGameSecond(), true);
            settleMoveState(project, ch);
            decisionRetryService.markSucceeded(job);
            log.info("[世界引擎] 决策重试成功（回填原定时刻）: char={} 原定时刻={} 尝试={}次",
                    job.getCharacterId(), job.getGameSecond(),
                    (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
        } catch (Exception e) {
            boolean unlimited = aiIntensityGate != null && aiIntensityGate.isMax(project);
            boolean willRetry;
            try {
                willRetry = decisionRetryService.markFailed(job, e.getMessage(), LocalDateTime.now(), unlimited);
            } catch (Exception me) {
                log.warn("[世界引擎] 决策重试失败收口异常（隔离继续）: job={} : {}", job.getId(), me.getMessage());
                return;
            }
            log.warn("[世界引擎] 决策重试失败: char={} 原定时刻={} 尝试={}次{} : {}",
                    job.getCharacterId(), job.getGameSecond(),
                    (job.getAttemptCount() == null ? 0 : job.getAttemptCount()),
                    willRetry ? "（指数退避重排）" : "（重试耗尽，落决策缺失）", e.getMessage());
            if (!willRetry) {
                // 重试耗尽 → 显式决策缺失留痕（不伪造活动文本；编年史「该时段无记录」）
                try {
                    ActorCharacter ch = characterRepository.findById(job.getCharacterId()).orElse(null);
                    if (ch != null && decisionLedgerService != null) {
                        decisionLedgerService.recordMissing(project, ch, job.getGameSecond(),
                                "决策重试耗尽（" + job.getAttemptCount() + " 次）: " + e.getMessage());
                    }
                } catch (Exception le) {
                    log.warn("[世界引擎] 重试耗尽缺失留痕失败（隔离继续）: char={} : {}",
                            job.getCharacterId(), le.getMessage());
                }
            }
        }
    }

    /**
     * 扇出线程池（惰性创建；daemon 线程，核心数=并行度，队列无界但每次提交量=角色数）。
     * <p>为什么固定池而不是每 tick 新建：一是避免每游戏小时创建/销毁 20 个线程；二是固定上限
     * 保证并发 AI 调用量可预测（与 H2 连接池、模型服务的并发能力对齐）。线程池只在真正需要
     * 并行（并行度&gt;1）时创建，串行路径永远不创建线程。</p>
     *
     * @return 线程池
     */
    private ExecutorService dispatchPool() {
        ExecutorService pool = dispatchExecutor;
        if (pool != null) {
            return pool;
        }
        synchronized (this) {
            if (dispatchExecutor == null) {
                int n = dispatchParallelism();
                AtomicInteger seq = new AtomicInteger();
                dispatchExecutor = Executors.newFixedThreadPool(n, r -> {
                    Thread t = new Thread(r, "world-dispatch-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
                log.info("[世界引擎] 角色决策扇出线程池已创建: 并行度={}", n);
            }
            return dispatchExecutor;
        }
    }

    /** 引擎停机：关闭扇出线程池（daemon 线程，仅为及时释放资源；未创建则空操作）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownDispatchPool() {
        ExecutorService pool = dispatchExecutor;
        if (pool != null) {
            pool.shutdownNow();
            log.info("[世界引擎] 角色决策扇出线程池已关闭");
        }
        ExecutorService daily = dailyTaskExecutor;
        if (daily != null) {
            daily.shutdownNow();
            log.info("[世界引擎] 跨日任务异步执行器已关闭");
        }
    }

    /**
     * 空间定期兜底异步调度：见 {@link #spaceRecomputeRunning}
     * 说明——把逐 NPC 的可发生空间 AI 生成移出引擎线程，世界时钟不再等待空间生成；
     * 单飞标记保证上一轮未完成时不堆叠。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 引擎推进到的游戏秒（空间重算的时间基准）
     */
    private void scheduleSpaceRecomputeAsync(Long projectId, Long gameSecond) {
        if (!spaceRecomputeRunning.compareAndSet(false, true)) {
            log.debug("[世界引擎] 空间定期兜底上一轮仍在执行，跳过本轮触发: project={} gs={}", projectId, gameSecond);
            return;
        }
        runDailyTaskAsync(projectId, "空间定期兜底", () -> {
            try {
                growthSpaceService.periodicRecompute(projectId, gameSecond);
            } finally {
                spaceRecomputeRunning.set(false);
            }
        });
    }

    /**
     * 跨日边界任务异步执行。
     * <p>语义：任务在独立虚拟线程执行（每任务一线程，用完即弃；跨日任务每天每项目至多 4 个，
     * 虚拟线程开销可忽略），引擎立即返回继续推进。任务内部自带异常兜底（各自实现零阻断），
     * 此处再包一层保险：任何异常只记 WARN，绝不反噬引擎线程。</p>
     *
     * @param projectId 项目 ID（日志用）
     *
     * @param label 任务中文名（日志用）
     *
     * @param task 任务体（须自带事务/幂等/失败隔离语义）
     */
    private void runDailyTaskAsync(Long projectId, String label, Runnable task) {
        ExecutorService executor = dailyTaskExecutor;
        if (executor == null) {
            synchronized (this) {
                if (dailyTaskExecutor == null) {
                    dailyTaskExecutor = Executors.newVirtualThreadPerTaskExecutor();
                    log.info("[世界引擎] 跨日任务异步执行器已创建（虚拟线程每任务一线程）");
                }
                executor = dailyTaskExecutor;
            }
        }
        // 冒烟抓修：在途计数（供测试 awaitDailyIdle 排空断言——跨日事件/日计划改异步后
        // 既有同步断言需等待异步任务落地）
        dailyTasksInFlight.incrementAndGet();
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[世界引擎] 跨日异步任务失败（已隔离，不影响世界推进）: project={} 任务={} 原因={}",
                            projectId, label, e.getMessage());
                } finally {
                    dailyTasksInFlight.decrementAndGet();
                }
            });
        } catch (Exception e) {
            // 执行器拒绝（停机中）：丢任务不重试——每日级任务错过可由下一游戏日自然补做（幂等）
            dailyTasksInFlight.decrementAndGet();
            log.warn("[世界引擎] 跨日异步任务提交失败（已跳过，下一游戏日幂等补做）: project={} 任务={} 原因={}",
                    projectId, label, e.getMessage());
        }
    }

    /**
     * 等待跨日异步任务排空（测试断言用；超时后返回，不阻塞业务）。
     * 冒烟抓修后新增：跨日事件/日计划改异步，既有「同步断言发布/规划」的测试须先排空。
     *
     * @param timeoutMs 最大等待毫秒
     */
    void awaitDailyIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (dailyTasksInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 派发上一 tick 排队下来的刺激（屏障三要素之「刺激排队」的派发相）。
     * <p>屏障已过（本 tick 在途任务已排空）之后到达的刺激不再立即执行，而是入队等下一个 tick 的
     * 派发相——代价 ≤ 1 tick（5 游戏分钟，rate=48 时真实约 0.6 秒），换来严格因果顺序：
     * NPC 的反应一定落在「他感知到的时刻」之后、「下一个时刻」之前。此处按入队顺序执行，
     * 执行时本 tick 屏障尚未过去，因此它们会重新登记为在途任务、被本 tick 的 awaitQuiescence 等待。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 本次成功重派发的刺激数
     */
    private int runDeferredStimuli(Long projectId) {
        TickBarrier barrier = tickBarrier;
        if (barrier == null) {
            return 0;
        }
        List<Runnable> tasks = barrier.drainDeferred(projectId);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        for (Runnable task : tasks) {
            try {
                task.run();
                dispatched++;
            } catch (Exception e) {
                // 单个刺激重派发失败：丢弃该刺激（世界仍推进，绝不因排队件反噬主流程）
                log.warn("[世界引擎] 排队刺激重派发失败（丢弃该刺激）: project={} : {}", projectId, e.getMessage());
            }
        }
        log.info("[世界引擎] 屏障排队刺激已重派发: project={} 数量={}", projectId, dispatched);
        return dispatched;
    }

    /**
     * 跨日世界事件双通道（T-4）：机制生成草稿（零成本）→ 预算内 AI 润色 → publish(kind=world,
     * source=engine, global, 幂等键 engine:{projectId}:{dayIndex}) → 预算内注入最近活跃会话
     * 触发在场角色回应。
     *
     * @param project 项目实体
     *
     * @param hour 当前推进到的游戏小时（跨日边界时刻，事件 game_hour）
     *
     * @param hourOfDay 当日小时（0-23）
     *
     * @param budgetPerTick 本次 tick 预算
     */
    private void advanceWorldEvent(ActorProject project, long hour, int hourOfDay, int budgetPerTick) {
        Long userId = project.getUserId();
        Long projectId = project.getId();
        int dayIndex = (int) Math.floorDiv(hour, 24);

        // 第一通道：机制生成草稿（零 AI 成本，时序由机制定——Hawkes 式节律后续可扩展）
        // ：保留地点实体列表——事件选点（与草稿一致的确定性轮换）携带 location_id/locationName，
        // 供 按地点路由注入在场会话 / 感知 location 可见性过滤（修）
        List<ActorWorldLocation> locationEntities = locationRepository.findByProjectId(projectId);
        List<String> locations = locationEntities.stream()
                .map(ActorWorldLocation::getName).toList();
        ActorWorldLocation picked = mechanismLocation(dayIndex, locationEntities);
        Long eventLocationId = picked == null ? null : picked.getId();
        String eventLocationName = picked == null ? null : picked.getName();
        long draftStart = System.currentTimeMillis();
        String draft = mechanismWorldEventDraft(dayIndex, hourOfDay, locations);
        String content = draft;
        boolean polished = false;
        // B5：跨日世界事件 = 一个业务动作级 trace_id（机制草稿/AI 润色/发布 同一决策链路）；
        // 机制草稿（非 AI）记 trace 行
        boolean ownsTrace = AiCallContext.currentTraceId() == null;
        if (ownsTrace) {
            AiCallContext.beginTrace();
        }
        AiCallContext.recordStep(projectId, "world_event", "event",
                "跨日世界事件机制草稿生成（第" + dayIndex + "日：" + Math.min(draft.length(), 40) + "…）",
                System.currentTimeMillis() - draftStart);

        // 第二通道：AI 润色（预算内；开关 holzyn.actor.world.ai-polish-enabled 默认 true；
        // 失败/超预算回退机制草稿，润色调用记 trace/用量）
        if (aiPolishEnabled && budgetGuard.tryConsume(projectId)) {
            try {
                String polishedText = worldEventService.polishEventByAi(userId, projectId, draft,
                        "第 " + dayIndex + " 游戏日，游戏时刻 " + WorldClockService.formatGameTime(hour));
                if (polishedText != null && !polishedText.isBlank()) {
                    content = polishedText;
                    polished = true;
                }
            } catch (Exception e) {
                log.warn("[世界引擎] 跨日事件 AI 润色失败，使用机制草稿: project={} : {}", projectId, e.getMessage());
            }
        }

        // 发布（kind=world、source=engine、visibility=global、重要度 4=重大世界事件进【世界大事记】；
        // ：携带 location_id/locationName（机制选点）供按地点路由/感知过滤；
        // 幂等键按游戏日唯一，防重复发布）
        worldEventBus.publish(userId, projectId, EventSpec.builder()
                .kind("world")
                .source("engine")
                .visibility("global")
                .title(titleOf(content))
                .content(content)
                .importance(4)
                .gameHour(hour)
                .locationId(eventLocationId)
                .locationName(eventLocationName)
                .sourceKey("engine:" + projectId + ":" + dayIndex)
                .build());
        // B5：发布环节（非 AI）记 trace 行
        AiCallContext.recordStep(projectId, "world_event", "event",
                "跨日世界事件发布（kind=world source_key=engine:" + projectId + ":" + dayIndex
                        + " 润色=" + polished + "）", 0);
        log.info("[世界引擎] 跨日世界事件发布：project={} 第{}日 润色={} 内容={}",
                projectId, dayIndex, polished, content);

        // ：预算内路由注入「在场/相关」会话（替换旧「最近更新的第一个会话」findFirst，修）——
        // 按事件 location_id 匹配「会话所在地=该地点 或 任一成员所在地=该地点」的在场会话；
        // 无匹配仅广播不注入（避免事件进无关会话）；路由服务缺失时回退旧「最近会话」兜底
        if (budgetGuard.tryConsume(projectId)) {
            try {
                ActorConversation target = null;
                if (eventRoutingService != null) {
                    target = eventRoutingService.findTarget(projectId, userId, eventLocationId, eventLocationName, null);
                } else {
                    target = conversationRepository
                            .findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId)
                            .stream().findFirst().orElse(null);
                }
                if (target != null) {
                    worldEventService.injectExternal(target.getId(), userId, content);
                    // 重排接线：在场高重要度特殊 NPC 当日计划被事件打断 → 自动重排（预算门控，失败静默）
                    worldEventService.replanForEvent(projectId, userId, target.getId(), hour, content);
                    log.info("[世界引擎] 跨日事件按地点路由注入会话触发在场角色回应：project={} conv={} loc={}",
                            projectId, target.getId(), eventLocationName);
                } else {
                    log.info("[世界引擎] 跨日事件无在场会话匹配，仅广播：project={} 第{}日 loc={}",
                            projectId, dayIndex, eventLocationName);
                }
            } catch (Exception e) {
                log.warn("[世界引擎] 跨日事件注入会话失败: project={} : {}", projectId, e.getMessage());
            }
        }

        // ：跨日事件后的「事件回应轮」——社会对话层修复 B 层 起由
        // AutonomousChatEventSubscriber 订阅事件总线统一接线（importance≥阈值 + 带地点 → 所在地点
        // NPC 内群一轮无玩家群聊；日上限护栏在订阅者内）——此处不再直挂，防同事件双触发。
        if (ownsTrace) {
            AiCallContext.endTrace();
        }
    }

    /**
     * 跨日规划：跨日边界为项目内特殊 NPC（planning_enabled 且非 programmatic 档）自动生成
     * 日计划（双保险之跨日通道，AI 预算内/回退机制）+ 自动生成目标（无 active 目标时，run_mode=minimal 跳过）。
     * 单角色失败隔离。
     *
     * @param project 项目实体
     *
     * @param hour 跨日边界游戏小时（计划所属游戏日编号基准）
     */
    private void advanceDayPlanning(ActorProject project, long hour) {
        if (planningService == null) {
            return;
        }
        Long projectId = project.getId();
        // 长跑抓修：已故角色不做跨日规划（plan_day/目标生成）
        List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        for (ActorCharacter ch : characters) {
            try {
                String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
                com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile profile =
                        brainProfileService.resolveByRef(ref);
                if (profile != null && Integer.valueOf(1).equals(profile.getPlanningEnabled())
                        && !"programmatic".equals(profile.getBrainLevel())) {
                    planningService.ensurePlanDay(projectId, ref, hour);
                }
                // 目标全自动 AI 生成（无 active 时；minimal 模式内部跳过）
                planningService.generateGoal(projectId, ref, hour);
            } catch (Exception e) {
                log.warn("[世界引擎] 角色跨日规划失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * 角色重要度（null 视为 0）—— 每小时决策机会的预算内优先级排序依据（降序：高重要度先消费
     * AgentRuntime 内部 AI 预算，预算耗尽后剩余角色在门面内走程序化兜底）。
     *
     * @param ch 角色实体
     *
     * @return 重要度 0-5（null=0）
     */
    private static int importanceOf(ActorCharacter ch) {
        return ch.getImportance() == null ? 0 : ch.getImportance();
    }

    /**
     * 程序化角色状态更新（逐时）：活动文本变化才写库（减少重复写）。
     * <p>⚠️ P0-02 诚实化止血 (a)：本方法<b>已不再从决策失败路径调用</b>——决策失败改为
     * 决策缺失（不伪造新活动文本，见 {@link #dispatchOneCharacter} 的 catch）。
     * 保留方法本身（含 actor_state 同步语义）供未来「明确配置 programmatic-fallback-allowed=true
     * 且必须打标」的真实度档位工作复用（审计 P0-02 止血 (b)），当前零调用方。</p>
     *
     * @param ch 角色实体
     *
     * @param hourOfDay 当日游戏小时（0-23）
     */
    @SuppressWarnings("unused")
    private void programmaticUpdate(ActorCharacter ch, int hourOfDay) {
        String activity = programmaticActivity(ch.getLocation(), hourOfDay);
        if (!java.util.Objects.equals(activity, ch.getCurrentActivity())) {
            ch.setCurrentActivity(activity);
            characterRepository.save(ch);
        }
        // 卡4-A：actor_state 同步（与 profile 同写；无变化也幂等写，收敛存量分歧）
        if (npcActivityStateService != null && ch.getId() != null && ch.getProjectId() != null) {
            try {
                npcActivityStateService.setActivity(ch.getProjectId(), com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                        com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.toActivityState(activity),
                        activity);
            } catch (Exception e) {
                log.warn("[世界引擎] actor_state 同步失败（不阻断推进）: char={} : {}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * 治理批次 0 · 卡3-A ①：移动后动作结算复位点——当前行动非移动 → 沙盘 moveState 复位 idle。
     * <p>onNpcMoved 只写 "walking" 永不复位；每小时 dispatch 后按角色当前行动文本经映射表唯一来源
     * {@code toActivityState} 判定（walk 语义=保持 walking），非移动 → 调
     * {@code WorldSandboxStateService.setMoveState(...,"idle")}。失败零阻断（沙盘是旁路）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色实体
     */
    private void settleMoveState(Long projectId, ActorCharacter ch) {
        if (sandboxStateService == null || ch == null || ch.getId() == null || projectId == null) {
            return;
        }
        try {
            String activity = ch.getCurrentActivity();
            if (activity == null || activity.isBlank()
                    || !com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.STATE_WALK.equals(
                    com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.toActivityState(activity))) {
                sandboxStateService.setMoveState(projectId, com.holzyn.actor.common.NpcRefs.of(ch.getId()),
                        com.holzyn.actor.domain.npc.state.service.NpcActivityStateService.DEFAULT_ACTIVITY);
            }
        } catch (Exception e) {
            log.warn("[世界引擎] moveState 复位失败（不阻断推进）: char={} : {}", ch.getId(), e.getMessage());
        }
    }

    /**
     * 时钟速率（null 用默认 24）。
     *
     * @param clock 时钟实体
     *
     * @return 速率
     */
    private int rateOf(ActorWorldClock clock) {
        return clock.getRate() == null ? DEFAULT_RATE : clock.getRate();
    }

    /**
     * 记录本轮推进的 tick 队列状况（P0-01 根治可观测；backlog=0 且无丢弃表示已追平）。
     * <p>口径：积压 &gt;0 记 INFO（世界落后真实流逝、正在追平——余量显式留在队列）；<b>本轮丢弃
     * &gt;0 升 WARN</b>（队列溢出=显式丢弃并记录，异常事件）；积压 ≥ 阈值且创新高再补一条 WARN——
     * 避免离线回补/长耗时轮次的连续刷屏，同时让「积压持续扩大」的真实异常显眼。</p>
     *
     * @param projectId 项目 ID
     *
     * @param backlogGameSeconds 本轮结束后队列积压（frontier − 已消费位置；0=已追平）
     *
     * @param droppedThisRound 本轮显式丢弃的游戏秒（队列溢出部分；0=未溢出）
     *
     * @param cumulativeDropped 显式丢弃累计（落库口径）
     *
     * @param committedGameSecond 本轮结束后的已消费位置（快照用）
     *
     * @param reason 积压来源（queue=自走路径墙钟生产；external=外部时钟上报）
     */
    private void recordDeferred(Long projectId, long backlogGameSeconds, long droppedThisRound,
                                long cumulativeDropped, long committedGameSecond, String reason) {
        TimeBacklogMetric m = timeBacklog.computeIfAbsent(projectId, k -> new TimeBacklogMetric());
        m.backlogGameSeconds = backlogGameSeconds;
        m.committedGameSecond = committedGameSecond;
        m.droppedGameSeconds = cumulativeDropped;
        if (backlogGameSeconds > 0) {
            m.deferredRounds++;
            // 峰值积压持续跟踪（无论是否触发告警；积压回落期不更新）
            if (backlogGameSeconds > m.peakBacklogGameSeconds) {
                m.peakBacklogGameSeconds = backlogGameSeconds;
            }
            log.info("[世界引擎] 时间回补积压: project={} 当前积压 {} 游戏秒（{}），余量留在队列逐轮追平",
                    projectId, backlogGameSeconds, reason);
        }
        if (droppedThisRound > 0) {
            log.warn("[世界引擎] tick 队列溢出，显式丢弃并记录: project={} 本轮丢弃 {} 游戏秒（容量上限 "
                            + "{} 游戏秒），累计丢弃 {} 游戏秒——世界落后真实流逝的部分已显式计数，不再静默丢失",
                    projectId, droppedThisRound, tickQueueCapacityGameSeconds, cumulativeDropped);
        }
        // 告警口径：仅当「当前积压 ≥ 阈值 且 创出新高（== 峰值）」才升 WARN——
        // 避免离线回补/长耗时轮次的连续刷屏，同时让「积压持续扩大」的真实异常显眼。
        if (backlogGameSeconds >= deferralWarnThresholdGameSeconds
                && backlogGameSeconds == m.peakBacklogGameSeconds) {
            log.warn("[世界引擎] 时间回补积压超阈值: project={} 当前落后 {} 游戏秒（≥{}），"
                            + "世界时间持续落后真实流逝——请关注单轮耗时或降低速率",
                    projectId, backlogGameSeconds, deferralWarnThresholdGameSeconds);
        }
    }

    /**
     * 时间回补可观测快照（P0-01 调试查询入口；无记录时返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @return 队列统计（committedGameSecond/currentBacklogGameSeconds/peakBacklogGameSeconds/
     * deferredRounds/droppedGameSeconds/queueCapacityGameSeconds/unit）；
     * 该项目从未推进时 null
     */
    public Map<String, Object> timeBacklogSnapshot(Long projectId) {
        TimeBacklogMetric m = timeBacklog.get(projectId);
        if (m == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("committedGameSecond", m.committedGameSecond);
        out.put("currentBacklogGameSeconds", m.backlogGameSeconds);
        out.put("peakBacklogGameSeconds", m.peakBacklogGameSeconds);
        out.put("deferredRounds", m.deferredRounds);
        out.put("droppedGameSeconds", m.droppedGameSeconds);
        out.put("queueCapacityGameSeconds", tickQueueCapacityGameSeconds);
        out.put("unit", "game-seconds");
        return out;
    }

    /** S2 沙盘 clock 广播（失败仅告警，不影响推进主流程）。 */
    private void publishClockSafe(Long projectId, Integer paused) {
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishClock(projectId, paused);
            }
        } catch (Exception e) {
            log.warn("[世界引擎] 沙盘 clock 广播失败（不影响推进）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 获取/懒创建项目世界引擎配置行（每项目唯一）。
     *
     * @param projectId 项目 ID
     *
     * @return 引擎配置行
     */
    private ActorWorldEngine requireEngine(Long projectId) {
        return engineRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorWorldEngine engine = new ActorWorldEngine();
            engine.setProjectId(projectId);
            engine.setBudgetPerTick(maxAiCalls);
            // ：DB 列 max_step_hours 仍按游戏小时存（全局缺省 max-catch-up-seconds 换算为小时）
            engine.setMaxStepHours(Math.max(1, maxCatchUpSeconds / 3600));
            engine.setLastAiBudgetResetAt(LocalDateTime.now());
            return engineRepository.save(engine);
        });
    }

    /**
     * 基础结果 Map（status/advancedHours/gameHour/actions/crowds/events/aiCalls/summary）。
     */
    private Map<String, Object> baseResult(Long projectId, String status, long gameHour, long advancedHours,
                                           int actions, int crowds, int events, int aiCalls, String summary) {
        return baseResult(projectId, status, gameHour, advancedHours, actions, crowds, events, aiCalls, summary, 0);
    }

    /**
     * 基础结果 Map + 扇出超时计数（jstack 抓修：世界运行采样可直接从接口结果读到
     * {@code dispatchTimeouts}，无需解析日志——&gt;0 即本次推进出现过 AI 悬挂）。
     */
    private Map<String, Object> baseResult(Long projectId, String status, long gameHour, long advancedHours,
                                           int actions, int crowds, int events, int aiCalls, String summary,
                                           int dispatchTimeouts) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("projectId", projectId);
        r.put("status", status);
        r.put("advancedHours", advancedHours);
        r.put("gameHour", gameHour);
        r.put("actions", actions);
        r.put("crowds", crowds);
        r.put("events", events);
        r.put("aiCalls", aiCalls);
        r.put("dispatchTimeouts", dispatchTimeouts);
        r.put("summary", summary);
        return r;
    }

    private Map<String, Object> baseResult(Long projectId, String status, long gameHour, long advancedHours,
                                           int actions, int crowds, int events, String summary) {
        return baseResult(projectId, status, gameHour, advancedHours, actions, crowds, events, 0, summary);
    }

    /**
     * 推进统计与摘要记录。
     * <p>屏障接线新增四个可观测字段（世界运行测试据此核对屏障效果）：
     * {@code barrierWaits} 本次推进内发生等待的 tick 数、{@code barrierWaitedMs} 等待总毫秒、
     * {@code barrierTimeouts} 超时放行次数（&gt;0 说明有 AI 链超过 max-wait-ms）、
     * {@code barrierDeferred} 重派发的排队刺激数。</p>
     * <p>同日 jstack 抓修新增 {@code dispatchTimeouts}：小时级决策扇出中因 AI 悬挂超过等待总预算
     * （{@code dispatch-wait-seconds}）而被放弃等待的角色数——&gt;0 表示本 tick 出现过悬挂调用，
     * 世界已按设计继续推进（不停摆），是排查模型服务端半死连接的关键指标。
     * ⚠️ R1 起同步等待被「预热+异步补发」取代，本字段恒为 0（保留接口兼容）。</p>
     * <p>R1 新增 {@code lookaheadSkipped}：边界时预热命中被跳过（不重复触发）的角色数——
     * 与边界补发派发数相加 = 角色数（每角色每游戏小时恰好一次决策入口的可观测口径）。</p>
     * <p>P0-02 新增 {@code missingDecisions}：决策缺失数（决策失败→本时段无决策记录，
     * 不伪造活动文本；串行路径统计，异步路径缺失落台账但不在本统计）。</p>
     */
    private record EngineStats(int actions, int crowds, int events, int aiCalls, int dispatchTimeouts,
                               int barrierWaits, long barrierWaitedMs, int barrierTimeouts,
                               int barrierDeferred, int lookaheadSkipped, int missingDecisions, String summary) {
    }

    /**
     * 小时级决策派发结果（R1 起为异步派发）。
     *
     * @param completed 本批派发的角色数（异步路径=提交数；串行路径=完成数）
     *
     * @param timedOut 恒 0（异步不等待，字段保留接口兼容——历史语义：等待预算用尽被放弃等待的角色数）
     *
     * @param skipped 预热命中被跳过（不重复触发）的角色数
     *
     * @param missing 决策缺失数（串行路径统计；异步路径恒 0——缺失落台账但不在派发结果内）
     */
    private record DispatchOutcome(int completed, int timedOut, int skipped, int missing) {
    }
}
