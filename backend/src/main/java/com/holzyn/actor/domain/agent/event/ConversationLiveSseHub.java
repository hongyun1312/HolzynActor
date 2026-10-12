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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 会话实时订阅广播中心（阶段 D， 无玩家群聊「围观」通道）。
 * <p>职责：按会话维护 SSE 订阅者集合（{@code GET /api/conversations/{id}/live-stream}，
 * 订阅型长连接，心跳 15s），无玩家群聊轮逐条消息落库后向该会话订阅者实时推送
 * 玩家打开 NPC 内群页面即可实时围观 NPC 自发对话（无需刷新）。</p>
 * <p>与玩家触发的 {@code /stream} 生成流区分：本 Hub 是<b>被动订阅</b>（推送已落库消息），
 * 打开即订阅、断线由前端自动重连（订阅型流）；不触发任何生成。</p>
 * <p>生命周期统一走 {@link SseLifecycle}（：超时/心跳/终止清理统一）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationLiveSseHub {

    /** SSE 生命周期统一工具 */
    private final SseLifecycle sseLifecycle;

    /** 订阅者集合（会话 → 发射器集合，并发安全） */
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emittersByConversation = new ConcurrentHashMap<>();

    /** 消息监听器集合（旁路消费广播，如沙盘 npc:talk 转发；S2，并发安全） */
    private final List<BiConsumer<Long, Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册消息监听器（旁路消费每次广播：会话 ID + 消息载荷；异常不影响主广播）。
     *
     * @param listener 收到广播数据时的回调（每次 broadcast 均调用）
     */
    public void addListener(BiConsumer<Long, Map<String, Object>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 订阅会话实时消息流（订阅型：无超时 + 15s 心跳 + 终止自动移除）。
     *
     * @param conversationId 会话 ID
     *
     * @return SSE 发射器
     */
    public SseEmitter subscribe(Long conversationId) {
        // 终止回调需引用 emitter 自身，用 holder 绕过 lambda 捕获「未初始化」限制
        SseEmitter[] holder = new SseEmitter[1];
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS,
                () -> remove(conversationId, holder[0]));
        holder[0] = emitter;
        emittersByConversation.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        // 订阅即推送确认，避免代理缓冲导致连接不建立
        sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED,
                Map.of("message", "会话实时消息流已订阅", "conversationId", conversationId));
        return emitter;
    }

    /**
     * 向会话订阅者广播一条已落库消息（无玩家群聊轮/世界推进写消息后调用）。
     *
     * @param conversationId 会话 ID
     *
     * @param data 消息载荷（messageId/characterId/characterName/role/type/content/gameTimeText）
     */
    public void broadcast(Long conversationId, Map<String, Object> data) {
        if (conversationId == null || data == null) {
            return;
        }
        Set<SseEmitter> emitters = emittersByConversation.get(conversationId);
        if (emitters != null && !emitters.isEmpty()) {
            for (SseEmitter emitter : emitters) {
                if (!sseLifecycle.send(emitter, "message", data)) {
                    // 连接断开：移除订阅者
                    emitters.remove(emitter);
                }
            }
        }
        // 旁路监听器（沙盘 npc:talk 转发等）：监听器异常不影响主广播
        for (BiConsumer<Long, Map<String, Object>> listener : listeners) {
            try {
                listener.accept(conversationId, data);
            } catch (Exception ignored) {
                // 忽略监听器异常
            }
        }
    }

    /**
     * 移除订阅者（终止回调与发送失败统一入口）。
     */
    private void remove(Long conversationId, SseEmitter emitter) {
        Set<SseEmitter> set = emittersByConversation.get(conversationId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 当前全局活跃订阅数（调试用） */
    public int size() {
        return emittersByConversation.values().stream().mapToInt(Set::size).sum();
    }

    /** 指定会话活跃订阅数（调试用） */
    public int size(Long conversationId) {
        Set<SseEmitter> set = emittersByConversation.get(conversationId);
        return set == null ? 0 : set.size();
    }
}
