package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GatewayConcurrencyLimiter 单元测试（2026-09-29_07 网关并发错峰观察项）。
 * <p>职责：验证并发限流语义——①默认关闭（max-concurrent=0）直通零开销；②启用后并发上限生效：
 * 超出上限排队等待空位、等待超时抛 AiCallException（业务侧按既有失败语义重试/兜底）；
 * ③等待中断抛「排队被中断」；④观测计数：inFlight/maxInFlight/totalWaits/waitTimeouts。</p>
 */
class GatewayConcurrencyLimiterTest {

    /** 默认（max-concurrent=0）：不限流直通，观测全零。 */
    @Test
    void defaultDisabledIsNoop() {
        GatewayConcurrencyLimiter limiter = new GatewayConcurrencyLimiter();
        limiter.init();
        assertFalse(limiter.isEnabled());
        limiter.acquire();
        limiter.release();
        GatewayConcurrencyLimiter.Stats s = limiter.stats();
        assertEquals(0, s.maxConcurrent());
        assertEquals(0, s.inFlight());
        assertEquals(0, s.maxInFlight());
        assertEquals(0, s.totalWaits());
        assertEquals(0, s.waitTimeouts());
    }

    /** 启用后并发上限生效：第 3 个调用等待超时失败；释放后重新可获空位。 */
    @Test
    void enabledCapsConcurrencyAndTimesOutWaiting() {
        GatewayConcurrencyLimiter limiter = new GatewayConcurrencyLimiter();
        limiter.setMaxConcurrent(2);
        limiter.setWaitTimeoutSeconds(1);
        limiter.init();
        assertTrue(limiter.isEnabled());
        limiter.acquire();
        limiter.acquire();
        // 上限 2 已满：第 3 个排队等待 1s → 超时抛 AiCallException
        AiCallException ex = assertThrows(AiCallException.class, limiter::acquire);
        assertTrue(ex.getMessage().contains("排队超时"), "消息应含排队超时提示：" + ex.getMessage());
        // 超时失败不占在途（acquire 失败未持空位）
        assertEquals(2, limiter.stats().inFlight());
        assertEquals(1, limiter.stats().waitTimeouts());
        // 释放 2 个空位后重新可获空位
        limiter.release();
        limiter.release();
        limiter.acquire();
        assertEquals(1, limiter.stats().inFlight());
        assertEquals(2, limiter.stats().maxInFlight());
        limiter.release();
    }

    /** 排队等待语义：并发超限时后续调用排队（totalWaits 计数），空位释放后获空位，等待不超时。 */
    @Test
    void statsTrackWaitsAndMaxInFlight() throws Exception {
        GatewayConcurrencyLimiter limiter = new GatewayConcurrencyLimiter();
        limiter.setMaxConcurrent(1);
        limiter.setWaitTimeoutSeconds(5);
        limiter.init();
        limiter.acquire();
        CompletableFuture<Void> second = CompletableFuture.runAsync(limiter::acquire);
        Thread.sleep(300);
        GatewayConcurrencyLimiter.Stats s = limiter.stats();
        assertEquals(1, s.inFlight());
        assertEquals(1, s.maxInFlight());
        assertEquals(1, s.totalWaits());
        assertEquals(0, s.waitTimeouts());
        // 空位释放 → 等待线程获空位
        limiter.release();
        second.get(5, TimeUnit.SECONDS);
        assertEquals(1, limiter.stats().inFlight(), "等待线程获空位后在途=1");
        limiter.release();
        assertEquals(0, limiter.stats().inFlight(), "全部结束后在途归零");
    }

    /** 等待中断：排队期间线程被中断 → 抛「排队被中断」AiCallException。 */
    @Test
    void interruptedWaitThrowsQueueInterrupted() throws Exception {
        GatewayConcurrencyLimiter limiter = new GatewayConcurrencyLimiter();
        limiter.setMaxConcurrent(1);
        limiter.setWaitTimeoutSeconds(30);
        limiter.init();
        limiter.acquire();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (Throwable e) {
                err.set(e);
            }
        });
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(5000);
        assertInstanceOf(AiCallException.class, err.get());
        assertTrue(err.get().getMessage().contains("排队被中断"), "消息应含排队被中断：" + err.get().getMessage());
        assertEquals(1, limiter.stats().waitTimeouts(), "中断按等待超时计数（失败语义一致）");
        limiter.release();
    }
}
