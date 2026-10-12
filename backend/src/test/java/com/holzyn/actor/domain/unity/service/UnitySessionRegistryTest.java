package com.holzyn.actor.domain.unity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unity 会话注册表单元测试（U 轨道 U1，domain/unity/service）。
 * <p>职责：验证——连接注册/注销/项目隔离/多连接、seq 幂等对账（单调递增/重复拒绝/null 放行）、
 * 活性 touch/失联扫描、全部会话快照与统计。</p>
 */
class UnitySessionRegistryTest {

    private UnitySessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new UnitySessionRegistry();
    }

    private WebSocketSession session(long id) {
        WebSocketSession s = Mockito.mock(WebSocketSession.class);
        Mockito.when(s.getId()).thenReturn("sess-" + id);
        Mockito.when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void registerAndUnregister() {
        WebSocketSession a = session(1);
        assertFalse(registry.isConnected(37L), "未连接");
        registry.register(37L, a);
        assertTrue(registry.isConnected(37L));
        assertEquals(1, registry.sessionCount(37L));
        assertEquals(1, registry.connectedProjectCount());
        assertEquals(1, registry.totalSessionCount());
        registry.unregister(37L, a);
        assertFalse(registry.isConnected(37L), "注销后断开");
        assertEquals(0, registry.totalSessionCount());
    }

    @Test
    void projectIsolation() {
        WebSocketSession a = session(1);
        WebSocketSession b = session(2);
        registry.register(37L, a);
        registry.register(99L, b);
        assertTrue(registry.isConnected(37L));
        assertTrue(registry.isConnected(99L));
        assertEquals(1, registry.sessionCount(37L));
        assertEquals(1, registry.sessionCount(99L));
        assertEquals(2, registry.totalSessionCount());
        registry.unregister(37L, a);
        assertFalse(registry.isConnected(37L));
        assertTrue(registry.isConnected(99L), "项目隔离：37 断开不影响 99");
    }

    @Test
    void multipleSessionsSameProject() {
        WebSocketSession a = session(1);
        WebSocketSession b = session(2);
        registry.register(37L, a);
        registry.register(37L, b);
        assertEquals(2, registry.sessionCount(37L));
        assertEquals(2, registry.sessionsOf(37L).size());
        registry.unregister(37L, a);
        assertTrue(registry.isConnected(37L), "仍有一个连接");
        registry.unregister(37L, b);
        assertFalse(registry.isConnected(37L), "最后一个连接注销后断开");
    }

    @Test
    void seqMonotonicIdempotency() {
        assertTrue(registry.acceptSeq(37L, 1L), "首个 seq 接受");
        assertTrue(registry.acceptSeq(37L, 2L), "递增接受");
        assertFalse(registry.acceptSeq(37L, 2L), "重复 seq 拒绝");
        assertFalse(registry.acceptSeq(37L, 1L), "回退 seq 拒绝");
        assertTrue(registry.acceptSeq(37L, 5L), "跳号接受（断点续传容忍）");
        assertEquals(5L, registry.lastSeqOf(37L));
        // null seq 放行（部分消息类型不要求序号）
        assertTrue(registry.acceptSeq(37L, null));
    }

    @Test
    void seqIsolationByProject() {
        assertTrue(registry.acceptSeq(37L, 10L));
        assertTrue(registry.acceptSeq(99L, 1L), "不同项目独立计数");
        assertEquals(10L, registry.lastSeqOf(37L));
        assertEquals(1L, registry.lastSeqOf(99L));
    }

    @Test
    void activityAndStaleSweep() throws InterruptedException {
        WebSocketSession a = session(1);
        WebSocketSession b = session(2);
        registry.register(37L, a);
        registry.register(37L, b);
        // ⚠️ 时序确定性：staleSessions 判定为严格 now-lastActivity>timeout，退化阈值 0 会踩
        //    同毫秒竞态（b 未 touch 要求注册时刻<扫描时刻，a 已 touch 要求 touch 与扫描同毫秒）
        //    → sleep 拉开「注册时刻」与「touch 时刻」（b 至少早 10ms），再用 5ms 阈值：
        //    b（≥10ms 未活跃）必然失联；a（touch 后微秒级）必然不失联
        Thread.sleep(10);
        registry.touch(a);
        long before = registry.lastActivityOf(a.getId());
        assertTrue(before > 0, "touch 记录活跃时刻");
        // 未 touch 的 b（活跃时刻=注册时刻，早于 a 至少 10ms）应被判定失联；a（刚 touch）不失联
        List<WebSocketSession> stale = registry.staleSessions(5L);
        assertTrue(stale.stream().anyMatch(s -> s.getId().equals(b.getId())), "未 touch 会话失联");
        assertTrue(stale.stream().noneMatch(s -> s.getId().equals(a.getId())), "已 touch 会话不失联");
    }

    @Test
    void allSessionsSnapshot() {
        WebSocketSession a = session(1);
        WebSocketSession b = session(2);
        registry.register(37L, a);
        registry.register(99L, b);
        List<WebSocketSession> all = registry.allSessions();
        assertEquals(2, all.size());
        assertEquals(2, registry.totalSessionCount());
    }
}
