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

package com.holzyn.actor.domain.debug.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.holzyn.actor.domain.debug.service.DebugLogBuffer;

/**
 * Logback 自定义 Appender：把每条日志推入内存环形缓冲（调试前端实时日志）。
 * <p>职责：实现 {@link AppenderBase}，在 {@link #append(ILoggingEvent)} 中把级别/线程/logger/消息
 * 写入 {@link DebugLogBuffer}（Spring 全局实例），由 SSE {@code /api/debug/logs/stream} 实时推送。</p>
 * <p>开关：由 logback-spring.xml 按 {@code holzyn.actor.debug.log-append.enabled} 附加本 Appender；
 * Appender 内再双重校验 {@link DebugLogBuffer#globalEnabled()}（Spring 配置 Bean 同步）与
 * 全局实例是否就绪（Spring Bean 未初始化前的启动日志会丢弃，可接受）。</p>
 * <p>防自循环：本 Appender 不产生任何日志输出；logback 内部（ch.qos.logback）噪音行直接跳过。</p>
 */
public class DebugLogAppender extends AppenderBase<ILoggingEvent> {

    /** 是否已启动（context 生命周期内有效） */
    private boolean started;

    /**
     * 日志事件处理：写入内存环形缓冲。
     *
     * @param event logback 日志事件
     */
    @Override
    protected void append(ILoggingEvent event) {
        if (!DebugLogBuffer.globalEnabled()) {
            return;
        }
        DebugLogBuffer buffer = DebugLogBuffer.instance();
        if (buffer == null) {
            return; // Spring 未就绪，丢弃
        }
        if (event == null || event.getFormattedMessage() == null) {
            return;
        }
        String loggerName = event.getLoggerName();
        // 跳过 logback 内部噪音（如配置解析），避免刷屏
        if (loggerName != null && loggerName.startsWith("ch.qos.logback")) {
            return;
        }
        buffer.append(event.getLevel() == null ? "INFO" : event.getLevel().toString(),
                event.getThreadName() == null ? "?" : event.getThreadName(),
                loggerName == null ? "?" : loggerName,
                event.getFormattedMessage());
    }
}
