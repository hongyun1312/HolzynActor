package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TickBarrier（tick 屏障，O6 G1a 世界运行测试·阶段⑤增补）单元测试（与实现同包）。
 *
 * <p>验证用户决策「每 tick 等所有请求完成后才推进新 tick」的四个语义要素：</p>
 * <ol>
 *   <li><b>等待排空</b>——awaitQuiescence 阻塞到在途任务 complete 为止，且把真实等待时长带回去；</li>
 *   <li><b>刺激排队</b>——屏障过后（本 tick 在途已排空）新到的刺激改为排队，等下一 tick 派发；</li>
 *   <li><b>超时放行</b>——等待上限到点放行并计数，世界永不被慢依赖冻结；连续超时自愈清零；</li>
 *   <li><b>跨 tick 结转 + 无活动 tick 不排队</b>——beginTick 不清零在途计数；引擎停/暂停（epoch=0）
 *       时刺激照旧即时执行（否则世界不跑时 NPC 永远不反应）。</li>
 * </ol>
 */
class TickBarrierTest {

    private TickBarrier barrier;

    @BeforeEach
    void setUp() {
        // 直接 new（@Value 字段在单元测试中不注入，但字段初始化已给出生产默认值：
        // enabled=true / maxWaitMs=30000 / pollMs=20 / maxCatchUpTicks=24 / parallelDispatch=4）
        barrier = new TickBarrier();
    }

    /** 属性回填（等价 application.yml 配置）。 */
    private void prop(String name, Object value) {
        ReflectionTestUtils.setField(barrier, name, value);
    }

    // ==================== 基本登记 / 排空 ====================

    /**
     * 无活动 tick 时登记为空操作（引擎停/暂停时不应凭空产生在途计数）。
     */
    @Test
    void registerWithoutActiveTickIsNoOp() {
        barrier.register(1L);
        assertEquals(0, barrier.pending(1L), "无活动 tick=无屏障状态，登记为空操作");
        assertFalse(barrier.shouldDefer(1L), "无活动 tick 时刺激不排队（引擎不跑时 NPC 仍要能反应）");
    }

    /**
     * 等待在途任务排空：awaitQuiescence 必须在 complete 之后才返回，并带回真实等待时长。
     */
    @Test
    void awaitQuiescenceWaitsUntilComplete() throws Exception {
        barrier.beginTick(1L);
        barrier.register(1L);
        barrier.register(1L);
        assertEquals(2, barrier.pending(1L), "两个在途任务");

        CountDownLatch done = new CountDownLatch(1);
        List<TickBarrier.AwaitResult> results = new ArrayList<>();
        Thread waiter = new Thread(() -> {
            results.add(barrier.awaitQuiescence(1L));
            done.countDown();
        }, "barrier-waiter");
        waiter.setDaemon(true);
        waiter.start();

        // 等待期间：waiter 不应返回（在途未排空）
        assertFalse(done.await(150, TimeUnit.MILLISECONDS), "在途任务未完成时不得放行");
        barrier.complete(1L);
        barrier.complete(1L);
        assertTrue(done.await(5, TimeUnit.SECONDS), "在途排空后应立即放行");
        assertEquals(0, results.get(0).remaining(), "无剩余在途");
        assertFalse(results.get(0).timedOut(), "正常排空不算超时");
        assertEquals(0, barrier.pending(1L));
    }

    /**
     * 计数不会为负（complete 多于 register 时不出现负数，否则 shouldDefer/等待判定会错乱）。
     */
    @Test
    void completeNeverGoesNegative() {
        barrier.beginTick(1L);
        barrier.complete(1L);
        barrier.complete(1L);
        assertEquals(0, barrier.pending(1L), "多余 complete 不会把计数打成负数");
    }

    /**
     * 跨 tick 结转：beginTick 不清零在途计数——上一 tick 结束后仍在跑的写回要计入本次屏障，
     * 这样「上一 tick 的工作全部结束」才是新 tick 开始的前提。
     */
    @Test
    void beginTickCarriesOverPendingFromPreviousTick() {
        barrier.beginTick(1L);
        barrier.register(1L);
        assertEquals(2L, barrier.beginTick(1L), "tick 序号递增");
        assertEquals(1, barrier.pending(1L), "跨 tick 结转：新 tick 仍能看到上一 tick 的在途任务");
    }

