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

package com.holzyn.actor.domain.action.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 行动事件 SSE 广播中心。
 * <p>职责：维护「行动时间线」页的 SSE 订阅者集合，行动引擎模拟执行完成后向所有订阅者
 * 广播 action 事件，前端时间线实时刷新（无需轮询）。</p>
 * <p>扩展：新增「监听器」机制（{@link #addListener(Consumer)}）——调试前端
 * （{@code DebugEventHub}）注册为监听器后，行动事件同时转发到调试「事件流」页，零侵入复用广播。</p>
 * <p>B1（事件总线）调整：DebugEventHub 已改为 {@code WorldEventBus} 订阅者
 * （行动事件由 ActionEngine 发布总线后同源推送），不再挂钩本 Hub；监听器机制保留供其他旁路消费方使用。</p>
 * <p>（SSE 生命周期统一）：订阅型长连接走 {@link SseLifecycle}——统一超时/15s 心跳/
 * 终止清理/事件名（connected/ping/action）。</p>
 */
@Slf4j
@Component
public class ActionSseHub {

    /** SSE 生命周期统一工具 */
    private final SseLifecycle sseLifecycle;

    /** 订阅者集合（并发安全） */
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** 事件监听器集合（调试前端事件流等旁路消费，并发安全） */
    private final List<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();

    public ActionSseHub(SseLifecycle sseLifecycle) {
        this.sseLifecycle = sseLifecycle;
    }

    /**
     * 注册行动事件监听器（旁路消费广播，如调试前端事件流）。
     *
     * @param listener 收到广播数据时的回调（每次广播均调用）
     */
    public void addListener(Consumer<Map<String, Object>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 订阅行动事件流（GET /api/actions/stream 使用）。
     *
     * @return SSE 发射器（订阅型：无超时 + 15s 心跳，连接保持至页面关闭）
     */
    public SseEmitter subscribe() {
        // 终止回调需引用 emitter 自身，用 holder 绕过 lambda 捕获「未初始化」限制
        SseEmitter[] holder = new SseEmitter[1];
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS,
                () -> emitters.remove(holder[0]));
        holder[0] = emitter;
        emitters.add(emitter);
        // 订阅即推送确认，避免代理缓冲导致连接不建立
        sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED,
                Map.of("message", "行动时间线已订阅"));
        return emitter;
    }

    /**
     * 向全部订阅者广播行动事件，并转发到监听器（调试前端事件流）。
     *
     * @param data 事件数据（type=action / planId / characterId / action / status / time）
     */
    public void broadcast(Map<String, Object> data) {
        for (SseEmitter emitter : emitters) {
            if (!sseLifecycle.send(emitter, "action", data)) {
                // 连接断开：移除订阅者
                emitters.remove(emitter);
            }
        }
        // 旁路监听器（调试事件流）：监听器失败不影响主广播
        for (Consumer<Map<String, Object>> listener : listeners) {
            try {
                listener.accept(data);
            } catch (Exception ignored) {
                // 忽略监听器异常
            }
        }
    }

    /**
     * 当前活跃订阅数（调试概览页展示）。
     *
     * @return 订阅者数量
     */
    public int size() {
        return emitters.size();
    }
}