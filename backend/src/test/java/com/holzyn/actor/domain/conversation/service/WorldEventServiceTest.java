package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 世界事件服务（world_event_polish）单元测试。
 * <p>批5 世界与叙事（2026-10-10）：世界事件润色属「世界视角」生成——前置
 * 段2D 当前世界动态（世界历时刻/季节/天气），润色贴合当下世界。</p>
 */
class WorldEventServiceTest {

    private ActorWorldSettingRepository worldSettingRepository;
    private AiSpringChatGateway aiChatGateway;
    private PromptService promptService;
    private PromptTemplateService promptTemplateService;
    private UsageLogService usageLogService;
    private WorldEventService service;

    @BeforeEach
    void setUp() {
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptService = Mockito.mock(PromptService.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        service = new WorldEventService(
                Mockito.mock(com.holzyn.actor.domain.conversation.service.ConversationService.class),
                Mockito.mock(ActorConversationRepository.class),
                Mockito.mock(ActorMessageRepository.class),
                worldSettingRepository,
                aiChatGateway,
                promptService,
                promptTemplateService,
                usageLogService,
                Mockito.mock(AgentRuntime.class),
                Mockito.mock(WorldEventBus.class),
                new ObjectMapper());
        when(promptTemplateService.systemMessage(anyLong(), anyLong(),
                Mockito.eq(PromptTemplateService.CODE_WORLD_EVENT_POLISH), any())).thenReturn("sys");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"title\":\"青禾村入秋\",\"content\":\"粮仓满溢。\"}",
                        "test-model", 10, 5, 15, 1L));
    }

    // ==================== 批5/批6 世界视角前缀：段1A 世界观 + 段2D 世界动态（2026-10-10） ====================

    /**
     * 世界事件润色（world_event_polish）属「世界视角」——前置 段1A【世界观】+ 段2D 当前世界动态
     * （世界历时刻/季节/天气），润色贴合世界设定与当下世界。
     */
    @Test
    void polishEventByAi_prependsWorldDynamicsPrefix() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(promptService.buildWorldEventPolishPrompt(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn("润色 Prompt");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n青禾村历 0420年09月15日 午时 · 秋季 · 天气晴");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        String result = service.polishEventByAi(1L, 1L, "粮仓入秋", "第 5 天");
        assertEquals("青禾村入秋\n粮仓满溢。", result);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.startsWith("【世界观】"), "应前置段1A 世界观: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + user);
        assertTrue(user.contains("秋季"), "应含季节: " + user);
        assertTrue(user.contains("润色 Prompt"), "模板 prompt 保留: " + user);
    }

    /** 注入器未装配 → 润色请求不注入段2D（零阻断，行为与批5 前一致）。 */
    @Test
    void polishEventByAi_skipsPrefixWhenInjectorMissing() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(promptService.buildWorldEventPolishPrompt(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn("润色 Prompt");
        ReflectionTestUtils.setField(service, "aiContextInjector", null);

        String result = service.polishEventByAi(1L, 1L, "粮仓入秋", "第 5 天");
        assertEquals("青禾村入秋\n粮仓满溢。", result);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(!user.contains("【当前世界动态】"), "未装配 → 不注入段2D: " + user);
        assertTrue(user.contains("润色 Prompt"), "模板 prompt 保留: " + user);
    }
}
