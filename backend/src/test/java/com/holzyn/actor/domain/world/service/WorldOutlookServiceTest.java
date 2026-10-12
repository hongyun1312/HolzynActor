package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 潜在世界预测注入（P1 ③-2，world_outlook）单元测试。
 * <p>覆盖：程序化趋势摘要（快照对比：粮价/民怨/势力/议题方向；数据不足/异常降级 null）；
 * AI 预测段（趋势+宏观输入 → 段组装；按游戏日缓存——同日复用不重复 AI、跨日重算；
 * fail-open——AI 失败回退趋势摘要；未装配降级）；开关/截断边界。</p>
 * <p>装配：仓库 mock 直接 new 构造（构造器=仓库+ObjectMapper）；可选服务字段
 * {@code ReflectionTestUtils.setField} 注入（对齐 WorldGraphQueryServiceTest 模式）。</p>
 */
class WorldOutlookServiceTest {

    private final ActorWorldMacroSnapshotRepository repo = mock(ActorWorldMacroSnapshotRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorldOutlookService service = new WorldOutlookService(repo, mapper);

    // ==================== 程序化趋势摘要 ====================

    /** 无快照行 → 趋势 null（省略段零阻断）。 */
    @Test
    void trendSummaryNoSnapshotsReturnsNull() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of());

