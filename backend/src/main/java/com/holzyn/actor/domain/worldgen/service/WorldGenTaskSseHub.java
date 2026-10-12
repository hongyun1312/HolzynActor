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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 世界观生成任务 SSE 进度中枢。
 * <p>职责：把 TaskRunner 的 log/stage/result/error 事件按任务广播给 {@code GET /stream} 的
 * SSE 订阅者（复用 {@link SseLifecycle} 统一发送/心跳/清理），并保留<b>每任务最近事件回放缓冲</b>
 * （最多 {@link #BUFFER_MAX} 条）——任务创建后客户端再连上 /stream 也能看到已发生进度
 * （对齐方案文档 §3.5「SSE 进度流」的断连/迟到订阅体验）。</p>
 * <p>事件名对齐 统一常量：{@code log}/{@code stage}/{@code result}/{@code error}，
 * 载荷与 WorldWorkflowController 的 SseWorkflowLog 一致（log={level,message,time}、
 */
@Component
@Slf4j
public class WorldGenTaskSseHub {

    /** 每任务回放缓冲上限（超出丢弃最旧） */
    private static final int BUFFER_MAX = 200;

    /** 统一 SSE 生命周期工具 */
    private final SseLifecycle sseLifecycle;

    /** 任务 ID → 频道（订阅者 + 回放缓冲 + 终态标记） */
    private final ConcurrentHashMap<Long, Channel> channels = new ConcurrentHashMap<>();

    public WorldGenTaskSseHub(SseLifecycle sseLifecycle) {
        this.sseLifecycle = sseLifecycle;
    }

    /** 回放缓冲事件条目 */
    private record BufferedEvent(String name, Object data) {
    }

    /** 单任务频道 */
    private static final class Channel {
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        final Deque<BufferedEvent> buffer = new ArrayDeque<>();
        volatile boolean terminal = false;
    }

    /**
     * 订阅任务进度（客户端连接 /stream 时调用）。
     * <p>行为：先回放缓冲内历史事件，再接入实时订阅；若任务已终态，回放后不订阅并返回 true
     * （调用方据此 complete 发射器）。</p>
     *
     * @param taskId 任务 ID
     *
     * @param emitter SSE 发射器
     *
     * @return true=任务已终态（订阅方应 complete 发射器）；false=已接入实时订阅
     */
    public boolean subscribe(Long taskId, SseEmitter emitter) {
        Channel ch = channels.computeIfAbsent(taskId, k -> new Channel());
        synchronized (ch) {
            for (BufferedEvent e : ch.buffer) {
                if (!sseLifecycle.send(emitter, e.name, e.data)) {
                    return true; // 客户端已断开
                }
            }
            if (ch.terminal) {
                return true;
            }
            ch.subscribers.add(emitter);
            return false;
        }
    }

    /**
     * 取消订阅（客户端断开/发射器终止时调用，防残留）。
     *
     * @param taskId 任务 ID
     *
     * @param emitter SSE 发射器
     */
    public void unsubscribe(Long taskId, SseEmitter emitter) {
        Channel ch = channels.get(taskId);
        if (ch != null) {
            ch.subscribers.remove(emitter);
        }
    }

    /**
     * 广播进度事件（log/stage 等）：写入回放缓冲 + 推给全部在线订阅者（发送失败自动移除）。
     *
     * @param taskId 任务 ID
     *
     * @param name 事件名（SseLifecycle.EVENT_LOG / EVENT_STAGE 等）
     *
     * @param data 载荷（Map/对象，可序列化）
     */
    public void broadcast(Long taskId, String name, Object data) {
        Channel ch = channels.computeIfAbsent(taskId, k -> new Channel());
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

    /**
     * 终态完成：广播 result 事件 + 标记终态 + 完成全部订阅者（生成型流收尾语义）。
     *
     * @param taskId 任务 ID
     *
     * @param name 事件名（SseLifecycle.EVENT_RESULT）
     *
     * @param data 结果载荷（任务 VO）
     */
    public void complete(Long taskId, String name, Object data) {
        Channel ch = channels.computeIfAbsent(taskId, k -> new Channel());
        synchronized (ch) {
            ch.buffer.addLast(new BufferedEvent(name, data));
            ch.terminal = true;
        }
        for (SseEmitter e : ch.subscribers) {
            sseLifecycle.send(e, name, data);
            try {
                e.complete();
            } catch (Exception ignored) {
                // 已完成/断开，忽略
            }
        }
        ch.subscribers.clear();
    }

    /**
     * 终态失败：广播 error 事件 + 标记终态 + 完成全部订阅者。
     *
     * @param taskId 任务 ID
     *
     * @param message 错误信息（载荷 {message}，与 error 约定一致）
     */
    public void fail(Long taskId, String message) {
        complete(taskId, SseLifecycle.EVENT_ERROR, Map.of("message", message == null ? "任务失败" : message));
    }

    /**
     * 查询任务是否已终态（订阅前判断；终态任务直接返回快照，不再订阅实时）。
     *
     * @param taskId 任务 ID
     *
     * @return true=终态
     */
    public boolean isTerminal(Long taskId) {
        Channel ch = channels.get(taskId);
        return ch != null && ch.terminal;
    }

    /** 当前频道数（调试用） */
    public int channelCount() {
        return channels.size();
    }

    /**
     * 广播一行工作流日志（log 事件，载荷与 WorldWorkflowController.SseWorkflowLog 一致：
     * {level,message,time}，前端控制台同款渲染）。
     *
     * @param taskId 任务 ID
     *
     * @param message 日志内容（含前缀如 [世界观生成] 便于前端着色）
     */
    public void log(Long taskId, String message) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("level", "info");
        data.put("message", message);
        data.put("time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        broadcast(taskId, SseLifecycle.EVENT_LOG, data);
    }

    /**
     * 广播节点阶段进度（stage 事件，载荷 {name,index,total}）。
     *
     * @param taskId 任务 ID
     *
     * @param name 节点中文名
     *
     * @param index 当前序号（1 起）
     *
     * @param total 节点总数
     */
    public void stage(Long taskId, String name, int index, int total) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("name", name);
        data.put("index", index);
        data.put("total", total);
        broadcast(taskId, SseLifecycle.EVENT_STAGE, data);
    }
}
