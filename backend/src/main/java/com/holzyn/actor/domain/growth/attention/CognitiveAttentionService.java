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

package com.holzyn.actor.domain.growth.attention;

import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.ai.decision.DecisionState;
import com.holzyn.actor.ai.decision.gate.DecisionGate;
import com.holzyn.actor.ai.decision.gate.DecisionScenes;
import com.holzyn.actor.ai.decision.gate.DecisionTemplates;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认知注意力服务。
 * <p><b>定位</b>：把「每 tick 全 NPC 批量注意力打分 + 高频微决策」做成真实心智活动的语义层——
 * 真人不会每小时才想一次「我该干嘛」，而是每时每刻都有注意力起伏、偶尔冒出念头。决策模型
 * 亚秒级批量判定使每 2 tick（≈10 游戏分钟）全 NPC 一次前向成为现实（20 NPC × 2 题 ≤ 96 上限）。</p>
 * <p><b>两条通道</b>：</p>
 * <ul>
 * <li><b>每 tick 注意力状态 + 即时意向</b>（{@link #tickAsync}）：全 NPC 各一个 state，
 * attention 场景 score「此刻注意力状态」→ {@link BrainTierService#applyAttention}
 * 语义升降档；micro_decision 场景 choice「此刻即时意向」→ 低频记忆轨迹 +
 * 极低概率打断（MENTAL_URGE）；大部分「继续当下事」保持行为连续性（零副作用）；</li>
 * <li><b>事件×NPC 相关度</b>（{@link #eventRelevanceAsync}）：事件发布时异步评估<b>在场 NPC</b>
 * 相关度（按事件可见性推导：global=全部存活 NPC / location=当事人+同地点在场者 /
 * private=仅当事人——<b>修：偶遇等小范围事件不再评估全 NPC</b>）→ 高相关语义升档 +
 * 写 L1 感知记忆「此事与我有关」+ 触发深度处理。</li>
 * </ul>
 * <p><b>执行模型</b>：异步虚拟线程执行、<b>不进 tick 屏障</b>（真实心智活动
 * 本就有延迟，结果滞后一两个 tick 可接受；不拖慢世界推进）；任何失败 fail-closed 零阻断
 * （未启用/场景关/调用失败 → 静默跳过）。含 决策模型 调用，<b>不带 {@code @Transactional}</b>
 * （AiCallOutsideTransactionTest 反射守护）。</p>
 */
@Slf4j
@Service
public class CognitiveAttentionService implements AttentionLevelProvider {

    /** 注意力 state 内的问题键（attention 场景：注意力状态 score） */
    public static final String QID_ATTENTION_STATE = "attention_state";

    /** 微决策 state 内的问题键（micro_decision 场景：即时意向 choice） */
    public static final String QID_MICRO_INTENT = "micro_intent";

    /** 事件相关度 state 内的问题键（attention 场景：事件相关度 score） */
    public static final String QID_EVENT_RELEVANCE = "event_relevance";

    /** 参与度统计种类：成功发言（spoke，权重 1） */
    public static final String PARTICIPATION_SPOKE = "spoke";

    /** 参与度统计种类：在场参与（attended，权重 0.5） */
    public static final String PARTICIPATION_ATTENDED = "attended";

    /** 异步执行器（虚拟线程每任务一线程，用完即弃；daemon 由 JVM 托管） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 内存观测 ====================

    /** 内存观测环状缓冲上限（防无限增长；纯内存零副作用） */
    private static final int STATS_CAP = 300;

    /** 注意力档位变化轨迹（npcRef → level → 档位变化 → gameSecond，最近在前） */
    private final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> attentionStats =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** 微决策非「继续」意向轨迹（npcRef → 意向 → gameSecond，最近在前） */
    private final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> microStats =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** 微决策计数器（继续当下事 / 冒出念头——反映真人式行为连续性占比） */
    private final java.util.concurrent.atomic.AtomicLong microContinueCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong microNonContinueCount =
            new java.util.concurrent.atomic.AtomicLong();

    // ==================== 群聊参与度统计（双向联动的对话侧信号源，内存态） ====================

    /** 参与度统计：key = projectId + ":" + npcRef → 窗口内计数（spoke/attended 加权）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, ParticipationState> participation =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 参与度窗口状态（滑动窗口按游戏秒；游戏秒回退/跳变按新窗口处理，简单稳定）。
     */
    private static final class ParticipationState {
        final java.util.concurrent.atomic.DoubleAdder weighted = new java.util.concurrent.atomic.DoubleAdder();
        volatile long windowStartGameSecond;
        volatile long windowEndGameSecond;

        ParticipationState(long gameSecond, long windowSeconds) {
            this.windowStartGameSecond = gameSecond;
            this.windowEndGameSecond = gameSecond + windowSeconds;
        }

        boolean inWindow(long gameSecond) {
            return gameSecond >= windowStartGameSecond && gameSecond < windowEndGameSecond;
        }
    }

    /** 参与度统计键。 */
    private static String participationKey(Long projectId, String npcRef) {
        return projectId + ":" + npcRef;
    }

    /** 最近注意力档位变化（调试页「注意力卡」数据源；max≤500）。 */
    public java.util.List<Map<String, Object>> recentAttentionStats(int max) {
        return drain(attentionStats, max);
    }

    /** 最近微决策意向轨迹（调试页「微决策卡」数据源；max≤500）。 */
    public java.util.List<Map<String, Object>> recentMicroStats(int max) {
        return drain(microStats, max);
    }

    /** 微决策计数器（继续当下事 / 冒出念头）。 */
    public Map<String, Object> microCounters() {
        return Map.of("continue", microContinueCount.get(), "thought", microNonContinueCount.get());
    }

    // ==================== 参与度 / 相机注意力（AttentionLevelProvider 实现，） ====================

    /**
     * 记录一次群聊参与：发言者=spoke（权重 1）、在场其他成员=attended
     * （权重 0.5）。滑动窗口（近 {@code participation-window-game-hours} 游戏小时）内计数，
     * 超窗重置——真人社交记忆节奏。内存态（重启清零，纯观测信号，不落库）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @param gameSecond 当前游戏秒（null=未知，跳过记录）
     */
    public void recordParticipation(Long projectId, String npcRef, String kind, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || gameSecond == null) {
            return;
        }
        GrowthProperties.Attention a = attentionConfig();
        if (a == null || !a.isParticipationEnabled() || !a.isParticipationRecordingEnabled()) {
            return;
        }
        try {
            double weight = PARTICIPATION_SPOKE.equals(kind) ? 1.0 : 0.5;
            long windowSeconds = Math.max(1L, (long) a.getParticipationWindowGameHours() * 3600L);
            String key = participationKey(projectId, npcRef);
            ParticipationState st = participation.compute(key, (k, cur) -> {
                if (cur == null || !cur.inWindow(gameSecond)) {
                    return new ParticipationState(gameSecond, windowSeconds);
                }
                return cur;
            });
            st.weighted.add(weight);
            // 防窗口陈旧条目无限膨胀：条目数有界（角色数 × 项目数），无需额外清理
        } catch (Exception e) {
            log.debug("[认知注意力] 参与度记录失败（跳过）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /**
     * 群聊参与度档位（0~3）：近窗口内加权次数按步长分档——≥1 步长=1 档、≥2 步长=2 档、≥3 步长=3 档。
     * 开关关/无记录返回 0（消费方按「无信号」处理，零阻断）。
     */
    @Override
    public int participationLevel(Long projectId, String npcRef) {
        GrowthProperties.Attention a = attentionConfig();
        if (a == null || !a.isParticipationEnabled() || projectId == null || npcRef == null) {
            return 0;
        }
        ParticipationState st = participation.get(participationKey(projectId, npcRef));
        if (st == null) {
            return 0;
        }
        int step = Math.max(1, a.getParticipationLevelStep());
        double weighted = st.weighted.sum();
        if (weighted >= 3.0 * step) {
            return 3;
        }
        if (weighted >= 2.0 * step) {
            return 2;
        }
        if (weighted >= step) {
            return 1;
        }
        return 0;
    }

    /**
     * 相机注意力档位（0~3，程序化连续映射）：距离 ≤ camera-near-meters→3、≤ camera-mid-meters→2、
     * ≤ camera-far-meters→1、否则 0；无相机/未装配/开关关=0（零副作用）。
     */
    @Override
    public int cameraAttentionLevel(Long projectId, String npcRef) {
        GrowthProperties.Attention a = attentionConfig();
        if (a == null || !a.isCameraFocusEnabled() || cameraDistanceProvider == null
                || projectId == null || npcRef == null) {
            return 0;
        }
        try {
            if (!cameraDistanceProvider.hasActiveCamera(projectId)) {
                return 0;
            }
            Double d = cameraDistanceProvider.distanceMeters(projectId, npcRef);
            if (d == null || d < 0) {
                return 0;
            }
            double near = growthProperties.getLod().getCameraNearMeters();
            double mid = Math.max(near, a.getCameraMidMeters());
            double far = Math.max(mid, a.getCameraFarMeters());
            if (d <= near) {
                return 3;
            }
            if (d <= mid) {
                return 2;
            }
            if (d <= far) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log.debug("[认知注意力] 相机注意力读取失败（跳过）: ref={} : {}", npcRef, e.getMessage());
            return 0;
        }
    }

    /**
     * 认知 LOD 档位（AttentionLevelProvider 实现：委托 BrainTierService；null=无信号）。
     */
    @Override
    public String tierOf(Long projectId, String npcRef) {
        if (brainTierService == null || projectId == null || npcRef == null) {
            return null;
        }
        try {
            return brainTierService.tierOf(projectId, npcRef);
        } catch (Exception e) {
            log.debug("[认知注意力] LOD 档位读取失败（返回 null）: ref={} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /** 参与度统计快照。 */
    public Map<String, Object> participationSnapshot() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ParticipationState> e : participation.entrySet()) {
            ParticipationState st = e.getValue();
            out.put(e.getKey(), Map.of(
                    "weighted", Math.round(st.weighted.sum() * 100.0) / 100.0,
                    "windowStart", st.windowStartGameSecond,
                    "windowEnd", st.windowEndGameSecond));
        }
        return out;
    }

    /** 环状缓冲读（最近在前，截断到 max）。 */
    private static java.util.List<Map<String, Object>> drain(
            java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> deque, int max) {
        int n = Math.max(1, Math.min(500, max));
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Iterator<Map<String, Object>> it = deque.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        return out;
    }

    /** 环状缓冲写（满上限丢最旧）。 */
    private static void push(java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> deque,
                             Map<String, Object> rec) {
        deque.addLast(rec);
        while (deque.size() > STATS_CAP) {
            deque.pollFirst();
        }
    }

    // ==================== 依赖（字段注入，构造器不变——治理纪律） ====================

    /** 决策模型 判定门 */
    @Autowired(required = false)
    private DecisionGate decisionGate;

    /** 认知 LOD（语义通道消费方；null=未装配跳过） */
    @Autowired(required = false)
    private BrainTierService brainTierService;

    /** 角色主表（全 NPC 名单/名称/位置/当前行动） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 状态模块（需求值用于情境文本；null=状态未知）。
     * <p><b>@Lazy 破循环</b>：事件总线 → 注意力服务 → 状态服务 → 信息感知/扩散 → … → 事件总线
     * 全环；状态服务较重建构且只在每 tick 情境文本构建时用到，
     * 惰性代理即可打破（沿 SpaceTriggerJudge.growthSpaceService @Lazy 先例）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 世界时钟（游戏时刻文本；null=未知） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 事件表（近期见闻注入情境；null=无） */
    @Autowired(required = false)
    private ActorEventRepository eventRepository;

    /** 实时决策（微决策打断源；null=跳过打断） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService realTimeDecisionService;

    /** L1 工作记忆（低频记忆轨迹；null=跳过记忆写入） */
    @Autowired(required = false)
    private MemoryWorkingService memoryWorkingService;

    /** 生命周期配置（注意力/微决策开关与参数） */
    @Autowired(required = false)
    private GrowthProperties growthProperties;

    /**
     * 相机距离提供器（相机注意力聚焦，Web 环境无相机 → 实现返回 null/false，
     * 零副作用；Unity 接入后自动生效）。字段注入 + 构造器不变（治理纪律）。
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.lod.CameraDistanceProvider cameraDistanceProvider;

    // ==================== 对外入口 ====================

    /**
     * 每 tick 高频微决策 + 注意力状态批量：全 NPC 注意力 score + 即时意向 choice
     * 各自批量一次前向（异步虚拟线程，不进 tick 屏障）。调用方（WorldEngine 感知周期）按
     * {@code holzyn.actor.growth.attention.period-ticks}（默认 2）间隔调用。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（可空）
     */
    public void tickAsync(Long projectId, Long gameSecond) {
        if (projectId == null || !attentionEnabled()) {
            return;
        }
        executor.submit(() -> {
            try {
                tickSync(projectId, gameSecond);
            } catch (Exception e) {
                log.warn("[认知注意力] 每 tick 批量执行失败（零阻断）: project={} : {}", projectId, e.getMessage());
            }
        });
    }

    /**
     * 事件×NPC 相关度批量：事件发布时异步评估<b>在场 NPC</b>对该事件的相关度——高相关
     * （≥ {@code event-relevance-promote-level}）→ 语义升档 focus + 写 L1 感知记忆
     * （「此事与我有关」，≥ {@code event-relevance-memory-level}）。
     * 只评估 importance≥2 的事件（琐碎日常不值得注意力评估）；在场名单按事件可见性推导
     * （见 {@link #presentCharacters}），2 人偶遇只评估双方当事人，无关 NPC 零评估。
     * <p><b>调用风暴抓修（2026-10-07 实测）</b>：<b>跳过 kind=relation 事件</b>——
     * relation 事件是关系更新后的<b>派生记录</b>（内容=「好感 X→Y（Δ1）…共同经历事件…」，
     * 单次群聊收尾可产生 500+ 条），其「共同经历」语义已由发布链本身（relation_delta 评估 +
     * 好感更新）表达；再对每一条做全 NPC 相关度评估 = 重复评估同一事件的放大器，
     * 一次群聊即可触发 700+ 次 attention 批量调用（60s 超时雪崩）。过滤后只评估
     * 真实世界事件（roleplay/evolution 等）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param event 已发布的世界事件（可空=跳过）
     */
    public void eventRelevanceAsync(Long projectId, ActorEvent event) {
        if (projectId == null || event == null || event.getId() == null
                || event.getContent() == null || event.getContent().isBlank()) {
            return;
        }
        if ("relation".equals(event.getKind())) {
            return; // relation 为关系更新的派生记录，跳过全 NPC 相关度评估（防调用风暴放大器）
        }
        if (event.getImportance() == null || event.getImportance() < 2) {
            return; // 琐碎事件不值得注意力评估
        }
        if (decisionGate == null || !decisionGate.isEnabled(
                DecisionScenes.ATTENTION)) {
            return; // attention 场景未启用 → 零成本跳过
        }
        executor.submit(() -> {
            try {
                eventRelevanceSync(projectId, event);
            } catch (Exception e) {
                log.warn("[认知注意力] 事件相关度批量执行失败（零阻断）: project={} evt={} : {}",
                        projectId, event.getId(), e.getMessage());
            }
        });
    }

    // ==================== 内部实现 ====================

    /** 每 tick 批量（同步体，异步线程内执行）。 */
    private void tickSync(Long projectId, Long gameSecond) {
        List<ActorCharacter> chars = aliveCharacters(projectId);
        if (chars.isEmpty()) {
            return;
        }
        Long clock = currentEngineSecond(projectId, gameSecond);
        String recentEvent = recentEventText(projectId);
        // ① 每个 NPC 一个 state：情境文本（名称/当前行动/地点/时刻/需求/在场者/近期见闻）
        Map<String, DecisionState> attentionStates = new LinkedHashMap<>();
        Map<String, DecisionState> microStates = new LinkedHashMap<>();
        for (ActorCharacter ch : chars) {
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            String text = buildStateText(projectId, ch, clock, recentEvent);
            Map<String, DecisionQuestion> attentionQuestions = DecisionState.newQuestions();
            attentionQuestions.put(QID_ATTENTION_STATE, DecisionTemplates.attentionStateQuestion());
            attentionStates.put(npcRef, DecisionState.text(npcRef, text, attentionQuestions));
            Map<String, DecisionQuestion> microQuestions = DecisionState.newQuestions();
            microQuestions.put(QID_MICRO_INTENT, DecisionTemplates.microIntentQuestion());
            microStates.put(npcRef, DecisionState.text(npcRef, text, microQuestions));
        }
        // ② 注意力批量（attention 场景 score）+ 微决策批量（micro_decision 场景 choice）
        Map<String, Map<String, Object>> attention =
                decisionGate == null ? Map.of()
                        : decisionGate.mixedBatchDecisions(projectId, DecisionScenes.ATTENTION,
                        attentionStates, Map.of(QID_ATTENTION_STATE, DecisionQuestion.TYPE_SCORE));
        Map<String, Map<String, Object>> micro =
                decisionGate == null ? Map.of()
                        : decisionGate.mixedBatchDecisions(projectId, DecisionScenes.MICRO_DECISION,
                        microStates, Map.of(QID_MICRO_INTENT, DecisionQuestion.TYPE_CHOICE));
        // ③ 消费结果（逐 NPC）
        int promoted = 0;
        int demoted = 0;
        int thoughts = 0;
        int interrupts = 0;
        for (ActorCharacter ch : chars) {
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            Map<String, Object> att = attention.get(npcRef);
            if (att != null && att.get(QID_ATTENTION_STATE) instanceof DecisionGate.ScoreOutcome so
                    && so.adopted()) {
                if (brainTierService != null) {
                    // 注意力合成（，三项认知 LOD 深化）：决策模型 语义档位 →
                    // ① 参与度回灌（参与度≥2 档 +1 封顶 3——「正在聊的人更投入」）→
                    // ② 相机注意力聚焦（近处角色程序化基线，取 max 只抬不压——Unity 相机聚焦）
                    int level = so.level();
                    GrowthProperties.Attention attCfg = attentionConfig();
                    if (attCfg != null && attCfg.isParticipationBoostEnabled()
                            && participationLevel(projectId, npcRef) >= 2) {
                        level = Math.min(3, level + 1);
                    }
                    int cam = cameraAttentionLevel(projectId, npcRef);
                    if (cam > level) {
                        level = cam;
                    }
                    String tierBefore = brainTierService.tierOf(projectId, npcRef);
                    brainTierService.applyAttention(projectId, npcRef, level, clock);
                    String tierAfter = brainTierService.tierOf(projectId, npcRef);
                    int before = rankOf(tierBefore);
                    int after = rankOf(tierAfter);
                    if (after > before) {
                        promoted++;
                    } else if (after < before) {
                        demoted++;
                    }
                    // 内存观测：注意力档位变化轨迹（调试「注意力卡」；记录合成后的档位与来源）
                    if (!tierBefore.equals(tierAfter)) {
                        push(attentionStats, Map.of(
                                "npcRef", npcRef, "name", nvlName(ch), "level", level,
                                "tierBefore", tierBefore, "tierAfter", tierAfter,
                                "gameSecond", clock == null ? null : clock,
                                "at", java.time.LocalDateTime.now().toString()));
                    }
                }
            }
            Map<String, Object> mic = micro.get(npcRef);
            if (mic != null && mic.get(QID_MICRO_INTENT) instanceof DecisionGate.ChoiceOutcome co
                    && co.adopted() && co.chosen() != null && !co.chosen().isBlank()) {
                if (!"continue_current".equals(co.chosen())) {
                    microNonContinueCount.incrementAndGet();
                    // 内存观测：微决策意向轨迹（调试「微决策卡」；低频记忆另由概率门控写 L1）
                    push(microStats, Map.of(
                            "npcRef", npcRef, "name", nvlName(ch), "intent", co.chosen(),
                            "intentText", intentText(co.chosen()),
                            "gameSecond", clock == null ? null : clock,
                            "at", java.time.LocalDateTime.now().toString()));
                    thoughts += handleMicroIntent(projectId, ch, co.chosen(), clock);
                    if (isActiveIntent(co.chosen())) {
                        interrupts += maybeInterrupt(projectId, ch, co.chosen(), clock);
                    }
                } else {
                    microContinueCount.incrementAndGet();
                }
            }
        }
        log.debug("[认知注意力] 每 tick 批量完成: project={} npcs={} 升档={} 降档={} 念头={} 打断={}",
                projectId, chars.size(), promoted, demoted, thoughts, interrupts);
    }

    /** 事件相关度批量（同步体，异步线程内执行）。 */
    private void eventRelevanceSync(Long projectId, ActorEvent event) {
        // 只评估「在场」NPC（修：偶遇等小范围事件不再评估全 NPC，杜绝无关 NPC 空转与调用放大）
        List<ActorCharacter> chars = presentCharacters(projectId, event);
        if (chars.isEmpty()) {
            return;
        }
        String eventText = event.getContent().trim();
        Map<String, DecisionState> states = new LinkedHashMap<>();
        for (ActorCharacter ch : chars) {
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            String text = buildStateText(projectId, ch, event.getGameHour() == null ? null
                    : event.getGameHour() * 3600L, null)
                    + " 事件：" + truncate(eventText, 80);
            Map<String, DecisionQuestion> questions = DecisionState.newQuestions();
            questions.put(QID_EVENT_RELEVANCE,
                    DecisionTemplates.eventRelevanceQuestion(truncate(eventText, 100)));
            states.put(npcRef, DecisionState.text(npcRef, text, questions));
        }
        Map<String, Map<String, Object>> results =
                decisionGate == null ? Map.of()
                        : decisionGate.mixedBatchDecisions(projectId, DecisionScenes.ATTENTION,
                        states, Map.of(QID_EVENT_RELEVANCE, DecisionQuestion.TYPE_SCORE));
        int promoteLevel = attentionConfig() == null ? 3 : attentionConfig().getEventRelevancePromoteLevel();
        int memoryLevel = attentionConfig() == null ? 2 : attentionConfig().getEventRelevanceMemoryLevel();
        int promoted = 0;
        for (ActorCharacter ch : chars) {
            String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            Map<String, Object> r = results.get(npcRef);
            if (r == null || !(r.get(QID_EVENT_RELEVANCE) instanceof DecisionGate.ScoreOutcome so)
                    || !so.adopted()) {
                continue;
            }
            if (so.level() >= memoryLevel && memoryWorkingService != null) {
                try {
                    memoryWorkingService.writePerception(projectId, npcRef,
                            "听说了这件事：「" + truncate(eventText, 60) + "」，与我有关，记在心里", 2);
                } catch (Exception e) {
                    log.debug("[认知注意力] 事件相关度记忆写入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                }
            }
            if (so.level() >= promoteLevel && brainTierService != null) {
                brainTierService.applyAttention(projectId, npcRef, 3,
                        event.getGameHour() == null ? null : event.getGameHour() * 3600L);
                promoted++;
            }
        }
        if (promoted > 0) {
            log.info("[认知注意力] 事件相关度批量：evt={} 高相关升档 {} 人", event.getId(), promoted);
        }
    }

    /** 微决策「非继续」意向处理：低频记忆轨迹（概率门控），返回写入条数。 */
    private int handleMicroIntent(Long projectId, ActorCharacter ch, String intent, Long gameSecond) {
        String npcRef = com.holzyn.actor.common.NpcRefs.of(ch.getId());
        if (memoryWorkingService == null) {
            return 0;
        }
        double p = attentionConfig() == null ? 0.3 : attentionConfig().getMemoryWriteProbability();
        if (ThreadLocalRandom.current().nextDouble() >= p) {
            return 0;
        }
        try {
            memoryWorkingService.writePerception(projectId, npcRef,
                    "心里泛起一个念头：" + intentText(intent), 1);
            return 1;
        } catch (Exception e) {
            log.debug("[认知注意力] 微决策记忆轨迹写入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
            return 0;
        }
    }

    /** 微决策主动意向打断（极低概率）：意向=搭话/起身活动时按打断概率触发即时决策（MENTAL_URGE）。 */
    private int maybeInterrupt(Long projectId, ActorCharacter ch, String intent, Long gameSecond) {
        if (realTimeDecisionService == null) {
            return 0;
        }
        double p = attentionConfig() == null ? 0.05 : attentionConfig().getInterruptProbability();
        if (ThreadLocalRandom.current().nextDouble() >= p) {
            return 0;
        }
        try {
            String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            realTimeDecisionService.onStimulus(projectId, ref,
                    com.holzyn.actor.domain.agent.runtime.StimulusType.MENTAL_URGE,
                    "心里突然起了个念头，想" + intentText(intent), gameSecond);
            log.debug("[认知注意力] 微决策打断（MENTAL_URGE）: ref={} intent={}", ref, intent);
            return 1;
        } catch (Exception e) {
            log.debug("[认知注意力] 微决策打断失败（跳过）: ref={} : {}", ch.getId(), e.getMessage());
            return 0;
        }
    }

    /** 是否主动意向（只有这些才会触发打断；放空/张望/小动作是被动的）。 */
    private boolean isActiveIntent(String intent) {
        return "chat".equals(intent) || "move_about".equals(intent);
    }

    /** 意向码 → 中文短句。 */
    private static String intentText(String intent) {
        return switch (intent == null ? "" : intent) {
            case "drift" -> "放空片刻，任由思绪飘一会儿";
            case "look_around" -> "张望四顾，看看周围的人和事";
            case "fidget" -> "做点无意识的小动作";
            case "chat" -> "找个人说说话";
            case "move_about" -> "起身活动活动，换个地方待着";
            default -> "继续手头的事";
        };
    }

    /** 角色名（null/空回退引用）。 */
    private static String nvlName(ActorCharacter ch) {
        return ch.getName() == null || ch.getName().isBlank() ? "npc:" + ch.getId() : ch.getName();
    }

    // ==================== 情境/名单辅助 ====================

    /** 存活角色名单（排除 DECEASED 与软删除；普通+特殊全量）。 */
    private List<ActorCharacter> aliveCharacters(Long projectId) {
        if (characterRepository == null) {
            return List.of();
        }
        List<ActorCharacter> out = new ArrayList<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (ch == null || ch.getId() == null) {
                continue;
            }
            if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                continue;
            }
            out.add(ch);
        }
        return out;
    }

    /**
     * 事件相关度候选名单（<b>在场者推导，修：偶遇场景评估全 NPC</b>）：
     * 按事件可见性对齐「谁知晓 / 谁在场」——
     * <ul>
     * <li>{@code global}=全项目知晓 → 全部存活 NPC（行为不变，全评估）；</li>
     * <li>{@code private}=仅当事人 → 只评估 {@code character_ids} 参与者；</li>
     * <li>{@code location}（缺省）=当事人 + 同地点在场者 → 参与者 ∪
     * {@code location_name} 与 NPC 当前 {@code location} 精确匹配者。</li>
     * </ul>
     * 2 人偶遇（move_encounter/pass_by）只有双方当事人在场，其余 NPC 零评估——
     * 既贴合「在场才相关」的真实心智（远处的人不该为偶遇空转），也压掉一次 attention 批量放大。
     * <p>当事人解析用 {@code NpcRefs.parseLenient}（兼容统一 {@code npc:} 前缀与历史
     * {@code special:}/{@code crowd:} 前缀）；同地点匹配仅用 {@code location_name}
     * 快照（NPC 侧只有地点名字段，无位置 ID），未发布地点名的事件不触发同地点扩展。</p>
     */
    private List<ActorCharacter> presentCharacters(Long projectId, ActorEvent event) {
        List<ActorCharacter> alive = aliveCharacters(projectId);
        if (alive.isEmpty()) {
            return List.of();
        }
        String vis = com.holzyn.actor.common.ActorEventFields.normalizeVisibility(event.getVisibility());
        if ("global".equals(vis)) {
            return alive; // 全项目知晓：全部评估（行为不变）
        }
        // 当事人（character_ids 内的角色必在场；去重）
        List<ActorCharacter> present = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        String ids = event.getCharacterIds();
        if (ids != null && !ids.isBlank()) {
            for (String part : ids.split(",")) {
                Long id = com.holzyn.actor.common.NpcRefs.parseLenient(part);
                if (id == null || seen.contains(id)) {
                    continue;
                }
                for (ActorCharacter c : alive) {
                    if (c.getId() != null && c.getId().equals(id)) {
                        present.add(c);
                        seen.add(id);
                        break;
                    }
                }
            }
        }
        if ("private".equals(vis)) {
            return present; // 仅当事人
        }
        // location（缺省）：当事人 + 同地点在场者（location_name 与 NPC 当前 location 精确匹配）
        String loc = event.getLocationName();
        if (loc != null && !loc.isBlank()) {
            String target = loc.trim();
            for (ActorCharacter c : alive) {
                if (c.getLocation() != null && c.getLocation().trim().equals(target)
                        && seen.add(c.getId())) {
                    present.add(c);
                }
            }
        }
        return present;
    }

    /** 单 NPC 情境文本（自包含：名称/当前行动/地点/时刻/需求/在场者/近期见闻）。 */
    private String buildStateText(Long projectId, ActorCharacter ch, Long gameSecond, String recentEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append(ch.getName() == null || ch.getName().isBlank() ? "该角色" : ch.getName());
        sb.append("此刻").append(ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "正在忙自己的事" : truncate(ch.getCurrentActivity().trim(), 40));
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("，在").append(truncate(ch.getLocation().trim(), 30));
        }
        if (worldClockService != null && gameSecond != null) {
            try {
                Long h = gameSecond / 3600L;
                sb.append("，游戏时刻约 ").append(Math.floorMod(h, 24L)).append(" 点");
            } catch (Exception ignored) {
                // 时刻失败跳过
            }
        }
        ActorNpcState st = null;
        if (npcStateService != null) {
            try {
                st = npcStateService.stateFor(projectId, com.holzyn.actor.common.NpcRefs.of(ch.getId()));
            } catch (Exception ignored) {
                // 状态读取失败按未知
            }
        }
        if (st != null) {
            List<String> flags = new ArrayList<>();
            if (st.getHunger() != null && st.getHunger() < 40) {
                flags.add("饿了");
            }
            if (st.getThirst() != null && st.getThirst() < 40) {
                flags.add("口渴");
            }
            if (st.getEnergy() != null && st.getEnergy() < 40) {
                flags.add("疲惫");
            }
            if (st.getSocial() != null && st.getSocial() < 40) {
                flags.add("有点闷");
            }
            if (!flags.isEmpty()) {
                sb.append("，").append(String.join("、", flags));
            }
        }
        // 在场者（同地点活人，除自己；最多 3 个）
        if (characterRepository != null && ch.getLocation() != null) {
            List<String> present = new ArrayList<>();
            for (ActorCharacter other : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                if (other == null || other.getId().equals(ch.getId()) || other.getLocation() == null
                        || !other.getLocation().equals(ch.getLocation())) {
                    continue;
                }
                if (GrowthStage.DECEASED.name().equalsIgnoreCase(other.getStage())) {
                    continue;
                }
                if (other.getName() != null && !other.getName().isBlank()) {
                    present.add(other.getName());
                    if (present.size() >= 3) {
                        break;
                    }
                }
            }
            if (!present.isEmpty()) {
                sb.append("，身边有：").append(String.join("、", present));
            }
        }
        if (recentEvent != null && !recentEvent.isBlank()) {
            sb.append("。最近村里的事：").append(truncate(recentEvent, 60));
        }
        sb.append("。");
        int max = attentionConfig() == null ? 160 : attentionConfig().getStateTextMaxChars();
        return truncate(sb.toString(), max);
    }

    /** 最近事件摘要（项目最近一条 importance≥2 的事件文本；无返回 null）。 */
    private String recentEventText(Long projectId) {
        if (eventRepository == null) {
            return null;
        }
        try {
            for (ActorEvent e : eventRepository.findByProjectIdOrderByIdDesc(projectId)) {
                if (e == null || e.getContent() == null || e.getContent().isBlank()) {
                    continue;
                }
                if (e.getImportance() == null || e.getImportance() < 2) {
                    continue;
                }
                return e.getContent().trim();
            }
        } catch (Exception e) {
            log.debug("[认知注意力] 近期事件读取失败（跳过）: {}", e.getMessage());
        }
        return null;
    }

    /** 当前引擎游戏秒（入参优先，否则世界时钟）。 */
    private Long currentEngineSecond(Long projectId, Long gameSecond) {
        if (gameSecond != null) {
            return gameSecond;
        }
        if (worldClockService != null) {
            try {
                return worldClockService.currentEngineGameSecond(projectId);
            } catch (Exception ignored) {
                // 时钟读取失败按未知
            }
        }
        return null;
    }

    /** 注意力配置（未装配返回 null）。 */
    private GrowthProperties.Attention attentionConfig() {
        return growthProperties == null || !growthProperties.isEnabled() ? null
                : growthProperties.getAttention();
    }

    /** 高频微决策总开关。 */
    private boolean attentionEnabled() {
        GrowthProperties.Attention a = attentionConfig();
        if (a == null || !a.isEnabled()) {
            return false;
        }
        // 两场景全关时也跳过（无判定可做）
        return decisionGate != null
                && (decisionGate.isEnabled(DecisionScenes.ATTENTION)
                || decisionGate.isEnabled(DecisionScenes.MICRO_DECISION));
    }

    /** 档位高低（focus=2/ambient=1/dormant=0）。 */
    private static int rankOf(String tier) {
        if (BrainTierService.TIER_FOCUS.equals(tier)) {
            return 2;
        }
        if (BrainTierService.TIER_AMBIENT.equals(tier)) {
            return 1;
        }
        return 0;
    }

    /** 截断（null/空返回 null；超长截断）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
