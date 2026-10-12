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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.common.sse.SseLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界事件前端 SSE 广播中心（B1，domain/agent/event）。
 * <p>职责：按项目维护 SSE 订阅者集合（{@code /api/projects/{id}/world-events/stream}），
 * 世界事件总线发布后向该项目订阅者广播 {@code world-event} 事件（载荷含
 * <p>与调试事件流的关系：本 Hub 是「业务事件流」，调试前端事件流由 {@code DebugEventHub}
 * （注册为 {@link WorldEventBus} 订阅者）同源消费——不再各发各的。</p>
 * <p>（SSE 生命周期统一）：订阅型长连接走 {@link SseLifecycle}——统一超时/15s 心跳/
 * 终止清理/事件名（connected/ping/world-event）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldEventSseHub {

    /** SSE 生命周期统一工具 */
    private final SseLifecycle sseLifecycle;

    /** 订阅者集合（项目 → 发射器集合，并发安全） */
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emittersByProject = new ConcurrentHashMap<>();

    /**
     * 订阅项目世界事件流（GET /api/projects/{id}/world-events/stream 使用）。
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
        // 订阅即推送心跳确认，避免代理缓冲导致连接不建立
        sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED,
                Map.of("message", "世界事件流已订阅"));
        return emitter;
    }

    /**
     * 向项目订阅者广播世界事件（事件名 world-event）。
     *
     * @param projectId 项目 ID
     *
     * @param data 事件载荷（WorldEventViews 映射）
     */
    public void broadcast(Long projectId, Map<String, Object> data) {
        Set<SseEmitter> emitters = emittersByProject.get(projectId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            if (!sseLifecycle.send(emitter, "world-event", data)) {
                // 连接断开：移除订阅者
                emitters.remove(emitter);
            }
        }
    }

    /**
     * 移除订阅者。
     */
    private void remove(Long projectId, SseEmitter emitter) {
        Set<SseEmitter> set = emittersByProject.get(projectId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 当前全局活跃订阅数（调试概览页展示） */
    public int size() {
        return emittersByProject.values().stream().mapToInt(Set::size).sum();
    }

    /** 指定项目活跃订阅数 */
    public int size(Long projectId) {
        Set<SseEmitter> set = emittersByProject.get(projectId);
        return set == null ? 0 : set.size();
    }
}
