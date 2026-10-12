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

package com.holzyn.actor.common.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SSE 生命周期统一工具（阶段 D， 修复「SSE 断连/重建与取消策略未统一」）。
 * <p>背景：对话流/工作流/事件流/行动流/演化流等各 SSE 端点此前各自实现超时/取消/清理，
 * 页面刷新/切换时偶发「已中断」提示与残留 streaming 消息。本工具统一三件事：</p>
 * <ul>
 * <li><b>统一创建</b>：{@link #create}——统一超时、统一 onCompletion/onTimeout/onError 清理钩子
 * （terminate 时自动取消心跳 + 触发调用方 cleanup，避免 emitter 泄漏/残留任务）；</li>
 * <li><b>统一心跳</b>：{@link #scheduleHeartbeat}——每 {@link #DEFAULT_HEARTBEAT_MS}（15s）
 * 发送一条 {@code ping} 事件，防代理缓冲/中间层断连误判，保持连接活性；发送失败自动终止并清理；</li>
 * <li><b>统一事件名与发送</b>：{@link #EVENT_*} 常量 + {@link #send}/{@link #fail}——
 * 发送失败统一按「连接已断开」处理（返回 false / 终止），错误事件统一命名为 {@code error}。</li>
 * </ul>
 * <p>重连/取消策略由调用方（前端客户端）决定：订阅型流（世界事件/行动/调试事件/日志）断线自动重连；
 * 生成型流（对话/演化/工作流/解析/人群/地点提取）断线标记 failed 不重连（防重复生成）。</p>
 */
@Slf4j
@Component
public class SseLifecycle {

    /** 统一心跳周期（真实毫秒，默认 15s）：订阅型流保持活性、生成型流探测死连 */
    public static final long DEFAULT_HEARTBEAT_MS = 15_000L;

    // ==================== 统一事件名常量（：全端对齐，避免各端点自定义事件名分叉） ====================

    /** 连接建立确认（订阅即推送，避免代理缓冲导致连接不建立） */
    public static final String EVENT_CONNECTED = "connected";
    /** 心跳（注释事件：data 为空，仅保活） */
    public static final String EVENT_PING = "ping";
    /** 流式 token 增量 */
    public static final String EVENT_TOKEN = "token";
    /** 群聊新角色开始发言（含 assistantMessageId/characterId/characterName） */
    public static final String EVENT_MESSAGE_START = "message-start";
    /** 单条消息/单个环节完成 */
    public static final String EVENT_DONE = "done";
    /** 整体流程完成（含结果载荷） */
    public static final String EVENT_RESULT = "result";
    /** 系统消息（环境/加入/退场/收尾等叙述性内容） */
    public static final String EVENT_SYSTEM = "system";
    /** 进度（工作流/解析/生成：done/total/label/chars） */
    public static final String EVENT_PROGRESS = "progress";
    /** 阶段推进（工作流 stage） */
    public static final String EVENT_STAGE = "stage";
    /** 日志行（工作流控制台） */
    public static final String EVENT_LOG = "log";
    /** 演化解码/调度事件 */
    public static final String EVENT_SCHEDULE = "schedule";
    /** 整体完成（演化收尾） */
    public static final String EVENT_FINISHED = "finished";
    /** 错误（统一载荷 {message}） */
    public static final String EVENT_ERROR = "error";

    /** 心跳任务表（发射器 → 心跳任务；终止时取消，防泄漏） */
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    /** 统一心跳调度线程（守护线程，单线程即可） */
    private final ScheduledThreadPoolExecutor scheduler;

    public SseLifecycle() {
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 统一创建 SSE 发射器：统一超时 + 统一终止清理（取消心跳 + 调用方 cleanup）+ 可选自动心跳。
     *
     * @param timeoutMs 超时毫秒（&lt;=0=不超时，订阅型长连接）
     *
     * @param heartbeatMs 心跳周期（&lt;=0=不发送心跳）
     *
     * @param cleanup 终止回调（onCompletion/onTimeout/onError 任一触发即调用；可空）
     *
     * @return 统一配置好的 SseEmitter
     */
    public SseEmitter create(long timeoutMs, long heartbeatMs, Runnable cleanup) {
        SseEmitter emitter = new SseEmitter(timeoutMs <= 0 ? 0L : timeoutMs);
        emitter.onCompletion(() -> terminate(emitter, cleanup));
        emitter.onTimeout(() -> terminate(emitter, cleanup));
        emitter.onError(e -> terminate(emitter, cleanup));
        if (heartbeatMs > 0) {
            scheduleHeartbeat(emitter, heartbeatMs);
        }
        return emitter;
    }

    /**
     * 统一发送事件（捕获异常：连接已断开返回 false，调用方据此停止生成/移除订阅者）。
     *
     * @param emitter 发射器
     *
     * @param name 事件名（统一用 {@link #EVENT_*} 常量）
     *
     * @param data 载荷（Map/字符串等可序列化对象）
     *
     * @return true=发送成功；false=连接已断开
     */
    public boolean send(SseEmitter emitter, String name, Object data) {
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (Exception e) {
            // 连接断开/已完成：统一按断开处理，不再抛异常（调用方自行终止生成）
            return false;
        }
    }

    /**
     * 统一发送 JSON 字符串事件（工作流/解析类以 JSON 字符串 + application/json 媒体类型发送，
     * 与既有端点行为一致；连接断开返回 false）。
     *
     * @param emitter 发射器
     *
     * @param name 事件名
     *
     * @param json JSON 字符串载荷
     *
     * @return true=发送成功；false=连接已断开
     */
    public boolean sendJson(SseEmitter emitter, String name, String json) {
        if (emitter == null || json == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(json, org.springframework.http.MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 统一发送错误并完成（生成型流断线/失败语义：错误事件 + complete）。
     *
     * @param emitter 发射器
     *
     * @param message 错误信息（载荷 {message}）
     */
    public void fail(SseEmitter emitter, String message) {
        send(emitter, EVENT_ERROR, Map.of("message", message == null ? "未知错误" : message));
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成/断开，忽略
        }
    }

    // ==================== 心跳 ====================

    /**
     * 为发射器启动心跳任务（每 interval 毫秒发一条 ping 事件；发送失败自动终止并清理）。
     *
     * @param emitter 发射器
     *
     * @param interval 心跳周期（毫秒）
     */
    public void scheduleHeartbeat(SseEmitter emitter, long interval) {
        if (emitter == null || interval <= 0) {
            return;
        }
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (!ping(emitter)) {
                // 连接已断开：终止并清理（cancel 自身）
                ScheduledFuture<?> self = heartbeats.remove(emitter);
                if (self != null) {
                    self.cancel(false);
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已完成，忽略
                }
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        heartbeats.put(emitter, task);
    }

    /**
     * 发送心跳 ping 事件（data 为空字符串）。
     *
     * @param emitter 发射器
     *
     * @return true=发送成功
     */
    public boolean ping(SseEmitter emitter) {
        return send(emitter, EVENT_PING, "");
    }

    /**
     * 终止处理：取消心跳任务 + 触发调用方清理（onCompletion/onTimeout/onError 统一入口）。
     *
     * @param emitter 发射器
     *
     * @param cleanup 调用方清理回调（可空）
     */
    private void terminate(SseEmitter emitter, Runnable cleanup) {
        ScheduledFuture<?> task = heartbeats.remove(emitter);
        if (task != null) {
            task.cancel(false);
        }
        if (cleanup != null) {
            try {
                cleanup.run();
            } catch (Exception e) {
                log.warn("[SSE] 终止清理回调异常: {}", e.getMessage());
            }
        }
    }

    /** 当前活跃心跳任务数（调试用） */
    public int heartbeatCount() {
        return heartbeats.size();
    }
}
