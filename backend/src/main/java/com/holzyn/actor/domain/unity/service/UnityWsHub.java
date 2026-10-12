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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import com.holzyn.actor.domain.unity.model.UnityEnvelope;
import com.holzyn.actor.domain.unity.ws.UnityWsHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity 下行推送中心（U 轨道 U1 服务端对接层，）——HA → Unity 数据契约 v1 推送。
 * <p>数据源：注册为 {@link WorldSandboxSseHub} 旁路监听器（U1 新增机制）——沙盘 SSE 的
 * 10 事件契约（moved/arrived/state/thought/talk/join/leave/scene:event/clock/weather）
 * 全部经本中心按项目映射为 Unity envelope 下行推送：</p>
 * <ul>
 * <li>{@code npc:moved/npc:arrived} → {@code npc_position}（移动插值目标 + 到达）；</li>
 * <li>{@code npc:state} → {@code npc_state}（动作/状态切换，含内部状态）；</li>
 * </ul>
 * <p>心跳与看门狗：{@link #heartbeatTick()} 定时下行 heartbeat envelope + 失联扫描
 * （超过 timeout-seconds 未收到客户端任何消息 → 关闭连接 → afterConnectionClosed →
 * 世界静止回退多源判定）；下行发送按会话同步（防并发写交错）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnityWsHub {

    /** Unity 对接配置（心跳间隔/失联阈值） */
    private final UnityProperties props;

    /** Unity 会话注册表（下行目标 + 活性 touch） */
    private final UnitySessionRegistry sessionRegistry;

    /** 协议服务（envelope 编解码） */
    private final UnityProtocolService protocol;

    /** 沙盘 SSE 中心（旁路数据源注册；字段注入避免构造循环，null=未装配[单测]跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldSandboxSseHub sandboxSseHub;

    /** 心跳/失联扫描定时间隔（毫秒；读取配置秒数拼接 000——15 秒） */
    private static final String HEARTBEAT_FIXED_DELAY = "${holzyn.actor.unity.heartbeat.interval-seconds:15}000";

    /**
     * 启动注册旁路监听：所有沙盘广播事件 → 按项目映射为 Unity envelope 下行。
     */
    @PostConstruct
    void init() {
        if (sandboxSseHub == null) {
            log.warn("[Unity下行] 沙盘 SSE 中心未装配，Unity 下行推送不可用（单测/降级环境）");
            return;
        }
        sandboxSseHub.addListener(this::onSandboxEvent);
        log.info("[Unity下行] 已注册沙盘 SSE 旁路监听（10 事件契约 → Unity envelope）");
    }

    /**
     * 沙盘广播事件 → Unity envelope 映射下行（异常隔离，不影响沙盘主通道）。
     *
     * @param event 沙盘广播事件
     */
    private void onSandboxEvent(WorldSandboxSseHub.SandboxEvent event) {
        try {
            if (event == null || event.projectId() == null || event.name() == null) {
                return;
            }
            String downType = mapType(event.name());
            if (downType == null) {
                return;
            }
            Map<String, Object> payload = event.data() == null ? new LinkedHashMap<>() : event.data();
            Long gameSecond = payload.get("gameSecond") instanceof Number n ? n.longValue() : null;
            UnityEnvelope envelope = new UnityEnvelope(downType, null, event.projectId(),
                    gameSecond, payload, null);
            send(event.projectId(), envelope);
        } catch (Exception e) {
            log.debug("[Unity下行] 事件映射/推送失败（隔离继续）: {}", e.getMessage());
        }
    }

    /** 沙盘事件名 → Unity 下行 type 映射（未识别返回 null 忽略）。 */
    private String mapType(String sandboxEventName) {
        return switch (sandboxEventName) {
            case WorldSandboxSseHub.EVENT_NPC_MOVED, WorldSandboxSseHub.EVENT_NPC_ARRIVED ->
                    UnityProtocolService.DOWN_NPC_POSITION;
            case WorldSandboxSseHub.EVENT_NPC_STATE -> UnityProtocolService.DOWN_NPC_STATE;
            case WorldSandboxSseHub.EVENT_NPC_THOUGHT -> UnityProtocolService.DOWN_NPC_THOUGHT;
            case WorldSandboxSseHub.EVENT_NPC_TALK -> UnityProtocolService.DOWN_NPC_TALK;
            case WorldSandboxSseHub.EVENT_NPC_JOIN -> UnityProtocolService.DOWN_NPC_JOIN;
            case WorldSandboxSseHub.EVENT_NPC_LEAVE -> UnityProtocolService.DOWN_NPC_LEAVE;
            case WorldSandboxSseHub.EVENT_SCENE_EVENT -> UnityProtocolService.DOWN_EVENT;
            case WorldSandboxSseHub.EVENT_CLOCK -> UnityProtocolService.DOWN_CLOCK;
            case WorldSandboxSseHub.EVENT_WEATHER -> UnityProtocolService.DOWN_WEATHER;
            case WorldSandboxSseHub.EVENT_NPC_PERSONALITY_SHIFT -> UnityProtocolService.DOWN_NPC_PERSONALITY_SHIFT;
            case WorldSandboxSseHub.EVENT_NPC_MASK_CHANGE -> UnityProtocolService.DOWN_NPC_MASK_CHANGE;
            case WorldSandboxSseHub.EVENT_NPC_BIRTH -> UnityProtocolService.DOWN_NPC_BIRTH;
            case WorldSandboxSseHub.EVENT_NPC_STAGE_TRANSITION -> UnityProtocolService.DOWN_NPC_STAGE_TRANSITION;
            case WorldSandboxSseHub.EVENT_NPC_DEATH -> UnityProtocolService.DOWN_NPC_DEATH;
            default -> null;
        };
    }

    /**
     * 向项目全部 Unity 会话推送信封（目标会话不存在/编码失败隔离；发送成功刷新活性）。
     *
     * @param projectId 项目 ID
     *
     * @param envelope 信封
     */
    public void send(Long projectId, UnityEnvelope envelope) {
        if (projectId == null || envelope == null) {
            return;
        }
        List<WebSocketSession> sessions = sessionRegistry.sessionsOf(projectId);
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = protocol.encode(envelope);
        } catch (Exception e) {
            log.warn("[Unity下行] envelope 编码失败（丢弃）: type={} : {}", envelope.type(), e.getMessage());
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                sessionRegistry.touch(session);
            } catch (IOException | IllegalStateException e) {
                log.debug("[Unity下行] 会话发送失败（移除）: session={} : {}", session.getId(), e.getMessage());
                try {
                    session.close();
                } catch (IOException ignored) {
                    // 关闭失败由容器兜底
                }
            }
        }
    }

    /**
     * 心跳与失联看门狗（定时）：下行 heartbeat envelope + 超时未活跃会话踢出
     * （踢出后 afterConnectionClosed → 世界静止回退多源判定）。
     */
    @Scheduled(fixedDelayString = HEARTBEAT_FIXED_DELAY)
    public void heartbeatTick() {
        try {
            // ① 下行心跳（所有连接）
            sendHeartbeatToAll();
            // ② 失联扫描踢出
            long timeout = props.getHeartbeat().getTimeoutSeconds() > 0
                    ? props.getHeartbeat().getTimeoutSeconds() * 1000L : 45_000L;
            List<WebSocketSession> stale = sessionRegistry.staleSessions(timeout);
            for (WebSocketSession session : stale) {
                log.warn("[Unity] 心跳超时踢出连接: session={}（{} 秒未活跃）",
                        session.getId(), timeout / 1000L);
                try {
                    session.close();
                } catch (Exception e) {
                    log.debug("[Unity] 失联会话关闭失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Unity] 心跳 tick 异常（隔离继续）: {}", e.getMessage());
        }
    }

    /** 向全部已连接项目下行 heartbeat envelope。 */
    private void sendHeartbeatToAll() {
        // 遍历会话（registry 无项目遍历 API，用每项目 isConnected 无法枚举——通过总览
        // 不现实；改为在 registry 提供项目列表？为保持简单，本方法由心跳 tick 直接
        // 对每会话发送，需 registry 暴露全部会话快照——见 registry.allSessions()）
        for (WebSocketSession session : sessionRegistry.allSessions()) {
            Long projectId = (Long) session.getAttributes().get(UnityWsHandler.ATTR_PROJECT_ID);
            if (projectId == null || !session.isOpen()) {
                continue;
            }
            try {
                UnityEnvelope hb = new UnityEnvelope(UnityProtocolService.DOWN_HEARTBEAT,
                        null, projectId, null, Map.of(), null);
                synchronized (session) {
                    session.sendMessage(new TextMessage(protocol.encode(hb)));
                }
                sessionRegistry.touch(session);
            } catch (Exception e) {
                log.debug("[Unity] 心跳发送失败（忽略）: {}", e.getMessage());
            }
        }
    }
}
