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

package com.holzyn.actor.domain.sandbox.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.agent.event.ConversationLiveSseHub;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.agent.event.WorldEventViews;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.sandbox.vo.SandboxOverviewVO;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界沙盘 SSE 广播中心（世界沙盘 S2，domain/sandbox/service）——项目级聚合订阅 Hub。
 * <p>职责（主开发方案 §6.2/§10.1 S2）：维护「项目 → SSE 订阅者」集合（{@code GET
 * /api/worlds/{id}/sandbox/stream}，订阅型长连接：无超时 + 15s 心跳 + 终止自动移除），
 * 把沙盘相关的实时变化<b>合并为一条流</b>推送：</p>
 * <ul>
 * <li><b>10 事件契约</b>：{@code sandbox:init}（订阅后回放全量快照）/ {@code npc:moved} /
 * <li><b>事件来源</b>：WorldSandboxStateService（moved/arrived/state/thought/join/leave 发布）；
 * 本 Hub 自注册为 {@link WorldEventBus} 订阅者 → {@code scene:event}（世界事件转发，载荷同
 * {@link WorldEventViews}）；自注册为 {@link ConversationLiveSseHub} 监听器 → {@code npc:talk}
 * （对话消息实时流入沙盘气泡）；WorldEngine/WorldClockService 挂钩 → {@code clock}。</li>
 * </ul>
 * <p>与既有 Hub 的差异：沙盘 Hub 是<b>项目级聚合订阅</b>（位置/动作/想法/对话/事件合并一条流，
 * 前端统一消费）；既有 WorldEventSseHub/ActionSseHub 保留不动（其它页面仍用）。</p>
 * <p>开关：{@code holzyn.actor.sandbox.sse-enabled}（默认 true）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldSandboxSseHub implements WorldEventSubscriber {

    // ==================== 10 事件契约常量（与主开发方案 §6.2 一一对应，勿改） ====================

    /** 订阅后回放的全量快照（场景 + 全部 NPC 状态 + 时钟） */
    public static final String EVENT_INIT = "sandbox:init";
    /** NPC 开始移动 {npcRef, from, to, path[], durationGameSec} */
    public static final String EVENT_NPC_MOVED = "npc:moved";
    /**
     * NPC 真实移动开始/途中（M2 连续移动状态机）{npcRef, from, to, progress, arriveGameSecond, reason}
     * ——与 npc:moved 并存：moved=坐标跳变/落位（旧语义），walking=真实移动开始（插值推进）
     */
    public static final String EVENT_NPC_WALKING = "npc:walking";
    /** NPC 到达锚点 {npcRef, pos, heading} */
    public static final String EVENT_NPC_ARRIVED = "npc:arrived";
    /** 动作/状态切换 {npcRef, moveState, actionText, statusText, gameSecond} */
    public static final String EVENT_NPC_STATE = "npc:state";
    /** 想法气泡刷新 {npcRef, text, source, gameSecond} */
    public static final String EVENT_NPC_THOUGHT = "npc:thought";
    /** 对话实时 {npcRef, text, convId, kind} */
    public static final String EVENT_NPC_TALK = "npc:talk";
    /** 角色进出场景 {npcRef, name, npcType} */
    public static final String EVENT_NPC_JOIN = "npc:join";
    /** 角色离开场景 {npcRef, name, npcType} */
    public static final String EVENT_NPC_LEAVE = "npc:leave";
    /** 世界事件/环境变化可视化（载荷同 WorldEventViews） */
    public static final String EVENT_SCENE_EVENT = "scene:event";
    /** 世界时钟/暂停态 {gameDay, gameHour, paused, gameTimeText} */
    public static final String EVENT_CLOCK = "clock";
    /** 天气变化（S4：WeatherSwitchService 推送，{region, condition, temperature, wind, gameSecond, message}） */
    public static final String EVENT_WEATHER = "weather";
    /**
     * 人格偏移下行 {npcRef, npcName, dim, field, old, new, delta, why}（验收⑥：
     * |Δ|>0.15 走 SSE/Unity 下行；UnityWsHub 映射 npc_personality_shift 信封）
     */
    public static final String EVENT_NPC_PERSONALITY_SHIFT = "npc:personality_shift";
    /**
     * 面具变化下行 {npcRef, contextCode, contextLabel, suppression, cause}（破绽松动/周期固化；
     * UnityWsHub 映射 npc_mask_change 信封）
     */
    public static final String EVENT_NPC_MASK_CHANGE = "npc:mask_context_change";
    /**
     * 新生儿出生下行 {npcRef, npcName, gender, motherRef, fatherRef, gameSecond}
     * （UnityWsHub 映射 npc_birth 信封；同时复用 npc:join 入场景）
     */
    public static final String EVENT_NPC_BIRTH = "npc:birth";
    /**
     * 阶段跨越下行 {npcRef, npcName, fromStage, toStage, age, gameSecond}
     */
    public static final String EVENT_NPC_STAGE_TRANSITION = "npc:stage_transition";
    /**
     * 死亡下行 {npcRef, npcName, reason, gameSecond}
     */
    public static final String EVENT_NPC_DEATH = "npc:death";

    /** SSE 生命周期统一工具 */
    private final SseLifecycle sseLifecycle;

    /**
     * 沙盘状态服务（init 快照回放；字段注入避免与 WorldSandboxStateService.sandboxHub 形成构造循环，
     * null=快照字段跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldSandboxStateService sandboxStateService;

    /** 世界事件总线（scene:event 转发源） */
    private final WorldEventBus worldEventBus;

    /** 会话实时订阅 Hub（npc:talk 监听源） */
    private final ConversationLiveSseHub conversationLiveSseHub;

    /** 会话仓库（talk 消息 → 项目解析） */
    private final ActorConversationRepository conversationRepository;

    /** 特殊角色仓库（talk 消息 characterId → npcRef 解析） */
    private final ActorCharacterRepository characterRepository;

    /** 普通人群仓库（talk 消息 characterId → npcRef 解析） */
    private final ActorCharacterRepository ordinaryNpcRepository;

    /**
     * 世界时钟服务（init/clock 载荷组装；字段注入——WorldClockService.sandboxStateService →
     * WorldSandboxStateService.sandboxHub → 本 Hub 的构造引用会成环；null=clock 载荷跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldClockService worldClockService;

    /** 沙盘 SSE 总开关（默认 true；@Value 仅生产注入，默认值保证单测/缺配置下广播可用） */
    @Value("${holzyn.actor.sandbox.sse-enabled:true}")
    private boolean sseEnabled = true;

    /** 订阅者集合（项目 → 发射器集合，并发安全） */
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emittersByProject = new ConcurrentHashMap<>();

    /**
     * 旁路监听器集合（U1 Unity 对接层 UnityWsHub 注册为监听器，所有 publish
     * 事件同步转发——单点数据源，SSE 与 WS 下行零重复逻辑；异常隔离不影响 SSE 主通道）
     */
    private final java.util.Set<java.util.function.Consumer<SandboxEvent>> listeners =
            ConcurrentHashMap.newKeySet();

    /**
     * 沙盘广播事件（旁路监听器载荷，U1）。
     *
     * @param projectId 项目 ID
     *
     * @param name 事件名（EVENT_* 常量）
     *
     * @param data 载荷 Map
     */
    public record SandboxEvent(Long projectId, String name, Map<String, Object> data) {
    }

    /**
     * 注册旁路监听器（U1 Unity 下行数据源；任意 publish 事件转发，调用方自行过滤所需事件）。
     *
     * @param listener 监听器（载荷=SandboxEvent；异常隔离，不影响发布方）
     */
    public void addListener(java.util.function.Consumer<SandboxEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 旁路监听器数（调试统计）。 */
    public int listenerCount() {
        return listeners.size();
    }

    /**
     * 启动注册旁路消费：本 Hub 作为 {@link WorldEventBus} 订阅者（scene:event 转发）+
     * {@link ConversationLiveSseHub} 监听器（npc:talk 转发）。
     */
    @PostConstruct
    void init() {
        try {
            worldEventBus.subscribe(this);
            conversationLiveSseHub.addListener(this::onConversationMessage);
            log.info("[沙盘SSE] 已注册旁路消费：WorldEventBus(scene:event) + ConversationLiveSseHub(npc:talk)");
        } catch (Exception e) {
            log.warn("[沙盘SSE] 旁路消费注册失败（沙盘事件不转发，其余功能不受影响）: {}", e.getMessage());
        }
    }

    /**
     * 订阅项目沙盘实时流（GET /api/worlds/{id}/sandbox/stream 使用）。
     * <p>订阅即推送 {@code connected} 确认 + {@code sandbox:init} 全量快照（场景 + 全部 NPC 状态 +
     * 时钟），前端据此渲染初始态，后续经 10 事件增量更新。</p>
     *
     * @param projectId 项目 ID
     *
     * @return SSE 发射器（订阅型：无超时 + 15s 心跳，连接保持至页面关闭）
     */
    public SseEmitter subscribe(Long projectId) {
        // 终止回调需引用 emitter 自身，用 holder 绕过 lambda 捕获「未初始化」限制
        SseEmitter[] holder = new SseEmitter[1];
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS,
                () -> remove(projectId, holder[0]));
        holder[0] = emitter;
        emittersByProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        // 订阅即推送确认，避免代理缓冲导致连接不建立
        sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED,
                Map.of("message", "世界沙盘已订阅", "projectId", projectId));
        // 回放全量快照（init）
        sendInit(projectId, emitter);
        return emitter;
    }

    // ==================== 事件发布（对外入口，各业务挂钩调用） ====================

    /**
     * 向项目订阅者广播一条沙盘事件（事件名自带命名空间，如 npc:moved）。
     *
     * @param projectId 项目 ID
     *
     * @param eventName 事件名（{@link #EVENT_*} 常量）
     *
     * @param data 载荷 Map
     */
    public void publish(Long projectId, String eventName, Map<String, Object> data) {
        if (!sseEnabled || projectId == null || eventName == null) {
            return;
        }
        Set<SseEmitter> emitters = emittersByProject.get(projectId);
        if (emitters != null && !emitters.isEmpty()) {
            for (SseEmitter emitter : emitters) {
                if (!sseLifecycle.send(emitter, eventName, data)) {
                    // 连接断开：移除订阅者
                    emitters.remove(emitter);
                }
            }
        }
        // U1 旁路转发（Unity WS 下行数据源；异常隔离不影响 SSE 主通道）
        if (!listeners.isEmpty()) {
            SandboxEvent event = new SandboxEvent(projectId, eventName, data);
            for (java.util.function.Consumer<SandboxEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    log.debug("[沙盘SSE] 旁路监听器异常（隔离继续）: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * NPC 开始移动（onNpcMoved 挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param from 起点坐标 [x, y, z]
     *
     * @param to 终点坐标 [x, y, z]
     *
     * @param durationGameSec 移动耗时（游戏秒，S1 无路径模拟按 10 秒占位）
     */
    public void publishNpcMoved(Long projectId, String npcRef, double[] from, double[] to, long durationGameSec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("from", posList(from));
        m.put("to", posList(to));
        m.put("path", java.util.List.of(posList(from), posList(to))); // S1 直线路径
        m.put("durationGameSec", durationGameSec);
        publish(projectId, EVENT_NPC_MOVED, m);
    }

    /**
     * NPC 真实移动开始/途中（M2 连续移动状态机，MovementService 挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param from 起点坐标 [x, y, z]（沙盘中心化域）
     *
     * @param to 终点坐标 [x, y, z]
     *
     * @param progress 移动进度（0~1）
     *
     * @param arriveGameSecond 预计到达游戏秒
     *
     * @param reason 触发源（plan/decision/physio/home/sandbox）
     */
    public void publishNpcWalking(Long projectId, String npcRef, double[] from, double[] to,
                                  double progress, Long arriveGameSecond, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("from", posList(from));
        m.put("to", posList(to));
        m.put("progress", progress);
        m.put("arriveGameSecond", arriveGameSecond);
        m.put("reason", reason);
        publish(projectId, EVENT_NPC_WALKING, m);
    }

    /**
     * NPC 到达锚点（onNpcMoved 落库后调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param pos 坐标 [x, y, z]
     *
     * @param heading 朝向（弧度）
     */
    public void publishNpcArrived(Long projectId, String npcRef, double[] pos, double heading) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("pos", posList(pos));
        m.put("heading", heading);
        publish(projectId, EVENT_NPC_ARRIVED, m);
    }

    /**
     * 动作/状态切换（状态行更新后调用；无内部状态版——兼容既有调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param moveState 移动状态
     *
     * @param actionText 动作描述
     *
     * @param statusText 状态摘要
     *
     * @param gameSecond 游戏时刻（秒）
     */
    public void publishNpcState(Long projectId, String npcRef, String moveState,
                                String actionText, String statusText, Long gameSecond) {
        publishNpcState(projectId, npcRef, moveState, actionText, statusText, gameSecond, null);
    }

    /**
     * 动作/状态切换 + 内部状态。
     * <p>载荷：沙盘字段（moveState/actionText/statusText/gameSecond）+ 内部状态（需求 hunger/thirst/
     * energy/bladder/health + mood + 主导情绪/强度 + PAD 三维 + urgencyLevel/urgencyText），
     * 前端据此渲染需求条/情绪气泡免刷新。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param moveState 移动状态
     *
     * @param actionText 动作描述
     *
     * @param statusText 状态摘要
     *
     * @param gameSecond 游戏时刻（秒）
     *
     * @param state 内部状态行
     */
    public void publishNpcState(Long projectId, String npcRef, String moveState,
                                String actionText, String statusText, Long gameSecond, ActorNpcState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("moveState", moveState);
        m.put("actionText", actionText);
        m.put("statusText", statusText);
        m.put("gameSecond", gameSecond);
        if (state != null) {
            m.put("hunger", state.getHunger());
            m.put("thirst", state.getThirst());
            m.put("energy", state.getEnergy());
            m.put("bladder", state.getBladder());
            m.put("health", state.getHealth());
            m.put("mood", state.getMood());
            m.put("dominantEmotion", state.getDominantEmotion());
            m.put("emotionIntensity", state.getEmotionIntensity());
            m.put("emotionPleasure", state.getEmotionPleasure());
            m.put("emotionArousal", state.getEmotionArousal());
            m.put("emotionDominance", state.getEmotionDominance());
            m.put("urgencyLevel", state.getUrgencyLevel());
            m.put("urgencyText", state.getUrgencyText());
        }
        publish(projectId, EVENT_NPC_STATE, m);
    }

    /**
     * 想法气泡刷新（publishThought 去重后调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param text 想法文本
     *
     * @param source 想法来源（derived/ai/reflection/perception）
     *
     * @param gameSecond 游戏时刻（秒）
     */
    public void publishNpcThought(Long projectId, String npcRef, String text, String source, Long gameSecond) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("text", text);
        m.put("source", source);
        m.put("gameSecond", gameSecond);
        publish(projectId, EVENT_NPC_THOUGHT, m);
    }

    /**
     * NPC 对话实时（对话消息落库后经 ConversationLiveSseHub 监听转发）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param text 对话文本
     *
     * @param conversationId 会话 ID
     *
     * @param kind 对话类别（single/group/autonomous）
     */
    public void publishNpcTalk(Long projectId, String npcRef, String text, Long conversationId, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("text", text);
        m.put("convId", conversationId);
        m.put("kind", kind == null ? "talk" : kind);
        publish(projectId, EVENT_NPC_TALK, m);
    }

    /**
     * NPC 进入场景（角色/人群创建挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param npcType NPC 类型（special/crowd）
     *
     * @param name NPC 名称
     */
    public void publishNpcJoin(Long projectId, String npcRef, String npcType, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("npcType", npcType);
        m.put("name", name);
        publish(projectId, EVENT_NPC_JOIN, m);
    }

    /**
     * NPC 离开场景（角色/人群删除挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @param npcType NPC 类型（special/crowd）
     *
     * @param name NPC 名称
     */
    public void publishNpcLeave(Long projectId, String npcRef, String npcType, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", npcRef);
        m.put("npcType", npcType);
        m.put("name", name);
        publish(projectId, EVENT_NPC_LEAVE, m);
    }

    /**
     * 世界时钟/暂停态（WorldEngine 推进与 WorldClockService 更新挂钩调用）。
     *
     * @param projectId 项目 ID
     *
     * @param paused 是否暂停（0/1）
     */
    public void publishClock(Long projectId, Integer paused) {
        Map<String, Object> m = clockPayload(projectId, paused);
        if (m == null) {
            return;
        }
        publish(projectId, EVENT_CLOCK, m);
    }

    // ==================== 旁路消费实现 ====================

    /**
     * WorldEventSubscriber：世界事件发布 → {@code scene:event} 转发（载荷同 WorldEventViews，
     * 沙盘事件提示条与环境可视化消费）。
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (event == null || event.getProjectId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = WorldEventViews.toMap(event, worldClockService);
            publish(event.getProjectId(), EVENT_SCENE_EVENT, payload);
        } catch (Exception e) {
            log.warn("[沙盘SSE] scene:event 转发失败: event={} : {}", event.getId(), e.getMessage());
        }
    }

    /**
     * ConversationLiveSseHub 监听器：对话消息落库广播 → {@code npc:talk} 转发。
     * <p>只转发「NPC 发言」（characterId 非空 + content 非空；系统叙述 characterId 为空被跳过），
     * npcRef 由 characterId 双轨解析（special 优先，其次 crowd）。</p>
     *
     * @param conversationId 会话 ID
     *
     * @param data 消息载荷（messageId/characterId/characterName/role/type/content/gameTimeText）
     */
    private void onConversationMessage(Long conversationId, Map<String, Object> data) {
        if (conversationId == null || data == null) {
            return;
        }
        try {
            Object cid = data.get("characterId");
            if (cid == null) {
                return; // 系统叙述（角色无关）不进 NPC 对话气泡
            }
            long id = ((Number) cid).longValue();
            String text = data.get("content") == null ? null : String.valueOf(data.get("content"));
            if (text == null || text.isBlank()) {
                return;
            }
            Long projectId = conversationRepository.findById(conversationId)
                    .map(ActorConversation::getProjectId).orElse(null);
            if (projectId == null) {
                return;
            }
            String npcRef = resolveRef(projectId, id);
            if (npcRef == null) {
                return;
            }
            publishNpcTalk(projectId, npcRef, truncate(text, 200), conversationId, "talk");
        } catch (Exception e) {
            log.debug("[沙盘SSE] npc:talk 转发跳过: conv={} : {}", conversationId, e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /** 订阅后回放 init 全量快照（场景 + 全部 NPC 状态 + 时钟）。 */
    private void sendInit(Long projectId, SseEmitter emitter) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", projectId);
            if (sandboxStateService != null) {
                SandboxOverviewVO overview = sandboxStateService.overview(projectId);
                m.put("sceneCount", overview.getSceneCount());
                m.put("scenes", overview.getScenes());
                m.put("states", overview.getStates());
                m.put("stateCount", overview.getStateCount());
            }
            Map<String, Object> clock = clockPayload(projectId, null);
            if (clock != null) {
                m.put("clock", clock);
            }
            sseLifecycle.send(emitter, EVENT_INIT, m);
        } catch (Exception e) {
            log.warn("[沙盘SSE] init 快照回放失败: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 时钟载荷组装（读时钟行转 VO；paused 覆盖可空；时钟服务不可用返回 null）。
     * <p>（世界演化与游戏时间系统，）：载荷扩展 gameMinute / gameDayMinute / refRealTime——
     * 前端沙盘时钟条/总览仪表盘据此本地按 rate 连续插值跳动（暂停冻结不跳）；纯后端加字段，
     * 前端消费在。</p>
     */
    private Map<String, Object> clockPayload(Long projectId, Integer pausedOverride) {
        try {
            if (worldClockService == null) {
                return null;
            }
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            var vo = worldClockService.toVO(clock);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", projectId);
            m.put("gameDay", vo.getDay());
            m.put("gameHour", vo.getGameHour());
            m.put("gameSecond", vo.getGameSecond());
            m.put("gameMinute", vo.getGameMinute());
            m.put("gameDayMinute", vo.getGameDayMinute());
            m.put("refRealTime", vo.getRefRealTime());
            m.put("paused", pausedOverride != null ? pausedOverride : vo.getPaused());
            m.put("rate", vo.getRate());
            m.put("worldRunning", vo.getWorldRunning());
            m.put("gameTimeText", vo.getGameTimeText());
            // 世界时钟显示口径切换：引擎实际推进位置（不虚快）+ 插值锚点 + 插值钳制值——
            // 前端「世界时钟」显示世界的实际时间（引擎推进位置），而非流动时钟换算时间
            m.put("engineGameSecond", vo.getEngineGameSecond());
            m.put("engineRefRealTime", vo.getEngineRefRealTime());
            m.put("engineTickGameSeconds", vo.getEngineTickGameSeconds());
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** characterId → npcRef 双轨解析（special 优先，其次 crowd；都无返回 null）。 */
    private String resolveRef(Long projectId, long id) {
        ActorCharacter ch = characterRepository.findById(id).orElse(null);
        if (ch != null && projectId.equals(ch.getProjectId()) && Integer.valueOf(0).equals(ch.getDeleted())) {
            return com.holzyn.actor.common.NpcRefs.of(id);
        }
        ActorCharacter npc = ordinaryNpcRepository.findById(id).orElse(null);
        if (npc != null && projectId.equals(npc.getProjectId())) {
            return com.holzyn.actor.common.NpcRefs.of(id);
        }
        return null;
    }

    /** 坐标数组 → List（[x,y,z]；null 时用空数组占位）。 */
    private java.util.List<Double> posList(double[] pos) {
        if (pos == null || pos.length < 3) {
            return java.util.List.of(0d, 0d, 0d);
        }
        return java.util.List.of(pos[0], pos[1], pos[2]);
    }

    /** 文本截断（talk 载荷长度保护）。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 移除订阅者（终止回调与发送失败统一入口）。 */
    private void remove(Long projectId, SseEmitter emitter) {
        Set<SseEmitter> set = emittersByProject.get(projectId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 当前全局活跃订阅数（调试概览展示） */
    public int size() {
        return emittersByProject.values().stream().mapToInt(Set::size).sum();
    }

    /** 指定项目活跃订阅数 */
    public int size(Long projectId) {
        Set<SseEmitter> set = emittersByProject.get(projectId);
        return set == null ? 0 : set.size();
    }
}
