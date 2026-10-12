package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 婚恋 AI 判定通用支撑（AiDecisionSourceSupport）单元测试。
 * <p>职责：模板渲染 → AI 调用 → 用量入账 → 提取 JSON 的通用链路；
 * 批5 世界与叙事（2026-10-10）：婚恋/生育/婚宴叙事（5 场景共用）前置 V2.0
 * memoryChainContext 前缀（段1A 世界观 + 段1C 角色卡 + 段2D 当前世界动态 + 段2E 当前状态）。</p>
 */
class AiDecisionSourceSupportTest {

    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private CurrentUserProvider currentUserProvider;
    private UsageLogService usageLogService;
    private AiDecisionSourceSupport support;

    @BeforeEach
    void setUp() {
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        support = new AiDecisionSourceSupport(promptTemplateService, aiChatGateway,
                currentUserProvider, usageLogService);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(any(), any(), anyString(), any())).thenReturn("判定 Prompt");
        when(promptTemplateService.systemMessage(any(), any(), anyString(), any())).thenReturn("");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any()))
                .thenReturn(new AiChatResult("{\"attracted\":true}", "test-model", 10, 5, 15, 1L));
    }

    // ==================== 批5 世界与叙事：段1A+1C+2D+2E 前缀（2026-10-10） ====================

    /**
     * 婚恋判定（growth_romance_decision）前置 V2.0 memoryChainContext 前缀
     * （段1A 世界观 + 段1C 角色卡 + 段2D 当前世界动态 + 段2E 当前状态）——
     * AI 判定心动/求婚/生育意愿时知道世界与自身处境。
     */
    @Test
    void call_prependsMemoryChainContextPrefix() {
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(1L, "npc:7", null))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        ReflectionTestUtils.setField(support, "aiContextInjector", inj);

        String content = support.call(1L, 7L, "growth_romance_decision", Map.of("name", "沈砚秋"));
        assertNotNull(content);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("判定 Prompt"), "模板 prompt 保留: " + user);
    }

    /** 注入器未装配 → 判定请求不注入前缀（零阻断，行为与批5 前一致）。 */
    @Test
    void call_skipsPrefixWhenInjectorMissing() {
        ReflectionTestUtils.setField(support, "aiContextInjector", null);
        String content = support.call(1L, 7L, "growth_romance_decision", Map.of("name", "沈砚秋"));
        assertNotNull(content);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(!user.contains("【世界观】"), "未装配 → 不注入前缀: " + user);
        assertTrue(user.contains("判定 Prompt"), "模板 prompt 保留: " + user);
    }
}
