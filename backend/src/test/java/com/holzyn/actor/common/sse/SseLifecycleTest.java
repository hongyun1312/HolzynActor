package com.holzyn.actor.common.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SseLifecycle（D4 C-20 SSE 生命周期统一工具）单元测试（与实现同包，common/sse）。
 * <p>职责：验证统一事件名常量、统一发送（连接断开返回 false）、心跳注册（create 开启/关闭）。
 * 注：onCompletion/onTimeout 回调由 Servlet 容器异步触发，纯单元测试不可靠，故只测确定性行为。</p>
 */
class SseLifecycleTest {

    private final SseLifecycle lifecycle = new SseLifecycle();

    /**
     * 统一事件名常量（C-20：全端对齐，避免各端点自定义事件名分叉）。
     */
    @Test
    void eventNameConstants() {
        assertEquals("connected", SseLifecycle.EVENT_CONNECTED);
        assertEquals("ping", SseLifecycle.EVENT_PING);
        assertEquals("token", SseLifecycle.EVENT_TOKEN);
        assertEquals("message-start", SseLifecycle.EVENT_MESSAGE_START);
        assertEquals("done", SseLifecycle.EVENT_DONE);
        assertEquals("result", SseLifecycle.EVENT_RESULT);
        assertEquals("system", SseLifecycle.EVENT_SYSTEM);
        assertEquals("progress", SseLifecycle.EVENT_PROGRESS);
        assertEquals("stage", SseLifecycle.EVENT_STAGE);
        assertEquals("log", SseLifecycle.EVENT_LOG);
        assertEquals("schedule", SseLifecycle.EVENT_SCHEDULE);
        assertEquals("finished", SseLifecycle.EVENT_FINISHED);
        assertEquals("error", SseLifecycle.EVENT_ERROR);
        assertEquals(15_000L, SseLifecycle.DEFAULT_HEARTBEAT_MS, "统一心跳周期 15s");
    }

    /**
     * 统一发送：连接已完成后发送返回 false（调用方据此停止生成/移除订阅者），不抛异常。
     */
    @Test
    void sendReturnsFalseAfterComplete() {
        SseEmitter emitter = lifecycle.create(0L, 0L, null);
        emitter.complete();
        assertFalse(lifecycle.send(emitter, SseLifecycle.EVENT_DONE, Map.of("ok", true)),
                "已完成连接发送返回 false");
    }

    /**
     * 心跳 ping：活跃连接发送成功；连接已完成发送失败（探测死连）。
     */
    @Test
    void pingTrueAliveFalseAfterComplete() {
        SseEmitter emitter = lifecycle.create(0L, 0L, null);
        assertTrue(lifecycle.ping(emitter), "活跃连接心跳发送成功");
        emitter.complete();
        assertFalse(lifecycle.ping(emitter), "已完成连接心跳失败（死连）");
    }

    /**
     * 心跳注册：create 传心跳周期即注册；传 0（生成型流）不注册。
     */
    @Test
    void heartbeatRegisteredOnlyWhenConfigured() {
        SseEmitter sub = lifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS, null);
        assertEquals(1, lifecycle.heartbeatCount(), "订阅型流（心跳>0）注册心跳任务");

        SseEmitter gen = lifecycle.create(0L, 0L, null);
        assertEquals(1, lifecycle.heartbeatCount(), "生成型流（心跳=0）不额外注册");
    }

    /**
     * fail：统一失败终止（发送 error + complete）不抛异常。
     */
    @Test
    void failDoesNotThrow() {
        SseEmitter emitter = lifecycle.create(0L, 0L, null);
        lifecycle.fail(emitter, "测试失败");
        assertTrue(true, "fail 不抛异常");
    }
}
