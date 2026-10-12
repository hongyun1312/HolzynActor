package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BudgetGuard（B3 F3 预算守卫）单元测试（与实现同包，domain/agent/runtime）。
 * <p>职责：验证纯逻辑（预算可消耗/消耗不落负）与 tick 生命周期（beginTick 重置 →
 * tryConsume 原子扣减 → 用尽返回 false → endTick 清理）。</p>
 */
class BudgetGuardTest {

    /**
     * 静态纯逻辑：预算可消耗判定与消耗（不落到 0 以下）。
     */
    @Test
    void staticConsumeLogic() {
        assertTrue(BudgetGuard.canConsume(3), "有余额可消耗");
        assertFalse(BudgetGuard.canConsume(0), "0 不可消耗");
        assertFalse(BudgetGuard.canConsume(-1), "负不可消耗");
        assertEquals(2, BudgetGuard.consume(3), "消耗 1 次");
        assertEquals(0, BudgetGuard.consume(0), "0 消耗不落负");
    }

    /**
     * 原子消耗：并发安全 + 用尽后返回 false。
     */
    @Test
    void tryConsumeAtomic() {
        AtomicInteger rem = new AtomicInteger(2);
        assertTrue(BudgetGuard.tryConsumeAtomic(rem));
        assertTrue(BudgetGuard.tryConsumeAtomic(rem));
        assertFalse(BudgetGuard.tryConsumeAtomic(rem), "预算用尽");
        assertEquals(0, rem.get(), "剩余不为负");
    }

    /**
     * tick 生命周期：beginTick 重置预算 → 逐次消耗 → 剩余/已用正确 → 用尽后不再消耗。
     */
    @Test
    void tickLifecycle() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginTick(1L, 3);
        assertTrue(guard.tryConsume(1L));
        assertTrue(guard.tryConsume(1L));
        assertEquals(1, guard.remaining(1L));
        assertEquals(2, guard.used(1L));
        assertTrue(guard.tryConsume(1L));
        assertFalse(guard.tryConsume(1L), "预算用尽");
        assertEquals(0, guard.remaining(1L));
        assertEquals(3, guard.used(1L));

