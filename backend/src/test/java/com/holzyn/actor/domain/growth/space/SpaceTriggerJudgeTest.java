package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 空间重算触发判定 + 关系变化量评估单元测试（N5 事件驱动重算，2026-09-26）。
 *
 * <p>测试纪律：字段注入 + {@code ReflectionTestUtils.setField}（构造器不变）；NanoJev 判定门
 * 全 mock（脚本化响应，零真实 AI）；同步判定体（{@code decideTrigger}/
 * {@code evaluateRelationDeltaSync}）反射直调保证确定性，异步端到端用 Mockito timeout 收敛。</p>
 *
 * <p>所属模块：domain/growth/space（N5）</p>
 */
class SpaceTriggerJudgeTest {

    private SpaceTriggerJudge judge;
    private NanoJevDecisionGate gate;
    private GrowthSpaceService growthSpaceService;
    private RelationDynamicsService relationDynamics;
    private GrowthProperties properties;

    @BeforeEach
    void setUp() {
        judge = new SpaceTriggerJudge();
        gate = mock(NanoJevDecisionGate.class);
        growthSpaceService = mock(GrowthSpaceService.class);
        relationDynamics = mock(RelationDynamicsService.class);
        properties = new GrowthProperties();
        ReflectionTestUtils.setField(judge, "nanoJevDecisionGate", gate);
        ReflectionTestUtils.setField(judge, "growthSpaceService", growthSpaceService);
        ReflectionTestUtils.setField(judge, "worldClockService", mock(WorldClockService.class));
        ReflectionTestUtils.setField(judge, "growthProperties", properties);
        ReflectionTestUtils.setField(judge, "relationDynamics", relationDynamics);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = SpaceTriggerJudge.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(judge, args);
    }

