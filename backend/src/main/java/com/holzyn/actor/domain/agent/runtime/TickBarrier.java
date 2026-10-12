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

package com.holzyn.actor.domain.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * tick 屏障。
 * <p><b>要解决的问题</b>：世界引擎按 5 游戏分钟一个 tick 推进，但"本 tick 派生出的
 * 异步工作"从不被等待——刺激驱动的即时决策、状态情绪 AI 评估都是虚拟线程射后不管，
 * 于是会出现：① 一个刺激决策基于 tick N 的感知、却把状态写在 tick N+2；
 * ② 情绪评估写回时状态衰减已经往前走了（同一行被两个线程反复覆盖）；
 * ③ 引擎 tick 的长事务与这些写回抢 {@code actor_state}，实测 56 次
 * {@code Timeout trying to lock table}（其中 3 次彻底丢弃已付费的 AI 结果）；
 * ④ 跨日重活把 {@code scheduling-1} 占住 3.5 分钟，期间世界整体停摆。</p>
 * <p><b>屏障语义（用户 决策：窄屏障 + 刺激排队 + 超时放行）</b>：</p>
 * <ol>
 * <li><b>窄屏障范围</b>——只等待"会写 {@code actor_state} / 行动结果"的决策链：角色决策
 * （{@link AgentRuntime}）、刺激即时决策（{@link RealTimeDecisionService}）、
 * 状态情绪 AI 评估（{@code NpcStateService}）。记忆提炼/编年史/宏观/世界帧等
 * <b>不入屏障</b>（它们不改状态曲线，且本就按游戏日/小时错峰）。</li>
 * <li><b>刺激排队</b>——屏障一旦过去（本 tick 的在途任务已排空），新到的刺激不再立即执行，
 * 而是入队等**下一个 tick 的派发相**。代价 ≤ 1 tick＝5 游戏分钟（rate=48 时真实约 0.6 秒），
 * 换来严格因果顺序：NPC 的反应一定落在"他感知到的时刻"之后、"下一个时刻"之前。</li>
 * <li><b>超时放行</b>——等待上限 {@code max-wait-ms}（默认 30 秒）到点即放行并 WARN 计数，
 * 世界永不被慢依赖冻结（对齐"观测数据不得反噬业务"）。连续超时达阈值时**自愈**清零计数，
 * 避免某个漏掉 complete 的 bug 让每 tick 都白等 30 秒。</li>
 * <li><b>跨 tick 结转</b>——{@code beginTick} 不清零在途计数：上一次 tick 结束后仍在跑的写回
 * 会被计入本次屏障，因此"上一个 tick 的工作全部结束"才是新 tick 开始的前提。</li>
 * </ol>
 * <p>关闭开关：{@code holzyn.actor.world.barrier.enabled=false} 退回改造前的自由并发模型
 * （beginTick/await 变空操作，刺激立即执行）。</p>
 */
@Slf4j
@Component
public class TickBarrier {

    /** 屏障总开关（false=退回改造前的自由并发模型，便于快速回退） */
    @Value("${holzyn.actor.world.barrier.enabled:true}")
    private boolean enabled = true;

    /** 单 tick 等待在途任务的上限（毫秒；到点放行，世界不被慢依赖冻结） */
    @Value("${holzyn.actor.world.barrier.max-wait-ms:30000}")
    private long maxWaitMs = 30_000L;

    /** 等待轮询间隔（毫秒） */
    @Value("${holzyn.actor.world.barrier.poll-ms:20}")
    private long pollMs = 20L;

    /** 等待超过该时长才打 INFO（避免正常 tick 刷屏） */
    @Value("${holzyn.actor.world.barrier.slow-wait-log-ms:500}")
    private long slowWaitLogMs = 500L;

    /** 连续超时多少次后自愈清零（防"漏 complete"导致每 tick 白等） */
    @Value("${holzyn.actor.world.barrier.self-heal-after-timeouts:3}")
    private int selfHealAfterTimeouts = 3;

    /** 单次扫描最多推进多少 tick（屏障模式下限制"快进补推"：宁可游戏时钟走慢，也不要快进跳过） */
    @Value("${holzyn.actor.world.barrier.max-catch-up-ticks:24}")
    private int maxCatchUpTicks = 24;

