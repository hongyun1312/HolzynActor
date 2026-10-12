package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.usage.entity.TestUsageLog;
import com.holzyn.actor.domain.usage.repository.TestUsageLogRepository;
import com.holzyn.actor.domain.usage.service.TestUsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 长跑测试用量明细（表 {@code actor_test_usage_log}）单元测试 —— 2026-09-27 第 3 轮长跑新增。
 *
 * <p>被测对象横跨两个包（{@code domain/usage} 的写入器 + {@code domain/debug} 的埋点汇聚点），
 * 因 {@code AiCallTrace} 的构造器与 {@code AiCallTraceService} 的仓库构造器都是包内可见，
 * 故本测试放在 {@code domain.debug.service} 包，并按公开 API 断言（真实 trace + 捕获落库实体，
 * 不使用包内 {@code toEntity}），以便同时验证「埋点挂载点」与「写入器映射」。</p>
 *
 * <p>覆盖三层：</p>
 * <ol>
 * <li><b>开关语义</b>：关闭时零写入零开销（不建线程、不碰库），开启时按 trace 完整映射落库；</li>
 * <li><b>保真与限长</b>：system/user/response <b>全文不截断</b>（即使远超 actor_ai_call_log 的
 *     max-prompt-chars=20000），只对元数据列做防御性限长；</li>
 * <li><b>不阻断 AI 主流程</b>：写入异常只告警计数、绝不抛出；调试埋点关闭
 *     （debug.ai-log.enabled=false）时测量数据照样落库，且一次调用最多一条记录（防双写）。</li>
 * </ol>
 */
class AiCallTraceServiceTestUsageHookTest {

    /** 用量明细仓库（mock） */
    private TestUsageLogRepository usageRepository;

    /** 调试埋点仓库（mock） */
    private ActorAiCallLogRepository aiLogRepository;

    /** 世界时钟仓库（mock；提供只读游戏秒） */
    private ActorWorldClockRepository worldClockRepository;

    /** 被测写入器 */
    private TestUsageLogService usageService;

    /** 被测埋点服务（默认：仓库注入，enabled 由 @Value 字段初值 true） */
    private AiCallTraceService traceService;