        assertNull(service.trendSummary(100L), "无快照 → null");
    }

    /** 仅 1 个 daily 快照（无对比基准）→ null。 */
    @Test
    void trendSummarySingleSnapshotReturnsNull() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L))
                .thenReturn(List.of(snapshot(5L, "daily", stateJson(5L, 80.0, 40.0, 30.0, "天地异象", 0.5))));

        assertNull(service.trendSummary(100L), "单日无对比 → null");
    }

    /** 非 daily tick 行不参与趋势（只认 daily）。 */
    @Test
    void trendSummaryIgnoresNonDailyTicks() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                snapshot(4L, "init", stateJson(4L, 80.0, 40.0, 30.0, "天地异象", 0.5)),
                snapshot(3L, "faction", stateJson(3L, 90.0, 50.0, 40.0, "粮荒", 0.8))));

        assertNull(service.trendSummary(100L), "无 daily 行 → null");
    }

    /** 两日快照：粮价上涨 + 民怨上升 → 趋势文本含方向。 */
    @Test
    void trendSummaryRisingPriceAndGrievance() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                snapshot(6L, "daily", stateJson(6L, 88.0, 52.0, 34.0, "天地异象", 0.6)),
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.5))));

        String t = service.trendSummary(100L);

        assertNotNull(t);
        assertTrue(t.contains("粮价上升"), "粮价上升方向: " + t);
        assertTrue(t.contains("80.00→88.00"), "粮价首末值: " + t);
        assertTrue(t.contains("民怨上升"), "民怨上升: " + t);
        assertTrue(t.contains("45→52"), "民怨首末值: " + t);
    }

    /** 势力影响力上升 + 议题热度上升。 */
    @Test
    void trendSummaryFactionAndIssueRising() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                snapshot(6L, "daily", stateJson(6L, 88.0, 52.0, 38.0, "天地异象", 0.7)),
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.4))));

        String t = service.trendSummary(100L);

        assertNotNull(t);
        assertTrue(t.contains("「沈氏宗族」影响力上升"), "势力上升: " + t);
        assertTrue(t.contains("议题「天地异象」热度上升"), "议题热度上升: " + t);
    }

    /** 指标持平 → 「平稳」表述。 */
    @Test
    void trendSummaryFlatUsesStable() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                snapshot(6L, "daily", stateJson(6L, 80.0, 45.0, 30.0, "天地异象", 0.5)),
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.5))));

        String t = service.trendSummary(100L);

        assertNotNull(t);
        assertTrue(t.contains("粮价平稳"), "粮价平稳: " + t);
        assertTrue(t.contains("民怨平稳"), "民怨平稳: " + t);
    }

    /** 损坏快照行跳过（不阻断整体趋势）。 */
    @Test
    void trendSummarySkipsBrokenRow() {
        ActorWorldMacroSnapshot broken = snapshot(6L, "daily", "{not-json");
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                broken,
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.5)),
                snapshot(4L, "daily", stateJson(4L, 76.0, 40.0, 28.0, "天地异象", 0.4))));

        String t = service.trendSummary(100L);

        assertNotNull(t, "损坏行跳过，剩余行仍可对比");
        assertTrue(t.contains("粮价上升"), "趋势仍有效: " + t);
    }

    // ==================== AI 预测段 + 缓存 ====================

    /** 无趋势无宏观 → 段 null（无从预测，省略零阻断）。 */
    @Test
    void outlookSegmentNoInputsReturnsNull() {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of());

        assertNull(service.outlookSegment(100L), "无事实输入 → null");
    }

    /** AI 成功：段 = 【未来一天世界走向】+ 预测文本（≤80 字截断）。 */
    @Test
    void outlookSegmentAiSuccessBuildsSegment() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("你只输出一句不超过 80 字的世界走向预判");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), eq(100L), any(), any()))
                .thenReturn(new AiChatResult("明日粮价恐再涨，集市或有人抢购，沈氏宗族借机收拢人心。",
                        "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        com.holzyn.actor.domain.account.CurrentUserProvider user =
                mock(com.holzyn.actor.domain.account.CurrentUserProvider.class);
        when(user.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        ReflectionTestUtils.setField(service, "currentUserProvider", user);

        String seg = service.outlookSegment(100L);

        assertNotNull(seg);
        assertTrue(seg.startsWith("【未来一天世界走向】"), "段含标题: " + seg);
        assertTrue(seg.contains("明日粮价恐再涨"), "段含 AI 预测: " + seg);
        assertTrue(seg.length() <= "【未来一天世界走向】".length() + 80 + 1, "预测段 ≤80 字截断");
        verify(gateway).chatCompletion(anyLong(), eq(100L), any(), any());
    }

    /** 缓存：同一游戏日第二次调用不重复 AI（verify 次数=1）。 */
    @Test
    void outlookSegmentCachesSameGameDay() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        service.outlookSegment(100L);
        service.outlookSegment(100L);

        verify(gateway, times(1)).chatCompletion(anyLong(), anyLong(), any(), any());
        assertEquals("【未来一天世界走向】预测文本", service.outlookSegment(100L), "同日缓存复用");
    }

    /** 缓存失效：世界时钟推进到下一游戏日 → 重新 AI（verify 次数=2）。 */
    @Test
    void outlookSegmentCacheInvalidatesNextGameDay() throws Exception {
        stubTrendAndClock(100L, 489600L); // Day5
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        service.outlookSegment(100L); // Day5 首次
        service.outlookSegment(100L); // Day5 缓存
        com.holzyn.actor.domain.world.service.WorldClockService clock =
                (com.holzyn.actor.domain.world.service.WorldClockService) ReflectionTestUtils
                        .getField(service, "worldClockService");
        when(clock.currentEngineGameSecond(100L)).thenReturn(576000L); // Day6
        service.outlookSegment(100L); // Day6 重算

        verify(gateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /** fail-open：AI 失败 → 回退程序化趋势摘要（非 null 保证有段）。 */
    @Test
    void outlookSegmentAiFailureFallsBackToTrend() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("AI 不可用"));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        String seg = service.outlookSegment(100L);

        assertNotNull(seg, "AI 失败回退趋势摘要（非 null）");
        assertTrue(seg.contains("粮价上升"), "回退段含趋势摘要: " + seg);
    }

    /** 未装配模板/网关 → 直接回退趋势摘要（fail-open 零阻断）。 */
    @Test
    void outlookSegmentUnwiredFallsBackToTrend() throws Exception {
        stubTrendAndClock(100L, 489600L);

        String seg = service.outlookSegment(100L);

        assertNotNull(seg, "未装配 AI → 回退趋势摘要");
        assertTrue(seg.contains("粮价上升"), "回退段含趋势: " + seg);
    }

    /** 批6 收口：world_outlook 属「世界视角」——AI 预测请求前置 段2D【当前世界动态】。 */
    @Test
    void outlookSegmentPrependsWorldDynamicsSegment2D() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("你只输出一句不超过 80 字的世界走向预判");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), eq(100L), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(100L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年05月15日 06时00分 · 夏 · 天气晴");
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        service.outlookSegment(100L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(gateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertTrue(userMsg.startsWith("【世界观】"), "world_outlook 请求必须前置 段1A【世界观】: " + userMsg);
        assertTrue(userMsg.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + userMsg);
        assertTrue(userMsg.contains("世界走向预测 Prompt"), "模板正文须保留在段 2D 之后");
    }

    /** 批6 收口：注入器未装配 → world_outlook 请求无前缀（fail-open 零阻断）。 */
    @Test
    void outlookSegmentNoPrefixWhenInjectorMissing() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), eq(100L), eq(WorldOutlookService.SCENE_WORLD_OUTLOOK), any()))
                .thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), eq(100L), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        service.outlookSegment(100L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(gateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertEquals("世界走向预测 Prompt", userMsg, "注入器未装配时不得注入前缀");
    }

    /** 开关关闭 → 全 null（零影响）。 */
    @Test
    void disabledReturnsNull() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);
        stubTrendAndClock(100L, 489600L);

        assertNull(service.trendSummary(100L), "关 → 趋势 null");
        assertNull(service.outlookSegment(100L), "关 → 段 null");
    }

    /** 世界时钟不可用（null）→ 不缓存，每次重算（多次调用均有效）。 */
    @Test
    void outlookSegmentWithoutClockRebuildsEachTime() throws Exception {
        // 不注入 worldClockService（null）→ gameDay() 返回 null → 不缓存
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(100L)).thenReturn(List.of(
                snapshot(6L, "daily", stateJson(6L, 88.0, 52.0, 34.0, "天地异象", 0.6)),
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.5))));
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        service.outlookSegment(100L);
        service.outlookSegment(100L);

        verify(gateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /** 超长 AI 输出截断（≤80 字）。 */
    @Test
    void outlookSegmentTruncatesLongPrediction() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("这是一个非常非常长的预测文本".repeat(20), "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        String seg = service.outlookSegment(100L);

        assertNotNull(seg);
        assertTrue(seg.length() <= "【未来一天世界走向】".length() + 80 + 1, "超长截断: len=" + seg.length());
    }

    /** 清空缓存（调试/冒烟用）后同日重新 AI。 */
    @Test
    void clearCacheForcesRebuild() throws Exception {
        stubTrendAndClock(100L, 489600L);
        com.holzyn.actor.domain.settings.service.PromptTemplateService tpl =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        when(tpl.render(anyLong(), anyLong(), any(), any())).thenReturn("世界走向预测 Prompt");
        when(tpl.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("预测文本", "test-model", 40, 20, 60, 1L));
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", tpl);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);

        service.outlookSegment(100L);
        service.clearCache();
        service.outlookSegment(100L);

        verify(gateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 辅助 ====================

    /** 快照行构造（tickType/indicatorsJson）。 */
    private static ActorWorldMacroSnapshot snapshot(Long day, String tickType, String indicatorsJson) {
        ActorWorldMacroSnapshot s = new ActorWorldMacroSnapshot();
        s.setGameDay(day);
        s.setTickType(tickType);
        s.setIndicatorsJson(indicatorsJson);
        return s;
    }

    /** MacroState JSON（粮价/民怨/势力 top/议题 top）。 */
    private String stateJson(Long day, double price, double grievance, double factionInfluence,
                             String issueTopic, double issueIntensity) {
        MacroState st = new MacroState();
        st.setGameDay(day);
        MacroState.Economy eco = new MacroState.Economy();
        eco.setGrainPrice(price);
        st.setEconomy(eco);
        st.setGrievance(grievance);
        Map<String, MacroState.Faction> factions = new LinkedHashMap<>();
        MacroState.Faction f = new MacroState.Faction();
        f.setName("沈氏宗族");
        f.setInfluence(factionInfluence);
        factions.put("沈氏宗族", f);
        st.setFactions(factions);
        MacroState.Issue issue = new MacroState.Issue();
        issue.setTopic(issueTopic);
        issue.setIntensity(issueIntensity);
        st.setIssues(List.of(issue));
        try {
            return mapper.writeValueAsString(st);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 趋势快照 + 世界时钟（Day5=489600s）+ 宏观服务（当前简报 null 亦可——趋势已有事实）。 */
    private void stubTrendAndClock(Long projectId, Long gameSecond) throws Exception {
        when(repo.findByProjectIdOrderByGameDayDescTickTypeAsc(projectId)).thenReturn(List.of(
                snapshot(6L, "daily", stateJson(6L, 88.0, 52.0, 34.0, "天地异象", 0.6)),
                snapshot(5L, "daily", stateJson(5L, 80.0, 45.0, 30.0, "天地异象", 0.5))));
        com.holzyn.actor.domain.world.service.WorldClockService clock =
                mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        when(clock.currentEngineGameSecond(projectId)).thenReturn(gameSecond);
        ReflectionTestUtils.setField(service, "worldClockService", clock);
    }
}
