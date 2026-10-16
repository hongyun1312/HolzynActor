package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈单聊编排单元测试（AI mock，零真实调用）。
 * <p>职责：验证——SSE 事件序 meta→token→done（done 含 messageId/content/characterName）、
 * 上下文装配进 AI 请求（system/user 双段）、历史剔除刚落库的本轮发言（对齐助手域 M5 抓修）、
 * 流式失败 error 降级（不落库）。</p>
 */
class DialogueChatServiceTest {

    private DialogueSessionService sessionService;
    private DialogueMessageService messageService;
    private DialogueContextAssembler assembler;
    private ActorCharacterRepository characterRepository;
    private AiSpringChatGateway aiChatGateway;
    private DialogueChatService service;

    private final List<ActorDialogueMessage> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        messageService = mock(DialogueMessageService.class);
        assembler = mock(DialogueContextAssembler.class);
        characterRepository = mock(ActorCharacterRepository.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        service = new DialogueChatService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "assembler", assembler);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);

        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈守拙");
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(ch));
    }

    private ActorDialogueSession session() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setCharacterRef("npc:1");
        return s;
    }

    private ActorDialogueMessage msg(String role, String content, Long id) {
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setId(id);
        m.setSessionId(7L);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 捕获型 SSE sink：事件按序记录。 */
    private static final class CaptureSink implements DialogueChatService.SseSink {
        final List<Map.Entry<String, Object>> events = new ArrayList<>();

        @Override
        public boolean send(String name, Object data) {
            events.add(Map.entry(name, data));
            return true;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatStreamsReplyAndSaves() {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        // 历史含刚落库的本轮发言 → 编排应剔除
        when(messageService.listEntities(1L, 7L)).thenReturn(
                List.of(msg("user", "你好", 1L), msg("assistant", "早", 2L), msg("user", "你好", 3L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("你是沈守拙…", "【本轮】你好…"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("早");
            onToken.accept("上好");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(eq(1L), eq(7L), eq("npc:1"), eq("早上好"), eq(null)))
                .thenReturn(msg("assistant", "早上好", 4L));

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        assertTrue(sink.events.size() >= 3);
        assertEquals("meta", sink.events.get(0).getKey());
        assertEquals("token", sink.events.get(1).getKey());
        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals("done", sink.events.get(sink.events.size() - 1).getKey());
        assertEquals(4L, done.get("messageId"));
        assertEquals("早上好", done.get("content"));
        assertEquals("沈守拙", done.get("characterName"));
        // 回复落库（不含 error）
        verify(messageService).appendAssistant(eq(1L), eq(7L), eq("npc:1"), eq("早上好"), eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatDropsJustAppendedUserFromHistory() {
        when(messageService.appendUser(1L, 7L, "在吗")).thenReturn(msg("user", "在吗", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        // 历史=刚落库本轮发言 + 旧消息：编排应只传旧消息（剔除末尾 user）
        when(messageService.listEntities(1L, 7L)).thenReturn(
                List.of(msg("user", "早上好", 1L), msg("assistant", "早", 2L), msg("user", "在吗", 3L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("在吗"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("在");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any())).thenReturn(msg("assistant", "在", 5L));

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "在吗", sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActorDialogueMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(assembler).composeSingle(eq(1L), eq(1L), any(), eq("在吗"), historyCaptor.capture());
        List<ActorDialogueMessage> passed = historyCaptor.getValue();
        // 剔除末尾刚落库的「在吗」，只保留旧两条
        assertEquals(2, passed.size());
        assertEquals("早上好", passed.get(0).getContent());
        assertEquals("早", passed.get(1).getContent());
    }

    @Test
    void chatStreamFailureSendsErrorNoSave() {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doThrow(new AiCallException("网关超时"))
                .when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        Map<String, Object> error = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals("error", sink.events.get(sink.events.size() - 1).getKey());
        assertTrue(String.valueOf(error.get("message")).contains("网关超时"));
        verify(messageService, never()).appendAssistant(any(), any(), any(), any(), any());
        assertFalse(sink.events.stream().anyMatch(e -> "done".equals(e.getKey())));
    }

    @Test
    void chatAssemblesContextIntoAiRequest() throws Exception {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("【世界观】L0", "【本轮】L1"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("回");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any())).thenReturn(msg("assistant", "回", 5L));

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), reqCaptor.capture(), any(), any());
        AiChatRequest req = reqCaptor.getValue();
        assertEquals(2, req.messages().size());
        assertEquals("system", req.messages().get(0).role());
        assertEquals("【世界观】L0", req.messages().get(0).content());
        assertEquals("user", req.messages().get(1).role());
        assertEquals("【本轮】L1", req.messages().get(1).content());
    }

    // ==================== 重写（regenerate） ====================

    @Test
    @SuppressWarnings("unchecked")
    void regenerateTruncatesTargetAndReusesLastUser() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        ActorDialogueMessage user = msg("user", "你好", 1L);
        ActorDialogueMessage old = msg("assistant", "旧回复", 2L);
        // 第一次 listEntities=重写前全量；第二次=截断后剩余
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(user, old), List.of(user));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("新");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(eq(1L), eq(7L), eq("npc:1"), eq("新"), any()))
                .thenReturn(msg("assistant", "新", 5L));

        CaptureSink sink = new CaptureSink();
        service.regenerate(1L, 1L, 7L, null, sink);

        // 截断目标（含目标本身）
        verify(messageService).remove(1L, 7L, 2L);
        // 历史不含触发发言本身：剩余=[user]，剔除后为空
        ArgumentCaptor<List<ActorDialogueMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(assembler).composeSingle(eq(1L), eq(1L), any(), eq("你好"), historyCaptor.capture());
        assertEquals(0, historyCaptor.getValue().size());
        // 旧回复进入新回复 swipe 候选
        ArgumentCaptor<String> metaCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).appendAssistant(eq(1L), eq(7L), eq("npc:1"), eq("新"), metaCaptor.capture());
        assertTrue(metaCaptor.getValue().contains("旧回复"));
        assertTrue(metaCaptor.getValue().contains("swipes"));
        // SSE 事件序 meta→token→done
        assertEquals("meta", sink.events.get(0).getKey());
        assertEquals("token", sink.events.get(1).getKey());
        assertEquals("done", sink.events.get(sink.events.size() - 1).getKey());
    }

    @Test
    void regenerateTargetsSpecifiedAssistantMessage() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        ActorDialogueMessage u1 = msg("user", "你好", 1L);
        ActorDialogueMessage a1 = msg("assistant", "回复A", 2L);
        ActorDialogueMessage u2 = msg("user", "追问", 3L);
        ActorDialogueMessage a2 = msg("assistant", "回复B", 4L);
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(u1, a1, u2, a2), List.of(u1));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("新");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any()))
                .thenReturn(msg("assistant", "新", 9L));

        CaptureSink sink = new CaptureSink();
        // 指定重写较早的 assistant（id=2）：截断其后全部（2/3/4），触发=截断后最后玩家（你好）
        service.regenerate(1L, 1L, 7L, 2L, sink);

        verify(messageService).remove(1L, 7L, 2L);
        verify(assembler).composeSingle(eq(1L), eq(1L), any(), eq("你好"), any());
        assertTrue(sink.events.stream().anyMatch(e -> "done".equals(e.getKey())));
    }

    @Test
    void regenerateWithoutAssistantSendsError() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));

        CaptureSink sink = new CaptureSink();
        service.regenerate(1L, 1L, 7L, null, sink);

        Map<String, Object> error = (Map<String, Object>) sink.events.get(0).getValue();
        assertEquals("error", sink.events.get(0).getKey());
        assertTrue(String.valueOf(error.get("message")).contains("没有可重写的角色回复"));
        verify(messageService, never()).remove(any(), any(), any());
        verify(aiChatGateway, never()).chatCompletionStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void regenerateWithoutPrecedingUserSendsError() {
        when(sessionService.require(1L, 7L)).thenReturn(session());
        ActorDialogueMessage a1 = msg("assistant", "孤立回复", 2L);
        // 第一次返回 [assistant]；remove 后剩余为空
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(a1), List.of());

        CaptureSink sink = new CaptureSink();
        service.regenerate(1L, 1L, 7L, null, sink);

        Map<String, Object> error = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals("error", sink.events.get(sink.events.size() - 1).getKey());
        assertTrue(String.valueOf(error.get("message")).contains("没有玩家的发言可回应"));
        verify(messageService, never()).appendAssistant(any(), any(), any(), any(), any());
    }

    // ==================== M4：用量记账 + 滚动窗口摘要触发 ====================

    @Test
    void chatRecordsUsageSceneDialogueChat() throws Exception {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("回");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any())).thenReturn(msg("assistant", "回", 5L));
        com.holzyn.actor.domain.usage.service.UsageLogService usageLogService =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        // scene=dialogue_chat 记账（供应商未返回 usage → 本地估算兜底，token 非空）
        verify(usageLogService).record(eq(1L), eq(1L), eq(1L), any(), any(),
                eq(DialogueChatService.SCENE_USAGE), any(), any(), any(), any(), any());
    }

    @Test
    void chatTriggersAutoSummaryAfterReply() {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("回");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any())).thenReturn(msg("assistant", "回", 5L));
        DialogueSummaryService summaryService = mock(DialogueSummaryService.class);
        ReflectionTestUtils.setField(service, "summaryService", summaryService);

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        // done 推送后触发 maybeSummarize（超窗才发起 AI，零开销）
        verify(summaryService).maybeSummarize(1L, 1L, 7L);
    }

    @Test
    void usageRecordingFailureIsSwallowed() {
        when(messageService.appendUser(1L, 7L, "你好")).thenReturn(msg("user", "你好", 1L));
        when(sessionService.require(1L, 7L)).thenReturn(session());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg("user", "你好", 1L)));
        when(assembler.composeSingle(eq(1L), eq(1L), any(), eq("你好"), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("回");
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendAssistant(any(), any(), any(), any(), any())).thenReturn(msg("assistant", "回", 5L));
        com.holzyn.actor.domain.usage.service.UsageLogService usageLogService =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("写库失败"))
                .when(usageLogService).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);

        CaptureSink sink = new CaptureSink();
        service.chat(1L, 1L, 7L, "你好", sink);

        // 记账失败不阻断：done 事件仍在
        assertEquals("done", sink.events.get(sink.events.size() - 1).getKey());
    }
}
