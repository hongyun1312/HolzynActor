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

package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.prompt.GroupReplyParse;
import com.holzyn.actor.domain.agent.prompt.LayeredPromptBuilder;
import com.holzyn.actor.domain.agent.prompt.PromptContract;
import com.holzyn.actor.domain.agent.prompt.PromptLayers;
import com.holzyn.actor.domain.agent.prompt.PromptMetricsService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.service.ConversationContextService;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.service.GroupChatConfigService;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorEvolutionTurnRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

/**
 * 角色扮演组件（阶段 C，domain/agent/roleplay）——对话/群聊/演化 AI 编排收编 + B5 crowd 渲染并入。
 * <p>定位：把认知结果翻译为<b>符合人设的语言/行为表达</b>——
 * 单聊/群聊/演化/事件回应；并让普通 NPC 也能进入对话。 收编：</p>
 * <ul>
 * <li><b>单聊编排</b>（原 {@code ChatService.buildMessages/generate/generateTitle}）：
 * system_prompt（角色卡/档案）+ 用户档案 + 场景 + 感知【当前所见】+ 记忆/RAG/人群环境 + 历史窗口 + 超窗摘要；</li>
 * <li><b>群聊编排</b>（原 {@code GroupChatService.runRound/generateOne/pickNext/decideSpeaker/
 * buildCharacterMessages/...}）：AI 按发言欲望选发言人 + 逐角色流式生成 + 成员索引调度（B5）；</li>
 * <li><b>演化编排</b>（原 {@code WorldEvolutionService.orchestrate/aiSelect}）：AI 导演逐拍编排
 * + AI 选角；</li>
 * <li><b>crowd 渲染</b>（B5）：复用 {@link RoleplayContextService#resolveCharacterPrompt}（成员类型化）。</li>
 * </ul>
 * <p>接线：旧服务（ChatService/GroupChatService/WorldEvolutionService）保留兼容壳 + roleplay 开关
 * 委托本组件；AgentRuntime 扮演步骤委托本组件。记忆侧一律经 {@link AgentMemoryService}（记忆组件）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleplayService {

    /** 单聊保留的历史消息轮数（控制 token 成本） */
    private static final int HISTORY_WINDOW = 20;

    /** SSE 事件超时（毫秒）：300 秒无写入则断开（收编自 ChatService；群聊回复上限可调到 1~20） */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** 虚拟线程执行器：流式生成在虚拟线程运行，避免占用请求线程（收编自 ChatService） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 会话级生成互斥标志（防止同一会话并发多次生成； 收编自 ChatService） */
    private final ConcurrentHashMap<Long, AtomicBoolean> generating = new ConcurrentHashMap<>();

    /** 群聊发言欲望量表上限（1~5：与 group_orchestrator 模板契约一致） */
    private static final int DESIRE_MAX = 5;

    /**
     * 群聊发言欲望阈值（1~5 量表；低于该值视为「此刻不想接话」→ 本轮收束）。
     * <p>说话意愿修复：改为可配并统一口径——模板此前写「1到10 的整数」而代码按 1~5
     * 卡阈值 3（同文件另一模板即 1-5），三处口径不一致导致模型给 2 分即被判定「无人想接话」。</p>
     */
    @Value("${holzyn.actor.roleplay.group-desire-threshold:3}")
    private int desireThreshold = 3;

    /** 群聊单角色回复最大输出 token */
    private static final int REPLY_MAX_TOKENS = 1024;

    /** 群聊发言人评估输出最大 token */
    private static final int DESIRE_MAX_TOKENS = 512;

    /** AI 调度评估最大重试次数 */
    private static final int MAX_RETRY = 2;

    // ==================== 长跑 修复：群聊失控循环防护 ====================

    /** 深夜时段起始游戏小时（含）：23 点起居民就寝（与引擎程序化作息 23 就寝 / 0-5 休息同口径） */
    public static final int DEEP_NIGHT_START_HOUR = 23;
    /** 深夜时段结束游戏小时（不含）：6 点天亮解禁 */
    public static final int DEEP_NIGHT_END_HOUR = 6;

    /** 无实质进展判定阈值：本轮回复与上一条回复的 Dice 二元组重叠 ≥ 该值视为重复（内容原地踏步） */
    private static final double NO_PROGRESS_SIMILARITY = 0.9;
    /** 无实质进展连续次数达到该值 → 本轮提前结束（防 AI 逐字重复生成死循环） */
    private static final int NO_PROGRESS_MAX = 2;

    /**
     * 单条回复连续失败上限（无玩家轮）：发言人生成空回复/异常时<b>跳过该发言者继续下一拍</b>
     * （不再整轮截断，修复「一条失败回复 → 1~3 条短对话」缺陷），但连续失败达上限即收尾——
     * 防模型/网关持续故障时无限空转（每次失败都会清掉占位消息，不会堆积空消息）。
     */
    private static final int MAX_CONSECUTIVE_REPLY_FAILURES = 3;

    /** 入睡活动关键词（特殊 NPC current_activity 命中即视为休息，不再被调度发言） */
    private static final List<String> SLEEP_ACTIVITY_KEYWORDS = List.of(
            "入睡", "沉睡", "就寝", "歇息", "卧床", "酣睡", "安睡", "睡着", "睡觉", "睡下", "入眠", "打盹", "小憩");

    /** 演化 AI 编排最大输出 token */
    private static final int EVOLVE_MAX_TOKENS = 2048;

    /** 演化 AI 编排温度 */
    private static final double EVOLVE_TEMPERATURE = 0.8;

    /** 演化历史纳入窗口（拍数） */
    private static final int EVOLUTION_CONTEXT_WINDOW = 40;

    /** 演化逐拍调度输出最大 token（只需一个 JSON； 收编 WorldEvolutionService） */
    private static final int EVOLVE_SCHEDULE_MAX_TOKENS = 512;

    /** 演化单拍角色回复/行动最大输出 token（收编 WorldEvolutionService） */
    private static final int EVOLVE_BEAT_REPLY_MAX_TOKENS = 1024;

    /** 演化逐拍调度最大重试次数（模型偶发空输出/JSON 损坏/选中不在场角色时重试） */
    private static final int EVOLVE_SCHEDULE_MAX_RETRY = 2;

    /** 演化单拍回复最大重试次数（模型偶发只输出思考导致正文为空时重试） */
    private static final int EVOLVE_BEAT_MAX_RETRY = 2;

    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    private final ActorCharacterRepository npcRepository;
    private final ActorMessageRepository messageRepository;
    private final GroupChatConfigService groupChatConfigService;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    /** 智能体运行时（接线：对话完成后 after_dialog 行动自动评估经门面；单向依赖无循环——门面不依赖本组件） */
    private final AgentRuntime agentRuntime;
    private final ConversationContextService conversationContextService;
    private final AgentMemoryService agentMemoryService;
    private final LocalAccountService localAccountService;
    private final PerceptionService perceptionService;
    private final WorldClockService worldClockService;
    private final RoleplayContextService roleplayContextService;
    private final CurrentUserProvider currentUserProvider;
    private final PromptTemplateService promptTemplateService;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorSceneRepository sceneRepository;
    private final ActorEvolutionTurnRepository turnRepository;
    private final ObjectMapper objectMapper;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/crowd——记忆上下文走 character_ref 版、
     * 超窗摘要跳过、成员普通型校验等「只对普通型生效」的分支必须查 actor_profile.type；
     * Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /**
     * 作息运行态访问器（：普通型 NPC 的 state 列迁入 actor_state.activity_state，
     * 入睡判定不得再从主表实体读取；Spring 自动装配）
     */
    private final NpcActivityStateService npcActivityStateService;

    /** B2 感知注入开关（默认 true；false 行为与 B1 前一致） */
    @Value("${holzyn.actor.perception.enabled:true}")
    private boolean perceptionEnabled;

    /** 动态关系服务（群聊对话挂钩：字段注入保持构造器不变，存量单测零改动） */
    @Autowired(required = false)
    private RelationDynamicsService relationDynamicsService;

    /** 动态关系开关（：群聊挂钩前置门控；false 不增减好感） */
    @Value("${holzyn.actor.learning.relations.enabled:true}")
    private boolean relationsEnabled;

    /** 群聊一轮互动的好感增量（启发式：NPC 两两参与群聊每轮 +1） */
    @Value("${holzyn.actor.learning.relations.dialog-delta:1}")
    private int dialogDelta;

    /**
     * 会话实时订阅 Hub（无玩家群聊围观通道：字段注入保持构造器不变，存量单测零改动）：
     * 无玩家轮逐条消息经 {@link EventSink#send} 广播到订阅该会话的前端。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.event.ConversationLiveSseHub conversationLiveSseHub;

    /**
     * 预算守卫（无玩家群聊轮：字段注入保持构造器不变）：无玩家轮每次 AI 调用受
     * WorldEngine tick 预算门控（有窗口时 tryConsume 扣减，用尽即规则性收尾；无窗口[独立
     * 异步/玩家路径]不门控），玩家触发群聊不消耗引擎预算。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.BudgetGuard budgetGuard;

    /** SSE 生命周期统一工具（：字段注入避免改构造器；生成流 emitter 统一超时/清理） */
    @Autowired(required = false)
    private com.holzyn.actor.common.sse.SseLifecycle sseLifecycle;

    /** NPC 状态服务 */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 沙盘状态服务 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /** Prompt 工程指标（群聊契约格式合规率埋点；字段注入保持构造器不变） */
    @Autowired(required = false)
    private PromptMetricsService promptMetrics;

    /** 阶段护栏（INFANT 无语言输出能力 → 对话硬阻断，程序化回应零 AI；字段注入保持构造器不变） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.guard.GrowthGuardService growthGuard;

    /** 增长指标（护栏拦截计数；字段注入保持构造器不变） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.metrics.GrowthMetricsService growthMetrics;

    /** 社会面具（对话 mask_compliance 软修正：违规→指标+破绽演化；字段注入保持构造器不变） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.mask.MaskService maskService;

    /**
     * 社交意愿门控（搭话瞬间 boolean 判定「此刻是否愿意回应」；字段注入保持构造器不变；
     * 实测一致率 92.3%，升级策略 top1Prob≥0.5 采纳 NanoJev 否则升 Qwen）
     */
    @Autowired(required = false)
    private SocialWillingnessGate socialWillingnessGate;

    /** 社交拒绝连锁（拒绝后记忆 L1 + 对搭话者好感微降 + 情绪异步 + 沙盘想法；字段注入保持构造器不变） */
    @Autowired(required = false)
    private SocialDenialService socialDenialService;

    /**
     * 统一 AI 上下文注入器（对话链 V2.0 装配：段 1A 世界观 system 前缀 + 段 2D 世界动态 +
     * 段 2F few-shot 真实历史抽取 + 段 3I Post-History 行为护栏；字段注入保持构造器不变）。
     * <p>2026-10-08 批 2 对话链：单聊/群聊模板把批 1 判定统一门的 V2.0 骨架迁移到对话——
     * ① 世界观简报（common_knowledge 精炼常识）拼入 system 前缀（段 1A，缓存友好）；
     * ② 当前世界动态精简行（段 2D）拼入 CURRENT_STATE；③ few-shot 真实对话示例（段 2F）
     * 拼入 MEMORY_CONTEXT；④ 行为护栏（段 3I）落在触发消息末尾（Post-History 高权重位）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 认知注意力档位提供器（，LOD×群聊参与度双向联动的对话侧）：
     * ① desire 加分（参与度≥2 档或 focus 档 → desire +1 封顶 5，高参与/高关注更愿接话）；
     * ② 群聊参与度记录（发言=spoke、在场=attended 回灌注意力升档信号）。
     * 字段注入 + @Lazy 破 bean 环（roleplay → cognitiveAttentionService → realTimeDecisionService
     * → … 环）；null=未装配走既有行为（零联动）。
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.AttentionLevelProvider attentionLevelProvider;

    // ==================== 长跑 修复配置（无玩家群聊失控循环防护） ====================

    /**
     * 无玩家群聊轮现实时长上限（毫秒，默认 7 分钟）：轮内逐条生成超限即提前结束本轮。
     * <p>长跑 修复：异步化后轮级上限已非主护栏（剧集级时长上限 + 无进展检测 +
     * 无人接话收尾才是主护栏），默认放宽至 7 分钟≈上限 10 条回复 × 本地模型 40s/条的自然时长，
     * 避免截断正常对话；极端故障（AI 超时 130s×10 条≈21 分钟）仍被钉死在上限内。</p>
     */
    @Value("${holzyn.actor.roleplay.group-round-max-wall-ms:420000}")
    private long roundMaxWallMs = 420_000L;

    /** 深夜时段起始游戏小时（默认 23；入睡抑制判定用，与引擎程序化作息同口径） */
    @Value("${holzyn.actor.roleplay.deep-night-start:23}")
    private int deepNightStartHour = DEEP_NIGHT_START_HOUR;

    /** 深夜时段结束游戏小时（默认 6；入睡抑制判定用） */
    @Value("${holzyn.actor.roleplay.deep-night-end:6}")
    private int deepNightEndHour = DEEP_NIGHT_END_HOUR;

    // ==================== 群聊轮事件输出抽象（无玩家轮） ====================

    /**
     * 群聊轮事件输出抽象：玩家触发的群聊走 {@link SseEmitter} 流式；
     * 无玩家轮（NPC 自发对话）没有 SSE 客户端，改为向会话实时订阅 Hub 广播（玩家围观）。
     */
    public interface EventSink {

        /**
         * 发送一条事件。
         *
         * @param name 事件名（对齐 {@link com.holzyn.actor.common.sse.SseLifecycle#EVENT_*} 常量）
         *
         * @param data 载荷
         *
         * @return true=发送成功；false=连接已断开（调用方据此停止生成）
         */
        boolean send(String name, Map<String, Object> data);
    }

    /** 发射器适配：玩家触发的流式通道 */
    private static final class EmitterSink implements EventSink {
        private final SseEmitter emitter;

        EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public boolean send(String name, Map<String, Object> data) {
            if (emitter == null) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 会话实时广播适配：无玩家轮的围观推送（消息逐条广播给订阅该会话的前端） */
    private final class HubSink implements EventSink {
        private final Long conversationId;

        HubSink(Long conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public boolean send(String name, Map<String, Object> data) {
            if (conversationLiveSseHub != null) {
                conversationLiveSseHub.broadcast(conversationId, data);
            }
            // 广播无连接概念：返回 true（生成侧不受旁观者影响）
            return true;
        }
    }

    // ==================== 单聊编排（收编 ChatService.buildMessages/generate） ====================

    /**
     * 组装单聊对话消息（收编 {@code ChatService.buildMessages}，行为不变）：
     * system_prompt（角色卡渲染）+ NPC 档案 + 场景 + 感知【当前所见】+ 记忆/RAG/环境 + 历史窗口 + 超窗摘要。
     *
     * @param conv 会话实体
     *
     * @param pending assistant 占位（跳过）
     *
     * @param userId 归属用户 ID（RAG/embedding 凭据归属）
     *
     * @return 消息序列
     */
    /**
     * 组装单聊回复消息：
     * <p>按「变化频率从低到高」组装，稳定前缀（角色 system prompt + 本地账户档案）恒在最前且
     * 跨请求逐字节一致——命中服务端前缀缓存；变化段（当前状态/记忆上下文/任务/输出格式）
     * 拼入末尾单一 user 触发消息，不污染 system 前缀。</p>
     * <p>输出契约=流式正文。</p>
     */
    public List<AiChatRequest.ChatMessage> buildReplyMessages(ActorConversation conv, ActorMessage pending, Long userId) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        // ① 稳定前缀（L1/L2 缓存层）：V2.0 段 1A 世界观简报 + 角色 system prompt（SYSTEM+ROLE_PROFILE）+ 本地账户档案
        // 批 2 对话链：世界观简报（common_knowledge 精炼常识）拼入 system 前缀——跨版本稳定、缓存友好
        // （normalizeForLlm 会把多条 system 合并为唯一前置 system 段，段 1A+1B+1C 全在前缀）
        String brief = aiContextInjector == null ? "" : aiContextInjector.worldBrief(conv.getProjectId());
        if (brief != null && !brief.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", "【世界观】\n" + brief));
        }
        messages.add(new AiChatRequest.ChatMessage("system", resolveSystemPrompt(conv)));
        String npcProfile = localAccountService.renderNpcProfile();
        if (!npcProfile.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", npcProfile));
        }
        // ② 历史（user/assistant 交错；世界事件→user 角色，避免事件 system 被合并进前置污染前缀）
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        // 记忆检索 query=最近一条实质消息（玩家消息优先语义：最后一条非空实质消息通常即玩家/事件情境；
        // 流式占位/中断残留不算实质）——修复「对话注入空查询→memory_tier 空跑 + L2 相关性=0」缺陷
        String lastUserText = lastSubstantiveText(history);
        String memberRef = pending.getCharacterId() == null ? null : memberRefOf(conv, pending.getCharacterId());
        List<String> recentTexts = historyTexts(history);
        // ③ 变化段素材（L3/L4）：场景/感知/记忆上下文/超窗摘要
        String scene = resolveSceneMessage(conv);
        String perception = "";
        if (perceptionEnabled && pending.getCharacterId() != null) {
            long perceptionStart = System.currentTimeMillis();
            try {
                String perceptionRef = memberRef != null ? memberRef
                        : (com.holzyn.actor.common.NpcRefs.of(pending.getCharacterId()));
                // ：单聊对象=玩家（非角色对象）→ 传空列表，面具按「外来者+场合」判定
                perception = perceptionService.buildDialogPerceptionText(conv.getProjectId(), perceptionRef, null,
                        java.util.List.of());
                AiCallContext.recordStep(conv.getProjectId(), "dialog", "perception",
                        "对话感知组装（" + perceptionRef + "，可见事件/关系摘要）",
                        System.currentTimeMillis() - perceptionStart);
            } catch (Exception e) {
                log.warn("[Roleplay] 感知段注入失败，跳过: conv={} : {}", conv.getId(), e.getMessage());
            }
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type 决定走 character_ref 版上下文）
        String context = memberRef != null && npcTypeResolver.isCommon(conv.getProjectId(), memberRef)
                ? conversationContextService.buildContextByRef(userId, conv.getProjectId(), memberRef, lastUserText, recentTexts)
                : conversationContextService.buildContext(userId, conv.getProjectId(),
                        pending.getCharacterId(), lastUserText, recentTexts);
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        String past = "";
        // S2 窗口压缩联动：历史超窗时注入「会话摘要记忆」承接（普通型 NPC 不注入超窗摘要，与旧行为一致）
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type）
        if (from > 0 && pending.getCharacterId() != null
                && (memberRef == null || !npcTypeResolver.isCommon(conv.getProjectId(), memberRef))) {
            past = agentMemoryService.conversationSummaryContext(conv.getProjectId(), pending.getCharacterId());
        }
        // ④ 变化段 → 单一 user 触发消息（分层组装，稳定前缀不受污染）
        String name = pending.getCharacterId() == null ? null : characterName(pending.getCharacterId());
        LayeredPromptBuilder layered = LayeredPromptBuilder.builder();
        StringBuilder currentState = new StringBuilder();
        // 批 2 对话链：段 2D 当前世界动态精简行（世界历时刻 · 季节 · 天气）置顶——轻量确定性、零 AI
        String dynamics = aiContextInjector == null ? "" : aiContextInjector.worldDynamicsLine(conv.getProjectId());
        if (dynamics != null && !dynamics.isBlank()) {
            currentState.append("【当前世界动态】").append(dynamics);
        }
        if (scene != null && !scene.isBlank()) {
            if (currentState.length() > 0) {
                currentState.append(PromptLayers.BLOCK_SEP);
            }
            currentState.append(scene);
        }
        if (perception != null && !perception.isBlank()) {
            if (currentState.length() > 0) {
                currentState.append(PromptLayers.BLOCK_SEP);
            }
            currentState.append(perception);
        }
        layered.section(PromptLayers.CURRENT_STATE, currentState.toString());
        StringBuilder memoryCtx = new StringBuilder();
        if (context != null && !context.isBlank()) {
            memoryCtx.append(context);
        }
        if (past != null && !past.isBlank()) {
            if (memoryCtx.length() > 0) {
                memoryCtx.append(PromptLayers.BLOCK_SEP);
            }
            memoryCtx.append("【过往对话摘要】").append(past);
        }
        // 批 2 对话链：段 2F few-shot 真实历史抽取（该角色近期真实对话范例 + 模板兜底）拼入记忆段
        String fewShot = aiContextInjector == null ? "" : aiContextInjector.dialogueFewShot(conv.getProjectId(), memberRef);
        if (fewShot != null && !fewShot.isBlank()) {
            if (memoryCtx.length() > 0) {
                memoryCtx.append(PromptLayers.BLOCK_SEP);
            }
            memoryCtx.append(fewShot);
        }
        layered.section(PromptLayers.MEMORY_CONTEXT, memoryCtx.toString());
        layered.section(PromptLayers.TASK, PromptContract.singleChatTask(name));
        // 批 2 对话链：段 3I 行为护栏（Post-History 高权重位，TASK 之后、输出契约之前）
        if (aiContextInjector != null) {
            layered.section(PromptLayers.POST_HISTORY_GUARD, aiContextInjector.dialogueGuardRail(name));
        }
        layered.section(PromptLayers.OUTPUT_FORMAT, PromptContract.singleChatOutputFormat(name));
        String trigger = layered.build();
        // ⑤ 历史消息（事件→user 角色；跳过占位消息与空回复残留）
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (m.getId().equals(pending.getId())) {
                continue; // 跳过占位消息
            }
            // 空回复防护：历史中的空 assistant 消息（旧 500 残留）不进入请求
            if ("assistant".equals(m.getRole()) && (m.getContent() == null || m.getContent().isBlank())) {
                continue;
            }
            AiChatRequest.ChatMessage ai = toLayeredAiMessage(m);
            if (ai != null) {
                messages.add(ai);
            }
        }
        if (!trigger.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("user", trigger));
        }
        // llama.cpp 兼容归一化：合并相邻 system（仅稳定前缀）+ 结构校验
        return normalizeForLlm(messages);
    }

    /**
     * 提取历史窗口内消息文本（注入去重用）：取最近 HISTORY_WINDOW 条消息的内容列表，
     * 供记忆注入侧剔除「刚说过的事实」（避免上下文冗余/token 浪费）。
     *
     * @param history 会话历史消息（按序）
     *
     * @return 最近窗口内非空消息内容列表（可空/空=不去重）
     */
    private static List<String> historyTexts(List<ActorMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        List<String> out = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (m.getContent() != null && !m.getContent().isBlank()) {
                out.add(m.getContent().trim());
            }
        }
        return out;
    }

    /**
     * 最近一条「实质消息」文本（记忆检索 query 源）：倒序遍历取最后一条内容非空、
     * 非流式占位/中断残留的消息——玩家消息、世界事件、已完成角色发言均算实质；
     * 纯 NPC↔NPC 自治聊天（历史只有 assistant/system）也能取到最近发言，
     * 修复「role=user 过滤 → 自治聊天 query 恒空 → memory_tier 恒（无明确查询）」缺陷（2026-10 长跑实测）。
     *
     * @param history 会话历史消息（按序）
     *
     * @return 最近实质消息文本（无可取返回 null，调用方再兜底本场背景/注入器情境）
     */
    private static String lastSubstantiveText(List<ActorMessage> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            // 流式占位/中断残留不算实质消息
            if ("assistant".equals(m.getRole()) && "streaming".equals(m.getStatus())) {
                continue;
            }
            return m.getContent();
        }
        return null;
    }

    /**
     * 把单聊历史消息映射为 AI 对话消息（静态， 收编 {@code ChatService.toAiMessage}，行为不变）。
     */
    public static AiChatRequest.ChatMessage toAiMessage(ActorMessage m) {
        if ("event".equals(m.getType()) && m.getContent() != null && !m.getContent().isBlank()) {
            return new AiChatRequest.ChatMessage("system", ActorMessage.EVENT_CONTEXT_PREFIX + m.getContent());
        }
        if ("user".equals(m.getRole())) {
            return new AiChatRequest.ChatMessage("user", m.getContent() == null ? "" : m.getContent());
        }
        if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
            return new AiChatRequest.ChatMessage("assistant", m.getContent() == null ? "" : m.getContent());
        }
        return null;
    }

    /**
     * 历史消息映射：世界事件 → <b>user</b>角色
     * （区别于旧 {@link #toAiMessage} 的 system 角色）——避免事件 system 消息被
     * {@link #normalizeForLlm} 合并进前置 system，污染稳定前缀（前缀缓存命中率）。
     *
     * @param m 历史消息
     *
     * @return AI 对话消息（event→user【世界事件】/ user→user / assistant(done)→assistant；其余 null）
     */
    public static AiChatRequest.ChatMessage toLayeredAiMessage(ActorMessage m) {
        if ("event".equals(m.getType()) && m.getContent() != null && !m.getContent().isBlank()) {
            return new AiChatRequest.ChatMessage("user", ActorMessage.EVENT_CONTEXT_PREFIX + m.getContent());
        }
        if ("user".equals(m.getRole())) {
            return new AiChatRequest.ChatMessage("user", m.getContent() == null ? "" : m.getContent());
        }
        if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
            return new AiChatRequest.ChatMessage("assistant", m.getContent() == null ? "" : m.getContent());
        }
        return null;
    }

    /**
     * llama.cpp 兼容归一化（「system 消息位置/多条连续 system → 空响应/500」）：
     * 模型机 llama-server（Qwen3.6-35B chat template）强制「System message must be at the beginning」
     * （Jinja 异常 500，已实测），且对多条连续 system 返回空响应——即 system 只能出现一次、且必须在
     * 消息序列最前。对话类请求会注入多条 system（身份、NPC 档案、场景、感知、记忆/上下文、世界事件、
     * 摘要、末尾「当前轮到」指示…），故发送前统一归一化：
     * ① 末尾 system（「当前轮到」指示 / 注入的世界事件）摘出，作为触发消息转为 user 追加到末尾
     * （llama.cpp 模板只在 user 消息后产出；Q/R/D 实测「合并 1 system + user」正常生成）；
     * ② 其余全部 system 内容（含历史中途的世界事件）按序收集为唯一的前置 system 段（\n\n 拼接，
     * 内容零丢失）——消除「非开头 system」与「多条连续 system」两类致命结构；
     * ③ user/assistant 历史保持原顺序不变。
     * 仅调整角色/合并结构，内容全部保留，行为不变。
     */
    static List<AiChatRequest.ChatMessage> normalizeForLlm(List<AiChatRequest.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        // ① 摘出末尾 system（指示/事件）→ 后续转为 user 触发消息
        List<AiChatRequest.ChatMessage> src = messages;
        AiChatRequest.ChatMessage trailing = null;
        int last = messages.size() - 1;
        if ("system".equals(messages.get(last).role())) {
            trailing = messages.get(last);
            src = messages.subList(0, last);
        }
        // ② 其余 system 全部收集为唯一前置 system 段；user/assistant 保持原顺序
        List<AiChatRequest.ChatMessage> out = new ArrayList<>();
        StringBuilder systemBuf = null;
        for (AiChatRequest.ChatMessage m : src) {
            if ("system".equals(m.role())) {
                if (systemBuf == null) {
                    systemBuf = new StringBuilder();
                } else {
                    systemBuf.append("\n\n");
                }
                systemBuf.append(m.content() == null ? "" : m.content());
            } else {
                out.add(m);
            }
        }
        if (systemBuf != null) {
            out.add(0, new AiChatRequest.ChatMessage("system", systemBuf.toString()));
        }
        // ③ 末尾指示/事件 → user 追加（触发模型产出；连续 user 实测兼容）
        if (trailing != null) {
            out.add(new AiChatRequest.ChatMessage("user", trailing.content()));
        }
        return out;
    }

    /**
     * 单聊流式生成（收编 {@code ChatService.generate}，行为不变）：组装 Prompt → 流式 AI →
     * 逐 token 推送 → 完成后落库 + 用量 + after_dialog 行动评估 + 记忆抽取。
     *
     * @param emitter SSE 发射器
     *
     * @param conv 会话实体
     *
     * @param pending assistant 占位消息
     *
     * @param userId 归属用户 ID（已捕获）
     */
    public void generateSingleReply(SseEmitter emitter, ActorConversation conv, ActorMessage pending, Long userId) {
        StringBuilder full = new StringBuilder();
        long startMs = System.currentTimeMillis();
        String charName = pending.getCharacterId() == null ? null
                : characterRepository.findById(pending.getCharacterId()).map(ActorCharacter::getName).orElse(null);
        log.info("[对话] 任务开始：会话={} 角色ID={} 角色={}", conv.getId(), pending.getCharacterId(), charName);
        AiCallContext.beginTrace();
        try {
            // 阶段护栏（stage_compliance）：无语言输出能力的阶段（INFANT）→ 硬阻断 AI 对话，
            // 直接输出程序化婴儿回应（零 AI 调用；验收「构造 INFANT 强制对话 → 护栏拦截」的落点）
            if (growthGuard != null && pending.getCharacterId() != null) {
                try {
                    ActorCharacter ch = characterRepository.findById(pending.getCharacterId()).orElse(null);
                    String block = ch == null ? null
                            : growthGuard.dialogueBlockReason(conv.getProjectId(), ch);
                    if (block != null) {
                        if (growthMetrics != null) {
                            growthMetrics.recordStageComplianceIntercept();
                        }
                        String infantText = infantReply(charName);
                        pending.setContent(infantText);
                        pending.setStatus("done");
                        pending.setTokenIn(0);
                        pending.setTokenOut(0);
                        messageRepository.save(pending);
                        log.info("[对话][护栏] 阶段硬阻断：会话={} 角色={}（{}），AI 未调用，程序化回应",
                                conv.getId(), pending.getCharacterId(), block);
                        try {
                            emitter.send(SseEmitter.event().name("token")
                                    .data(Map.of("delta", infantText, "characterId", pending.getCharacterId())));
                            emitter.send(SseEmitter.event().name("done")
                                    .data(Map.of("assistantMessageId", pending.getId(),
                                            "characterId", pending.getCharacterId(),
                                            "content", infantText, "tokenIn", 0, "tokenOut", 0)));
                        } catch (IOException ex) {
                            log.debug("[对话][护栏] SSE 已断开（忽略）: conv={}", conv.getId());
                        }
                        emitter.complete();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[对话][护栏] 阶段判定失败（放行 AI 对话，零阻断）: conv={} : {}",
                            conv.getId(), e.getMessage());
                }
            }
            // 社交意愿门控（搭话瞬间 boolean 判定「此刻是否愿意回应」）：不愿意 → 程序化简短拒绝
            // （零 AI 生成，亚秒返回）+ 拒绝连锁（记忆/好感/情绪/沙盘）；门控失败/未启用 → fail-open
            // 放行（现状有求必应，门控是新增层，失败不降级体验）。判定时机=单聊/被打招呼共用入口。
            if (socialWillingnessGate != null && pending.getCharacterId() != null) {
                try {
                    Long gs = worldClockService == null ? null
                            : worldClockService.currentEngineGameSecond(conv.getProjectId());
                    String stateText = buildSingleSocialStateText(conv, pending, charName, gs);
                    var wr = socialWillingnessGate.evaluate(conv.getProjectId(), pending.getCharacterId(),
                            charName, stateText);
                    if (!wr.willing()) {
                        String denial = socialWillingnessGate.denialText(charName, denialReasonOf(wr));
                        pending.setContent(denial);
                        pending.setStatus("done");
                        pending.setTokenIn(0);
                        pending.setTokenOut(0);
                        messageRepository.save(pending);
                        log.info("[对话][社交意愿] 拒绝回应：会话={} 角色={}（{}）理由={}",
                                conv.getId(), pending.getCharacterId(), charName, wr.disposition());
                        // SSE 推送（token + done 对齐正常回复通道，前端无需特判）
                        try {
                            emitter.send(SseEmitter.event().name("token")
                                    .data(Map.of("delta", denial, "characterId", pending.getCharacterId())));
                            emitter.send(SseEmitter.event().name("done")
                                    .data(Map.of("assistantMessageId", pending.getId(),
                                            "characterId", pending.getCharacterId(),
                                            "content", denial, "tokenIn", 0, "tokenOut", 0)));
                        } catch (java.io.IOException ex) {
                            log.debug("[对话][社交意愿] SSE 已断开（忽略）: conv={}", conv.getId());
                        }
                        // 拒绝连锁（记忆/好感/情绪/沙盘；搭话者为玩家 → 好感跳过）
                        if (socialDenialService != null) {
                            try {
                                socialDenialService.onDenial(conv.getProjectId(),
                                        com.holzyn.actor.common.NpcRefs.of(pending.getCharacterId()),
                                        com.holzyn.actor.common.NpcRefs.normalizeType(
                                                npcTypeOf(pending.getCharacterId())),
                                        null, null, denial, gs);
                            } catch (Exception ex) {
                                log.debug("[对话][社交意愿] 拒绝连锁失败（静默）: {}", ex.getMessage());
                            }
                        }
                        emitter.complete();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[对话][社交意愿] 门控判定失败（fail-open 放行 AI 对话，零阻断）: conv={} : {}",
                            conv.getId(), e.getMessage());
                }
            }
            List<AiChatRequest.ChatMessage> messages = buildReplyMessages(conv, pending, userId);
            // ：流式调用不返回 AiChatResult，供应商/模型从本次调用 trace 取——
            // 用量入账补齐 provider/model（此前 dialog 用量行这两列恒为 null，大模型管理后端无记录可查）
            java.util.concurrent.atomic.AtomicReference<Long> providerRef = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> modelRef = new java.util.concurrent.atomic.AtomicReference<>();
            AiCallContext.run("dialog", pending.getCharacterId(), () -> {
                aiChatGateway.chatCompletionStream(userId, conv.getProjectId(), null,
                        new AiChatRequest(null, messages, 0.7, 2048),
                        delta -> {
                            full.append(delta);
                            Map<String, Object> tokenData = new LinkedHashMap<>();
                            tokenData.put("delta", delta);
                            tokenData.put("characterId", pending.getCharacterId());
                            try {
                                emitter.send(SseEmitter.event().name("token").data(tokenData));
                            } catch (IOException ex) {
                                throw new RuntimeException("SSE 连接已断开", ex);
                            }
                        },
                        usage -> {
                            pending.setTokenIn(usage.promptTokens());
                            pending.setTokenOut(usage.completionTokens());
                            pending.setCacheHitTokens(usage.cacheHitTokens());
                            pending.setCacheMissTokens(usage.cacheMissTokens());
                        });
                com.holzyn.actor.domain.debug.service.AiCallTrace t = AiCallContext.current();
                if (t != null) {
                    providerRef.set(t.getProviderId());
                    modelRef.set(t.getModel());
                }
                return null;
            });

            // 空回复防护：模型空响应时不把空 assistant 消息写入会话历史
            // （与群聊一致，防空消息累积死循环；前端收到 error 事件可提示重试）
            if (full.length() == 0) {
                log.warn("[对话] 会话={} 角色={} 生成空回复（模型空响应，tokens={}/{}），不写入会话历史",
                        conv.getId(), pending.getCharacterId(), pending.getTokenIn(), pending.getTokenOut());
                pending.setStatus("failed");
                pending.setContent(null);
                messageRepository.save(pending);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "AI 返回空回复，请重试")));
                } catch (Exception ignored) {
                    // 前端已断开时忽略推送失败
                }
                emitter.complete();
                return;
            }
            pending.setContent(full.toString());
            pending.setStatus("done");
            messageRepository.save(pending);
            // mask_compliance 软修正：单聊回复违背当前面具情境禁止表达 →
            // 指标计数 + 记日志 + 破绽演化（真实的人违背表达规则本身就是失态/破绽，不替换 AI 文本——
            // 替换程序化文本比偶尔失态更假）；失败零阻断
            if (growthGuard != null && pending.getCharacterId() != null && full.length() > 0) {
                try {
                    ActorCharacter ch = characterRepository.findById(pending.getCharacterId()).orElse(null);
                    Long gs = worldClockService == null ? null : worldClockService.currentEngineGameSecond(conv.getProjectId());
                    String violation = growthGuard.dialogueViolationReason(conv.getProjectId(), ch,
                            java.util.List.of(), gs, full.toString());
                    if (violation != null) {
                        if (growthMetrics != null) {
                            growthMetrics.recordMaskComplianceIntercept();
                        }
                        log.info("[对话][面具] 单聊回复违背面具情境（软修正=失态破绽）：会话={} 角色={} 原因={}",
                                conv.getId(), pending.getCharacterId(), violation);
                        if (maskService != null) {
                            try {
                                maskService.evolveOnViolation(conv.getProjectId(),
                                        com.holzyn.actor.common.NpcRefs.of(pending.getCharacterId()), gs, violation);
                            } catch (Exception e) {
                                log.warn("[对话][面具] 破绽演化失败（跳过）: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[对话][面具] 面具软修正判定失败（跳过，零阻断）: {}", e.getMessage());
                }
            }
            // S2 沙盘 npc:talk：单聊回复也广播到会话实时 Hub（旁观语义），沙盘 Hub 经监听器转发对话气泡
            if (conversationLiveSseHub != null && pending.getCharacterId() != null
                    && full.length() > 0) {
                try {
                    Map<String, Object> talk = new LinkedHashMap<>();
                    talk.put("messageId", pending.getId());
                    talk.put("characterId", pending.getCharacterId());
                    talk.put("characterName", charName);
                    talk.put("role", "assistant");
                    talk.put("type", "text");
                    talk.put("content", full.toString());
                    talk.put("gameTimeText", conv.getGameTimeText());
                    conversationLiveSseHub.broadcast(conv.getId(), talk);
                } catch (Exception e) {
                    log.debug("[Roleplay] 单聊回复围观广播失败（不影响生成）: conv={} : {}", conv.getId(), e.getMessage());
                }
            }
            emitter.send(SseEmitter.event().name("done")
                    .data(Map.of("assistantMessageId", pending.getId(), "characterId", pending.getCharacterId(),
                            "content", full.toString(),
                            "tokenIn", pending.getTokenIn(), "tokenOut", pending.getTokenOut())));
            log.info("[对话] 角色={}：{}", charName, full.toString());
            log.info("[对话] 任务结束：会话={} 角色={} 耗时={}ms tokens={}/{}", conv.getId(), charName,
                    System.currentTimeMillis() - startMs, pending.getTokenIn(), pending.getTokenOut());
            usageLogService.record(userId, conv.getProjectId(), pending.getCharacterId(),
                    providerRef.get(), modelRef.get(), "dialog",
                    pending.getTokenIn(), pending.getTokenOut(),
                    pending.getCacheHitTokens(), pending.getCacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));
            log.info("[Roleplay] 单聊生成完成：会话={} 角色={} tokens={}/{}", conv.getId(), pending.getCharacterId(),
                    pending.getTokenIn(), pending.getTokenOut());
            // 对话完成后自动评估高重要度角色的行动（after_dialog， 统一经 AgentRuntime 门面）
            agentRuntime.evaluateAfterDialogAsync(conv.getId(), userId);
            // 对话完成后异步抽取长期记忆（记忆组件）
            agentMemoryService.extractAfterRoundAsync(conv.getId(), userId);
            // 状态；冷却/人群门控/失败回退在服务内）
            maybeEmotionEvalFromDialog(conv, pending.getCharacterId(), userId);
            emitter.complete();
        } catch (Exception e) {
            log.warn("[Roleplay] SSE 对话生成失败: conv={} : {}", conv.getId(), e.getMessage());
            pending.setStatus("failed");
            pending.setContent(full.length() > 0 ? full.toString() : null);
            messageRepository.save(pending);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", friendlyError(e))));
            } catch (Exception ignored) {
                // 前端已断开时忽略推送失败
            }
            emitter.complete();
        } finally {
            AiCallContext.endTrace();
        }
    }

    /**
     * AI 生成对话专属标题（收编 {@code ChatService.generateTitle}，行为不变）。
     */
    public String generateTitle(Long projectId, Long characterId, String location, String gameTimeText) {
        Long userId = currentUserProvider.currentUserId();
        String charName = characterId == null ? null
                : characterRepository.findById(characterId).map(ActorCharacter::getName).orElse(null);
        String locText = (location == null || location.isBlank())
                ? "通过手机等远程通讯软件进行对话（远程通话/文字消息）" : location;
        List<AiChatRequest.ChatMessage> messages = List.of(
                new AiChatRequest.ChatMessage("system",
                        "你是一位善于提炼对话主题的世界观拟题助手。请根据给定的角色、对话所在地与世界时间，"
                                + "生成一个简短、有画面感、贴合世界设定的中文对话标题（不超过 20 字）。"
                                + "只输出标题本身，不要引号、不要标点修饰、不要任何解释。"),
                new AiChatRequest.ChatMessage("user",
                        "角色：" + (charName == null ? "（未知角色）" : charName)
                                + "\n对话所在地：" + locText
                                + "\n世界时间：" + (gameTimeText == null || gameTimeText.isBlank() ? "未指定" : gameTimeText)
                                + "\n请生成对话标题。"));
        long startMs = System.currentTimeMillis();
        try {
            AiChatResult result = AiCallContext.run("title_gen", characterId, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, messages, 0.8, 64)));
            usageLogService.record(userId, projectId, characterId, result.providerId(), result.model(),
                    "title_gen", result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));
            String title = result.content() == null ? "" : result.content();
            title = title.replaceAll("^[\"'“”‘’「『【《]+|[\"'“”‘’」』】》]+$", "")
                    .replaceAll("[\\r\\n]+", " ").trim();
            if (title.isBlank()) {
                throw new BizException(400, "AI 生成标题为空，请重试或手动填写");
            }
            if (title.length() > 100) {
                title = title.substring(0, 100).trim();
            }
            return title;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("[Roleplay] 标题生成失败：project={} : {}", projectId, e.getMessage());
            throw new BizException(400, "AI 标题生成失败：" + friendlyError(e));
        }
    }

    // ==================== 对话入口（收编 ChatService.sendMessage/stream，角色扮演组件唯一入口） ====================

    /**
     * 发送消息（收编 {@code ChatService.sendMessage}，行为不变）：单聊写 user + assistant 占位；
     * 群聊仅写 user 消息（发言人由编排阶段动态确定）。
     *
     * @param conversationId 会话主键
     *
     * @param content 用户消息内容
     *
     * @param forceCharacterId 群聊指定发言人（可空）
     *
     * @return Map：userMessageId / assistantMessageId（单聊）/ mode / group / forceCharacterId
     */
    @Transactional
    public Map<String, Object> sendMessage(Long conversationId, String content, Long forceCharacterId) {
        ActorConversation conv = conversationService.requireOwned(conversationId);
        // 会话结束兜底（懒检查）：距上条消息超过阈值视为上次会话已结束，本轮开始前异步补提完整记忆
        // （须在更新 lastMessageAt 之前调用）
        agentMemoryService.maybeExtractAfterIdleAsync(conv, currentUserProvider.currentUserId());
        // 清理历史遗留的 streaming 残留（如页面刷新中断），置为 failed 避免阻塞后续
        messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(m -> "assistant".equals(m.getRole()) && "streaming".equals(m.getStatus()))
                .forEach(m -> {
                    m.setStatus("failed");
                    m.setContent((m.getContent() == null ? "" : m.getContent()) + "（已中断）");
                    messageRepository.save(m);
                });

        ActorMessage user = new ActorMessage();
        user.setConversationId(conversationId);
        user.setRole("user");
        user.setType("text");
        user.setContent(content);
        user.setStatus("done");
        messageRepository.save(user);

        conv.setLastMessageAt(LocalDateTime.now());
        // 玩家插话：向 NPC 内群发消息即视为「参与」，标记参与时刻（UI 展示；参与后世界推进仍继续自动轮）
        if ("npc_group".equals(conv.getConversationType()) && conv.getPlayerParticipatedAt() == null) {
            conv.setPlayerParticipatedAt(LocalDateTime.now());
        }
        conversationRepository.save(conv);

        if ("group".equals(conv.getMode())) {
            // 群聊：不预建 assistant 占位，发言人由流式编排动态确定（forceCharacterId 可空，需可变 Map）
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userMessageId", user.getId());
            result.put("mode", "group");
            result.put("group", true);
            result.put("forceCharacterId", forceCharacterId);
            return result;
        }
        // 单聊：预建 assistant 占位（streaming），由 stream 触发生成；归属会话首个成员角色
        ActorMessage assistant = new ActorMessage();
        assistant.setConversationId(conversationId);
        assistant.setRole("assistant");
        assistant.setType("text");
        assistant.setContent("");
        assistant.setStatus("streaming");
        memberRepository.findByConversationId(conversationId).stream()
                .findFirst()
                .ifPresent(m -> assistant.setCharacterId(m.getCharacterId()));
        messageRepository.save(assistant);
        return Map.of("userMessageId", user.getId(), "assistantMessageId", assistant.getId(),
                "mode", "single", "group", false);
    }

    /**
     * 打开 SSE 流式通道（收编 {@code ChatService.stream}，行为不变）：群聊委托 {@link #runGroupRound}；
     * 单聊取最新 streaming 的 assistant 消息触发 {@link #generateSingleReply}；
     * 世界事件注入后（无占位）自动为会话首个成员建回应占位。
     *
     * @param conversationId 会话主键
     *
     * @param forceCharacterId 群聊指定发言人（可空，来自 stream 查询参数）
     *
     * @return SseEmitter（无进行中任务时直接 complete）
     */
    public SseEmitter stream(Long conversationId, Long forceCharacterId) {
        ActorConversation conv = conversationService.requireOwned(conversationId);
        // ：统一工具创建生成流发射器（超时 + 终止清理置 alive/互斥标志）
        SseEmitter emitter;
        // 并发互斥：同一会话已有生成任务时拒绝重复流
        AtomicBoolean flag = generating.computeIfAbsent(conversationId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            emitter = sseLifecycle == null ? new SseEmitter(SSE_TIMEOUT_MS)
                    : sseLifecycle.create(SSE_TIMEOUT_MS, 0L, null);
            emitter.completeWithError(new BizException(409, "该会话已有消息正在生成，请稍候"));
            return emitter;
        }
        // alive 标志：连接超时/完成/出错后置 false，生成线程据此安静终止
        AtomicBoolean alive = new AtomicBoolean(true);
        Runnable cleanup = () -> { alive.set(false); flag.set(false); };
        emitter = sseLifecycle == null ? new SseEmitter(SSE_TIMEOUT_MS) : sseLifecycle.create(SSE_TIMEOUT_MS, 0L, cleanup);
        if (sseLifecycle == null) {
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(e -> cleanup.run());
        }

        // 关键：SecurityContext 不随虚拟线程传播，必须在请求线程捕获当前用户 ID
        Long userId = currentUserProvider.currentUserId();

        if ("group".equals(conv.getMode())) {
            executor.execute(() -> runGroupRound(emitter, conv, userId, forceCharacterId, alive));
            return emitter;
        }

        // 单聊：取最新一条 streaming 的 assistant 消息作为生成目标
        ActorMessage pending = messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(m -> "assistant".equals(m.getRole()) && "streaming".equals(m.getStatus()))
                .findFirst().orElse(null);
        if (pending == null) {
            // 世界事件注入等无占位场景：自动为会话首个成员建回应占位
            List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
            if (!members.isEmpty()) {
                pending = new ActorMessage();
                pending.setConversationId(conversationId);
                pending.setCharacterId(members.get(0).getCharacterId());
                pending.setRole("assistant");
                pending.setType("text");
                pending.setContent("");
                pending.setStatus("streaming");
                messageRepository.save(pending);
            }
        }
        if (pending == null) {
            emitter.complete();
            return emitter;
        }
        ActorMessage target = pending;
        executor.execute(() -> generateSingleReply(emitter, conv, target, userId));
        return emitter;
    }

    // ==================== 群聊编排（收编 GroupChatService.runRound 系） ====================

    /**
     * 每轮连续回复上限（读取用户配置）。
     */
    public int maxReplies(Long userId) {
        return groupChatConfigService.getMaxReplies(userId);
    }

    /**
     * 执行一轮群聊编排（收编 {@code GroupChatService.runRound}，行为不变）：
     * 评估发言人 → 逐角色流式生成 → SSE 推送（玩家触发路径，流式走 SseEmitter）。
     */
    public void runGroupRound(SseEmitter emitter, ActorConversation conv, Long userId, Long forceCharacterId,
                              AtomicBoolean alive) {
        runGroupRoundSink(new EmitterSink(emitter), conv, userId, forceCharacterId, alive, emitter, true, null);
    }

    /**
     * 无玩家群聊轮（headless）：无 SSE 客户端，消息逐条广播到会话实时订阅 Hub
     * （玩家打开 NPC 内群页实时围观）；记忆抽取 + 关系好感照常（对齐群聊），但不触发
     * after_dialog 行动评估（自发对话由世界引擎驱动，行动评估已由引擎决策线覆盖，避免双算）。
     *
     * @param conv 会话实体（NPC 内群）
     *
     * @param userId 归属用户 ID
     *
     * @param alive 存活标志（外部可中止；null=不受限）
     *
     * @return 本轮回复数
     */
    public int runAutonomousGroupRound(ActorConversation conv, Long userId, AtomicBoolean alive) {
        return runAutonomousGroupRound(conv, userId, alive, null);
    }

    /**
     * 无玩家群聊轮（headless， 说话意愿修复：带「本场背景」重载）：
     * 背景 = 触发本次交谈的缘由（世界事件文本 / 日常自发闲聊说明）——注入发言人选评估与
     * 逐角色回复上下文，让「开场轮」有据可依（此前开场轮上下文为空 → 模型必答 desire=1
     * 「无人想接话」→ 剧集 1 节拍即收尾）。
     *
     * @param conv 会话实体（NPC 内群）
     *
     * @param userId 归属用户 ID
     *
     * @param alive 存活标志（外部可中止；null=不受限）
     *
     * @param chatBackground 本场背景（可空=不注入，行为同旧版）
     *
     * @return 本轮回复数
     */
    public int runAutonomousGroupRound(ActorConversation conv, Long userId, AtomicBoolean alive,
                                       String chatBackground) {
        return runGroupRoundSink(new HubSink(conv.getId()), conv, userId, null, alive, null, false, chatBackground);
    }

    /**
     * 群聊轮内部编排（玩家/无玩家共用）：发言人调度 → 逐成员生成 → 沉淀（关系/记忆）→ 收尾事件。
     *
     * @param sink 事件输出（EmitterSink=玩家流式 / HubSink=无玩家围观广播）
     *
     * @param conv 会话实体
     *
     * @param userId 归属用户 ID
     *
     * @param forceCharacterId 指定发言人（玩家路径可空；无玩家轮恒 null）
     *
     * @param alive 存活标志（可空=不受限）
     *
     * @param emitter SseEmitter（仅玩家路径用于 complete；无玩家轮传 null）
     *
     * @param evaluateAfterDialog 是否触发 after_dialog 行动评估（玩家群聊 true；无玩家轮 false 防双算）
     *
     * @param chatBackground 本场背景（说话意愿修复；可空=不注入）
     *
     * @return 本轮回复数
     */
    private int runGroupRoundSink(EventSink sink, ActorConversation conv, Long userId, Long forceCharacterId,
                                  AtomicBoolean alive, SseEmitter emitter, boolean evaluateAfterDialog,
                                  String chatBackground) {
        try {
            long roundStart = System.currentTimeMillis();
            List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
            if (members.isEmpty()) {
                throw new BizException(400, "群聊没有成员，请先添加角色");
            }
            int maxReplies = groupChatConfigService.getMaxReplies(userId);
            log.info("[群聊] 本轮开始：会话={} 成员={} 上限={} 条 无玩家={}", conv.getId(), members.size(),
                    maxReplies, !evaluateAfterDialog);
            // 无玩家轮预算门控：每次 AI 调用（发言人评估 + 每条回复）受 BudgetGuard 门控，
            // 用尽即停（由 AutonomousChatService 按规则性离场/收尾兜底）
            boolean gateBudget = !evaluateAfterDialog;
            // 说话意愿修复（C 层）：本场是否尚无实质发言（=开场轮）。
            // 开场轮不得被「发言欲望阈值」与「社交意愿门控」拦死——否则整场胎死腹中（0 条消息），
            // 正是长跑/冒烟「6 场中 4 场 0~1 条消息」的直接机制；开场轮改由模型选出的最相关成员起头。
            boolean kickoff = gateBudget && hasNoSubstantiveMessages(conv.getId());
            // 长跑 修复：无玩家轮先过滤入睡/深夜成员——入睡者不得被强制拉入发言
            // （v1 实测：两角色入睡后仍被轮询兜底强拉发言、逐字复读循环 30+ 分钟）；全员入睡 → 本轮直接跳过。
            // 时刻取「引擎实际推进位置」（currentEngineGameHour）而非流动时钟——流动时钟在引擎停摆期间
            // 会随速率漂移，入睡抑制必须以世界真实时刻为基准。
            List<ActorConversationMember> candidates = members;
            if (gateBudget) {
                Long gh = conv.getProjectId() == null ? null : worldClockService.currentEngineGameHour(conv.getProjectId());
                candidates = filterAwakeMembers(conv, members, gh);
                if (candidates.isEmpty()) {
                    log.info("[群聊-无玩家] 全员入睡/深夜休息，本轮跳过：会话={}", conv.getId());
                    return 0;
                }
                log.debug("[群聊-无玩家] 清醒成员 {} / 全体 {}：会话={}", candidates.size(), members.size(), conv.getId());
            }
            // 社交意愿门控（群聊）：每轮对候选成员批量 boolean 判定「此刻是否愿意接话」
            // （一次前向 ≤32 states），不愿者沉默出局（不发言=自然社交动态）；全员不愿 → 自然收束
            // （对齐「无人想接话」语义）。门控失败/未启用 → 全员放行（fail-open 现状）。
            // 说话意愿修复（C 层）：开场轮例外——本场还没人开口时，全员「不愿接话」
            // 不得让整场胎死腹中（否则事件回应轮 0 条消息直接归档，世界又变安静）；保留候选，
            // 由发言人选评估挑出最相关成员起头，后续节拍再按真实意愿自然收束。
            if (socialWillingnessGate != null && !candidates.isEmpty()) {
                try {
                    Long gs = worldClockService == null ? null
                            : worldClockService.currentEngineGameSecond(conv.getProjectId());
                    List<ActorConversationMember> willing = applyGroupSocialWillingness(conv, candidates, gs);
                    if (willing.isEmpty()) {
                        if (kickoff) {
                            log.info("[群聊][社交意愿] 开场轮全员不愿接话 → 仍由最相关成员起头"
                                    + "（保证开场首个发言者）：会话={} 候选={}", conv.getId(), candidates.size());
                        } else {
                            log.info("[群聊][社交意愿] 无人愿意接话，本轮自然收束：会话={}", conv.getId());
                            if (emitter != null) {
                                sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_DONE,
                                        Map.of("group", true, "count", 0));
                                emitter.complete();
                            }
                            return 0;
                        }
                    } else {
                        candidates = willing;
                    }
                } catch (Exception e) {
                    log.warn("[群聊][社交意愿] 批量门控失败（全员放行，零阻断）: conv={} : {}", conv.getId(),
                            e.getMessage());
                }
            }
            Set<String> used = new HashSet<>();
            Set<String> memberNames = members.stream()
                    .map(this::memberName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            int replies = 0;
            boolean first = true;
            // 无实质进展计数
            int noProgress = 0;
            String lastContent = null;
            // 长跑 修复（2026-10）：① 上一成功发言成员 ref（防自言自语——同一人不得连续发言，
            // 由 pickNext 在候选人 ≥2 时回避）；② 本轮发言失败的成员 ref（防再次选中同一失败者空转）
            String lastSpeakerRef = lastDoneAssistantRef(conv);
            Set<String> failedRefs = new HashSet<>();
            // 单条回复连续失败计数（防模型/网关持续故障时空转）
            int consecutiveReplyFailures = 0;
            while (replies < maxReplies && (alive == null || alive.get())) {
                // 现实时长上限（无玩家轮）：单轮耗时超限即收尾——防长剧集独占调度线程/阻塞世界引擎
                if (gateBudget && System.currentTimeMillis() - roundStart > roundMaxWallMs) {
                    log.info("[群聊-无玩家] 单轮现实时长超限（上限 {}ms），本轮提前结束：会话={} 已回复={}",
                            roundMaxWallMs, conv.getId(), replies);
                    break;
                }
                if (gateBudget && !tryConsumeAiBudget(conv.getProjectId())) {
                    log.info("[群聊-无玩家] AI 预算用尽，本轮提前结束：会话={}", conv.getId());
                    break;
                }
                ActorConversationMember next = pickNext(conv, userId, candidates, used, first ? forceCharacterId : null,
                        evaluateAfterDialog, kickoff && first, chatBackground, gateBudget, lastSpeakerRef, failedRefs);
                if (next == null || (alive != null && !alive.get())) {
                    if (next == null && gateBudget) {
                        log.info("[群聊-无玩家] 无人想接话/评估失败，本轮结束：会话={}", conv.getId());
                    }
                    break;
                }
                first = false;
                used.add(memberRefOf(next));
                AtomicReference<String> lastRef = new AtomicReference<>();
                if (!generateGroupMemberSink(sink, conv, next, userId, alive, memberNames, lastRef, chatBackground)) {
                    // 长跑 修复（2026-10）：单条回复失败（空回复/解析失败/异常）不再整轮截断——
                    // 无玩家轮跳过该发言者继续下一拍（失败占位消息已在生成器内清理），
                    // 连续失败达上限才收尾；玩家轮保持原行为（失败即结束本轮，体验优先）。
                    if (!gateBudget) {
                        break;
                    }
                    failedRefs.add(memberRefOf(next));
                    consecutiveReplyFailures++;
                    log.info("[群聊-无玩家] 角色={} 回复失败（已清理占位），跳过继续：会话={} 连续失败={}",
                            memberName(next), conv.getId(), consecutiveReplyFailures);
                    if (consecutiveReplyFailures >= MAX_CONSECUTIVE_REPLY_FAILURES) {
                        log.info("[群聊-无玩家] 连续 {} 条回复失败，本轮提前结束：会话={}", MAX_CONSECUTIVE_REPLY_FAILURES, conv.getId());
                        break;
                    }
                    continue;
                }
                consecutiveReplyFailures = 0;
                replies++;
                // 上一成功发言成员（供下一拍防连选）
                String spokeRef = memberRefOf(next);
                if (spokeRef != null) {
                    lastSpeakerRef = spokeRef;
                }
                // 无实质进展检测（仅无玩家轮）：与上一条回复近乎逐字相同 → 计数，连续达上限即收尾
                if (gateBudget) {
                    String cur = lastRef.get();
                    if (cur != null && lastContent != null
                            && MemoryDedup.overlapScore(cur, lastContent) >= NO_PROGRESS_SIMILARITY) {
                        noProgress++;
                        log.info("[群聊-无玩家] 内容与上一条高度重复（Dice≥{}），无进展计数={}：会话={}",
                                NO_PROGRESS_SIMILARITY, noProgress, conv.getId());
                        if (noProgress >= NO_PROGRESS_MAX) {
                            log.info("[群聊-无玩家] 连续 {} 条无实质进展，本轮提前结束：会话={}", noProgress, conv.getId());
                            break;
                        }
                    } else {
                        noProgress = 0;
                    }
                    lastContent = cur;
                }
            }
            conv.setLastMessageAt(LocalDateTime.now());
            conversationRepository.save(conv);
            // （对话→关系好感）：群聊轮内 NPC 两两互动，一轮后每对双向 +dialogDelta
            //
            applyGroupChatRelationDeltas(conv, members);
            // 玩家路径：对话后自动评估在场角色行动（after_dialog 触发源）
            if (evaluateAfterDialog) {
                agentRuntime.evaluateAfterDialogAsync(conv.getId(), userId);
            }
            // 记忆抽取（无玩家轮对齐群聊：每轮后照常抽取各参与者记忆）
            agentMemoryService.extractAfterRoundAsync(conv.getId(), userId);
            // 状态
            maybeEmotionEvalFromGroupRound(conv, members, userId);
            log.info("[群聊] 本轮结束：会话={} 共 {} 条回复，耗时={}ms 无玩家={}", conv.getId(), replies,
                    System.currentTimeMillis() - roundStart, !evaluateAfterDialog);
            if (alive == null || alive.get()) {
                sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_DONE,
                        Map.of("group", true, "count", replies));
                if (emitter != null) {
                    emitter.complete();
                }
            }
            return replies;
        } catch (Exception e) {
            log.warn("[Roleplay] 群聊编排失败: conv={} : {}", conv.getId(), e.getMessage());
            sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_ERROR,
                    Map.of("message", friendlyError(e)));
            if (emitter != null) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已完成/断开，忽略
                }
            }
            return 0;
        }
    }

    /**
     * 生成单个成员的回复（收编 {@code GroupChatService.generateOne}，行为不变）：建占位消息 →
     * 流式 AI → 回填落库 + 用量 + SSE 事件（玩家路径，流式走 SseEmitter）。
     */
    public boolean generateGroupMember(SseEmitter emitter, ActorConversation conv, ActorConversationMember member,
                                       Long userId, AtomicBoolean alive, Set<String> memberNames) {
        return generateGroupMemberSink(new EmitterSink(emitter), conv, member, userId, alive, memberNames, null, null);
    }

    /**
     * 生成单个成员的回复（玩家/无玩家共用内部实现）：建占位消息 → 流式 AI → 回填落库 +
     * 用量 + 事件（经 {@link EventSink} 输出——玩家流式 / 无玩家广播围观）。
     *
     * @param lastContentRef 输出参：生成成功时回填本次回复正文（供无玩家轮无实质进展检测；可空）
     */
    private boolean generateGroupMemberSink(EventSink sink, ActorConversation conv, ActorConversationMember member,
                                            Long userId, AtomicBoolean alive, Set<String> memberNames,
                                            AtomicReference<String> lastContentRef, String chatBackground) {
        Long characterId = member.getCharacterId();
        ActorMessage pending = new ActorMessage();
        pending.setConversationId(conv.getId());
        pending.setCharacterId(characterId);
        pending.setRole("assistant");
        pending.setType("text");
        pending.setStatus("streaming");
        messageRepository.save(pending);

        String charName = memberName(member);
        if (alive != null && !alive.get()) {
            log.info("[群聊] 连接已关闭，跳过角色={}（ID={}）的生成：会话={}", charName, characterId, conv.getId());
            pending.setStatus("failed");
            pending.setContent("（已中断）");
            messageRepository.save(pending);
            return false;
        }
        log.info("[群聊] 角色={}（ID={}）开始回复：会话={}", charName, characterId, conv.getId());
        if (!sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_MESSAGE_START, Map.of(
                "assistantMessageId", pending.getId(), "characterId", characterId, "characterName", charName))) {
            throw new RuntimeException("SSE 连接已断开");
        }

        long startMs = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        int[] usage = new int[4];
        AiCallContext.beginTrace();
        try {
            // 阶段护栏（stage_compliance）：无语言输出能力的阶段（INFANT）→ 硬阻断群聊 AI 回复，
            // 程序化婴儿回应（零 AI；与单聊同口径）
            if (growthGuard != null && characterId != null) {
                try {
                    ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
                    String block = ch == null ? null
                            : growthGuard.dialogueBlockReason(conv.getProjectId(), ch);
                    if (block != null) {
                        if (growthMetrics != null) {
                            growthMetrics.recordStageComplianceIntercept();
                        }
                        String infantText = infantReply(charName);
                        pending.setContent(infantText);
                        pending.setStatus("done");
                        pending.setTokenIn(0);
                        pending.setTokenOut(0);
                        messageRepository.save(pending);
                        log.info("[群聊][护栏] 阶段硬阻断：会话={} 角色={}（{}），AI 未调用，程序化回应",
                                conv.getId(), characterId, block);
                        Map<String, Object> donePayload = new LinkedHashMap<>();
                        donePayload.put("assistantMessageId", pending.getId());
                        donePayload.put("characterId", characterId);
                        donePayload.put("content", infantText);
                        donePayload.put("tokenIn", 0);
                        donePayload.put("tokenOut", 0);
                        sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_DONE, donePayload);
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("[群聊][护栏] 阶段判定失败（放行 AI 回复，零阻断）: conv={} : {}",
                            conv.getId(), e.getMessage());
                }
            }
            List<AiChatRequest.ChatMessage> msgs = buildCharacterMessages(conv, member, pending, userId, chatBackground);
            // Prompt 工程：群聊逐角色回复改<b>非流式 JSON 调用</b>
            // （打字机让位；SSE 事件契约不变：message_start → done 携带解析后的 dialogue）；
            //
            AiChatResult result = AiCallContext.run("dialog", characterId, () -> {
                AiChatResult r = aiChatGateway.chatCompletion(userId, conv.getProjectId(), null,
                        new AiChatRequest(null, msgs, 0.7, REPLY_MAX_TOKENS, true));
                usage[0] = r.promptTokens() == null ? 0 : r.promptTokens();
                usage[1] = r.completionTokens() == null ? 0 : r.completionTokens();
                usage[2] = r.cacheHitTokens() == null ? 0 : r.cacheHitTokens();
                usage[3] = r.cacheMissTokens() == null ? 0 : r.cacheMissTokens();
                return r;
            });
            String raw = result == null ? "" : result.content();
            full.append(raw == null ? "" : raw);

            // 契约解析（dialogue/action/emotion_update/internal_thought）；JSON 提取失败/无 dialogue → 空回复防护
            GroupReplyParse.GroupReply reply = GroupReplyParse.parse(full.toString());
            String cleaned = reply == null ? null : reply.dialogue();
            if (reply != null && !reply.compliant()) {
                // 契约不合规（缺 dialogue）：记指标（格式合规率分母）
                if (promptMetrics != null) {
                    promptMetrics.recordJsonFail("dialog", "群聊回复缺 dialogue 字段（契约不合规）");
                }
            } else if (reply != null) {
                if (promptMetrics != null) {
                    promptMetrics.recordJsonOk("dialog");
                }
            }
            // 空回复防护：模型空响应/仅角色名前缀时，不把空 assistant 消息写入
            // 会话历史（此前空消息累积 → 请求劣化 → 持续失败死循环，DB 曾堆积 31 条空消息）。
            // 2026-10 长跑修复：空回复时<b>直接删除占位行</b>（此前存 status=failed、content=null 的空行，
            // 前端渲染成「只有角色名没有内容」，且整轮被截断）。
            if (cleaned == null || cleaned.isBlank()) {
                log.warn("[群聊] 角色={} 生成空回复（模型空响应，tokens={}/{}），已清理占位：会话={}",
                        charName, usage[0], usage[1], conv.getId());
                try {
                    messageRepository.delete(pending);
                } catch (Exception e) {
                    log.debug("[群聊] 空回复占位删除失败（忽略，残留空行）: {}", e.getMessage());
                }
                return false;
            }
            pending.setContent(cleaned);
            // ：原始 JSON 契约存 raw_stream（调试回看 action/emotion/internal_thought）
            pending.setRawStream(truncate(full.toString(), 3000));
            pending.setStatus("done");
            pending.setTokenIn(usage[0]);
            pending.setTokenOut(usage[1]);
            pending.setCacheHitTokens(usage[2]);
            pending.setCacheMissTokens(usage[3]);
            messageRepository.save(pending);
            // 群聊参与度记录（，双向联动的对话侧信号源）：发言成功 → 发言者 spoke、
            // 在场其他成员 attended（内存态零副作用；失败静默跳过）
            recordGroupParticipation(conv, member, characterId);
            // mask_compliance 软修正（群聊与单聊同口径）：回复违背当前面具情境禁止表达 →
            // 指标计数 + 破绽演化（不替换 AI 文本；群聊对象=在场其他成员）
            if (growthGuard != null && characterId != null && cleaned.length() > 0) {
                try {
                    ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
                    Long gs = worldClockService == null ? null : worldClockService.currentEngineGameSecond(conv.getProjectId());
                    java.util.List<String> pRefs = new java.util.ArrayList<>();
                    for (ActorConversationMember m : memberRepository.findByConversationId(conv.getId())) {
                        if (m == null || m.getId() == null || m.getId().equals(member.getId())) {
                            continue;
                        }
                        String ref = memberRefOf(m);
                        if (ref != null && !ref.isBlank()) {
                            pRefs.add(ref);
                        }
                    }
                    String violation = growthGuard.dialogueViolationReason(conv.getProjectId(), ch, pRefs, gs, cleaned);
                    if (violation != null) {
                        if (growthMetrics != null) {
                            growthMetrics.recordMaskComplianceIntercept();
                        }
                        log.info("[群聊][面具] 回复违背面具情境（软修正=失态破绽）：会话={} 角色={} 原因={}",
                                conv.getId(), characterId, violation);
                        if (maskService != null) {
                            try {
                                maskService.evolveOnViolation(conv.getProjectId(),
                                        com.holzyn.actor.common.NpcRefs.of(characterId), gs, violation);
                            } catch (Exception e) {
                                log.warn("[群聊][面具] 破绽演化失败（跳过）: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[群聊][面具] 面具软修正判定失败（跳过，零阻断）: {}", e.getMessage());
                }
            }
            if (lastContentRef != null) {
                lastContentRef.set(cleaned);
            }
            // ：附带信息——情绪写回（轻量，冷却在服务内）、内心想法→沙盘想法气泡（失败静默）
            publishGroupReplySideEffects(conv, member, reply);
            // 无玩家围观：广播最终消息载荷（含 gameTimeText），前端可直接渲染；
            // ：附带 action/emotion/internalThought 字段（前端可选消费，向后兼容）
            java.util.Map<String, Object> donePayload = new java.util.LinkedHashMap<>();
            donePayload.put("assistantMessageId", pending.getId());
            donePayload.put("characterId", characterId);
            donePayload.put("content", cleaned);
            donePayload.put("tokenIn", usage[0]);
            donePayload.put("tokenOut", usage[1]);
            donePayload.put("cacheHitTokens", usage[2]);
            donePayload.put("cacheMissTokens", usage[3]);
            if (reply != null) {
                donePayload.put("action", reply.action() == null ? "" : reply.action());
                donePayload.put("emotion", reply.emotion() == null ? "" : reply.emotion());
                donePayload.put("emotionIntensity", reply.emotionIntensity() == null ? 0 : reply.emotionIntensity());
                donePayload.put("internalThought", reply.internalThought() == null ? "" : reply.internalThought());
            }
            sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_DONE, donePayload);
            log.info("[群聊] 角色={}：{}", charName, cleaned);
            log.info("[群聊] 角色={} 回复完成：耗时={}ms tokens={}/{}", charName,
                    System.currentTimeMillis() - startMs, usage[0], usage[1]);
            // ：用量入账补齐 provider/model（此前 dialog 用量行这两列恒为 null）
            usageLogService.record(userId, conv.getProjectId(), characterId,
                    result == null ? null : result.providerId(), result == null ? null : result.model(),
                    "dialog", usage[0], usage[1], usage[2], usage[3],
                    (int) (System.currentTimeMillis() - startMs));
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                pending.setStatus("failed");
                pending.setContent(full.length() > 0 ? full.toString() : "（已中断）");
                messageRepository.save(pending);
                return false;
            }
            log.warn("[Roleplay] 群聊角色生成失败: conv={} char={} : {}", conv.getId(), characterId, e.getMessage());
            if (full.length() > 0 && !full.toString().isBlank()) {
                // 有部分输出：保留 failed 行（正文可回看）
                pending.setStatus("failed");
                pending.setContent(full.toString());
                messageRepository.save(pending);
            } else {
                // 无任何输出：清理占位行（2026-10 长跑修复：不留「只有角色名」的空消息，
                // 也不让失败占位拖垮整轮——调用方跳过该发言者继续）
                try {
                    messageRepository.delete(pending);
                } catch (Exception ex) {
                    log.debug("[群聊] 失败占位删除失败（忽略，残留空行）: {}", ex.getMessage());
                }
            }
            sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_ERROR,
                    Map.of("message", friendlyError(e)));
            return false;
        } finally {
            AiCallContext.endTrace();
        }
    }

    /**
     * Prompt 工程：群聊 JSON 契约附带信息的落地——
     * ① emotion_update → 写回情绪（NpcStateService.evaluateEmotion 冷却门控内，失败静默）；
     * ② internal_thought → 沙盘想法气泡（失败静默）；③ action 仅随 SSE done 附带（rawStream 已留痕）。
     *
     * @param conv 会话实体
     *
     * @param member 发言成员
     *
     * @param reply 群聊契约解析结果（可空）
     */
    private void publishGroupReplySideEffects(ActorConversation conv, ActorConversationMember member,
                                              GroupReplyParse.GroupReply reply) {
        if (reply == null || conv == null || member == null) {
            return;
        }
        // ① 情绪写回（轻量契约情绪，冷却/失败回退在服务内）
        if (reply.emotion() != null && npcStateService != null) {
            try {
                String ref = memberRefOf(member);
                // ：类型值字面量统一走 NpcRefs.normalizeType（member_type 既有值以 common 为准）
                String type = com.holzyn.actor.common.NpcRefs.normalizeType(member.getMemberType());
                npcStateService.evaluateEmotion(conv.getProjectId(), ref, type,
                        "群聊发言", "对话情绪：" + reply.emotion()
                                + (reply.emotionIntensity() == null ? "" : "（强度 " + reply.emotionIntensity() + "）"),
                        null);
            } catch (Exception e) {
                log.debug("[Roleplay] 群聊契约情绪写回失败（静默）: conv={} : {}", conv.getId(), e.getMessage());
            }
        }
        // ② 内心想法 → 沙盘想法气泡（仅特殊型 NPC 有沙盘状态；失败静默）
        // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
        if (reply.internalThought() != null && !reply.internalThought().isBlank() && sandboxStateService != null
                && member.getCharacterId() != null
                && !com.holzyn.actor.common.NpcRefs.isCommonType(member.getMemberType())) {
            try {
                sandboxStateService.publishThought(conv.getProjectId(), com.holzyn.actor.common.NpcRefs.of(member.getCharacterId()),
                        reply.internalThought(), "ai", null);
            } catch (Exception e) {
                log.debug("[Roleplay] 群聊内心想法气泡失败（静默）: conv={} : {}", conv.getId(), e.getMessage());
            }
        }
    }

    /**
     * 选择下一位发言人（收编 {@code GroupChatService.pickNext/decideSpeaker}，行为不变）：
     * 指定覆盖优先 → AI 发言欲望评估 → 失败时轮询兜底。
     * <p>长跑 修复（核心）：</p>
     * <ul>
     * <li>AI 明确「无人想接话」（desire &lt; 阈值）→ <b>返回 null 结束本轮</b>——
     * 此前此处仅打印「本轮结束」却继续落入轮询兜底强拉第一位成员发言（v1 实测死循环根因①）；</li>
     * <li>AI 评估失败（空输出/解析失败，本地模型超长重复上下文下不稳定）→ 无玩家轮
     * {@code fallbackOnFailure=false} 返回 null 结束本轮（防死循环根因②）；玩家轮
     * {@code fallbackOnFailure=true} 保留轮询兜底（玩家发言后总要有人回应，体验优先）。</li>
     * </ul>
     * <p><b>2026-10 长跑修复（防自言自语/重复）</b>：候选人 ≥2 且 AI 选中的成员命中
     * 「上一成功发言成员」或「本轮回复失败的成员」→ 带回避提示重评估一次（{@code decideSpeaker}
     * 追加调度注意段）；重试仍选中失败成员 → 结束本轮（避免空转），选中上一发言者则接受
     * （fail-open，不因回避机制饿死对话）。</p>
     *
     * @param fallbackOnFailure 评估失败时是否轮询兜底（true=玩家触发群聊；false=无玩家自主轮）
     *
     * @param kickoff 是否开场轮（说话意愿修复 C 层：本场尚无实质发言 →
     * desire 只用于排序、不作为「开不开场」的闸门，保证开场有人起头）
     *
     * @param chatBackground 本场背景（A 层：触发本次交谈的缘由，注入编排 Prompt）
     *
     * @param avoidConsecutive 是否启用「防连选/防重选失败者」回避（true=无玩家轮 gateBudget；
     * false=玩家轮——保持原行为，玩家发言后由 AI 自由选最相关者回应）
     *
     * @param lastSpeakerRef 上一成功发言成员 ref（可空=本场尚无发言；防同一人连续发言）
     *
     * @param failedRefs 本轮发言失败的成员 ref 集合（可空；防再次选中失败者空转）
     */
    private ActorConversationMember pickNext(ActorConversation conv, Long userId, List<ActorConversationMember> members,
                                             Set<String> used, Long forceCharacterId, boolean fallbackOnFailure,
                                             boolean kickoff, String chatBackground, boolean avoidConsecutive,
                                             String lastSpeakerRef, Set<String> failedRefs) {
        if (members.isEmpty()) {
            return null;
        }
        if (forceCharacterId != null) {
            log.info("[群聊-调度] 指定发言人覆盖：角色ID={}", forceCharacterId);
            // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
            return members.stream()
                    .filter(m -> !com.holzyn.actor.common.NpcRefs.isCommonType(m.getMemberType()))
                    .filter(m -> m.getCharacterId().equals(forceCharacterId))
                    .findFirst().orElse(null);
        }
        try {
            Map<String, Object> decision = decideSpeaker(conv, userId, members, chatBackground, null);
            ActorConversationMember chosen = acceptDecision(decision, kickoff);
            if (chosen == null) {
                if (decision != null) {
                    // AI 明确无人想接话 → 结束本轮（不再强拉人发言）
                    log.info("[群聊-调度] 无人想接话：最高 desire={} < 阈值{}，本轮结束", decision.get("desire"), desireThreshold);
                    return null;
                }
                // AI 评估失败（输出为空/解析失败）：无玩家轮结束本轮；玩家轮轮询兜底
                if (!fallbackOnFailure) {
                    log.info("[群聊-调度] AI 评估失败（输出为空/解析失败），本轮结束：会话={}", conv.getId());
                    return null;
                }
                log.info("[群聊-调度] AI 评估失败（输出为空/解析失败），转轮询兜底选下一位：成员={}",
                        memberName(members.get(0)));
                return members.get(0);
            }
            // 2026-10 长跑修复：防自言自语 + 防重选失败者——仅无玩家轮（avoidConsecutive）启用；
            // 候选人 ≥2 且命中「上一成功发言成员」或「本轮回复失败的成员」→ 带回避提示重评估一次
            boolean hitLastSpeaker = lastSpeakerRef != null && sameMemberRef(lastSpeakerRef, chosen);
            boolean hitFailed = containsMemberRef(failedRefs, chosen);
            if (avoidConsecutive && members.size() >= 2 && (hitLastSpeaker || hitFailed)) {
                String avoidNote = avoidNoteFor(hitLastSpeaker, hitFailed);
                Map<String, Object> retry = decideSpeaker(conv, userId, members, chatBackground, avoidNote);
                ActorConversationMember retryChosen = acceptDecision(retry, kickoff);
                if (retryChosen != null) {
                    if (containsMemberRef(failedRefs, retryChosen)) {
                        // 重试仍选中本轮失败成员（大概率继续失败）→ 结束本轮，避免空转
                        log.info("[群聊-调度] 重试仍选中失败成员={}，本轮结束：会话={}",
                                memberName(retryChosen), conv.getId());
                        return null;
                    }
                    chosen = retryChosen;
                    decision = retry;
                }
                // 重试无人想接话/评估失败 → 保留原选中（fail-open，不因回避机制饿死对话）
            }
            if (kickoff && ((int) decision.get("desire") < desireThreshold)) {
                // C 层：开场轮低欲望不再让整场胎死腹中——desire 仅作排序依据，由最相关者起头
                log.info("[群聊-调度] 开场轮首个发言者：选中成员={} desire={}（阈值{}，仅排序不作闸门）理由={}",
                        memberName(chosen), decision.get("desire"), desireThreshold, decision.get("reason"));
            } else {
                log.info("[群聊-调度] 发言欲望评估：选中成员={} desire={}（阈值{}）理由={}",
                        memberName(chosen), decision.get("desire"), desireThreshold, decision.get("reason"));
            }
            return chosen;
        } catch (Exception e) {
            if (!fallbackOnFailure) {
                log.info("[群聊-调度] AI 评估不可用，本轮结束：会话={}", conv.getId());
                log.warn("[Roleplay] 群聊发言人评估失败，本轮结束: conv={} : {}", conv.getId(), e.getMessage());
                return null;
            }
            log.info("[群聊-调度] AI 评估不可用，转轮询兜底：候选第一位成员={}", memberName(members.get(0)));
            log.warn("[Roleplay] 群聊发言人评估失败，转轮询兜底: conv={} : {}", conv.getId(), e.getMessage());
            return members.get(0);
        }
    }

    /**
     * decision 是否产生「可发言」成员：desire≥阈值 或 开场轮（desire 仅排序不作闸门）。
     * 无人想接话（decision 非空但 desire 低）/ 评估失败（decision 为 null）→ 返回 null。
     */
    private ActorConversationMember acceptDecision(Map<String, Object> decision, boolean kickoff) {
        if (decision != null && ((int) decision.get("desire") >= desireThreshold || kickoff)) {
            return (ActorConversationMember) decision.get("member");
        }
        return null;
    }

    /** 成员 ref 是否等于给定 ref（防连选判定）。 */
    private boolean sameMemberRef(String ref, ActorConversationMember m) {
        if (ref == null || m == null) {
            return false;
        }
        String mr = memberRefOf(m);
        return mr != null && ref.equals(mr);
    }

    /** 成员 ref 是否在给定集合中（防重选失败者判定）。 */
    private boolean containsMemberRef(Set<String> refs, ActorConversationMember m) {
        if (refs == null || refs.isEmpty() || m == null) {
            return false;
        }
        String mr = memberRefOf(m);
        return mr != null && refs.contains(mr);
    }

    /** 回避提示段（注入发言人评估 Prompt）：防连续同人发言 + 防重选失败成员。 */
    private String avoidNoteFor(boolean hitLastSpeaker, boolean hitFailed) {
        StringBuilder sb = new StringBuilder("\n—— 调度注意：");
        if (hitLastSpeaker) {
            sb.append("上一位发言者刚刚说过话，若候选还有其他成员，请勿连续选择同一人发言（避免自言自语）；");
        }
        if (hitFailed) {
            sb.append("该成员刚才发言未能完成，请勿选择他，改选其他成员；");
        }
        return sb.toString();
    }

    // ==================== 长跑 修复：入睡/深夜成员调度抑制 ====================

    /**
     * 深夜时段判定：游戏小时（累计，引擎口径）在 [deepNightStartHour, 24) ∪ [0, deepNightEndHour)
     * 视为深夜全员休息——居民入睡，不再被调度进入对话。
     *
     * @param gameHour 累计游戏小时（内部取模当日小时）
     */
    public boolean isDeepNightHour(long gameHour) {
        int h = Math.floorMod(gameHour, 24);
        return h >= deepNightStartHour || h < deepNightEndHour;
    }

    /**
     * 过滤出「清醒可发言」的群聊成员。
     * <p>判定顺序：① 深夜时段 → 全员视为休息（与引擎程序化作息 23 就寝 / 0-5 休息同口径）；
     * ② 普通 NPC 按 state（rest/sleep/含「睡·休息」）判定；③ 特殊 NPC 按 current_activity
     * 命中入睡关键词判定。档案查不到/状态为空 → 视为清醒（不误伤旧行为）。</p>
     *
     * @param conv 会话实体（仅取 projectId）
     *
     * @param members 全体成员
     *
     * @param gameHour 当前游戏小时（可空=不做深夜判定）
     *
     * @return 清醒成员列表（空=全员入睡/深夜）
     */
    public List<ActorConversationMember> filterAwakeMembers(ActorConversation conv, List<ActorConversationMember> members,
                                                            Long gameHour) {
        if (members == null || members.isEmpty()) {
            return members == null ? List.of() : members;
        }
        if (gameHour != null && isDeepNightHour(gameHour)) {
            return List.of();
        }
        Long projectId = conv == null ? null : conv.getProjectId();
        List<ActorConversationMember> awake = new ArrayList<>();
        for (ActorConversationMember m : members) {
            if (isMemberResting(m, projectId)) {
                continue;
            }
            awake.add(m);
        }
        return awake;
    }

    /**
     * 清醒判定：供社交层在「建会话之前」预检。
     * <p>长跑实证：事件回应轮原实现先建 NPC 内群会话、后在 {@code doRunEpisode} 判清醒，深夜每触发
     * 一次就残留一个 0 消息会话（验收项⑤「空转会话占比 = 0」实测被打破：3 场会话全 0 消息）。
     * 本方法把同一套清醒口径（① 深夜时段 → 全员休息；② 普通型按 actor_state 作息运行态；
     * ③ 特殊型按 current_activity 入睡关键词；查不到视为清醒）暴露给预检，使「不够 2 名清醒」
     * 时根本不建会话。</p>
     *
     * @param ch 角色实体（特殊/普通同口径）
     *
     * @param gameHour 当前游戏小时（可空=不做深夜判定）
     *
     * @return true=清醒可参与内群
     */
    public boolean isAwakeCharacter(ActorCharacter ch, Long gameHour) {
        if (ch == null) {
            return false;
        }
        if (gameHour != null && isDeepNightHour(gameHour)) {
            return false;
        }
        try {
            if (com.holzyn.actor.common.NpcRefs.isCommonType(ch.getType())) {
                String st = npcActivityStateService.activityState(ch.getProjectId(),
                        com.holzyn.actor.common.NpcRefs.of(ch.getId()));
                if (st == null || st.isBlank()) {
                    return true;
                }
                return !("rest".equalsIgnoreCase(st) || "sleep".equalsIgnoreCase(st)
                        || st.contains("睡") || st.contains("休息"));
            }
            String act = ch.getCurrentActivity();
            if (act == null || act.isBlank()) {
                return true;
            }
            for (String kw : SLEEP_ACTIVITY_KEYWORDS) {
                if (act.contains(kw)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // 状态读取失败不误伤（与 filterAwakeMembers/isMemberResting 同口径：视为清醒）
            log.debug("[群聊] 角色清醒状态读取失败（视为清醒）: ref={} : {}",
                    ch.getId(), e.getMessage());
            return true;
        }
    }

    /**
     * 单成员休息判定：普通型 NPC 按作息运行态（actor_state.activity_state）；特殊型 NPC 按
     * current_activity 入睡关键词（查不到视为清醒）。
     */
    private boolean isMemberResting(ActorConversationMember m, Long projectId) {
        if (m == null || m.getCharacterId() == null) {
            return false;
        }
        try {
            // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
            if (com.holzyn.actor.common.NpcRefs.isCommonType(m.getMemberType())) {
                // ：作息运行态（原普通型 NPC 主表 state 列）已迁入 actor_state.activity_state，
                // 统一经 NpcActivityStateService 读取（无行返回 idle=清醒）
                String st = npcActivityStateService.activityState(projectId,
                        com.holzyn.actor.common.NpcRefs.of(m.getCharacterId()));
                if (st == null || st.isBlank()) {
                    return false;
                }
                return "rest".equalsIgnoreCase(st) || "sleep".equalsIgnoreCase(st)
                        || st.contains("睡") || st.contains("休息");
            }
            ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
            if (ch == null) {
                return false;
            }
            String act = ch.getCurrentActivity();
            if (act == null || act.isBlank()) {
                return false;
            }
            for (String kw : SLEEP_ACTIVITY_KEYWORDS) {
                if (act.contains(kw)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 状态读取失败不阻断发言（保持旧行为）
            log.debug("[群聊] 成员休息状态读取失败（视为清醒）: conv-member={} : {}", m.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 群聊参与度记录（，LOD×群聊参与度双向联动的对话侧信号源）：
     * 本次成功发言 → 发言者记录 spoke（权重 1）、在场其他成员记录 attended（权重 0.5），
     * 由 {@code CognitiveAttentionService} 按滑动窗口（近 6 游戏小时）加权计数，
     * 参与度档位回灌注意力升档（tick 注意力 +1）并参与本群聊 desire 加分（闭环）。
     * 纯内存零副作用（不落库、不调 AI、不含事务）；未装配/失败静默跳过。
     *
     * @param conv 当前会话
     *
     * @param speaker 本次发言成员（null 安全）
     *
     * @param characterId 发言角色 ID（日志用）
     */
    private void recordGroupParticipation(ActorConversation conv, ActorConversationMember speaker,
                                          Long characterId) {
        if (attentionLevelProvider == null || conv == null || conv.getProjectId() == null) {
            return;
        }
        try {
            Long gameSecond = worldClockService == null
                    ? null : worldClockService.currentEngineGameSecond(conv.getProjectId());
            if (gameSecond == null) {
                return;
            }
            String speakerRef = speaker == null ? null : memberRefOf(speaker);
            if (speakerRef != null && !speakerRef.isBlank()) {
                attentionLevelProvider.recordParticipation(conv.getProjectId(), speakerRef,
                        com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.PARTICIPATION_SPOKE,
                        gameSecond);
            }
            for (ActorConversationMember m : memberRepository.findByConversationId(conv.getId())) {
                if (m == null || m.getId() == null
                        || (speaker != null && m.getId().equals(speaker.getId()))) {
                    continue;
                }
                String ref = memberRefOf(m);
                if (ref != null && !ref.isBlank()) {
                    attentionLevelProvider.recordParticipation(conv.getProjectId(), ref,
                            com.holzyn.actor.domain.growth.attention.AttentionLevelProvider.PARTICIPATION_ATTENDED,
                            gameSecond);
                }
            }
        } catch (Exception e) {
            log.debug("[群聊] 参与度记录失败（跳过）: conv={} char={} : {}", conv.getId(), characterId, e.getMessage());
        }
    }

    /**
     * AI 评估各成员发言欲望（收编 {@code GroupChatService.decideSpeaker}，行为不变）。
     * 候选以「成员序号 1..N」标识（避免 special/crowd 对象 ID 撞车，B5）。
     * <p><b>2026-10 长跑修复（防自言自语/重复）</b>：{@code avoidNote} 非空时追加到成员序号列表之后
     * ——「上一位发言者刚说过话请勿连续选择同一人 / 该成员发言失败请改选其他成员」的调度注意段。</p>
     *
     * @param avoidNote 回避提示段（可空=不注入；防连续同人发言/防重选失败成员）
     */
    private Map<String, Object> decideSpeaker(ActorConversation conv, Long userId,
                                              List<ActorConversationMember> candidates, String chatBackground,
                                              String avoidNote) {
        String membersSummary = buildMembersSummary(conv, candidates);
        String context = buildContext(conv);
        // 说话意愿修复（A 层）：注入「本场背景」——此前触发本次交谈的缘由（世界事件文本）
        // 只进退场/收尾编排，发言人评估完全看不到；开场轮上下文为空时模型无据可依，只能答 desire=1
        // 「无人想接话」，剧集 1 节拍即收尾。
        String prompt = promptService.buildGroupOrchestratorPrompt(userId, conv.getProjectId(), membersSummary, context,
                chatBackground);
        if (!candidates.isEmpty()) {
            StringBuilder memberDesc = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                ActorConversationMember m = candidates.get(i);
                if (memberDesc.length() > 0) {
                    memberDesc.append("、");
                }
                memberDesc.append("成员").append(i + 1).append(":").append(memberName(m));
            }
            prompt = prompt + "\n—— 成员序号列表（memberIndex 只能从其中选择 1~" + candidates.size()
                    + "；允许选择已发言角色，剧情连贯时同一角色可继续接话）：——\n"
                    + memberDesc + "\n";
        }
        if (avoidNote != null && !avoidNote.isBlank()) {
            prompt = prompt + avoidNote;
        }
        // 用量累计（长跑审计改进：评估调用在成功/失败出口统一记一次，
        // 成功路径归属到最终选中的成员——消除 v1 长跑 character_id=null 占 51% 的审计盲区）
        int[] usageAcc = new int[4];
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.7, DESIRE_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("dialog", null, () ->
                        aiChatGateway.chatCompletion(userId, conv.getProjectId(), null, req));
                // 空值安全累计（HolzynTraceAdvisor 失败分支可能返回 null 用量， 抓修）
                usageAcc[0] += result.promptTokens() == null ? 0 : result.promptTokens();
                usageAcc[1] += result.completionTokens() == null ? 0 : result.completionTokens();
                usageAcc[2] += result.cacheHitTokens() == null ? 0 : result.cacheHitTokens();
                usageAcc[3] += result.cacheMissTokens() == null ? 0 : result.cacheMissTokens();

                String json = JsonUtil.extractJson(result.content());
                if (json == null) {
                    log.warn("[群聊-调度] 第 {} 次 AI 输出无法提取 JSON：原始输出（前 200 字）={}",
                            attempt, truncate(result.content(), 200));
                    continue;
                }
                JsonNode node = objectMapper.readTree(json);
                int memberIndex = node.path("memberIndex").asInt(-1);
                if (memberIndex < 1) {
                    // 容错：旧版模板/模型可能输出 characterId 字段（实为 1..N 序号，
                    // 摘要未给真实角色 ID，模型常按成员序号推断）——同义回退，避免误判 -1 全走轮询
                    memberIndex = node.path("characterId").asInt(-1);
                }
                // 说话意愿修复（B 层）：desire 收敛 1~5（与模板契约/阈值同口径）——
                // 旧版无上限，模型按旧模板「1到10」给分时会与按 1~5 标定的阈值错位
                int desire = Math.max(1, Math.min(DESIRE_MAX, node.path("desire").asInt(1)));
                boolean valid = memberIndex >= 1 && memberIndex <= candidates.size();
                if (!valid) {
                    log.warn("[群聊-调度] 第 {} 次 AI 选中成员序号={} 不在候选内（1~{}），判定无效",
                            attempt, memberIndex, candidates.size());
                    continue;
                }
                ActorConversationMember chosen = candidates.get(memberIndex - 1);
                // LOD×群聊参与度联动：参与度 ≥2 档或认知 LOD focus 档 →
                // desire +1 封顶 5——高参与/高关注角色更愿接话（真人正在聊的人更投入）。
                // 只做程序化加分，不改变 desire 解析（1~5）与阈值语义（上轮说话意愿 B/C 层不受影响）
                int baseDesire = desire;
                if (attentionLevelProvider != null && conv.getProjectId() != null && desire < DESIRE_MAX) {
                    try {
                        String ref = memberRefOf(chosen);
                        boolean focus = com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_FOCUS
                                .equals(attentionLevelProvider.tierOf(conv.getProjectId(), ref));
                        boolean highParticipation =
                                attentionLevelProvider.participationLevel(conv.getProjectId(), ref) >= 2;
                        if (focus || highParticipation) {
                            desire = Math.min(DESIRE_MAX, desire + 1);
                            log.info("[群聊-调度] 参与度联动加成：成员={} desire {}→{}（focus={} 参与度档≥2={}）",
                                    memberName(chosen), baseDesire, desire, focus, highParticipation);
                        }
                    } catch (Exception e) {
                        log.debug("[群聊-调度] 参与度联动加成失败（跳过）: {}", e.getMessage());
                    }
                }
                // 用量归属到最终选中的成员（特殊=角色 id；普通=crowd 的 npc id；两者均非空）
                usageLogService.record(userId, conv.getProjectId(), chosen.getCharacterId(),
                        result.providerId(), result.model(), "dialog",
                        usageAcc[0], usageAcc[1], usageAcc[2], usageAcc[3], 0);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("member", chosen);
                decision.put("desire", desire);
                decision.put("reason", node.path("reason").asText(""));
                return decision;
            } catch (Exception e) {
                log.warn("[群聊-调度] 第 {} 次评估失败: {}", attempt, e.getMessage());
            }
        }
        // 全部重试失败：按项目归 null 记一次（保留审计，避免调用丢失）
        usageLogService.record(userId, conv.getProjectId(), null, null, null, "dialog",
                usageAcc[0], usageAcc[1], usageAcc[2], usageAcc[3], 0);
        return null;
    }

    /**
     * 组装单个成员的对话消息：
     * <p>稳定前缀（角色 system prompt + 本地账户档案）在最前；变化段（当前状态/记忆上下文/任务/输出格式）
     * 拼入末尾 user 触发消息；输出契约=DIALOG_JSON（{@link GroupReplyParse}），群聊改非流式调用解析落库。</p>
     */
    private List<AiChatRequest.ChatMessage> buildCharacterMessages(ActorConversation conv, ActorConversationMember member,
                                                                   ActorMessage skip, Long userId,
                                                                   String chatBackground) {
        Long characterId = member.getCharacterId();
        String memberRef = memberRefOf(member);
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        // ① 稳定前缀（L1/L2 缓存层）：V2.0 段 1A 世界观简报 + 角色 system prompt + 本地账户档案
        // 批 2 对话链：世界观简报（common_knowledge 精炼常识）拼入 system 前缀（缓存友好，
        // normalizeForLlm 合并为唯一前置 system 段）
        String brief = aiContextInjector == null ? "" : aiContextInjector.worldBrief(conv.getProjectId());
        if (brief != null && !brief.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", "【世界观】\n" + brief));
        }
        messages.add(new AiChatRequest.ChatMessage("system", resolveCharacterSystemPrompt(conv, member)));
        String npcProfile = localAccountService.renderNpcProfile();
        if (!npcProfile.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", npcProfile));
        }
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        // 记忆检索 query=最近一条实质消息；纯 NPC↔NPC 自治聊天历史只有 assistant/system 角色
        // （无 user 消息）→ 兜底本场背景（事件回应轮=「世界事件后的自发回应——<事件>」/概率轮=
        // 「同地点 NPC 日常闲聊」，恒非空）——修复「自治聊天 memory_tier 恒空查询」缺陷（2026-10 长跑实测）
        String lastUserText = lastSubstantiveText(history);
        if (lastUserText == null || lastUserText.isBlank()) {
            lastUserText = chatBackground;
        }
        // ② 变化段素材（L3/L4）：记忆上下文（B5 普通型 NPC 走 character_ref 版）+ 感知 + 超窗摘要
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type）
        String context = npcTypeResolver.isCommon(conv.getProjectId(), memberRef)
                ? conversationContextService.buildContextByRef(userId, conv.getProjectId(), memberRef, lastUserText)
                : conversationContextService.buildContext(userId, conv.getProjectId(), characterId, lastUserText);
        String perception = "";
        // B2（感知）：群聊每位发言人注入自己的【当前所见】感知段
        // ：群聊感知段不重复注入【核心记忆】（L3 已由 buildContextByRef 记忆段恒在注入，避免双份）；
        // 状态段按对话开关 inject-dialog 注入【当前状态】全量段（§8.1）
        if (perceptionEnabled) {
            long perceptionStart = System.currentTimeMillis();
            try {
                // ：群聊面具对象 = 在场其他成员（本人除外）；玩家在场时含非角色成员——
                // 由 MaskContextSource 按关系/身份/场合解析情境码
                java.util.List<String> partnerRefs = new java.util.ArrayList<>();
                for (ActorConversationMember m : memberRepository.findByConversationId(conv.getId())) {
                    if (m == null || m.getId() == null || m.getId().equals(member.getId())) {
                        continue;
                    }
                    String ref = memberRefOf(m);
                    if (ref != null && !ref.isBlank()) {
                        partnerRefs.add(ref);
                    }
                }
                perception = perceptionService.buildDialogPerceptionText(conv.getProjectId(), memberRef, null,
                        partnerRefs);
                AiCallContext.recordStep(conv.getProjectId(), "dialog", "perception",
                        "群聊感知组装（" + memberRef + "）",
                        System.currentTimeMillis() - perceptionStart);
            } catch (Exception e) {
                log.warn("[Roleplay] 群聊感知段注入失败，跳过: conv={} 成员={} : {}", conv.getId(), memberRef, e.getMessage());
            }
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        String past = "";
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type）
        if (from > 0 && !npcTypeResolver.isCommon(conv.getProjectId(), memberRef)) {
            past = agentMemoryService.conversationSummaryContext(conv.getProjectId(), characterId);
        }
        // ③ 双角色上下文：对家视角（当前发言者 vs 在场其他人/上一发言者）
        String partnerContext = buildPartnerContext(conv, member, history);
        // ④ 变化段 → 单一 user 触发消息（分层组装）
        String name = memberName(member);
        LayeredPromptBuilder layered = LayeredPromptBuilder.builder();
        // 批 2 对话链：段 2D 当前世界动态精简行（世界历时刻 · 季节 · 天气）置顶——轻量确定性、零 AI
        String dynamics = aiContextInjector == null ? "" : aiContextInjector.worldDynamicsLine(conv.getProjectId());
        String currentState = (dynamics == null || dynamics.isBlank()) ? perception
                : "【当前世界动态】" + dynamics
                        + (perception == null || perception.isBlank() ? "" : PromptLayers.BLOCK_SEP + perception);
        layered.section(PromptLayers.CURRENT_STATE, currentState);
        StringBuilder memoryCtx = new StringBuilder();
        if (context != null && !context.isBlank()) {
            memoryCtx.append(context);
        }
        if (past != null && !past.isBlank()) {
            if (memoryCtx.length() > 0) {
                memoryCtx.append(PromptLayers.BLOCK_SEP);
            }
            memoryCtx.append("【过往对话摘要】").append(past);
        }
        // 批 2 对话链：段 2F few-shot 真实历史抽取（该角色近期真实对话范例 + 模板兜底）拼入记忆段
        String fewShot = aiContextInjector == null ? "" : aiContextInjector.dialogueFewShot(conv.getProjectId(), memberRef);
        if (fewShot != null && !fewShot.isBlank()) {
            if (memoryCtx.length() > 0) {
                memoryCtx.append(PromptLayers.BLOCK_SEP);
            }
            memoryCtx.append(fewShot);
        }
        layered.section(PromptLayers.MEMORY_CONTEXT, memoryCtx.toString());
        // 双角色上下文段（对家身份/状态/态度——提高角色间交互的自然与针对性）
        layered.section(PromptLayers.TASK, buildGroupReplyTask(name, partnerContext, chatBackground,
                hasNoSubstantive(history)));
        // 批 2 对话链：段 3I 行为护栏（Post-History 高权重位，TASK 之后、输出契约之前）
        if (aiContextInjector != null) {
            layered.section(PromptLayers.POST_HISTORY_GUARD, aiContextInjector.dialogueGuardRail(name));
        }
        layered.section(PromptLayers.OUTPUT_FORMAT, PromptContract.groupReplyOutputFormat());
        String trigger = layered.build();
        // ⑤ 历史消息（事件→user 角色；空回复残留过滤）
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (skip != null && m.getId().equals(skip.getId())) {
                continue;
            }
            if ("user".equals(m.getRole())) {
                messages.add(new AiChatRequest.ChatMessage("user", m.getContent() == null ? "" : m.getContent()));
            } else if ("event".equals(m.getType()) && m.getContent() != null && !m.getContent().isBlank()) {
                // 分层：事件→user 角色（避免 system 合并污染稳定前缀）
                messages.add(new AiChatRequest.ChatMessage("user", ActorMessage.EVENT_CONTEXT_PREFIX + m.getContent()));
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                // 空回复防护：历史中的空 assistant 消息（旧 500 残留，如「钱串子：」前缀空）不进入请求
                if (m.getContent() == null || m.getContent().isBlank()) {
                    continue;
                }
                String nm = memberNameOf(m.getCharacterId(), conv);
                String content = (nm == null ? "" : nm + "：")
                        + (m.getContent() == null ? "" : m.getContent());
                messages.add(new AiChatRequest.ChatMessage("assistant", content));
            }
        }
        if (!trigger.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("user", trigger));
        }
        // llama.cpp 兼容归一化：合并相邻 system + 结构校验
        return normalizeForLlm(messages);
    }

    /**
     * 群聊 TASK 段：当前轮到谁发言 + 对家上下文（双角色分层——当前发言者
     * 以「对家」视角感知在场其他人身份/状态/态度，输出更具针对性）。
     *
     * @param speaker 当前发言者名
     *
     * @param partnerContext 对家上下文段（可空）
     *
     * @return TASK 段文本
     */
    private String buildGroupReplyTask(String speaker, String partnerContext) {
        return buildGroupReplyTask(speaker, partnerContext, null, false);
    }

    /**
     * 群聊 TASK 段：当前轮到谁发言 + 对家上下文 +
     * <b>本场背景</b>（触发本次交谈的缘由）。
     * <p>开场轮（本场尚无实质发言）时额外给出「由你起头」的明确指令——此前开场发言者的 TASK 段
     * 只写「针对上一位发言者回应」，而场上没有任何人说过话（且上下文为空），模型无据可依。</p>
     *
     * @param speaker 当前发言者名
     *
     * @param partnerContext 对家上下文段（可空）
     *
     * @param chatBackground 本场背景（可空=不注入）
     *
     * @param kickoffSpeaker 是否开场发言者（本场尚无实质发言）
     *
     * @return TASK 段文本
     */
    private String buildGroupReplyTask(String speaker, String partnerContext, String chatBackground,
                                       boolean kickoffSpeaker) {
        String who = speaker == null || speaker.isBlank() ? "你" : speaker;
        StringBuilder sb = new StringBuilder();
        sb.append(PromptContract.groupReplyTask(who));
        if (chatBackground != null && !chatBackground.isBlank()) {
            sb.append(PromptLayers.BLOCK_SEP).append("【本场背景】").append(chatBackground);
            if (kickoffSpeaker) {
                sb.append(PromptLayers.BLOCK_SEP)
                        .append("本场还没有人开口，由你来起头：请围绕上面的背景/话题自然开口")
                        .append("（可以是议论、感慨、招呼、搭话或起个话头），不要等别人先说话。");
            }
        }
        if (partnerContext != null && !partnerContext.isBlank()) {
            sb.append(PromptLayers.BLOCK_SEP).append(partnerContext);
        }
        sb.append(PromptLayers.BLOCK_SEP)
                .append("对话历史中【角色名：】前缀代表其他角色的发言，请针对他们的最新发言做出针对性回应。")
                .append("只回复这一轮，严格只用第一人称扮演【").append(who).append("】：只说你自己的话，")
                .append("不要替其他角色发言，不要代答，不要转述或标注其他角色的台词。");
        return sb.toString();
    }

    /**
     * 对家上下文段：最近一条非本人发言者（或玩家）的身份/状态摘要，
     * 让当前发言者以「对家」视角感知说话对象——提高角色间交互的自然度与针对性（双角色分层）。
     *
     * @param conv 会话实体
     *
     * @param member 当前发言成员
     *
     * @param history 会话历史（取最近非本人发言者）
     *
     * @return 对家上下文段（无可识别对家/组装失败返回空串）
     */
    private String buildPartnerContext(ActorConversation conv, ActorConversationMember member,
                                       List<ActorMessage> history) {
        try {
            if (history == null || history.isEmpty()) {
                return "";
            }
            Long selfId = member.getCharacterId();
            Long partnerId = null;
            for (int i = history.size() - 1; i >= Math.max(0, history.size() - 8); i--) {
                ActorMessage m = history.get(i);
                if (m == null || m.getCharacterId() == null || m.getCharacterId().equals(selfId)) {
                    continue;
                }
                if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                    partnerId = m.getCharacterId();
                    break;
                }
            }
            if (partnerId == null) {
                return "";
            }
            String partnerName = characterName(partnerId);
            String partnerRef = memberRefOf(conv, partnerId);
            String state = npcStateService == null ? "" : npcStateService.stateSegment(conv.getProjectId(), partnerRef, null);
            StringBuilder sb = new StringBuilder("【对家】").append(partnerName == null ? "对方" : partnerName);
            if (state != null && !state.isBlank()) {
                sb.append("（").append(state.trim()).append("）");
            }
            sb.append(" 是与你对话的对象。");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[Roleplay] 对家上下文组装失败（跳过）: conv={} : {}", conv.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 生成全体成员角色摘要（收编 {@code GroupChatService.buildMembersSummary}，行为不变；B5 含普通 NPC）。
     */
    private String buildMembersSummary(ActorConversation conv, List<ActorConversationMember> members) {
        StringBuilder sb = new StringBuilder();
        for (ActorConversationMember m : members) {
            // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
            if (com.holzyn.actor.common.NpcRefs.isCommonType(m.getMemberType())) {
                ActorCharacter npc = m.getCharacterId() == null ? null
                        : npcRepository.findById(m.getCharacterId()).orElse(null);
                if (npc == null) {
                    continue;
                }
                sb.append("成员序号: ").append(memberName(m))
                        .append("（普通居民");
                if (npc.getOccupation() != null && !npc.getOccupation().isBlank()) {
                    sb.append("，职业 ").append(npc.getOccupation());
                }
                if (npc.getAffiliation() != null && !npc.getAffiliation().isBlank()) {
                    sb.append("，归属 ").append(npc.getAffiliation());
                }
                if (npc.getLocation() != null && !npc.getLocation().isBlank()) {
                    sb.append("，现居 ").append(npc.getLocation());
                }
                sb.append("）\n");
                continue;
            }
            ActorCharacter ch = m.getCharacterId() == null ? null
                    : characterRepository.findById(m.getCharacterId()).orElse(null);
            if (ch == null) {
                continue;
            }
            sb.append("成员序号: ").append(ch.getName())
                    .append(ch.getTitle() == null || ch.getTitle().isBlank() ? "" : "（" + ch.getTitle() + "）")
                    .append("；重要度 ").append(ch.getImportance() == null ? 1 : ch.getImportance());
            String summary = (personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null));
            if (summary != null && !summary.isBlank()) {
                try {
                    JsonNode p = objectMapper.readTree(summary);
                    List<String> bits = new ArrayList<>();
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 4, p, "dims", "personality", "traits_summary");
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 4, p, "dims", "values", "life_goals");
                    com.holzyn.actor.domain.growth.persona.PersonaBits.appendAll(bits, 4, p, "dims", "habits", "micro_movements");
                    if (!bits.isEmpty()) {
                        sb.append("；人设：").append(String.join("、", bits));
                    }
                } catch (Exception ignored) {
                    // 角色卡解析失败时忽略人设摘要
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成最近对话上下文（供群聊编排系统提示； 收编 {@code GroupChatService.buildContext}，行为不变）。
     */
    private String buildContext(ActorConversation conv) {
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if ("user".equals(m.getRole())) {
                sb.append("玩家：").append(m.getContent() == null ? "" : m.getContent()).append("\n");
            } else if ("event".equals(m.getType()) && m.getContent() != null) {
                sb.append(ActorMessage.EVENT_CONTEXT_PREFIX).append(m.getContent()).append("\n");
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus()) && m.getContent() != null) {
                sb.append(characterName(m.getCharacterId())).append("：").append(m.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 本场是否尚无「角色发言」（开场轮判定， 说话意愿修复 C/A 层）：
     * 无玩家消息、无已完成的角色发言（role=assistant 且 status=done 且有内容）即为开场。
     * <p>注意：① 流式占位消息（status=streaming、内容为空）与系统叙述（退场/收尾 system 文本）
     * 不算发言；② <b>世界事件消息（type=event）是「情境」而不是「有人说过话」</b>——只被注入了
     * 事件、还没人开口的会话仍属开场轮（否则「事件注入 → 无人接话 → 0 条消息归档」的老问题会
     * 在事件路由命中 NPC 内群时复现）。</p>
     * <p>查询失败 → 返回 false（视为非开场，保持旧语义，零阻断）。</p>
     *
     * @param conversationId 会话主键
     *
     * @return true=本场尚无角色发言（开场轮）
     */
    private boolean hasNoSubstantiveMessages(Long conversationId) {
        try {
            return hasNoSubstantive(messageRepository.findByConversationIdOrderByIdAsc(conversationId));
        } catch (Exception e) {
            log.debug("[Roleplay] 开场轮判定失败（按非开场处理）: conv={} : {}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 列表口径的开场轮判定（见 {@link #hasNoSubstantiveMessages(Long)}）。
     *
     * @param history 会话消息（可空）
     *
     * @return true=尚无实质发言
     */
    private static boolean hasNoSubstantive(List<ActorMessage> history) {
        if (history == null || history.isEmpty()) {
            return true;
        }
        for (ActorMessage m : history) {
            if (m == null) {
                continue;
            }
            boolean hasText = m.getContent() != null && !m.getContent().isBlank();
            if (!hasText) {
                continue;
            }
            if ("user".equals(m.getRole())) {
                return false;
            }
            if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 最近一条「已完成角色发言」的成员 ref（防自言自语调度用）：倒序取最后一条
     * role=assistant、status=done、有 characterId 的消息 → {@code npc:{id}}。
     * 供 {@link #pickNext} 回避「上一成功发言者连续再发言」（候选人 ≥2 时）。
     *
     * @param conv 会话实体
     *
     * @return 上一成功发言成员 ref（无可取返回 null）
     */
    private String lastDoneAssistantRef(ActorConversation conv) {
        try {
            if (conv == null || conv.getId() == null) {
                return null;
            }
            List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
            for (int i = history.size() - 1; i >= 0; i--) {
                ActorMessage m = history.get(i);
                if (m != null && m.getCharacterId() != null
                        && "assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                    return com.holzyn.actor.common.NpcRefs.of(m.getCharacterId());
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[Roleplay] 上一发言者判定失败（按无上一发言者处理）: conv={} : {}",
                    conv == null ? null : conv.getId(), e.getMessage());
            return null;
        }
    }

    // ==================== 演化编排（收编 WorldEvolutionService.orchestrate/aiSelect） ====================

    /**
     * AI 编排一轮世界演化（收编 {@code WorldEvolutionService.orchestrate}，行为不变）。
     * <p>入参用基本参数解耦（不依赖 world 域实体）：调用方（WorldEvolutionService 壳）传入
     * 演化会话的 id/sceneId/background 与在场角色 ID 列表。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param evolutionId 演化会话 ID（历史拍查询）
     *
     * @param sceneId 场景 ID（可空=全局演化）
     *
     * @param background 演化背景设定（可空）
     *
     * @param activeIds 在场角色 ID 列表
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」紧凑摘要（场景化演化重构；可空=不注入，
     * 导演编排读世界帧作事实源——不凭空编造世界状态）
     *
     * @return 编排计划（messages/sceneEvent/joins/leaves/shouldFinish/finishReason/summary）
     */
    public Map<String, Object> orchestrateEvolution(Long userId, Long projectId, Long evolutionId,
                                                    Long sceneId, String background, List<Long> activeIds,
                                                    String worldFrameSummary) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(sceneId, background);
        String charactersText = activeIds.stream()
                .map(cid -> "ID " + cid + " " + charName(cid) + "\n" + personaSummary(cid))
                .collect(Collectors.joining("\n\n"));
        String history = turnRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .limit(EVOLUTION_CONTEXT_WINDOW)
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (history.isBlank()) history = "（暂无经过）";

        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_EVOLUTION, Map.of(
                        "world_setting", worldSetting.isBlank() ? "（未提供）" : worldSetting,
                        "scene_background", sceneBackground,
                        "characters", charactersText,
                        "history", history,
                        "evolution_background", background == null ? "" : background,
                        "world_frame", worldFrameSummary == null || worldFrameSummary.isBlank()
                                ? "（当前无生效世界帧）" : worldFrameSummary));

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, EVOLVE_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        AiChatResult result = AiCallContext.run("world_event", null, () ->
                aiChatGateway.chatCompletion(userId, projectId, null, req));
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "action",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("messages", List.of());
        plan.put("sceneEvent", "");
        plan.put("joins", List.of());
        plan.put("leaves", List.of());
        plan.put("shouldFinish", false);
        plan.put("finishReason", "");
        plan.put("summary", "");
        Map<String, Object> parsed = parsePlan(result.content(), activeIds);
        if (parsed == null) {
            if (!activeIds.isEmpty()) {
                plan.put("messages", List.of(Map.of(
                        "characterId", activeIds.get(0),
                        "type", "text",
                        "content", "……（沉默片刻）")));
            }
            return plan;
        }
        plan.putAll(parsed);
        return plan;
    }

    /**
     * AI 选角。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param requestedSceneId 用户指定场景（可空）
     *
     * @param requestedBackground 用户指定背景（可空）
     *
     * @param requestedCharacterIds 用户指定角色（可空）
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」紧凑摘要
     */
    public Map<String, Object> selectEvolutionCast(Long userId, Long projectId, Long requestedSceneId,
                                                   String requestedBackground, List<Long> requestedCharacterIds,
                                                   String worldFrameSummary) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        List<ActorScene> scenes = sceneRepository.findByProjectIdOrderByEnabledDescIdAsc(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        List<ActorCharacter> candidates = chars.stream()
                .filter(c -> (personaService != null && personaService.latestPersonaJson(c.getId()).isPresent()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = chars;
        }
        String sceneText = scenes.isEmpty() ? "（无预设场景，可全局演化）"
                : scenes.stream().map(s -> "ID " + s.getId() + " " + s.getName()
                        + (s.getDescription() == null ? "" : "：" + s.getDescription()))
                        .collect(Collectors.joining("\n"));
        String charText = candidates.stream()
                .map(c -> "ID " + c.getId() + " " + c.getName() + (c.getTitle() == null ? "" : "（" + c.getTitle() + "）"))
                .collect(Collectors.joining("\n"));

        String prompt = """
                你是一位虚构世界的「选角导演」。请为一场世界演化选择场景、背景与参与角色，输出一个 JSON 对象，禁止输出其他文字。

                —— 输出结构 ——
                { "sceneId": 场景ID或null（null=全局/世界演化）, "background": "演化背景设定（一段话，含此刻的情境/氛围/为什么这些人在这里）",
                  "characterIds": [角色ID数组（2-4位，出场必须有剧情理由）], "title": "演化标题（一句话）", "reason": "选角理由（50字内）" }

                —— 要求 ——
                1. 若用户已指定场景/角色，必须优先沿用，不要擅自更换。
                2. 选角要符合世界观与角色人设：优先选有剧情关联、能产生互动的主要角色。
                3. background 要有具体情境（时间地点氛围），为角色互动提供自然动机。
                4. 场景/角色 ID 必须从下列列表中选取（sceneId 可为 null=全局）。
                5. 今日世界状态是当前世界的真实事实（导演上帝视角），选角与背景设定必须与它一致，
                   不凭空编造与它冲突的世界状态；需要呼应今日宏观事件时可围绕它们设计情境。

                —— 世界设定 ——
                %s

                —— 今日世界状态（当前世界帧） ——
                %s

                —— 可选场景 ——
                %s

                —— 候选角色 ——
                %s
                """.formatted(worldSetting.isBlank() ? "（未提供）" : worldSetting,
                worldFrameSummary == null || worldFrameSummary.isBlank() ? "（当前无生效世界帧）" : worldFrameSummary,
                sceneText, charText);

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, EVOLVE_MAX_TOKENS, true);
        try {
            AiChatResult result = AiCallContext.run("world_event", null, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null, req));
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "action",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json != null) {
                JsonNode node = objectMapper.readTree(json);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("sceneId", node.path("sceneId").isNull() ? null : node.path("sceneId").asLong());
                out.put("background", node.path("background").asText(""));
                out.put("title", node.path("title").asText(""));
                out.put("reason", node.path("reason").asText(""));
                List<Long> picked = new ArrayList<>();
                for (JsonNode idNode : node.path("characterIds")) {
                    long cid = idNode.asLong(-1);
                    if (cid > 0 && candidates.stream().anyMatch(c -> c.getId().equals(cid)) && !picked.contains(cid)) {
                        picked.add(cid);
                    }
                }
                if (picked.isEmpty() && !candidates.isEmpty()) {
                    picked = candidates.stream()
                            .sorted(java.util.Comparator.comparingInt((ActorCharacter c) ->
                                    Integer.valueOf(1).equals(c.getIsProtagonist()) ? 0 : 1)
                                    .thenComparingInt(c -> c.getImportance() == null ? 1 : -c.getImportance()))
                            .limit(3).map(ActorCharacter::getId).toList();
                }
                out.put("characterIds", picked);
                log.info("[演化] AI 选角：项目={} 场景={} 角色={} 理由={}", projectId,
                        out.get("sceneId") == null ? "全局" : out.get("sceneId"), picked,
                        out.get("reason"));
                return out;
            }
        } catch (Exception e) {
            log.warn("[Roleplay] 演化 AI 选角失败，使用兜底: {}", e.getMessage());
        }
        // 兜底：用户指定优先；场景取第一个；角色取前 3
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sceneId", requestedSceneId != null ? requestedSceneId
                : (scenes.isEmpty() ? null : scenes.get(0).getId()));
        out.put("background", requestedBackground);
        out.put("title", "");
        List<Long> picked = requestedCharacterIds;
        if (picked == null || picked.isEmpty()) {
            picked = candidates.stream().limit(3).map(ActorCharacter::getId).toList();
        }
        out.put("characterIds", picked);
        return out;
    }

    /**
     * 解析 AI 编排输出（静态纯逻辑， 收编 {@code WorldEvolutionService.parsePlan}，可单测）。
     * <p>对齐 {@link #parseSchedule} 为 public static：世界域机制服务测试跨包引用该纯逻辑。</p>
     */
    public static Map<String, Object> parsePlan(String json, List<Long> activeIds) {
        if (json == null) {
            return null;
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            Map<String, Object> plan = new LinkedHashMap<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (JsonNode m : node.path("messages")) {
                Long cid = m.path("characterId").asLong(-1);
                if (cid <= 0 || activeIds == null || !activeIds.contains(cid)) {
                    continue;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("characterId", cid);
                msg.put("type", "text".equals(m.path("type").asText("text")) ? "text" : "action");
                msg.put("content", m.path("content").asText(""));
                if (msg.get("content") != null && !String.valueOf(msg.get("content")).isBlank()) {
                    messages.add(msg);
                }
            }
            if (messages.isEmpty() && activeIds != null && !activeIds.isEmpty()) {
                messages.add(Map.of("characterId", activeIds.get(0), "type", "text", "content", "……"));
            }
            plan.put("messages", messages);
            plan.put("sceneEvent", node.path("sceneEvent").asText(""));
            plan.put("joins", nodeArrayOf(node, "joins"));
            plan.put("leaves", nodeArrayOf(node, "leaves"));
            plan.put("shouldFinish", node.path("shouldFinish").asBoolean(false));
            plan.put("finishReason", node.path("finishReason").asText(""));
            plan.put("summary", node.path("summary").asText(""));
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 joins/leaves 数组（静态，{characterId, reason}）。
     */
    private static List<Map<String, Object>> nodeArrayOf(JsonNode node, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("characterId", item.path("characterId").asLong(-1));
            m.put("reason", item.path("reason").asText(""));
            out.add(m);
        }
        return out;
    }

    // ==================== 演化流式编排（收编 WorldEvolutionService.scheduleBeat/generateBeat，基础参数解耦 world 域实体） ====================

    /**
     * 演化逐拍调度（收编 {@code WorldEvolutionService.scheduleBeat}，行为不变）：AI 根据世界观/场景/
     * 在场角色/最近剧情，选出本拍角色与类型（对话 text / 行为 action），并可选输出场景变化与加入/退场。
     * <p>健壮性：AI 调用或解析失败时重试 {@link #EVOLVE_SCHEDULE_MAX_RETRY} 次，重试耗尽按在场顺序轮询兜底。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param evolutionId 演化会话 ID（历史拍查询）
     *
     * @param sceneId 场景 ID（可空=全局演化）
     *
     * @param background 演化背景设定（可空）
     *
     * @param activeCharacterIds 在场角色 ID 列表
     *
     * @param turnCount 已推进拍数（轮询兜底偏移，防独角戏）
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」紧凑摘要
     *
     * @return 调度决策 Map；AI 不可用/解析失败时返回轮询兜底决策
     */
    public Map<String, Object> scheduleEvolutionBeat(Long userId, Long projectId, Long evolutionId,
                                                     Long sceneId, String background,
                                                     List<Long> activeCharacterIds, Integer turnCount,
                                                     String worldFrameSummary) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(sceneId, background);
        String charactersText = activeCharacterIds.stream()
                .map(cid -> "ID " + cid + " " + charName(cid) + "\n" + personaSummary(cid))
                .collect(Collectors.joining("\n\n"));
        String history = turnRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .limit(EVOLUTION_CONTEXT_WINDOW)
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (history.isBlank()) {
            history = "（剧情刚开始，还没有任何发展）";
        }

        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_EVOLUTION_SCHEDULE, Map.of(
                        "world_setting", worldSetting.isBlank() ? "（未提供）" : worldSetting,
                        "scene_background", sceneBackground,
                        "characters", charactersText,
                        "history", history,
                        "world_frame", worldFrameSummary == null || worldFrameSummary.isBlank()
                                ? "（当前无生效世界帧）" : worldFrameSummary));
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, EVOLVE_SCHEDULE_MAX_TOKENS, true);

        Map<String, Object> decision = null;
        for (int attempt = 1; attempt <= EVOLVE_SCHEDULE_MAX_RETRY; attempt++) {
            try {
                long startMs = System.currentTimeMillis();
                AiChatResult result = AiCallContext.run("world_event", null, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req));
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                        "action", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - startMs));
                decision = parseSchedule(result.content(), activeCharacterIds);
                if (decision != null) {
                    break;
                }
                log.warn("[演化-流] 第 {} 次调度输出无法解析（在场角色ID={}）：原始输出（前 300 字）={}",
                        attempt, activeCharacterIds, truncate(result.content(), 300));
            } catch (Exception e) {
                log.warn("[演化-流] 第 {} 次调度 AI 调用失败: {}", attempt, e.getMessage());
            }
        }
        if (decision == null) {
            log.warn("[演化-流] 调度重试耗尽，按在场顺序轮询兜底：在场角色ID={}", activeCharacterIds);
            return fallbackEvolutionSchedule(turnCount, activeCharacterIds);
        }
        log.info("[演化-流] 调度：演化={} 选中角色ID={} desire={} 类型={} 理由={} 场景变化={} 加入={} 退场={}",
                evolutionId, decision.get("characterId"), decision.get("desire"),
                decision.get("beatType"), decision.get("reason"), decision.get("sceneEvent"),
                decision.get("joins"), decision.get("leaves"));
        return decision;
    }

    /**
     * 解析逐拍调度输出（静态纯逻辑， 收编 {@code WorldEvolutionService.parseSchedule}，可单测）。
     * <p>规则：characterId 必须在场；desire 收敛 1~5；beatType 收敛 text/action；sceneEvent/joins/leaves 透传。
     * 无效 JSON 或选中不在场角色返回 null。</p>
     *
     * @param json AI 输出文本
     *
     * @param activeIds 在场角色 ID 列表
     *
     * @return 调度决策 Map；无有效 JSON 返回 null
     */
    public static Map<String, Object> parseSchedule(String json, List<Long> activeIds) {
        if (json == null) {
            return null;
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            long cid = node.path("characterId").asLong(-1);
            if (cid <= 0 || activeIds == null || !activeIds.contains(cid)) {
                return null;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("characterId", cid);
            d.put("desire", Math.max(1, Math.min(5, node.path("desire").asInt(3))));
            d.put("reason", node.path("reason").asText(""));
            d.put("beatType", "action".equals(node.path("beatType").asText("text")) ? "action" : "text");
            d.put("sceneEvent", node.path("sceneEvent").asText(""));
            d.put("joins", nodeArrayOf(node, "joins"));
            d.put("leaves", nodeArrayOf(node, "leaves"));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 演化调度兜底（收编 {@code WorldEvolutionService.fallbackSchedule}）：AI 不可用时按在场顺序
     * <strong>轮询</strong>选角色（用 turnCount 做轮转偏移），保证演化持续推进不中断。
     *
     * @param turnCount 已推进拍数（轮转偏移）
     *
     * @param activeCharacterIds 在场角色 ID 列表
     *
     * @return 兜底调度决策
     */
    private Map<String, Object> fallbackEvolutionSchedule(Integer turnCount, List<Long> activeCharacterIds) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (activeCharacterIds.isEmpty()) {
            d.put("characterId", null);
            d.put("desire", 3);
            d.put("reason", "无在场角色");
            d.put("beatType", "text");
            d.put("sceneEvent", "");
            d.put("joins", List.of());
            d.put("leaves", List.of());
            return d;
        }
        int idx = (turnCount == null ? 0 : turnCount) % activeCharacterIds.size();
        d.put("characterId", activeCharacterIds.get(idx));
        d.put("desire", 3);
        d.put("reason", "调度暂不可用，按在场顺序轮询推进");
        d.put("beatType", "text");
        d.put("sceneEvent", "");
        d.put("joins", List.of());
        d.put("leaves", List.of());
        return d;
    }

    /**
     * 演化单拍发言/行动生成（收编 {@code WorldEvolutionService.generateBeat}，行为不变）：
     * 流式 AI 生成 → SSE 推送 → 落库本拍轮次消息 + 用量。
     *
     * @param emitter SSE 发射器
     *
     * @param evolutionId 演化会话 ID
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID（可空）
     *
     * @param background 演化背景（可空）
     *
     * @param characterId 发言/行动角色 ID
     *
     * @param userId 归属用户 ID
     *
     * @param beatType text 对话 / action 行为
     *
     * @param alive 连接存活标志
     *
     * @param scheduleReason 调度理由（注入指令，可空）
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」紧凑摘要
     *
     * @return true 成功；false 失败（连接断开/生成失败）
     */
    public boolean generateEvolutionBeat(SseEmitter emitter, Long evolutionId, Long projectId, Long sceneId,
                                         String background, Long characterId, Long userId, String beatType,
                                         AtomicBoolean alive, String scheduleReason, String worldFrameSummary) {
        String speaker = charName(characterId);
        log.info("[演化-流] 角色={}（ID={}）开始{}：演化={}", speaker, characterId,
                "action".equals(beatType) ? "行动" : "发言", evolutionId);
        pushEvent(emitter, "message-start",
                Map.of("characterId", characterId, "characterName", speaker, "type", beatType));
        long startMs = System.currentTimeMillis();
        String content = "";
        int[] usage = new int[4]; // 0=prompt 1=completion 2=cacheHit 3=cacheMiss
        try {
            List<AiChatRequest.ChatMessage> msgs = buildEvolutionBeatMessages(evolutionId, projectId, sceneId,
                    background, characterId, userId, beatType, scheduleReason, worldFrameSummary);
            for (int attempt = 1; attempt <= EVOLVE_BEAT_MAX_RETRY && alive.get(); attempt++) {
                StringBuilder full = new StringBuilder();
                usage[0] = usage[1] = usage[2] = usage[3] = 0;
                try {
                    AiCallContext.run("world_event", characterId, () -> {
                        aiChatGateway.chatCompletionStream(userId, projectId, null,
                                new AiChatRequest(null, msgs, 0.8, EVOLVE_BEAT_REPLY_MAX_TOKENS),
                                delta -> {
                                    if (!alive.get()) {
                                        throw new RuntimeException("SSE 连接已断开");
                                    }
                                    full.append(delta);
                                    sendToken(emitter, delta, characterId);
                                },
                                usageInfo -> {
                                    usage[0] = usageInfo.promptTokens();
                                    usage[1] = usageInfo.completionTokens();
                                    usage[2] = usageInfo.cacheHitTokens();
                                    usage[3] = usageInfo.cacheMissTokens();
                                });
                        return null;
                    });
                } catch (Exception e) {
                    String errMsg = e.getMessage() == null ? "" : e.getMessage();
                    if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                        return false; // 连接断开，安静结束（不重试）
                    }
                    throw e;
                }
                content = full.toString().trim();
                if (!content.isBlank()) {
                    break;
                }
                log.warn("[演化-流] 第 {} 次角色={} 输出为空（completionTokens={}），重试生成", attempt, speaker, usage[1]);
            }
            if (content.isBlank()) {
                content = "（" + speaker + "沉默了片刻，似乎在酝酿着什么。）";
                log.warn("[演化-流] 角色={} 多次输出为空，使用程序化兜底台词", speaker);
            }
            addEvolutionTurn(evolutionId, characterId, "assistant", beatType, content);
            pushEvent(emitter, "done", Map.of("characterId", characterId, "type", beatType, "content", content,
                    "tokenIn", usage[0], "tokenOut", usage[1]));
            usageLogService.record(userId, projectId, characterId, null, null, "action",
                    usage[0], usage[1], usage[2], usage[3], (int) (System.currentTimeMillis() - startMs));
            log.info("[演化-流] 角色={}：{}", speaker, content);
            log.info("[演化-流] 角色={} {}完成：耗时={}ms tokens={}/{}", speaker,
                    "action".equals(beatType) ? "行动" : "发言",
                    System.currentTimeMillis() - startMs, usage[0], usage[1]);
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                return false;
            }
            log.warn("[演化-流] 角色生成失败：演化={} char={} : {}", evolutionId, characterId, e.getMessage());
            sendError(emitter, friendlyError(e));
            return false;
        }
    }

    /**
     * 组装单个角色的演化本拍消息（收编 {@code WorldEvolutionService.buildBeatMessages}，行为不变）：
     * 角色卡 system_prompt + 世界观/场景背景 + 长期记忆 + 最近剧情 + 本拍指令（区分对话/行为）。
     *
     * @param evolutionId 演化会话 ID（历史拍查询）
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID（可空）
     *
     * @param background 演化背景（可空）
     *
     * @param characterId 发言/行动角色 ID
     *
     * @param userId 归属用户 ID（记忆注入凭据归属）
     *
     * @param beatType text 对话 / action 行为
     *
     * @param scheduleReason 调度理由（可空）
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」紧凑摘要
     *
     * @return 消息序列
     */
    private List<AiChatRequest.ChatMessage> buildEvolutionBeatMessages(Long evolutionId, Long projectId,
                                                                       Long sceneId, String background,
                                                                       Long characterId, Long userId,
                                                                       String beatType, String scheduleReason,
                                                                       String worldFrameSummary) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", resolveEvolutionCharacterSystemPrompt(characterId)));

        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(sceneId, background);
        String gameTime = currentGameTimeText(projectId);
        StringBuilder ctx = new StringBuilder();
        ctx.append("这是一场场景化世界演化（非普通对话），发生在特定的场景/地点中。你的一举一动都在场景里发生、可被其他角色观察到；")
                .append("说话用第一人称，行动用第三人称描述行为（可夹在对话中，如：（她端起咖啡杯））。");
        if (!worldSetting.isBlank()) {
            ctx.append("\n\n【世界观】\n").append(worldSetting);
        }
        if (gameTime != null && !gameTime.isBlank()) {
            ctx.append("\n\n【当前游戏时刻】").append(gameTime);
        }
        ctx.append("\n\n【场景背景】\n").append(sceneBackground.isBlank() ? "（全局演化，无固定场景）" : sceneBackground);
        // 场景化演化重构：注入当前世界帧「今日世界状态」（导演上帝视角事实源；空=不注入）
        if (worldFrameSummary != null && !worldFrameSummary.isBlank()) {
            ctx.append("\n\n【今日世界状态（当前世界帧）】\n").append(worldFrameSummary);
        }
        messages.add(new AiChatRequest.ChatMessage("system", ctx.toString()));

        List<ActorEvolutionTurn> history = turnRepository.findByEvolutionIdOrderByIdAsc(evolutionId);
        int from = Math.max(0, history.size() - EVOLUTION_CONTEXT_WINDOW);
        // 记忆检索 query=最近剧情（tier 判定与 L2 语义检索的情境；无剧情回退场景背景，上限 300 字符——
        // 修复「演化注入空查询→tier 恒 LOW + L2 相关性=0」缺陷，2026-09-30 长跑实测）
        String memoryQuery = history.isEmpty() ? null : history.get(history.size() - 1).getContent();
        if (memoryQuery == null || memoryQuery.isBlank()) {
            memoryQuery = background;
        }
        if (memoryQuery != null && memoryQuery.length() > 300) {
            memoryQuery = memoryQuery.substring(0, 300);
        }
        String memory = agentMemoryService.memoryContext(userId, projectId, characterId, memoryQuery, null);
        if (!memory.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", memory));
        }

        for (int i = from; i < history.size(); i++) {
            ActorEvolutionTurn t = history.get(i);
            String name = t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId());
            messages.add(new AiChatRequest.ChatMessage("system", name + "：" + t.getContent()));
        }

        String speaker = charName(characterId);
        String instruction = "当前轮到【" + speaker + "】。这一拍是【"
                + ("action".equals(beatType) ? "行为" : "对话") + "】。"
                + ("action".equals(beatType)
                ? "请主要用行动/行为推进剧情（如站起身、走向某物、拿起/放下、望向某人、做某个表情或动作），可辅以简短台词，用第三人称描述。"
                : "请主要用第一人称说话，自然接续最近剧情，回应在场角色的最新言行，可夹带动作描写（用括号）。")
                + "结合【场景背景】【世界观】与最近剧情，像真实社交中身处该场景的角色一样反应，有互动、有情绪、逻辑自洽，不要机械重复。"
                + "不要替其他角色发言，不要转述其他角色的台词，不要以【】或『某某说：』开头。";
        if (scheduleReason != null && !scheduleReason.isBlank()) {
            instruction = instruction + "\n（调度理由：" + scheduleReason + "）";
        }
        messages.add(new AiChatRequest.ChatMessage("system", instruction));
        // llama.cpp 兼容归一化：合并相邻 system（含逐拍历史）+ 末尾指示 system 转 user（防多条连续 system 空响应）
        return normalizeForLlm(messages);
    }

    /**
     * 解析角色对话系统 Prompt（最新角色卡渲染的 system_prompt，无卡时降级；演化拍使用）。
     */
    private String resolveEvolutionCharacterSystemPrompt(Long characterId) {
        // ：角色卡 system_prompt 已废弃——改为分片渲染档案（单聊场景），无档案时用通用兜底身份句。
        String personaShards = personaService == null ? "" : personaService.renderShardsForScene(characterId, "dialog_system");
        return personaShards == null || personaShards.isBlank()
                ? "你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。"
                : personaShards;
    }

    /**
     * 当前项目游戏时刻文本（供演化本拍上下文注入；时钟读取失败返回空串）。
     * <p>时间口径统一：取<b>引擎推进位置</b>（世界权威时间），不再按真实锚点 × 速率
     * 流动换算——后者与引擎位置是并行时间源，恢复基线后相差约 120 游戏日，Prompt 时间感会与
     * 玩家可见时钟/昼夜光照互相矛盾。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 游戏时刻文本（如「清晨 · 第 3 日」）
     */
    private String currentGameTimeText(Long projectId) {
        try {
            Long hour = worldClockService.currentGameHour(projectId);
            return hour == null ? "" : WorldClockService.formatGameTime(hour);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 演化轮次消息（本拍落库； 收编自 WorldEvolutionService.addTurn）。
     *
     * @param evolutionId 演化会话 ID
     *
     * @param characterId 角色 ID（可空=系统消息）
     *
     * @param role 消息角色（assistant/system）
     *
     * @param type 消息类型（text/action/system）
     *
     * @param content 内容
     */
    private void addEvolutionTurn(Long evolutionId, Long characterId, String role, String type, String content) {
        ActorEvolutionTurn t = new ActorEvolutionTurn();
        t.setEvolutionId(evolutionId);
        t.setCharacterId(characterId);
        t.setRole(role);
        t.setType(type);
        t.setContent(content);
        turnRepository.save(t);
    }

    /**
     * 推送 SSE 命名事件（连接断开时抛 RuntimeException 终止循环；演化流式使用）。
     */
    private void pushEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException("SSE 连接已断开", e);
        }
    }

    // ==================== crowd 渲染与成员（B5，复用 RoleplayContextService） ====================

    /**
     * 解析成员的角色 system_prompt（成员类型化渲染； 复用 RoleplayContextService）。
     */
    public String resolveCharacterPrompt(Long projectId, String memberType, Long memberId) {
        return roleplayContextService.resolveCharacterPrompt(projectId, memberType, memberId);
    }

    /**
     * 确认普通型 NPC 已入会话（， 桥接）：校验会话内存在 member_type=common（历史 crowd）的该成员。
     *
     * @param conversationId 会话 ID
     *
     * @param crowdRef 统一角色引用（{@code npc:{id}}）
     *
     * @return true=成员在会话内且为普通型 NPC
     */
    public boolean ensureCrowdMember(Long conversationId, String crowdRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type；无 projectId 走 null=不做项目校验）
        if (conversationId == null || crowdRef == null || !npcTypeResolver.isCommon(null, crowdRef)) {
            return false;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long npcId = com.holzyn.actor.common.NpcRefs.idOf(crowdRef);
        if (npcId == null) {
            return false; // 非法引用（保持原有 null → false 的处理）
        }
        try {
            // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值与 common 口径兼容）
            return memberRepository.findByConversationId(conversationId).stream()
                    .anyMatch(m -> com.holzyn.actor.common.NpcRefs.isCommonType(m.getMemberType())
                            && npcId.equals(m.getCharacterId()));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 私有辅助（单聊/群聊， 收编） ====================

    /**
     * 解析单聊角色的 system_prompt（取第一个成员的类型化渲染，B5）。
     */
    private String resolveSystemPrompt(ActorConversation conv) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
        if (members.isEmpty()) {
            return "你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。";
        }
        ActorConversationMember first = members.get(0);
        // ：类型值字面量统一走 NpcRefs.normalizeType（member_type 既有值以 common 为准）
        String type = com.holzyn.actor.common.NpcRefs.normalizeType(first.getMemberType());
        return roleplayContextService.resolveCharacterPrompt(conv.getProjectId(), type, first.getCharacterId());
    }

    /**
     * 解析会话成员对应的统一角色引用（B5：统一单前缀 {@code npc:{id}}）。
     */
    private String memberRefOf(ActorConversation conv, Long characterId) {
        return memberRepository.findByConversationId(conv.getId()).stream()
                .filter(m -> characterId != null && characterId.equals(m.getCharacterId()))
                .findFirst()
                // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（引用不再编码 special/crowd 类型）
                .map(m -> com.holzyn.actor.common.NpcRefs.of(m.getCharacterId()))
                .orElse(com.holzyn.actor.common.NpcRefs.of(characterId));
    }

    // ==================== 状态模块 对话情绪评估挂钩 ====================

    /**
     * 单聊轮结束 → AI 情绪评估：评估说话角色情绪并写回（类型查 actor_profile.type）；
     * 冷却/人群门控/失败回退全部在 {@link NpcStateService#evaluateEmotion} 内，本方法仅 try 隔离。
     */
    private void maybeEmotionEvalFromDialog(ActorConversation conv, Long characterId, Long userId) {
        if (npcStateService == null || conv == null || characterId == null) {
            return;
        }
        try {
            String context = emotionEvalContext(messageRepository.findByConversationIdOrderByIdAsc(conv.getId()));
            String ref = com.holzyn.actor.common.NpcRefs.of(characterId);
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（npc_type 存库值改查 actor_profile.type）
            String type = npcTypeResolver.typeOf(conv.getProjectId(), ref);
            npcStateService.evaluateEmotion(conv.getProjectId(), ref, type,
                    "近期对话", context, null);
        } catch (Exception e) {
            log.debug("[Roleplay] 对话情绪评估挂钩失败（静默）: conv={} : {}", conv.getId(), e.getMessage());
        }
    }

    /**
     * 群聊轮结束 → 各成员 AI 情绪评估：逐个成员评估（类型按 actor_profile.type，
     * 冷却在服务内——每人每游戏小时最多 1 次，未冷却成员零成本跳过）。
     */
    private void maybeEmotionEvalFromGroupRound(ActorConversation conv, List<ActorConversationMember> members, Long userId) {
        if (npcStateService == null || conv == null || members == null || members.isEmpty()) {
            return;
        }
        try {
            String context = emotionEvalContext(messageRepository.findByConversationIdOrderByIdAsc(conv.getId()));
            for (ActorConversationMember member : members) {
                if (member.getCharacterId() == null) {
                    continue;
                }
                String ref = memberRefOf(member);
                // ：类型值字面量改走 NpcRefs.normalizeType（member_type 既有值统一 common 口径，
                // 旧 "crowd".equals 分支在新世界会把 common 误判为 special）
                String type = com.holzyn.actor.common.NpcRefs.normalizeType(member.getMemberType());
                npcStateService.evaluateEmotion(conv.getProjectId(), ref, type, "近期对话", context, null);
            }
        } catch (Exception e) {
            log.debug("[Roleplay] 群聊情绪评估挂钩失败（静默）: conv={} : {}", conv.getId(), e.getMessage());
        }
    }

    /**
     * 情绪评估情境文本：最近 4 条消息「角色/对方：内容」紧凑拼接（每条 ≤200 字，供评估输入有界）。
     */
    private String emotionEvalContext(List<ActorMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        int from = Math.max(0, history.size() - 4);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String roleLabel = "user".equals(m.getRole()) ? "对方" : "角色";
            sb.append(roleLabel).append("：").append(capText(content, 200));
        }
        return sb.toString();
    }

    /**
     * 文本截断（上限字符；中文按字符计）。
     */
    private static String capText(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 成员统一角色引用（B5：统一单前缀 {@code npc:{id}}）。
     */
    private String memberRefOf(ActorConversationMember member) {
        // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（引用不再编码 special/crowd 类型，旧 type 局部变量已删）
        return com.holzyn.actor.common.NpcRefs.of(member.getCharacterId());
    }

    // ==================== 社交意愿门控辅助（搭话瞬间/群聊每轮判定） ====================

    /**
     * 群聊批量门控执行：候选成员逐个 boolean 判定「此刻是否愿意接话」→ 不愿者沉默出局 +
     * 拒绝连锁（群聊拒绝无特定搭话对象 → 不做好感调整，仅记忆/情绪/沙盘）。
     *
     * @param conv 会话实体
     *
     * @param candidates 候选成员（已清醒过滤）
     *
     * @param gs 当前世界时刻（游戏秒）
     *
     * @return 愿意接话的成员（门控失败返回原列表=全员放行）
     */
    private List<ActorConversationMember> applyGroupSocialWillingness(ActorConversation conv,
                                                                      List<ActorConversationMember> candidates,
                                                                      Long gs) {
        Map<String, SocialWillingnessGate.SocialContext> items = new LinkedHashMap<>();
        for (ActorConversationMember m : candidates) {
            if (m.getCharacterId() == null) {
                continue;
            }
            String ref = com.holzyn.actor.common.NpcRefs.of(m.getCharacterId());
            items.put(ref, new SocialWillingnessGate.SocialContext(m.getCharacterId(),
                    memberName(m), buildGroupSocialStateText(conv, m, gs)));
        }
        if (items.isEmpty()) {
            return candidates;
        }
        Map<String, Boolean> willing = socialWillingnessGate.evaluateBatch(conv.getProjectId(), items);
        List<ActorConversationMember> out = new ArrayList<>();
        for (ActorConversationMember m : candidates) {
            if (m.getCharacterId() == null) {
                out.add(m);
                continue;
            }
            String ref = com.holzyn.actor.common.NpcRefs.of(m.getCharacterId());
            Boolean w = willing.get(ref);
            if (w == null || w) {
                out.add(m);
                continue;
            }
            // 拒绝=沉默不接话（自然社交动态）；连锁（无特定搭话对象 → 好感跳过）
            log.info("[群聊][社交意愿] 成员沉默不接话：会话={} 角色={}", conv.getId(), m.getCharacterId());
            if (socialDenialService != null) {
                try {
                    socialDenialService.onDenial(conv.getProjectId(), ref,
                            com.holzyn.actor.common.NpcRefs.normalizeType(m.getMemberType()),
                            null, null, null, gs);
                } catch (Exception e) {
                    log.debug("[群聊][社交意愿] 拒绝连锁失败（静默）: {}", e.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * 单聊/被打招呼的情境文本（社交意愿门控输入）：角色此刻在做什么 + 地点 + 有人搭话。
     * 判据自包含，构造失败回退占位文本零阻断。
     */
    private String buildSingleSocialStateText(ActorConversation conv, ActorMessage pending,
                                              String charName, Long gs) {
        try {
            StringBuilder sb = new StringBuilder();
            if (pending.getCharacterId() != null) {
                ActorCharacter ch = characterRepository.findById(pending.getCharacterId()).orElse(null);
                if (ch != null) {
                    sb.append(ch.getName() == null || ch.getName().isBlank() ? "该角色" : ch.getName());
                    String activity = ch.getCurrentActivity();
                    sb.append("此刻").append(activity == null || activity.isBlank()
                            ? "正在忙自己的事" : activity.trim());
                    if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
                        sb.append("，在").append(ch.getLocation().trim());
                    }
                    sb.append("。");
                }
            }
            sb.append("有人过来与其搭话说话。");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[社交意愿] 单聊情境文本构造失败（占位，零阻断）: {}", e.getMessage());
            return charName == null ? "（角色此刻的情境未知）" : charName + "此刻正在忙自己的事。有人过来与其搭话说话。";
        }
    }

    /**
     * 群聊情境文本（批量门控输入）：角色此刻在做什么 + 地点 + 有人说话。
     */
    private String buildGroupSocialStateText(ActorConversation conv, ActorConversationMember m, Long gs) {
        try {
            StringBuilder sb = new StringBuilder();
            String name = memberName(m);
            sb.append(name == null || name.isBlank() ? "该角色" : name);
            if (m.getCharacterId() != null) {
                ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
                if (ch != null) {
                    String activity = ch.getCurrentActivity();
                    sb.append("此刻").append(activity == null || activity.isBlank()
                            ? "正在忙自己的事" : activity.trim());
                    if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
                        sb.append("，在").append(ch.getLocation().trim());
                    }
                }
            }
            sb.append("。有几个人正在说话，问其要不要接话。");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[社交意愿] 群聊情境文本构造失败（占位，零阻断）: {}", e.getMessage());
            return "该角色此刻正在忙自己的事。有几个人正在说话，问其要不要接话。";
        }
    }

    /** 拒绝文本理由短语（boolean 判定不输出理由；按处置给通用措辞）。 */
    private String denialReasonOf(SocialWillingnessGate.WillingnessResult wr) {
        return "正忙，无暇应答";
    }

    /** 角色类型（npc:{id} 不再编码类型，情绪评估/连锁需要行内 type；未知返回 null）。 */
    private String npcTypeOf(Long characterId) {
        if (characterId == null) {
            return null;
        }
        return characterRepository.findById(characterId).map(ActorCharacter::getType).orElse(null);
    }

    /**
     * 群聊一轮后的关系好感挂钩：参与 NPC 两两双向增减好感。
     * <p>启发式：NPC 共同参与一轮群聊互动让彼此更亲近，
     * 每对 +{@code dialog-delta}（默认 1）；单聊/双人中玩家为对话主体方（非角色）不建模；
     * 失败仅告警不阻断对话主流程（关系演化为旁路增强）。</p>
     *
     * @param conv 会话实体
     *
     * @param members 群聊成员（NPC：special/crowd）
     */
    private void applyGroupChatRelationDeltas(ActorConversation conv, List<ActorConversationMember> members) {
        if (!relationsEnabled || relationDynamicsService == null || conv.getProjectId() == null
                || members == null || members.size() < 2) {
            return;
        }
        try {
            List<String> refs = members.stream()
                    .map(this::memberRefOf)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            for (int i = 0; i < refs.size(); i++) {
                for (int j = i + 1; j < refs.size(); j++) {
                    relationDynamicsService.applyDialogPairDelta(
                            conv.getProjectId(), refs.get(i), refs.get(j), dialogDelta, "群聊互动");
                }
            }
        } catch (Exception e) {
            log.warn("[Roleplay] 群聊关系好感挂钩失败: conv={} : {}", conv.getId(), e.getMessage());
        }
    }

    /**
     * 组装【当前对话场景】system 消息（所在地 + 世界时间随时钟刷新，）。
     */
    private String resolveSceneMessage(ActorConversation conv) {
        String location = conv.getLocation();
        String time = worldClockService.gameTimeTextOf(conv.getProjectId(),
                worldClockService.currentGameHour(conv.getProjectId()));
        boolean hasLocation = location != null && !location.isBlank();
        boolean hasTime = time != null && !time.isBlank();
        if (!hasLocation && !hasTime) {
            return null;
        }
        String locText = hasLocation ? location : "通过手机等远程通讯软件进行对话（远程通话/文字消息，非面对面）";
        String timeText = hasTime ? time : "未指定";
        return "【当前对话场景】本次对话发生的地点：" + locText + "；发生时间（世界时间）：" + timeText
                + "。请严格按照该场景与时间进行角色扮演：若为面对面场景，请自然地描述周围环境、光线、声音、"
                + "在场人物等细节；若为远程通讯（手机/文字消息/语音通话），请体现通讯方式的质感与距离感，"
                + "不要凭空描述未出现在场景中的画面。";
    }

    /**
     * 解析群聊成员的对话系统 Prompt（B5 成员类型化）。
     */
    private String resolveCharacterSystemPrompt(ActorConversation conv, ActorConversationMember member) {
        // ：类型值字面量统一走 NpcRefs.normalizeType（member_type 既有值以 common 为准）
        String type = com.holzyn.actor.common.NpcRefs.normalizeType(member.getMemberType());
        return roleplayContextService.resolveCharacterPrompt(conv.getProjectId(), type, member.getCharacterId());
    }

    /**
     * 成员显示名（B5）：member_name 快照优先，否则双查（先特殊角色表再普通 NPC 表）。
     */
    private String memberName(ActorConversationMember member) {
        if (member.getMemberName() != null && !member.getMemberName().isBlank()) {
            return member.getMemberName();
        }
        return characterName(member.getCharacterId());
    }

    /**
     * 消息归属角色名（B5）：按会话成员类型解析名字（双查兜底）。
     */
    private String memberNameOf(Long memberId, ActorConversation conv) {
        if (memberId == null) {
            return null;
        }
        // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
        boolean common = memberRepository.findByConversationId(conv.getId()).stream()
                .filter(m -> memberId.equals(m.getCharacterId()))
                .findFirst()
                .map(m -> com.holzyn.actor.common.NpcRefs.isCommonType(m.getMemberType()))
                .orElse(false);
        if (common) {
            return npcRepository.findById(memberId).map(ActorCharacter::getName).orElse(null);
        }
        return characterRepository.findById(memberId).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * 角色名（双查：先特殊角色表再普通 NPC 表）。
     */
    private String characterName(Long memberId) {
        if (memberId == null) {
            return null;
        }
        ActorCharacter ch = characterRepository.findById(memberId).orElse(null);
        if (ch != null) {
            return ch.getName();
        }
        return npcRepository.findById(memberId).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * SSE token 事件推送（带角色 ID）。
     */
    private void sendToken(SseEmitter emitter, String delta, Long characterId) {
        try {
            emitter.send(SseEmitter.event().name("token").data(Map.of("delta", delta, "characterId", characterId)));
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException("SSE 连接已断开", e);
        }
    }

    /**
     * token 事件推送（经 {@link EventSink}——玩家流式 / 无玩家围观广播）。
     */
    private void sendTokenSink(EventSink sink, String delta, Long characterId) {
        if (!sink.send(com.holzyn.actor.common.sse.SseLifecycle.EVENT_TOKEN,
                Map.of("delta", delta, "characterId", characterId))) {
            throw new RuntimeException("SSE 连接已断开");
        }
    }

    /**
     * 无玩家轮 AI 预算门控——有预算窗口（引擎 tick 或自开窗口）时 tryConsume 扣减，
     * 用尽返回 false；无窗口（玩家触发/独立异步无预算语义）不门控返回 true。
     *
     * @param projectId 项目 ID
     *
     * @return true=可调用 AI（或无需门控）；false=预算用尽
     */
    private boolean tryConsumeAiBudget(Long projectId) {
        if (budgetGuard == null || projectId == null) {
            return true;
        }
        // 长跑 修复：异步剧集预算窗口优先（BudgetGuard 独立命名空间，与引擎 tick
        // 预算完全隔离——异步剧集并发推进时不消耗/不覆盖引擎预算，引擎 AI 决策不被饿死）
        if (budgetGuard.hasEpisodeWindow(projectId)) {
            return budgetGuard.tryConsumeEpisode(projectId);
        }
        if (budgetGuard.budgetTotal(projectId) < 0) {
            // 无窗口：不门控（玩家触发群聊 / 独立上下文自行管理预算）
            return true;
        }
        return budgetGuard.tryConsume(projectId);
    }

    /**
     * SSE error 事件推送并结束流。
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
        } catch (Exception ignored) {
            // 前端已断开时忽略推送失败
        }
        emitter.complete();
    }

    /**
     * 剥离模型误输出的「角色名：」前缀（角色名掺杂防护，最多剥 3 层）。
     */
    private String stripSpeakerPrefix(String text, Set<String> names) {
        if (text == null || names == null || names.isEmpty()) {
            return text;
        }
        String s = text;
        for (int i = 0; i < 3; i++) {
            String before = s;
            for (String name : names) {
                if (s.startsWith(name)) {
                    String after = s.substring(name.length());
                    if (after.startsWith("：") || after.startsWith(":")) {
                        s = after.substring(1).trim();
                        break;
                    }
                }
            }
            if (before.equals(s)) {
                break;
            }
        }
        return s;
    }

    // ==================== 私有辅助（演化， 收编 WorldEvolutionService） ====================

    /**
     * 角色人设摘要（从角色卡提取关键信息；无卡时用档案 detail）。
     */
    private String personaSummary(Long characterId) {
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null) return "";
        String card = (personaService == null ? null : personaService.latestPersonaJson(characterId).orElse(null));
        if (card != null && card != null) {
            try {
                JsonNode p = objectMapper.readTree(card);
                StringBuilder sb = new StringBuilder();
                sb.append("身份：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "无头衔", "dims", "identity", "identity_title"))

                        .append("；社会阶层：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "未知", "dims", "identity", "social_class"));

                sb.append("\n性格特质：").append(String.join("、", com.holzyn.actor.domain.growth.persona.PersonaBits.texts(p, "dims", "personality", "traits_summary")));

                sb.append("\n目标：").append(String.join("、", com.holzyn.actor.domain.growth.persona.PersonaBits.texts(p, "dims", "values", "life_goals")));

                sb.append("\n说话风格：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "自然", "dims", "voice", "register"));
                return sb.toString();
            } catch (Exception e) {
                // 忽略，降级
            }
        }
        return "档案：" + (ch.getDetail() == null ? "" : ch.getDetail());
    }

    /**
     * 组装场景背景（演化场景 + 演化背景叠加； 收编 {@code WorldEvolutionService.buildSceneBackground}）。
     */
    private String buildSceneBackground(Long sceneId, String background) {
        StringBuilder sb = new StringBuilder();
        if (sceneId != null) {
            sceneRepository.findById(sceneId).ifPresent(s -> {
                sb.append("地点：").append(s.getName());
                if (s.getDescription() != null) sb.append("（").append(s.getDescription()).append("）");
                if (s.getBackground() != null) sb.append("\n场景背景：").append(s.getBackground());
            });
        } else {
            sb.append("全局（世界）演化，无固定地点。");
        }
        if (background != null && !background.isBlank()) {
            sb.append("\n本场设定：").append(background);
        }
        return sb.toString();
    }

    /**
     * 角色名解析。
     */
    private String charName(Long characterId) {
        if (characterId == null) return "系统";
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse("角色 " + characterId);
    }

    /**
     * 阶段护栏：INFANT（婴儿）阶段无语言输出能力，对话/群聊被 stage_compliance 硬阻断时，
     * 由本方法返回程序化婴儿回应文本（零 AI 调用，与决策层 continue 兜底同口径）。
     * 回应池按「发声 / 需求 / 情绪」三类轮换，保证同一角色连续多次回应不重复。
     */
    private String infantReply(String charName) {
        List<String> pool = List.of(
                "咿咿呀呀……",
                "（挥舞小手）啊啊——",
                "呜呜……要抱抱……",
                "（咯咯笑起来）呀呀！",
                "（伸出小手够向对方）啊、啊……",
                "哇——（哭了起来，小脸皱成一团）",
                "（抓着玩具摇晃，咿呀自语）哒哒……",
                "（好奇地盯着对方，咧嘴露出刚冒的乳牙）咿——",
                "（困倦地揉眼睛，发出含糊的哼唧）嗯嗯……",
                "（被逗得手舞足蹈）啊啊啊！"
        );
        String text = pool.get((int) (System.nanoTime() % pool.size()));
        return "【" + (charName == null ? "婴儿" : charName) + "】" + text;
    }

    /**
     * 消息类型中文标签。
     */
    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "action" -> "行动";
            case "system" -> "系统";
            case "event" -> "事件";
            default -> "对话";
        };
    }

    /**
     * 字符串截断（null 安全，用于日志输出）。
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 生成失败的用户友好提示。
     */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) {
            return be.getMessage();
        }
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) {
            return ae.getMessage();
        }
        return "对话生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
    }
}
