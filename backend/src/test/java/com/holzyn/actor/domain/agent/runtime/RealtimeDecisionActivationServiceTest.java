package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 实时决策激活闸门单元测试（世界演化与游戏时间系统 M6 用户需求，domain/agent/runtime，同包）。
 * <p>职责：验证——① 默认关（无引擎行/存量 NULL=关，后端启动不自动运行）；② 手动常开（setManual
 * 持久化 DB + 缓存生效，优先于页面自动态）；③ 页面级开/关（sandbox/evolution 幂等，markOpen 激活、
 * markClose 去激活）；④ isActive 判定 = 手动常开 ∪ 沙盘页打开 ∪ 演化会话进行，任一成立即激活；
 * ⑤ <b>M6 世界静止语义（2026-09-08）</b>：markOpen（打开沙盘/演化页）与 setManual(true) 激活时
 * 重置引擎回补基准（worldClockService.anchorEngineToNow）——世界从冻结进度接续、不补离线进度；
 * markClose / setManual(false) 关闭时不重置（世界静止）。</p>
 * <p>设计依据：2026-09-07 用户需求（后端启动不自动运行实时行为决策——默认关，仅打开沙盘/演化页
 * 或手动常开时世界才推进；进度由 last_game_second 持久化延续）+ 2026-09-08 用户需求（关闭=世界静止，
 * 重新打开=从冻结进度接续推进，不把关闭期间落下的进度补回来）。</p>
 */
class RealtimeDecisionActivationServiceTest {

    private ActorWorldEngineRepository engineRepository;
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;
    private RealtimeDecisionActivationService service;

    @BeforeEach
    void setUp() {
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        worldClockService = Mockito.mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        service = new RealtimeDecisionActivationService(engineRepository);
        // 注入世界时钟服务（M6 世界静止语义：激活时重置引擎回补基准）
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        // 默认无引擎行（懒创建前=关）
        when(engineRepository.findByProjectId(any())).thenReturn(Optional.empty());
    }

    @Test
    void defaultInactive() {
        assertFalse(service.isActive(1L), "默认关：后端启动不自动运行实时决策");
        assertFalse(service.isManualEnabled(1L), "无引擎行手动常开=关");
        assertFalse(service.isActive(null), "null 项目=关");
    }

