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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 地图生成进度 SSE 中枢（按<b>项目</b>分频道，而不是按任务）。
 *
 * <p><b>为什么按项目而不按任务</b>：地图生成是「一个项目一张图」的语义——前端打开首页时只知道
 * 「我在看哪个项目的地图」，不知道 runId；重掷/单阶段重跑都会产生新的 run，如果按 run 分频道，
 * 前端每次都要先拿 runId 再订阅，页面一刷新就断流。按项目分频道后，
 * {@code GET /api/worlds/{id}/world-map/stream} 永远有效，重跑自动复用同一个通道。</p>
 *
 * <p><b>迟到订阅</b>：频道保留最近 {@link #BUFFER_MAX} 条事件（含上一次运行的 stage/log），
 * 所以「点了生成再打开页面」也能立刻看到进度条走到哪一步，而不是空白等第一条事件。</p>
 *
 * <p>事件名统一用 {@link SseLifecycle} 常量：{@code stage} / {@code progress} / {@code log} /
 * {@code result} / {@code error}。终态（result/error）之后频道标记 terminal，再订阅会立即收到
 * 终态快照然后断开——不会挂着一个永远不动的进度条。</p>
 */
@Slf4j
@Component
public class MapGenProgressHub {

    /** 每项目回放缓冲上限（超出丢弃最旧） */
    private static final int BUFFER_MAX = 240;

    /** 时间格式（日志行） */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 阶段总数（S0..S5） */
    public static final int STAGE_TOTAL = 6;

    private final SseLifecycle sseLifecycle;

    /** 项目 ID → 频道 */
    private final ConcurrentHashMap<Long, Channel> channels = new ConcurrentHashMap<>();

    public MapGenProgressHub(SseLifecycle sseLifecycle) {
        this.sseLifecycle = sseLifecycle;
    }

    /** 回放缓冲事件 */
    private record BufferedEvent(String name, Object data) {
    }

    /** 单项目频道 */
    private static final class Channel {
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        final Deque<BufferedEvent> buffer = new ArrayDeque<>();
        volatile boolean terminal = false;
    }

    // ==================== 订阅 ====================

    /**
     * 创建并订阅该项目的进度流（统一超时/心跳/清理交给 {@link SseLifecycle}）。
     *
     * @param projectId 项目 ID
     * @return SSE 发射器（调用方直接返回给 Spring）
     */
    public SseEmitter subscribe(Long projectId) {
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS, null);
        Channel ch = channels.computeIfAbsent(projectId, k -> new Channel());
        synchronized (ch) {
            for (BufferedEvent e : ch.buffer) {
                if (!sseLifecycle.send(emitter, e.name, e.data)) {
                    return emitter;
                }
            }
            if (ch.terminal) {
                // 上一轮已结束：回放完就收尾，不留悬挂连接
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已完成/断开，忽略
                }
                return emitter;
            }
            ch.subscribers.add(emitter);
        }
        emitter.onCompletion(() -> ch.subscribers.remove(emitter));
        emitter.onTimeout(() -> ch.subscribers.remove(emitter));
        emitter.onError(e -> ch.subscribers.remove(emitter));
        return emitter;
    }

    // ==================== 广播 ====================

    /**
     * 新的一轮开始：清空上一轮回放缓冲与终态标记（同一频道继续用，前端不用重连）。
     *
     * @param projectId 项目 ID
     */
    public void beginRun(Long projectId) {
        Channel ch = channels.computeIfAbsent(projectId, k -> new Channel());
        synchronized (ch) {
            ch.buffer.clear();
            ch.terminal = false;
        }
    }

    /**
     * 阶段推进（{@code stage} 事件）。
     *
     * @param projectId 项目 ID
     * @param stage     阶段码（S0..S5）
     * @param name      阶段中文名
     * @param index     序号（1 起）
     * @param percent   整轮百分比 0~100
     */
    public void stage(Long projectId, String stage, String name, int index, int percent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("name", name);
        data.put("index", index);
        data.put("total", STAGE_TOTAL);
        data.put("percent", percent);
        broadcast(projectId, SseLifecycle.EVENT_STAGE, data);
    }

    /**
     * 阶段内进度（{@code progress} 事件）。
     *
     * @param projectId 项目 ID
     * @param percent   整轮百分比 0~100
     * @param message   一句话说明
     */
    public void progress(Long projectId, int percent, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("percent", percent);
        data.put("message", message);
        broadcast(projectId, SseLifecycle.EVENT_PROGRESS, data);
    }

    /**
     * 日志行（{@code log} 事件，前端控制台同款渲染）。
     *
     * @param projectId 项目 ID
     * @param message   日志内容（建议带 [S0] 之类前缀便于着色）
     */
    public void log(Long projectId, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", "info");
        data.put("message", message);
        data.put("time", LocalTime.now().format(TIME));
        broadcast(projectId, SseLifecycle.EVENT_LOG, data);
    }

    /**
     * 成功收尾（{@code result} 事件 + 关闭全部订阅者）。
     *
     * @param projectId 项目 ID
     * @param payload   地图概览（前端据此刷新页面数据）
     */
    public void complete(Long projectId, Object payload) {
        Channel ch = channels.computeIfAbsent(projectId, k -> new Channel());
        synchronized (ch) {
            ch.buffer.addLast(new BufferedEvent(SseLifecycle.EVENT_RESULT, payload));
            ch.terminal = true;
        }
        for (SseEmitter e : ch.subscribers) {
            sseLifecycle.send(e, SseLifecycle.EVENT_RESULT, payload);
            try {
                e.complete();
            } catch (Exception ignored) {
                // 已完成/断开，忽略
            }
        }
        ch.subscribers.clear();
    }

    /**
     * 失败收尾（{@code error} 事件 + 关闭全部订阅者）。
     *
     * @param projectId 项目 ID
     * @param message   错误信息
     */
    public void fail(Long projectId, String message) {
        Channel ch = channels.computeIfAbsent(projectId, k -> new Channel());
        Map<String, Object> data = Map.of("message", message == null ? "地图生成失败" : message);
        synchronized (ch) {
            ch.buffer.addLast(new BufferedEvent(SseLifecycle.EVENT_ERROR, data));
            ch.terminal = true;
        }
        for (SseEmitter e : ch.subscribers) {
            sseLifecycle.send(e, SseLifecycle.EVENT_ERROR, data);
            try {
                e.complete();
            } catch (Exception ignored) {
                // 已完成/断开，忽略
            }
        }
        ch.subscribers.clear();
    }

    /**
     * 广播一条事件（写回放缓冲 + 推给在线订阅者，发送失败自动移除）。
     *
     * @param projectId 项目 ID
     * @param name      事件名
     * @param data      载荷
     */
    private void broadcast(Long projectId, String name, Object data) {
        Channel ch = channels.computeIfAbsent(projectId, k -> new Channel());
        synchronized (ch) {
            ch.buffer.addLast(new BufferedEvent(name, data));
            if (ch.buffer.size() > BUFFER_MAX) {
                ch.buffer.removeFirst();
            }
        }
        for (SseEmitter e : ch.subscribers) {
            if (!sseLifecycle.send(e, name, data)) {
                ch.subscribers.remove(e);
            }
        }
    }

    /** 当前频道数（调试用） */
    public int channelCount() {
        return channels.size();
    }
}
