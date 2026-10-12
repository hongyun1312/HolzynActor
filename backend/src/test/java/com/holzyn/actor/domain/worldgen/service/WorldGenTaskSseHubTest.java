package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenTaskSseHub（M1 引擎骨架：任务 SSE 进度中枢 + 最近事件回放）单元测试。
 * <p>职责：验证——先订阅回放缓冲历史事件再接实时、广播送达在线订阅者、
 * 终态 complete 后迟到订阅者回放含 result 且不再实时订阅、unsubscribe 移除订阅者。</p>
 * <p>说明：SseLifecycle 用 Mockito mock（send 恒 true）；SseEmitter 用真实对象（无容器，仅记录调用）。</p>
 */
class WorldGenTaskSseHubTest {

    private SseLifecycle sseLifecycle;
    private WorldGenTaskSseHub hub;

    @BeforeEach
    void setUp() {
        sseLifecycle = Mockito.mock(SseLifecycle.class);
        when(sseLifecycle.send(any(), anyString(), any())).thenReturn(true);
        hub = new WorldGenTaskSseHub(sseLifecycle);
    }

    @Test
    void subscribeReplaysBufferThenReceivesLive() {
        hub.broadcast(1L, SseLifecycle.EVENT_LOG, Map.of("level", "info", "message", "开始", "time", "10:00:00"));
        hub.broadcast(1L, SseLifecycle.EVENT_STAGE, Map.of("name", "维度A", "index", 1, "total", 9));

        SseEmitter emitter = new SseEmitter();
        boolean terminal = hub.subscribe(1L, emitter);
        assertFalse(terminal, "任务未终态，应接入实时订阅");
        // 回放 2 条历史事件
        verify(sseLifecycle, times(2)).send(eq(emitter), anyString(), any());

        // 实时广播送达
        hub.broadcast(1L, SseLifecycle.EVENT_LOG, Map.of("level", "info", "message", "继续", "time", "10:00:01"));
        verify(sseLifecycle, times(3)).send(eq(emitter), anyString(), any());
    }

    @Test
    void completeThenLateSubscriberReplaysIncludingResult() {
        hub.broadcast(1L, SseLifecycle.EVENT_LOG, Map.of("level", "info", "message", "开始", "time", "10:00:00"));
        hub.complete(1L, SseLifecycle.EVENT_RESULT, Map.of("taskId", 1L, "status", "succeeded"));
        assertTrue(hub.isTerminal(1L));

        SseEmitter late = new SseEmitter();
        boolean terminal = hub.subscribe(1L, late);
        assertTrue(terminal, "任务已终态，订阅方应 complete 发射器");
        // 回放 log + result 两条
        verify(sseLifecycle, times(2)).send(eq(late), anyString(), any());
    }

    @Test
    void unsubscribeStopsDelivery() {
        hub.broadcast(1L, SseLifecycle.EVENT_LOG, Map.of("level", "info", "message", "开始", "time", "10:00:00"));
        SseEmitter emitter = new SseEmitter();
        hub.subscribe(1L, emitter); // 回放 1 条
        hub.unsubscribe(1L, emitter);

        hub.broadcast(1L, SseLifecycle.EVENT_LOG, Map.of("level", "info", "message", "x", "time", "10:00:01"));
        // 退订后：仅订阅时回放的 1 条，之后无实时投递
        verify(sseLifecycle, times(1)).send(eq(emitter), anyString(), any());
    }

    @Test
    void failBroadcastsErrorAndTerminates() {
        SseEmitter emitter = new SseEmitter();
        hub.subscribe(1L, emitter);
        hub.fail(1L, "节点执行失败");
        assertTrue(hub.isTerminal(1L));
        verify(sseLifecycle).send(eq(emitter), eq(SseLifecycle.EVENT_ERROR), any());
    }
}
