package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈 AI 动态开场白服务单元测试（AI mock，零真实调用）。
 * <p>职责：验证——AI 成功生成并落库（assistant + greeting meta）/已有实质消息跳过（不重复生成）/
 * AI 失败 fail-open（不落库返回 null）。</p>
 */
class DialogueGreetingServiceTest {

    private DialogueSessionService sessionService;
    private DialogueMessageService messageService;
    private DialogueContextAssembler assembler;
    private DialogueRelationService relationService;
    private ActorCharacterRepository characterRepository;
    private AiSpringChatGateway aiChatGateway;
    private DialogueGreetingService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        messageService = mock(DialogueMessageService.class);
        assembler = mock(DialogueContextAssembler.class);
        relationService = mock(DialogueRelationService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        service = new DialogueGreetingService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "assembler", assembler);
        ReflectionTestUtils.setField(service, "relationService", relationService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
    }

    private ActorDialogueSession session() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setCharacterRef("npc:1");
        return s;
    }

    @Test
    void generateSuccessSavesAssistantMessage() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of());
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈守拙");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        when(assembler.stableSystem(eq(1L), eq("npc:1"), eq("沈守拙"), eq("阿岚"))).thenReturn("【世界观】...");
        when(assembler.greetingPrompt(eq(1L), any(), eq("沈守拙"), eq("阿岚"))).thenReturn("【场景】...");
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenReturn(new AiChatResult("这位客官，可是远道而来？", "qwen", 10, 8, 18, 1L));

        String text = service.generate(1L, 1L, 7L);
        assertEquals("这位客官，可是远道而来？", text);
        verify(messageService).appendAssistant(eq(1L), eq(7L), eq("npc:1"), eq("这位客官，可是远道而来？"),
                eq("{\"kind\":\"greeting\"}"));
    }

    @Test
    void generateSkipWhenMessagesExist() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setContent("我先说了句话");
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(m));

        assertNull(service.generate(1L, 1L, 7L));
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        verify(messageService, never()).appendAssistant(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void generateAiFailureFailOpen() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of());
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenThrow(new AiCallException("网关不可达"));

        assertNull(service.generate(1L, 1L, 7L));
        verify(messageService, never()).appendAssistant(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void generateBlankContentFailOpen() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of());
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenReturn(new AiChatResult("  ", "qwen", 1, 1, 2, 1L));

        assertNull(service.generate(1L, 1L, 7L));
        verify(messageService, never()).appendAssistant(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void generateGroupSessionSkipped() {
        ActorDialogueSession s = session();
        s.setCharacterRef(null); // 群聊开场 M5
        when(sessionService.require(1L, 7L)).thenReturn(s);
        assertNull(service.generate(1L, 1L, 7L));
    }
}