    // ==================== 刺激排队 ====================

    /**
     * 屏障过后（本 tick 在途已排空）新到的刺激应排队，而不是插进本 tick 的尾巴——
     * 换来严格因果顺序：反应落在「他感知到的时刻」之后、「下一个时刻」之前。
     */
    @Test
    void shouldDeferOnlyAfterBarrierPassed() {
        barrier.beginTick(1L);
        assertFalse(barrier.shouldDefer(1L), "屏障未过：刺激立即执行（属于本 tick）");
        barrier.awaitQuiescence(1L);
        assertTrue(barrier.shouldDefer(1L), "屏障已过：刺激排队到下一 tick 的派发相");
        barrier.endTick(1L);
        assertFalse(barrier.shouldDefer(1L), "tick 结束（引擎停）后恢复即时执行");
    }

    /**
     * 排队刺激按到达顺序取出（同一 tick 内的因果顺序稳定），且取出后队列清空。
     */
    @Test
    void deferAndDrainPreservesArrivalOrder() {
        barrier.beginTick(1L);
        List<Integer> fired = new ArrayList<>();
        barrier.defer(1L, () -> fired.add(1));
        barrier.defer(1L, () -> fired.add(2));
        barrier.defer(1L, () -> fired.add(3));

        List<Runnable> drained = barrier.drainDeferred(1L);
        assertEquals(3, drained.size(), "取出三个排队刺激");
        drained.forEach(Runnable::run);
        assertEquals(List.of(1, 2, 3), fired, "按到达顺序派发");
        assertEquals(0, barrier.drainDeferred(1L).size(), "取出后队列清空");
        assertEquals(3L, barrier.stats().get("deferred"), "排队计数累计");
    }

    /** 无活动 tick 时排队为空操作（不吞掉调用方，也不产生无主队列）。 */
    @Test
    void deferWithoutActiveTickIsNoOp() {
        barrier.defer(1L, () -> {
            throw new AssertionError("不应被执行");
        });
        assertEquals(0, barrier.drainDeferred(1L).size(), "无屏障状态=无队列");
    }

    // ==================== 超时放行 / 自愈 ====================

    /**
     * 等待超时 → 放行并如实上报（remaining&gt;0、timedOut=true）：世界永不被慢依赖冻结。
     */
    @Test
    void awaitQuiescenceTimesOutAndReportsRemaining() {
        prop("maxWaitMs", 1L);
        barrier.beginTick(1L);
        barrier.register(1L);

        TickBarrier.AwaitResult r = barrier.awaitQuiescence(1L);
        assertTrue(r.timedOut(), "超时应放行");
        assertEquals(1, r.remaining(), "如实上报剩余在途数");
        assertEquals(1, barrier.pending(1L), "超时不强改计数（写回仍在跑）");
        assertTrue(barrier.shouldDefer(1L), "超时放行后同样进入排队语义（新刺激等下一 tick）");
    }

    /**
     * 连续超时自愈：连续 N 次超时后清零在途计数——防某个漏掉 complete 的 bug 让每 tick 都白等满
     * max-wait-ms（观测数据反噬业务）。
     */
    @Test
    void consecutiveTimeoutsSelfHealPendingCount() {
        prop("maxWaitMs", 1L);
        prop("selfHealAfterTimeouts", 2);
        barrier.beginTick(1L);
        barrier.register(1L); // 故意漏 complete

        assertTrue(barrier.awaitQuiescence(1L).timedOut(), "第 1 次超时");
        assertEquals(1, barrier.pending(1L), "未达阈值不清零");
        assertTrue(barrier.awaitQuiescence(1L).timedOut(), "第 2 次超时");
        assertEquals(0, barrier.pending(1L), "达阈值自愈清零");
        assertFalse(barrier.awaitQuiescence(1L).timedOut(), "自愈后不再白等");
        assertEquals(0, barrier.pending(1L));
    }