    @BeforeEach
    void setUp() {
        usageRepository = Mockito.mock(TestUsageLogRepository.class);
        aiLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        worldClockRepository = Mockito.mock(ActorWorldClockRepository.class);
        traceService = new AiCallTraceService(aiLogRepository);
        usageService = new TestUsageLogService(usageRepository);
        ReflectionTestUtils.setField(usageService, "worldClockRepository", worldClockRepository);
        AiCallContext.setSink(null);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /** 开启用量明细开关并设置轮次标签 */
    private void enableUsage(String runLabel) {
        ReflectionTestUtils.setField(usageService, "enabled", true);
        ReflectionTestUtils.setField(usageService, "runLabel", runLabel);
    }

    /**
     * 造一条真实链路 trace：上下文内 {@code begin(projectId)}（置 invoked + 回填项目）→
     * 填 system/user → markSuccess（模型/token/缓存/供应商）。
     */
    private AiCallTrace invokedTrace(String scene, Long characterId, Long projectId,
                                     String system, String user, String response) {
        AiCallTrace[] holder = new AiCallTrace[1];
        AiCallContext.run(scene, characterId, () -> {
            AiCallTrace t = traceService.begin(projectId);
            t.setBaseUrl("https://api.deepseek.com");
            t.fillMessages(new AiChatRequest("deepseek-flash", List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", user)), null, null));
            t.markSuccess(response, "deepseek-flash", 1234, 567, 800, 434, 88L);
            holder[0] = t;
            return null;
        });
        assertNotNull(holder[0], "上下文内应能取到 trace");
        return holder[0];
    }

    /** 记录一条并等落盘，返回落库实体（先清掉本用例此前的交互记录，便于同用例多次断言） */
    private TestUsageLog recordAndCapture(AiCallTrace trace) {
        Mockito.clearInvocations(usageRepository);
        usageService.recordAsync(trace);
        assertTrue(usageService.awaitIdle(2000), "用量明细应能在 2 秒内落盘");
        ArgumentCaptor<TestUsageLog> captor = ArgumentCaptor.forClass(TestUsageLog.class);
        verify(usageRepository).save(captor.capture());
        return captor.getValue();
    }

    /** 关闭态：完全不记录（仓库零交互、在途/失败计数为 0），{@code awaitIdle} 立即返回。 */
    @Test
    void disabledWritesNothing() {
        assertFalse(usageService.isEnabled(), "默认应为关闭（holzyn.actor.test-usage.enabled=false）");

        usageService.recordAsync(invokedTrace("action", 5L, 1L, "sys", "usr", "resp"));

        assertTrue(usageService.awaitIdle(500), "关闭态应立即视为空闲");
        assertEquals(0, usageService.getInFlight(), "关闭态不应有在途写入");
        assertEquals(0, usageService.getFailedCount(), "关闭态不应有失败计数");
        verify(usageRepository, never()).save(Mockito.any());
    }

    /** 开启态：字段完整映射（轮次标签/游戏秒/场景/项目/角色/供应商/模型/地址/token/缓存/状态/字符数）。 */
    @Test
    void enabledMapsAllFields() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(141_605L);

        AiCallTrace trace = invokedTrace("action", 7L, 1L, "系统", "用户", "回复");
        TestUsageLog saved = recordAndCapture(trace);

        assertEquals("R3-20260927", saved.getRunLabel());
        assertEquals(141_605L, saved.getGameSecond().longValue(), "游戏秒应取世界时钟只读快照");
        assertEquals("action", saved.getScene());
        assertEquals("ai_call", saved.getStepType());
        assertEquals(7L, saved.getCharacterId().longValue());
        assertEquals(1L, saved.getProjectId().longValue());
        assertEquals(88L, saved.getProviderId().longValue());
        assertEquals("deepseek-flash", saved.getModel());
        assertEquals("https://api.deepseek.com", saved.getBaseUrl());
        assertEquals(1234, saved.getTokenIn().intValue());
        assertEquals(567, saved.getTokenOut().intValue());
        assertEquals(800, saved.getCacheHitTokens().intValue());
        assertEquals(434, saved.getCacheMissTokens().intValue());
        assertEquals("success", saved.getStatus());
        assertEquals(4, saved.getRequestChars().intValue(), "字符数=（system+user）码点数：系统2+用户2");
        assertEquals(2, saved.getResponseChars().intValue());
        assertNotNull(saved.getCallAt(), "call_at 应来自 trace 起始时刻");
        assertTrue(saved.getDurationMs() >= 0, "用时应为非负毫秒");
        assertEquals(trace.getTraceId(), saved.getTraceId(), "trace_id 原样透传（无业务链路上下文时可为 null）");
    }

    /** 保真：超长 prompt/输出全文落库（不被 20000 字符上限截断——与 actor_ai_call_log 的关键差别）。 */
    @Test
    void fullTextIsNeverTruncated() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(1L);
        String longSystem = "系".repeat(40_000);   // 40,000 码点 > max-prompt-chars 20000
        String longUser = "用".repeat(40_000);
        String longResponse = "回".repeat(40_000);

        TestUsageLog saved = recordAndCapture(
                invokedTrace("dialog", 3L, 1L, longSystem, longUser, longResponse));

        assertEquals(40_000, saved.getSystemPrompt().codePointCount(0, saved.getSystemPrompt().length()),
                "system prompt 必须全文保真");
        assertEquals(40_000, saved.getUserPrompt().codePointCount(0, saved.getUserPrompt().length()));
        assertEquals(40_000, saved.getResponseText().codePointCount(0, saved.getResponseText().length()));
        assertEquals(80_000, saved.getRequestChars().intValue());
        assertEquals(40_000, saved.getResponseChars().intValue());
    }

    /** 元数据列防御性限长（避免超长值让整行插入失败），正文列不受影响。 */
    @Test
    void metadataClampedButTextIntact() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(1L);

        TestUsageLog saved = recordAndCapture(
                invokedTrace("x".repeat(100), 1L, 1L, "sys", "usr", "resp"));

        assertEquals(48, saved.getScene().length(), "scene 列长 48：限长防插入失败");
        assertEquals("sys", saved.getSystemPrompt(), "正文不得被限长影响");
    }

    /** 空/空白轮次标签 → {@code UNLABELED}（避免报告脚本把无标签数据误算进某一轮）。 */
    @Test
    void blankRunLabelFallsBackToUnlabeled() {
        enableUsage("   ");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(1L);

        assertEquals("UNLABELED", usageService.effectiveRunLabel());
        assertEquals("UNLABELED", recordAndCapture(invokedTrace("action", 1L, 1L, "s", "u", "r")).getRunLabel());
        assertEquals("UNLABELED", new TestUsageLogService(usageRepository).effectiveRunLabel(),
                "空白标签应同样回退 UNLABELED");
    }

