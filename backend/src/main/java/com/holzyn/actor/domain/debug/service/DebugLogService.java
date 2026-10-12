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

import com.holzyn.actor.domain.debug.service.DebugLogBuffer.DebugLogEntry;
import com.holzyn.actor.common.sse.SseLifecycle;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时日志 SSE 服务（调试前端实时日志）。
 * <p>职责：维护「实时日志」页的 SSE 订阅者集合（每个订阅者可带 level/keyword 服务端粗过滤），
 * 新连接先补发缓冲中最近 500 条（按过滤条件），之后 {@link DebugLogBuffer} 每来一条新日志
 * 即向匹配的订阅者推送（{@code log} 事件）。缓冲分页回看走 {@link #page(int, int, String, String)}。</p>
 */
@Slf4j
@Service
public class DebugLogService {

    /** 新连接补发的最近日志条数 */
    private static final int REPLAY_COUNT = 500;

    /** 日志缓冲（数据源） */
    private final DebugLogBuffer buffer;

    /** SSE 生命周期统一工具（：统一超时/心跳/清理/事件名） */
    private final com.holzyn.actor.common.sse.SseLifecycle sseLifecycle;

    /** 订阅者集合（并发安全；每个订阅者持有自己的过滤条件） */
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    public DebugLogService(DebugLogBuffer buffer, com.holzyn.actor.common.sse.SseLifecycle sseLifecycle) {
        this.buffer = buffer;
        this.sseLifecycle = sseLifecycle;
    }

    /**
     * 启动后注册缓冲监听：新日志实时广播到各订阅者。
     */
    @PostConstruct
    void hookBuffer() {
        buffer.addListener(this::broadcast);
    }

    /**
     * 订阅实时日志流（GET /api/debug/logs/stream）。
     *
     * @param level 级别粗过滤（可空；空=全部；支持 TRACE~ERROR 单级别）
     *
     * @param keyword 关键词粗过滤（可空；空=全部；对 message 做不区分大小写包含匹配）
     *
     * @return SSE 发射器（先补发最近 500 条，再实时推送）
     */
    public SseEmitter subscribe(String level, String keyword) {
        // 终止回调需引用 sub，用 holder 绕过 lambda 捕获「未初始化」限制
        Subscription[] holder = new Subscription[1];
        SseEmitter emitter = sseLifecycle.create(0L, SseLifecycle.DEFAULT_HEARTBEAT_MS,
                () -> subscriptions.remove(holder[0]));
        Subscription sub = new Subscription(emitter, normalizeLevel(level), normalizeKeyword(keyword));
        holder[0] = sub;
        subscriptions.add(sub);

        // 发缓冲中最近 500 条（按过滤条件）
        List<DebugLogEntry> replay = buffer.recent(REPLAY_COUNT).stream()
                .filter(e -> matches(e, sub.level, sub.keyword))
                .toList();
        if (!sseLifecycle.send(emitter, SseLifecycle.EVENT_CONNECTED,
                Map.of("message", "日志流已连接", "replayed", replay.size()))) {
            subscriptions.remove(sub);
            return emitter;
        }
        try {
            for (DebugLogEntry entry : replay) {
                if (!sseLifecycle.send(emitter, "log", entryToMap(entry))) {
                    subscriptions.remove(sub);
                    break;
                }
            }
        } catch (Exception e) {
            subscriptions.remove(sub);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 连接建立失败，忽略
            }
        }
        return emitter;
    }

    /**
     * 广播一条新日志到匹配的订阅者（DebugLogBuffer 监听回调）。
     *
     * @param entry 新日志条目
     */
    public void broadcast(DebugLogEntry entry) {
        for (Subscription sub : subscriptions) {
            if (!matches(entry, sub.level, sub.keyword)) {
                continue;
            }
            if (!sseLifecycle.send(sub.emitter, "log", entryToMap(entry))) {
                // 连接断开：移除订阅者
                subscriptions.remove(sub);
            }
        }
    }

    /**
     * 分页回看缓冲（GET /api/debug/logs/buffer）。
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 100，上限 500）
     *
     * @param level 级别过滤（可空）
     *
     * @param keyword 关键词过滤（可空）
     */
    public Map<String, Object> page(int page, int size, String level, String keyword) {
        String lvl = normalizeLevel(level);
        String kw = normalizeKeyword(keyword);
        List<DebugLogEntry> all = buffer.all().stream()
                .filter(e -> matches(e, lvl, kw))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 500);
        int total = all.size();
        int from = Math.min((pageNo - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DebugLogEntry e : all.subList(from, to)) {
            items.add(entryToMap(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    /** 当前活跃订阅数（概览页展示） */
    public int size() {
        return subscriptions.size();
    }

    /**
     * 日志条目 → 前端 Map。
     *
     * @param e 日志条目
     */
    private Map<String, Object> entryToMap(DebugLogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("time", e.time());
        m.put("level", e.level());
        m.put("thread", e.thread());
        m.put("logger", e.logger());
        m.put("message", e.message());
        return m;
    }

    /**
     * 过滤匹配判断：级别（相等）与关键词（message 不区分大小写包含）。
     *
     * @param e 日志条目
     *
     * @param level 级别（可空）
     *
     * @param keyword 关键词（可空）
     *
     * @return true=匹配
     */
    private static boolean matches(DebugLogEntry e, String level, String keyword) {
        if (level != null && !level.isEmpty() && e.level() != null
                && !level.equalsIgnoreCase(e.level())) {
            return false;
        }
        if (keyword != null && !keyword.isEmpty()) {
            String msg = e.message() == null ? "" : e.message().toLowerCase(Locale.ROOT);
            return msg.contains(keyword);
        }
        return true;
    }

    /** 级别归一化（空白 → null） */
    private static String normalizeLevel(String level) {
        return level == null || level.isBlank() ? null : level.trim().toUpperCase(Locale.ROOT);
    }

    /** 关键词归一化（空白 → null，小写） */
    private static String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 订阅者（发射器 + 服务端过滤条件）。
     */
    private record Subscription(SseEmitter emitter, String level, String keyword) {
    }
}
