package com.holzyn.actor.domain.debug.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiCallContext（B5 F19 决策链路立基扩展）单元测试（与实现同包）。
 * <p>职责：验证业务动作级 trace_id 贯穿（withTrace/beginTrace 块内全部 AI 调用共享 trace_id）、
 * 重试 attempt 维度（run(attempt)）、非 AI 环节记录（recordStep 的 step_type/content/耗时）、
 * 降级标记（markDegraded）。</p>
 */
class AiCallContextTest {

    /** 收集 sink 提交的 trace（模拟异步写库消费者） */
    private List<AiCallTrace> submitted;

    @BeforeEach
    void setUp() {
        submitted = new ArrayList<>();
        AiCallContext.setSink(submitted::add);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /**
     * withTrace 包裹：块内多次 run 创建的 AI 调用 trace 共享同一 trace_id（业务动作级贯穿，F19）。
     */
    @Test
    void withTraceSharesTraceIdAcrossRuns() {
        AiCallContext.withTrace("trace-abc", () -> {
            AiCallContext.run("action", 1L, () -> { markInvoked(); return null; });
            AiCallContext.run("action", 1L, () -> { markInvoked(); return null; });
            return null;
        });

        assertEquals(2, submitted.size(), "两次 run 各提交一条");
        assertEquals("trace-abc", submitted.get(0).getTraceId());
        assertEquals("trace-abc", submitted.get(1).getTraceId());
    }

    /**
     * 无 withTrace 包裹：run 创建的 trace trace_id 为 null（独立调用，不归链路）。
     */
    @Test
    void runWithoutTraceHasNullTraceId() {
        AiCallContext.run("dialog", 3L, () -> { markInvoked(); return null; });
        assertEquals(1, submitted.size());
        assertNull(submitted.get(0).getTraceId(), "无业务动作包裹时不归链路");
        assertEquals(1, submitted.get(0).getAttempt(), "缺省 attempt=1");
    }

    /**
     * 重试 attempt 维度（V2-4）：同 trace_id 下每次尝试各记一条、attempt 从 1 递增。
     */
    @Test
    void runAttemptDistinguishesRetries() {
        AiCallContext.withTrace("trace-retry", () -> {
            AiCallContext.run("action", 1L, 1, () -> { markInvoked(); return null; });
            AiCallContext.run("action", 1L, 2, () -> { markInvoked(); return null; });
            return null;
        });

        assertEquals(2, submitted.size());
        assertEquals(1, submitted.get(0).getAttempt(), "第一次尝试 attempt=1");
        assertEquals(2, submitted.get(1).getAttempt(), "第二次尝试 attempt=2");
        assertEquals("trace-retry", submitted.get(0).getTraceId());
        assertEquals("trace-retry", submitted.get(1).getTraceId(), "重试共享同一链路 trace_id");
    }

    /**
     * recordStep 非 AI 环节：写 step_type/content/耗时 明确的 trace 行，trace_id 继承当前链路。
     */
    @Test
    void recordStepWritesNonAiStep() {
        AiCallContext.withTrace("trace-step", () -> {
            AiCallContext.recordStep(100L, "action", "perception", "感知组装：可见事件 2 条", 12L);
            return null;
        });

        assertEquals(1, submitted.size());
        AiCallTrace step = submitted.get(0);
        assertEquals("perception", step.getStepType(), "非 AI 环节 step_type");
        assertEquals("感知组装：可见事件 2 条", step.getContent());
        assertEquals(12L, step.getDurationMs());
        assertEquals("trace-step", step.getTraceId());
        assertEquals(100L, step.getProjectId());
    }

    /**
     * recordStep 无 withTrace 包裹（无业务动作）：写独立 trace 行（trace_id=null），不丢失环节记录。
     */
    @Test
    void recordStepWithoutTraceStillRecorded() {
        AiCallContext.recordStep(5L, "world_event", "event", "机制草稿生成", 3L);
        assertEquals(1, submitted.size());
        assertNull(submitted.get(0).getTraceId());
        assertEquals("event", submitted.get(0).getStepType());
    }

    /**
     * markDegraded：run 块内 AI 失败后调用方做程序化兜底 → 该条 trace 落库为 degraded。
     */
    @Test
    void markDegradedMarksCurrentTrace() {
        AiCallContext.withTrace("trace-deg", () -> {
            AiCallContext.run("action", 1L, 1, () -> {
                markInvoked();
                AiCallContext.markDegraded();
                return null;
            });
            return null;
        });
        assertEquals(1, submitted.size());
        assertTrue(submitted.get(0).isDegraded(), "降级标记生效");
        assertEquals("trace-deg", submitted.get(0).getTraceId());
    }

    /**
     * beginTrace/endTrace 命令式配对：块内 run 继承 trace_id，结束后恢复（不影响外层）。
     */
    @Test
    void beginEndTracePairs() {
        AiCallContext.withTrace("outer", () -> {
            AiCallContext.run("dialog", 1L, () -> { markInvoked(); return null; });
            String inner = AiCallContext.beginTrace();
            assertNotNull(inner, "beginTrace 返回生成的 trace_id");
            AiCallContext.run("action", 2L, () -> { markInvoked(); return null; });
            AiCallContext.endTrace();
            AiCallContext.run("dialog", 1L, () -> { markInvoked(); return null; });
            return null;
        });

        assertEquals(3, submitted.size());
        assertEquals("outer", submitted.get(0).getTraceId(), "外层 run 用 outer");
        assertEquals(innerId(submitted.get(1)), submitted.get(1).getTraceId(), "begin 后 run 用 inner");
        assertEquals("outer", submitted.get(2).getTraceId(), "endTrace 后恢复外层");
    }

    /**
     * N 轨道 N1（2026-09-22）：NanoJev 参数快照写入 detail_json.request_params.params_snapshot
     * （「调度记录完整」验收项：states/questions/paths 三轴可回溯）。
     */
    @Test
    void nanojevParamsSnapshotInDetailJson() {
        AiCallContext.run("nanojev_sample", 1L, () -> {
            AiCallContext.current().setNanojevParams("states=1, questions=3, paths=5");
            markInvoked();
            return null;
        });
        assertEquals(1, submitted.size());
        String detail = submitted.get(0).toDetailJson(20000);
        assertTrue(detail.contains("\"params_snapshot\":\"states=1, questions=3, paths=5\""),
                "detail_json.request_params 必须含 params_snapshot：" + detail);
        // 非 NanoJev 调用不设置该字段 → detail_json 不出现 params_snapshot
        AiCallContext.run("dialog", 1L, () -> { markInvoked(); return null; });
        String plain = submitted.get(1).toDetailJson(20000);
        assertTrue(!plain.contains("params_snapshot"), "非 NanoJev 调用不得出现 params_snapshot");
    }

    /** 提取 inner trace_id（beginTrace 生成） */
    private String innerId(AiCallTrace t) {
        return t.getTraceId();
    }

    // ==================== 主动记忆检索段 holder（P1 ②-1，2026-10-03） ====================

    /** hold/read/clear：持有后可读，清除后不可读。 */
    @Test
    void proactiveSegmentHoldReadClear() {
        assertNull(AiCallContext.readProactiveSegment(), "初始无段");
        AiCallContext.holdProactiveSegment("【主动想起】段");
        assertEquals("【主动想起】段", AiCallContext.readProactiveSegment(), "持有后可读");
        AiCallContext.clearProactiveSegment();
        assertNull(AiCallContext.readProactiveSegment(), "清除后不可读");
    }

    /** 空白段不持有（模板零噪音）。 */
    @Test
    void proactiveSegmentBlankIgnored() {
        AiCallContext.holdProactiveSegment("   ");
        assertNull(AiCallContext.readProactiveSegment(), "空白段不持有");
        AiCallContext.holdProactiveSegment(null);
        assertNull(AiCallContext.readProactiveSegment(), "null 不持有");
        AiCallContext.clearProactiveSegment();
    }

    // ==================== MUSE 策略偏好段 holder（P1 ①-3，2026-10-02） ====================

    /** hold/read/clear：持有后可读，清除后不可读（防虚拟线程复用泄漏）。 */
    @Test
    void strategyPreferenceHoldReadClear() {
        assertNull(AiCallContext.readStrategyPreference(), "初始无段");
        AiCallContext.holdStrategyPreference("【策略偏好】面对「农事耕作」这类事你自认把握不大");
        assertEquals("【策略偏好】面对「农事耕作」这类事你自认把握不大",
                AiCallContext.readStrategyPreference(), "持有后可读");
        AiCallContext.clearStrategyPreference();
        assertNull(AiCallContext.readStrategyPreference(), "清除后不可读");
    }

    /** 空白段不持有（模板零噪音）。 */
    @Test
    void strategyPreferenceBlankIgnored() {
        AiCallContext.holdStrategyPreference("   ");
        assertNull(AiCallContext.readStrategyPreference(), "空白段不持有");
        AiCallContext.holdStrategyPreference(null);
        assertNull(AiCallContext.readStrategyPreference(), "null 不持有");
        AiCallContext.clearStrategyPreference();
    }

    /** 触发一次 Provider 调用的最小标记（trace 未 invoked 会跳过提交） */
    private static void markInvoked() {
        AiCallTrace t = AiCallContext.current();
        if (t != null) {
            t.markInvoked();
        }
    }
}