    @Test
    @DisplayName("N5 触发判定：场景未启用 → 程序化阈值兜底（变化信号存在即触发）")
    void decideTriggerProgrammaticFallback() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)).thenReturn(false);
        boolean worth = (boolean) invoke("decideTrigger",
                new Class<?>[]{Long.class, String.class, String.class, String.class, Long.class},
                1L, "npc:1", SpaceTriggerJudge.QID_EMOTION_SHIFT, "情绪变化", 1000L);
        assertTrue(worth);
    }

    @Test
    @DisplayName("N5 触发判定：NanoJev 采纳 false → 不触发（模型认为琐碎变化不值得重算）")
    void decideTriggerAdoptedFalseNoTrigger() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)).thenReturn(true);
        when(gate.booleanDecision(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_TRIGGER),
                any(), eq(SpaceTriggerJudge.QID_EMOTION_SHIFT))).thenReturn(
                new NanoJevDecisionGate.BooleanOutcome(false, true, NanoJevDecisionGate.DISP_ADOPTED,
                        0.2, 0.8, null, 5));
        boolean worth = (boolean) invoke("decideTrigger",
                new Class<?>[]{Long.class, String.class, String.class, String.class, Long.class},
                1L, "npc:1", SpaceTriggerJudge.QID_EMOTION_SHIFT, "情绪变化", 1000L);
        assertFalse(worth);
    }

    @Test
    @DisplayName("N5 触发判定：NanoJev 采纳 true → 触发")
    void decideTriggerAdoptedTrue() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)).thenReturn(true);
        when(gate.booleanDecision(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_TRIGGER),
                any(), eq(SpaceTriggerJudge.QID_EMOTION_SHIFT))).thenReturn(
                new NanoJevDecisionGate.BooleanOutcome(true, true, NanoJevDecisionGate.DISP_ADOPTED,
                        0.75, 0.75, null, 5));
        boolean worth = (boolean) invoke("decideTrigger",
                new Class<?>[]{Long.class, String.class, String.class, String.class, Long.class},
                1L, "npc:1", SpaceTriggerJudge.QID_EMOTION_SHIFT, "情绪变化", 1000L);
        assertTrue(worth);
    }

    @Test
    @DisplayName("N5 触发判定：升级/失败 → 程序化阈值兜底（fail-closed 零阻断）")
    void decideTriggerEscalatedFallsBack() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)).thenReturn(true);
        when(gate.booleanDecision(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_TRIGGER),
                any(), eq(SpaceTriggerJudge.QID_EMOTION_SHIFT))).thenReturn(
                new NanoJevDecisionGate.BooleanOutcome(false, false, NanoJevDecisionGate.DISP_ESCALATED,
                        null, 0.4, 1.2, 5));
        boolean worth = (boolean) invoke("decideTrigger",
                new Class<?>[]{Long.class, String.class, String.class, String.class, Long.class},
                1L, "npc:1", SpaceTriggerJudge.QID_EMOTION_SHIFT, "情绪变化", 1000L);
        assertTrue(worth);
    }

    @Test
    @DisplayName("N5 情绪漂移检测点端到端：标签变化 → NanoJev 采纳 true → EMOTION_SHIFT 空间重算")
    void onEmotionEvalEndToEnd() {
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)).thenReturn(true);
        when(gate.booleanDecision(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_TRIGGER),
                any(), eq(SpaceTriggerJudge.QID_EMOTION_SHIFT))).thenReturn(
                new NanoJevDecisionGate.BooleanOutcome(true, true, NanoJevDecisionGate.DISP_ADOPTED,
                        0.8, 0.8, null, 5));
        ActorNpcState st = mock(ActorNpcState.class);
        when(st.getDominantEmotion()).thenReturn("angry");
        judge.onEmotionEval(1L, "npc:1", "calm", st, 1000L);
        verify(growthSpaceService, timeout(3000)).triggerRegenerate(eq(1L), eq("npc:1"),
                eq(GrowthSpaceTrigger.EMOTION_SHIFT), eq(1000L));
        // 触发轨迹记录：判定来源标注 NanoJev（2026-09-26 抓修：此前误传取反标志）
        java.util.List<Map<String, Object>> trail = judge.recentTriggers(5);
        assertTrue(!trail.isEmpty(), "触发轨迹应有记录");
        Map<String, Object> rec = trail.get(0);
        assertEquals(GrowthSpaceTrigger.EMOTION_SHIFT.code(), rec.get("trigger"));
        assertEquals(Boolean.TRUE, rec.get("worth"));
        assertEquals(Boolean.TRUE, rec.get("nanoJevActive"), "NanoJev 场景活跃时应标注语义判定");
    }

    @Test
    @DisplayName("N5 情绪漂移检测点：标签未变 → 无信号，零判定")
    void onEmotionEvalNoSignal() throws InterruptedException {
        ActorNpcState st = mock(ActorNpcState.class);
        when(st.getDominantEmotion()).thenReturn("calm");
        judge.onEmotionEval(1L, "npc:1", "calm", st, 1000L);
        Thread.sleep(150);
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("N5 关系跨档检测点：状态未变 → 跳过（零判定）")
    void onRelationJumpSameStateSkips() throws InterruptedException {
        judge.onRelationJump(1L, "npc:1", "friend", "friend", null, 1000L);
        Thread.sleep(150);
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("N5 关系变化量：NanoJev 采纳（幅度 2=中 × 方向恶化）→ 好感 -3 平滑更新")
    void evaluateRelationDeltaSyncAdopted() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.RELATION_DELTA),
                any(), any(Map.class))).thenReturn(Map.of(
                SpaceTriggerJudge.QID_DELTA_MAGNITUDE,
                new NanoJevDecisionGate.ScoreOutcome(2, 1.8, true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                SpaceTriggerJudge.QID_DELTA_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome("worsen", true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0)));
        invoke("evaluateRelationDeltaSync",
                new Class<?>[]{Long.class, String.class, String.class, String.class, String.class,
                        int.class, Long.class},
                1L, "npc:1", "npc:2", "争执事件", "两人因田界起了争执", 1, 1000L);
        // DELTA_SCALE[2]=3 × 恶化 → -3
        verify(relationDynamics).applyDialogPairDelta(eq(1L), eq("npc:1"), eq("npc:2"),
                eq(-3), anyString());
    }

    @Test
    @DisplayName("N5 关系变化量：未启用 → 回退固定增量（原 event-delta）")
    void evaluateRelationDeltaSyncFallback() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)).thenReturn(false);
        invoke("evaluateRelationDeltaSync",
                new Class<?>[]{Long.class, String.class, String.class, String.class, String.class,
                        int.class, Long.class},
                1L, "npc:1", "npc:2", "互助事件", "张三帮李四修了屋顶", 1, 1000L);
        verify(relationDynamics).applyDialogPairDelta(eq(1L), eq("npc:1"), eq("npc:2"),
                eq(1), anyString());
    }

    @Test
    @DisplayName("N5 关系变化量：方向「无变化」→ 不动好感（零更新）")
    void evaluateRelationDeltaSyncUnchanged() throws Exception {
        when(gate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.RELATION_DELTA),
                any(), any(Map.class))).thenReturn(Map.of(
                SpaceTriggerJudge.QID_DELTA_MAGNITUDE,
                new NanoJevDecisionGate.ScoreOutcome(1, 0.8, true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                SpaceTriggerJudge.QID_DELTA_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome("unchanged", true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0)));
        invoke("evaluateRelationDeltaSync",
                new Class<?>[]{Long.class, String.class, String.class, String.class, String.class,
                        int.class, Long.class},
                1L, "npc:1", "npc:2", "日常事件", "两人在集市打了个照面", 1, 1000L);
        verify(relationDynamics, never()).applyDialogPairDelta(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("N5 调用风暴抓修：关系评估并发超限 → 不排队、直接回退固定增量（不丢关系更新）")
    void evaluateRelationDeltaAsyncRateLimitedFallsBackToFixedDelta() {
        // 占满并发闸（默认 4）：先连发 4 个异步评估并卡住（gate 挂起不返回）→ 第 5 个必须立即回退
        when(gate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)).thenReturn(true);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(4);
        when(gate.mixedDecisions(eq(1L), anyLong(), eq(NanoJevDecisionScenes.RELATION_DELTA),
                any(), any(Map.class))).thenAnswer(inv -> {
            hold.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Map.of(
                    SpaceTriggerJudge.QID_DELTA_MAGNITUDE,
                    new NanoJevDecisionGate.ScoreOutcome(2, 1.8, true,
                            NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                    SpaceTriggerJudge.QID_DELTA_DIRECTION,
                    new NanoJevDecisionGate.ChoiceOutcome("improve", true,
                            NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0));
        });
        // 4 个并发在途（各 300ms），第 5 个应同步回退固定增量，不等 NanoJev
        for (int i = 0; i < 4; i++) {
            judge.evaluateRelationDeltaAsync(1L, "npc:1", "npc:" + (10 + i), "共同事件", "正文", 1, 1000L);
        }
        // 等待 4 个在途任务把信号量占满（挂起在 mixedDecisions 内）
        try {
            hold.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        long t0 = System.currentTimeMillis();
        judge.evaluateRelationDeltaAsync(1L, "npc:1", "npc:99", "共同事件", "正文", 1, 1000L);
        long elapsed = System.currentTimeMillis() - t0;
        // 回退路径不阻塞提交方（异步提交立即返回）：远小于 NanoJev 在途时长（300ms），不排队等待
        assertTrue(elapsed < 200, "并发超限应直接回退固定增量（不排队），实际耗时=" + elapsed + "ms");
        // 回退异步执行（沿 evaluateRelationDeltaAsync 异步模型，避免事件总线事务内同步持锁）→ timeout 收敛
        verify(relationDynamics, timeout(3000)).applyDialogPairDelta(eq(1L), eq("npc:1"), eq("npc:99"),
                eq(1), anyString());
    }

    @Test
    @DisplayName("N5 调用风暴抓修：并发未超限 → 正常异步走 NanoJev 评估（不误伤）")
    void evaluateRelationDeltaAsyncBelowLimitGoesThrough() {
        when(gate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)).thenReturn(true);
        when(gate.mixedDecisions(eq(1L), anyLong(), eq(NanoJevDecisionScenes.RELATION_DELTA),
                any(), any(Map.class))).thenReturn(Map.of(
                SpaceTriggerJudge.QID_DELTA_MAGNITUDE,
                new NanoJevDecisionGate.ScoreOutcome(3, 2.8, true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0),
                SpaceTriggerJudge.QID_DELTA_DIRECTION,
                new NanoJevDecisionGate.ChoiceOutcome("improve", true,
                        NanoJevDecisionGate.DISP_ADOPTED, null, null, null, 0)));
        judge.evaluateRelationDeltaAsync(1L, "npc:1", "npc:2", "共同事件", "正文", 1, 1000L);
        // 异步执行：DELTA_SCALE[3]=6 × 改善 → 好感 +6
        verify(relationDynamics, timeout(3000)).applyDialogPairDelta(eq(1L), eq("npc:1"), eq("npc:2"),
                eq(6), anyString());
    }
}
