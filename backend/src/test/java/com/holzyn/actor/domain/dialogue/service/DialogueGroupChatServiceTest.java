package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈群聊回复编排单元测试（AI mock，零真实调用）。
 * <p>职责：验证——清醒成员全员依次串行流式（meta→group-member→token→group-member-done→done）、
 * 后一成员历史含前一成员回复、睡着成员跳过/全员不清醒回退、连选守卫跳过同人、
 * 无进展检测跳过不落库、单成员失败跳过继续（F2）、空成员 error、非群聊拒绝、
 * 用量记账 scene=dialogue_group_chat、轮后触发超窗摘要。</p>
 */
class DialogueGroupChatServiceTest {

    private DialogueSessionService sessionService;
    private DialogueMessageService messageService;
    private DialogueGroupService groupService;
    private DialogueContextAssembler assembler;
    private DialogueRelationService relationService;
    private ActorCharacterRepository characterRepository;
    private AiSpringChatGateway aiChatGateway;
    private DialogueGroupChatService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        messageService = mock(DialogueMessageService.class);
        groupService = mock(DialogueGroupService.class);
        assembler = mock(DialogueContextAssembler.class);
        relationService = mock(DialogueRelationService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        service = new DialogueGroupChatService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "groupService", groupService);
        ReflectionTestUtils.setField(service, "assembler", assembler);
        ReflectionTestUtils.setField(service, "relationService", relationService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);

        when(assembler.composeGroup(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DialogueContextAssembler.ComposedContext("sys", "usr"));
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");

        ActorCharacter ch1 = new ActorCharacter();
        ch1.setId(1L);
        ch1.setName("沈守拙");
        when(characterRepository.findById(1L)).thenReturn(java.util.Optional.of(ch1));
    }

