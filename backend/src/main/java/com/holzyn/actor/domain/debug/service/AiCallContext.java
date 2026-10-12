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

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AI 调用上下文（ThreadLocal， 调试前端埋点 + B5 决策链路立基扩展）。
 * <p>职责：让调用方把「scene/characterId」维度与本次 AI 调用关联起来——
 * Router 埋点在当前线程读取上下文拿到场景与角色，调用方在 catch 到 AI 异常并做了
 * 程序化兜底时调用 {@link #markDegraded()} 把该调用标记为 degraded，
 * 块结束（finally）时统一异步落库（{@link AiCallContext#run} 的语义）。</p>
 * <p><b>B5（决策链路立基）</b>：新增三层扩展——</p>
 * <ul>
 * <li><b>业务动作级 trace_id 贯穿</b>：{@link #withTrace} 包裹一次业务动作（行动决策/对话回复/
 * 跨日事件润色等），块内所有 {@link #run} 创建的 AI 调用 trace 共享同一 trace_id
 * （一条决策链路多调用关联，）；{@link #beginTrace}/{@link #endTrace} 提供命令式版本；</li>
 * <li><b>重试 attempt 维度</b>：{@link #run(String, Long, int, Supplier)} 指定尝试序号，
 * 每次尝试各记一条、共享 trace_id，失败尝试可回查；</li>
 * <li><b>非 AI 环节记录</b>：{@link #recordStep} 为感知组装/行动执行/事件发布等非 AI 环节写一条
 * step_type 明确的 trace 行（content 存环节摘要+耗时，token=0），让一次决策链路含非 AI 步骤。</li>
 * </ul>
 * <p>使用示例：</p>
 * <pre>
 * </pre>
 * <p>降级示例（AI 失败后程序化兜底）：</p>
 * <pre>
 * AiCallContext.markDegraded(); // 本次调用记为 degraded（仅当处于 run 块内有效）
 * </pre>
 * <p>无上下文（调用方未包裹）时 Router 兜底记录通用 scene（chat/chat_stream），由
 * <p>落库 sink 由 {@link AiCallTraceService} 在启动时注册（{@link #setSink}），
 * 未注册时 run 块结束静默跳过（不阻塞任何业务）。</p>
 */
public final class AiCallContext {

    /** 当前线程正在进行的 trace（可空） */
    private static final ThreadLocal<AiCallTrace> CURRENT = new ThreadLocal<>();

    /** 当前线程业务动作级 trace_id（withTrace/beginTrace 设置；run 创建 trace 时继承） */
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /** 命令式 beginTrace/endTrace 嵌套配对栈（B5：endTrace 恢复 beginTrace 前的外层 TRACE_ID） */
    private static final ThreadLocal<java.util.Deque<String>> TRACE_STACK =
            ThreadLocal.withInitial(java.util.LinkedList::new);

    /** 落库 sink（AiCallTraceService 启动注册；测试可注入假 sink） */
    private static volatile Consumer<AiCallTrace> SINK;

    /**
     * trace 数据流补全：当前线程累积的感知组装数据源
     * （帧/记忆 memoryId/实体/刺激），下次 run()/recordStep() 创建 trace 时一次性复制并清空
     */
    private static final ThreadLocal<java.util.List<String>> DATA_SOURCES =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    /**
     * 主动记忆检索（proactive_retrieval）结果段：决策循环自问环节组装出的
     * 【主动想起】段文本，经 ThreadLocal 传递给决策组件（AI 深思 {{proactive_memory}}
     * 占位符 / NanoJev 快判情境文本注入）。随当前业务动作线程生命周期存在，
     * 由调用方在决策生成完成后 {@link #clearProactiveSegment()} 清理（防线程复用泄漏）。
     */
    private static final ThreadLocal<String> PROACTIVE_SEGMENT = new ThreadLocal<>();

    /**
     * MUSE 策略切换（muse_strategy）结果段：决策策略链内 FamiliarityStrategy
     * 计算出的【策略偏好】段文本（保守/激进），经 ThreadLocal 传递给决策组件
     * （AI 深思 {{strategy_preference}} 占位符 / NanoJev 快判情境文本首部注入）。
     * 链内 priority=85 先于 SpaceChoice(80)/Deliberate(60) 执行保证双通道可读；
     * 随当前业务动作线程生命周期存在，由调用方在决策生成完成后
     * {@link #clearStrategyPreference()} 清理（防线程复用泄漏）。
     */
    private static final ThreadLocal<String> STRATEGY_PREFERENCE = new ThreadLocal<>();

    private AiCallContext() {
    }

    /**
     * 注册落库 sink（由 AiCallTraceService 启动时调用）。
     *
     * @param sink 接收 trace 的消费者（通常=异步写库）
     */
    public static void setSink(Consumer<AiCallTrace> sink) {
        SINK = sink;
    }

    /**
     * 取当前线程正在进行的 trace（Router 埋点填充用；无上下文返回 null）。
     *
     * @return 当前 trace（可空）
     */
    public static AiCallTrace current() {
        return CURRENT.get();
    }

    /**
     * 取当前线程业务动作级 trace_id（B5；无 withTrace/beginTrace 包裹返回 null）。
     *
     * @return 当前 trace_id（可空）
     */
    public static String currentTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 在带调用上下文的块内执行业务动作：块内发起 Router 调用的 trace 将关联
     * 给定的 scene/characterId，块结束（finally）统一异步落库。
     *
     * @param scene 调用场景（dialog/card_gen/action/relation_gen/memory/...）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param action 块内动作（通常=一次 router 调用）
     *
     * @param <T>返回值类型
     *
     * @return 块内动作返回值
     */
    public static <T> T run(String scene, Long characterId, Supplier<T> action) {
        return run(scene, characterId, 1, action);
    }

    /**
     * 带调用上下文 + 重试尝试序号执行业务动作。
     * <p>trace 的 trace_id 继承当前线程业务动作级 TRACE_ID（withTrace 包裹时），
     * attempt 取传入序号——同一次动作的每次重试各记一条、共享 trace_id，失败尝试可回查。</p>
     *
     * @param scene 调用场景
     *
     * @param characterId 角色 ID（可空）
     *
     * @param attempt 重试尝试序号（<=0 回退 1）
     *
     * @param action 块内动作
     *
     * @param <T>返回值类型
     *
     * @return 块内动作返回值
     */
    public static <T> T run(String scene, Long characterId, int attempt, Supplier<T> action) {
        AiCallTrace prev = CURRENT.get();
        AiCallTrace trace = new AiCallTrace(scene, characterId);
        trace.setTraceId(TRACE_ID.get());
        trace.setAttempt(attempt);
        trace.setContextOwned(true);
        // ：把当前线程累积的感知组装数据源复制进本次调用 trace（复制后清空累积）
        java.util.List<String> ds = takeDataSources();
        if (ds != null) {
            ds.forEach(trace::addDataSource);
        }
        CURRENT.set(trace);
        try {
            return action.get();
        } finally {
            CURRENT.set(prev);
            Consumer<AiCallTrace> sink = SINK;
            if (sink != null) {
                sink.accept(trace);
            }
        }
    }

    /**
     * 以业务动作级 trace_id 包裹一次业务动作（B5 决策链路贯穿）：
     * 块内所有 {@link #run} 创建的 AI 调用 trace 共享该 trace_id（一条决策链路多调用关联）。
     *
     * @param traceId 业务动作级 trace_id（null/空白则生成新 UUID）
     *
     * @param action 块内业务动作（可含多次 run / recordStep）
     *
     * @param <T>返回值类型
     *
     * @return 块内动作返回值
     */
    public static <T> T withTrace(String traceId, Supplier<T> action) {
        String prev = TRACE_ID.get();
        TRACE_ID.set(traceId == null || traceId.isBlank() ? java.util.UUID.randomUUID().toString() : traceId);
        try {
            return action.get();
        } finally {
            TRACE_ID.set(prev);
        }
    }

    /**
     * 命令式开启业务动作级 trace_id（B5）：保存当前外层 TRACE_ID 到配对栈，生成并设置
     * 当前线程 TRACE_ID，返回该 id。配合 {@link #endTrace()} 使用（finally 中调用）；
     * 与 {@link #withTrace} 等价。
     *
     * @return 生成的 trace_id
     */
    public static String beginTrace() {
        TRACE_STACK.get().push(TRACE_ID.get());
        String id = java.util.UUID.randomUUID().toString();
        TRACE_ID.set(id);
        return id;
    }

    /**
     * 命令式结束业务动作级 trace_id（恢复 beginTrace 前的外层 TRACE_ID，与 beginTrace 配对）。
     * <p><b>嵌套安全（B5 修复）</b>：begin/end 按栈配对——begin 时压入外层，end 时弹出恢复，
     * 内层 begin/end 不会误伤外层链路；无配对 begin 时兜底移除当前 TRACE_ID。</p>
     */
    public static void endTrace() {
        java.util.Deque<String> stack = TRACE_STACK.get();
        if (stack.isEmpty()) {
            TRACE_ID.remove();
        } else {
            TRACE_ID.set(stack.pop());
        }
    }

    /**
     * 记录一个非 AI 环节到决策链路（B5：感知组装/行动执行/事件发布等不调用 AI 的步骤）。
     * <p>写一条 step_type 明确、content 为环节摘要的 trace 行（token=0、无 prompt/response、
     * status=success），trace_id 继承当前业务动作级 TRACE_ID（withTrace 包裹时）；
     * 无 sink（埋点开关关闭/未注册）时静默跳过。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param scene 调用场景（action/dialog/world_event/...）
     *
     * @param stepType 环节类型（perception/action/event/...；null 回退 ai_call）
     *
     * @param content 环节摘要（如「感知组装：可见事件 2 条」）
     *
     * @param durationMs 环节耗时（毫秒，<=0 记 0）
     */
    public static void recordStep(Long projectId, String scene, String stepType, String content, long durationMs) {
        Consumer<AiCallTrace> sink = SINK;
        if (sink == null) {
            return;
        }
        AiCallTrace trace = new AiCallTrace(scene == null || scene.isBlank() ? "step" : scene, null);
        trace.setProjectId(projectId);
        trace.setTraceId(TRACE_ID.get());
        trace.setStepType(stepType);
        trace.setContent(content == null || content.isBlank() ? null : content);
        trace.setDurationMs((int) Math.max(0, Math.min(Integer.MAX_VALUE, durationMs)));
        // ：感知组装环节同步把当前累积数据源记入 trace 行（供数据流回溯）
        java.util.List<String> ds = takeDataSources();
        if (ds != null) {
            ds.forEach(trace::addDataSource);
        }
        trace.markInvoked();
        trace.setContextOwned(true);
        sink.accept(trace);
    }

    /**
     * 累积一条感知组装数据源（帧/记忆 memoryId/实体/刺激等）。
     * 数据源随下一次 {@link #run}/{@link #recordStep} 复制进 trace 落库（上限 20 条防膨胀）。
     *
     * @param source 数据源描述（如「记忆#1024」/「帧#day-12」/「实体#5」/「刺激:事件#88」）
     */
    public static void addDataSource(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        java.util.List<String> l = DATA_SOURCES.get();
        if (l.size() < 20) {
            l.add(source);
        }
    }

    /**
     * 取出并清空当前线程累积的数据源（无累积返回 null）。
     */
    private static java.util.List<String> takeDataSources() {
        java.util.List<String> l = DATA_SOURCES.get();
        if (l == null || l.isEmpty()) {
            return null;
        }
        java.util.List<String> copy = new java.util.ArrayList<>(l);
        l.clear();
        return copy;
    }

    /**
     * 持有主动记忆检索结果段（【主动想起】段文本；空白忽略）。
     * 供决策循环自问环节写入、决策组件读取（当前线程内一次业务动作）。
     *
     * @param segment 主动检索段文本（可空/空白=不持有）
     */
    public static void holdProactiveSegment(String segment) {
        if (segment != null && !segment.isBlank()) {
            PROACTIVE_SEGMENT.set(segment);
        }
    }

    /**
     * 读取当前线程主动记忆检索结果段（可空；不消费——AI 深思重试多次构建 prompt 均可见）。
     *
     * @return 【主动想起】段文本（无则 null）
     */
    public static String readProactiveSegment() {
        return PROACTIVE_SEGMENT.get();
    }

    /**
     * 清除当前线程主动记忆检索结果段（业务动作完成后调用，防虚拟线程复用泄漏）。
     */
    public static void clearProactiveSegment() {
        PROACTIVE_SEGMENT.remove();
    }

    /**
     * 持有 MUSE 策略切换结果段（【策略偏好】段文本；空白忽略）。
     * 供决策策略链内 FamiliarityStrategy 写入、决策组件读取（当前线程内一次业务动作）。
     *
     * @param segment 策略偏好段文本（可空/空白=不持有）
     */
    public static void holdStrategyPreference(String segment) {
        if (segment != null && !segment.isBlank()) {
            STRATEGY_PREFERENCE.set(segment);
        }
    }

    /**
     * 读取当前线程 MUSE 策略切换结果段（可空；不消费——AI 深思重试多次构建 prompt 均可见）。
     *
     * @return 【策略偏好】段文本（无则 null）
     */
    public static String readStrategyPreference() {
        return STRATEGY_PREFERENCE.get();
    }

    /**
     * 清除当前线程 MUSE 策略切换结果段（业务动作完成后调用，防虚拟线程复用泄漏）。
     */
    public static void clearStrategyPreference() {
        STRATEGY_PREFERENCE.remove();
    }

    /**
     * 标记当前调用为降级（AI 失败后调用方做了程序化兜底）。
     * <p>仅当调用处于 {@link #run} 块内有效（当前线程存在上下文）；否则静默忽略。</p>
     */
    public static void markDegraded() {
        AiCallTrace trace = CURRENT.get();
        if (trace != null) {
            trace.setDegraded(true);
        }
    }
}