    /** 超时后正常完成：连续超时计数复位（不误触发自愈）。 */
    @Test
    void successfulWaitResetsConsecutiveTimeoutCounter() {
        prop("maxWaitMs", 1L);
        prop("selfHealAfterTimeouts", 3);
        barrier.beginTick(1L);
        barrier.register(1L);
        assertTrue(barrier.awaitQuiescence(1L).timedOut(), "第 1 次超时");
        barrier.complete(1L);
        assertFalse(barrier.awaitQuiescence(1L).timedOut(), "排空后正常返回");
        // 再次漏 complete 时连续计数从 0 重新累计：两次超时不足以自愈（阈值 3）
        barrier.register(1L);
        barrier.awaitQuiescence(1L);
        barrier.awaitQuiescence(1L);
        assertEquals(1, barrier.pending(1L), "连续计数已复位，两次超时未达阈值 3");
    }

    // ==================== 开关 / 可观测 ====================

    /** 关闭开关：完全退回改造前的自由并发模型（beginTick 空操作、不排队、不等待）。 */
    @Test
    void disabledBarrierIsNoOp() {
        prop("enabled", false);
        assertEquals(0L, barrier.beginTick(1L), "关闭时无 tick 序号");
        barrier.register(1L);
        assertEquals(0, barrier.pending(1L));
        assertFalse(barrier.shouldDefer(1L), "关闭时不排队（刺激照旧即时执行）");
        TickBarrier.AwaitResult r = barrier.awaitQuiescence(1L);
        assertEquals(0L, r.waitedMs(), "关闭时不等待");
        assertFalse(r.timedOut());
    }

    /** 配置下界：并行度/封顶 tick/等待上限都不允许出现 0 或负数（防配置写错把世界卡死）。 */
    @Test
    void configFloorsArePositive() {
        prop("parallelDispatch", 0);
        prop("maxCatchUpTicks", 0);
        prop("maxWaitMs", -1L);
        assertEquals(1, barrier.parallelDispatch(), "并行度至少 1（=串行）");
        assertEquals(1, barrier.maxCatchUpTicks(), "封顶 tick 至少 1");
        assertEquals(0L, barrier.maxWaitMs(), "等待上限不为负");
    }

    /**
     * 累计统计可观测（世界运行测试据此核对屏障效果）：等待次数/时长/超时/排队数。
     */
    @Test
    void statsAccumulate() {
        prop("maxWaitMs", 1L);
        prop("selfHealAfterTimeouts", 0); // 关自愈，保持 pending 以便观察超时
        barrier.beginTick(1L);
        barrier.defer(1L, () -> {
        });
        barrier.register(1L);
        barrier.awaitQuiescence(1L);
        barrier.awaitQuiescence(1L);

        Map<String, Object> stats = barrier.stats();
        assertEquals(2L, stats.get("waits"), "等待次数");
        assertEquals(2L, stats.get("timeouts"), "超时次数");
        assertEquals(1L, stats.get("deferred"), "排队刺激数");
        assertTrue(((Number) stats.get("waitedMs")).longValue() >= 0L, "等待总时长");
    }

    /**
     * 多项目隔离：屏障状态与在途计数按项目独立（一个项目卡住不影响另一个项目推进）。
     */
    @Test
    void barrierStateIsPerProject() {
        barrier.beginTick(1L);
        barrier.beginTick(2L);
        barrier.register(1L);
        assertEquals(1, barrier.pending(1L), "项目 1 有在途");
        assertEquals(0, barrier.pending(2L), "项目 2 不受影响");
        assertFalse(barrier.shouldDefer(2L), "项目 2 屏障未过");
        barrier.awaitQuiescence(2L);
        assertTrue(barrier.shouldDefer(2L));
        assertFalse(barrier.shouldDefer(1L), "项目 1 仍在本 tick 内");
    }

    /** 空项目 ID 全路径空操作（防 NPE）。 */
    @Test
    void nullProjectIsNoOp() {
        assertEquals(0L, barrier.beginTick(null));
        barrier.register(null);
        barrier.complete(null);
        barrier.defer(null, () -> {
        });
        assertFalse(barrier.shouldDefer(null));
        assertEquals(0, barrier.pending(null));
        assertTrue(barrier.drainDeferred(null).isEmpty());
        TickBarrier.AwaitResult r = barrier.awaitQuiescence(null);
        assertEquals(0L, r.waitedMs());
        assertFalse(r.timedOut());
    }
}