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

import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NPC 间自主对话服务（阶段 D，domain/agent/roleplay）。
 * <p>职责：WorldEngine 在剧情节点触发「无玩家群聊轮」（NPC 自发对话）——复用
 * {@link RoleplayService#runAutonomousGroupRound} 群聊编排（headless：无 SSE 客户端，
 * 消息逐条广播到会话实时订阅 Hub 供玩家围观），参与者=同地点特殊角色（special）+普通 NPC
 * （crowd）。承载会话为「地点 NPC 内群」（actor_conversation.conversation_type=npc_group，
 * 惰性创建/复用，一轮收尾后 status=archived 归档关闭并沉淀记忆）。</p>
 * <p>两条触发路径：</p>
 * <ul>
 * <li><b>事件后必触发</b>：世界事件（跨日引擎事件/手动 AI 注入事件）发布后，事件所在地点
 * NPC 内群触发一轮回应轮（不限每日 1 轮）；</li>
 * <li><b>平时概率闲聊</b>：WorldEngine 每游戏小时每地点按概率（默认 35%）触发一次闲聊轮
 * （社会对话层修复 A 层 0.15→0.35，每游戏日每地点限
 * {@code daily-cap-per-location} 轮[默认 3，可配]；一轮可多节拍持续，
 * 直到剩 1 人/不足以维持/达节拍上限）。</li>
 * </ul>
 * <p>节拍编排：每节拍=一次群聊轮；预算充足时每 {@code orchestrate-interval}
 * （默认 3）个节拍做一次 AI 编排判断哪个 NPC 自愿退场/是否收尾（模板 autonomous_chat_beat）；
 * AI 预算不足时用规则性离场（低重要度概率离场）兜底。沉淀：每轮对话记忆抽取 +
 * 关系好感增减（对齐群聊）+ 逐轮 kind=roleplay 事件（进事件总线/时间线）+ 收尾归档总括事件
 * （经历记忆经 AgentMemoryEventSubscriber 自动沉淀）。</p>
 * <p>预算：每次 AI 调用受 {@link BudgetGuard} 门控（引擎 tick 内共享窗口；独立异步路径自开
 * 小窗口）；run_mode=minimal 不触发概率闲聊（事件回应轮保留）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomousChatService {

    /** NPC 内群会话类型标记（actor_conversation.conversation_type） */
    static final String CONV_TYPE_NPC_GROUP = "npc_group";
    /** 会话状态：活跃 */
    static final String STATUS_ACTIVE = "active";
    /** 会话状态：已归档（一轮收尾关闭，下次触发重新建） */
    static final String STATUS_ARCHIVED = "archived";

    /** AI 编排输出最大 token（autonomous_chat_beat） */
    private static final int ORCHESTRATE_MAX_TOKENS = 512;
    /** AI 编排最大重试次数 */
    private static final int ORCHESTRATE_MAX_RETRY = 2;
    /** 事件内容摘要截断上限 */
    private static final int SUMMARY_MAX = 200;
    /** 规则性离场概率（预算不足兜底：每次检查 50% 让一名低重要度成员离场） */
    private static final double RULE_LEAVE_PROBABILITY = 0.5;

    // ==================== 依赖（构造器注入） ====================

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final WorldLocationRepository locationRepository;
    private final WorldEventBus worldEventBus;
    private final BudgetGuard budgetGuard;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final WorldClockService worldClockService;
    private final RoleplayService roleplayService;
    private final ObjectMapper objectMapper;

    /**
     * NPC 类型解析器（注入：npc:{id} 不再编码 special/crowd——ref 显示名的档案兜底口径
     * 必须查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /**
     * 会话实时订阅 Hub（围观广播：字段注入保持构造器不变）：无玩家轮逐条消息/系统消息
     * 广播给订阅该会话的前端（玩家围观 NPC 自发对话）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.event.ConversationLiveSseHub conversationLiveSseHub;

    /**
     * 记忆组件。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.memory.AgentMemoryService agentMemoryService;

    /**
     * 动态关系服务。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.learning.RelationDynamicsService relationDynamicsService;

    /**
     * 统一 AI 上下文注入器（角色视角调用注入世界观简报 + 角色核心卡；null=未装配跳过）。
     * <p>2026-10-07 上下文注入根治：无玩家群聊的退场/收尾编排此前只给「成员1:钱串子、成员2:…」
     * 这类名字清单——调度判断退场/收尾缺角色与世界观依据。现把世界观简报 + 各成员核心卡摘要
     * 拼进编排 prompt。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== 开关与参数（application.yml） ====================

    /** 总开关（默认 true） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.enabled:true}")
    private boolean enabled;
    /** 概率闲聊触发概率（0~1，默认 0.35；社会对话层修复 A 层 0.15→0.35） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.probability:0.35}")
    private double probability;
    /**
     * 概率闲聊每日每地点上限（默认 3；社会对话层修复 A 层 1→3 可配；
     * 仅统计「成功开轮」的剧集，不足 2 人/入睡等跳过不计入上限）
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.daily-cap-per-location:3}")
    private int dailyCapPerLocation = 3;
    /** 单轮最大节拍数（默认 20） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.max-beats:20}")
    private int maxBeats;
    /** AI 退场/收尾编排的节拍间隔（默认 3） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.orchestrate-interval:3}")
    private int orchestrateInterval;
    /** 世界事件后触发回应轮开关（默认 true） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.event-response-enabled:true}")
    private boolean eventResponseEnabled;

    // ==================== 长跑 修复配置（失控循环防护） ====================

    /**
     * 独立异步路径（引擎 tick 外推进）自开预算窗口的预算值（默认 32）：
     * 约覆盖 3 轮 × 5 条回复 + 退场编排的 AI 调用量——异步化后不再受引擎 tick 预算约束，
     * 真实防护由「轮级/剧集级现实时长上限 + 无进展检测 + 最大节拍」三重护栏承担。
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.async-episode-budget:32}")
    private int asyncEpisodeBudget = 32;

    /**
     * 剧集现实时长上限（毫秒，默认 10 分钟）：整场无玩家群聊超限即收尾——
     * 与轮级上限（RoleplayService.group-round-max-wall-ms）构成双重护栏，
     * 杜绝 v1 长跑「单剧集独占 scheduling-1 线程 30+ 分钟」的停摆。
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.episode-max-wall-ms:600000}")
    private long episodeMaxWallMs = 600_000L;

    /**
     * 世界运行时激活闸门：世界停止（关沙盘/手动关闭/未激活）
     * → 在途剧集随之收束——「群聊实时随世界运行发生」，世界停则闲聊停。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService runtimeGate;

    /** 虚拟线程执行器（手动注入事件回应轮异步路径） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 概率闲聊每日每地点成功轮数计数：key=projectId:locationId:gameDay → 当日已成功开轮数。
     * 社会对话层修复 A 层：1→{@code daily-cap-per-location} 可配；仅成功开轮的
     * 剧集计数（不足 2 人/入睡等跳过不计），避免空地点烧掉当日额度；旧键按游戏日过期清理防膨胀。
     * 单进程内存态（WorldEngine 单线程推进），应用重启后重置（可接受）。
     */
    private final ConcurrentHashMap<String, Integer> probabilityEpisodes = new ConcurrentHashMap<>();

    /** 会话级推进互斥（防同一 NPC 内群并发推进：引擎 tick 与手动注入路径可能交错） */
    private final ConcurrentHashMap<Long, AtomicBoolean> runningConversations = new ConcurrentHashMap<>();

    /** 随机源（概率闲聊/规则性离场；可注入种子便于测试） */
    private final ThreadLocal<Random> random = ThreadLocal.withInitial(Random::new);

    // ==================== 对外入口 ====================

    /**
     * 预算预检： 长跑 修复——无玩家群聊异步化后剧集使用<b>独立预算命名空间</b>
     * （BudgetGuard.beginEpisode，见 {@link #runEpisode}），与引擎 tick 预算完全隔离；
     * 因此不再按引擎 tick 预算门控（原预检会在引擎决策预算耗尽时饿死事件回应轮/概率闲聊，
     * 实测 10:20 云来茶馆真实事件回应轮被误拦——群聊应实时随世界运行发生，不受引擎决策预算约束）。
     *
     * @param projectId 项目 ID
     *
     * @return 恒 true（剧集自行开启独立窗口并受轮级/剧集级时长上限 + 无进展检测约束）
     */
    private boolean budgetPrecheckOk(Long projectId) {
        return true;
    }

    /**
     * 平时概率闲聊（WorldEngine 每游戏小时调用）：遍历有 ≥2 名合格 NPC 的地点，
     * 按概率（默认 15%）且当日该地点未开过概率轮 → 推进一轮（预算门控）。
     * run_mode=minimal 时不触发（由 WorldEngine 在调用前判断，此处双保险）。
     * <p>长跑 修复：入口改为异步（{@link #triggerProbabilityAsync}），
     * 本方法仍在引擎线程外执行——深夜时段（居民入睡）整体跳过概率闲聊。</p>
     *
     * @param project 项目实体
     *
     * @param hour 当前推进到的游戏小时
     */
    public void maybeTriggerProbability(ActorProject project, long hour) {
        if (!enabled || project == null || project.getId() == null) {
            return;
        }
        Long projectId = project.getId();
        Long userId = project.getUserId();
        // 长跑 修复：深夜时段居民入睡，概率闲聊整体跳过
        // （事件回应轮仍可因重大事件触发，成员级入睡抑制由 runEpisode 内 filterAwakeMembers 兜底）
        if (roleplayService.isDeepNightHour(hour)) {
            log.info("[无玩家群聊] 深夜时段（第 {} 小时）居民入睡，概率闲聊跳过：project={}", hour, projectId);
            return;
        }
        // 预算预检（起恒放行：剧集独立预算窗口，见 budgetPrecheckOk 注释）
        if (!budgetPrecheckOk(projectId)) {
            return;
        }
        List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
        for (ActorWorldLocation loc : locations) {
            try {
                // 社会对话层修复 A 层：先判「同地点合格 NPC ≥2」再掷骰——
                // 前先掷骰、再进 runEpisode 才发现不足 2 人：日志刷出「概率闲聊触发」紧接
                // 「不足 2 名 NPC，跳过」，且空地点白耗随机数
                if (qualifiedCountAt(projectId, loc.getId(), loc.getName()) < 2) {
                    continue;
                }
                if (!shouldRoll(random.get())) {
                    continue;
                }
                if (!canStartProbabilityEpisode(projectId, loc.getId(), hour)) {
                    continue; // 当日该地点已开够轮数（daily-cap-per-location）
                }
                log.info("[无玩家群聊] 概率闲聊触发：project={} 地点={} 游戏小时={}", projectId, loc.getName(), hour);
                // 社会对话层修复 A 层：仅成功开轮的剧集计入当日上限——
                // 不足 2 人/入睡等跳过不烧额度（避免空地点上午烧光、下午 NPC 聚集后反而无闲聊）
                Map<String, Object> episode = runEpisode(projectId, userId, loc.getId(), loc.getName(),
                        hour, "probability", null);
                if (episode != null && "finished".equals(episode.get("status"))) {
                    markProbabilityEpisode(projectId, loc.getId(), hour);
                }
            } catch (Exception e) {
                log.warn("[无玩家群聊] 概率闲聊推进失败（隔离继续）: project={} 地点={} : {}",
                        projectId, loc.getName(), e.getMessage());
            }
        }
    }

    /**
     * 平时概率闲聊：
     * WorldEngine 每游戏小时调用，仅入队由虚拟线程执行器在引擎线程外推进；
     * 群聊剧集不再独占 scheduling-1、不再阻塞世界引擎（v1 实测冻结 30+ 分钟）。
     * 并发安全：剧集级互斥（runningConversations）+ 每地点每游戏日限 1 轮标记均在推进前落定。
     *
     * @param project 项目实体
     *
     * @param hour 当前推进到的游戏小时
     */
    public void triggerProbabilityAsync(ActorProject project, long hour) {
        if (!enabled || project == null || project.getId() == null) {
            return;
        }
        executor.execute(() -> {
            try {
                maybeTriggerProbability(project, hour);
            } catch (Exception e) {
                log.warn("[无玩家群聊] 概率闲聊异步推进失败: project={} : {}", project.getId(), e.getMessage());
            }
        });
    }

    /**
     * 事件回应轮前置条件（B 层 冒烟修复：订阅者「先查后记账」）。
     * <p><b>动机</b>：修复前订阅者用「事件到达」当触发依据，先扣当日额度再异步开轮，
     * 而 {@link #runEpisode} 会在同地点合格 NPC 不足 2 名时直接跳过——实测 12 点额度被
     * 12 起事件瞬间扣光，其中 9 起从未真正开轮（同地点仅 1 人），额度白烧。</p>
     * <p>本方法只读、不建会话、不扣额度（与 A 层 {@code canStartProbabilityEpisode} 同口径），
     * 供订阅者在记账/触发前判定「这起事件是否真的能开出一轮议论」。</p>
     *
     * @param projectId 项目 ID
     *
     * @param locationId 事件地点 ID（可空）
     *
     * @param locationName 事件地点名（可空）
     *
     * @return true=开关齐备且同地点合格 NPC ≥2（可以开轮并计额）
     */
    public boolean canTriggerEventResponse(Long projectId, Long locationId, String locationName) {
        if (!enabled || !eventResponseEnabled || projectId == null) {
            return false;
        }
        try {
            return qualifiedCountAt(projectId, locationId, locationName) >= 2;
        } catch (Exception e) {
            // 零阻断：前置检查失败视为不可开轮（订阅者不改行为，仅不计额不触发）
            log.debug("[无玩家群聊] 事件回应轮前置检查失败（视为不可触发）: project={} 地点={} : {}",
                    projectId, locationName, e.getMessage());
            return false;
        }
    }

    /**
     * 世界事件后的事件回应轮（跨日引擎事件路径，WorldEngine 同步调用；事件回应轮不受每日 1 轮限制）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 事件地点 ID（可空）
     *
     * @param locationName 事件地点名（可空）
     *
     * @param gameHour 事件游戏小时
     *
     * @param eventContent 事件文本（NPC 围绕事件展开回应；可空）
     */
    public void triggerEventResponse(Long projectId, Long userId, Long locationId, String locationName,
                                     Long gameHour, String eventContent) {
        if (!enabled || !eventResponseEnabled || projectId == null) {
            return;
        }
        // 预算预检（起恒放行：剧集独立预算窗口，见 budgetPrecheckOk 注释）
        if (!budgetPrecheckOk(projectId)) {
            log.info("[无玩家群聊] 事件回应轮跳过（预算不足）：project={} 地点={}", projectId, locationName);
            return;
        }
        try {
            log.info("[无玩家群聊] 事件回应轮触发：project={} 地点={} 事件={}",
                    projectId, locationName, shortText(eventContent));
            runEpisode(projectId, userId, locationId, locationName, gameHour, "event", eventContent);
        } catch (Exception e) {
            log.warn("[无玩家群聊] 事件回应轮推进失败（隔离继续）: project={} 地点={} : {}",
                    projectId, locationName, e.getMessage());
        }
    }

    /**
     * 世界事件后的事件回应轮（手动/AI 注入事件路径，WorldEventService.inject 异步调用；
     * 不在 HTTP 请求线程内跑多轮 AI，防阻塞用户请求）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 会话所在地点 ID（可空）
     *
     * @param locationName 会话所在地点名（可空）
     *
     * @param gameHour 当前游戏小时（可空=世界时钟当前值）
     *
     * @param eventContent 事件文本（可空）
     */
    public void triggerEventResponseAsync(Long projectId, Long userId, Long locationId, String locationName,
                                          Long gameHour, String eventContent) {
        if (!enabled || !eventResponseEnabled || projectId == null) {
            return;
        }
        Long gh = gameHour;
        if (gh == null) {
            gh = worldClockService.currentGameHour(projectId);
        }
        final Long fGameHour = gh;
        executor.execute(() -> {
            try {
                triggerEventResponse(projectId, userId, locationId, locationName, fGameHour, eventContent);
            } catch (Exception e) {
                log.warn("[无玩家群聊] 事件回应轮异步推进失败: project={} : {}", projectId, e.getMessage());
            }
        });
    }

    /**
     * 当前进行中的无玩家群聊剧集数。
     * <p>probabilityEpisodes 记录每地点每游戏日「已成功开轮」的键集合（markProbabilityEpisode
     * 置位、过期日清理），数量=当日已成功开过的概率剧集地点·日键数——作为群聊活跃度的内存观测口径
     * （A 层 键值由标记日改为成功轮数，语义=「已开过概率轮的地点·日」数）。</p>
     *
     * @return 进行中剧集数
     */
    public int runningEpisodeCount() {
        return probabilityEpisodes.size();
    }

    /**
     * 专门测试接口·手动触发无玩家群聊剧集。
     * <p>与概率闲聊/事件回应同源（runEpisode 异步剧集），但由测试人员显式指定地点触发：
     * locationId/locationName 指定地点（均缺省时自动挑选同地点合格 NPC ≥2 的首个地点）；
     * 触发类型 test，走与 production 相同的清醒过滤/护栏/收尾链路（不绕过任何护栏）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 地点 ID（可空）
     *
     * @param locationName 地点名（可空；与 locationId 二选一）
     */
    public Map<String, Object> triggerEpisodeForTest(Long projectId, Long userId,
                                                     Long locationId, String locationName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (projectId == null) {
            out.put("status", "failed");
            out.put("message", "projectId 不能为空");
            return out;
        }
        // 地点解析：locationId 优先 → 名称匹配 → 自动挑选首个合格地点
        List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
        ActorWorldLocation picked = null;
        if (locationId != null) {
            picked = locations.stream().filter(l -> locationId.equals(l.getId())).findFirst().orElse(null);
        }
        if (picked == null && locationName != null && !locationName.isBlank()) {
            String name = locationName.trim();
            picked = locations.stream()
                    .filter(l -> l.getName() != null
                            && (l.getName().trim().equals(name) || l.getName().trim().contains(name)))
                    .findFirst().orElse(null);
        }
        if (picked == null) {
            picked = locations.stream()
                    .filter(l -> qualifiedCountAt(projectId, l.getId(), l.getName()) >= 2)
                    .findFirst().orElse(null);
        }
        if (picked == null) {
            out.put("status", "skipped");
            out.put("message", "未找到可触发地点（地点不存在或所有地点同地点合格 NPC 不足 2 名）");
            return out;
        }
        final ActorWorldLocation loc = picked;
        Long gameHour = worldClockService.currentEngineGameHour(projectId);
        final Long gh = gameHour == null ? 0L : gameHour;
        executor.execute(() -> {
            try {
                runEpisode(projectId, userId, loc.getId(), loc.getName(), gh, "test", null);
            } catch (Exception e) {
                log.warn("[无玩家群聊] 测试触发剧集推进失败: project={} 地点={} : {}",
                        projectId, loc.getName(), e.getMessage());
            }
        });
        out.put("status", "started");
        out.put("locationId", loc.getId());
        out.put("locationName", loc.getName());
        out.put("message", "无玩家群聊剧集已异步启动（地点=" + loc.getName() + "，与概率闲聊同源护栏）");
        return out;
    }

    /**
     * 推进一轮无玩家群聊（核心，WorldEngine tick 内同步 / 异步路径共用）：
     * 惰性建/复用 NPC 内群 → 刷新成员 → 多节拍群聊轮（预算门控 + AI 退场编排/规则离场兜底）→
     * 逐轮沉淀（记忆/关系/事件）→ 收尾归档（归档关闭 + 总括事件 + 经历记忆沉淀）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 地点 ID（可空）
     *
     * @param locationName 地点名（可空）
     *
     * @param gameHour 游戏小时
     *
     * @param triggerType probability 概率闲聊 / event 事件回应
     *
     * @param eventContent 事件文本（event 触发时携带，参与成员闲聊背景；可空）
     *
     * @return 本轮结果（status/skipReason/beats/participants/finished）
     */
    public Map<String, Object> runEpisode(Long projectId, Long userId, Long locationId, String locationName,
                                          Long gameHour, String triggerType, String eventContent) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) {
            out.put("status", "skipped");
            out.put("skipReason", "autonomous-chat.disabled");
            return out;
        }
        // 长跑 修复：剧集预算一律自开「剧集独立窗口」（BudgetGuard 独立命名空间）——
        // 不复用/不覆盖引擎 tick 预算窗口（异步剧集与引擎并发推进，覆盖式 beginTick 会饿死引擎 AI）
        budgetGuard.beginEpisode(projectId, asyncEpisodeBudget);
        try {
            // ① 先统计同地点合格 NPC 数量（不建会话）：不足 2 名直接跳过——
            // 避免每次概率闲聊为「无人地点」创建空 NPC 内群会话（污染会话列表/DB，冒烟抓修）
            if (qualifiedCountAt(projectId, locationId, locationName) < 2) {
                log.info("[无玩家群聊] 同地点不足 2 名 NPC，跳过：project={} 地点={} 类型={}",
                        projectId, locationName, triggerType);
                out.put("status", "skipped");
                out.put("skipReason", "insufficient-members");
                return out;
            }
            // 长跑 抓修（空转会话泄漏，验收项⑤）：清醒预检前移到建会话之前——
            // 深夜/入睡场景直接跳过，不再「先建会话 → 再判清醒跳过」留下 0 消息会话
            //
            if (qualifiedAwakeCountAt(projectId, locationId, locationName, gameHour) < 2) {
                log.info("[无玩家群聊] 同地点清醒 NPC 不足 2 名（入睡/深夜），跳过且不建会话：project={} 地点={} 类型={} 游戏小时={}",
                        projectId, locationName, triggerType, gameHour);
                out.put("status", "skipped");
                out.put("skipReason", "members-asleep");
                return out;
            }
            ActorConversation conv = ensureNpcGroupConversation(projectId, userId, locationId, locationName);
            if (conv == null) {
                out.put("status", "skipped");
                out.put("skipReason", "conversation-create-failed");
                return out;
            }
            // 会话级互斥：同一 NPC 内群不并发推进
            AtomicBoolean running = runningConversations.computeIfAbsent(conv.getId(), k -> new AtomicBoolean(false));
            if (!running.compareAndSet(false, true)) {
                out.put("status", "skipped");
                out.put("skipReason", "conversation-busy");
                return out;
            }
            try {
                return doRunEpisode(conv, projectId, userId, locationId, locationName, gameHour,
                        triggerType, eventContent, out);
            } finally {
                running.set(false);
                runningConversations.remove(conv.getId());
            }
        } finally {
            budgetGuard.endEpisode(projectId);
        }
    }

    // ==================== 节拍推进核心 ====================

    /**
     * 节拍推进：刷新成员 → 循环群聊轮（预算门控 + AI 退场编排/规则离场）→ 收尾归档。
     */
    private Map<String, Object> doRunEpisode(ActorConversation conv, Long projectId, Long userId,
                                             Long locationId, String locationName, Long gameHour,
                                             String triggerType, String eventContent, Map<String, Object> out) {
        // ① 刷新成员：合并当前同地点合格 NPC（特殊 + 普通）——防御性再检（runEpisode 已按合格数预检）
        List<ActorConversationMember> members = refreshMembers(conv, projectId, locationId, locationName);
        if (members.size() < 2) {
            log.info("[无玩家群聊] 刷新后同地点不足 2 名 NPC，跳过：project={} conv={} 成员={}",
                    projectId, conv.getId(), members.size());
            out.put("status", "skipped");
            out.put("skipReason", "insufficient-members");
            return out;
        }
        // 长跑 修复：清醒成员不足 2 名（入睡/深夜休息）→ 跳过开轮——
        // 杜绝「入睡角色被强制拉入发言」的失真实循环（v1 实测两角色入睡后仍被轮询兜底强拉发言）
        List<ActorConversationMember> awake = roleplayService.filterAwakeMembers(conv, members, gameHour);
        if (awake.size() < 2) {
            log.info("[无玩家群聊] 清醒成员不足 2 名（入睡/深夜），跳过：project={} conv={} 全体={} 清醒={} 游戏小时={}",
                    projectId, conv.getId(), members.size(), awake.size(), gameHour);
            out.put("status", "skipped");
            out.put("skipReason", "members-asleep");
            // 长跑 抓修（空转会话泄漏兜底）：走到这里说明会话已建——
            // 若它一条消息都没有（本场一轮未开且无历史复用内容），按 D 层同口径回收，
            // 否则「深夜事件回应」每触发一次就多一个 0 消息会话堆在会话列表里。
            // 复用会话（有历史消息）绝不删除（与 D 层同一前置条件）。
            if (conversationHasNoMessages(conv.getId())) {
                recycleEmptyConversation(conv, "members-asleep");
            }
            return out;
        }
        // 参与成员 ref 集合（逐轮事件/收尾归档 participants）
        Set<String> participants = refsOf(members);
        // 说话意愿修复（A 层）：本场背景（触发本次交谈的缘由）——注入发言人选评估与
        // 逐角色回复上下文；此前开场轮上下文为空、模型无据可依，只能答「无人想接话」1 节拍即收尾
        String chatBackground = chatBackgroundFor(triggerType, eventContent, locationName);
        AtomicBoolean alive = new AtomicBoolean(true);
        long episodeStart = System.currentTimeMillis();

        int beats = 0;
        boolean finished = false;
        String finishReason = null;
        // 说话意愿修复 D 层：本场累计回复数（0 = 空转剧集，收尾时不归档而是回收会话）
        int totalReplies = 0;
        while (!finished && beats < maxBeats) {
            // ②-a 修复：世界停止（关沙盘/手动关闭/未激活）→ 在途剧集随之收束
            if (runtimeGate != null && !runtimeGate.isActive(projectId)) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "世界已停止运行，闲聊随之收束。";
                }
                break;
            }
            // ②-b 修复：剧集现实时长上限——超限即收尾（与轮级上限构成双重护栏）
            if (System.currentTimeMillis() - episodeStart > episodeMaxWallMs) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "本场闲聊已达现实时长上限，就此收尾。";
                }
                break;
            }
            // ②-c 节拍前刷新清醒成员：上一节拍退场/时间流逝后可能全员入睡
            awake = roleplayService.filterAwakeMembers(conv, members, gameHour);
            if (awake.size() < 2) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "在场角色均已入睡，闲聊自然收束。";
                }
                break;
            }
            beats++;
            // ② 本轮预算预检（剧集独立窗口剩余 ≥2 才跑一轮；不足则规则离场后收尾）
            boolean budgetOk = budgetGuard.remainingEpisode(projectId) >= 2;
            int roundReplies = 0;
            if (budgetOk) {
                // ③ 群聊轮（headless，消息广播到会话实时订阅 Hub；内部逐 AI 调用受预算门控）
                roundReplies = roleplayService.runAutonomousGroupRound(conv, userId, alive, chatBackground);
            }
            totalReplies += roundReplies;
            // ③-b 修复：一轮下来无人想接话/无任何回复 → 闲聊自然收束（不再无限续轮）
            if (budgetOk && roundReplies == 0) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "无人想接话，闲聊自然收束。";
                }
                break;
            }
            // ④ 逐轮沉淀：kind=roleplay 事件（进事件总线/时间线，事件经历记忆由订阅者自动沉淀）
            publishRoundEvent(projectId, userId, conv, locationId, locationName, gameHour, participants, beats);
            // ⑤ 退场/收尾编排：每 orchestrateInterval 节拍 AI 编排（预算充足）；预算不足规则离场兜底
            if (beats % orchestrateInterval == 0) {
                if (budgetGuard.tryConsumeEpisode(projectId)) {
                    Map<String, Object> plan = orchestrateLeavesAndFinish(projectId, userId, conv, gameHour,
                            triggerType, eventContent, participants);
                    if (plan != null) {
                        // Prompt 工程：收尾编排的关系影响（relationship_impact）
                        // 在收尾归档前写回（双向好感增减，reason=闲聊中的具体言行依据）
                        applyRelationshipImpact(conv, plan, members);
                        applyLeaves(conv, plan, userId);
                        if (Boolean.TRUE.equals(plan.get("shouldFinish"))) {
                            finished = true;
                            finishReason = str(plan.get("finishReason"));
                        }
                    }
                } else {
                    ruleBasedLeave(conv, random.get());
                }
            } else if (!budgetOk) {
                // 预算不足：规则性离场一次后收尾（不再空转）
                ruleBasedLeave(conv, random.get());
            }
            // ⑥ 在场成员不足 2 → 自然收尾
            if (memberRepository.findByConversationId(conv.getId()).size() <= 1) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "场景中只剩下一名角色，对话自然收束。";
                }
            }
            // ⑦ 预算完全耗尽且无成员离场进展 → 收尾防死循环
            if (!budgetOk && memberRepository.findByConversationId(conv.getId()).size() >= 2) {
                finished = true;
                if (finishReason == null || finishReason.isBlank()) {
                    finishReason = "AI 预算用尽，本场闲聊就此收束。";
                }
            }
        }
        if (!finished) {
            finished = true;
            if (finishReason == null || finishReason.isBlank()) {
                finishReason = "本场闲聊已达剧情上限，就此收尾。";
            }
        }
        // ⑧ 空转剧集回收（说话意愿修复 D 层）：本场累计回复数 0 **且会话本身一条消息都没有**
        // → 不归档、不发布收尾事件，直接回收刚建的地点 NPC 内群会话——否则 0 消息的
        // npc_group 会话会堆在会话列表里。
        // ⚠️ 必须双条件：同地点 active 内群会**跨剧集复用**（本场 0 回复但会话已有历史消息）——
        // 此时照常归档，绝不删除真实对话内容（自审抓出的数据丢失风险）。
        if (totalReplies == 0 && conversationHasNoMessages(conv.getId())) {
            recycleEmptyEpisode(conv, projectId, locationId, locationName, triggerType, beats, finishReason);
            out.put("status", "finished");
            out.put("skipReason", "no-messages");
            out.put("conversationId", conv.getId());
            out.put("beats", beats);
            out.put("participants", participants);
            out.put("finished", true);
            out.put("finishReason", finishReason);
            out.put("eventId", null);
            return out;
        }
        // ⑧-b 自然收尾（2026-10 长跑修复：结尾不自然/突然结束）：本场有实质对话 →
        // 在聊天内写入一句「自然的收尾叙述」（技术性收尾理由绝不暴露给玩家/时间线，
        // 一律映射为世界内叙述），让 NPC 自发群聊有可见、自然的结尾，而不是戛然而止。
        if (totalReplies > 0) {
            addSystemMessage(conv, naturalFinishNarration(finishReason));
        }
        // ⑨ 收尾归档：会话归档关闭 + 总括事件（参与成员经历记忆由订阅者沉淀）
        Map<String, Object> archive = archiveEpisode(projectId, userId, conv, locationId, locationName,
                gameHour, participants, beats, triggerType, eventContent, finishReason);
        out.put("status", "finished");
        out.put("conversationId", conv.getId());
        out.put("beats", beats);
        out.put("participants", participants);
        out.put("finished", true);
        out.put("finishReason", finishReason);
        out.put("eventId", archive.get("eventId"));
        log.info("[无玩家群聊] 本轮结束：project={} conv={} 地点={} 类型={} 节拍={} 参与={} 收尾={}",
                projectId, conv.getId(), locationName, triggerType, beats, participants.size(), finishReason);
        return out;
    }

    // ==================== 会话/成员管理 ====================

    /**
     * 惰性建/复用「地点 NPC 内群」会话：查该地点 active 的 npc_group 会话，无则创建
     * （标题「{地点名} · NPC 内群」，mode=group，conversation_type=npc_group，location_id 归一）。
     *
     * @return 活跃的 NPC 内群会话（创建失败返回 null）
     */
    private ActorConversation ensureNpcGroupConversation(Long projectId, Long userId, Long locationId,
                                                         String locationName) {
        List<ActorConversation> existing = conversationRepository
                .findByProjectIdAndConversationTypeAndStatus(projectId, CONV_TYPE_NPC_GROUP, STATUS_ACTIVE);
        for (ActorConversation c : existing) {
            // 地点匹配：location_id 优先；无则地点名快照匹配
            if (locationMatches(c, locationId, locationName)) {
                return c;
            }
        }
        ActorConversation c = new ActorConversation();
        c.setProjectId(projectId);
        c.setUserId(userId);
        c.setMode("group");
        c.setConversationType(CONV_TYPE_NPC_GROUP);
        c.setStatus(STATUS_ACTIVE);
        c.setTitle(locationTitle(locationName));
        c.setLocation(locationName == null || locationName.isBlank() ? null : locationName.trim());
        c.setLocationId(locationId);
        c.setGameTimeText(WorldClockService.formatGameTime(
                worldClockService.currentGameHour(projectId) == null ? 0L
                        : worldClockService.currentGameHour(projectId)));
        ActorConversation saved = conversationRepository.save(c);
        log.info("[无玩家群聊] 创建地点 NPC 内群：project={} conv={} 地点={}", projectId, saved.getId(), locationName);
        return saved;
    }

    /**
     * 会话地点匹配（NPC 内群复用判定）：location_id 相等优先；无则地点名快照相等。
     */
    private boolean locationMatches(ActorConversation c, Long locationId, String locationName) {
        if (c == null) {
            return false;
        }
        if (locationId != null && c.getLocationId() != null) {
            return locationId.equals(c.getLocationId());
        }
        if (locationName != null && !locationName.isBlank() && c.getLocation() != null) {
            return locationName.trim().equals(c.getLocation().trim());
        }
        return false;
    }

    /**
     * 刷新成员：将「当前同地点合格 NPC」（特殊 + 普通）中尚未入会话者补入成员表；
     * 返回会话当前成员列表（已离开的成员不在此轮加入）。
     */
    private List<ActorConversationMember> refreshMembers(ActorConversation conv, Long projectId,
                                                         Long locationId, String locationName) {
        List<ActorConversationMember> current = memberRepository.findByConversationId(conv.getId());
        // 现有成员 key（member_type:characterId）
        Set<String> present = new java.util.HashSet<>();
        for (ActorConversationMember m : current) {
            present.add(keyOf(m.getMemberType(), m.getCharacterId()));
        }
        List<ActorConversationMember> members = new ArrayList<>(current);
        // 特殊型角色
        for (ActorCharacter ch : characterRepository
                .findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_SPECIAL, 0)) {
            if (ch == null || ch.getId() == null || !npcAtLocation(ch.getLocationId(), ch.getLocation(),
                    locationId, locationName)) {
                continue;
            }
            // ：成员 key 的类型值统一走 NpcRefs.TYPE_SPECIAL（member_type 既有值以 common 为准）
            String key = keyOf(NpcRefs.TYPE_SPECIAL, ch.getId());
            if (present.contains(key)) {
                continue;
            }
            ActorConversationMember m = new ActorConversationMember();
            m.setConversationId(conv.getId());
            // ：类型值字面量存库统一走 NpcRefs.TYPE_SPECIAL（单前缀不再编码类型）
            m.setMemberType(NpcRefs.TYPE_SPECIAL);
            m.setCharacterId(ch.getId());
            m.setMemberName(null);
            memberRepository.save(m);
            present.add(key);
            members.add(m);
        }
        // 普通型 NPC
        for (ActorCharacter npc : npcRepository
                .findByProjectIdAndTypeOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON)) {
            if (npc == null || npc.getId() == null || !npcAtLocation(npc.getLocationId(), npc.getLocation(),
                    locationId, locationName)) {
                continue;
            }
            // ：成员 key 的类型值统一走 NpcRefs.TYPE_COMMON（历史 crowd 归一为 common）
            String key = keyOf(NpcRefs.TYPE_COMMON, npc.getId());
            if (present.contains(key)) {
                continue;
            }
            ActorConversationMember m = new ActorConversationMember();
            m.setConversationId(conv.getId());
            // ：类型值字面量存库统一走 NpcRefs.TYPE_COMMON（引用不再编码类型，member_type 以 common 为准）
            m.setMemberType(NpcRefs.TYPE_COMMON);
            m.setCharacterId(npc.getId());
            m.setMemberName(npc.getName());
            memberRepository.save(m);
            present.add(key);
            members.add(m);
        }
        return members;
    }

    /**
     * 同地点合格 NPC 数量（不建会话的预检用）：统计当前同地点特殊角色 + 普通 NPC 数，
     * 用于 {@link #runEpisode} 在惰性建会话<b>之前</b>判断是否值得开轮——
     * 避免每次概率闲聊为「无人地点」创建空 NPC 内群会话（污染会话列表/DB，冒烟抓修）。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID（可空）
     *
     * @param locationName 地点名（可空）
     *
     * @return 同地点合格 NPC 数量
     */
    private int qualifiedCountAt(Long projectId, Long locationId, String locationName) {
        int count = 0;
        // ：actor_profile 单表含 special/common，两类各按 type 过滤统计（原按两张表区分，单表后会重复计数）
        for (ActorCharacter ch : characterRepository
                .findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_SPECIAL, 0)) {
            if (ch != null && ch.getId() != null
                    && npcAtLocation(ch.getLocationId(), ch.getLocation(), locationId, locationName)) {
                count++;
            }
        }
        for (ActorCharacter npc : npcRepository
                .findByProjectIdAndTypeOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON)) {
            if (npc != null && npc.getId() != null
                    && npcAtLocation(npc.getLocationId(), npc.getLocation(), locationId, locationName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 同地点「清醒」合格 NPC 数量：
     * 在「是否值得开轮」之外再判「是否有人醒着」——深夜/入睡角色不参与内群，
     * 不足 2 名直接跳过且**不建会话**（原实现在建会话之后才判，深夜每触发一次残留一个空会话）。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID（可空）
     *
     * @param locationName 地点名（可空）
     *
     * @param gameHour 当前游戏小时（可空=不做深夜判定）
     *
     * @return 同地点清醒合格 NPC 数量
     */
    private int qualifiedAwakeCountAt(Long projectId, Long locationId, String locationName, Long gameHour) {
        int count = 0;
        for (ActorCharacter ch : characterRepository
                .findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_SPECIAL, 0)) {
            if (ch != null && ch.getId() != null
                    && npcAtLocation(ch.getLocationId(), ch.getLocation(), locationId, locationName)
                    && roleplayService.isAwakeCharacter(ch, gameHour)) {
                count++;
            }
        }
        for (ActorCharacter npc : npcRepository
                .findByProjectIdAndTypeOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON)) {
            if (npc != null && npc.getId() != null
                    && npcAtLocation(npc.getLocationId(), npc.getLocation(), locationId, locationName)
                    && roleplayService.isAwakeCharacter(npc, gameHour)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 实体是否在该地点：location_id 相等优先；无 location_id 时按地点名文本匹配。
     */
    private boolean npcAtLocation(Long targetLocationId, String targetLocation,
                                  Long locationId, String locationName) {
        if (targetLocationId != null && locationId != null) {
            return targetLocationId.equals(locationId);
        }
        if (locationId == null && locationName != null && !locationName.isBlank()
                && targetLocation != null && !targetLocation.isBlank()) {
            return locationName.trim().equals(targetLocation.trim())
                    || targetLocation.trim().contains(locationName.trim());
        }
        return false;
    }

    /** ToM 推断深度注入开关（realtime-decision.coordination.tom-inject-enabled；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.realtime-decision.coordination.tom-inject-enabled:true}")
    private boolean tomInjectEnabled = true;

    /** 角色卡仓库 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;

    /**
     * 为群聊成员组装 ToM 推断深度段（按各特殊成员 OCEAN 宜人性×神经质；
     * 无特殊成员/全部解析失败返回空串=不注入，零阻断）。
     */
    private String tomSectionFor(List<ActorConversationMember> members) {
        try {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (ActorConversationMember m : members) {
                if (m == null || m.getCharacterId() == null) {
                    idx++;
                    continue;
                }
                ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
                String tier = com.holzyn.actor.domain.agent.runtime.TomInference.tierOf(
                        ch == null ? null : personaJsonOf(ch));
                if (tier == null || com.holzyn.actor.domain.agent.runtime.TomInference.TIER_NORMAL.equals(tier)) {
                    idx++;
                    continue; // 常规推断不注入（避免噪音）
                }
                String name = ch == null || ch.getName() == null || ch.getName().isBlank()
                        ? "成员" + idx : ch.getName().trim();
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(name).append("：").append(com.holzyn.actor.domain.agent.runtime.TomInference.labelOf(tier));
            }
            if (sb.length() == 0) {
                return "";
            }
            return "【成员推断深度】" + sb + "——退场/收尾判断请顾及各成员理解他人意图的深浅（过度解读者易把寻常话当弦外之音，直接理解者只认明说的事实）。";
        } catch (Exception e) {
            log.debug("[无玩家群聊] ToM 段组装失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /** 角色卡 persona_json（解析失败返回 null）。 */
    private String personaJsonOf(ActorCharacter ch) {
        try {
            return personaService == null ? null : personaService.latestPersonaJson(ch.getId()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 退场/收尾编排 ====================

    /**
     * AI 编排判断退场/收尾（模板 autonomous_chat_beat，预算已消耗）：返回
     * {leaves:[{memberIndex, reason}], shouldFinish, finishReason}；失败返回 null（调用方规则离场兜底）。
     */
    private Map<String, Object> orchestrateLeavesAndFinish(Long projectId, Long userId, ActorConversation conv,
                                                           Long gameHour, String triggerType, String eventContent,
                                                           Set<String> participants) {
        try {
            List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
            if (members.isEmpty()) {
                return null;
            }
            StringBuilder memberDesc = new StringBuilder();
            for (int i = 0; i < members.size(); i++) {
                ActorConversationMember m = members.get(i);
                if (memberDesc.length() > 0) {
                    memberDesc.append("、");
                }
                memberDesc.append("成员").append(i + 1).append(":").append(memberName(m));
            }
            String context = recentMessagesText(conv.getId());
            String background = triggerType == null ? "同地点 NPC 日常闲聊"
                    : ("event".equals(triggerType)
                    ? "世界事件后的自发回应：" + shortText(eventContent)
                    : "同地点 NPC 日常闲聊（概率自发）");
            // ：ToM 推断深度段——按各成员 OCEAN 宜人性×神经质计算
            // 推断深度档（过度解读/常规推断/直接理解），让调度在判断退场/收尾时顾及成员
            // 各自的意图理解方式；开关关/无特殊成员=空串不注入
            String tomSection = tomInjectEnabled ? tomSectionFor(members) : "";
            // 上下文注入根治（2026-10-07）：世界观简报 + 各成员角色核心卡摘要拼进编排
            // ——此前只有「成员1:钱串子、成员2:…」名字清单，调度判断退场/收尾缺依据；
            // 注入器未装配/无数据 → 空串（保留既有行为）
            String injectedContext = buildOrchestrateInjectedContext(conv, members);
            String prompt = promptTemplateService.render(userId, projectId,
                    PromptTemplateService.CODE_AUTONOMOUS_CHAT_BEAT, Map.of(
                            "members_summary", memberDesc.toString(),
                            "context", context == null ? "" : context,
                            "chat_background", background,
                            "tom_section", tomSection,
                            "injected_context", injectedContext));
            String system = promptTemplateService.systemMessage(userId, projectId,
                    PromptTemplateService.CODE_AUTONOMOUS_CHAT_BEAT, null);
            for (int attempt = 1; attempt <= ORCHESTRATE_MAX_RETRY; attempt++) {
                try {
                    long startMs = System.currentTimeMillis();
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system",
                                    system == null || system.isBlank() ? "你只输出严格的 JSON，不输出任何其他文字。" : system),
                            new AiChatRequest.ChatMessage("user", prompt)), 0.7, ORCHESTRATE_MAX_TOKENS, true);
                    AiChatResult result = AiCallContext.run("autonomous_chat", null, () ->
                            aiChatGateway.chatCompletion(userId, projectId, null, req));
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            "autonomous_chat", result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        log.warn("[无玩家群聊] 第 {} 次编排输出无法提取 JSON（前 200 字）={}", attempt, shortText(result.content()));
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(json);
                    Map<String, Object> plan = new LinkedHashMap<>();
                    List<Map<String, Object>> leaves = new ArrayList<>();
                    JsonNode leavesNode = node.path("leaves");
                    if (leavesNode.isArray()) {
                        for (JsonNode l : leavesNode) {
                            int idx = l.path("memberIndex").asInt(-1);
                            if (idx >= 1 && idx <= members.size()) {
                                leaves.add(Map.of(
                                        "member", members.get(idx - 1),
                                        "reason", l.path("reason").asText("")));
                            }
                        }
                    }
                    plan.put("leaves", leaves);
                    plan.put("shouldFinish", node.path("shouldFinish").asBoolean(false));
                    plan.put("finishReason", node.path("finishReason").asText(""));
                    // Prompt 工程：收尾编排新增 relationship_impact——
                    // 本场闲聊让在场成员关系发生的变化（[{memberIndexA, memberIndexB, delta, reason}]），
                    // 收尾时写回 RelationDynamicsService（双向好感增减）
                    java.util.List<Map<String, Object>> impacts = new java.util.ArrayList<>();
                    JsonNode impactsNode = node.path("relationship_impact");
                    if (impactsNode.isArray()) {
                        for (JsonNode imp : impactsNode) {
                            int a = imp.path("memberIndexA").asInt(-1);
                            int b = imp.path("memberIndexB").asInt(-1);
                            if (a < 1 || a > members.size() || b < 1 || b > members.size() || a == b) {
                                continue;
                            }
                            int delta = imp.path("delta").asInt(0);
                            if (delta == 0) {
                                continue;
                            }
                            java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                            one.put("memberA", members.get(a - 1));
                            one.put("memberB", members.get(b - 1));
                            one.put("delta", Math.max(-5, Math.min(5, delta)));
                            one.put("reason", imp.path("reason").asText(""));
                            impacts.add(one);
                        }
                    }
                    plan.put("relationshipImpact", impacts);
                    return plan;
                } catch (Exception e) {
                    log.warn("[无玩家群聊] 第 {} 次退场编排失败: {}", attempt, e.getMessage());
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[无玩家群聊] 退场编排异常（规则离场兜底）: conv={} : {}", conv.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Prompt 工程：应用收尾编排的关系影响——本场闲聊中成员间的互动
     * RelationDynamicsService 双向好感增减（reason=闲聊中的具体言行依据）；失败仅告警不阻断收尾。
     *
     * @param conv 会话实体
     *
     * @param plan 收尾编排结果（含 relationshipImpact；可空）
     *
     * @param members 在场成员列表（memberIndex 1..N 对齐）
     */
    private void applyRelationshipImpact(ActorConversation conv, Map<String, Object> plan,
                                         List<ActorConversationMember> members) {
        if (plan == null || conv == null || conv.getProjectId() == null) {
            return;
        }
        Object raw = plan.get("relationshipImpact");
        if (!(raw instanceof List<?> impacts) || impacts.isEmpty()) {
            return;
        }
        for (Object o : impacts) {
            if (!(o instanceof Map<?, ?> im)) {
                continue;
            }
            Object aObj = im.get("memberA");
            Object bObj = im.get("memberB");
            if (!(aObj instanceof ActorConversationMember a) || !(bObj instanceof ActorConversationMember b)) {
                continue;
            }
            Object deltaObj = im.get("delta");
            if (!(deltaObj instanceof Integer delta) || delta == 0) {
                continue;
            }
            String reason = im.get("reason") == null ? "" : String.valueOf(im.get("reason"));
            try {
                if (relationDynamicsService != null) {
                    String refA = memberRefOf(a);
                    String refB = memberRefOf(b);
                    if (refA != null && refB != null && !refA.equals(refB)) {
                        relationDynamicsService.applyDialogPairDelta(conv.getProjectId(), refA, refB,
                                delta, "无玩家群聊互动：" + reason);
                        log.info("[无玩家群聊] 关系影响写回: {} ↔ {} delta={} 理由={}", memberName(a), memberName(b),
                                delta, reason);
                    }
                }
            } catch (Exception e) {
                log.warn("[无玩家群聊] 关系影响写回失败（不影响收尾）: conv={} : {}", conv.getId(), e.getMessage());
            }
        }
    }

    /**
     * 应用退场列表：对每位退场成员写「退场」系统消息（广播围观）+ 从会话移除成员。
     */
    private void applyLeaves(ActorConversation conv, Map<String, Object> plan, Long userId) {
        Object raw = plan == null ? null : plan.get("leaves");
        if (!(raw instanceof List<?> leaves) || leaves.isEmpty()) {
            return;
        }
        for (Object o : leaves) {
            if (!(o instanceof Map<?, ?> lm)) {
                continue;
            }
            Object memberObj = lm.get("member");
            if (!(memberObj instanceof ActorConversationMember member)) {
                continue;
            }
            String name = memberName(member);
            String reason = lm.get("reason") == null ? "" : String.valueOf(lm.get("reason"));
            addSystemMessage(conv, "【" + name + "】离开了现场" + (reason.isBlank() ? "。" : "：" + reason));
            memberRepository.delete(member);
            log.info("[无玩家群聊] 成员自愿退场：conv={} 成员={} 理由={}", conv.getId(), name, reason);
        }
    }

    /**
     * 规则性离场（预算不足兜底）：50% 概率移除一名「重要度最低」的成员（普通 NPC 视为低重要度）。
     * <p>2026-10 长跑修复（突然离开/结尾不自然）：</p>
     * <ul>
     * <li><b>未发言成员静默离场</b>——本场没说过话的成员（背景板）离开时<b>不写系统消息</b>
     * （此前每移除一名就刷一条「【X】有事离开现场。」，且多为从未发言者，读起来极其突兀）；
     * 只记录日志，成员从在场列表自然消失；</li>
     * <li><b>已发言成员离场用自然措辞</b>（小集轮换，不再千篇一律「有事离开现场」）。</li>
     * </ul>
     */
    private void ruleBasedLeave(ActorConversation conv, Random rng) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
        if (members.size() <= 1) {
            return;
        }
        if (rng.nextDouble() >= RULE_LEAVE_PROBABILITY) {
            return;
        }
        ActorConversationMember lowest = members.get(0);
        int lowestImportance = memberImportance(lowest);
        for (int i = 1; i < members.size(); i++) {
            ActorConversationMember m = members.get(i);
            int imp = memberImportance(m);
            if (imp < lowestImportance) {
                lowest = m;
                lowestImportance = imp;
            }
        }
        if (lowest != null) {
            String name = memberName(lowest);
            // 未发言成员静默离场（不写系统消息，避免突兀的「背景板突然离开」）
            if (!memberHasSpoken(conv.getId(), lowest)) {
                log.info("[无玩家群聊] 未发言成员静默离场（不写叙述）: conv={} 成员={}", conv.getId(), name);
            } else {
                addSystemMessage(conv, leaveLine(name, rng));
            }
            memberRepository.delete(lowest);
            log.info("[无玩家群聊] 规则性离场（预算不足兜底）：conv={} 成员={}", conv.getId(), name);
        }
    }

    /**
     * 成员本场是否发过言（离场叙述判定）：会话历史存在该成员的已完成角色发言。
     */
    private boolean memberHasSpoken(Long conversationId, ActorConversationMember m) {
        if (conversationId == null || m == null || m.getCharacterId() == null) {
            return false;
        }
        try {
            List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
            for (ActorMessage x : history) {
                if (x != null && m.getCharacterId().equals(x.getCharacterId())
                        && "assistant".equals(x.getRole()) && "done".equals(x.getStatus())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("[无玩家群聊] 成员发言史判定失败（按未发言处理）: conv={} : {}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 已发言成员的离场叙述（自然措辞小集，按随机源轮换）——修复千篇一律的「有事离开现场」。
     */
    private String leaveLine(String name, Random rng) {
        String[] lines = {
                "【" + name + "】聊了一阵，起身告辞，先走一步。",
                "【" + name + "】看了看天色，招呼一声便离开了。",
                "【" + name + "】话说到一半，忽然想起还有事，匆匆离去了。",
                "【" + name + "】笑着摆摆手，转身离开了现场。",
        };
        int idx = Math.abs(rng.nextInt()) % lines.length;
        return lines[idx];
    }

    /**
     * 收尾理由 → 世界内自然叙述（2026-10 长跑修复：结尾不自然/技术理由外露）。
     * <p>引擎/预算等技术性收尾理由（AI 预算用尽、现实时长上限、无人想接话、世界停止等）
     * 一律映射为「玩家/时间线可见的世界内叙述」——此前这些技术理由直接出现在聊天结尾
     * 与时间线事件里（如「AI 预算用尽，本场闲聊就此收束」），极其出戏；AI 编排给出的
     * 自然理由（如「天色已晚，大家各自回家」）原样保留。</p>
     *
     * @param finishReason 收尾理由（技术性或自然叙述，可空）
     *
     * @return 世界内自然收尾叙述（恒非空，以「（…）」包裹）
     */
    private String naturalFinishNarration(String finishReason) {
        String r = finishReason == null ? "" : finishReason.trim();
        if (r.isBlank()) {
            return "（这场闲谈就此告一段落，众人各自散去。）";
        }
        if (r.contains("AI 预算") || r.contains("预算用尽") || r.contains("现实时长上限")
                || r.contains("世界已停止运行") || r.contains("无人想接话") || r.contains("只剩下一名角色")
                || r.contains("剧情上限") || r.contains("收束") || r.contains("收尾")) {
            if (r.contains("世界已停止运行")) {
                return "（话音未落，村中的喧闹渐渐平息，仿佛被什么按下了暂停。）";
            }
            if (r.contains("入睡") || r.contains("深夜")) {
                return "（夜深了，众人打着哈欠各自散去，村里重归寂静。）";
            }
            if (r.contains("只剩下一名角色")) {
                return "（众人陆续离开，最后只剩一人，这场闲谈就此收场。）";
            }
            if (r.contains("现实时长上限")) {
                return "（这场闲谈不知不觉聊了很久，众人意犹未尽，各自散去。）";
            }
            if (r.contains("预算用尽")) {
                return "（聊到兴尽，天色不早，众人各自散去。）";
            }
            if (r.contains("剧情上限")) {
                return "（这场热闹的闲谈告一段落，众人各自散去。）";
            }
            return "（闲谈渐稀，一时无人再接话，众人便各自散了。）";
        }
        // AI 编排给出的自然理由：原样包裹保留
        return "（" + r + "）";
    }

    /**
     * 成员重要度（规则离场用）：特殊型取角色重要度；普通型视为 1。
     */
    private int memberImportance(ActorConversationMember m) {
        if (m == null || m.getCharacterId() == null) {
            return 1;
        }
        // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
        if (NpcRefs.isCommonType(m.getMemberType())) {
            return 1;
        }
        return characterRepository.findById(m.getCharacterId())
                .map(ch -> ch.getImportance() == null ? 1 : ch.getImportance())
                .orElse(1);
    }

    // ==================== 沉淀与归档 ====================

    /**
     * 逐轮事件：kind=roleplay、visibility=location、带地点/参与角色，进事件总线（时间线可见 +
     * 经历记忆由 AgentMemoryEventSubscriber 自动沉淀）；sourceKey 按轮幂等。
     */
    private void publishRoundEvent(Long projectId, Long userId, ActorConversation conv, Long locationId,
                                   String locationName, Long gameHour, Set<String> participants, int beat) {
        try {
            String title = (locationName == null || locationName.isBlank() ? "同地点" : locationName)
                    + " · NPC 自发闲聊";
            String content = "第 " + beat + " 轮：" + namesOf(projectId, participants) + " 在 "
                    + (locationName == null || locationName.isBlank() ? "该地点" : locationName)
                    + " 交谈" + roundSnippet(conv.getId());
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind("roleplay")
                    .source("roleplay")
                    .visibility("location")
                    .title(title)
                    .content(shortText(content))
                    .characterRefs(new ArrayList<>(participants))
                    .locationId(locationId)
                    .locationName(locationName)
                    .importance(2)
                    .gameHour(gameHour)
                    .sourceKey("roleplay:round:" + projectId + ":" + conv.getId() + ":" + beat)
                    .build());
        } catch (Exception e) {
            log.warn("[无玩家群聊] 逐轮事件发布失败（主流程不受影响）: conv={} beat={} : {}",
                    conv.getId(), beat, e.getMessage());
        }
    }

    /**
     * 本场背景文案（说话意愿修复 A 层）：事件回应轮 = 世界事件文本；
     * 概率闲聊轮 = 日常自发交谈说明。注入发言人选评估与逐角色回复上下文，让开场轮有据可依。
     *
     * @param triggerType probability 概率闲聊 / event 事件回应
     *
     * @param eventContent 事件文本（可空）
     *
     * @param locationName 地点名（可空）
     *
     * @return 本场背景文案（恒非空）
     */
    private String chatBackgroundFor(String triggerType, String eventContent, String locationName) {
        String where = locationName == null || locationName.isBlank() ? "此地" : locationName;
        if ("event".equals(triggerType) && eventContent != null && !eventContent.isBlank()) {
            // 事件内容取首行（事件文本常带「理由：此刻想…」等动作日志尾注，整段进背景/时间线很出戏）
            return "世界事件后的自发回应——" + firstDisplayLine(eventContent) + "（此刻在" + where + "）";
        }
        return "同地点 NPC 日常闲聊（概率自发；此刻在" + where + "，无特定缘由）";
    }

    /**
     * 事件文本的「首行」展示（截掉动作日志尾注等后续行，如「理由：此刻想…」）。
     * 空/空白返回空串；单行返回原样（仍受 SUMMARY_MAX 截断）。
     */
    private String firstDisplayLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.trim().split("\\r?\\n")[0].trim();
        return shortText(line);
    }

    /**
     * 会话是否「没有一条可渲染的消息」（空转剧集回收的前置条件， 说话意愿修复 D 层）。
     * <p>2026-10 长跑修复：口径由「表行数=0」改为「无可渲染内容」——此前 failed/streaming
     * 占位行（content 为空）也算「有消息」，导致「整场只产生空占位」的会话不被回收、
     * 空消息堆积在会话列表/前端显示「只有角色名」；现改为：存在任一 content 非空的消息
     * （角色发言/系统叙述/事件）才算有消息。</p>
     * <p>查询失败 → 返回 false（按「有消息」保守处理，绝不触发删除）。</p>
     *
     * @param conversationId 会话主键
     *
     * @return true=会话无任何可渲染消息
     */
    private boolean conversationHasNoMessages(Long conversationId) {
        try {
            for (ActorMessage m : messageRepository.findByConversationIdOrderByIdAsc(conversationId)) {
                if (m != null && m.getContent() != null && !m.getContent().isBlank()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("[无玩家群聊] 空转判定查询失败（按有消息处理，不回收）: conv={} : {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除会话的全部空占位消息行（failed/streaming 且 content 为空）——配合「可渲染口径」
     * 回收空转会话时，显式清掉其残留的空占位行（表间无外键级联，须先清消息再删会话）。
     *
     * @param conversationId 会话主键
     */
    private void deleteBlankMessageRows(Long conversationId) {
        try {
            List<ActorMessage> rows = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
            for (ActorMessage m : rows) {
                if (m != null && (m.getContent() == null || m.getContent().isBlank())) {
                    messageRepository.delete(m);
                }
            }
        } catch (Exception e) {
            log.debug("[无玩家群聊] 空占位消息清理失败（忽略）: conv={} : {}", conversationId, e.getMessage());
        }
    }

    /**
     * 回收「一轮未开就跳过」的空会话：成员关系 + 会话行显式清理。
     * <p>与 D 层空转剧集回收同一删除口径，但**触发点不同**：D 层是「剧集跑完 0 回复」，
     * 本方法是「会话刚建出来就因清醒成员不足跳过」。调用前必须已确认会话 0 消息——
     * 复用会话（跨剧集累计历史对话）绝不可删。</p>
     *
     * @param conv 会话实体（已确认 0 消息）
     *
     * @param reason 跳过原因（日志用）
     */
    private void recycleEmptyConversation(ActorConversation conv, String reason) {
        try {
            List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
            if (!members.isEmpty()) {
                memberRepository.deleteAll(members);
            }
            // 2026-10 长跑修复：空转会话可能残留 failed/streaming 空占位行（可渲染口径下算「空」），
            // 显式清掉再删会话（表间无外键级联，否则留下孤儿消息行）
            deleteBlankMessageRows(conv.getId());
            conversationRepository.delete(conv);
            log.info("[无玩家群聊] 空会话已回收（0 条消息，未开轮）：conv={} 地点={} 原因={}",
                    conv.getId(), conv.getLocation(), reason);
        } catch (Exception e) {
            log.warn("[无玩家群聊] 空会话回收失败（保留空会话，主流程不受影响）: conv={} : {}",
                    conv.getId(), e.getMessage());
        }
    }

    /**
     * 空转剧集回收（说话意愿修复 D 层）：删除 0 消息的地点 NPC 内群会话（成员关系 + 会话行）。
     * <p>表间无外键级联，需显式先后清理；失败仅告警不阻断（最坏结果 = 留下一个空会话，与修复前一致）。</p>
     *
     * @param conv 会话实体（0 消息）
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID
     *
     * @param locationName 地点名
     *
     * @param triggerType 触发类型
     *
     * @param beats 已推进节拍
     *
     * @param finishReason 收尾原因
     */
    private void recycleEmptyEpisode(ActorConversation conv, Long projectId, Long locationId, String locationName,
                                     String triggerType, int beats, String finishReason) {
        try {
            List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
            if (!members.isEmpty()) {
                memberRepository.deleteAll(members);
            }
            // 2026-10 长跑修复：空转剧集可能残留 failed/streaming 空占位行（可渲染口径下算「空」），
            // 显式清掉再删会话（表间无外键级联，否则留下孤儿消息行）
            deleteBlankMessageRows(conv.getId());
            conversationRepository.delete(conv);
            log.info("[无玩家群聊] 空转剧集（0 条消息）不归档，已回收会话：project={} conv={} 地点={} 类型={} 节拍={} 收尾={}",
                    projectId, conv.getId(), locationName, triggerType, beats, finishReason);
        } catch (Exception e) {
            log.warn("[无玩家群聊] 空转剧集会话回收失败（保留空会话，主流程不受影响）: conv={} : {}",
                    conv.getId(), e.getMessage());
        }
    }

    /**
     * 收尾归档：会话 status=archived 归档关闭 + 总括事件（kind=roleplay、location 可见性、
     * 参与成员经历记忆经订阅者自动沉淀）。
     */
    private Map<String, Object> archiveEpisode(Long projectId, Long userId, ActorConversation conv,
                                               Long locationId, String locationName, Long gameHour,
                                               Set<String> participants, int beats, String triggerType,
                                               String eventContent, String finishReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        conv.setStatus(STATUS_ARCHIVED);
        conversationRepository.save(conv);
        out.put("conversationId", conv.getId());
        // （L1 工作记忆）：收尾归档即会话结束——巩固该会话 L1 工作项（草稿复核提升进 L2，
        // task/context/perception 按重要度提升或过期），防止工作记忆滞留；失败不阻断归档
        try {
            if (agentMemoryService != null) {
                agentMemoryService.consolidateByConversation(conv.getId(), userId, false);
            }
        } catch (Exception e) {
            log.warn("[无玩家群聊] 收尾归档 L1 巩固失败（主流程不受影响）: conv={} : {}", conv.getId(), e.getMessage());
        }
        try {
            String title = (locationName == null || locationName.isBlank() ? "同地点" : locationName)
                    + " · NPC 自发闲聊收束";
            StringBuilder content = new StringBuilder();
            content.append(namesOf(projectId, participants)).append(" 在 ")
                    .append(locationName == null || locationName.isBlank() ? "该地点" : locationName)
                    .append(" 自发交谈 ").append(beats).append(" 轮后收尾");
            if (triggerType != null && "event".equals(triggerType) && eventContent != null && !eventContent.isBlank()) {
                // 事件内容取首行（事件文本常带「理由：此刻想…」等动作日志尾注，混进时间线很出戏）
                content.append("（围绕事件「").append(firstDisplayLine(eventContent)).append("」）");
            }
            // 2026-10 长跑修复：收尾理由一律用「世界内自然叙述」（技术性理由不外露到时间线）
            content.append("；").append(naturalFinishNarration(finishReason));
            var event = worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind("roleplay")
                    .source("roleplay")
                    .visibility("location")
                    .title(title)
                    .content(shortText(content.toString()))
                    .characterRefs(new ArrayList<>(participants))
                    .locationId(locationId)
                    .locationName(locationName)
                    .importance(2)
                    .gameHour(gameHour)
                    .sourceKey("roleplay:finish:" + projectId + ":" + conv.getId())
                    .build());
            out.put("eventId", event == null ? null : event.getId());
        } catch (Exception e) {
            log.warn("[无玩家群聊] 收尾归档事件发布失败: conv={} : {}", conv.getId(), e.getMessage());
        }
        return out;
    }

    /**
     * 写系统消息（退场/收尾叙述，role=system/type=text）并广播围观。
     */
    private void addSystemMessage(ActorConversation conv, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        ActorMessage msg = new ActorMessage();
        msg.setConversationId(conv.getId());
        msg.setRole("system");
        msg.setType("text");
        msg.setContent(content);
        msg.setStatus("done");
        ActorMessage saved = messageRepository.save(msg);
        // 广播围观（若 Hub 存在；Map.of 不允许 null 值，用 LinkedHashMap 组装）
        if (conversationLiveSseHub != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", saved.getId());
            data.put("characterId", null);
            data.put("characterName", null);
            data.put("role", "system");
            data.put("type", "text");
            data.put("content", content);
            data.put("gameTimeText", conv.getGameTimeText());
            conversationLiveSseHub.broadcast(conv.getId(), data);
        }
    }

    // ==================== 工具 ====================

    /**
     * 概率闲聊是否触发：随机数 &lt; probability。
     */
    private boolean shouldRoll(Random rng) {
        if (rng == null) {
            return false;
        }
        return rng.nextDouble() < clamp01(probability);
    }

    /**
     * 每日每地点是否还能开概率轮（A 层 上限由 1 → {@code daily-cap-per-location} 可配）：
     * 当日（projectId:locationId:gameDay）已成功轮数 &lt; 上限才放行。
     */
    private boolean canStartProbabilityEpisode(Long projectId, Long locationId, long gameHour) {
        if (locationId == null) {
            return false;
        }
        long gameDay = Math.floorDiv(gameHour, 24L);
        String key = projectId + ":" + locationId + ":" + gameDay;
        Integer count = probabilityEpisodes.get(key);
        return count == null || count < Math.max(1, dailyCapPerLocation);
    }

    /**
     * 概率轮成功计数（A 层 1→N；仅在剧集 status=finished 后调用）：
     * CAS 递增当日该地点成功轮数，并顺带清理本项目的过期游戏日键（防长期运行内存膨胀）。
     */
    private void markProbabilityEpisode(Long projectId, Long locationId, long gameHour) {
        if (locationId == null) {
            return;
        }
        long gameDay = Math.floorDiv(gameHour, 24L);
        String key = projectId + ":" + locationId + ":" + gameDay;
        probabilityEpisodes.merge(key, 1, Integer::sum);
        purgeStaleProbabilityKeys(projectId, gameDay);
    }

    /**
     * 清理本项目早于「前一个游戏日」的概率轮计数键（保留当日与前一日，防日界并发写竞争误删）。
     */
    private void purgeStaleProbabilityKeys(Long projectId, long currentDay) {
        try {
            String prefix = projectId + ":";
            List<String> stale = new ArrayList<>();
            for (String key : probabilityEpisodes.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String[] parts = key.split(":");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    long day = Long.parseLong(parts[2]);
                    if (day < currentDay - 1) {
                        stale.add(key);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字日段（理论不可达），保留不清理
                }
            }
            for (String key : stale) {
                probabilityEpisodes.remove(key);
            }
        } catch (Exception e) {
            log.debug("[无玩家群聊] 概率轮计数键清理失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 成员 ref 集合（统一单前缀 {@code npc:{id}}）。
     */
    private Set<String> refsOf(List<ActorConversationMember> members) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        for (ActorConversationMember m : members) {
            if (m == null || m.getCharacterId() == null) {
                continue;
            }
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（引用不再编码 special/common 类型）
            refs.add(NpcRefs.of(m.getCharacterId()));
        }
        return refs;
    }

    /**
     * 成员名（特殊型查档案；普通型用名称快照）。
     */
    private String memberName(ActorConversationMember m) {
        if (m == null || m.getCharacterId() == null) {
            return "未知";
        }
        // ：类型值字面量比较统一走 NpcRefs.isCommonType（member_type 既有值以 common 为准）
        if (NpcRefs.isCommonType(m.getMemberType())) {
            return m.getMemberName() == null || m.getMemberName().isBlank() ? "居民" : m.getMemberName();
        }
        return characterRepository.findById(m.getCharacterId())
                .map(ActorCharacter::getName).orElse("角色" + m.getCharacterId());
    }

    /**
     * 成员统一角色引用。
     */
    private String memberRefOf(ActorConversationMember m) {
        if (m == null || m.getCharacterId() == null) {
            return null;
        }
        // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（引用不再编码 special/crowd 类型）
        return NpcRefs.of(m.getCharacterId());
    }

    /**
     * 编排注入上下文：世界观简报 + 各成员角色核心卡摘要（供退场/收尾编排判断）。
     * <p>2026-10-07 上下文注入根治：此前编排 prompt 只有「成员1:钱串子、成员2:…」名字清单，
     * 调度判断退场/收尾缺角色与世界观依据。现拼世界观简报 + 每成员核心卡（≤2 人全量、
     * 多人截断前 3 人防撑爆）；注入器未装配/无数据 → 空串（保留既有行为）。</p>
     *
     * @param conv 会话实体
     *
     * @param members 在场成员
     *
     * @return 注入段（可空）
     */
    private String buildOrchestrateInjectedContext(ActorConversation conv,
                                                   List<ActorConversationMember> members) {
        if (aiContextInjector == null || conv == null || members == null || members.isEmpty()) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            String brief = aiContextInjector.worldBrief(conv.getProjectId());
            if (!brief.isBlank()) {
                sb.append("【世界观】\n").append(brief);
            }
            // 批 2 对话链（收尾编排）：段 2D 当前世界动态精简行（世界历时刻 · 季节 · 天气）——
            // 调度判断退场/收尾需要知道此刻世界时刻（如天色已晚/深夜/雨雪），零 AI 确定性
            String dynamics = aiContextInjector.worldDynamicsLine(conv.getProjectId());
            if (dynamics != null && !dynamics.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("【当前世界动态】").append(dynamics);
            }
            List<String> cards = new java.util.ArrayList<>();
            int limit = Math.min(3, members.size());
            for (int i = 0; i < limit; i++) {
                ActorConversationMember m = members.get(i);
                String ref = memberRefOf(m);
                if (ref == null) {
                    continue;
                }
                String card = aiContextInjector.characterCard(conv.getProjectId(), ref);
                if (!card.isBlank()) {
                    cards.add("【成员" + (i + 1) + "】\n" + card);
                }
            }
            if (!cards.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(String.join("\n\n", cards));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[无玩家群聊] 编排注入上下文组装失败（跳过，零阻断）: conv={} : {}", conv.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 参与成员名列表（顿号连接）。
     */
    private String namesOf(Long projectId, Set<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return "多位 NPC";
        }
        StringBuilder sb = new StringBuilder();
        for (String ref : refs) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(refName(projectId, ref));
        }
        return sb.toString();
    }

    /**
     * ref → 显示名（统一单前缀；普通型/特殊型按 actor_profile.type 兜底口径）。
     */
    private String refName(Long projectId, String ref) {
        if (ref == null || ref.isBlank()) {
            return "NPC";
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long id = NpcRefs.idOf(ref);
        if (id == null) {
            return "NPC"; // 非法引用（保持原有 → "NPC" 的处理）
        }
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type 决定兜底显示名）
        if (npcTypeResolver.isCommon(projectId, ref)) {
            return npcRepository.findById(id).map(ActorCharacter::getName).orElse("居民");
        }
        return characterRepository.findById(id).map(ActorCharacter::getName).orElse("角色");
    }

    /**
     * 最近对话文本（供编排 AI 判断退场/收尾；截断控制 token）。
     */
    private String recentMessagesText(Long conversationId) {
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        int from = Math.max(0, history.size() - 20);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                sb.append("【").append(characterName(m.getCharacterId())).append("】")
                        .append(m.getContent()).append("\n");
            } else if ("system".equals(m.getRole())) {
                sb.append("（系统）").append(m.getContent()).append("\n");
            } else if ("event".equals(m.getType())) {
                sb.append("（事件）").append(m.getContent()).append("\n");
            }
        }
        return shortText(sb.toString());
    }

    /**
     * 本轮回复片段（事件内容点缀，取最近几条回复首行）。
     */
    private String roundSnippet(Long conversationId) {
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, history.size() - 3); i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (m != null && "assistant".equals(m.getRole()) && "done".equals(m.getStatus())
                    && m.getContent() != null && !m.getContent().isBlank()) {
                String firstLine = m.getContent().trim().split("\\n")[0].trim();
                if (!firstLine.isEmpty()) {
                    sb.append("；").append(shortText(firstLine));
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "。";
    }

    /**
     * 角色名（消息 characterId 归属特殊角色）。
     */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return "NPC";
        }
        return characterRepository.findById(characterId).map(ActorCharacter::getName)
                .orElse("角色" + characterId);
    }

    /**
     * 会话标题：地点名 + 「 · NPC 内群」。
     */
    private String locationTitle(String locationName) {
        return (locationName == null || locationName.isBlank() ? "同地点" : locationName.trim())
                + " · NPC 内群";
    }

    /**
     * 成员 key（member_type:characterId；类型值经 NpcRefs 归一，历史 crowd 与 common 同键）。
     */
    private String keyOf(String memberType, Long characterId) {
        // ：类型值字面量统一走 NpcRefs.normalizeType（member_type 既有值以 common 为准）
        return NpcRefs.normalizeType(memberType) + ":" + characterId;
    }

    /**
     * 截断文本（null/空白返回原样）。
     */
    private static String shortText(String s) {
        if (s == null || s.length() <= SUMMARY_MAX) {
            return s;
        }
        return s.substring(0, SUMMARY_MAX);
    }

    /**
     * 概率钳制到 0~1。
     */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Object 转字符串。
     */
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
