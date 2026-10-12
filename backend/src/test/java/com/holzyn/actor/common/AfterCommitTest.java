package com.holzyn.actor.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AfterCommit（事务提交后执行工具，2026-09-21 世界运行抓修）单元测试。
 *
 * <p>抓修背景：记忆写入把 embedding HTTP 调用放在事务里（{@code writeShortTerm} / {@code applyNoiseAndStore}），
 * 且常被更大的业务事务嵌套，导致一个 HTTP 往返期间 {@code actor_state} 行锁一直被按住——实测世界运行
 * 两个游戏小时边界出现 31 次 {@code Timeout trying to lock table "actor_state"}、4 次情绪写回彻底丢失。
 * 本工具把这类"派生数据计算/外部 HTTP"统一挪到提交之后。</p>
 *
 * <p>验证要点：①无事务时立即执行（单测/非事务调用语义不变）；②有事务同步时不立即执行、
 * 提交回调时执行；③动作抛异常不外抛（绝不影响提交结果）。</p>
 */
class AfterCommitTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** 无事务：立即执行（改造前的调用语义保持）。 */
    @Test
    void runsImmediatelyWithoutTransaction() {
        AtomicInteger n = new AtomicInteger();
        AfterCommit.runAfterCommit("测试动作", n::incrementAndGet);
        assertEquals(1, n.get(), "无事务应立即执行");
    }

    /** 有事务同步：注册后不立即执行，afterCommit 回调时才执行（行锁已释放）。 */
    @Test
    void deferredUntilAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        AtomicInteger n = new AtomicInteger();
        AfterCommit.runAfterCommit("测试动作", n::incrementAndGet);
        assertEquals(0, n.get(), "事务提交前不得执行（否则 HTTP 往返仍占着行锁）");

        // 模拟 Spring 提交：依次触发 afterCommit 回调
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertEquals(1, n.get(), "提交后执行且恰好一次");
    }

    /** 动作抛异常：只记日志不外抛（提交结果不受影响）。 */
    @Test
    void exceptionIsSwallowed() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AfterCommit.runAfterCommit("会失败的动作", () -> {
            throw new IllegalStateException("embedding down");
        });
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit(); // 不应抛出
        }
    }

    /** null 动作：空操作（调用方少写一层判空）。 */
    @Test
    void nullActionIsNoOp() {
        AfterCommit.runAfterCommit("空动作", null);
    }
}