    private ActorDialogueSession groupSession() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setType("group");
        s.setLocationId(10L);
        s.setLocationName("村口");
        s.setFrozenSnapshotJson("{\"world_dynamics\":\"青禾村历 0420-05-15 06时00分\"}");
        return s;
    }

    private DialogueGroupMemberVO member(Long id, String ref, String name) {
        return new DialogueGroupMemberVO(id, 7L, ref, name, "present", LocalDateTime.now());
    }

    private ActorDialogueMessage msg(Long id, String role, String ref, String content) {
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setId(id);
        m.setSessionId(7L);
        m.setRole(role);
        m.setCharacterRef(ref);
        m.setContent(content);
        m.setSeq(id.intValue());
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

    /** 按调用序流式回复的网关 mock（第 n 次调用流 text[n]）。 */
    private void streamSequence(String... texts) {
        int[] call = {0};
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(4);
            String t = texts[Math.min(call[0], texts.length - 1)];
            call[0]++;
            onToken.accept(t);
            return null;
        }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
    }

    /** 标准两成员（在场，均清醒）编排桩。 */
    private void stubTwoAwakeMembers() {
        when(groupService.members(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
        when(groupService.awakeMembers(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupChatStreamsEachAwakeMemberInOrder() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        stubTwoAwakeMembers();
        streamSequence("诸位安好", "我也在");
        when(messageService.appendGroup(eq(1L), eq(7L), eq("npc:1"), eq("诸位安好"), eq(null)))
                .thenReturn(msg(10L, "group", "npc:1", "诸位安好"));
        when(messageService.appendGroup(eq(1L), eq(7L), eq("npc:2"), eq("我也在"), eq(null)))
                .thenReturn(msg(11L, "group", "npc:2", "我也在"));

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        // 事件序：meta → 成员1(meta/token/done) → 成员2(meta/token/done) → done
        assertEquals("meta", sink.events.get(0).getKey());
        assertEquals("group-member", sink.events.get(1).getKey());
        assertTrue(sink.events.stream().anyMatch(e -> "group-member-done".equals(e.getKey())
                && "npc:1".equals(((Map<?, ?>) e.getValue()).get("characterRef"))));
        assertTrue(sink.events.stream().anyMatch(e -> "group-member-done".equals(e.getKey())
                && "npc:2".equals(((Map<?, ?>) e.getValue()).get("characterRef"))));
        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals("done", sink.events.get(sink.events.size() - 1).getKey());
        assertEquals(2, done.get("replied"));
        assertEquals(0, done.get("skipped"));
        assertEquals(0, done.get("failed"));
        verify(messageService).appendGroup(1L, 7L, "npc:1", "诸位安好", null);
        verify(messageService).appendGroup(1L, 7L, "npc:2", "我也在", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupChatPassesPreviousRepliesToNextMember() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        // 历史 = 刚落库本轮发言（剔除后为空）
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        stubTwoAwakeMembers();
        streamSequence("诸位安好", "我也在");
        when(messageService.appendGroup(any(), any(), any(), any(), any())).thenAnswer(inv ->
                msg(inv.getArgument(2).equals("npc:1") ? 10L : 11L, "group", inv.getArgument(2), inv.getArgument(3)));
        // 调用时快照历史内容（running 为可变引用，事后捕获只会看到终态）
        List<List<String>> seen = new ArrayList<>();
        when(assembler.composeGroup(any(), any(), any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<ActorDialogueMessage> h = inv.getArgument(8);
                    seen.add(h.stream().map(ActorDialogueMessage::getContent).toList());
                    return new DialogueContextAssembler.ComposedContext("sys", "usr");
                });

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        // 成员1 历史为空；成员2 历史含成员1 的回复——角色可接「对家」的话
        assertEquals(2, seen.size());
        assertEquals(0, seen.get(0).size());
        assertEquals(1, seen.get(1).size());
        assertEquals("诸位安好", seen.get(1).get(0));
    }

    @Test
    void groupChatSkipsAsleepMember() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        // members 两人，但 awake 只有 np1（np2 睡着）
        when(groupService.members(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        streamSequence("好");
        when(messageService.appendGroup(any(), any(), any(), any(), any()))
                .thenReturn(msg(10L, "group", "npc:1", "好"));

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals(1, done.get("replied"));
        verify(messageService).appendGroup(1L, 7L, "npc:1", "好", null);
        verify(messageService, never()).appendGroup(1L, 7L, "npc:2", "好", null);
    }

    @Test
    void groupChatFallsBackToAllMembersWhenAllAsleep() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        when(groupService.members(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of());
        streamSequence("好", "也");
        when(messageService.appendGroup(any(), any(), any(), any(), any())).thenAnswer(inv ->
                msg(inv.getArgument(2).equals("npc:1") ? 10L : 11L, "group", inv.getArgument(2), inv.getArgument(3)));

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals(2, done.get("replied"));
    }

    @Test
    void groupChatSkipsConsecutiveSameSpeaker() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        // 历史末尾是 npc:1 的发言（剔除刚落的 user 后仍以 npc:1 结尾）→ 连选守卫跳过
        when(messageService.listEntities(1L, 7L))
                .thenReturn(List.of(msg(1L, "group", "npc:1", "之前说的"), msg(2L, "user", null, "大家好")));
        when(groupService.members(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals(0, done.get("replied"));
        assertEquals(1, done.get("skipped"));
        verify(aiChatGateway, never()).chatCompletionStream(any(), any(), any(), any(), any(), any());
        verify(messageService, never()).appendGroup(any(), any(), any(), any(), any());
    }

    @Test
    void groupChatNearDuplicateSkipped() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        // 历史末尾（对家）内容 = 成员将生成的内容 → 无进展检测跳过
        when(messageService.listEntities(1L, 7L))
                .thenReturn(List.of(msg(1L, "group", "npc:2", "今日风大，不宜出门"), msg(2L, "user", null, "大家好")));
        when(groupService.members(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        // 成员生成的内容与上一条完全重复
        streamSequence("今日风大，不宜出门");

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals(0, done.get("replied"));
        assertEquals(1, done.get("skipped"));
        verify(messageService, never()).appendGroup(any(), any(), any(), any(), any());
    }

    @Test
    void groupChatMemberFailureContinuesRound() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        stubTwoAwakeMembers();
        // 成员1 AI 失败 → error（带 memberRef）；成员2 正常 → 整轮继续
        doThrow(new AiCallException("网关超时"))
                .doAnswer(inv -> {
                    java.util.function.Consumer<String> onToken = inv.getArgument(4);
                    onToken.accept("我接一句");
                    return null;
                }).when(aiChatGateway).chatCompletionStream(eq(1L), eq(1L), eq(null), any(), any(), any());
        when(messageService.appendGroup(any(), any(), any(), any(), any()))
                .thenReturn(msg(11L, "group", "npc:2", "我接一句"));

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        // error 事件带 memberRef=np1
        assertTrue(sink.events.stream().anyMatch(e -> "error".equals(e.getKey())
                && "npc:1".equals(((Map<?, ?>) e.getValue()).get("memberRef"))));
        Map<String, Object> done = (Map<String, Object>) sink.events.get(sink.events.size() - 1).getValue();
        assertEquals(1, done.get("replied"));
        assertEquals(1, done.get("failed"));
        verify(messageService).appendGroup(1L, 7L, "npc:2", "我接一句", null);
    }

    @Test
    void groupChatEmptyRosterSendsError() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        when(groupService.members(1L, 7L)).thenReturn(List.of());
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of());

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        Map<String, Object> error = (Map<String, Object>) sink.events.get(0).getValue();
        assertEquals("error", sink.events.get(0).getKey());
        assertTrue(String.valueOf(error.get("message")).contains("还没有成员"));
        verify(aiChatGateway, never()).chatCompletionStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void groupChatRejectsNonGroupSession() {
        ActorDialogueSession single = new ActorDialogueSession();
        single.setId(7L);
        single.setProjectId(1L);
        single.setType("single");
        when(sessionService.require(1L, 7L)).thenReturn(single);

        CaptureSink sink = new CaptureSink();
        BizException e = assertThrows(BizException.class, () -> service.groupChat(1L, 1L, 7L, "你好", sink));
        assertTrue(e.getMessage().contains("仅群聊会话"));
        verify(messageService, never()).appendUser(any(), any(), any());
    }

    @Test
    void groupChatRecordsUsageSceneGroupChat() throws Exception {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        stubTwoAwakeMembers();
        streamSequence("好", "也");
        when(messageService.appendGroup(any(), any(), any(), any(), any())).thenAnswer(inv ->
                msg(inv.getArgument(2).equals("npc:1") ? 10L : 11L, "group", inv.getArgument(2), inv.getArgument(3)));
        UsageLogService usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        verify(usageLogService, times(2)).record(eq(1L), eq(1L), any(), any(), any(),
                eq(DialogueGroupChatService.SCENE_USAGE_GROUP), any(), any(), any(), any(), any());
    }

    @Test
    void groupChatTriggersSummaryAfterRound() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        stubTwoAwakeMembers();
        streamSequence("好", "也");
        when(messageService.appendGroup(any(), any(), any(), any(), any())).thenAnswer(inv ->
                msg(inv.getArgument(2).equals("npc:1") ? 10L : 11L, "group", inv.getArgument(2), inv.getArgument(3)));
        DialogueSummaryService summaryService = mock(DialogueSummaryService.class);
        ReflectionTestUtils.setField(service, "summaryService", summaryService);

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        verify(summaryService).maybeSummarize(1L, 1L, 7L);
    }

    @Test
    void groupChatUsageFailureIsSwallowed() {
        when(messageService.appendUser(1L, 7L, "大家好")).thenReturn(msg(1L, "user", null, "大家好"));
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "大家好")));
        when(groupService.members(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        when(groupService.awakeMembers(1L, 7L)).thenReturn(List.of(member(1L, "npc:1", "沈守拙")));
        streamSequence("好");
        when(messageService.appendGroup(any(), any(), any(), any(), any()))
                .thenReturn(msg(10L, "group", "npc:1", "好"));
        UsageLogService usageLogService = mock(UsageLogService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("写库失败"))
                .when(usageLogService).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);

        CaptureSink sink = new CaptureSink();
        service.groupChat(1L, 1L, 7L, "大家好", sink);

        // 记账失败不阻断：整轮 done 仍在
        assertEquals("done", sink.events.get(sink.events.size() - 1).getKey());
    }
}
