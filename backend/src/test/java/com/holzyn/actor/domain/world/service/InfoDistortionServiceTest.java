package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信息认知失真改写服务单元测试（世界演化与游戏时间系统 M5，domain/world/service）。
 * <p>职责：验证——① AI 改写调用与<b>（受众×包×游戏日）缓存收敛</b>（同键同日只调 1 次 AI，
 * 换日/换受众重新调用）；② 无失真规则 → 原文直返零 AI；③ AI 失败/关断 → 规则兜底（传闻前缀，
 * 确定性零成本）；④ 规则兜底/文本清理纯静态逻辑。</p>
 */
class InfoDistortionServiceTest {

    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private WorldInfoProperties props;
    private InfoDistortionService service;

    @BeforeEach
    void setUp() {
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        props = new WorldInfoProperties();
        service = new InfoDistortionService(promptTemplateService, aiChatGateway, usageLogService, props);
        CurrentUserProvider c = Mockito.mock(CurrentUserProvider.class);
        when(c.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(service, "currentUserProvider", c);
        when(promptTemplateService.render(any(), any(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(any(), any(), anyString(), any())).thenReturn("系统提示");
    }

    /** mock AI 返回固定失真文本（带引号包裹，验证清理）。 */
    private void mockAiResult(String content) {
        AiChatResult result = Mockito.mock(AiChatResult.class);
        when(result.content()).thenReturn(content);
        when(result.providerId()).thenReturn(1L);
        when(result.model()).thenReturn("mock-model");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);
    }

    @Test
    void distortForNpc_cachesByAudiencePacketDay() {
        mockAiResult("「集市粮价飞涨」");
        List<String> rules = List.of("距离越远越夸大");

        String first = service.distortForNpc(1L, "special:1", "沈砚秋·村长", 1L,
                "集市粮价飞涨，有人开始囤粮", rules, "消息经商队传来", 1L);
        assertEquals("集市粮价飞涨", first, "AI 输出应去引号清理");
        String second = service.distortForNpc(1L, "special:1", "沈砚秋·村长", 1L,
                "集市粮价飞涨，有人开始囤粮", rules, "消息经商队传来", 1L);
        assertEquals(first, second);
        verify(aiChatGateway, times(1)).chatCompletion(any(), any(), any(), any());

        // 换游戏日 → 重新调用
        service.distortForNpc(1L, "special:1", "沈砚秋·村长", 1L,
                "集市粮价飞涨", rules, "ctx", 2L);
        verify(aiChatGateway, times(2)).chatCompletion(any(), any(), any(), any());

        // 换受众 → 重新调用
        service.distortForNpc(1L, "crowd:5", "货郎·赵六", 1L,
                "集市粮价飞涨", rules, "ctx", 1L);
        verify(aiChatGateway, times(3)).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void distortForNpc_noRulesReturnsOriginalWithoutAi() {
        String content = "集市粮价平稳";
        String out = service.distortForNpc(1L, "special:1", "沈砚秋", 1L, content, List.of(), null, 1L);
        assertEquals(content, out, "无失真规则应原文直返");
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        assertNull(service.distortForNpc(1L, "special:1", "沈砚秋", 1L, null, List.of("夸大"), null, 1L),
                "空内容直返");
    }

    @Test
    void distortForNpc_aiFailureFallsBackToRule() {
        when(aiChatGateway.chatCompletion(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));
        String out = service.distortForNpc(1L, "special:1", "沈砚秋", 1L,
                "集市粮价飞涨", List.of("距离越远越夸大"), "消息经商队从渡口传来", 1L);
        assertNotNull(out);
        assertTrue(out.contains("据说事情闹得比传言还大"), "夸大规则应加程度前缀: " + out);
        assertTrue(out.contains("集市粮价飞涨"), "核心事实保留");
    }

    @Test
    void distortForNpc_aiDisabledUsesRuleFallback() {
        props.setDistortionAiEnabled(false);
        String out = service.distortForNpc(1L, "special:1", "沈砚秋", 1L,
                "渡口损毁，往来不便", List.of("职业刻意隐瞒"), "ctx", 1L);
        assertNotNull(out);
        assertTrue(out.contains("讳莫如深"), "隐瞒规则应模糊化: " + out);
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void distortForRegion_sharesCachePerRegion() {
        mockAiResult("渡口那边出事了，听说桥都断了");
        List<String> rules = List.of("距离越远越夸大");
        String a = service.distortForRegion(1L, "集市", 1L, "渡口损毁", rules, "ctx", 1L);
        String b = service.distortForRegion(1L, "集市", 1L, "渡口损毁", rules, "ctx", 1L);
        assertEquals(a, b, "同区域同包同日共享同一传闻版");
        verify(aiChatGateway, times(1)).chatCompletion(any(), any(), any(), any());
        // 不同区域 → 不同版本（重新调用）
        service.distortForRegion(1L, "山神庙", 1L, "渡口损毁", rules, "ctx", 1L);
        verify(aiChatGateway, times(2)).chatCompletion(any(), any(), any(), any());
    }

    // ==================== 静态纯逻辑 ====================

    @Test
    void ruleFallback_appliesRulesAndKeepsFacts() {
        String content = "粮价上涨";
        assertTrue(InfoDistortionService.ruleFallback(content, List.of("夸大"), null).contains("粮价上涨"));
        assertTrue(InfoDistortionService.ruleFallback(content, List.of("隐瞒"), null).contains("语焉不详"));
        assertTrue(InfoDistortionService.ruleFallback(content, List.of("缩小"), null).contains("没那么要紧"));
        assertTrue(InfoDistortionService.ruleFallback(content, List.of("其他规则"), "ctx").contains("ctx"));
        assertNull(InfoDistortionService.ruleFallback(null, List.of("夸大"), null));
        assertEquals("", InfoDistortionService.ruleFallback("  ", List.of("夸大"), null));
    }

    @Test
    void cleanText_stripsQuotesAndCodeBlocks() {
        assertEquals("消息", InfoDistortionService.cleanText("\"消息\""));
        assertEquals("消息", InfoDistortionService.cleanText("「消息」"));
        assertEquals("消息", InfoDistortionService.cleanText("```\n消息\n```"));
        assertEquals("消息", InfoDistortionService.cleanText(" 消息 "));
        assertNull(InfoDistortionService.cleanText(null));
        assertFalse(InfoDistortionService.cleanText("普通文本").isBlank());
    }

    // ==================== 批6 收口：info_distortion 世界视角段 2D（2026-10-10） ====================

    /** AI 改写请求前置 段2D【当前世界动态】；注入器未装配 → 原样（fail-open）。 */
    @Test
    void distortionRequestPrependsWorldDynamics() {
        mockAiResult("「集市粮价飞涨」");
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年05月15日 · 夏 · 天气晴");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        service.distortForNpc(1L, "special:1", "沈砚秋·村长", 1L,
                "集市粮价飞涨，有人开始囤粮", List.of("夸大"), "ctx", 1L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(any(), any(), any(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertTrue(userMsg.startsWith("【世界观】"), "info_distortion 必须前置 段1A【世界观】: " + userMsg);
        assertTrue(userMsg.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + userMsg);
        assertTrue(userMsg.contains("prompt"), "模板正文须保留在段 2D 之后");
    }

    @Test
    void distortionRequestNoPrefixWhenInjectorMissing() {
        mockAiResult("「集市粮价飞涨」");
        service.distortForNpc(1L, "special:1", "沈砚秋·村长", 1L,
                "集市粮价飞涨", List.of("夸大"), "ctx", 1L);
        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(any(), any(), any(), cap.capture());
        assertEquals("prompt", cap.getValue().messages().get(1).content(),
                "注入器未装配时不得注入前缀（fail-open）");
    }
}
