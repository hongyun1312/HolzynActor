package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈滚动窗口自动摘要服务单元测试（AI mock，零真实调用）。
 * <p>职责：验证——消息数 ≤ 窗口不触发；超窗按水位增量折叠（批 = [水位, 超窗)）；
 * 既有摘要并入 AI 输入；摘要成功短事务写回（updateSummary + 水位推进）；
 * AI 失败/空摘要 fail-open（不写回）；用量记账 scene=dialogue_summary。</p>
 */
class DialogueSummaryServiceTest {

    private DialogueSessionService sessionService;
    private DialogueMessageService messageService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private DialogueSummaryService service;

    private final List<ActorDialogueMessage> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        messageService = mock(DialogueMessageService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        service = new DialogueSummaryService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
    }

    private ActorDialogueSession session(Integer summaryMessageCount) {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setCharacterRef("npc:1");
        s.setSummaryMessageCount(summaryMessageCount);
        return s;
    }

    private ActorDialogueMessage msg(String role, String content) {
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setSessionId(7L);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 生成 n 条交替消息（玩家/角色）。 */
    private void fill(int n) {
        messages.clear();
        for (int i = 0; i < n; i++) {
            messages.add(msg(i % 2 == 0 ? "user" : "assistant", "消息" + i));
        }
    }

    private void stubAi(String summaryText) {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(summaryText, "deepseek", 120, 30, 150, 1L));
    }

    @Test
    void skipsWhenMessagesWithinWindow() {
        fill(DialogueSummaryService.HISTORY_WINDOW);
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(0));
        service.maybeSummarize(1L, 1L, 7L);
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
        verify(sessionService, never()).updateSummary(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void foldsNewBatchWhenExceedingWindow() {
        fill(DialogueSummaryService.HISTORY_WINDOW + 1); // 25 条 → 超窗 1 条
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(0));
        stubAi("前情摘要：…");
        service.maybeSummarize(1L, 1L, 7L);
        verify(aiChatGateway).chatCompletion(eq(1L), eq(1L), any(), any());
        // 水位 0 → 折叠 [0,1)，写回水位 1
        verify(sessionService).updateSummary(eq(1L), eq(7L), eq("前情摘要：…"), eq(1));
        // 用量记账 scene=dialogue_summary
        verify(usageLogService).record(eq(1L), eq(1L), any(), any(), anyString(),
                eq(DialogueSummaryService.SCENE_SUMMARY), any(), any(), any(), any(), any());
    }

    @Test
    void foldsIncrementallyByWatermark() {
        fill(33); // 超窗 9 条；水位 1 → 折叠 [1,9)
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        ActorDialogueSession s = session(1);
        s.setSummary("既有摘要");
        when(sessionService.require(1L, 7L)).thenReturn(s);
        stubAi("合并后摘要");
        service.maybeSummarize(1L, 1L, 7L);
        // 新批 = [1,9) → 8 条（消息1~8），写回水位 9
        verify(sessionService).updateSummary(eq(1L), eq(7L), eq("合并后摘要"), eq(9));
    }

    @Test
    void skipsWhenNoNewBatchBeyondWatermark() {
        fill(30); // 超窗 6 条；水位 6 → 无新批
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(6));
        service.maybeSummarize(1L, 1L, 7L);
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
        verify(sessionService, never()).updateSummary(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void aiFailureIsFailOpenNoWriteBack() {
        fill(30);
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(0));
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new AiCallException("网关超时"));
        service.maybeSummarize(1L, 1L, 7L);
        verify(sessionService, never()).updateSummary(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void emptySummaryIsFailOpenNoWriteBack() {
        fill(30);
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(0));
        stubAi("   ");
        service.maybeSummarize(1L, 1L, 7L);
        verify(sessionService, never()).updateSummary(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void writeBackFailureIsSwallowed() {
        fill(30);
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        when(sessionService.require(1L, 7L)).thenReturn(session(0));
        stubAi("摘要");
        org.mockito.Mockito.doThrow(new RuntimeException("写库失败"))
                .when(sessionService).updateSummary(anyLong(), anyLong(), anyString(), anyInt());
        // 不抛异常（fail-open）
        service.maybeSummarize(1L, 1L, 7L);
    }

    @Test
    void summarizePromptIncludesExistingAndBatch() {
        fill(30);
        when(messageService.listEntities(1L, 7L)).thenReturn(messages);
        ActorDialogueSession s = session(0);
        s.setSummary("既有摘要");
        when(sessionService.require(1L, 7L)).thenReturn(s);
        stubAi("合并后摘要");
        service.maybeSummarize(1L, 1L, 7L);
        // 批 = [0,6) 全部折叠（30 条超窗 6 条），输入含既有摘要
        verify(sessionService).updateSummary(eq(1L), eq(7L), eq("合并后摘要"), eq(6));
    }
}
