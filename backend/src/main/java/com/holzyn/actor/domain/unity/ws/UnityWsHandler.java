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

package com.holzyn.actor.domain.unity.ws;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.sandbox.controller.SandboxController;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import com.holzyn.actor.domain.unity.model.UnityEnvelope;
import com.holzyn.actor.domain.unity.service.ExternalClockService;
import com.holzyn.actor.domain.unity.service.UnityEnvironmentService;
import com.holzyn.actor.domain.unity.service.UnityPerceptionService;
import com.holzyn.actor.domain.unity.service.UnityProtocolService;
import com.holzyn.actor.domain.unity.service.UnitySessionRegistry;
import com.holzyn.actor.domain.unity.service.UnityWsHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity WebSocket 处理器（U 轨道 U1 服务端对接层，）——/ws/unity/{projectId} 主通道。
 * <p>职责：连接生命周期（握手校验 → 注册 → <b>独占激活</b>→ connected 确认 → 断开注销 →
 * 静止回退）+ 上行消息分发（hello/heartbeat/clock_report/perception/environment/
 * player_action/intervention，seq 幂等对账在注册表）。</p>
 * <p>上行处理原则：<b>毫秒级返回</b>——clock_report 异步入队（虚拟线程推进，防阻塞 WS 线程
 * 被心跳看门狗自踢）；perception/environment 同步轻量处理（触发链内部异步）；intervention
 * 委托 {@link SandboxController} 同源方法（WS 兜底，REST /intervene/* 为推荐路径）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnityWsHandler extends TextWebSocketHandler {

    /** 握手拦截器写入的会话属性键（projectId） */
    public static final String ATTR_PROJECT_ID = "unity.projectId";

    private final UnityProperties props;
    private final UnityProtocolService protocol;
    private final UnitySessionRegistry sessionRegistry;
    private final UnityWsHub unityWsHub;
    private final ExternalClockService externalClockService;
    private final UnityPerceptionService unityPerceptionService;
    private final UnityEnvironmentService unityEnvironmentService;
    private final ActorProjectRepository projectRepository;
    private final RealtimeDecisionActivationService activationService;
    private final SandboxController sandboxController;

    // ==================== 连接生命周期 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long projectId = (Long) session.getAttributes().get(ATTR_PROJECT_ID);
        if (projectId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("缺少 projectId"));
            return;
        }
        // 项目校验（不存在/已删除拒绝连接）
        boolean valid = projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .isPresent();
        if (!valid) {
            log.warn("[Unity] 连接拒绝：项目不存在或已删除: project={} session={}", projectId, session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("项目不存在或已删除"));
            return;
        }
        sessionRegistry.register(projectId, session);
        // U1 独占激活：连接 = 世界激活（重置引擎回补基准，接续语义）
        activationService.markUnityConnected(projectId);
        sendConnected(session, projectId);
        log.info("[Unity] 连接建立: project={} session={}", projectId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long projectId = (Long) session.getAttributes().get(ATTR_PROJECT_ID);
        if (projectId != null) {
            sessionRegistry.unregister(projectId, session);
            // 最后一个连接断开 → 世界静止回退多源判定（时钟权威回退内部自走）
            if (!sessionRegistry.isConnected(projectId)) {
                activationService.markUnityDisconnected(projectId);
            }
        }
        log.info("[Unity] 连接关闭: project={} session={} status={}", projectId, session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[Unity] 传输错误: session={} : {}", session.getId(), exception.getMessage());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // 关闭失败由容器兜底
        }
    }

    // ==================== 上行消息分发 ====================

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!props.isEnabled()) {
            return;
        }
        Long sessionProject = (Long) session.getAttributes().get(ATTR_PROJECT_ID);
        UnityEnvelope env;
        try {
            env = protocol.decode(message.getPayload());
        } catch (BizException e) {
            sendError(session, sessionProject, e.getMessage());
            return;
        }
        // 归属校验：信封 projectId（可空=沿用连接路径）必须与连接路径一致
        Long projectId = env.projectId() != null ? env.projectId() : sessionProject;
        if (projectId == null || !projectId.equals(sessionProject)) {
            sendError(session, sessionProject, "projectId 与连接路径不一致（连接为 /ws/unity/" + sessionProject + "）");
            return;
        }
        sessionRegistry.touch(session);
        String traceId = env.traceId() != null && !env.traceId().isBlank()
                ? env.traceId() : protocol.newTraceId();

        // 类型白名单
        if (!protocol.isUpstreamType(env.type())) {
            sendError(session, projectId, "未知上行 type：" + env.type());
            return;
        }
        // seq 幂等对账（断点续传：重复/回退丢弃）
        if (!sessionRegistry.acceptSeq(projectId, env.seq())) {
            log.debug("[Unity] 重复 seq 丢弃: project={} type={} seq={} trace={}",
                    projectId, env.type(), env.seq(), traceId);
            return;
        }

        Map<String, Object> payload = env.payload() == null ? Map.of() : env.payload();
        try {
            switch (env.type()) {
                case UnityProtocolService.UP_HELLO -> sendConnected(session, projectId);
                case UnityProtocolService.UP_HEARTBEAT ->
                        log.debug("[Unity] 心跳: project={} session={}", projectId, session.getId());
                case UnityProtocolService.UP_CLOCK_REPORT -> {
                    Long gs = num(payload, "gameSecond");
                    Integer rate = intOf(payload, "rate");
                    externalClockService.submitClockReport(projectId, gs, rate, traceId);
                }
                case UnityProtocolService.UP_PERCEPTION -> {
                    List<String> refs = strList(payload, "targetNpcRefs");
                    String text = str(payload, "text");
                    Long gs = num(payload, "gameSecond");
                    Boolean broadcast = bool(payload, "broadcast");
                    unityPerceptionService.onPerceptionReport(projectId, refs, text, gs, broadcast);
                }
                case UnityProtocolService.UP_ENVIRONMENT -> {
                    String weather = str(payload, "weather");
                    Double temperature = dbl(payload, "temperature");
                    String wind = str(payload, "wind");
                    unityEnvironmentService.applyEnvironmentReport(projectId, weather, temperature, wind);
                }
                case UnityProtocolService.UP_PLAYER_ACTION -> log.info(
                        "[Unity] 玩家行动留痕: project={} action={} target={} text={} trace={}",
                        projectId, str(payload, "action"), str(payload, "targetNpcRef"),
                        str(payload, "text"), traceId);
                case UnityProtocolService.UP_INTERVENTION -> handleIntervention(projectId, payload, traceId);
                default -> sendError(session, projectId, "未处理的上行 type：" + env.type());
            }
        } catch (Exception e) {
            log.warn("[Unity] 上行处理失败: project={} type={} : {} trace={}",
                    projectId, env.type(), e.getMessage(), traceId);
            sendError(session, projectId, "处理失败：" + e.getMessage());
        }
    }

    // ==================== 干预（WS 兜底，委托沙盘干预同源方法） ====================

    /**
     * WS 干预命令（REST /intervene/* 为推荐路径，本通道兜底）：payload{command: talk|action|
     * event|clock|weather, ...业务字段同 REST} → 委托 {@link SandboxController} 同名方法，
     * 执行结果经 {@code intervention_result} 信封回执。
     */
    private void handleIntervention(Long projectId, Map<String, Object> payload, String traceId) {
        String command = str(payload, "command");
        if (command == null) {
            sendError(null, projectId, "intervention 缺少 command（talk/action/event/clock/weather）");
            return;
        }
        Object result;
        switch (command) {
            case "talk" -> result = sandboxController.interveneTalk(projectId, payload);
            case "action" -> result = sandboxController.interveneAction(projectId, payload);
            case "event" -> result = sandboxController.interveneEvent(projectId, payload);
            case "clock" -> result = sandboxController.interveneClock(projectId, payload);
            case "weather" -> result = sandboxController.switchWeather(projectId, payload);
            default -> {
                sendError(null, projectId, "未知干预 command：" + command);
                return;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        if (result instanceof R<?> r) {
            out.put("data", r.getData());
            out.put("message", r.getMessage());
        } else {
            out.put("data", result);
        }
        unityWsHub.send(projectId, new UnityEnvelope(UnityProtocolService.DOWN_INTERVENTION_RESULT,
                null, projectId, null, out, traceId));
    }

    // ==================== 内部工具 ====================

    /** 连接确认信封（hello 回复/连接建立即推送）。 */
    private void sendConnected(WebSocketSession session, Long projectId) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message", "Unity 通道已连接（契约 v1）");
            m.put("projectId", projectId);
            m.put("protocol", "v1");
            m.put("sessionId", session.getId());
            m.put("serverTime", System.currentTimeMillis());
            m.put("clockSource", "unity");
            sendDirect(session, new UnityEnvelope(UnityProtocolService.DOWN_CONNECTED,
                    null, projectId, null, m, null));
        } catch (Exception e) {
            log.warn("[Unity] connected 信封发送失败: {}", e.getMessage());
        }
    }

    /** 错误回执（session 可空=按项目广播）。 */
    private void sendError(WebSocketSession session, Long projectId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        UnityEnvelope err = new UnityEnvelope(UnityProtocolService.DOWN_ERROR,
                null, projectId, null, m, null);
        if (session != null) {
            sendDirect(session, err);
        } else if (projectId != null) {
            unityWsHub.send(projectId, err);
        }
    }

    /** 直发单会话（同步 + 活性刷新）。 */
    private void sendDirect(WebSocketSession session, UnityEnvelope envelope) {
        try {
            String json = protocol.encode(envelope);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
            sessionRegistry.touch(session);
        } catch (Exception e) {
            log.debug("[Unity] 直发失败: {}", e.getMessage());
        }
    }

    // ==================== 载荷取值工具（null 安全） ====================

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
