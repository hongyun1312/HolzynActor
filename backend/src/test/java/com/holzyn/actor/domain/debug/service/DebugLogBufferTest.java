package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.debug.service.DebugLogBuffer.DebugLogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DebugLogBuffer 环形缓冲单元测试（2026-08-20 调试前端实时日志）。
 * <p>职责：验证——环形覆盖（满则丢最旧）、recent(N)/all() 顺序、容量下限保护。</p>
 * <p>说明：直接构造 {@code new DebugLogBuffer(capacity, enabled)}（包内测试），不依赖 Spring。</p>
 */
class DebugLogBufferTest {

    /**
     * 环形覆盖：超过容量时最旧的被丢弃，最新 N 条保留。
     */
    @Test
    void ringOverwritesOldestWhenFull() {
        DebugLogBuffer buffer = new DebugLogBuffer(3, true);
        buffer.append("INFO", "t1", "logger.a", "m1");
        buffer.append("INFO", "t1", "logger.a", "m2");
        buffer.append("INFO", "t1", "logger.a", "m3");
        buffer.append("WARN", "t2", "logger.b", "m4");
        buffer.append("ERROR", "t3", "logger.c", "m5");

        List<DebugLogEntry> all = buffer.all();
        assertEquals(3, all.size(), "容量 3 时超过后应只保留最新 3 条");
        assertEquals("m3", all.get(0).message());
        assertEquals("m4", all.get(1).message());
        assertEquals("m5", all.get(2).message());
        // 序号全局递增，用于 SSE 顺序/去重
        assertTrue(all.get(0).seq() < all.get(1).seq());
        assertTrue(all.get(1).seq() < all.get(2).seq());
    }

    /**
     * recent(N)：返回最近 N 条（时间升序）；不足 N 返回全部。
     */
    @Test
    void recentReturnsLastNInOrder() {
        DebugLogBuffer buffer = new DebugLogBuffer(10, true);
        for (int i = 1; i <= 6; i++) {
            buffer.append("INFO", "t", "l", "m" + i);
        }
        List<DebugLogEntry> recent = buffer.recent(3);
        assertEquals(3, recent.size());
        assertEquals("m4", recent.get(0).message());
        assertEquals("m6", recent.get(2).message());

        assertEquals(6, buffer.recent(100).size(), "不足 N 返回全部");
        assertEquals(0, new DebugLogBuffer(10, true).recent(5).size());
    }

    /**
     * 全部日志按时间升序（追加顺序）。
     */
    @Test
    void allPreservesAppendOrder() {
        DebugLogBuffer buffer = new DebugLogBuffer(100, true);
        buffer.append("DEBUG", "t1", "l", "a");
        buffer.append("INFO", "t2", "l", "b");
        List<DebugLogEntry> all = buffer.all();
        assertEquals(2, all.size());
        assertEquals("DEBUG", all.get(0).level());
        assertEquals("INFO", all.get(1).level());
        assertEquals("t2", all.get(1).thread());
        assertEquals("l", all.get(1).logger());
    }

    /**
     * 容量下限保护：容量至少为 1（极小值仅测试用，生产默认 2000）。
     */
    @Test
    void capacityHasMinimumGuard() {
        DebugLogBuffer buffer = new DebugLogBuffer(1, true);
        for (int i = 1; i <= 5; i++) {
            buffer.append("INFO", "t", "l", "m" + i);
        }
        assertEquals(1, buffer.all().size(), "容量 1 时仅保留最新 1 条");
        assertEquals("m5", buffer.all().get(0).message());
    }

    /**
     * 监听器：新日志被通知（SSE 广播依赖）。
     */
    @Test
    void listenersReceiveNewEntries() {
        DebugLogBuffer buffer = new DebugLogBuffer(10, true);
        boolean[] hit = {false};
        String[] got = {null};
        buffer.addListener(e -> {
            hit[0] = true;
            got[0] = e.message();
        });
        buffer.append("WARN", "t", "l", "新日志");
        assertTrue(hit[0]);
        assertEquals("新日志", got[0]);
        assertFalse(buffer.all().isEmpty());
    }
}
