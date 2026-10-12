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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 后端日志内存环形缓冲（调试前端实时日志）。
 * <p>职责：由 Logback 自定义 Appender（{@code DebugLogAppender}）把每条日志推入本缓冲，
 * 满则覆盖最旧（默认容量 2000，可配 {@code holzyn.actor.debug.log-buffer-size}）；
 * 新 SSE 连接从此补发最近 N 条；缓冲内容可供「实时日志」页回看分页。</p>
 * <p>全局访问：Logback 在 Spring 上下文之外创建 Appender 实例，无法直接注入本 Bean——
 * 采用「Spring Bean 启动时自注册静态 INSTANCE」的方式，Appender 通过
 * {@link #instance()} 拿到缓冲引用（启动早期 Spring Bean 未初始化前的日志会丢弃，可接受）。</p>
 * <p>开关：{@code holzyn.actor.debug.log-append.enabled}（默认 true）关闭后 Appender 静默跳过。</p>
 */
@Component
public class DebugLogBuffer {

    /** 时间格式化（日志行时间戳，毫秒） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Spring Bean 注册的全局缓冲实例（Logback Appender 跨上下文访问） */
    private static volatile DebugLogBuffer INSTANCE;

    /** 全局开关（由配置注入后复制；Appender 读取） */
    private static volatile boolean GLOBAL_ENABLED = true;

    /** 环形缓冲容量 */
    private final int capacity;

    /** 环形缓冲内容（synchronized 保护） */
    private final ArrayDeque<DebugLogEntry> entries = new ArrayDeque<>();

    /** 全局递增序号（保证顺序与去重） */
    private final AtomicLong seq = new AtomicLong();

    /** 新日志监听器（DebugLogService 注册用于 SSE 广播） */
    private final List<Consumer<DebugLogEntry>> listeners = new ArrayList<>();

    /**
     * 构造（Spring 注入配置）。
     *
     * @param capacity 缓冲容量（默认 2000）
     *
     * @param enabled 日志追加开关（holzyn.actor.debug.log-append.enabled，默认 true）
     */
    public DebugLogBuffer(@Value("${holzyn.actor.debug.log-buffer-size:2000}") int capacity,
                          @Value("${holzyn.actor.debug.log-append.enabled:true}") boolean enabled) {
        // 容量下限 1（极小值仅测试用；生产默认 2000）
        this.capacity = Math.max(1, capacity);
        GLOBAL_ENABLED = enabled;
    }

    /**
     * 启动后自注册全局实例 + 同步开关。
     */
    @PostConstruct
    void register() {
        INSTANCE = this;
    }

    /**
     * 当前实例是否开启日志追加（概览展示用）。
     *
     * @return true=开启
     */
    public boolean isEnabled() {
        return GLOBAL_ENABLED;
    }

    /**
     * 供 Logback Appender 访问的全局实例。
     *
     * @return 缓冲实例（Spring 未就绪时可能为 null）
     */
    public static DebugLogBuffer instance() {
        return INSTANCE;
    }

    /**
     * 全局开关当前值（Appender 读取；默认 true）。
     *
     * @return true=开启
     */
    public static boolean globalEnabled() {
        return GLOBAL_ENABLED;
    }

    /**
     * 由配置 Bean 设置全局开关。
     *
     * @param enabled true=开启
     */
    public static void setGlobalEnabled(boolean enabled) {
        GLOBAL_ENABLED = enabled;
    }

    /**
     * 注册新日志监听器（SSE 广播用）。
     *
     * @param listener 收到新日志时的回调
     */
    public void addListener(Consumer<DebugLogEntry> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * 一条日志（Appender 调用）：满则覆盖最旧，并通知监听器。
     *
     * @param level 日志级别（TRACE~ERROR）
     *
     * @param thread 线程名
     *
     * @param logger logger 名
     *
     * @param message 日志消息
     */
    public void append(String level, String thread, String logger, String message) {
        DebugLogEntry entry = new DebugLogEntry(seq.incrementAndGet(),
                LocalDateTime.now().format(TIME_FMT), level, thread, logger, message);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
        List<Consumer<DebugLogEntry>> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (Consumer<DebugLogEntry> listener : snapshot) {
            try {
                listener.accept(entry);
            } catch (Exception ignored) {
                // 监听器失败不影响日志缓冲
            }
        }
    }

    /**
     * 最近 N 条（新连接补发用；不足 N 返回全部）。
     *
     * @param n 条数
     *
     * @return 时间升序的最近 N 条
     */
    public List<DebugLogEntry> recent(int n) {
        synchronized (entries) {
            int from = Math.max(0, entries.size() - n);
            return new ArrayList<>(entries).subList(from, entries.size());
        }
    }

    /**
     * 全部缓冲内容（分页回看用）。
     *
     * @return 时间升序的全部条目
     */
    public List<DebugLogEntry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * 当前缓冲条目数。
     *
     * @return 条目数
     */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** 缓冲容量（概览展示用） */
    public int capacity() {
        return capacity;
    }

    /**
     * 日志条目（不可变）。
     *
     * @param seq 全局递增序号
     *
     * @param time 时间戳（yyyy-MM-dd HH:mm:ss.SSS）
     *
     * @param level 级别
     *
     * @param thread 线程名
     *
     * @param logger logger 名
     *
     * @param message 消息
     */
    public record DebugLogEntry(long seq, String time, String level, String thread, String logger, String message) {
    }
}