        // 未开始 tick 的项目不可消耗
        assertFalse(guard.tryConsume(2L));
        assertEquals(0, guard.remaining(2L));
    }

    /**
     * beginTick 覆盖重置（跨 tick 新预算）+ endTick 清理内存态。
     */
    @Test
    void beginAndEndTick() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginTick(1L, 2);
        assertTrue(guard.tryConsume(1L));
        guard.beginTick(1L, 5);
        assertEquals(5, guard.remaining(1L), "新 tick 重置为 5");

        guard.endTick(1L);
        assertEquals(0, guard.remaining(1L), "endTick 后无内存态");
        assertEquals(-1, guard.budgetTotal(1L), "budgetTotal 未知返回 -1");
    }

    // ==================== O6 G1a：窗口归属原子化（按角色并发决策的前提） ====================

    /**
     * {@code beginTickIfAbsent}：只有第一个到达者能开窗（返回 true），后来者复用窗口（返回 false，
     * 且<b>不得</b>重置预算）——这是"按角色并发决策"下防止每 tick 预算被翻倍的关键。
     */
    @Test
    void beginTickIfAbsentIsAtomicOwnership() {
        BudgetGuard guard = new BudgetGuard();
        assertTrue(guard.beginTickIfAbsent(1L, 5), "首个到达者开出窗口");
        assertTrue(guard.hasTickWindow(1L));
        assertTrue(guard.tryConsume(1L));
        assertEquals(4, guard.remaining(1L));

        assertFalse(guard.beginTickIfAbsent(1L, 5), "窗口已存在 → 后来者不开窗");
        assertEquals(4, guard.remaining(1L), "后来者不得重置（覆盖式 beginTick）预算");

        guard.endTick(1L);
        assertFalse(guard.hasTickWindow(1L), "endTick 清理窗口登记");
        assertTrue(guard.beginTickIfAbsent(1L, 7), "窗口关闭后可再次开窗");
        assertEquals(7, guard.remaining(1L));
        assertFalse(guard.beginTickIfAbsent(null, 7), "projectId 为空不开窗");
    }

    /**
     * {@code WorldEngine} 用覆盖式 {@code beginTick} 开窗时**同样登记**窗口——
     * 否则 AgentRuntime 的 {@code beginTickIfAbsent} 会误判"无窗口"而重复开窗（预算翻倍）。
     */
    @Test
    void plainBeginTickAlsoRegistersWindow() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginTick(2L, 3);
        assertTrue(guard.hasTickWindow(2L), "覆盖式 beginTick 也登记窗口");
        assertFalse(guard.beginTickIfAbsent(2L, 3), "引擎已开窗 → AgentRuntime 复用不重开");
        assertEquals(3, guard.remaining(2L), "预算未被后来者重置");
        guard.endTick(2L);
        assertFalse(guard.hasTickWindow(2L));
    }

    // ==================== R1 异步决策批次预算命名空间（2026-09-28） ====================

    /**
     * 批次窗口与 tick 窗口完全隔离：beginBatch 消费走 tryConsumeBatch，不触碰 tick 窗口；
     * endBatch 关闭批次窗口（budgetTotal 仍反映 tick 窗口独立状态）。
     */
    @Test
    void batchNamespaceIsolatedFromTickWindow() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginBatch(1L, 4);
        assertTrue(guard.hasBatchWindow(1L), "批次窗口开启");
        assertTrue(guard.tryConsumeBatch(1L));
        assertTrue(guard.tryConsumeBatch(1L));
        assertEquals(2, guard.remainingBatch(1L));
        assertEquals(2, guard.batchUsed(1L));
        assertTrue(guard.tryConsumeBatch(1L));
        assertTrue(guard.tryConsumeBatch(1L), "第 4 次消耗成功（预算 4 全部用尽）");
        assertFalse(guard.tryConsumeBatch(1L), "第 5 次消耗失败（批次预算用尽）");
        assertEquals(0, guard.remainingBatch(1L));
        assertEquals(4, guard.batchUsed(1L));

        // tick 窗口完全未被触碰
        assertEquals(-1, guard.budgetTotal(1L), "批次窗口不隐式开 tick 窗口");
        assertFalse(guard.hasTickWindow(1L));

        // 反向隔离：tick 窗口开启也不影响批次（tick 消耗不增加批次计数）
        guard.beginTick(1L, 5);
        assertTrue(guard.tryConsume(1L));
        assertEquals(4, guard.batchUsed(1L), "tick 消耗不计入批次（批次已用 4 次，不增不减）");
        assertEquals(4, guard.remaining(1L));
        assertEquals(0, guard.remainingBatch(1L), "批次剩余不受 tick 窗口影响");

        guard.endBatch(1L);
        assertFalse(guard.hasBatchWindow(1L), "endBatch 清理批次窗口");
        assertEquals(4, guard.remaining(1L), "tick 窗口不受 endBatch 影响");
    }

    /**
     * 重叠批次（引用计数）：beginBatch 累加预算，最后一个 endBatch 才真正关闭——
     * 预热批次未排空又触发补发批次时预算不丢、窗口不提前关闭。
     */
    @Test
    void batchReferenceCountingAccumulatesAndClosesOnLastEnd() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginBatch(1L, 3);
        guard.beginBatch(1L, 2); // 重叠批次：预算累加
        assertEquals(5, guard.remainingBatch(1L), "重叠批次预算累加（3+2）");
        assertTrue(guard.tryConsumeBatch(1L));
        assertEquals(4, guard.remainingBatch(1L));

        guard.endBatch(1L); // 第一个批次结束：窗口仍开（还有一个引用）
        assertTrue(guard.hasBatchWindow(1L), "引用未归零窗口不关闭");
        assertEquals(4, guard.remainingBatch(1L), "中间 endBatch 不重置预算");

        guard.endBatch(1L); // 最后一个批次结束：真正关闭
        assertFalse(guard.hasBatchWindow(1L), "引用归零窗口关闭");
        assertFalse(guard.tryConsumeBatch(1L), "关闭后不可消费");
    }

    /**
     * 未开批次窗口的项目不可消费批次预算（fail-safe）。
     */
    @Test
    void batchConsumeWithoutWindowFails() {
        BudgetGuard guard = new BudgetGuard();
        assertFalse(guard.tryConsumeBatch(1L), "无批次窗口不可消费");
        assertEquals(0, guard.batchUsed(1L));
        guard.endBatch(1L); // 空 endBatch 不抛异常
        assertFalse(guard.hasBatchWindow(1L));
    }

    // ==================== 反思预算独立命名空间（2026-09-29，P0-2 遗留根治②） ====================

    /**
     * 反思窗口与 tick 窗口完全隔离：beginReflection 消费走 tryConsumeReflection，不触碰 tick 窗口；
     * endReflection 关闭反思窗口（budgetTotal 仍反映 tick 窗口独立状态）——反思不再被 tick 决策饿死。
     */
    @Test
    void reflectionNamespaceIsolatedFromTickWindow() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginReflection(1L, 3);
        assertTrue(guard.hasReflectionWindow(1L), "反思窗口开启");
        assertTrue(guard.tryConsumeReflection(1L));
        assertTrue(guard.tryConsumeReflection(1L));
        assertEquals(1, guard.remainingReflection(1L));
        assertEquals(2, guard.reflectionUsed(1L));
        assertTrue(guard.tryConsumeReflection(1L));
        assertFalse(guard.tryConsumeReflection(1L), "反思预算用尽");
        assertEquals(0, guard.remainingReflection(1L));
        assertEquals(3, guard.reflectionUsed(1L));

        // tick 窗口完全未被触碰
        assertEquals(-1, guard.budgetTotal(1L), "反思窗口不隐式开 tick 窗口");
        assertFalse(guard.hasTickWindow(1L));

        // 反向隔离：tick 窗口开启也不影响反思（tick 消耗不增加反思计数、不消耗反思预算）
        guard.beginTick(1L, 1);
        assertTrue(guard.tryConsume(1L));
        assertFalse(guard.tryConsume(1L), "tick 预算 1 已用尽");
        assertEquals(3, guard.reflectionUsed(1L), "tick 消耗不计入反思");
        assertEquals(0, guard.remainingReflection(1L), "反思剩余不受 tick 窗口影响");

        guard.endReflection(1L);
        assertFalse(guard.hasReflectionWindow(1L), "endReflection 清理反思窗口");
        assertEquals(0, guard.remaining(1L), "tick 窗口不受 endReflection 影响");
    }

    /**
     * 反思窗口生命周期：beginReflection 覆盖重置（跨周期新预算）+ endReflection 清理内存态。
     */
    @Test
    void reflectionLifecycleResetsAndCleans() {
        BudgetGuard guard = new BudgetGuard();
        guard.beginReflection(1L, 2);
        assertTrue(guard.tryConsumeReflection(1L));
        assertEquals(1, guard.remainingReflection(1L));
        guard.beginReflection(1L, 5);
        assertEquals(5, guard.remainingReflection(1L), "新周期重置为 5");
        assertEquals(0, guard.reflectionUsed(1L), "新周期已用计数重置");

        guard.endReflection(1L);
        assertFalse(guard.hasReflectionWindow(1L));
        assertEquals(0, guard.remainingReflection(1L));
        assertFalse(guard.tryConsumeReflection(1L), "关闭后不可消费");
        guard.endReflection(1L); // 空 endReflection 不抛异常
        assertFalse(guard.hasReflectionWindow(1L));
    }

    /**
     * 未开反思窗口的项目不可消费反思预算（fail-safe）——手动路径由 ReflectionService 自开自关。
     */
    @Test
    void reflectionConsumeWithoutWindowFails() {
        BudgetGuard guard = new BudgetGuard();
        assertFalse(guard.tryConsumeReflection(1L), "无反思窗口不可消费");
        assertEquals(0, guard.reflectionUsed(1L));
        assertEquals(0, guard.remainingReflection(1L));
    }
}