    @Test
    void manualEnabledPersistsAndPrioritizes() {
        // setManual(true) → DB 保存 realtime_decision_enabled=1 + 缓存 → 激活
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        service.setManual(1L, true);
        verify(engineRepository).save(any());
        assertTrue(service.isManualEnabled(1L), "手动常开生效（缓存）");
        assertTrue(service.isActive(1L), "手动常开 → 激活");
        // 关闭沙盘页后仍激活（手动优先于页面自动态）
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markClose(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        assertTrue(service.isActive(1L), "页面全关后手动常开仍激活");
        // setManual(false) → 关闭
        service.setManual(1L, false);
        assertFalse(service.isActive(1L), "手动关闭后默认不激活");
    }

    @Test
    void manualEnabledLoadsFromDbLazily() {
        // O2（2026-09-16）：DB 常开标记仅在 auto-resume=true 配置下启动懒加载恢复
        service.setAutoResume(true);
        ActorWorldEngine engine = new ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setRealtimeDecisionEnabled(1);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engine));
        assertTrue(service.isManualEnabled(1L), "auto-resume=true：DB 持久化常开恢复（重启后保持）");
        assertTrue(service.isActive(1L));
    }

    @Test
    void manualDisabledLoadsFromDb() {
        service.setAutoResume(true);
        ActorWorldEngine engine = new ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setRealtimeDecisionEnabled(0);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engine));
        assertFalse(service.isManualEnabled(1L), "DB 显式 0=关");
    }

    // ==================== O2 世界运行时门控：启动全静止（2026-09-16 用户需求） ====================

    /**
     * O2 启动全静止：auto-resume=false（默认）时，即使 DB 存有上次「手动常开」标记，
     * 启动后所有世界一律静止——DB 标记不自动生效（不查询），仅当前会话 setManual 后生效。
     */
    @Test
    void startupAllStoppedEvenIfDbManualEnabled() {
        ActorWorldEngine engine = new ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setRealtimeDecisionEnabled(1);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engine));
        // 默认 auto-resume=false：不读 DB
        assertFalse(service.isManualEnabled(1L), "启动全静止：DB 常开标记不自动生效");
        assertFalse(service.isActive(1L), "启动全静止：isActive=关");
        verify(engineRepository, never()).findByProjectId(any());
    }

    /**
     * O2 会话内手动常开：auto-resume=false 下显式 setManual(true) → 立即生效并激活
     * （进入沙盘/手动开启是世界运行的唯一途径）。
     */
    @Test
    void manualTakesEffectAfterSetManualInSession() {
        ActorWorldEngine engine = new ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setRealtimeDecisionEnabled(1);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engine));
        assertFalse(service.isActive(1L), "启动全静止");
        service.setManual(1L, true);
        assertTrue(service.isManualEnabled(1L), "本会话 setManual 后生效");
        assertTrue(service.isActive(1L));
    }

    @Test
    void sandboxOpenActivatesAndCloseDeactivates() {
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertTrue(service.isActive(1L), "打开沙盘页 → 激活");
        assertTrue(service.isSandboxActive(1L));
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertFalse(service.isActive(1L), "离开沙盘页 → 去激活（世界暂停）");
        assertFalse(service.isSandboxActive(1L));
    }

    @Test
    void evolutionOpenActivatesAndCloseDeactivates() {
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        assertTrue(service.isActive(1L), "打开演化页 → 激活");
        assertTrue(service.isEvolutionActive(1L));
        service.markClose(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        assertFalse(service.isActive(1L));
    }

    @Test
    void anyReasonOpenActivates() {
        // 沙盘 + 演化任一打开即激活；仅关一方仍激活（另一原因保持）
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        assertTrue(service.isActive(1L));
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertTrue(service.isActive(1L), "演化仍打开 → 保持激活");
        service.markClose(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        assertFalse(service.isActive(1L));
    }

    @Test
    void markOpenCloseAreIdempotentAndUnknownReasonIgnored() {
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertFalse(service.isActive(1L), "重复开/关幂等");
        service.markOpen(1L, "未知原因");
        assertFalse(service.isActive(1L), "未知原因不激活");
        service.markOpen(1L, null);
        assertFalse(service.isActive(1L), "null 原因不激活");
        service.markOpen(null, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertFalse(service.isActive(null));
    }

    @Test
    void setManualPersistsExplicitly() {
        // setManual 应显式保存引擎行（含懒创建），只保存一次
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        service.setManual(1L, true);
        verify(engineRepository, times(1)).save(any());
        // 再次 setManual(false) 也应保存
        service.setManual(1L, false);
        verify(engineRepository, times(2)).save(any());
    }

    // ==================== M6 世界静止语义：激活重置引擎回补基准 ====================

    @Test
    void markOpenResetsEngineAnchor() {
        // 打开沙盘页 → 激活 + 重置引擎回补基准（世界从此刻接续，不补离线进度）
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        verify(worldClockService).anchorEngineToNow(1L);
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        verify(worldClockService, times(2)).anchorEngineToNow(1L);
    }

    @Test
    void markCloseDoesNotResetEngineAnchor() {
        // 关闭 = 世界静止：不重置基准（进度冻结在关闭前，重新打开时才接续）
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markClose(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        verifyNoInteractions(worldClockService);
    }

    @Test
    void setManualTrueResetsAnchorButFalseDoesNot() {
        // 手动常开 → 重置基准（从开启时刻起算推进）；关闭 → 不重置（静止）
        service.setManual(1L, true);
        verify(worldClockService).anchorEngineToNow(1L);
        service.setManual(1L, false);
        verify(worldClockService, times(1)).anchorEngineToNow(1L);
    }

    @Test
    void markOpenWithNullProjectSkipsAnchorReset() {
        service.markOpen(null, RealtimeDecisionActivationService.REASON_SANDBOX);
        verifyNoInteractions(worldClockService);
    }

    // ==================== U1 Unity 连接独占激活（2026-09-21，用户决策：连接期间独占） ====================

    /**
     * U1 独占激活：Unity 连接期间 isActive 只看 Unity 源——即使沙盘/演化/手动全关，
     * 连接即激活（世界由 Unity 时钟独占驱动）。
     */
    @Test
    void unityConnectedExclusiveActivation() {
        assertFalse(service.isActive(1L), "未连接默认关");
        service.markUnityConnected(1L);
        assertTrue(service.isUnityConnected(1L));
        assertTrue(service.isActive(1L), "Unity 连接 → 激活");
        // 其他源全关也不影响（独占）
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markClose(1L, RealtimeDecisionActivationService.REASON_EVOLUTION);
        service.setManual(1L, false);
        assertTrue(service.isActive(1L), "连接期间独占：其他源关闭不影响激活");
        // 断开 → 回退多源判定（全关 = 静止）
        service.markUnityDisconnected(1L);
        assertFalse(service.isUnityConnected(1L));
        assertFalse(service.isActive(1L), "断开且无其他源 → 静止");
    }

    /**
     * U1 断开回退多源：Unity 断开后，若沙盘页/演化/手动常开任一仍激活，世界继续推进
     * （时钟权威回退内部自走）。
     */
    @Test
    void unityDisconnectFallsBackToOtherSources() {
        service.markOpen(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        service.markUnityConnected(1L);
        assertTrue(service.isActive(1L));
        service.markUnityDisconnected(1L);
        assertTrue(service.isActive(1L), "断开后沙盘页仍打开 → 保持激活");
        service.markClose(1L, RealtimeDecisionActivationService.REASON_SANDBOX);
        assertFalse(service.isActive(1L), "沙盘也关 → 静止");
    }

    /**
     * U1 连接即接续：markUnityConnected 重置引擎回补基准（世界从冻结进度接续，不补离线），
     * 断开不重置（静止冻结）。
     */
    @Test
    void unityConnectResetsEngineAnchorDisconnectDoesNot() {
        service.markUnityConnected(1L);
        verify(worldClockService).anchorEngineToNow(1L);
        service.markUnityDisconnected(1L);
        verify(worldClockService, times(1)).anchorEngineToNow(1L);
    }

    @Test
    void unityMarkNullProjectIgnored() {
        service.markUnityConnected(null);
        service.markUnityDisconnected(null);
        verifyNoInteractions(worldClockService);
        assertFalse(service.isUnityConnected(null));
    }
}
