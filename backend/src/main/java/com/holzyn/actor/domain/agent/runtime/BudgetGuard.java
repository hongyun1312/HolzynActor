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
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 预算守卫（B3 世界推进引擎，domain/agent/runtime；阶段 C AgentRuntime 复用的预算组件）。
 * <p>职责：把「每 tick AI 预算」做成成本控制而非冻结（修 /L-02）——
 * 每个项目每个 tick 有一个 AI 调用预算（缺省 5，来自 actor_world_engine.budget_per_tick），
 * 预算用尽后推进继续但全部走程序化兜底（世界不冻结）；预算消耗写 actor_ai_call_log（trace
 * 已具备），调试前端用量图可实时观测（W-3）。</p>
 * <p>实现：内存 ConcurrentHashMap&lt;projectId, 剩余预算&gt;，WorldEngine 每 tick 开始调用
 * {@link #beginTick(projectId, budget)} 重置，推进过程中 {@link #tryConsume(projectId)}
 * 原子扣减；单实例运行约定（与双推进线收敛后单入口一致）。</p>
 * <p>纯逻辑（canConsume/consume）抽静态方法可单测。</p>
 */
@Slf4j
@Component
public class BudgetGuard {

    /** 每项目当前 tick 剩余预算（无行=未开始 tick，视为 0） */
    private final ConcurrentHashMap<Long, AtomicInteger> remaining = new ConcurrentHashMap<>();

    /** 每项目当前 tick 已消耗 AI 调用数（与 remaining 成对维护，无行=0） */
    private final ConcurrentHashMap<Long, AtomicInteger> used = new ConcurrentHashMap<>();

    // ==================== 长跑 修复：异步剧集预算命名空间 ====================
    // 无玩家群聊异步化后，剧集在引擎 tick 外推进（虚拟线程并发）。若剧集复用/覆盖引擎 tick 预算
    // 窗口（beginTick 是覆盖式 put），会饿死引擎 AI（决策全落程序化兜底）。因此为剧集提供独立
    // 命名空间——与引擎 tick 预算完全隔离，互不覆盖、互不消耗。

    /** 每项目异步剧集剩余预算（无行=未开剧集窗口） */
    private final ConcurrentHashMap<Long, AtomicInteger> episodeRemaining = new ConcurrentHashMap<>();

    /** 每项目异步剧集已消耗 AI 调用数 */
    private final ConcurrentHashMap<Long, AtomicInteger> episodeUsed = new ConcurrentHashMap<>();

    /**
     * 开启一个异步剧集预算窗口（AutonomousChatService 独立路径自开）。
     * 与 {@link #beginTick} 的引擎 tick 窗口隔离——即使引擎 tick 正在推进也不会互相覆盖。
     *
     * @param projectId 项目 ID
     *
     * @param budget 剧集 AI 预算（<=0 视为 0=全程序化）
     */
    public void beginEpisode(Long projectId, int budget) {
        episodeRemaining.put(projectId, new AtomicInteger(Math.max(0, budget)));
        episodeUsed.put(projectId, new AtomicInteger(0));
    }

    /**
     * 尝试消耗 1 次异步剧集 AI 预算（原子）。
     *
     * @param projectId 项目 ID
     *
     * @return true=剧集预算充足且消耗成功；false=用尽或未开剧集窗口
     */
    public boolean tryConsumeEpisode(Long projectId) {
        AtomicInteger rem = episodeRemaining.get(projectId);
        if (rem == null) {
            return false;
        }
        if (tryConsumeAtomic(rem)) {
            episodeUsed.computeIfAbsent(projectId, k -> new AtomicInteger(0)).incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 当前异步剧集剩余预算。
     *
     * @param projectId 项目 ID
     *
     * @return 剩余次数（未开剧集窗口返回 0）
     */
    public int remainingEpisode(Long projectId) {
        AtomicInteger rem = episodeRemaining.get(projectId);
        return rem == null ? 0 : rem.get();
    }

    /**
     * 异步剧集预算窗口是否存在。
     *
     * @param projectId 项目 ID
     *
     * @return true=剧集窗口开启中
     */
    public boolean hasEpisodeWindow(Long projectId) {
        return episodeRemaining.containsKey(projectId);
    }

    /**
     * 当前异步剧集已消耗的 AI 调用数。
     *
     * @param projectId 项目 ID
     *
     * @return 已消耗次数（未开剧集窗口返回 0）
     */
    public int episodeUsed(Long projectId) {
        AtomicInteger u = episodeUsed.get(projectId);
        return u == null ? 0 : u.get();
    }

    /**
     * 结束当前异步剧集预算窗口（剧集 finally 调用）。
     *
     * @param projectId 项目 ID
     */
    public void endEpisode(Long projectId) {
        episodeRemaining.remove(projectId);
        episodeUsed.remove(projectId);
    }

    // ==================== R1 引擎 AI 异步化/前瞻缓冲：异步决策批次预算命名空间 ====================
    // 小时边界不再同步等待全体角色 AI 决策 ——改为异步派发：预热/补发批次在
    // 引擎 tick 窗口之外运行（虚拟/池线程），若复用引擎 tick 窗口（beginTick 是覆盖式 put + endTick
    // 会随推进结束关闭），会饿死批次内角色决策（预算被关窗重置或与刺激决策互抢）。因此为引擎异步
    // 决策批次提供独立命名空间——与引擎 tick 预算完全隔离，互不覆盖、互不消耗（沿 episode 先例）。
    // 批次引用计数：重叠批次（预热批次未排空又触发补发）预算累加、最后一个批次结束时整体关闭。

    /** 每项目异步决策批次剩余预算（无行=未开批次窗口） */
    private final ConcurrentHashMap<Long, AtomicInteger> batchRemaining = new ConcurrentHashMap<>();

    /** 每项目异步决策批次已消耗 AI 调用数 */
    private final ConcurrentHashMap<Long, AtomicInteger> batchUsed = new ConcurrentHashMap<>();

    /** 每项目异步决策批次引用计数（重叠批次管理） */
    private final ConcurrentHashMap<Long, AtomicInteger> batchRefs = new ConcurrentHashMap<>();

    /**
     * 开启一个异步决策批次预算窗口（WorldEngine 预热/边界补发批次调用）。
     * 与 {@link #beginTick} 的引擎 tick 窗口隔离——批次任务跨引擎扫描窗口运行时仍能消费本批次预算。
     *
     * @param projectId 项目 ID
     *
     * @param budget 批次 AI 预算（<=0 视为 0=全程序化；重叠批次累加）
     */
    public void beginBatch(Long projectId, int budget) {
        if (projectId == null) {
            return;
        }
        batchRefs.compute(projectId, (k, refs) -> {
            AtomicInteger r = refs == null ? new AtomicInteger(0) : refs;
            r.incrementAndGet();
            return r;
        });
        batchRemaining.compute(projectId, (k, rem) -> {
            AtomicInteger r = rem == null ? new AtomicInteger(0) : rem;
            r.addAndGet(Math.max(0, budget));
            return r;
        });
        batchUsed.computeIfAbsent(projectId, k -> new AtomicInteger(0));
    }

    /**
     * 尝试消耗 1 次异步决策批次 AI 预算（原子）。
     *
     * @param projectId 项目 ID
     *
     * @return true=批次预算充足且消耗成功；false=用尽或未开批次窗口
     */
    public boolean tryConsumeBatch(Long projectId) {
        AtomicInteger rem = batchRemaining.get(projectId);
        if (rem == null) {
            return false;
        }
        if (tryConsumeAtomic(rem)) {
            batchUsed.computeIfAbsent(projectId, k -> new AtomicInteger(0)).incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 结束一个异步决策批次预算窗口（批次最后一个任务完成时调用；引用计数归零才真正关闭）。
     *
     * @param projectId 项目 ID
     */
    public void endBatch(Long projectId) {
        if (projectId == null) {
            return;
        }
        AtomicInteger refs = batchRefs.get(projectId);
        if (refs != null && refs.decrementAndGet() <= 0) {
            batchRefs.remove(projectId);
            batchRemaining.remove(projectId);
            batchUsed.remove(projectId);
        }
    }

    /**
     * 异步决策批次预算窗口是否存在。
     *
     * @param projectId 项目 ID
     *
     * @return true=批次窗口开启中
     */
    public boolean hasBatchWindow(Long projectId) {
        return batchRemaining.containsKey(projectId);
    }

    /**
     * 当前异步决策批次剩余预算。
     *
     * @param projectId 项目 ID
     *
     * @return 剩余次数（未开批次窗口返回 0）
     */
    public int remainingBatch(Long projectId) {
        AtomicInteger rem = batchRemaining.get(projectId);
        return rem == null ? 0 : rem.get();
    }

    /**
     * 当前异步决策批次已消耗的 AI 调用数（调试/测试观测）。
     *
     * @param projectId 项目 ID
     *
     * @return 已消耗次数（未开批次窗口返回 0）
     */
    public int batchUsed(Long projectId) {
        AtomicInteger u = batchUsed.get(projectId);
        return u == null ? 0 : u.get();
    }

    // ==================== 反思预算独立命名空间 ====================
    // 反思（ReflectionService）此前与引擎 tick 共享 budget_per_tick（默认 5）窗口：tick 内
    // 24 角色决策链（每链多次 AI 调用）秒耗尽共享预算 → 日边界触发的反思 tryConsume 恒 false →
    // 反思被饿死（冒烟实证：首轮跨日反思执行 0 个角色、scene=reflection 零调用；临时调
    // budget_per_tick=200 后立即恢复）。反思属「每游戏日周期重活」，与 tick 高频决策语义不同，
    // 必须走独立命名空间——与引擎 tick / 异步剧集 / 异步批次完全隔离，互不覆盖、互不消耗
    // （沿 episode/batch 先例）。窗口生命周期：WorldEngine 每游戏日巩固周期经
    // LearningServiceSkeleton.scheduleConsolidation → ReflectionService.openCycleBudget 开窗、
    // closeCycleBudget 关窗；无窗口的手动/单角色路径由 ReflectionService 自开自关。

    /** 每项目反思剩余预算（无行=未开反思窗口） */
    private final ConcurrentHashMap<Long, AtomicInteger> reflectionRemaining = new ConcurrentHashMap<>();

    /** 每项目反思已消耗 AI 调用数 */
    private final ConcurrentHashMap<Long, AtomicInteger> reflectionUsed = new ConcurrentHashMap<>();

    /**
     * 开启一个反思周期预算窗口（ReflectionService.openCycleBudget 调用）。
     * 与 {@link #beginTick} 的引擎 tick 窗口隔离——反思与 tick 决策互不竞争、互不饿死。
     *
     * @param projectId 项目 ID
     *
     * @param budget 反思 AI 预算（<=0 视为 0=全程序化）
     */
    public void beginReflection(Long projectId, int budget) {
        if (projectId == null) {
            return;
        }
        reflectionRemaining.put(projectId, new AtomicInteger(Math.max(0, budget)));
        reflectionUsed.put(projectId, new AtomicInteger(0));
    }

    /**
     * 尝试消耗 1 次反思 AI 预算（原子）。
     *
     * @param projectId 项目 ID
     *
     * @return true=反思预算充足且消耗成功；false=用尽或未开反思窗口
     */
    public boolean tryConsumeReflection(Long projectId) {
        AtomicInteger rem = reflectionRemaining.get(projectId);
        if (rem == null) {
            return false;
        }
        if (tryConsumeAtomic(rem)) {
            reflectionUsed.computeIfAbsent(projectId, k -> new AtomicInteger(0)).incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 当前反思周期剩余预算。
     *
     * @param projectId 项目 ID
     *
     * @return 剩余次数（未开反思窗口返回 0）
     */
    public int remainingReflection(Long projectId) {
        AtomicInteger rem = reflectionRemaining.get(projectId);
        return rem == null ? 0 : rem.get();
    }

    /**
     * 反思周期预算窗口是否存在。
     *
     * @param projectId 项目 ID
     *
     * @return true=反思窗口开启中
     */
    public boolean hasReflectionWindow(Long projectId) {
        return reflectionRemaining.containsKey(projectId);
    }

    /**
     * 当前反思周期已消耗的 AI 调用数（调试/测试观测）。
     *
     * @param projectId 项目 ID
     *
     * @return 已消耗次数（未开反思窗口返回 0）
     */
    public int reflectionUsed(Long projectId) {
        AtomicInteger u = reflectionUsed.get(projectId);
        return u == null ? 0 : u.get();
    }

    /**
     * 结束当前反思周期预算窗口（ReflectionService.closeCycleBudget 调用）。
     *
     * @param projectId 项目 ID
     */
    public void endReflection(Long projectId) {
        reflectionRemaining.remove(projectId);
        reflectionUsed.remove(projectId);
    }

    /**
     * 开启一个 tick 的预算窗口（WorldEngine 每个项目推进前调用）。
     *
     * @param projectId 项目 ID
     *
     * @param budget 本次 tick 的 AI 预算（<=0 视为 0=全程序化）
     */
    public void beginTick(Long projectId, int budget) {
        // 登记窗口：WorldEngine 用本方法开窗，AgentRuntime 的 beginTickIfAbsent 依赖这份登记
        // 判断"当前是否已有窗口"——不登记就会误判为"无窗口"从而重复开窗（预算翻倍）。
        if (projectId != null) {
            tickWindowOpen.add(projectId);
        }
        remaining.put(projectId, new AtomicInteger(Math.max(0, budget)));
        used.put(projectId, new AtomicInteger(0));
    }

    /**
     * 已开启 tick 窗口的项目集合。
     */
    private final java.util.Set<Long> tickWindowOpen = ConcurrentHashMap.newKeySet();

    /**
     * 原子地"若当前没有 tick 窗口则开一个"。
     * <p>为什么需要：`AgentRuntime` 原先用**全局单锁**防重入，因此"当前有没有 tick 窗口"
     * 与"我是不是开窗人"是同一件事；改成**按角色**防重入后，多个角色的决策循环可以并发，
     * 于是必须把"开窗"做成原子操作——否则两个线程同时看到 `budgetTotal < 0` 就会各开一个
     * 窗口，把每 tick 预算翻倍（预算语义被并发破坏）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param budget 本 tick 预算（仅开窗人使用）
     *
     * @return true=本次调用开出了窗口（调用方负责在 finally 里 {@link #endTick}）；
     * false=窗口已存在（沿用他人窗口，不要关闭）
     */
    public boolean beginTickIfAbsent(Long projectId, int budget) {
        if (projectId == null || !tickWindowOpen.add(projectId)) {
            return false;
        }
        remaining.put(projectId, new AtomicInteger(Math.max(0, budget)));
        used.put(projectId, new AtomicInteger(0));
        return true;
    }

    /**
     * 当前是否有已开启的 tick 窗口（未开窗时 AgentRuntime 会尝试开窗）。
     *
     * @param projectId 项目 ID
     *
     * @return true=窗口已开
     */
    public boolean hasTickWindow(Long projectId) {
        return projectId != null && tickWindowOpen.contains(projectId);
    }

    /**
     * 尝试消耗 1 次 AI 预算（原子）。
     *
     * @param projectId 项目 ID
     *
     * @return true 表示预算充足、本次消耗成功；false 表示预算用尽（调用方应走程序化兜底）
     */
    public boolean tryConsume(Long projectId) {
        AtomicInteger rem = remaining.get(projectId);
        if (rem == null) {
            return false;
        }
        if (tryConsumeAtomic(rem)) {
            used.computeIfAbsent(projectId, k -> new AtomicInteger(0)).incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 当前 tick 剩余预算。
     *
     * @param projectId 项目 ID
     *
     * @return 剩余次数（未开始 tick 返回 0）
     */
    public int remaining(Long projectId) {
        AtomicInteger rem = remaining.get(projectId);
        return rem == null ? 0 : rem.get();
    }

    /**
     * 当前 tick 已消耗的 AI 调用数。
     *
     * @param projectId 项目 ID
     *
     * @return 已消耗次数（未开始 tick 返回 0）
     */
    public int used(Long projectId) {
        AtomicInteger u = used.get(projectId);
        return u == null ? 0 : u.get();
    }

    /**
     * 结束当前 tick 的预算窗口（WorldEngine 推进结束后调用，防跨 tick 误用）。
     *
     * @param projectId 项目 ID
     */
    public void endTick(Long projectId) {
        tickWindowOpen.remove(projectId);
        remaining.remove(projectId);
        used.remove(projectId);
    }

    /**
     * 查询某项目最近一次 tick 的预算总量（供调试前端状态卡展示）。
     * <p>内存态：仅当该项目正在推进时有值；否则返回 -1（由调用方回退引擎配置）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 最近一次 tick 预算总量（-1=未知）
     */
    public int budgetTotal(Long projectId) {
        AtomicInteger rem = remaining.get(projectId);
        if (rem == null) {
            return -1;
        }
        return rem.get() + used(projectId);
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 预算是否可消耗（剩余 > 0）。
     *
     * @param remaining 剩余预算
     *
     * @return true 表示可消耗
     */
    static boolean canConsume(int remaining) {
        return remaining > 0;
    }

    /**
     * 消耗 1 次预算（不落到 0 以下）。
     *
     * @param remaining 剩余预算
     *
     * @return 消耗后的剩余
     */
    static int consume(int remaining) {
        return remaining > 0 ? remaining - 1 : remaining;
    }

    /**
     * 对 AtomicInteger 原子执行「消耗 1 次」：成功返回 true，预算用尽返回 false。
     *
     * @param rem 剩余预算（原子计数器）
     *
     * @return true 表示本次消耗成功
     */
    static boolean tryConsumeAtomic(AtomicInteger rem) {
        while (true) {
            int cur = rem.get();
            if (cur <= 0) {
                return false;
            }
            if (rem.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }
}