    /** 小时级决策派发的并行度（屏障下 fan-out：tick 耗时从 Σ 变 max；1=串行） */
    @Value("${holzyn.actor.world.barrier.parallel-dispatch:4}")
    private int parallelDispatch = 4;

    /** 每项目屏障状态 */
    private static final class ProjectTick {
        /** 当前 tick 序号（0=无活动 tick——引擎停/暂停时刺激照旧即时执行） */
        private volatile long epoch;
        /** 本 tick 的屏障是否已过去（true=此后到达的刺激排队到下一 tick） */
        private volatile boolean barrierPassed;
        /** 在途任务计数（register/complete 成对） */
        private final AtomicInteger pending = new AtomicInteger();
        /** 排队到下一 tick 派发相的刺激 */
        private final Queue<Runnable> deferred = new ConcurrentLinkedQueue<>();
        /** 连续超时次数（自愈用） */
        private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
    }

    private final ConcurrentHashMap<Long, ProjectTick> ticks = new ConcurrentHashMap<>();

    /** 累计等待次数/等待总时长/超时次数/排队刺激数（可观测） */
    private final AtomicLong totalWaits = new AtomicLong();
    private final AtomicLong totalWaitedMs = new AtomicLong();
    private final AtomicLong totalTimeouts = new AtomicLong();
    private final AtomicLong totalDeferred = new AtomicLong();

    private ProjectTick tickOf(Long projectId) {
        return projectId == null ? null : ticks.get(projectId);
    }

    /**
     * 屏障是否启用（WorldEngine 据此决定是否启用 fan-out 与限流）。
     *
     * @return true=启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** 小时级决策派发并行度（1=串行）。 */
    public int parallelDispatch() {
        return Math.max(1, parallelDispatch);
    }

    /** 单次扫描最多推进的 tick 数（屏障模式下的快进上限）。 */
    public int maxCatchUpTicks() {
        return Math.max(1, maxCatchUpTicks);
    }

    /** 等待上限（毫秒）。 */
    public long maxWaitMs() {
        return Math.max(0L, maxWaitMs);
    }

    /**
     * 开始一个 tick：递增序号、复位"屏障已过"标志、**清理排队刺激**（调用方随后派发）。
     * <p>注意：<b>不清零在途计数</b>——上一次 tick 结束后仍在跑的写回要计入本次屏障，
     * 这样"上一个 tick 的工作全部结束"才是新 tick 开始的前提（跨 tick 结转）。</p>
     *
     * @param projectId 项目 ID（可空=空操作）
     *
     * @return 本 tick 序号（0=未启用/空项目）
     */
    public long beginTick(Long projectId) {
        if (!enabled || projectId == null) {
            return 0L;
        }
        ProjectTick t = ticks.computeIfAbsent(projectId, k -> new ProjectTick());
        t.barrierPassed = false;
        return ++t.epoch;
    }

    /**
     * 登记一个在途任务（必须在把任务交给线程**之前**调用）。
     *
     * @param projectId 项目 ID（可空=空操作）
     */
    public void register(Long projectId) {
        ProjectTick t = tickOf(projectId);
        if (t == null) {
            return;
        }
        t.pending.incrementAndGet();
    }

