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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.agent.event.WorldEventViews;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试事件流 SSE 中心（调试前端事件流，B1 起改为世界事件总线订阅者）。
 * <p>职责：向「事件流」页实时推送世界事件——<b>B1</b>：
 * 注册为 {@link WorldEventBus} 订阅者（{@link #onBusEvent}），一切经总线发布的事件
 * （行动 action / 居民活动 crowd / 世界事件 world / 演化 evolution / 手动 manual）同源推送；
 * 不再监听 ActionSseHub、不再由 WorldEventService 直推——调试事件流与业务事件流同一来源。
 * 新连接补发库中最近 50 条世界事件（actor_event，总线落库），页面一开即有内容。</p>
 * <p>SSE 事件约定：{@code connected}={message}；{@code action}=kind=action 的事件载荷；
 * {@code world}=其余事件载荷（载荷见 {@link WorldEventViews}，含 eventId/kind/title/content/
 * characterId/characterIds/locationName/visibility/gameHour/gameTimeText/time）。</p>
 * <p>；依赖 agent/event 总线，观测面单向消费）</p>
 */
@Slf4j
@Service
public class DebugEventHub implements WorldEventSubscriber {

    /** 订阅者集合（并发安全） */
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** 世界事件总线（事件源，注册为订阅者） */
    private final WorldEventBus worldEventBus;

    /** 事件仓库（新连接补发最近事件） */
    private final ActorEventRepository eventRepository;

    /** 世界时钟服务（载荷 gameTimeText 解析） */
    private final WorldClockService worldClockService;

    /** SSE 生命周期统一工具（：统一超时/心跳/清理/事件名） */
    private final com.holzyn.actor.common.sse.SseLifecycle sseLifecycle;

    public DebugEventHub(WorldEventBus worldEventBus, ActorEventRepository eventRepository,
                         WorldClockService worldClockService,
                         com.holzyn.actor.common.sse.SseLifecycle sseLifecycle) {
        this.worldEventBus = worldEventBus;
        this.eventRepository = eventRepository;
        this.worldClockService = worldClockService;
        this.sseLifecycle = sseLifecycle;
    }

    /**
     * 启动后注册为世界事件总线订阅者：一切已发布事件同源推送到调试订阅者。
     */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
    }

    /**
     * 订阅事件流（GET /api/debug/events/stream）。
     *
     * @return SSE 发射器（订阅型：无超时 + 15s 心跳；先补发最近 50 条世界事件，再实时推送）
     */
    public SseEmitter subscribe() {
        // 终止回调需引用 emitter 自身，用 holder 绕过 lambda 捕获「未初始化」限制
        SseEmitter[] holder = new SseEmitter[1];
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS,
                () -> emitters.remove(holder[0]));
        holder[0] = emitter;
        emitters.add(emitter);
        if (!sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED, Map.of("message", "事件流已连接"))) {
            emitters.remove(emitter);
            return emitter;
        }
        try {
            // 发库中最近 50 条世界事件（actor_event，B1 起总线落库），页面一开即有内容
            List<ActorEvent> recent = eventRepository.findTop50ByOrderByIdDesc();
            for (ActorEvent ev : recent) {
                if (!sseLifecycle.send(emitter, eventName(ev), eventMap(ev))) {
                    emitters.remove(emitter);
                    break;
                }
            }
        } catch (Exception e) {
            emitters.remove(emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 忽略
            }
        }
        return emitter;
    }

    /**
     * 总线订阅回调（WorldEventSubscriber.onEvent）：事件 → 按类别广播到调试订阅者
     * （action/crowd/world/evolution/manual 统一）。
     *
     * @param event 已落库的事件实体
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (event == null) {
            return;
        }
        broadcast(eventName(event), eventMap(event));
    }

    /**
     * 事件 → 事件名（action 事件用 action，其余用 world——调试前端两个过滤维度）。
     *
     * @param e 事件实体
     *
     * @return SSE 事件名
     */
    private String eventName(ActorEvent e) {
        return "action".equals(e.getKind()) ? "action" : "world";
    }

    /**
     * 事件实体 → 前端载荷（WorldEventViews 同源映射 + 调试前端兼容字段 time/action/status/planId）。
     *
     * @param e 事件实体
     *
     * @return 载荷 Map
     */
    private Map<String, Object> eventMap(ActorEvent e) {
        Map<String, Object> m = WorldEventViews.toMap(e, worldClockService);
        m.put("time", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
        if ("action".equals(e.getKind())) {
            // 兼容调试前端「行动」展示字段（action/status/planId）
            m.putIfAbsent("action", e.getTitle());
            m.putIfAbsent("status", "done");
            String sk = e.getSourceKey();
            if (sk != null && sk.startsWith("plan:")) {
                m.put("planId", sk.substring("plan:".length()));
            }
        }
        return m;
    }

    /**
     * 向全部订阅者广播。
     *
     * @param name 事件名（action/world）
     *
     * @param data 事件数据
     */
    private void broadcast(String name, Map<String, Object> data) {
        for (SseEmitter emitter : emitters) {
            if (!sseLifecycle.send(emitter, name, data)) {
                // 连接断开：移除订阅者
                emitters.remove(emitter);
            }
        }
    }

    /** 当前活跃订阅数（概览页展示） */
    public int size() {
        return emitters.size();
    }
}
