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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.AfterCommit;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorEmotionAnchorRepository;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NPC 状态服务。
 * <p>职责：</p>
 * <ul>
 * <li><b>惰性初始化</b>：ensureState 按 project_id+npc_ref 幂等 upsert 状态行（默认值建行）；
 * syncProjectStates 为项目全部特殊+人群 NPC 补行（对齐沙盘 S1 惰性同步哲学）；</li>
 * <li><b>游戏时间驱动衰减</b>：advance 按行内 game_second 差<b>惰性补偿</b>（纯静态 applyDecay 可单测）——
 * 特殊全列衰减、人群轻量（仅 hunger/energy）、夜间睡眠精力恢复、低值区可配放缓（taper）；</li>
 * <li><b>行动挂钩恢复</b>：onNpcAction 对齐沙盘 onNpcMoved 模式（行动类型 → 需求恢复映射 +
 * 最近进食/睡眠时刻 + 附带健康微恢复），人群作息挂钩 onCrowdSchedule（rest→精力恢复）；</li>
 * <li><b>urgency 分级</b>：0 无 / 1 轻度（需求&lt;30）/ 2 强烈（需求&lt;15）/ 3 健康告急（health&lt;25）+ 派生文本；</li>
 * <li><b>健康联动</b>：生理四需长期低于阈值 → 健康侵蚀；全部高于回升阈值 → 缓慢回升；</li>
 * <li><b>mood 派生</b>：需求满意度加权聚合（生理 1.0 / 健康 0.8 / 清洁社交趣味 0.5）
 * × (1-w) + 情绪分（P 轴×强度） × w + env_modifier；人群 mood 仅取 hunger/energy（轻量列语义）。</li>
 * <li><b>情绪模型 （主开发方案 §7）</b>：三路生成——① 程序化规则
 * {@link #applyProceduralEmotion}（需求/健康/夜间/行动满足 → PAD 增减，advance 与行动挂钩双时机）；
 * ② AI 评估写回 {@link #evaluateEmotion}（内置模板 npc_emotion_eval + {@link EmotionEvalParse} +
 * {@link #applyEmotionEval}[钳制/显式标签/OCC 规则映射]，对话/事件/行动三挂钩，
 * 冷却+事件重要度阈值+人群场景人数门控）；
 * ③ OCC 显式三维评价（occ-enabled 开启时：合意性→P / 称赞性→D+轻度P / 喜好性→P&D 规则映射）；
 * 情绪衰减 {@link #applyEmotionDecay}（指数衰减回平静锚点，半衰期可配 + 心情协同
 * mood&lt;阈值 时负面情绪退得慢）；主导情绪 = 距 actor_emotion_anchor 7 锚点欧氏最近者
 * （AI 显式标签当次写入生效并保持）；强度 = PAD 向量模长 /√3 ×100 归一。</li>
 * </ul>
 * <p>衰减/恢复/阈值全量可配（{@link NpcStateProperties}，holzyn.actor.npc-state.*）；世界暂停冻结
 * （衰减随行内 game_second 基准，暂停期间 game_second 不推进 → 自然冻结，对齐单一时间源）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§6（程序化更新模型）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcStateService {

    /** 统一刻度上限 */
    private static final int MAX_VALUE = 100;
    /** 统一刻度下限 */
    private static final int MIN_VALUE = 0;

    /**
     * 钳制告警容差（游戏秒，=25 游戏小时）：引擎自身输入（小时边界 hour×3600 / 重启回补大步进
     * ≤ max_step_hours=24h）在时钟落库前瞬态超前 ≤ 一步 + 1 小时属正常推进序列，静默钳制；
     * 超过此值视为真流动时钟污染才 WARN（24h 压测实证污染 87 游戏天 ≫ 此值）。
     */
    private static final long SANITIZE_WARN_TOLERANCE_SECONDS = 25 * 3600L;

    /** 平静锚点 P（与 actor_emotion_anchor code=calm 保持一致；静态核心避免 DB 依赖） */
    private static final double CALM_PLEASURE = 0.10d;
    /** 平静锚点 A */
    private static final double CALM_AROUSAL = -0.10d;
    /** 平静锚点 D */
    private static final double CALM_DOMINANCE = 0.00d;

    /** PAD 上限 */
    private static final double PAD_MAX = 1.0d;
    /** PAD 下限 */
    private static final double PAD_MIN = -1.0d;

    /** 默认主导情绪标签（锚点算出回退/无锚点） */
    private static final String EMOTION_CALM = "calm";

    /** AI 情绪评估场景（trace/用量记账 scene） */
    private static final String SCENE_EMOTION_EVAL = "npc_emotion_eval";
    /** AI 情绪评估温度（低随机，评估稳定） */
    private static final double EMOTION_EVAL_TEMPERATURE = 0.3;
    /** AI 情绪评估最大输出 token */
    private static final int EMOTION_EVAL_MAX_TOKENS = 256;

    /** AI 人群行动决策场景 */
    private static final String SCENE_CROWD_DECISION = "npc_crowd_decision";
    /** AI 人群行动决策温度（中低随机，行动稳定可解释） */
    private static final double CROWD_DECISION_TEMPERATURE = 0.4;
    /** AI 人群行动决策最大输出 token */
    private static final int CROWD_DECISION_MAX_TOKENS = 160;

    /** 【当前状态】注入段头（设计 §8.1，决策/对话共用） */
    private static final String STATE_SEGMENT_HEADER = "【当前状态】";

    private final ActorNpcStateRepository stateRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final NpcStateProperties props;

    /**
     * NPC 类型解析器（：npc:{id} 不再编码类型，惰性建行定型改查 actor_profile.type；
     * 字段注入避免改构造器破坏存量单测，null=回退特殊型——与历史 refTypeOf 行为一致）
     */
    @Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    /** 世界时钟（游戏时刻解析；字段注入保持构造器精简，纯数据单测可置空） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    // ==================== 情绪模型依赖（字段注入，纯数据单测可置空跳过） ====================

    /** 情绪锚点仓库（主导情绪最近锚点判定；null=锚点缺失回退 calm） */
    @Autowired(required = false)
    private ActorEmotionAnchorRepository anchorRepository;

    /** 沙盘状态仓库（人群 AI 评估场景人数门控：读 actor_sandbox_state 按 scene_id 统计） */
    @Autowired(required = false)
    private ActorSandboxStateRepository sandboxStateRepository;

    /** Prompt 模板服务（npc_emotion_eval 内置模板渲染；null=AI 评估跳过） */
    @Autowired(required = false)
    private PromptTemplateService promptTemplateService;

    /** Spring AI 网关（AI 情绪评估调用；null=AI 评估跳过） */
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 评估 trace/用量记录；null=AI 评估跳过） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    // ==================== 分层驱动/展示依赖（字段注入，纯数据单测可置空跳过） ====================

    /**
     * 世界沙盘 SSE Hub（npc:state 内部状态广播；@Lazy 破 Bean 环——Hub 经 WorldEventBus→
     * WorldClockService→沙盘服务链路，运行时经代理取真实 Bean；null=广播关闭）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldSandboxSseHub sandboxHub;

    /** 世界观地点仓库 */
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    /**
     * 位置归一服务（治理批次 0 · 卡1：applyCrowdAction 移动后 locationId 与 location 归一一致——
     * 修「人群移动只写 location 不写 locationId → PerceptionService 在场过滤用陈旧 locationId」；
     * 字段注入避免改构造器，null=未装配[单测]跳过归一）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.LocationNormalizationService locationNormalizationService;

    /**
     * 连续移动状态机（位置移动与地图生成系统 · M2：人群基础版——applyCrowdAction 移动目标 →
     * 发起真实移动[walking→arrived 插值推进，零 AI]；字段注入避免改构造器，null=未装配[单测]
     * 回退既有「setLocation + syncCrowdStates 派生坐标」瞬移语义——零破坏既有行为）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /**
     * 空间重算触发判定（情绪漂移检测点——AI 情绪评估写回后按情绪标签变化 +
     * 决策模型 boolean 确认触发空间重算 EMOTION_SHIFT；字段注入保持构造器不变，null=未装配跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    /** 历史快照服务 */
    @Autowired(required = false)
    private NpcStateHistoryService historyService;

    /**
     * 实时行为决策服务（实时行为决策系统：感知 tick urgency 升档检测 → 刺激①即时决策；
     * 字段注入避免改构造器破坏存量单测，@Lazy 破 Bean 环——实时决策服务经 AgentRuntime→感知→
     * 沙盘→本服务 构成环（启动实测抓修），运行时经代理取真实 Bean；null=未装配跳过升档回调）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService realtimeDecisionService;

    /**
     * 生理小动作服务（实时行为决策系统：状态写回时按状态程序化生成小动作文本[零 AI，
     * 特殊+人群都适用]，落 thought_text + SSE；字段注入避免改构造器破坏存量单测，null=未装配跳过）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.MicroMotionService microMotionService;

    /**
     * 信息感知三层过滤服务（世界演化与游戏时间系统：人群 AI 决策【世界动态】段注入——
     * 该居民当前可感知的信息包，双轨全 NPC；字段注入保持构造器不变，纯数据单测置空跳过）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.InfoPerceptionService infoPerceptionService;

    /**
     * L1 模糊引擎（大睡动作[睡觉/就寝/卧床/歇息/眠/睡]触发该角色 L1 睡眠清洗——
     * 特殊+人群共用本挂钩；字段注入保持构造器不变，null=未装配/开关关跳过零影响）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.L1FuzzificationService fuzzService;

    /**
     * 作息运行态访问器（：state / last_action / daily_plan_json 已从 actor_profile 收敛到
     * actor_state，读写一律经本访问器；字段注入保持构造器不变，null=跳过作息运行态读写）
     */
    @Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /** 虚拟线程执行器：AI 情绪评估异步执行（对齐记忆抽取/对话抽取模式，不阻塞对话/行动/事件主流程） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * tick 屏障（阶段⑤：窄屏障成员——情绪 AI 评估会写 {@code actor_state}，
     * 此前写回时刻与 tick 完全脱钩，是锁超时与"情绪被覆盖"的主要来源）。
     * 字段注入保持构造器不变；null=未装配（单测）→ 退化为改造前的自由异步。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.TickBarrier tickBarrier;

    /**
     * 决策模型 判定门（crowd_decision 场景：普通 NPC 行动选择走程序化候选集 + sample 直接采纳；
     * 字段注入保持构造器不变，null=未装配[单测]跳过 决策模型 路径回退 Qwen/程序化）
     */
    @Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.DecisionGate decisionGate;

    /**
     * 此刻可发生空间（普通 NPC crowd 统一到空间候选——决策时 ensureSpace +
     * 空间 possible_actions + 通用三项 → precondition 过滤 → 决策模型 choice；字段注入保持
     * 构造器不变，null=未装配[单测]回退 程序化候选集）
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.holzyn.actor.domain.growth.space.GrowthSpaceService growthSpaceService;

    /**
     * 统一环境服务（天气 precondition 程序化判定用：computeContext(projectId, gameSecond)
     * → weather condition；字段注入保持构造器不变，null=天气未知按 UNKNOWN 宽松放行）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.EnvironmentContextService environmentContextService;

    /**
     * C 层社交聚集（社会对话层修复 C 层 独处普通 NPC 自发流向社交中心[茶馆/集市/村口…]，
     * 注入 crowd 空间候选；字段注入保持构造器不变，null=未装配[单测]跳过社交候选，行为回到修复前）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SocialGatheringService socialGatheringService;

    /**
     * BrainStrategy 可插拔决策策略路由器（P2-3：普通 NPC 决策显式化为有序策略链——
     * 危险强制→快选择[决策模型+AI 建议]→概率化，链式责任首中即胜、命中写 trace；
     * 字段注入保持构造器不变，null=未装配[单测]走既有内联管线，行为与 P2-3 前一致）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter brainStrategyRouter;

    /**
     * 统一 AI 上下文注入器（角色视角 AI 评估注入世界观简报 + 角色核心卡；null=未装配跳过）。
     * <p>2026-10-07 上下文注入根治：情绪评估/人群决策的 character_info 此前只有
     * 「身份：钱串子（云来茶馆掌柜）」一行简化简介——AI 缺角色性格/背景/目标与世界观的
     * 依据，判断不准。现把注入器组合段拼进 prompt（世界观 + 我的身份与处境）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== npc:state 内部状态广播去重缓存 ====================

    /**
     * 显著变化广播签名缓存（key=projectId:npcRef → 最近已广播签名；需求跨阈值/情绪切换/urgency 变化才推，
     * 防广播风暴；上限 {@link #SIGNATURE_CACHE_LIMIT} 条目防止内存膨胀）
     */
    private final Map<String, String> lastStateSignature = new ConcurrentHashMap<>();

    /** 广播签名缓存条目上限（防长期运行内存膨胀） */
    private static final int SIGNATURE_CACHE_LIMIT = 5000;

    // ==================== 惰性初始化（幂等 upsert） ====================

    /**
     * 惰性初始化状态行（幂等 upsert）：不存在则按默认值建行，存在则原样返回。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 初始游戏时刻（秒，可空=回退世界时钟当前值）
     *
     * @return 状态行（null=参数非法）
     */
    @Transactional
    public ActorNpcState ensureState(Long projectId, String npcType, String npcRef, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return null;
        }
        ActorNpcState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (st == null) {
            st = new ActorNpcState();
            st.setProjectId(projectId);
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型值入库前归一化：历史 crowd → common）
            st.setNpcType(NpcRefs.normalizeType(npcType));
            st.setNpcRef(npcRef);
            // ：建行初始时刻同样钳制到引擎推进位置（防流动时钟值落库）
            Long gs = sanitizeGameSecond(projectId, gameSecond != null ? gameSecond : currentGameSecond(projectId));
            st.setGameSecond(gs);
            st.setMood(deriveMood(st, props));
            return stateRepository.save(st);
        }
        return st;
    }

    /**
     * 取状态行（展示读接口：惰性建行 + 返回当前行；不推进衰减——衰减由引擎 advance 推进，
     * 读接口展示当前落库值；返回 null=参数非法）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @return 状态行（null=参数非法）
     */
    public ActorNpcState stateFor(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return null;
        }
        ActorNpcState st = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        return st != null ? st : ensureState(projectId, refTypeOf(projectId, npcRef), npcRef, null);
    }

    // ====================：生理基线 ↔ 状态模块联动 ====================

    /**
     * 写入该 NPC 的生理基线并换算生效系数（<b>档案 ③ 维的落库入口</b>）。
     * <p>这是「生理基线不独立于状态模块」的关键接口：AI 建档/开发者预设产出的 ③ 维 JSON
     * 由 {@code PersonaPersistService} 调本方法写入 {@code actor_state.physio_json}，
     * 同时立刻换算为生效系数缓存到 {@code physio_coef_json}——此后每 tick 衰减只读系数。</p>
     *
     * @param st 状态行（原地更新；需已持久化）
     *
     * @param physioJson 档案 ③ 维 JSON（可空=清空并回退默认系数）
     *
     * @param personaVersion 档案版本号（写入 {@code physio_version}，用于过期检测）
     *
     * @param endurance 体质耐力 0-10（② 维，可空；参与恢复速率）
     */
    public void applyPhysioBaseline(ActorNpcState st, String physioJson, Integer personaVersion, Integer endurance) {
        if (st == null) {
            return;
        }
        st.setPhysioJson(physioJson);
        st.setPhysioCoefJson(PhysioProfile.fromPhysioJson(physioJson).withEndurance(endurance).toJson());
        st.setPhysioVersion(personaVersion == null ? 0 : personaVersion);
    }

    /**
     * 惰性补算生效系数（零阻断兜底）：系数为空但生理档案存在时（如历史行/外部写入）即时换算。
     *
     * @param st 状态行（原地更新）
     */
    public void ensurePhysioProfile(ActorNpcState st) {
        if (st == null) {
            return;
        }
        if (st.getPhysioCoefJson() == null || st.getPhysioCoefJson().isBlank()) {
            applyPhysioBaseline(st, st.getPhysioJson(), st.getPhysioVersion(), null);
        }
    }

    /**
     * 读取该 NPC 当前生效的生理系数（调试页「档案 ③ 维 ↔ 生效系数 ↔ 实际曲线」对照用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return 生效系数（无状态行时返回默认系数）
     */
    public PhysioProfile physioProfileOf(Long projectId, String npcRef) {
        ActorNpcState st = stateFor(projectId, npcRef);
        return st == null ? PhysioProfile.defaults() : PhysioProfile.fromCoefJson(st.getPhysioCoefJson());
    }

    /**
     * 项目全部状态行。
     *
     * @param projectId 项目 ID
     *
     * @return 状态行列表（空项目返回空列表）
     */
    public List<ActorNpcState> listStates(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
    }

    /**
     * 生计联动：粮价→生计→状态参数确定性映射——
     * 粮价贵（priceFactor≥1.3）→ 饥饿感上升（hunger 降）/心情下降（mood 降，消费抑制的
     * 程序化表达）；粮价便宜（≤0.85）→ 心情微升。只作用于人群（crowd）状态行（普通 NPC=
     * 宏观人口池代表个体），特殊 NPC 走 AI 决策感知注入（【生计压力】段）不在此调整。
     * 每日宏观 tick 调用一次；幅度 = round(|priceFactor−1|×15) 钳制 [1,8]；
     * 触顶触底清余额；单行失败隔离不阻断。
     *
     * @param projectId 项目 ID
     *
     * @param priceFactor 粮价/基准比值（≤0 忽略）
     */
    public void applyMacroLivelihoodAdjust(Long projectId, double priceFactor) {
        if (projectId == null || priceFactor <= 0) {
            return;
        }
        int adjust;
        int hungerDelta;
        int moodDelta;
        if (priceFactor >= 1.3) {
            // 粮价贵：挨饿感上升、心情低落（消费抑制）
            adjust = (int) Math.round(Math.min(8.0, Math.max(1.0, (priceFactor - 1.0) * 15.0)));
            hungerDelta = -adjust;
            moodDelta = -adjust;
        } else if (priceFactor <= 0.85) {
            // 粮价便宜：日子松快，心情微升
            adjust = (int) Math.round(Math.min(6.0, Math.max(1.0, (1.0 - priceFactor) * 15.0)));
            hungerDelta = 0;
            moodDelta = adjust;
        } else {
            return;
        }
        try {
            List<ActorNpcState> states = stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            for (ActorNpcState st : states) {
                // ：npc:{id} 不再编码类型，此处按意图 B 处理（行内 npc_type 为准，不再看引用前缀）
                if (st == null || !NpcRefs.isCommonType(st.getNpcType()) || st.getNpcRef() == null) {
                    continue;
                }
                try {
                    int hunger = st.getHunger() == null ? 70 : st.getHunger();
                    int mood = st.getMood() == null ? 70 : st.getMood();
                    st.setHunger(clampValue(hunger + hungerDelta, 0, 100));
                    st.setMood(clampValue(mood + moodDelta, 0, 100));
                    // 触顶触底清对应余额（防残差漂移）
                    Map<String, Double> frac = parseFrac(st.getDecayFrac());
                    if (st.getHunger() <= 0 || st.getHunger() >= 100) {
                        frac.remove("hunger");
                    }
                    if (st.getMood() <= 0 || st.getMood() >= 100) {
                        frac.remove("mood");
                    }
                    st.setDecayFrac(serializeFrac(frac));
                    stateRepository.save(st);
                } catch (Exception e) {
                    log.warn("[状态] 生计联动单行失败（隔离继续）: project={} ref={} : {}",
                            projectId, st.getNpcRef(), e.getMessage());
                }
            }
            log.debug("[状态] 生计联动完成: project={} priceFactor={} hungerΔ={} moodΔ={}",
                    projectId, priceFactor, hungerDelta, moodDelta);
        } catch (Exception e) {
            log.warn("[状态] 生计联动失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 钳制到 [min, max]。 */
    private static int clampValue(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 为项目全部 NPC（特殊 + 人群）补齐状态行（读前惰性同步，对齐沙盘 syncProjectStates 哲学）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 初始游戏时刻（秒，可空）
     */
    @Transactional
    public void syncProjectStates(Long projectId, Long gameSecond) {
        if (projectId == null) {
            return;
        }
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter ch : chars) {
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（逐行取档案行内 type 定型；
            // 单表合并后本查询含全部类型，不能再一律按 special 建行）
            ensureState(projectId, NpcRefs.normalizeType(ch.getType()),
                    NpcRefs.of(ch.getId()), gameSecond);
        }
    }

    // ==================== 游戏时间驱动衰减（惰性补偿） ====================

    /**
     * 项目全部 NPC 状态行衰减到目标游戏时刻（惰性补偿：按行内 game_second 差一次性补齐）。
     * <p>挂钩：WorldEngine 逐时推进（特殊+人群）；幂等——行已追平（game_second ≥ 目标）则跳过，
     * 世界暂停（game_second 不推进）自然冻结。</p>
     *
     * @param projectId 项目 ID
     *
     * @param toSecond 目标游戏时刻（秒；null=跳过）
     */
    @Transactional
    public void advance(Long projectId, Long toSecond) {
        if (!props.isEnabled() || projectId == null || toSecond == null) {
            return;
        }
        // 惰性建行（特殊+人群）后再衰减：actor_state 空表首次推进也能自建并追平（对齐方案 §4.4 惰性同步）
        syncProjectStates(projectId, toSecond);
        List<ActorNpcState> rows = stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorNpcState st : rows) {
            long from = st.getGameSecond() == null ? toSecond : st.getGameSecond();
            if (from >= toSecond) {
                continue;
            }
            applyDecay(st, from, toSecond, props);
            // 情绪模型：情绪指数衰减回平静锚点 + 程序化情绪规则 + 派生重算（对齐 mood/urgency 同节奏）
            applyEmotionDecay(st, from, toSecond, props);
            applyProceduralEmotion(st, props, isNightSecond(from, props), false);
            refreshEmotionDerived(st, props, anchors());
            st.setGameSecond(toSecond);
            st.setMood(deriveMood(st, props));
            // 实时行为决策系统：urgency 升档检测（刺激①）——行内旧级别 vs 新级别，
            // 仅升档（0→1/2/3）回调即时决策服务（降档/持平不重复触发，行为连续性）；
            // 5 游戏分钟粒度由引擎感知 tick 承载；回调内异步触发不阻塞衰减推进（失败隔离）
            int oldUrgency = st.getUrgencyLevel() == null ? 0 : st.getUrgencyLevel();
            deriveUrgency(st, props);
            if (realtimeDecisionService != null && st.getUrgencyLevel() != null
                    && st.getUrgencyLevel() > oldUrgency) {
                try {
                    realtimeDecisionService.onStateUrgencyRise(projectId, st.getNpcRef(),
                            oldUrgency, st.getUrgencyLevel(), toSecond);
                } catch (Exception e) {
                    log.warn("[状态] urgency 升档即时决策触发失败（隔离继续）: ref={} : {}",
                            st.getNpcRef(), e.getMessage());
                }
            }
            // 生理需求危险区实时打断（生理需求耦合轮）：任一生理四需低于危险阈值 →
            // 触发 PHYSIOLOGICAL_URGE 刺激「放下手头事」（核心角色专属——人群不触发即时决策，
            // 其危险区由概率化路径强制兜底；触发频率由即时决策服务冷却内建防抖，危险可突破）；
            // 感知 tick（5 游戏分钟）粒度检测，对齐刺激① urgency 升档先例
            if (realtimeDecisionService != null && props.getPhysio() != null
                    && props.getPhysio().isDangerInterruptEnabled()) {
                try {
                    String dangerText = com.holzyn.actor.domain.npc.physio.PhysioNeedService
                            .dangerStimulusTextOf(com.holzyn.actor.domain.npc.physio.PhysioNeedService
                                    .dangerNeedOf(st, props.getPhysio()));
                    if (dangerText != null) {
                        realtimeDecisionService.onStimulus(projectId, st.getNpcRef(),
                                com.holzyn.actor.domain.agent.runtime.StimulusType.PHYSIOLOGICAL_URGE,
                                dangerText, toSecond);
                    }
                } catch (Exception e) {
                    log.warn("[状态] 生理危险区打断触发失败（隔离继续）: ref={} : {}",
                            st.getNpcRef(), e.getMessage());
                }
            }
            stateRepository.save(st);
            // npc:state 内部状态广播（需求跨阈值/情绪切换/urgency 变化去重）
            publishStateAfterChange(projectId, st.getNpcRef(), st);
            // 生理小动作：状态写回时按状态程序化生成小动作
            // 文本（零 AI，特殊+人群都适用；冷却节流防刷屏；失败静默不阻断衰减推进）
            if (microMotionService != null) {
                try {
                    microMotionService.maybeMicroMotion(projectId, st.getNpcRef(), st, toSecond);
                } catch (Exception e) {
                    log.debug("[状态] 小动作生成失败（静默）: ref={} : {}", st.getNpcRef(), e.getMessage());
                }
            }
            // 历史快照（曲线数据源）： 起仅跨游戏小时边界采样——引擎改为每 5 游戏分钟调用
            // advance，不门控会把采样粒度从每小时变成每 5 分钟（168 条/NPC≈7 游戏日基线被打破）；
            // （世界演化与游戏时间系统，）门控按可配粒度 history.sample-minutes
            // （默认 60=每小时；可切 5=细粒度演示——切 5 时保留上限经 History.effectiveMaxPerNpc
            // 自动放大到 2016≈7 游戏日）（失败隔离不影响衰减推进）
            if (crossesSampleBoundary(from, toSecond, sampleMinutes())) {
                recordHistory(projectId, st);
            }
        }
    }

    /**
     * 是否跨越游戏小时边界。
     *
     * @param fromSecond 起始游戏秒
     *
     * @param toSecond 目标游戏秒
     *
     * @return true 表示跨了至少一个游戏小时（from/3600 != to/3600）
     */
    static boolean crossesHourBoundary(long fromSecond, long toSecond) {
        return Math.floorDiv(fromSecond, 3600L) != Math.floorDiv(toSecond, 3600L);
    }

    /**
     * 是否跨越采样粒度边界（历史快照采样门控：按 history.sample-minutes 配置粒度，
     * 默认 60=每小时，可切 5=细粒度演示；sampleMinutes<=0 回退 60）。advance 每行衰减区间
     * 与 onCrowdSchedule 统一口径：floorDiv(from, 粒度秒) != floorDiv(to, 粒度秒) 才采样。
     *
     * @param fromSecond 起始游戏秒
     *
     * @param toSecond 目标游戏秒
     *
     * @param sampleMinutes 采样粒度（游戏分钟；<=0 回退 60=每小时）
     *
     * @return true 表示跨了至少一个采样粒度边界
     */
    static boolean crossesSampleBoundary(long fromSecond, long toSecond, int sampleMinutes) {
        int minutes = sampleMinutes > 0 ? sampleMinutes : 60;
        long bucketSeconds = minutes * 60L;
        return Math.floorDiv(fromSecond, bucketSeconds) != Math.floorDiv(toSecond, bucketSeconds);
    }

    /** 历史快照采样（advance/onCrowdSchedule 统一入口；服务未装配/异常 → 静默跳过） */
    private void recordHistory(Long projectId, ActorNpcState st) {
        if (historyService == null || projectId == null || st == null) {
            return;
        }
        try {
            historyService.recordSnapshot(projectId, st);
        } catch (Exception e) {
            log.debug("[状态] 历史快照采样失败（忽略，不影响衰减）: ref={} : {}", st.getNpcRef(), e.getMessage());
        }
    }

    /**
     * 采样粒度：
     * 读 history.sample-minutes 配置；配置块缺失回退 60（每小时）。
     *
     * @return 采样粒度（游戏分钟；>0）
     */
    private int sampleMinutes() {
        return props.getHistory() == null || props.getHistory().getSampleMinutes() <= 0
                ? 60 : props.getHistory().getSampleMinutes();
    }

    /**
     * 行动挂钩（对齐沙盘 onNpcMoved 模式）：先惰性补偿衰减到当前时刻，再按行动类型恢复需求。
     * <p>挂钩：AgentActionService.execute（特殊 NPC 行动执行后）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcType NPC 类型（special / crowd）
     *
     * @param actionText 行动描述（如「前往酒馆用餐」；可空）
     *
     * @param actionType 行动类型（决策 type，如 custom；可空）
     *
     * @param gameSecond 游戏时刻（秒，可空=回退世界时钟当前值）
     */
    @Transactional
    public void onNpcAction(Long projectId, String npcRef, String npcType, String actionText,
                            String actionType, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        ActorNpcState st = ensureState(projectId, npcType, npcRef, gameSecond);
        if (st == null) {
            return;
        }
        // ：行动挂钩时间基准钳制到引擎推进位置（AgentActionService 未传时刻时
        // 回退引擎位置；事件/决策链传入的流动时钟值一律钳制，防状态行 game_second 超前冻结）
        Long target = sanitizeGameSecond(projectId, gameSecond != null ? gameSecond : currentGameSecond(projectId));
        if (target != null && st.getGameSecond() != null && st.getGameSecond() < target) {
            applyDecay(st, st.getGameSecond(), target, props);
            applyEmotionDecay(st, st.getGameSecond(), target, props);
            st.setGameSecond(target);
        }
        boolean satisfied = applyActionRecovery(st, actionText, actionType, target, props);
        // 情绪：行动满足需求 → P 微升（程序化规则）；派生重算
        applyProceduralEmotion(st, props, target != null && isNightSecond(target, props), satisfied);
        refreshEmotionDerived(st, props, anchors());
        st.setMood(deriveMood(st, props));
        deriveUrgency(st, props);
        stateRepository.save(st);
        // npc:state 内部状态广播（行动恢复/情绪变化去重）
        publishStateAfterChange(projectId, npcRef, st);
        // 行动挂钩内并入 AI 情绪评估（同冷却限制；AI 依赖未装配/失败静默回退程序化）
        evaluateEmotion(projectId, npcRef, npcType, "行动执行", actionText, target);
        // 位置移动与地图生成系统 · M2：就寝回家（开发路径 §4.3）——大睡动作（睡觉/就寝/卧床/
        // 歇息/眠/睡，小憩不算）命中时，若角色当前所在地 ≠ 住所 → 程序化触发「回家」移动
        // （reason=home；到家后 activity_state=rest 由作息状态机处理）。已在家/无住所/未装配
        // MovementService → 空操作（fail-open）；失败隔离不阻断行动主流程。
        if (movementService != null && target != null) {
            try {
                movementService.goHome(projectId, npcRef, npcType, target);
            } catch (Exception e) {
                log.warn("[状态] 就寝回家移动发起失败（隔离继续）: ref={} : {}", npcRef, e.getMessage());
            }
        }
        // ：睡眠清洗——大睡动作（睡觉/就寝/卧床/歇息/眠/睡，小憩不算）触发该角色
        // L1 隔夜清洗（L1 有效期=一段清醒时间：琐事合并/重要+临睡前模糊/重复或高重要蒸馏进 L2）；
        // 冷却（12 游戏时）与超清醒兜底在模糊引擎内自管；旁路增强失败仅告警不阻断行动主流程
        // ⚠️：清洗链含多段 AI（蒸馏/琐事合并，秒级）——本方法带 @Transactional，
        // 若在此同步调用会把 actor_state/actor_memory 行锁持有整个清洗时长（实测 79 次
        // actor_memory 锁超时的持有者之一）。改经 AfterCommit 延迟到行动事务提交后再清洗：
        // 无外层事务（单测/非事务调用）时立即执行，语义不变。
        if (fuzzService != null && target != null && fuzzService.isBigSleepAction(actionText)) {
            try {
                AfterCommit.runAfterCommit("睡眠清洗", () -> {
                    try {
                        fuzzService.onSleepAction(projectId, npcRef, target);
                    } catch (Exception e2) {
                        log.warn("[状态] 睡眠清洗挂钩失败（隔离继续）: ref={} : {}", npcRef, e2.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("[状态] 睡眠清洗挂钩失败（隔离继续）: ref={} : {}", npcRef, e.getMessage());
            }
        }
    }

    /**
     * 人群作息挂钩（OrdinaryNpcService.scheduleProgrammaticByGameHour 调度后）：
     * 惰性衰减补齐 + 按作息状态恢复（rest → 精力/健康恢复，如夜间歇息就寝/小憩）。
     * <p>防双恢复：分层驱动的 rest 动作已经
     * {@link #onNpcAction} 完成一次睡眠恢复（sleep-energy），若作息挂钩再恢复一次会把
     * rest 恢复翻倍（+45×2 钳 100）；本 3 参版委托 4 参版（空已恢复集合=纯程序化调度路径）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 调度到达的游戏时刻（秒，可空）
     *
     * @param npcs 已推进状态的普通 NPC 列表（含最新 state）
     */
    @Transactional
    public void onCrowdSchedule(Long projectId, Long gameSecond, List<ActorCharacter> npcs) {
        onCrowdSchedule(projectId, gameSecond, npcs, java.util.Set.of());
    }

    /**
     * 人群作息挂钩 4 参版：带「本轮已做行动恢复」的 NPC id 集合——
     * 这些 NPC 的分层驱动（AI/概率化）rest 动作已在 {@link #applyCrowdAction}→
     * {@link #onNpcAction} 完成睡眠恢复，作息挂钩跳过其 rest 恢复防双计数；
     * 程序化 computeState 的 rest（未驱动）仍按作息恢复。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 调度到达的游戏时刻（秒，可空）
     *
     * @param npcs 已推进状态的普通 NPC 列表（含最新 state）
     *
     * @param actionRecoveredIds 本轮已由驱动动作完成恢复的 NPC id 集合（null/空=全部按作息恢复）
     */
    @Transactional
    public void onCrowdSchedule(Long projectId, Long gameSecond, List<ActorCharacter> npcs,
                                java.util.Set<Long> actionRecoveredIds) {
        if (!props.isEnabled() || projectId == null || npcs == null || npcs.isEmpty()) {
            return;
        }
        // ：作息挂钩时间基准钳制到引擎推进位置（调用方可能传入流动时钟换算值）
        gameSecond = sanitizeGameSecond(projectId, gameSecond);
        // ：作息运行态（state）已从 actor_profile 收敛到 actor_state —— 按批量口径一次取全（循环内零查库）
        Map<String, String> activityByRef = npcActivityStateService == null
                ? Map.of() : npcActivityStateService.activityStateByRef(projectId);
        for (ActorCharacter n : npcs) {
            ActorNpcState st = ensureState(projectId, NpcRefs.normalizeType(n.getType()),
                    NpcRefs.of(n.getId()), gameSecond);
            if (st == null) {
                continue;
            }
            // 采样门控起点：衰减前的行内游戏时刻
            long fromSecond = st.getGameSecond() == null ? (gameSecond == null ? 0L : gameSecond) : st.getGameSecond();
            if (gameSecond != null && st.getGameSecond() != null && st.getGameSecond() < gameSecond) {
                applyDecay(st, st.getGameSecond(), gameSecond, props);
                applyEmotionDecay(st, st.getGameSecond(), gameSecond, props);
                st.setGameSecond(gameSecond);
            }
            // 作息状态机：rest（歇息就寝/小憩）→ 精力恢复 + 健康微恢复（对齐 §6.2 人群调度挂钩）；
            // ：分层驱动已做行动恢复的 NPC（rest 动作已恢复）跳过，防双恢复
            boolean satisfied = false;
            // ：作息运行态读取改经 NpcActivityStateService（actor_state.activity_state），不再读主表实体
            String activityState = activityByRef.getOrDefault(NpcRefs.of(n.getId()),
                    NpcActivityStateService.DEFAULT_ACTIVITY);
            if ("rest".equals(activityState)
                    && (actionRecoveredIds == null || !actionRecoveredIds.contains(n.getId()))) {
                st.setEnergy(clamp100((st.getEnergy() == null ? 80 : st.getEnergy()) + props.getRecovery().getSleepEnergy()));
                st.setHealth(clamp100((st.getHealth() == null ? 100 : st.getHealth()) + props.getRecovery().getSleepHealth()));
                if (gameSecond != null) {
                    st.setLastSleepGameSecond(gameSecond);
                }
                satisfied = true;
            }
            // 情绪：程序化规则（rest 满足 → P 微升）+ 派生重算
            applyProceduralEmotion(st, props, gameSecond != null && isNightSecond(gameSecond, props), satisfied);
            refreshEmotionDerived(st, props, anchors());
            st.setMood(deriveMood(st, props));
            deriveUrgency(st, props);
            stateRepository.save(st);
            // npc:state 内部状态广播（作息恢复变化去重）
            publishStateAfterChange(projectId, NpcRefs.of(n.getId()), st);
            // 历史快照（曲线数据源：人群作息挂钩采样； 起与 advance 同口径按 sample-minutes
            // 粒度边界门控[默认为小时边界=每小时基线] + 同秒幂等跳过）
            if (crossesSampleBoundary(fromSecond, gameSecond == null ? fromSecond : gameSecond, sampleMinutes())) {
                recordHistory(projectId, st);
            }
        }
    }

    // ==================== 纯静态核心（可单测，无 DB 依赖） ====================

    /**
     * 程序化衰减核心（纯静态，惰性补偿）：按 from→to 游戏秒差整段衰减。
     * <p>规则（§6.1/§6.4/§6.5）：特殊全列；人群仅 hunger/energy（×crowd-decay-scale）；
     * 夜间（sleepStartHour~sleepEndHour 区间）精力反向恢复 + 膀胱不下降；taper 开启时低值区衰减放缓；
     * 舒适不随时间自衰减；健康联动（生理四需长期低于阈值侵蚀 / 全部高于回升阈值回升）。
     * <b>（状态循环修复）</b>：各列衰减/恢复经 {@link #applyBalancedDelta} 双精度
     * 余额结转（decay_frac 列）——引擎 5 游戏分钟 tick 的微量衰减（hunger 1.2/时×0.083≈0.1 点）
     * 不再被整数取整吞掉，需求持续真实下降。</p>
     *
     * @param st 状态行（原地更新）
     *
     * @param fromSecond 起始游戏时刻（秒）
     *
     * @param toSecond 目标游戏时刻（秒）
     *
     * @param props 状态模块配置（衰减率/阈值）
     */
    public static void applyDecay(ActorNpcState st, long fromSecond, long toSecond, NpcStateProperties props) {
        double hours = (toSecond - fromSecond) / 3600.0;
        if (hours <= 0) {
            return;
        }
        NpcStateProperties.Decay d = props.getDecay();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（行内 npc_type 为准）
        boolean crowd = NpcRefs.isCommonType(st.getNpcType());
        double scale = crowd ? props.getCrowdDecayScale() : 1.0;
        // ====================：生理基线联动（档案 ③ 维 → actor_state 生效系数） ====================
        // 衰减率不再是纯全局参数，而是「全局默认 × 该 NPC 的个体生理系数」：
        // 代谢快慢/餐制 → 饥饿与口渴速率；睡眠需求 → 精力速率；作息型 → 夜间时段的个体相位；
        // 耐痛 → 健康侵蚀；体质耐力 → 恢复速率。系数由 NpcStateService.ensurePhysioProfile 在
        // 建档/档案修订/阶段跨越时从 actor_state.physio_json 换算并缓存，本处只读缓存（零 JSON 解析）。
        PhysioProfile physio = PhysioProfile.fromCoefJson(st.getPhysioCoefJson());
        int hourOfDay = (int) Math.floorMod(fromSecond / 3600L, 24);
        // 夜间判定改为"该 NPC 自己的作息相位"（早鸟 21-5 / 中间 22-6 / 夜猫 24-8），
        // 而非全员共用一个全局窗口——这是个体差异进入世界节律的最直观处。
        boolean night = physio.isSleepWindow(hourOfDay);

        if (crowd) {
            // 人群轻量：仅 hunger/energy 参与衰减（其余列保持默认）
            st.setHunger(balancedDecay(st, "hunger", st.getHunger(),
                    d.getHungerPerHour() * scale * physio.hungerScale(), hours, d, 70));
            if (night) {
                st.setEnergy(balancedRestore(st, "energy", st.getEnergy(),
                        d.getSleepRestorePerHour() * scale * physio.recoverScale(), hours, 80));
                st.setLastSleepGameSecond(toSecond);
            } else {
                st.setEnergy(balancedDecay(st, "energy", st.getEnergy(),
                        d.getEnergyPerHour() * scale * physio.energyScale(), hours, d, 80));
            }
            return;
        }

        // 特殊/统一全量：生理四需 + 清洁/社交/趣味 衰减（膀胱夜间不下降；舒适不衰减）
        st.setHunger(balancedDecay(st, "hunger", st.getHunger(),
                d.getHungerPerHour() * physio.hungerScale(), hours, d, 70));
        st.setThirst(balancedDecay(st, "thirst", st.getThirst(),
                d.getThirstPerHour() * physio.thirstScale(), hours, d, 70));
        if (night) {
            st.setEnergy(balancedRestore(st, "energy", st.getEnergy(),
                    d.getSleepRestorePerHour() * physio.recoverScale(), hours, 80));
            st.setLastSleepGameSecond(toSecond);
        } else {
            st.setEnergy(balancedDecay(st, "energy", st.getEnergy(),
                    d.getEnergyPerHour() * physio.energyScale(), hours, d, 80));
        }
        if (!night) {
            st.setBladder(balancedDecay(st, "bladder", st.getBladder(),
                    d.getBladderPerHour() * physio.bowelScale(), hours, d, 50));
        }
        st.setHygiene(balancedDecay(st, "hygiene", st.getHygiene(), d.getHygienePerHour(), hours, d, 90));
        st.setSocial(balancedDecay(st, "social", st.getSocial(), d.getSocialPerHour(), hours, d, 60));
        st.setFun(balancedDecay(st, "fun", st.getFun(), d.getFunPerHour(), hours, d, 60));

        // 健康联动（§6.4）：任一生理四需低于侵蚀阈值 → 侵蚀；全部高于回升阈值 → 回升（仅特殊）
        // ：侵蚀速率乘以该 NPC 的耐痛系数（耐痛高 → 同样的低需求下健康掉得慢）
        applyHealthCorrosion(st, hours, props, physio.painFactor());
    }

    /**
     * 行动类型 → 需求恢复映射（§6.2，可配置 ±）：按行动文本/类型关键词匹配恢复对应需求，
     * 记录最近进食/睡眠游戏时刻；封顶 100 下限 0。
     * <p>/ （状态循环修复，24h 压测实证）：关键词表全量扩充八类——
     * ① eat 补「充饥/干粮/觅食/饱腹/果腹/吃食/点心/用饭/粥/羹/粮…」（陆沉舟「就着干粮充饥」
     * 曾「吃了白吃」，人群默认动作「觅食充饥」不命中）；② sleep 分支补英文 <b>rest</b>
     * （AI 决策 type=rest 与关键词不对齐，休息永不恢复精力）+「调息/养神/歇脚/打盹/憩/午休/闭目」；
     * ③ drink/toilet/wash/chat/play/treat 同步扩充（含古意词：出恭/更衣/净手/解溲、梳洗/盥洗、
     * 叙旧/拉家常、听戏/弈棋/垂钓、敷药/煎药/诊脉/请郎中）。</p>
     *
     * @param st 状态行（原地更新）
     *
     * @param actionText 行动描述（可空）
     *
     * @param actionType 行动类型（可空）
     *
     * @param gameSecond 当前游戏时刻（秒，可空；用于 last_eat/last_sleep 记录）
     *
     * @param props 状态模块配置（恢复量）
     *
     * @return 是否命中任一恢复映射
     */
    public static boolean applyActionRecovery(ActorNpcState st, String actionText, String actionType,
                                              Long gameSecond, NpcStateProperties props) {
        if (st == null) {
            return false;
        }
        boolean satisfied = false;
        NpcStateProperties.Recovery r = props.getRecovery();
        String key = ((actionText == null ? "" : actionText) + " " + (actionType == null ? "" : actionType)).toLowerCase();

        if (containsAny(key, "eat", "进食", "用餐", "下馆子", "吃饭", "用膳", "就餐",
                "充饥", "干粮", "觅食", "饱腹", "果腹", "吃食", "点心", "用饭",
                "早膳", "午膳", "晚膳", "夜宵", "羹", "粥", "饭", "粮", "餐", "食")) {
            st.setHunger(clamp100((st.getHunger() == null ? 70 : st.getHunger()) + r.getEatHunger()));
            if (gameSecond != null) {
                st.setLastEatGameSecond(gameSecond);
            }
            satisfied = true;
        }
        if (containsAny(key, "drink", "喝水", "饮水", "饮酒", "喝茶",
                "取水", "灌水", "润喉", "水囊", "解渴", "汤", "酒", "茶", "饮", "水")) {
            st.setThirst(clamp100((st.getThirst() == null ? 70 : st.getThirst()) + r.getDrinkThirst()));
            satisfied = true;
        }
        if (containsAny(key, "sleep", "rest", "睡觉", "就寝", "休息", "歇息", "小憩", "卧床",
                "调息", "养神", "歇脚", "打盹", "午休", "闭目", "憩", "眠", "睡")) {
            st.setEnergy(clamp100((st.getEnergy() == null ? 80 : st.getEnergy()) + r.getSleepEnergy()));
            st.setHealth(clamp100((st.getHealth() == null ? 100 : st.getHealth()) + r.getSleepHealth()));
            if (gameSecond != null) {
                st.setLastSleepGameSecond(gameSecond);
            }
            satisfied = true;
        }
        if (containsAny(key, "toilet", "如厕", "解手", "如恭", "出恭", "净手", "更衣", "解溲", "小解")) {
            st.setBladder(clamp100((st.getBladder() == null ? 50 : st.getBladder()) + r.getToiletBladder()));
            satisfied = true;
        }
        if (containsAny(key, "wash", "洗浴", "洗漱", "沐浴", "净面", "梳洗", "盥洗", "擦洗", "净身", "浴")) {
            st.setHygiene(clamp100((st.getHygiene() == null ? 90 : st.getHygiene()) + r.getWashHygiene()));
            satisfied = true;
        }
        if (containsAny(key, "chat", "交谈", "社交", "攀谈", "寒暄", "聊天",
                "唠", "叙话", "闲聊", "搭话", "叙旧", "拉家常", "谈天")) {
            st.setSocial(clamp100((st.getSocial() == null ? 60 : st.getSocial()) + r.getChatSocial()));
            satisfied = true;
        }
        if (containsAny(key, "play", "娱乐", "消遣", "玩耍", "游玩", "下棋",
                "听戏", "弈棋", "听曲", "垂钓", "赏花", "庙会", "逛")) {
            st.setFun(clamp100((st.getFun() == null ? 60 : st.getFun()) + r.getPlayFun()));
            satisfied = true;
        }
        if (containsAny(key, "treat", "治疗", "就医", "疗伤", "看大夫", "看医生",
                "敷药", "煎药", "诊脉", "请郎中", "服药", "抓药", "问诊", "包扎")) {
            st.setHealth(clamp100((st.getHealth() == null ? 100 : st.getHealth()) + r.getTreatHealth()));
            satisfied = true;
        }
        return satisfied;
    }

    /**
     * mood 派生：需求满意度加权聚合 × (1-w) + 情绪分 × w + env_modifier。
     * <p>需求分量权重：生理四需 1.0 / 健康 0.8 / 清洁·社交·趣味 0.5；人群仅 hunger/energy（轻量列语义）。
     * 情绪分 = (P × 强度系数 + 1) / 2 × 100（0-100，强度系数 = emotionIntensity/100）；
     * w = emotion.mood-weight（默认 0.3）——高兴（P 正）拉高心情、愤怒/悲伤（P 负）拉低。</p>
     *
     * @param st 状态行（只读取值）
     *
     * @param props 状态模块配置（mood-weight）
     */
    public static int deriveMood(ActorNpcState st, NpcStateProperties props) {
        if (st == null) {
            return 70;
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（行内 npc_type 为准）
        if (NpcRefs.isCommonType(st.getNpcType())) {
            double avg = (val(st.getHunger(), 70) + val(st.getEnergy(), 80)) / 2.0;
            return clamp100((int) Math.round(avg));
        }
        double sum = (val(st.getHunger(), 70) + val(st.getThirst(), 70) + val(st.getEnergy(), 80)
                + val(st.getBladder(), 50)) * 1.0
                + val(st.getHealth(), 100) * 0.8
                + (val(st.getHygiene(), 90) + val(st.getSocial(), 60) + val(st.getFun(), 60)) * 0.5;
        double weight = 4 * 1.0 + 0.8 + 3 * 0.5;
        double needsBase = sum / weight;
        // 情绪积分：情绪分 = (P × 强度系数 + 1)/2 × 100；加权合并
        double w = props == null ? 0.3 : props.getEmotion().getMoodWeight();
        w = Math.max(0.0, Math.min(1.0, w));
        double intensity = st.getEmotionIntensity() == null ? 0 : st.getEmotionIntensity();
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double emotionScore = (p * (intensity / 100.0) + 1.0) / 2.0 * 100.0;
        double merged = needsBase * (1 - w) + emotionScore * w;
        return clamp100((int) Math.round(merged) + (st.getEnvModifier() == null ? 0 : st.getEnvModifier()));
    }

    /**
     * urgency 派生（§6.3）：0 无 / 1 轻度（任一需求&lt;mild）/ 2 强烈（任一需求&lt;strong）/
     * 3 健康告急（health&lt;healthAlert）；并写 urgency_text（最紧急需求描述）。原地更新 st 并返回分级。
     *
     * @param st 状态行（原地更新 urgencyLevel/urgencyText）
     *
     * @param props 状态模块配置（阈值）
     *
     * @return urgency 分级 0-3
     */
    public static int deriveUrgency(ActorNpcState st, NpcStateProperties props) {
        int level = 0;
        String text = "";
        double healthAlert = props.getUrgency().getHealthAlert();
        double strong = props.getUrgency().getThresholdStrong();
        double mild = props.getUrgency().getThresholdMild();

        int health = st.getHealth() == null ? 100 : st.getHealth();
        if (health < healthAlert) {
            level = 3;
            text = "身体不适，急需休息或就医";
        } else {
            // 生理四需中取最紧急者（值最低）
            String[] most = mostUrgentPhysiology(st);
            if (most != null) {
                double needVal = Double.parseDouble(most[2]);
                if (needVal < strong) {
                    level = 2;
                    text = urgentTextFor(most[1], 2);
                } else if (needVal < mild) {
                    level = 1;
                    text = urgentTextFor(most[1], 1);
                }
            }
        }
        st.setUrgencyLevel(level);
        st.setUrgencyText(text);
        return level;
    }

    // ==================== 情绪模型：程序化规则 / 衰减 / 锚点重算 / AI 评估（纯静态核心） ====================

    /**
     * 程序化情绪规则（§7.2 第一路，纯静态）：需求/健康/夜间/行动满足 → PAD 增减。
     * <p>规则：饥饿&lt;20 → P 降 A 升（烦躁）；口渴&lt;20 → P 降（难受）；膀胱&lt;20 → D 降（不安）；
     * 精力&lt;20 → A 降（疲惫低唤醒）；社交&lt;20 → P 降（孤独）；夜间 → A 降（睡意）；
     * 本时行动满足需求 → P 微升（进食/休息/如厕等恢复命中）。</p>
     * <p>增量幅度小（每时机 ≤0.10），与情绪指数衰减/主导重算配合避免瞬时跳变。</p>
     *
     * @param st 状态行（原地更新 PAD）
     *
     * @param props 状态模块配置
     *
     * @param night 当前是否为夜间睡眠时段（夜间 A 降）
     *
     * @param actionSatisfied 本时机行动是否满足了需求（true → P 微升）
     */
    public static void applyProceduralEmotion(ActorNpcState st, NpcStateProperties props, boolean night,
                                              boolean actionSatisfied) {
        if (st == null) {
            return;
        }
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double a = valD(st.getEmotionArousal(), CALM_AROUSAL);
        double d = valD(st.getEmotionDominance(), CALM_DOMINANCE);
        if (val(st.getHunger(), 70) < 20) {
            p -= 0.10;
            a += 0.10;
        }
        if (val(st.getThirst(), 70) < 20) {
            p -= 0.10;
        }
        if (val(st.getBladder(), 50) < 20) {
            d -= 0.10;
        }
        if (val(st.getEnergy(), 80) < 20) {
            a -= 0.05;
        }
        if (val(st.getSocial(), 60) < 20) {
            p -= 0.05;
        }
        if (night) {
            a -= 0.05;
        }
        if (actionSatisfied) {
            p += 0.05;
        }
        st.setEmotionPleasure(clampPad(p));
        st.setEmotionArousal(clampPad(a));
        st.setEmotionDominance(clampPad(d));
    }

    /**
     * 情绪指数衰减回平静锚点（§7.1，纯静态）：PAD 各维 = 平静 + (当前 - 平静) × 0.5^(hours/半衰期)。
     * <p>心情协同（§7.3）：mood 低于 {@code emotion.mood-synergy-threshold} 且 P&lt;0（负面情绪）
     * → 衰减量放慢 ×{@code emotion.mood-synergy-factor}（心情差 → 负面情绪退得慢）。</p>
     *
     * @param st 状态行（原地更新 PAD）
     *
     * @param fromSecond 起始游戏时刻（秒）
     *
     * @param toSecond 目标游戏时刻（秒）
     *
     * @param props 状态模块配置（半衰期/协同阈值/协同系数）
     */
    public static void applyEmotionDecay(ActorNpcState st, long fromSecond, long toSecond, NpcStateProperties props) {
        if (st == null) {
            return;
        }
        double hours = (toSecond - fromSecond) / 3600.0;
        if (hours <= 0) {
            return;
        }
        double halfLife = props.getEmotion().getDecayHalfLifeHours();
        if (halfLife <= 0) {
            halfLife = 6.0;
        }
        double ratio = 1 - Math.pow(0.5, hours / halfLife);
        double factor = 1.0;
        if (st.getMood() != null && st.getMood() < props.getEmotion().getMoodSynergyThreshold()
                && valD(st.getEmotionPleasure(), CALM_PLEASURE) < 0) {
            factor = props.getEmotion().getMoodSynergyFactor();
        }
        double fraction = ratio * factor;
        st.setEmotionPleasure(clampPad(CALM_PLEASURE
                + (valD(st.getEmotionPleasure(), CALM_PLEASURE) - CALM_PLEASURE) * (1 - fraction)));
        st.setEmotionArousal(clampPad(CALM_AROUSAL
                + (valD(st.getEmotionArousal(), CALM_AROUSAL) - CALM_AROUSAL) * (1 - fraction)));
        st.setEmotionDominance(clampPad(CALM_DOMINANCE
                + (valD(st.getEmotionDominance(), CALM_DOMINANCE) - CALM_DOMINANCE) * (1 - fraction)));
    }

    /**
     * 主导情绪标签重算（§7.1，纯静态）：距 7 锚点（actor_emotion_anchor）欧氏距离最近者；
     * 锚点列表为空/全无三维时回退 calm。仅在非 AI 显式标签时调用（explicit=0）。
     *
     * @param st 状态行（只读取 PAD）
     *
     * @param anchors 情绪锚点列表（可为空）
     *
     * @return 主导情绪标签（锚点 code，回退 calm）
     */
    public static String deriveDominantEmotion(ActorNpcState st, List<ActorEmotionAnchor> anchors) {
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double a = valD(st.getEmotionArousal(), CALM_AROUSAL);
        double d = valD(st.getEmotionDominance(), CALM_DOMINANCE);
        String best = EMOTION_CALM;
        double bestDist = Double.MAX_VALUE;
        if (anchors != null) {
            for (ActorEmotionAnchor an : anchors) {
                if (an.getPleasure() == null || an.getArousal() == null || an.getDominance() == null) {
                    continue;
                }
                double dist = Math.pow(an.getPleasure() - p, 2)
                        + Math.pow(an.getArousal() - a, 2)
                        + Math.pow(an.getDominance() - d, 2);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = an.getCode() == null || an.getCode().isBlank() ? EMOTION_CALM : an.getCode();
                }
            }
        }
        return best;
    }

    /**
     * 情绪强度重算（§7.1，纯静态）：PAD 向量模长 / √3 × 100（0-100 归一；|(1,1,1)|=√3 → 100）。
     *
     * @param st 状态行（只读取 PAD）
     *
     * @return 情绪强度 0-100
     */
    public static int deriveEmotionIntensity(ActorNpcState st) {
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double a = valD(st.getEmotionArousal(), CALM_AROUSAL);
        double d = valD(st.getEmotionDominance(), CALM_DOMINANCE);
        double mag = Math.sqrt(p * p + a * a + d * d);
        return (int) Math.round(mag / Math.sqrt(3.0) * 100.0);
    }

    /**
     * 统一刷新情绪派生（§7.1，纯静态）：钳制 PAD [-1,1] + 主导标签（非 AI 显式则重算最近锚点）+
     * 强度重算（PAD 模长归一）。任何 PAD 变化（衰减/程序化规则/AI 评估）后调用。
     *
     * @param st 状态行（原地更新 dominantEmotion/emotionIntensity）
     *
     * @param props 状态模块配置（未使用，保留签名一致）
     *
     * @param anchors 情绪锚点列表（可为空）
     */
    public static void refreshEmotionDerived(ActorNpcState st, NpcStateProperties props, List<ActorEmotionAnchor> anchors) {
        if (st == null) {
            return;
        }
        st.setEmotionPleasure(clampPad(valD(st.getEmotionPleasure(), CALM_PLEASURE)));
        st.setEmotionArousal(clampPad(valD(st.getEmotionArousal(), CALM_AROUSAL)));
        st.setEmotionDominance(clampPad(valD(st.getEmotionDominance(), CALM_DOMINANCE)));
        if (!Integer.valueOf(1).equals(st.getEmotionLabelExplicit())) {
            st.setDominantEmotion(deriveDominantEmotion(st, anchors));
        }
        st.setEmotionIntensity(deriveEmotionIntensity(st));
    }

    /**
     * 应用 AI 情绪评估（§7.2 第二/三路，纯静态）：padDelta 位移 PAD + OCC 规则映射（occ-enabled 时）+
     * 钳制 [-1,1] + 主导标签（显式标签当次写入生效；无则最近锚点）+ 强度（AI 提供则用，否则按 PAD 重算）。
     * <p>OCC 映射（规则）：合意性 desirability → P 轴 × 权重；称赞性 praiseworthiness → D 轴 × 权重
     * （+ P 轻度随动 ×0.3）；喜好性 liking → P/D 双轴 × 权重。OCC 默认关（occ-enabled=false 时忽略 OCC 字段）。</p>
     *
     * @param st 状态行（原地更新）
     *
     * @param eval AI 解析后的情绪评估（可为 null → false）
     *
     * @param props 状态模块配置（OCC 开关/权重）
     *
     * @param anchors 情绪锚点列表（无显式标签时最近锚点判定）
     *
     * @return 是否应用了任何变更
     */
    public static boolean applyEmotionEval(ActorNpcState st, EmotionEvalParse.EmotionEval eval,
                                           NpcStateProperties props, List<ActorEmotionAnchor> anchors) {
        if (st == null || eval == null) {
            return false;
        }
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double a = valD(st.getEmotionArousal(), CALM_AROUSAL);
        double d = valD(st.getEmotionDominance(), CALM_DOMINANCE);
        boolean changed = false;
        // ① 直接 padDelta 位移（delta 已由解析器界限守卫，结果再钳制）
        if (eval.padPleasure() != null || eval.padArousal() != null || eval.padDominance() != null) {
            p += eval.padPleasure() == null ? 0 : eval.padPleasure();
            a += eval.padArousal() == null ? 0 : eval.padArousal();
            d += eval.padDominance() == null ? 0 : eval.padDominance();
            changed = true;
        }
        // ② OCC 显式三维评价（occ-enabled 且提供 OCC 维度）
        if (props.getEmotion().isOccEnabled()
                && (eval.desirability() != null || eval.praiseworthiness() != null || eval.liking() != null)) {
            double wDes = props.getEmotion().getOccDesirabilityWeight();
            double wPraise = props.getEmotion().getOccPraiseworthinessWeight();
            double wLike = props.getEmotion().getOccLikingWeight();
            if (eval.desirability() != null) {
                p += eval.desirability() * wDes;
                changed = true;
            }
            if (eval.praiseworthiness() != null) {
                d += eval.praiseworthiness() * wPraise;
                p += eval.praiseworthiness() * wPraise * 0.3; // 称赞/责备轻度 P 随动
                changed = true;
            }
            if (eval.liking() != null) {
                p += eval.liking() * wLike;
                d += eval.liking() * wLike;
                changed = true;
            }
        }
        if (!changed && (eval.emotion() == null || eval.emotion().isBlank()) && eval.intensity() == null) {
            return false; // 无可应用内容
        }
        st.setEmotionPleasure(clampPad(p));
        st.setEmotionArousal(clampPad(a));
        st.setEmotionDominance(clampPad(d));
        // ③ 主导标签：AI 显式标签当次写入生效；无显式 → 最近锚点
        if (eval.emotion() != null && !eval.emotion().isBlank()) {
            st.setDominantEmotion(normalizeEmotionLabel(eval.emotion()));
            st.setEmotionLabelExplicit(1);
        } else {
            st.setEmotionLabelExplicit(0);
            st.setDominantEmotion(deriveDominantEmotion(st, anchors));
        }
        // ④ 强度：AI 提供则用（钳制 0-100），否则按 PAD 模长重算
        if (eval.intensity() != null) {
            st.setEmotionIntensity(Math.max(MIN_VALUE, Math.min(MAX_VALUE, eval.intensity())));
        } else {
            st.setEmotionIntensity(deriveEmotionIntensity(st));
        }
        return true;
    }

    // ==================== AI 情绪评估（实例：冷却/人群门控/调用/写回） ====================

    /**
     * AI 情绪评估写回（§7.2 第二路，实例）：对话/事件/行动三挂钩共用入口（<b>异步</b>——虚拟线程
     * 执行，不阻塞调用方主流程，对齐记忆抽取模式）。实际工作在 {@link #doEvaluateEmotion}。
     *
     * @param projectId 项目 ID
     *
     * @param npcType NPC 类型（special / crowd）
     *
     * @param contextKind 情境类型（如「近期对话」「世界事件」「行动执行」）
     *
     * @param contextText 情境文本（对话/事件/行动内容；可空）
     *
     * @param gameSecond 游戏时刻（秒，可空=回退世界时钟当前值）
     */
    public void evaluateEmotion(Long projectId, String npcRef, String npcType, String contextKind,
                                String contextText, Long gameSecond) {
        // ==================== 阶段⑤：tick 屏障（窄屏障成员） ====================
        // 情绪 AI 评估会写 actor_state（且用的是"调用方时刻"），此前完全不受 tick 约束：
        // 写回时状态衰减可能已经往前走了好几 tick（同一行被反复覆盖，实测 56 次锁超时）。
        // 现纳入窄屏障：屏障已过 → 排队到下一 tick 派发；未过 → 立即执行并 register，
        // 本 tick 的屏障等它结束后才推进下一 tick。
        if (tickBarrier != null && tickBarrier.shouldDefer(projectId)) {
            tickBarrier.defer(projectId, () -> evaluateEmotion(projectId, npcRef, npcType,
                    contextKind, contextText, gameSecond));
            log.debug("[状态情绪] 屏障已过，情绪评估排队到下一 tick: ref={}", npcRef);
            return;
        }
        if (tickBarrier != null) {
            tickBarrier.register(projectId);
        }
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    doEvaluateEmotion(projectId, npcRef, npcType, contextKind, contextText, gameSecond);
                } catch (Exception e) {
                    log.warn("[状态情绪] AI 评估异步执行失败（静默）: ref={} : {}", npcRef, e.getMessage());
                } finally {
                    if (tickBarrier != null) {
                        tickBarrier.complete(projectId);
                    }
                }
            });
            submitted = true;
        } finally {
            if (!submitted && tickBarrier != null) {
                // 提交失败：回滚登记，避免屏障空等（自愈之外的第二道保险）
                tickBarrier.complete(projectId);
            }
        }
    }

    /**
     * AI 情绪评估实际执行（异步线程内）：开关+依赖检查 → 冷却检查（每 NPC 每游戏小时 ≤1 次，
     * 基准 last_emotion_eval_game_second）→ 人群场景人数门控（crowd 且场景 NPC 数 &gt;
     * ai-crowd-threshold 跳过）→ 渲染 npc_emotion_eval 模板 → json_object AI 调用
     * （trace/用量 scene=npc_emotion_eval）→ {@link EmotionEvalParse} 解析 →
     * {@link #applyEmotionEval} 应用（钳制/显式标签/OCC）→ 落库。
     * AI 失败/输出非法/解析空 → 静默回退程序化情绪规则（不阻断调用方）。
     * 后台线程无显式事务包裹（对齐抽取模式）：仓库单操作自提交，幂等 upsert 语义保证安全。
     */
    private void doEvaluateEmotion(Long projectId, String npcRef, String npcType, String contextKind,
                                   String contextText, Long gameSecond) {
        if (!props.isEnabled() || !props.getEmotion().isAiEvalEnabled()) {
            return;
        }
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return; // AI 依赖未装配（纯数据单测/无 AI 环境）→ 跳过
        }
        // ：AI 评估写回时刻钳制到引擎推进位置（评估异步执行，调用方时刻可能过期/超前）
        Long target = sanitizeGameSecond(projectId, gameSecond != null ? gameSecond : currentGameSecond(projectId));
        if (target == null) {
            return;
        }
        ActorNpcState st = ensureState(projectId, npcType, npcRef, target);
        if (st == null) {
            return;
        }
        // 冷却：每 NPC 每游戏小时最多 1 次
        double cooldownHours = props.getEmotion().getAiEvalCooldownHours();
        if (st.getLastEmotionEvalGameSecond() != null
                && (target - st.getLastEmotionEvalGameSecond()) < (long) (cooldownHours * 3600)) {
            return;
        }
        // 人群场景人数门控（crowd 且场景 NPC 数 > 阈值 → 跳过 AI 评估）
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型值经 NpcRefs 归一比较）
        if (NpcRefs.isCommonType(npcType) && !scenePopulationWithinThreshold(projectId, npcRef)) {
            return;
        }
        try {
            Long userId = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            String characterInfo = characterInfoLine(projectId, npcType, npcRef);
            String currentEmotion = currentEmotionLine(st);
            String gameTime = gameTimeText(target);
            // ：npc:{id} 不再编码类型，此处按意图 A 处理（trace/用量记账的角色 id）
            Long charId = NpcRefs.idOf(npcRef);
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_NPC_EMOTION_EVAL,
                    Map.of("character_info", characterInfo.isBlank() ? "（无档案信息）" : characterInfo,
                            "current_emotion", currentEmotion,
                            "context_kind", contextKind == null || contextKind.isBlank() ? "情境" : contextKind,
                            "context", contextText == null ? "" : contextText,
                            "game_time", gameTime));
            // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：情绪评估在 character_info（段 1A 世界观 +
            // 段 1C 角色核心卡，characterInfoLine 已前置）基础上，再前置段 2D 当前世界动态 +
            // 段 2E 当前状态（此刻时节天气 + 需求/心情/情绪/健康全量）——AI 判断情绪时有「此刻处境」
            // 依据（批 3 decisionStatePrefix 同款；未装配/组装失败零阻断，不影响原调用）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.decisionStatePrefix(projectId, npcRef, target);
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            AiChatResult result = AiCallContext.run(SCENE_EMOTION_EVAL, charId, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    EMOTION_EVAL_TEMPERATURE, EMOTION_EVAL_MAX_TOKENS, true)));
            usageLogService.record(userId, projectId, charId, result.providerId(), result.model(), SCENE_EMOTION_EVAL,
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            EmotionEvalParse.EmotionEval eval = EmotionEvalParse.parse(result.content());
            if (eval == null) {
                // 输出空/非法 → 静默回退程序化情绪规则（同时武装冷却，防空决策反复触发）
                st.setLastEmotionEvalGameSecond(target);
                fallbackProceduralEmotion(st, target, false);
                // 世界运行抓修：冷却「武装」必须真的落库才算武装——原实现只改内存对象
                // 就 return，实体是脱管的，冷却窗口与程序化回退都不会持久化 → 下一个 tick 又对同一
                // NPC 发起一次注定失败的 AI 评估（白烧 token，且行为上情绪永远停在旧值）。
                persistStateQuietly(st, npcRef);
                return;
            }
            String emotionBefore = st.getDominantEmotion();
            applyEmotionEval(st, eval, props, anchors());
            st.setLastEmotionEvalGameSecond(target);
            st.setMood(deriveMood(st, props));
            deriveUrgency(st, props);
            // 收口（世界运行测试抓修）：**AI 侧失败**与**落库侧失败**必须分开处理。
            // 原实现把 save 与 AI 调用包在同一个 catch 里：实测 20 个 NPC 并发评估 + 引擎 tick 同写
            // actor_state 时，save 会因 H2 锁超时抛异常 → 被当成"AI 评估失败"→ 已付费的 AI 情绪结果
            // 被丢弃并退回程序化情绪（日志也误导为 AI 失败）。现改为：AI 失败才回退；落库失败按退避
            // 重试，重试仍失败则只告警（AI 结果留在内存，下一次状态写回/衰减会把它落库）。
            boolean persisted = persistStateQuietly(st, npcRef);
            // npc:state 内部状态广播（AI 评估情绪写回变化去重）
            publishStateAfterChange(projectId, npcRef, st);
            // 抓修（证据可信度）：原实现无论落库成败都打印「写回完成」——世界运行里出现过
            // 「日志说写回完成、数据库里查不到」的自相矛盾（本次运行实测 2 次），会误导后续排查。
            // 现按真实结果分开打印，落库失败时明确说明「本次结果不持久化」。
            if (persisted) {
                log.info("[状态情绪] AI 评估写回完成: ref={} → {}（强度 {}）", npcRef, st.getDominantEmotion(),
                        st.getEmotionIntensity());
            } else {
                log.warn("[状态情绪] AI 评估已重算但未能落库（本次情绪不入库，冷却窗口后重评）: ref={} → {}（强度 {}）",
                        npcRef, st.getDominantEmotion(), st.getEmotionIntensity());
            }
            // 事件驱动重算触发：情绪漂移检测点——情绪标签较评估前变化 →
            // 决策模型 boolean 确认（space_trigger 场景）→ 空间重算 EMOTION_SHIFT；
            // 失败/未启用 → 程序化阈值兜底；异步执行零阻断（本方法在虚拟线程，非事务）
            if (spaceTriggerJudge != null && persisted) {
                try {
                    spaceTriggerJudge.onEmotionEval(projectId, npcRef, emotionBefore, st, target);
                } catch (Exception e) {
                    log.debug("[状态情绪] 情绪漂移触发判定提交失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[状态情绪] AI 评估失败（静默回退程序化，冷却已武装防轰炸）: ref={} : {}", npcRef, e.getMessage());
            st.setLastEmotionEvalGameSecond(target);
            fallbackProceduralEmotion(st, target, false);
        }
    }

    /** 状态写回重试次数（H2 锁超时属瞬时竞争，退避重试即可成功） */
    private static final int STATE_SAVE_RETRY = 3;
    /** 状态写回重试退避基数（毫秒；第 n 次失败后退避 = 基数 × n） */
    private static final long STATE_SAVE_BACKOFF_MS = 150L;

    /**
     * 状态写回（锁超时退避重试；最终失败只告警不抛，不阻断调用方主流程）。
     * <p>为什么要重试而不是直接放弃：状态写回是<b>多人并发</b>路径（20 个 NPC 的 AI 情绪评估 +
     * 引擎 tick + 决策链都在写 {@code actor_state}），H2 的锁等待超过 {@code LOCK_TIMEOUT}
     * 就会抛异常；而这类竞争是瞬时的（毫秒级），退避重试基本都能成功。
     * 世界运行测试实测：不重试时约 19/35 次情绪评估因锁超时被丢弃（AI token 已花）。</p>
     * <p><b>重试仍失败时的语义（诚实说明）</b>：只告警、不抛出。此时本次 AI 情绪只存在于当前内存对象里
     * （实体未托管，不会被后续 tick 顺手写回），也就是<b>这一次评估白做了</b>；
     * 好处是不再把"落库失败"误报成"AI 失败"、不再叠加一次程序化回退写库，
     * 且冷却窗口（每 NPC 每游戏小时 ≤1 次）过后会重新评估。</p>
     *
     * @param st 状态行（须非空）
     *
     * @param npcRef NPC 引用（仅用于日志）
     *
     * @return true=已落库；false=重试后仍未落库（调用方据此决定日志口径，不再无脑说"写回完成"）
     */
    private boolean persistStateQuietly(ActorNpcState st, String npcRef) {
        if (st == null) {
            return false;
        }
        for (int attempt = 1; attempt <= STATE_SAVE_RETRY; attempt++) {
            try {
                stateRepository.save(st);
                return true;
            } catch (Exception e) {
                if (attempt >= STATE_SAVE_RETRY) {
                    log.warn("[状态情绪] 状态写回失败（已重试 {} 次），本次 AI 情绪未能落库（冷却窗口后重评）: ref={} : {}",
                            attempt, npcRef, e.getMessage());
                    return false;
                }
                try {
                    Thread.sleep(STATE_SAVE_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 情绪锚点列表（实例读取；锚点仓库未装配/读取失败 → 空列表回退 calm）。
     */
    private List<ActorEmotionAnchor> anchors() {
        if (anchorRepository == null) {
            return List.of();
        }
        try {
            return anchorRepository.findByDeletedOrderBySortOrderAscIdAsc(0);
        } catch (Exception e) {
            log.warn("[状态情绪] 锚点读取失败（回退 calm）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 人群场景人数门控（决策 crowd 且场景人数 &gt; ai-crowd-threshold 不触发 AI 评估）。
     * <p>读 actor_sandbox_state：取该 crowd 所在 scene_id，统计同场景全部 NPC（special+crowd）数；
     * 无沙盘行（该 crowd 未建沙盘状态）→ 项目级总数兜底；沙盘仓库未装配/读取失败 → 宽松放行。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true=场景人数 ≤ 阈值（允许 AI 评估）
     */
    private boolean scenePopulationWithinThreshold(Long projectId, String npcRef) {
        if (sandboxStateRepository == null) {
            return true;
        }
        try {
            List<ActorSandboxState> all = sandboxStateRepository.findByProjectIdOrderByIdAsc(projectId);
            if (all == null || all.isEmpty()) {
                return true;
            }
            ActorSandboxState self = sandboxStateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            Long sceneId = self == null ? null : self.getSceneId();
            long inScene;
            if (sceneId == null) {
                inScene = all.size(); // 无沙盘行 → 项目级总数兜底
            } else {
                inScene = all.stream().filter(s -> sceneId.equals(s.getSceneId())).count();
            }
            return inScene <= props.getAiCrowdThreshold();
        } catch (Exception e) {
            log.warn("[状态情绪] 场景人数门控读取失败（宽松放行）: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 角色档案信息一行（评估输入；特殊=角色名+头衔 / 人群=居民名+种族+职业；失败回退泛称）。
     * <p>2026-10-07 上下文注入根治：注入器装配时，在简化简介前拼【世界观】+【我的身份与处境】
     * （世界观简报 + 角色核心卡：性格/背景/目标/关键关系/当前情绪）——AI 评估以「这个人」为基底，
     * 而非光秃秃的名字。</p>
     */
    private String characterInfoLine(Long projectId, String npcType, String npcRef) {
        try {
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型值经 NpcRefs 归一后决定档案字段口径）
            Long id = NpcRefs.idOf(npcRef);
            String base;
            if (NpcRefs.isCommonType(npcType) && id != null && ordinaryNpcRepository != null) {
                ActorCharacter n = ordinaryNpcRepository.findById(id).orElse(null);
                if (n != null) {
                    base = "身份：居民「" + n.getName() + "」（种族 "
                            + (n.getRace() == null || n.getRace().isBlank() ? "未知" : n.getRace())
                            + " / 职业 " + (n.getOccupation() == null || n.getOccupation().isBlank() ? "未知" : n.getOccupation())
                            + "）";
                } else {
                    base = "身份：角色";
                }
            } else if (id != null && characterRepository != null) {
                ActorCharacter ch = characterRepository.findById(id).orElse(null);
                if (ch != null) {
                    base = "身份：「" + ch.getName() + "」"
                            + (ch.getTitle() == null || ch.getTitle().isBlank() ? "" : "（" + ch.getTitle() + "）");
                } else {
                    base = "身份：角色";
                }
            } else {
                base = "身份：角色";
            }
            // 注入器组合段（世界观简报 + 角色核心卡；未装配/无数据返回空串 → 保留简化简介）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.compose(projectId, npcRef);
            if (injected.isBlank()) {
                return base;
            }
            return injected + "\n" + base;
        } catch (Exception e) {
            return "身份：角色";
        }
    }

    /**
     * 当前情绪状态一行（评估输入：心情/主导情绪/强度/PAD/紧急）。
     */
    private String currentEmotionLine(ActorNpcState st) {
        double p = valD(st.getEmotionPleasure(), CALM_PLEASURE);
        double a = valD(st.getEmotionArousal(), CALM_AROUSAL);
        double d = valD(st.getEmotionDominance(), CALM_DOMINANCE);
        return "心情 " + (st.getMood() == null ? 70 : st.getMood()) + "/100；主导情绪 "
                + (st.getDominantEmotion() == null || st.getDominantEmotion().isBlank() ? "calm" : st.getDominantEmotion())
                + "（强度 " + (st.getEmotionIntensity() == null ? 0 : st.getEmotionIntensity()) + "）；"
                + String.format("PAD[P=%.2f A=%.2f D=%.2f]", p, a, d)
                + "；紧急：" + (st.getUrgencyText() == null || st.getUrgencyText().isBlank() ? "无" : st.getUrgencyText());
    }

    /**
     * 游戏时间文本（评估输入；从目标秒派生「第 X 游戏日 Y 时」，不依赖世界时钟保证单测稳定）。
     */
    private String gameTimeText(Long gameSecond) {
        if (gameSecond == null) {
            return "未知";
        }
        long day = gameSecond / (3600L * 24);
        int hour = (int) Math.floorMod(gameSecond / 3600L, 24);
        return "第 " + (day + 1) + " 游戏日 " + hour + " 时";
    }

    /**
     * AI 评估失败/空决策的回退路径：程序化情绪规则 + 派生重算 + mood/urgency 刷新 + 落库。
     */
    private void fallbackProceduralEmotion(ActorNpcState st, Long target, boolean actionSatisfied) {
        if (st == null) {
            return;
        }
        applyProceduralEmotion(st, props, target != null && isNightSecond(target, props), actionSatisfied);
        refreshEmotionDerived(st, props, anchors());
        st.setMood(deriveMood(st, props));
        deriveUrgency(st, props);
        try {
            stateRepository.save(st);
            publishStateAfterChange(st.getProjectId(), st.getNpcRef(), st);
        } catch (Exception ignored) {
            // 回退落库失败静默（不阻断调用方主流程）
        }
    }

    // ==================== 【当前状态】段注入（§8.1：决策/对话只读注入，双轨全量） ====================

    /**
     * 取 NPC 当前状态注入段：惰性建行 + 按游戏时刻惰性补偿衰减
     * （读接口先 syncState 哲学，注入数值准确）后渲染【当前状态】段文本。
     * <p>决策与对话<b>双轨都注入全量段</b>；调用方按 {@code NpcStateProperties.Inject}
     * 开关（inject-ai-decision / inject-dialog）决定是否调用（true 才取段）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏时刻（秒，可空=回退世界时钟当前值）
     *
     * @return 【当前状态】段文本；不可用/无内容返回空串
     */
    public String stateSegment(Long projectId, String npcRef, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || npcRef == null || npcRef.isBlank()) {
            return "";
        }
        ActorNpcState st = ensureState(projectId, refTypeOf(projectId, npcRef), npcRef, gameSecond);
        if (st == null) {
            return "";
        }
        // 惰性补偿：注入前衰减对齐到当前游戏时刻（数值准确；行已追平则跳过）；
        // ：决策/对话注入链传入的流动时钟时刻同样钳制到引擎推进位置
        Long target = sanitizeGameSecond(projectId, gameSecond != null ? gameSecond : currentGameSecond(projectId));
        if (target != null && st.getGameSecond() != null && st.getGameSecond() < target) {
            applyDecay(st, st.getGameSecond(), target, props);
            applyEmotionDecay(st, st.getGameSecond(), target, props);
            applyProceduralEmotion(st, props, isNightSecond(target, props), false);
            refreshEmotionDerived(st, props, anchors());
            st.setGameSecond(target);
            st.setMood(deriveMood(st, props));
            deriveUrgency(st, props);
            stateRepository.save(st);
        }
        return renderStateSegment(st, props, props.getInject().getStateBudget());
    }

    /**
     * 渲染【当前状态】注入段（§8.1，纯静态可单测）：需求数值+派生描述双轨 + 心情/情绪/紧急 + 健康。
     * <p>格式：<pre>
     * 【当前状态】
     * - 需求：饥饿 68/100（略饿）、口渴 45/100（欠佳）、精力 30/100（比较疲惫）、膀胱 82/100（无尿意）
     * - 心情：62/100；情绪：happy（强度 58）；紧急：有些饥饿
     * - 健康：100/100（健康）</pre>
     * 人群（crowd）仅渲染轻量列（饥饿/精力）；超预算（state-budget）丢健康行再丢派生描述（防撑爆 Prompt）。</p>
     *
     * @param st 状态行（只读取值）
     *
     * @param props 状态模块配置（state-budget）
     *
     * @param budget token 预算（≤0 用配置默认）
     *
     * @return 【当前状态】段文本（含段头；无内容返回空串）
     */
    public static String renderStateSegment(ActorNpcState st, NpcStateProperties props, int budget) {
        if (st == null) {
            return "";
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（行内 npc_type 为准）
        boolean crowd = NpcRefs.isCommonType(st.getNpcType());
        int maxTokens = budget > 0 ? budget : (props != null ? props.getInject().getStateBudget() : 300);
        int maxChars = maxTokens * 4; // 中文约 1 字≈1 token 的保守换算，4 字符/token 防溢出

        // 需求行：特殊全列 / 人群轻量（仅 hunger/energy）
        List<String> needs = new ArrayList<>();
        if (crowd) {
            needs.add(needLine("饥饿", val(st.getHunger(), 70)));
            needs.add(needLine("精力", val(st.getEnergy(), 80)));
        } else {
            needs.add(needLine("饥饿", val(st.getHunger(), 70)));
            needs.add(needLine("口渴", val(st.getThirst(), 70)));
            needs.add(needLine("精力", val(st.getEnergy(), 80)));
            needs.add(needLine("膀胱", val(st.getBladder(), 50)));
        }
        StringBuilder sb = new StringBuilder(STATE_SEGMENT_HEADER);
        sb.append("\n- 需求：").append(String.join("、", needs));
        int mood = st.getMood() == null ? 70 : st.getMood();
        String emotion = st.getDominantEmotion() == null || st.getDominantEmotion().isBlank()
                ? "calm" : st.getDominantEmotion();
        int intensity = st.getEmotionIntensity() == null ? 0 : st.getEmotionIntensity();
        String urgency = st.getUrgencyText() == null || st.getUrgencyText().isBlank() ? "无" : st.getUrgencyText();
        sb.append("\n- 心情：").append(mood).append("/100；情绪：").append(emotion)
                .append("（强度 ").append(intensity).append("）；紧急：").append(urgency);
        if (!crowd) {
            sb.append("\n- 健康：").append(val(st.getHealth(), 100)).append("/100（")
                    .append(needDescriptor("健康", val(st.getHealth(), 100))).append("）");
        }
        // 预算保护：超预算先丢健康行，仍超再丢派生描述（保数值主体），最后硬截断
        if (sb.length() > maxChars) {
            int healthIdx = sb.indexOf("\n- 健康：");
            if (healthIdx >= 0) {
                sb.delete(healthIdx, sb.length());
            }
        }
        if (sb.length() > maxChars) {
            for (String n : needs) {
                int open = n.indexOf('（');
                int close = n.indexOf('）');
                if (open >= 0 && close > open) {
                    String plain = n.substring(0, open) + n.substring(close + 1);
                    sb = new StringBuilder(sb.toString().replace(n, plain));
                    if (sb.length() <= maxChars) {
                        break;
                    }
                }
            }
        }
        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars);
        }
        return sb.toString();
    }

    /** 单条需求行（数值 + 派生描述，§8.1 双轨）。 */
    private static String needLine(String name, int value) {
        return name + " " + value + "/100（" + needDescriptor(name, value) + "）";
    }

    /**
     * 需求数值 → 派生描述（§8.1 双轨：AI 可读文本；纯静态可单测）。
     * <p>分档：≥80 正向 / 60-79 尚可 / 40-59 比较欠 / 20-39 轻度负面 / &lt;20 非常负面（按需求名词化）。</p>
     *
     * @param need 需求名（饥饿/口渴/精力/膀胱/健康/清洁/社交/趣味/舒适）
     *
     * @param value 数值 0-100
     *
     * @return 派生描述（如「有点饿」「很疲惫」；非法需求名回退通用分档）
     */
    public static String needDescriptor(String need, int value) {
        String key = need == null ? "" : need;
        String positive;
        String mild;
        String strong;
        switch (key) {
            case "饥饿" -> { positive = "饱腹"; mild = "有点饿"; strong = "非常饥饿"; }
            case "口渴" -> { positive = "不渴"; mild = "有些渴"; strong = "非常口渴"; }
            case "精力" -> { positive = "精力充沛"; mild = "有点疲惫"; strong = "非常疲惫"; }
            case "膀胱" -> { positive = "无尿意"; mild = "有些尿急"; strong = "尿意很强"; }
            case "健康" -> { positive = "健康"; mild = "微恙"; strong = "身体不适"; }
            case "清洁" -> { positive = "干净清爽"; mild = "有些邋遢"; strong = "很邋遢"; }
            case "社交" -> { positive = "满足"; mild = "有些孤单"; strong = "非常孤独"; }
            case "趣味" -> { positive = "尽兴"; mild = "有些无聊"; strong = "非常无聊"; }
            case "舒适" -> { positive = "舒适"; mild = "将就"; strong = "很不舒适"; }
            default -> { positive = "良好"; mild = "一般"; strong = "很差"; }
        }
        if (value >= 80) {
            return positive;
        }
        if (value >= 60) {
            return "尚可";
        }
        if (value >= 40) {
            return "精力".equals(key) ? "比较疲惫" : "欠佳";
        }
        if (value >= 20) {
            return mild;
        }
        return strong;
    }

    // ==================== 分层驱动（§8.2：人群 AI 建议式 / 大规模概率化 + 对象评分） ====================

    /**
     * 人群 AI 决策场景门控（§8.2 对外入口）：场景人数 ≤ ai-crowd-threshold → 允许 AI 建议式决策。
     * <p>读 actor_sandbox_state 按 scene_id 统计同场景 NPC 数；
     * 无沙盘行回退项目级；沙盘仓库未装配/读取失败宽松放行（小项目默认走 AI）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true=场景人数 ≤ 阈值（人群可走 AI 决策）
     */
    public boolean isCrowdAiEligible(Long projectId, String npcRef) {
        if (!props.isEnabled() || projectId == null || npcRef == null) {
            return false;
        }
        return scenePopulationWithinThreshold(projectId, npcRef);
    }

    /**
     * 人群概率化驱动开关（§8.2 大规模档）。
     *
     * @return true=场景人数 > 阈值时人群走概率化需求满足 + 对象评分
     */
    public boolean isProbabilisticDriveEnabled() {
        return props.isEnabled() && props.getDrive().isCrowdProbabilisticEnabled();
    }

    /**
     * 人群 AI 行动决策（§8.2 AI 建议式，实例）：渲染 npc_crowd_decision 模板 →
     * json_object AI 调用（trace/用量 scene=npc_crowd_decision）→ {@link CrowdDecisionParse} 解析。
     * <p>AI 失败/输出非法/解析空 → 返回 null（调用方回退程序化状态机）；AI 依赖未装配（纯数据单测）
     * → null。同步执行（对齐特殊 NPC 引擎决策模式）；预算门控由调用方（OrdinaryNpcService）负责。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npc 普通 NPC 实体
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @return AI 行动决策（null=不可用/失败）
     */
    public CrowdDecisionParse.CrowdAction decideCrowdAction(Long projectId, ActorCharacter npc, Long gameSecond) {
        return decideCrowdActionCore(projectId, npc, gameSecond, null);
    }

    /**
     * 人群 AI 行动决策（环境变体重载，实时行为决策系统 环境刺激：受影响区域人群对
     * 天气骤变/环境异动/扩散传闻的即时反应）。
     * <p>与三参版同链路，仅用户提示词尾部追加【环境变化】段（零模板改动，内置模板
     * npc_crowd_decision 占位符不变），让 AI 结合环境变化给出此刻最合适的行动
     * （如「大雨将至 → 收摊避雨」）；环境文本空回退三参版行为。预算门控由调用方
     * （EnvironmentStimulusService）负责。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npc 普通 NPC 实体
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @param environmentText 环境刺激文本（可空=常规人群决策）
     *
     * @return AI 行动决策（null=不可用/失败）
     */
    public CrowdDecisionParse.CrowdAction decideCrowdAction(Long projectId, ActorCharacter npc,
                                                            Long gameSecond, String environmentText) {
        return decideCrowdActionCore(projectId, npc, gameSecond, environmentText);
    }

    /** 人群 AI 决策核心（三参/四参共用；environmentText 可空）。 */
    private CrowdDecisionParse.CrowdAction decideCrowdActionCore(Long projectId, ActorCharacter npc,
                                                                 Long gameSecond, String environmentText) {
        if (!props.isEnabled() || npc == null || projectId == null) {
            return null;
        }
        // BrainStrategy 可插拔决策策略链（P2-3）：普通 NPC 链=危险强制→快选择→概率化，
        // 链式责任首中即胜、命中写 trace；链空返回 null（调用方作息状态机兜底，与 P2-3 前一致）
        if (brainStrategyRouter != null) {
            com.holzyn.actor.domain.agent.brain.strategy.DecisionOutcome outcome =
                    brainStrategyRouter.decide(new com.holzyn.actor.domain.agent.brain.strategy.DecisionContext(
                            projectId, npc, null, com.holzyn.actor.domain.agent.brain.strategy.NpcKind.COMMON,
                            null, null, gameSecond, environmentText, null));
            if (outcome != null) {
                return com.holzyn.actor.domain.agent.brain.strategy.CrowdActionCodec.fromMap(outcome.decision());
            }
            return null;
        }
        return legacyCrowdChoice(projectId, npc, gameSecond, environmentText);
    }

    /**
     * 普通 NPC 快选择（公开，BrainStrategy CrowdChoiceStrategy 委托）：决策模型 crowd 判定
     * （空间/程序化候选集 + choice sample 直接采纳）→ AI 建议式回退（Qwen，预算门控在调用方）；
     * 全失败返回 null（链继续 → 概率化）。
     */
    public CrowdDecisionParse.CrowdAction crowdChoiceDrive(Long projectId, ActorCharacter npc,
                                                           Long gameSecond, String environmentText) {
        if (!props.isEnabled() || npc == null || projectId == null) {
            return null;
        }
        return legacyCrowdChoice(projectId, npc, gameSecond, environmentText);
    }

    /**
     * 普通 NPC 危险强制（公开，BrainStrategy PhysioDangerStrategy 委托）：生理四需任一低于
     * 危险阈值 → 程序化强制最紧急危险行动（0 AI，任何触发源先于快通道——与特殊 NPC
     * 危险前置语义对齐；行动文本/恢复关键词与 onNpcAction 对齐）。
     */
    public CrowdDecisionParse.CrowdAction dangerDrive(Long projectId, ActorCharacter npc, Long gameSecond) {
        if (!props.isEnabled() || npc == null || projectId == null
                || props.getPhysio() == null || !props.getPhysio().isEnabled()) {
            return null;
        }
        try {
            ActorNpcState st = ensureState(projectId, NpcRefs.normalizeType(npc.getType()),
                    NpcRefs.of(npc.getId()), gameSecond);
            if (st == null) {
                return null;
            }
            com.holzyn.actor.domain.npc.physio.PhysioNeedService.DangerNeed danger =
                    com.holzyn.actor.domain.npc.physio.PhysioNeedService.dangerNeedOf(st, props.getPhysio());
            if (danger == null) {
                return null;
            }
            Random r = new Random();
            String target = pickTarget(locationNames(projectId), danger.actionType(), r);
            String text = com.holzyn.actor.domain.npc.physio.PhysioNeedService
                    .actionTextOf(danger.need(), target);
            return new CrowdDecisionParse.CrowdAction(text, danger.actionType(), target,
                    "生理需求危险区强制（0 AI）");
        } catch (Exception e) {
            log.warn("[状态驱动] 生理危险强制失败（回退决策链）: ref=npc:{} : {}", npc.getId(), e.getMessage());
            return null;
        }
    }

    /** 既有内联快选择管线（router 未装配[单测]；行为与 P2-3 前一致）：决策模型 优先 → AI 建议回退。 */
    private CrowdDecisionParse.CrowdAction legacyCrowdChoice(Long projectId, ActorCharacter npc,
                                                             Long gameSecond, String environmentText) {
        // 决策模型 优先（crowd_decision 场景）：程序化候选集 + choice sample 直接采纳——
        // 熵最高的平坦分布恰是「真人做琐碎决定的随意感」；失败/未启用回退 Qwen/程序化
        try {
            CrowdDecisionParse.CrowdAction nano = decideCrowdActionDecision(projectId, npc, gameSecond, environmentText);
            if (nano != null) {
                return nano;
            }
        } catch (Exception e) {
            log.warn("[状态驱动] 决策模型 人群决策失败（回退 Qwen/程序化）: ref=npc:{} : {}", npc.getId(), e.getMessage());
        }
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null; // AI 依赖未装配（纯数据单测/无 AI 环境）→ 跳过
        }
        try {
            Long userId = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            String npcRef = NpcRefs.of(npc.getId());
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型取档案行内 type）
            String npcType = NpcRefs.normalizeType(npc.getType());
            ActorNpcState st = ensureState(projectId, npcType, npcRef, gameSecond);
            String characterInfo = characterInfoLine(projectId, npcType, npcRef);
            String currentState = st == null ? "" : renderStateSegment(st, props, props.getInject().getStateBudget());
            String locations = String.join("、", locationNames(projectId));
            // ：当日计划已从 actor_profile 收敛到 actor_state.daily_plan_json，读取改经 NpcActivityStateService
            String planJson = npcActivityStateService == null ? null
                    : npcActivityStateService.dailyPlanJson(projectId, npcRef);
            String dailyPlan = planJson == null ? "" : truncatePlain(planJson, 300);
            // 【世界动态】段（人群 AI 决策注入，双轨全 NPC）：该居民当前可感知的信息包（三层过滤+失真）
            String worldDynamics = worldDynamicsOf(projectId, npcRef, gameSecond);
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_NPC_CROWD_DECISION,
                    Map.of("character_info", characterInfo.isBlank() ? "（无档案信息）" : characterInfo,
                            "current_state", currentState.isBlank() ? "（无状态信息）" : currentState,
                            "location", npc.getLocation() == null || npc.getLocation().isBlank() ? "未知" : npc.getLocation(),
                            "locations", locations.isBlank() ? "（无地点信息）" : locations,
                            "daily_plan", dailyPlan.isBlank() ? "（无安排）" : dailyPlan,
                            "world_dynamics", worldDynamics.isBlank() ? "（无世界动态）" : worldDynamics,
                            "game_time", gameTimeText(gameSecond)));
            // 环境刺激：环境变化段追加（用户提示词尾部，零模板改动）
            final String finalPrompt;
            if (environmentText != null && !environmentText.isBlank()) {
                finalPrompt = prompt + "\n\n【环境变化】" + environmentText.trim()
                        + "\n请结合上述环境变化，做出此刻最合适的行动。";
            } else {
                finalPrompt = prompt;
            }
            // ：npc:{id} 不再编码类型，此处按意图 A 处理（trace/用量记账的角色 id）
            Long charId = NpcRefs.idOf(npcRef);
            AiChatResult result = AiCallContext.run(SCENE_CROWD_DECISION, charId, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    CROWD_DECISION_TEMPERATURE, CROWD_DECISION_MAX_TOKENS, true)));
            usageLogService.record(userId, projectId, charId, result.providerId(), result.model(), SCENE_CROWD_DECISION,
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return CrowdDecisionParse.parse(result.content());
        } catch (Exception e) {
            log.warn("[状态驱动] 人群 AI 决策失败（回退程序化）: ref=crowd:{} : {}", npc.getId(), e.getMessage());
            return null;
        }
    }

    // ==================== 决策模型 人群行动判定（crowd_decision 场景，） ====================

    /**
     * 决策模型 人群行动判定：程序化候选集（需求驱动 + 通用项）→ choice 判定 →
     * sample 直接采纳（<b>无升级阈值</b>——：熵最高 1.96 的平坦分布恰是「真人做
     * 琐碎日常决定时的随意」，judge 自然度 4.38 全场景最高；分布平坦即采纳）。选中候选映射为
     * {@link CrowdDecisionParse.CrowdAction}（目标地点经既有需求-地点匹配）。
     *
     * @return 决策模型 行动决策（未启用/失败/升级=null → 调用方回退 Qwen/程序化）
     */
    private CrowdDecisionParse.CrowdAction decideCrowdActionDecision(Long projectId, ActorCharacter npc,
                                                                    Long gameSecond, String environmentText) {
        if (decisionGate == null || !decisionGate.isEnabled(
                com.holzyn.actor.ai.decision.gate.DecisionScenes.CROWD_DECISION)) {
            return null;
        }
        ActorNpcState st = ensureState(projectId, NpcRefs.normalizeType(npc.getType()),
                NpcRefs.of(npc.getId()), gameSecond);
        if (st == null) {
            return null;
        }
        String currentActivity = null;
        if (npcActivityStateService != null) {
            try {
                NpcActivityStateService.ActivityUpdate au = npcActivityStateService.activityOf(
                        projectId, NpcRefs.of(npc.getId()));
                if (au != null && au.lastAction() != null && !au.lastAction().isBlank()) {
                    currentActivity = au.lastAction();
                }
            } catch (Exception e) {
                log.debug("[状态驱动] 当前行动读取失败（回退 null）: {}", e.getMessage());
            }
        }
        // ：普通 NPC crowd 统一到空间候选——空间 possible_actions + 通用三项 →
        // precondition 过滤 → 决策模型 choice sample 直接采纳；空间无效/无候选/判定失败 →
        // 回退 程序化候选集（零阻断）
        CrowdDecisionParse.CrowdAction fromSpace = decideCrowdActionDecisionFromSpace(
                projectId, npc, gameSecond, environmentText, st, currentActivity);
        if (fromSpace != null) {
            return fromSpace;
        }
        // ① 程序化候选集（需求驱动 + 通用项；≤8 项利于 0.6B 判断）
        Map<String, String> candidates = buildDecisionCrowdCandidates(st, currentActivity, locationNames(projectId));
        if (candidates.size() < 2) {
            return null;
        }
        // ② 情境文本（当前行动/地点/状态摘要/环境变化）
        String stateText = buildDecisionCrowdStateText(npc, st, currentActivity, environmentText);
        Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> questions =
                com.holzyn.actor.ai.decision.DecisionState.newQuestions();
        questions.put(QID_CROWD_ACTION, com.holzyn.actor.ai.decision.gate.DecisionTemplates
                .crowdQuestion(candidates));
        com.holzyn.actor.ai.decision.DecisionState state = com.holzyn.actor.ai.decision.DecisionState.text(
                "crowd" + npc.getId(), stateText, questions);
        // ③ choice 判定（sample 直接采纳）
        com.holzyn.actor.ai.decision.gate.DecisionGate.ChoiceOutcome out =
                decisionGate.choiceDecision(projectId, npc.getId(),
                        com.holzyn.actor.ai.decision.gate.DecisionScenes.CROWD_DECISION,
                        state, QID_CROWD_ACTION);
        if (out == null || !out.adopted() || out.chosen() == null || out.chosen().isBlank()) {
            return null;
        }
        return decisionChosenToAction(projectId, out.chosen(), npc, st, currentActivity);
    }

    /** crowd choice 判定的问题键。 */
    private static final String QID_CROWD_ACTION = "action";

    // ==================== 普通 NPC 空间候选路径 ====================

    /**
     * 普通 NPC 空间候选判定：决策时 ensureSpace → 空间 possible_actions + 通用三项候选集 →
     * precondition 程序化优先 + 语义条件 boolean 批量（1 state 多题一次前向）→ choice sample
     * 直接采纳（无升级阈值，沿 crowd 先例）→ 映射 CrowdAction。
     *
     * @return 空间路径行动决策
     */
    private CrowdDecisionParse.CrowdAction decideCrowdActionDecisionFromSpace(Long projectId, ActorCharacter npc,
                                                                             Long gameSecond, String environmentText,
                                                                             ActorNpcState st, String currentActivity) {
        if (growthSpaceService == null) {
            return null;
        }
        try {
            Long gs = gameSecond;
            if (gs == null && worldClockService != null) {
                gs = worldClockService.currentEngineGameSecond(projectId);
            }
            if (gs == null) {
                return null;
            }
            String npcRef = NpcRefs.of(npc.getId());
            // 决策时 ensureSpace（空间缺失/TTL 过期 → 按 LOD 生成；普通 NPC ambient 档 AI 空间）
            growthSpaceService.ensureSpace(projectId, npcRef, gs);
            com.holzyn.actor.domain.growth.space.GrowthSpace space =
                    growthSpaceService.currentSpace(projectId, npcRef);
            if (space == null || space.getSpaceJson() == null || space.getSpaceJson().isBlank()) {
                return null;
            }
            com.holzyn.actor.domain.growth.space.GrowthSpaceParse.GrowthSpaceDoc doc =
                    com.holzyn.actor.domain.growth.space.GrowthSpaceParse.parse(space.getSpaceJson());
            if (doc == null || doc.possibleActions() == null || doc.possibleActions().isEmpty()) {
                return null;
            }
            com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context pctx =
                    buildSpacePreconditionContext(projectId, npc, gs, st);
            java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> candidates =
                    com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.candidatesFrom(doc, st);
            // 注入候选合并（社交聚集 + 生理低值，一次重组装；普通 NPC 与核心角色同源口径：
            // 生理候选命中 → 抑制通用「歇一歇/找人说说话」轻行动——配比分层）
            // 昼夜口径 = 引擎推进位置
            java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate> extra =
                    new java.util.ArrayList<>();
            boolean suppressLight = false;
            if (socialGatheringService != null) {
                java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate>
                        social = socialGatheringService.candidatesFor(projectId, npc.getLocation(),
                        pctx.presentNames() == null || pctx.presentNames().isEmpty(),
                        engineHourOfDay(projectId));
                if (!social.isEmpty()) {
                    extra.addAll(social);
                }
            }
            if (props.getPhysio() != null && props.getPhysio().isEnabled()) {
                Integer hour = gs == null ? null : (int) Math.floorDiv(gs, 3600L) % 24;
                java.util.List<com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate>
                        physio = com.holzyn.actor.domain.npc.physio.PhysioNeedService.lowCandidates(
                        st, props.getPhysio(), hour, locationNames(projectId), new java.util.Random());
                if (!physio.isEmpty()) {
                    extra.addAll(physio);
                    suppressLight = com.holzyn.actor.domain.npc.physio.PhysioNeedService
                            .hasLowNeed(st, props.getPhysio(), hour);
                }
            }
            if (!extra.isEmpty()) {
                candidates = com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates
                        .candidatesFrom(doc, st, extra, suppressLight);
            }
            if (candidates.size() < 2) {
                return null;
            }
            // 判定编排（precondition 程序化过滤 + 语义条件 boolean 批量 + 行动 choice 一次前向 +
            // 选中复核/重采样）→ 返回选中候选（null=回退程序化候选集）
            com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate chosen =
                    com.holzyn.actor.domain.growth.space.SpaceDecisionServiceUtils.decide(
                            decisionGate, projectId, npc.getId(), candidates, pctx,
                            buildDecisionCrowdStateText(npc, st, currentActivity, environmentText));
            if (chosen == null) {
                return null;
            }
            return decisionSpaceChosenToAction(projectId, chosen, npc, st, currentActivity);
        } catch (Exception e) {
            log.warn("[状态驱动] 决策模型 空间候选判定失败（回退程序化候选集）: ref=npc:{} : {}",
                    npc.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 空间路径 precondition 程序化判定上下文（时刻/状态/在场者/全村/天气/地点）。
     * 在场者名单从角色主表读取（同地点角色名 + 全村在世角色名）；天气经统一环境服务。
     */
    private com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context buildSpacePreconditionContext(
            Long projectId, ActorCharacter npc, Long gameSecond, ActorNpcState st) {
        Integer hourOfDay = null;
        if (worldClockService != null) {
            try {
                Long h = worldClockService.currentGameHour(projectId);
                hourOfDay = h == null ? null : (int) (h % 24L);
            } catch (Exception e) {
                log.debug("[状态驱动] 游戏时刻读取失败（按未知）: {}", e.getMessage());
            }
        }
        java.util.List<String> present = new ArrayList<>();
        java.util.List<String> village = new ArrayList<>();
        try {
            for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                if (com.holzyn.actor.domain.growth.stage.GrowthStage.DECEASED.name()
                        .equalsIgnoreCase(ch.getStage()) || ch.getId().equals(npc.getId())) {
                    continue;
                }
                village.add(ch.getName());
                if (npc.getLocation() != null && npc.getLocation().equals(ch.getLocation())) {
                    present.add(ch.getName());
                }
            }
        } catch (Exception e) {
            log.debug("[状态驱动] 在场者名单读取失败（按未知）: {}", e.getMessage());
        }
        String weather = null;
        if (environmentContextService != null) {
            try {
                com.holzyn.actor.domain.world.vo.EnvironmentContextVO vo =
                        environmentContextService.computeContext(projectId, gameSecond);
                weather = vo == null ? null : vo.getWeather();
            } catch (Exception e) {
                log.debug("[状态驱动] 天气读取失败（按未知）: {}", e.getMessage());
            }
        }
        return new com.holzyn.actor.domain.growth.space.SpacePreconditionEvaluator.Context(
                hourOfDay, st, present, village, weather, npc.getLocation());
    }

    /**
     * 引擎推进位置的时刻。
     * <p>用于 C 层社交聚集的昼夜门（「深夜不串门」）：流动时钟按实时×速率插值，世界暂停/慢速
     * 时会远超模拟位置，用流动时钟会让门控与模拟昼夜脱节；不可读返回 null（按非深夜宽松处理）。</p>
     */
    private Integer engineHourOfDay(Long projectId) {
        if (worldClockService == null) {
            return null;
        }
        try {
            Long h = worldClockService.currentEngineGameHour(projectId);
            return h == null ? null : (int) (h % 24L);
        } catch (Exception e) {
            log.debug("[状态驱动] 引擎时刻读取失败（按未知）: {}", e.getMessage());
            return null;
        }
    }

    /** 空间候选选中键 → 人群行动决策（动作文本/类型/目标地点）。 */
    private CrowdDecisionParse.CrowdAction decisionSpaceChosenToAction(Long projectId,
                                                                      com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates.SpaceCandidate chosen,
                                                                      ActorCharacter npc, ActorNpcState st,
                                                                      String currentActivity) {
        if (chosen.generic()) {
            return decisionChosenToAction(projectId, chosen.key(), npc, st, currentActivity);
        }
        String action = chosen.action() == null || chosen.action().isBlank()
                ? "忙于日常事务" : chosen.action().trim();
        String target = chosen.target() == null ? null : chosen.target().trim();
        String type = crowdTypeOfText(action);
        return new CrowdDecisionParse.CrowdAction(action, type, target,
                "决策模型 判定（space_decision）");
    }

    /** 空间行动文本 → 人群动作类型（需求恢复语义关键词；默认 work）。 */
    private static String crowdTypeOfText(String text) {
        if (text == null) {
            return "work";
        }
        String t = text;
        if (containsAny(t, "歇", "休息", "憩", "养神", "打盹")) {
            return "rest";
        }
        if (containsAny(t, "聊", "说话", "谈", "唠", "攀谈", "寒暄")) {
            return "chat";
        }
        if (containsAny(t, "吃", "食", "餐", "炊")) {
            return "eat";
        }
        if (containsAny(t, "喝", "水", "饮", "茶")) {
            return "drink";
        }
        if (containsAny(t, "洗", "浴", "濯")) {
            return "wash";
        }
        if (containsAny(t, "玩", "乐", "逛", "看热闹", "散心")) {
            return "play";
        }
        return "work";
    }

    /**
     * 程序化候选集（纯静态可单测）：通用三项（继续当前/歇一歇/找人说说话）+ 需求驱动项
     * （饥饿/口渴/疲惫/闷/无聊/不自在，需求值低于阈值才进入）——「继续当前行动」是常见倾向，
     * 需求项是「此刻最可能想做的事」，低权重项（歇/聊）永远在场=真人式偶尔放空。上限 8 项。
     *
     * @param st 状态行（只读取值；null=仅通用项）
     *
     * @param currentActivity 当前行动文本（continue_current 的描述背景；可空）
     *
     * @param locations 世界观地点名列表（可空；不影响候选集）
     *
     * @return 候选表（键 → 自包含描述）
     */
    public static Map<String, String> buildDecisionCrowdCandidates(ActorNpcState st, String currentActivity,
                                                                  List<String> locations) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("continue_current", "继续当前正在做的事");
        boolean tired = st != null && st.getEnergy() != null && st.getEnergy() < 80;
        c.put("rest", tired ? "停下歇一歇（有些疲惫）" : "停下歇一歇，放空一会儿");
        c.put("chat", "找个人说说话");
        if (st != null) {
            if (below(st.getHunger(), 70)) {
                c.put("eat", "去吃点东西（有些饿了）");
            }
            if (below(st.getThirst(), 70)) {
                c.put("drink", "去喝口水（有些口渴）");
            }
            if (below(st.getSocial(), 60)) {
                c.put("social", "找人聊聊天（有点闷）");
            }
            if (below(st.getFun(), 60)) {
                c.put("play", "寻点乐子（有点无聊）");
            }
            if (below(st.getHygiene(), 90)) {
                c.put("wash", "去洗漱一下（身上不自在）");
            }
        }
        // 上限 8（尾部超出移除；通用三项恒保留）
        while (c.size() > 8) {
            String last = c.keySet().stream().reduce((a, b) -> b).orElse(null);
            if (last == null || "continue_current".equals(last) || "rest".equals(last) || "chat".equals(last)) {
                break;
            }
            c.remove(last);
        }
        return c;
    }

    /** 需求值是否低于阈值（null 视为不低）。 */
    private static boolean below(Integer value, int threshold) {
        return value != null && value < threshold;
    }

    /** crowd 情境文本（当前行动/地点/状态摘要/环境变化；判据自包含）。 */
    private String buildDecisionCrowdStateText(ActorCharacter npc, ActorNpcState st,
                                              String currentActivity, String environmentText) {
        StringBuilder sb = new StringBuilder();
        sb.append(npc.getName() == null || npc.getName().isBlank() ? "该角色" : npc.getName());
        sb.append("此刻").append(currentActivity == null || currentActivity.isBlank()
                ? "正在忙自己的事" : currentActivity.trim());
        if (npc.getLocation() != null && !npc.getLocation().isBlank()) {
            sb.append("，在").append(npc.getLocation().trim());
        }
        if (st != null) {
            List<String> flags = new ArrayList<>();
            if (below(st.getHunger(), 40)) {
                flags.add("饿了");
            }
            if (below(st.getEnergy(), 40)) {
                flags.add("疲惫");
            }
            if (below(st.getSocial(), 40)) {
                flags.add("有点闷");
            }
            if (!flags.isEmpty()) {
                sb.append("，").append(String.join("、", flags));
            }
        }
        if (environmentText != null && !environmentText.isBlank()) {
            sb.append("。环境变化：").append(environmentText.trim());
        }
        sb.append("。");
        return sb.toString();
    }

    /** 选中候选键 → 行动决策（动作文本/类型/目标地点；与既有 buildAction 同口径）。 */
    private CrowdDecisionParse.CrowdAction decisionChosenToAction(Long projectId, String chosen,
                                                                 ActorCharacter npc, ActorNpcState st,
                                                                 String currentActivity) {
        List<String> locations = locationNames(projectId);
        Random rng = new Random();
        String reason = "决策模型 判定（crowd_decision）";
        switch (chosen) {
            case "continue_current" -> {
                String text = currentActivity == null || currentActivity.isBlank()
                        ? "忙于日常事务" : currentActivity.trim();
                return new CrowdDecisionParse.CrowdAction(text, "", null, reason);
            }
            case "rest" -> {
                String target = pickTarget(locations, "rest", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "就地歇息片刻" : "前往" + target + "歇息",
                        "rest", target, reason);
            }
            case "chat" -> {
                String target = pickTarget(locations, "chat", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "找人说说话" : "前往" + target + "与人攀谈",
                        "chat", target, reason);
            }
            case "eat" -> {
                String target = pickTarget(locations, "eat", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "觅食充饥" : "前往" + target + "用餐",
                        "eat", target, reason);
            }
            case "drink" -> {
                String target = pickTarget(locations, "drink", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "寻水饮用" : "前往" + target + "饮水",
                        "drink", target, reason);
            }
            case "social" -> {
                String target = pickTarget(locations, "chat", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "找人攀谈" : "前往" + target + "与人攀谈",
                        "chat", target, reason);
            }
            case "play" -> {
                String target = pickTarget(locations, "play", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "寻些乐子" : "前往" + target + "消遣",
                        "play", target, reason);
            }
            case "wash" -> {
                String target = pickTarget(locations, "wash", rng);
                return new CrowdDecisionParse.CrowdAction(target == null ? "寻水洗漱" : "前往" + target + "洗漱",
                        "wash", target, reason);
            }
            default -> {
                return null; // 未知候选键：回退 Qwen/程序化（零阻断）
            }
        }
    }

    /**
     * 应用人群行动决策（AI 或概率化共用，实例）：更新居民状态/动作/位置 + 经
     * {@link #onNpcAction} 恢复需求/情绪（含衰减补偿 + AI 情绪评估异步）+ 沙盘同步由
     * 调度后的 syncCrowdStates 完成（位置随 location 派生）。
     *
     * @param projectId 项目 ID
     *
     * @param npc 普通 NPC 实体（原地更新 state/lastAction/location）
     *
     * @param action AI 行动决策（action/actionType/target/reason）
     *
     * @param gameSecond 游戏时刻（秒，可空）
     */
    @Transactional
    public void applyCrowdAction(Long projectId, ActorCharacter npc,
                                 CrowdDecisionParse.CrowdAction action, Long gameSecond) {
        if (npc == null || action == null || projectId == null) {
            return;
        }
        String actionText = action.action() == null || action.action().isBlank()
                ? "忙于日常事务" : action.action().trim();
        String actionType = action.actionType() == null ? "" : action.actionType();
        String state = actionState(actionType, actionText);
        // ：作息运行态（state/last_action）写回 actor_state（actor_profile 不再承载运行态）
        if (npcActivityStateService != null) {
            npcActivityStateService.setActivity(projectId, NpcRefs.of(npc.getId()),
                    state, truncatePlain(actionText, 255));
        }
        // 移动至目标地点（真实执行档；目标地点经 onNpcAction 恢复对应需求）
        if (action.target() != null && !action.target().isBlank()) {
            String target = truncatePlain(action.target().trim(), 100);
            npc.setLocation(target);
            // 治理批次 0 · 卡1：locationId 与 location 归一一致（特殊 NPC move 行动同口径，
            // 修「人群移动不写 locationId → 在场过滤用陈旧值」；归一服务缺失/未命中=null 自由文本兜底）
            Long targetLocationId = null;
            if (locationNormalizationService != null) {
                try {
                    targetLocationId = locationNormalizationService.normalizedId(projectId, target);
                    npc.setLocationId(targetLocationId);
                } catch (Exception e) {
                    log.warn("[状态驱动] 人群移动位置归一失败（保留自由文本）: ref={} : {}", npc.getId(), e.getMessage());
                }
            }
            // 位置移动与地图生成系统 · M2：人群基础版——装配 MovementService 时发起真实移动
            // （walking→arrived 插值推进，坐标权威由 MovementService.tick 驱动；零 AI）。
            // 未装配[单测]回退既有瞬移语义（setLocation + syncCrowdStates 派生坐标），零破坏。
            if (movementService != null) {
                try {
                    movementService.startMove(projectId, NpcRefs.of(npc.getId()), NpcRefs.normalizeType(npc.getType()),
                            com.holzyn.actor.domain.worldmap.entity.ActorMovement.REASON_PLAN,
                            targetLocationId, target, gameSecond);
                } catch (Exception e) {
                    log.warn("[状态驱动] 人群真实移动发起失败（回退瞬移）: ref={} : {}", npc.getId(), e.getMessage());
                }
            }
        }
        // 需求恢复 + 程序化情绪规则 + AI 情绪评估（异步）——与特殊 NPC 行动挂钩同语义
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（类型取档案行内 type）
        onNpcAction(projectId, NpcRefs.of(npc.getId()), NpcRefs.normalizeType(npc.getType()),
                actionText, actionType, gameSecond);
    }

    /**
     * 人群视角【世界动态】段（只读注入）：委托 {@link InfoPerceptionService#worldDynamicsSegment}
     * 渲染该居民当前可感知的信息包（三层过滤 + 失真文本）；服务未装配/异常/无可注入内容返回空串
     * （静默降级，不阻断人群 AI 决策主流程）。
     */
    private String worldDynamicsOf(Long projectId, String npcRef, Long gameSecond) {
        if (infoPerceptionService == null || projectId == null || npcRef == null) {
            return "";
        }
        try {
            return infoPerceptionService.worldDynamicsSegment(projectId, npcRef, gameSecond);
        } catch (Exception e) {
            log.warn("[状态驱动] 【世界动态】段注入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
            return "";
        }
    }

    /**
     * 人群概率化驱动（§8.2 大规模档，实例）：取状态行 + 世界观地点候选 → 纯静态
     * {@link #probabilisticAction} 计算需求满足动作；无候选/状态不可用返回 null（调用方回退程序化）。
     *
     * @param projectId 项目 ID
     *
     * @param npc 普通 NPC 实体
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @param rng 随机源（调用方传入保证可测）
     *
     * @return 概率化行动决策（null=本轮无需求满足动作）
     */
    public CrowdDecisionParse.CrowdAction probabilisticDrive(Long projectId, ActorCharacter npc,
                                                             Long gameSecond, Random rng) {
        if (!props.isEnabled() || npc == null || projectId == null) {
            return null;
        }
        ActorNpcState st = ensureState(projectId, NpcRefs.normalizeType(npc.getType()),
                NpcRefs.of(npc.getId()), gameSecond);
        if (st == null) {
            return null;
        }
        // 三餐窗口门控（生理需求耦合轮）：进食候选仅窗口内注入——hour 由引擎推进位置换算
        Integer hour = gameSecond == null ? null : (int) Math.floorDiv(gameSecond, 3600L) % 24;
        DriveAction act = probabilisticAction(st, locationNames(projectId), props, rng, hour);
        if (act == null) {
            return null;
        }
        return new CrowdDecisionParse.CrowdAction(act.actionText(), act.actionType(), act.target(), "概率化需求满足");
    }

    /**
     * 概率化需求满足 + 需求-地点/对象评分（§8.2 大规模档，纯静态可单测；Sims 概率模型）。
     * <p>规则：① 需求值 &lt; probabilistic-threshold 的需求进入候选，处理概率 p=(100-value)/100
     * （100→0%、50→50%、10→90%，钳制 [0.05,0.95]）；存在匹配该需求的【对象地点】时 p 提升
     * （×1.3 上限 0.95，Sims 对象评分：饿了→餐厅得分极高）；② urgency≥2 时强制处理最紧急需求
     * （打断 daily_plan）；③ 候选按 p 加权随机选一；④ 按需求映射动作文本/类型 + 目标地点
     * （地点表关键词匹配，无匹配地点则无 target）；⑤ 无任何需求低于阈值 → 返回 null（程序化兜底）。</p>
     *
     * @param st 状态行（只读取值；crowd 仅 hunger/energy 轻量列参与）
     *
     * @param locations 世界观地点名列表（可为空=无对象评分）
     *
     * @param props 状态模块配置（drive 阈值）
     *
     * @param rng 随机源（可测；null 内部新建）
     *
     * @return 概率化动作（null=无需求满足动作）
     */
    public static DriveAction probabilisticAction(ActorNpcState st, List<String> locations,
                                                  NpcStateProperties props, Random rng) {
        return probabilisticAction(st, locations, props, rng, null);
    }

    /**
     * 概率化需求满足 + 需求-地点/对象评分（§8.2 大规模档，纯静态可单测；Sims 概率模型）。
     * <p>规则：① 需求值 &lt; probabilistic-threshold 的需求进入候选，处理概率 p=(100-value)/100
     * （100→0%、50→50%、10→90%，钳制 [0.05,0.95]）；存在匹配该需求的【对象地点】时 p 提升
     * （×1.3 上限 0.95，Sims 对象评分：饿了→餐厅得分极高）；② urgency≥2 时强制处理最紧急需求
     * （打断 daily_plan）；③ 候选按 p 加权随机选一；④ 按需求映射动作文本/类型 + 目标地点
     * （地点表关键词匹配，无匹配地点则无 target）；⑤ 无任何需求低于阈值 → 返回 null（程序化兜底）。</p>
     * <p><b>生理需求耦合（5 参重载，2026-10-02）</b>：⑥ 生理四需任一下降过<b>危险阈值</b> →
     * 强制最紧急危险需求（0 概率化，优先于一切——快饿死的人不受三餐时间约束）；
     * ⑦ <b>三餐窗口</b>仅约束进食候选（窗口外低值饥饿不进入候选，非窗口时段真人不会正经吃饭）。</p>
     *
     * @param st 状态行（只读取值；crowd 仅 hunger/energy 轻量列参与）
     *
     * @param locations 世界观地点名列表（可为空=无对象评分）
     *
     * @param props 状态模块配置（drive 阈值 + physio 阈值）
     *
     * @param rng 随机源（可测；null 内部新建）
     *
     * @param hourOfDay 当天小时（0-23；null=时间未知进食不受窗口门控）
     *
     * @return 概率化动作（null=无需求满足动作）
     */
    public static DriveAction probabilisticAction(ActorNpcState st, List<String> locations,
                                                  NpcStateProperties props, Random rng, Integer hourOfDay) {
        if (st == null || props == null) {
            return null;
        }
        Random r = rng != null ? rng : new Random();
        double threshold = props.getDrive().getProbabilisticThreshold();
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（行内 npc_type 为准）
        boolean crowd = NpcRefs.isCommonType(st.getNpcType());
        // 健康告急（§6.3 urgency=3，special）：强制就医倾向（打断 daily_plan），优先于需求候选
        if (!crowd && st.getHealth() != null && st.getHealth() < props.getUrgency().getHealthAlert()) {
            return new DriveAction("前往医馆诊治", "treat", pickTarget(locations, "treat", r), "stop");
        }
        // 生理需求危险区（生理需求耦合轮）：生理四需任一低于危险阈值 → 强制最紧急危险需求
        // （0 概率化，无论时段/三餐窗口）；对齐「健康告急强制」先例，普通/特殊同源
        com.holzyn.actor.domain.npc.physio.PhysioNeedService.DangerNeed danger =
                com.holzyn.actor.domain.npc.physio.PhysioNeedService.dangerNeedOf(st, props.getPhysio());
        if (danger != null) {
            String target = pickTarget(locations, danger.actionType(), r);
            String text = com.holzyn.actor.domain.npc.physio.PhysioNeedService
                    .actionTextOf(danger.need(), target);
            return new DriveAction(text, danger.actionType(), target, "stop");
        }
        // 候选需求（人群轻量：仅 hunger/energy；特殊全列）
        List<NeedCandidate> candidates = new ArrayList<>();
        if (!crowd) {
            addCandidate(candidates, "口渴", val(st.getThirst(), 70), threshold, locations, "drink");
            addCandidate(candidates, "膀胱", val(st.getBladder(), 50), threshold, locations, "toilet");
            addCandidate(candidates, "清洁", val(st.getHygiene(), 90), threshold, locations, "wash");
            addCandidate(candidates, "社交", val(st.getSocial(), 60), threshold, locations, "chat");
            addCandidate(candidates, "趣味", val(st.getFun(), 60), threshold, locations, "play");
        }
        addCandidate(candidates, "精力", val(st.getEnergy(), 80), threshold, locations, "rest");
        // 三餐窗口（生理需求耦合轮）：进食候选仅窗口内注入（hourOfDay=null 时间未知宽松放行）
        if (com.holzyn.actor.domain.npc.physio.PhysioNeedService.isMealHour(hourOfDay, props.getPhysio())) {
            addCandidate(candidates, "饥饿", val(st.getHunger(), 70), threshold, locations, "eat");
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // urgency≥2 → 强制最紧急需求（打断 daily_plan）
        if (st.getUrgencyLevel() != null && st.getUrgencyLevel() >= 2) {
            return buildAction(st, mostUrgent(candidates), locations, r);
        }
        // 按 p 加权随机
        double total = 0;
        for (NeedCandidate c : candidates) {
            total += c.probability();
        }
        double pick = r.nextDouble() * total;
        double acc = 0;
        for (NeedCandidate c : candidates) {
            acc += c.probability();
            if (pick <= acc) {
                return buildAction(st, c, locations, r);
            }
        }
        return buildAction(st, candidates.get(candidates.size() - 1), locations, r);
    }

    /** 需求候选。 */
    private record NeedCandidate(String need, int value, double probability) {
    }

    /** 构造候选：p=(100-value)/100 钳制 [0.05,0.95]；对象地点匹配 → ×1.3 上限 0.95。 */
    private static void addCandidate(List<NeedCandidate> out, String need, int value, double threshold,
                                     List<String> locations, String actionType) {
        if (value >= threshold) {
            return;
        }
        double p = Math.max(0.05, Math.min(0.95, (100.0 - value) / 100.0));
        if (hasMatchingLocation(locations, actionType)) {
            p = Math.min(0.95, p * 1.3);
        }
        out.add(new NeedCandidate(need, value, p));
    }

    /** 最紧急候选（值最低者；空列表回退首个）。 */
    private static NeedCandidate mostUrgent(List<NeedCandidate> candidates) {
        NeedCandidate best = null;
        for (NeedCandidate c : candidates) {
            if (best == null || c.value() < best.value()) {
                best = c;
            }
        }
        return best == null ? candidates.get(0) : best;
    }

    /** 需求 → 行动构建（动作文本/类型/目标地点/state）。 */
    private static DriveAction buildAction(ActorNpcState st, NeedCandidate c, List<String> locations, Random r) {
        String type = actionTypeOf(c.need());
        String target = pickTarget(locations, type, r);
        String actionText;
        String state;
        switch (c.need()) {
            case "饥饿" -> { actionText = target == null ? "觅食充饥" : "前往" + target + "用餐"; state = "stop"; }
            case "口渴" -> { actionText = target == null ? "寻水饮用" : "前往" + target + "饮水"; state = "stop"; }
            case "膀胱" -> { actionText = target == null ? "寻找如厕之处" : "前往" + target + "如厕"; state = "stop"; }
            case "清洁" -> { actionText = target == null ? "寻水洗漱" : "前往" + target + "洗漱"; state = "stop"; }
            case "社交" -> { actionText = target == null ? "找人攀谈" : "前往" + target + "与人攀谈"; state = "talk"; }
            case "趣味" -> { actionText = target == null ? "寻些乐子" : "前往" + target + "消遣"; state = "talk"; }
            case "精力" -> { actionText = target == null ? "就地歇息" : "前往" + target + "歇息"; state = "rest"; }
            default -> { actionText = "忙于日常事务"; state = "stop"; }
        }
        return new DriveAction(actionText, type, target, state);
    }

    /** 需求 → actionType（onNpcAction 恢复关键词对齐：eat/drink/toilet/wash/chat/play/rest）。 */
    private static String actionTypeOf(String need) {
        return switch (need) {
            case "饥饿" -> "eat";
            case "口渴" -> "drink";
            case "膀胱" -> "toilet";
            case "清洁" -> "wash";
            case "社交" -> "chat";
            case "趣味" -> "play";
            case "精力" -> "rest";
            default -> "custom";
        };
    }

    /** 人群动作 → 状态机 state（walk/stop/talk/rest；动作文本含休息/交谈关键词优先）。 */
    private static String actionState(String actionType, String actionText) {
        if (actionText != null) {
            String t = actionText.toLowerCase();
            // ：休息类关键词与恢复映射对齐扩充（调息/养神/歇脚/打盹/憩）
            if (containsAny(t, "休息", "歇息", "睡觉", "就寝", "卧床", "调息", "养神", "歇脚", "打盹", "憩")) {
                return "rest";
            }
            // ：交谈类关键词与恢复映射对齐扩充（闲聊/叙话/搭话/唠）
            if (containsAny(t, "交谈", "聊天", "攀谈", "社交", "闲聊", "叙话", "搭话", "唠")) {
                return "talk";
            }
        }
        if (actionType != null) {
            return switch (actionType) {
                case "rest" -> "rest";
                case "chat", "play" -> "talk";
                default -> "stop";
            };
        }
        return "stop";
    }

    /** 概率化行动决策结果。 */
    public record DriveAction(String actionText, String actionType, String target, String state) {
    }

    /**
     * 需求-地点/对象评分匹配（纯静态）：存在匹配该行动类型关键词的地点 → true（提升处理概率）。
     *
     * @param locations 世界观地点名列表（可为空）
     *
     * @param actionType 行动类型（eat/drink/toilet/wash/rest/chat/play）
     *
     * @return true=存在匹配对象地点
     */
    public static boolean hasMatchingLocation(List<String> locations, String actionType) {
        if (locations == null || actionType == null) {
            return false;
        }
        for (String loc : locations) {
            if (loc != null && locationMatches(loc, actionType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目标地点选取（纯静态）：从地点列表随机取匹配行动类型关键词的地点；无匹配返回 null。
     *
     * @param locations 世界观地点名列表（可为空）
     *
     * @param actionType 行动类型
     *
     * @param rng 随机源（可测；null 内部新建）
     *
     * @return 匹配地点名（null=无）
     */
    public static String pickTarget(List<String> locations, String actionType, Random rng) {
        if (locations == null || actionType == null) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (String loc : locations) {
            if (loc != null && locationMatches(loc, actionType)) {
                matches.add(loc);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        Random r = rng != null ? rng : new Random();
        return matches.get(r.nextInt(matches.size()));
    }

    /** 地点名 ↔ 行动类型关键词匹配。 */
    private static boolean locationMatches(String location, String actionType) {
        String key = location.toLowerCase();
        return switch (actionType) {
            case "eat" -> containsAny(key, "酒馆", "饭馆", "餐厅", "食肆", "食堂", "茶馆", "饭店", "食铺", "馆子", "面馆", "小吃");
            case "drink" -> containsAny(key, "井", "泉", "溪", "河", "水", "酒馆", "茶馆");
            case "toilet" -> containsAny(key, "茅房", "恭房", "厕");
            case "wash" -> containsAny(key, "澡堂", "浴", "井", "河", "溪");
            case "rest" -> containsAny(key, "床", "卧房", "客栈", "卧室", "客房", "居所", "宿");
            case "chat" -> containsAny(key, "市集", "广场", "茶馆", "酒馆", "街道", "集市", "茶楼", "市场");
            case "play" -> containsAny(key, "市集", "广场", "戏台", "茶馆", "酒馆", "市场", "集市", "庙会");
            case "treat" -> containsAny(key, "医馆", "药铺", "医庐", "郎中", "大夫");
            default -> false;
        };
    }

    // ==================== npc:state 内部状态广播（§10：需求跨阈值/情绪切换/urgency 变化 → npc:state） ====================

    /**
     * 状态落库后广播（实例）：计算显著变化签名（需求跨阈值/心情/主导情绪/urgency），
     * 与最近广播签名去重后经 {@link WorldSandboxSseHub} 推 npc:state（含内部状态全字段）。
     * <p>去重防止广播风暴（对齐沙盘状态广播去重哲学）；沙盘 Hub 未装配/无订阅者零成本跳过。
     * 各状态更新入口（advance/onNpcAction/onCrowdSchedule/AI 评估/回退）落库后调用。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param st 状态行（最新）
     */
    public void publishStateAfterChange(Long projectId, String npcRef, ActorNpcState st) {
        if (projectId == null || npcRef == null || st == null || sandboxHub == null) {
            return;
        }
        try {
            String sig = stateSignature(st);
            String key = projectId + ":" + npcRef;
            String last = lastStateSignature.get(key);
            if (sig.equals(last)) {
                return; // 无显著变化（防广播风暴）
            }
            if (lastStateSignature.size() >= SIGNATURE_CACHE_LIMIT) {
                lastStateSignature.clear(); // 防内存膨胀（低频清理，偶发重播可接受）
            }
            lastStateSignature.put(key, sig);
            sandboxHub.publishNpcState(projectId, npcRef, null, null, null, st.getGameSecond(), st);
        } catch (Exception e) {
            log.debug("[状态] npc:state 广播失败（静默）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * 状态显著变化签名（纯静态可单测）：需求取整到 10（跨阈值才变）+ mood 取整到 5 +
     * 主导情绪 + urgency 分级——覆盖「需求跨阈值/情绪切换/urgency 变化」三类广播触发。
     */
    static String stateSignature(ActorNpcState st) {
        if (st == null) {
            return "";
        }
        return (st.getHunger() == null ? "" : st.getHunger() / 10)
                + "|" + (st.getThirst() == null ? "" : st.getThirst() / 10)
                + "|" + (st.getEnergy() == null ? "" : st.getEnergy() / 10)
                + "|" + (st.getBladder() == null ? "" : st.getBladder() / 10)
                + "|" + (st.getHealth() == null ? "" : st.getHealth() / 10)
                + "|" + (st.getMood() == null ? "" : st.getMood() / 5)
                + "|" + (st.getDominantEmotion() == null ? "" : st.getDominantEmotion())
                + "|" + (st.getUrgencyLevel() == null ? "" : st.getUrgencyLevel());
    }

    // ==================== 私有工具 ====================

    /**
     * 健康联动（§6.4）：任一生理四需低于侵蚀阈值 → health 侵蚀；全部高于回升阈值 → 缓慢回升。
     * <p>健康侵蚀/回升同样经 {@link #applyBalancedDelta} 双精度余额结转（health 键）——
     * 0.5/时的侵蚀在 5 分钟 tick（0.042 点）下原 round 也会停滞（24h 压测 health 恒 100 原因之一）。</p>
     */
    private static void applyHealthCorrosion(ActorNpcState st, double hours, NpcStateProperties props) {
        applyHealthCorrosion(st, hours, props, 1.0);
    }

    /**
     * 健康联动。
     *
     * @param st 状态行（原地更新）
     *
     * @param hours 时长（游戏小时）
     *
     * @param props 配置
     *
     * @param painFactor 耐痛系数（&lt;1 = 耐痛高，健康侵蚀更慢；&gt;1 = 更脆弱）
     */
    private static void applyHealthCorrosion(ActorNpcState st, double hours, NpcStateProperties props,
                                             double painFactor) {
        if (hours <= 0) {
            return;
        }
        double threshold = props.getHealth().getCorrosionThreshold();
        double recover = props.getHealth().getRecoverThreshold();
        int hunger = val(st.getHunger(), 70);
        int thirst = val(st.getThirst(), 70);
        int energy = val(st.getEnergy(), 80);
        int bladder = val(st.getBladder(), 50);
        int h = val(st.getHealth(), 100);
        boolean low = hunger < threshold || thirst < threshold || energy < threshold || bladder < threshold;
        boolean ok = hunger >= recover && thirst >= recover && energy >= recover && bladder >= recover;
        // ：先取精确值（展示列 + 余额）再经余额结转（直接把展示值当精确值会导致余额每 tick 重置，
        // 侵蚀永不累积——本行 bug 由 health 小步进单测锁定）
        double total = currentPrecise(st, "health", h, 100);
        if (low) {
            h = applyBalancedDelta(st, "health", total,
                    -props.getHealth().getCorrosionRate() * hours * painFactor, 100);
        } else if (ok) {
            h = applyBalancedDelta(st, "health", total, props.getHealth().getRecoverRate() * hours, 100);
        }
        st.setHealth(h);
    }

    // ==================== 双精度余额结转（状态循环修复核心算法） ====================

    /**
     * 衰减计算（带小数余额结转；taper=true 且值低于放缓阈值时按 50% 衰减；封顶 0-100）。
     * <p>公式：total = 整数列 + decay_frac 余额 → total -= rate×hours（taper 半速）→
     * 展示 = round(total)（四舍五入，兼容既有数值口径），余数 = total - 展示 继续写入
     * decay_frac 结转——引擎稳态小步进下微量衰减持续累积，不再被取整吞掉。</p>
     *
     * @param st 状态行（原地更新 decay_frac）
     *
     * @param key 需求键（hunger/thirst/energy/bladder/hygiene/social/fun）
     *
     * @param v 当前整数列值（可空=回退 fallback）
     *
     * @param rate 衰减率 /游戏小时
     *
     * @param hours 衰减时长（游戏小时）
     *
     * @param d 衰减参数块（taper 判定）
     *
     * @param fallback 缺省值
     *
     * @return 展示用整数（0-100）
     */
    private static int balancedDecay(ActorNpcState st, String key, Integer v, double rate, double hours,
                                     NpcStateProperties.Decay d, int fallback) {
        double drop = rate * hours;
        double total = currentPrecise(st, key, v, fallback);
        if (d.isTaper() && total < d.getTaperThreshold()) {
            drop *= 0.5;
        }
        return applyBalancedDelta(st, key, total, -drop, fallback);
    }

    /**
     * 恢复计算（带小数余额结转；封顶 0-100）。
     *
     * @param st 状态行（原地更新 decay_frac）
     *
     * @param key 需求键（energy 夜间恢复等）
     *
     * @param v 当前整数列值（可空=回退 fallback）
     *
     * @param rate 恢复率 /游戏小时
     *
     * @param hours 恢复时长（游戏小时）
     *
     * @param fallback 缺省值
     *
     * @return 展示用整数（0-100）
     */
    private static int balancedRestore(ActorNpcState st, String key, Integer v, double rate, double hours, int fallback) {
        double total = currentPrecise(st, key, v, fallback);
        return applyBalancedDelta(st, key, total, rate * hours, fallback);
    }

    /**
     * 余额结转核心：精确值 total + delta → 钳制 0-100 → 展示四舍五入 → 余数写回 decay_frac。
     * <p>触顶（100）/触底（0）时余额清零（防 100+ 残差漂移）；余数双精度（-0.5~0.5 量级），
     * 序列化为 JSON 串持久化（重启不丢）；无余额（±1e-9 内）删除键保持串精简。</p>
     *
     * @param st 状态行（原地更新 decay_frac）
     *
     * @param key 需求键
     *
     * @param total 当前精确值（整数列 + 已有余额）
     *
     * @param delta 增量（负=衰减，正=恢复）
     *
     * @param fallback 缺省值（仅钳制语义用）
     *
     * @return 展示用整数（0-100）
     */
    private static int applyBalancedDelta(ActorNpcState st, String key, double total, double delta, int fallback) {
        double next = total + delta;
        double clamped = Math.max(MIN_VALUE, Math.min(MAX_VALUE, next));
        int shown = (int) Math.round(clamped);
        Map<String, Double> frac = parseFrac(st.getDecayFrac());
        double remainder = clamped - shown;
        if (Math.abs(remainder) < 1e-9 || clamped <= MIN_VALUE || clamped >= MAX_VALUE) {
            frac.remove(key);
        } else {
            frac.put(key, Math.round(remainder * 1e6) / 1e6);
        }
        st.setDecayFrac(serializeFrac(frac));
        return shown;
    }

    /**
     * 当前精确值。
     *
     * @param st 状态行
     *
     * @param key 需求键
     *
     * @param v 当前整数列值（可空=回退 fallback）
     *
     * @param fallback 缺省值
     *
     * @return 精确双精度值
     */
    private static double currentPrecise(ActorNpcState st, String key, Integer v, int fallback) {
        double frac = parseFrac(st.getDecayFrac()).getOrDefault(key, 0.0);
        return val(v, fallback) + frac;
    }

    /**
     * 解析 decay_frac JSON 串（null/空/非法 → 空 Map）。
     */
    private static Map<String, Double> parseFrac(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            tools.jackson.databind.JsonNode node = FRAC_MAPPER.readTree(json);
            Map<String, Double> map = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                if (e.getValue().isNumber()) {
                    map.put(e.getKey(), e.getValue().asDouble());
                }
            });
            return map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 序列化 decay_frac 为 JSON 串（空 Map → 空串）。
     */
    private static String serializeFrac(Map<String, Double> frac) {
        if (frac == null || frac.isEmpty()) {
            return "";
        }
        try {
            return FRAC_MAPPER.writeValueAsString(frac);
        } catch (Exception e) {
            return "";
        }
    }

    /** decay_frac 序列化用 ObjectMapper（静态只读，线程安全） */
    private static final tools.jackson.databind.ObjectMapper FRAC_MAPPER =
            new tools.jackson.databind.ObjectMapper();

    /**
     * 生理四需中最紧急者：返回 {名称, 紧急名, 数值字符串}；无（全 null）返回 null。
     * 优先级按「值最低 = 最紧急」，膀胱/饥饿/口渴/精力并列比较。
     */
    private static String[] mostUrgentPhysiology(ActorNpcState st) {
        int hunger = val(st.getHunger(), 70);
        int thirst = val(st.getThirst(), 70);
        int bladder = val(st.getBladder(), 50);
        int energy = val(st.getEnergy(), 80);
        int min = Math.min(Math.min(hunger, thirst), Math.min(bladder, energy));
        if (min >= 30) {
            return null; // 无低于轻度阈值者
        }
        if (min == hunger) {
            return new String[]{"hunger", "hunger", String.valueOf(hunger)};
        }
        if (min == thirst) {
            return new String[]{"thirst", "thirst", String.valueOf(thirst)};
        }
        if (min == bladder) {
            return new String[]{"bladder", "bladder", String.valueOf(bladder)};
        }
        return new String[]{"energy", "energy", String.valueOf(energy)};
    }

    /**
     * urgency 文本（最紧急需求描述）。
     */
    private static String urgentTextFor(String need, int level) {
        if (level >= 2) {
            return switch (need) {
                case "hunger" -> "非常饥饿，急需进食";
                case "thirst" -> "非常口渴，急需饮水";
                case "bladder" -> "尿意很强，急需如厕";
                case "energy" -> "非常疲惫，急需休息";
                default -> "身体不适，急需休息";
            };
        }
        return switch (need) {
            case "hunger" -> "有些饥饿";
            case "thirst" -> "有些口渴";
            case "bladder" -> "有些尿急";
            case "energy" -> "有些疲惫";
            default -> "";
        };
    }

    /**
     * 关键词命中（大小写不敏感）。
     */
    private static boolean containsAny(String key, String... keywords) {
        for (String kw : keywords) {
            if (key.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 值取值（null 回退默认）。
     */
    private static int val(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    /**
     * 钳制 0-100。
     */
    static int clamp100(int v) {
        return Math.max(MIN_VALUE, Math.min(MAX_VALUE, v));
    }

    /**
     * 夜间睡眠时段判定（§6.1：当日小时 ∈ [sleepStartHour, 24) ∪ [0, sleepEndHour)）。
     */
    private static boolean isNightSecond(long gameSecond, NpcStateProperties props) {
        int hourOfDay = (int) Math.floorMod(gameSecond / 3600L, 24);
        return hourOfDay >= props.getSleepStartHour() || hourOfDay < props.getSleepEndHour();
    }

    /**
     * Double 值取值（null 回退默认）。
     */
    private static double valD(Double v, double fallback) {
        return v == null ? fallback : v;
    }

    /**
     * 钳制 PAD [-1,1]。
     */
    private static double clampPad(double v) {
        return Math.max(PAD_MIN, Math.min(PAD_MAX, v));
    }

    /**
     * 情绪标签规范化：去空白 + 截断到列长（20）；保留锚点 code 或 custom:* 语义。
     */
    private static String normalizeEmotionLabel(String label) {
        if (label == null) {
            return EMOTION_CALM;
        }
        String t = label.trim();
        if (t.length() > 20) {
            t = t.substring(0, 20);
        }
        return t.isBlank() ? EMOTION_CALM : t;
    }

    /**
     * 当前游戏时刻（秒；世界时钟读取失败返回 null）。
     * <p>⚠️ 用「引擎推进位置（last_game_second/last_game_hour）」而非流动时钟 currentGameHour——
     * 冒烟抓修：onNpcAction 回退用流动时钟在高速率下（锚点久远 × 速率）把需求整体衰减归零
     * （真实流逝时钟与引擎离散模拟位置脱节）；状态衰减/行动挂钩的时间基准统一取引擎最后推进到的
     * 模拟位置。 起优先取秒级位置 last_game_second（5 游戏分钟步进后的细粒度值），
     * 存量/失败回退小时位置 ×3600。</p>
     */
    private Long currentGameSecond(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        Long s = worldClockService.currentEngineGameSecond(projectId);
        if (s != null) {
            return s;
        }
        Long h = worldClockService.currentEngineGameHour(projectId);
        return h == null ? null : h * 3600L;
    }

    /**
     * 游戏时刻钳制：
     * 一切进入 actor_state 的时间基准不得超过引擎推进位置——调用方（事件总线/决策上下文等）若传入
     * 流动时钟换算值（引擎停止期间仍按速率×真实流逝插值虚跑，24h 压测实证可达 87 游戏天），
     * 会把状态行 game_second 推到远超引擎位置 → advance 永久跳行（状态冻结）且一次性补衰减
     * 把需求/健康打到 0（陆沉舟 9/10 实证）。超过引擎位置一律钳制回引擎位置；
     * <b>2 游戏小时内静默钳制</b>（引擎自身小时边界输入 hour×3600 在时钟落库前瞬态超前 ≤1 步+1 小时，
     * 属正常推进序列，不告警防 WARN 刷屏污染压测统计），<b>超过 2 小时才 WARN</b>（真流动时钟污染）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 调用方传入的游戏时刻（秒，可空）
     *
     * @return 钳制后的游戏时刻（引擎不可读/未超限时原样返回）
     */
    private Long sanitizeGameSecond(Long projectId, Long gameSecond) {
        if (gameSecond == null || worldClockService == null || projectId == null) {
            return gameSecond;
        }
        Long engine = worldClockService.currentEngineGameSecond(projectId);
        if (engine == null) {
            return gameSecond;
        }
        if (gameSecond > engine) {
            if (gameSecond - engine > SANITIZE_WARN_TOLERANCE_SECONDS) {
                log.warn("[状态] 传入游戏时刻超过引擎推进位置，已钳制（P0-2 流动时钟污染防护）: "
                        + "project={} 传入={} 引擎={}", projectId, gameSecond, engine);
            } else {
                log.debug("[状态] 传入游戏时刻轻微超前引擎位置，静默钳制（引擎小时边界瞬态）: "
                        + "project={} 传入={} 引擎={}", projectId, gameSecond, engine);
            }
            return engine;
        }
        return gameSecond;
    }

    /**
     * npcRef → npcType 解析。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     */
    private String refTypeOf(Long projectId, String npcRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（"是否特殊型"查档案行）
        return npcTypeResolver != null ? npcTypeResolver.typeOf(projectId, npcRef) : NpcRefs.TYPE_SPECIAL;
    }

    /**
     * 项目世界观地点名列表（公开：生理需求耦合服务经本方法复用同一地点匹配口径）。
     */
    public List<String> locationNames(Long projectId) {
        if (locationRepository == null || projectId == null) {
            return List.of();
        }
        try {
            List<ActorWorldLocation> locs = locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
            if (locs == null || locs.isEmpty()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (ActorWorldLocation l : locs) {
                if (l.getName() != null && !l.getName().isBlank()) {
                    names.add(l.getName().trim());
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("[状态驱动] 地点候选读取失败（无对象评分）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 纯文本截断（保留原语义，截断到 max 字符；null 安全）。
     */
    private static String truncatePlain(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