    /**
     * 完成一个在途任务（与 {@link #register} 成对；计数不会为负）。
     *
     * @param projectId 项目 ID（可空=空操作）
     */
    public void complete(Long projectId) {
        ProjectTick t = tickOf(projectId);
        if (t == null) {
            return;
        }
        t.pending.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /**
     * 该刺激是否应排队到下一 tick（屏障已过 = 本 tick 的在途任务已排空）。
     * <p>返回 false 的情形：未启用、无活动 tick（引擎停/暂停 → 刺激照旧即时执行，
     * 否则引擎不跑时 NPC 永远不反应）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true=应排队
     */
    public boolean shouldDefer(Long projectId) {
        if (!enabled) {
            return false;
        }
        ProjectTick t = tickOf(projectId);
        return t != null && t.epoch > 0 && t.barrierPassed;
    }

    /**
     * 把刺激排队到下一 tick 的派发相。
     *
     * @param projectId 项目 ID
     *
     * @param task 派发时执行的任务（不可空）
     */
    public void defer(Long projectId, Runnable task) {
        ProjectTick t = tickOf(projectId);
        if (t == null || task == null) {
            return;
        }
        t.deferred.add(task);
        totalDeferred.incrementAndGet();
    }

    /**
     * 取出并清空本 tick 要派发的排队刺激（在 {@link #beginTick} 之后调用）。
     *
     * @param projectId 项目 ID
     *
     * @return 待派发任务列表（无则空列表）
     */
    public List<Runnable> drainDeferred(Long projectId) {
        ProjectTick t = tickOf(projectId);
        if (t == null) {
            return List.of();
        }
        List<Runnable> out = new ArrayList<>();
        Runnable r;
        while ((r = t.deferred.poll()) != null) {
            out.add(r);
        }
        return out;
    }

    /**
     * 等待本 tick 的在途任务排空（随后把屏障标记为"已过"，新到的刺激改为排队）。
     *
     * @param projectId 项目 ID
     *
     * @return 等待结果（等待时长/剩余在途/是否超时）
     */
    public AwaitResult awaitQuiescence(Long projectId) {
        ProjectTick t = tickOf(projectId);
        if (t == null || !enabled) {
            return new AwaitResult(0L, 0, false);
        }
        long start = System.currentTimeMillis();
        long deadline = start + maxWaitMs();
        while (t.pending.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.max(1L, pollMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 屏障已过：此后到达的刺激排队到下一 tick（严格因果顺序）
        t.barrierPassed = true;
        int remaining = t.pending.get();
        long waited = System.currentTimeMillis() - start;
        totalWaits.incrementAndGet();
        totalWaitedMs.addAndGet(waited);
        if (remaining > 0) {
            totalTimeouts.incrementAndGet();
            int consecutive = t.consecutiveTimeouts.incrementAndGet();
            if (selfHealAfterTimeouts > 0 && consecutive >= selfHealAfterTimeouts) {
                t.pending.set(0);
                t.consecutiveTimeouts.set(0);
                log.warn("[Tick屏障] 连续 {} 次超时，自愈清零在途计数（疑似有任务漏 complete）: project={} 剩余={}",
                        consecutive, projectId, remaining);
            } else {
                log.warn("[Tick屏障] 等待超时（{}ms），放行不冻结世界: project={} 仍有 {} 个在途任务"
                        + "（它们的写回会落到本 tick 之后）", waited, projectId, remaining);
            }
        } else {
            t.consecutiveTimeouts.set(0);
            if (waited >= slowWaitLogMs) {
                log.info("[Tick屏障] 本 tick 在途任务已排空: project={} 等待 {}ms", projectId, waited);
            }
        }
        return new AwaitResult(waited, remaining, remaining > 0);
    }

    /**
     * 结束本 tick：标记为"无活动 tick"（此后刺激即时执行，适配引擎停/暂停）。
     *
     * @param projectId 项目 ID
     */
    public void endTick(Long projectId) {
        ProjectTick t = tickOf(projectId);
        if (t == null) {
            return;
        }
        t.epoch = 0L;
        t.barrierPassed = false;
    }

    /**
     * 当前在途任务数（可观测/测试用）。
     *
     * @param projectId 项目 ID
     *
     * @return 在途数（无状态=0）
     */
    public int pending(Long projectId) {
        ProjectTick t = tickOf(projectId);
        return t == null ? 0 : t.pending.get();
    }

    /**
     * 累计统计（可观测：世界运行结束后核对屏障效果）。
     */
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("waits", totalWaits.get());
        m.put("waitedMs", totalWaitedMs.get());
        m.put("timeouts", totalTimeouts.get());
        m.put("deferred", totalDeferred.get());
        return m;
    }

    /**
     * 屏障等待结果。
     *
     * @param waitedMs 实际等待毫秒
     *
     * @param remaining 仍在途的任务数（超时>0）
     *
     * @param timedOut 是否超时放行
     */
    public record AwaitResult(long waitedMs, int remaining, boolean timedOut) {
    }
}