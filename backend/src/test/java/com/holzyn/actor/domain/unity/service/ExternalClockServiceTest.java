package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部时钟服务单元测试（U 轨道 U1，domain/unity/service）——时钟权威切换核心。
 * <p>职责：验证——上报驱动推进（引擎从旧位置模拟到新位置）、防回退（只进不退）、
 * 幂等（同位置不重复模拟）、步长封顶、速率透传、未启用/未连接/参数无效拒绝。</p>
 */
class ExternalClockServiceTest {

    private UnityProperties props;
    private UnitySessionRegistry sessionRegistry;
    private WorldClockService worldClockService;
    private WorldEngine worldEngine;
    private ExternalClockService service;

    @BeforeEach
    void setUp() {
        props = new UnityProperties();
        sessionRegistry = Mockito.mock(UnitySessionRegistry.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldEngine = Mockito.mock(WorldEngine.class);
        service = new ExternalClockService(props, sessionRegistry, worldClockService, worldEngine);
    }

    private ActorWorldClock clock(long lastGameSecond) {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setProjectId(37L);
        clock.setLastGameSecond(lastGameSecond);
        clock.setRate(24);
        return clock;
    }

    private void connect() {
        when(sessionRegistry.isConnected(37L)).thenReturn(true);
        when(worldClockService.requireClock(37L)).thenReturn(clock(1000L));
    }

    @Test
    void disabled() {
        props.setEnabled(false);
        var r = service.applyClockReportSync(37L, 2000L, null);
        assertEquals("disabled", r.status());
        verify(worldEngine, never()).advanceProjectExternal(anyLong(), anyLong());
    }

    @Test
    void invalidParams() {
        when(sessionRegistry.isConnected(37L)).thenReturn(true);
        var r = service.applyClockReportSync(37L, null, null);
        assertEquals("invalid", r.status());
        var r2 = service.applyClockReportSync(null, 100L, null);
        assertEquals("invalid", r2.status());
    }

    @Test
    void externalInactive() {
        when(sessionRegistry.isConnected(37L)).thenReturn(false);
        var r = service.applyClockReportSync(37L, 2000L, null);
        assertEquals("external_inactive", r.status());
        verify(worldEngine, never()).advanceProjectExternal(anyLong(), anyLong());
    }

    @Test
    void rejectsRegression() {
        connect();
        var r = service.applyClockReportSync(37L, 500L, null);
        assertEquals("rejected", r.status());
        assertEquals(1000L, r.fromSecond(), "当前位置");
        assertEquals(500L, r.toSecond(), "上报目标");
        verify(worldEngine, never()).advanceProjectExternal(anyLong(), anyLong());
    }

    @Test
    void idleOnSamePosition() {
        connect();
        var r = service.applyClockReportSync(37L, 1000L, null);
        assertEquals("idle", r.status());
        verify(worldEngine, never()).advanceProjectExternal(anyLong(), anyLong());
    }

    @Test
    void appliesAndAdvances() {
        connect();
        when(worldEngine.advanceProjectExternal(eq(37L), eq(3600L)))
                .thenReturn(Map.of("status", "advanced", "gameHour", 1L));
        var r = service.applyClockReportSync(37L, 3600L, null);
        assertEquals("advanced", r.status());
        assertTrue(r.advanced());
        assertEquals(1000L, r.fromSecond());
        assertEquals(3600L, r.toSecond());
        verify(worldEngine).advanceProjectExternal(37L, 3600L);
    }

    @Test
    void capsHugeStep() {
        connect();
        long maxStep = props.getClock().getMaxCatchUpGameSeconds(); // 默认 86400
        long huge = 1000L + maxStep * 2;
        when(worldEngine.advanceProjectExternal(eq(37L), eq(1000L + maxStep)))
                .thenReturn(Map.of("status", "advanced"));
        var r = service.applyClockReportSync(37L, huge, null);
        assertEquals("advanced", r.status(), "封顶后仍推进到 当前+上限");
        assertEquals(1000L + maxStep, r.toSecond(), "封顶目标 = 当前 + 单次上限");
        verify(worldEngine).advanceProjectExternal(37L, 1000L + maxStep);
    }

    @Test
    void ratePassthrough() {
        connect();
        ActorWorldClock clock = clock(1000L);
        when(worldClockService.requireClock(37L)).thenReturn(clock);
        when(worldEngine.advanceProjectExternal(anyLong(), anyLong()))
                .thenReturn(Map.of("status", "advanced"));
        service.applyClockReportSync(37L, 2000L, 60);
        assertEquals(60, clock.getRate(), "Unity 上报倍率透传落库");
    }

    @Test
    void engineResultPassthrough() {
        connect();
        when(worldEngine.advanceProjectExternal(anyLong(), anyLong()))
                .thenReturn(Map.of("status", "paused"));
        var r = service.applyClockReportSync(37L, 2000L, null);
        assertEquals("paused", r.status(), "引擎暂停语义透传");
        assertFalse(r.advanced());
    }

    @Test
    void externalClockActiveFlag() {
        assertFalse(service.isExternalClockActive(37L), "未连接=外部时钟未激活");
        when(sessionRegistry.isConnected(37L)).thenReturn(true);
        assertTrue(service.isExternalClockActive(37L), "连接=外部时钟激活");
        props.setEnabled(false);
        assertFalse(service.isExternalClockActive(37L), "总开关关闭=不激活");
    }
}