    /** 游戏秒取不到时回退 -1（未知），且绝不抛异常影响写入。 */
    @Test
    void unknownGameSecondFallsBackToMinusOne() {
        enableUsage("R3-20260927");
        ReflectionTestUtils.setField(usageService, "worldClockRepository", null);

        assertEquals(-1L, recordAndCapture(invokedTrace("action", 1L, 1L, "s", "u", "r"))
                .getGameSecond().longValue(), "无时钟仓库应回退 -1");

        ReflectionTestUtils.setField(usageService, "worldClockRepository", worldClockRepository);
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L))
                .thenThrow(new IllegalStateException("db down"));
        assertEquals(-1L, recordAndCapture(invokedTrace("action", 1L, 1L, "s", "u", "r"))
                .getGameSecond().longValue(), "查询异常应回退 -1，不得向上抛");
    }

    /** 不阻断主流程：仓库写入异常只告警计数，{@code recordAsync} 不抛、在途计数归零。 */
    @Test
    void repositoryFailureNeverPropagates() {
        enableUsage("R3-20260927");
        Mockito.when(usageRepository.save(Mockito.any(TestUsageLog.class)))
                .thenThrow(new RuntimeException("H2 写入失败"));

        assertDoesNotThrow(() -> usageService.recordAsync(invokedTrace("action", 1L, 1L, "s", "u", "r")));

        assertTrue(usageService.awaitIdle(2000), "写入失败后也应回到空闲");
        assertEquals(1, usageService.getFailedCount(), "失败应计数（供报告说明计量丢失）");
        assertEquals(0, usageService.getInFlight());
    }

    /** 异步非阻塞：写入发生在独立虚拟线程，{@code awaitIdle} 能等到落盘。 */
    @Test
    void recordIsAsynchronous() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(2L);

        TestUsageLog saved = recordAndCapture(invokedTrace("action", 1L, 1L, "s", "u", "r"));

        assertEquals("R3-20260927", saved.getRunLabel());
        assertEquals(2L, saved.getGameSecond().longValue());
    }

    /**
     * 埋点关闭（debug.ai-log.enabled=false）时测量数据照记，且同一条 trace 只记一次（防双写）。
     * <p>这是本设施与调试埋点<b>解耦</b>的回归断言：{@code submitted} 在关闭态不会被置位，
     * 因此防双写必须靠 trace 上的 {@code usageRecorded} 标志。</p>
     */
    @Test
    void recordsEvenWhenDebugAiLogDisabledAndNeverDoubleWrites() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(1L)).thenReturn(1L);
        ReflectionTestUtils.setField(traceService, "enabled", false);
        ReflectionTestUtils.setField(traceService, "testUsageRecorder", usageService);

        AiCallTrace trace = invokedTrace("action", 1L, 1L, "s", "u", "r");
        traceService.submitAsync(trace);
        traceService.submitAsync(trace);   // 重复提交：不得写第二条
        traceService.submitAsync(trace);

        assertTrue(usageService.awaitIdle(2000), "用量明细应落盘");
        verify(usageRepository).save(Mockito.any(TestUsageLog.class));
        verify(aiLogRepository, never()).save(Mockito.any(ActorAiCallLog.class));
    }

    /** 埋点开启时两张表各写一次（用量明细不干扰既有调试埋点链路）。 */
    @Test
    void bothSinksWriteOnceWhenDebugAiLogEnabled() {
        enableUsage("R3-20260927");
        Mockito.when(worldClockRepository.findGameSecondByProjectId(9L)).thenReturn(1L);
        ReflectionTestUtils.setField(traceService, "enabled", true);
        ReflectionTestUtils.setField(traceService, "testUsageRecorder", usageService);
        AiCallContext.setSink(traceService::submitAsync);

        AiCallTrace trace = invokedTrace("dialog", 5L, 9L, "系统提示", "用户消息", "回复");

        assertTrue(usageService.awaitIdle(2000), "用量明细应落盘");
        traceService.awaitIdle(2000);   // 既有 API 无返回值：等待 actor_ai_call_log 写完后统一断言
        ArgumentCaptor<TestUsageLog> usageCaptor = ArgumentCaptor.forClass(TestUsageLog.class);
        verify(usageRepository).save(usageCaptor.capture());
        verify(aiLogRepository).save(Mockito.any(ActorAiCallLog.class));
        TestUsageLog saved = usageCaptor.getValue();
        assertEquals("dialog", saved.getScene());
        assertEquals(5L, saved.getCharacterId().longValue());
        assertEquals(9L, saved.getProjectId().longValue());
        assertEquals("系统提示", saved.getSystemPrompt());
        assertEquals("用户消息", saved.getUserPrompt());
        assertEquals("回复", saved.getResponseText());
        assertEquals(88L, saved.getProviderId().longValue());
        assertEquals(trace.getTraceId(), saved.getTraceId(), "trace_id 原样透传（便于与决策链路对齐）");
    }

    /** 未发起过 Provider 调用的 trace 不入测量明细（兜底 trace 不应污染计量）。 */
    @Test
    void notInvokedTraceIsNotRecorded() {
        enableUsage("R3-20260927");
        ReflectionTestUtils.setField(traceService, "testUsageRecorder", usageService);

        AiCallTrace notInvoked = new AiCallTrace("chat", null);
        assertFalse(notInvoked.isInvoked(), "新建 trace 应为未发起状态");
        traceService.submitAsync(notInvoked);

        assertTrue(usageService.awaitIdle(500));
        verify(usageRepository, never()).save(Mockito.any());
    }

    /** 被记录器依赖的 trace 只读访问器存在且取值正确（本轮为此新增 {@code getStartMs/getBaseUrl}）。 */
    @Test
    void traceExposesStartTimeAndBaseUrl() {
        AiCallTrace trace = invokedTrace("action", 1L, 1L, "s", "u", "r");

        assertEquals("https://api.deepseek.com", trace.getBaseUrl());
        assertTrue(trace.getStartMs() > 0, "startMs 应为 epoch 毫秒");
    }
}
