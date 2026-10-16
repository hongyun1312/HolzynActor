package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueMessageRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueMessageVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈消息服务单元测试（纯本地 DB，零 AI）。
 * <p>职责：验证——玩家/角色消息追加（seq 递增 + 角色引用）、列表 seq 序、会话隔离 404、
 * 空白发言拒绝、编辑（仅玩家消息 + 作废后续链）、删除（含目标截断）、换一条（swipe 候选轮换）。</p>
 */
class DialogueMessageServiceTest {

    private ActorDialogueMessageRepository messageRepository;
    private ActorDialogueSessionRepository sessionRepository;
    private ActorCharacterRepository characterRepository;
    private DialogueMessageService service;

    private final List<ActorDialogueSession> sessions = new ArrayList<>();
    private final List<ActorDialogueMessage> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        messageRepository = mock(ActorDialogueMessageRepository.class);
        sessionRepository = mock(ActorDialogueSessionRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        service = new DialogueMessageService();
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);

        when(sessionRepository.findByIdAndProjectId(any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long pid = inv.getArgument(1);
            return sessions.stream().filter(s -> id.equals(s.getId()) && pid.equals(s.getProjectId())).findFirst();
        });
        when(sessionRepository.save(any(ActorDialogueSession.class))).thenAnswer(inv -> {
            ActorDialogueSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId((long) (sessions.size() + 1));
                sessions.add(s);
            }
            return s;
        });
        when(messageRepository.save(any(ActorDialogueMessage.class))).thenAnswer(inv -> {
            ActorDialogueMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (messages.size() + 1));
                messages.add(m);
            }
            return m;
        });
        when(messageRepository.findBySessionIdOrderByIdAsc(any())).thenAnswer(inv ->
                messages.stream().filter(m -> inv.getArgument(0).equals(m.getSessionId())).toList());
        when(messageRepository.findByIdAndSessionId(any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long sid = inv.getArgument(1);
            return messages.stream()
                    .filter(m -> id.equals(m.getId()) && sid.equals(m.getSessionId()))
                    .findFirst();
        });
        when(messageRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(any(), any())).thenAnswer(inv -> {
            Long sid = inv.getArgument(0);
            Long id = inv.getArgument(1);
            return messages.stream()
                    .filter(m -> sid.equals(m.getSessionId()) && m.getId() != null && m.getId() > id)
                    .toList();
        });
        when(messageRepository.findBySessionIdAndIdGreaterThanEqualOrderByIdAsc(any(), any())).thenAnswer(inv -> {
            Long sid = inv.getArgument(0);
            Long id = inv.getArgument(1);
            return messages.stream()
                    .filter(m -> sid.equals(m.getSessionId()) && m.getId() != null && m.getId() >= id)
                    .toList();
        });
        when(messageRepository.countBySessionId(any())).thenAnswer(inv ->
                messages.stream().filter(m -> inv.getArgument(0).equals(m.getSessionId())).count());
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character(1L, "沈守拙")));
    }

    private ActorCharacter character(Long id, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private ActorDialogueSession session(Long id, Long projectId) {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(id);
        s.setProjectId(projectId);
        sessions.add(s);
        return s;
    }

    @Test
    void appendUserAssignsSeq() {
        session(1L, 1L);
        ActorDialogueMessage m1 = service.appendUser(1L, 1L, "你好");
        ActorDialogueMessage m2 = service.appendUser(1L, 1L, "在吗");
        assertEquals(1, m1.getSeq());
        assertEquals(2, m2.getSeq());
        assertEquals("user", m2.getRole());
    }

    @Test
    void appendAssistantAssignsSeqAndCharacterRef() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        ActorDialogueMessage m = service.appendAssistant(1L, 1L, "npc:1", "我很好", "{\"kind\":\"greeting\"}");
        assertEquals(2, m.getSeq());
        assertEquals("assistant", m.getRole());
        assertEquals("npc:1", m.getCharacterRef());
        assertEquals("{\"kind\":\"greeting\"}", m.getMetaJson());
    }

    @Test
    void listOrderedBySeqWithCharacterName() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        service.appendAssistant(1L, 1L, "npc:1", "我很好", null);
        List<DialogueMessageVO> list = service.list(1L, 1L);
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).seq());
        assertEquals(2, list.get(1).seq());
        assertEquals("沈守拙", list.get(1).characterName());
        assertEquals("user", list.get(0).role());
    }

    @Test
    void sessionIsolationThrows404() {
        session(1L, 1L);
        assertThrows(BizException.class, () -> service.list(97L, 1L));
        assertThrows(BizException.class, () -> service.appendUser(97L, 1L, "你好"));
    }

    @Test
    void blankUserRejected() {
        session(1L, 1L);
        assertThrows(BizException.class, () -> service.appendUser(1L, 1L, "   "));
    }

    @Test
    void listEntitiesReturnsAll() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        List<ActorDialogueMessage> entities = service.listEntities(1L, 1L);
        assertEquals(1, entities.size());
        assertTrue(entities.get(0).getContent().contains("你好"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void editUserMessageUpdatesAndTruncatesTail() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");          // id=1
        service.appendAssistant(1L, 1L, "npc:1", "早", null); // id=2
        service.appendUser(1L, 1L, "在吗");          // id=3

        DialogueMessageVO vo = service.edit(1L, 1L, 1L, "改过的发言");

        assertEquals("改过的发言", vo.content());
        assertEquals("user", vo.role());
        ArgumentCaptor<List<ActorDialogueMessage>> tailCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).deleteAll(tailCaptor.capture());
        List<ActorDialogueMessage> tail = tailCaptor.getValue();
        // 作废后续链：仅删目标之后（id=2/3），目标本身保留
        assertEquals(2, tail.size());
        assertEquals(2L, tail.get(0).getId());
        assertEquals(3L, tail.get(1).getId());
    }

    @Test
    void editAssistantMessageRejected() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        service.appendAssistant(1L, 1L, "npc:1", "早", null);
        BizException e = assertThrows(BizException.class, () -> service.edit(1L, 1L, 2L, "改"));
        assertTrue(e.getMessage().contains("只能编辑玩家的发言"));
    }

    @Test
    void editMissingMessageThrows404() {
        session(1L, 1L);
        BizException e = assertThrows(BizException.class, () -> service.edit(1L, 1L, 99L, "改"));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeTruncatesFromTarget() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");          // id=1
        service.appendAssistant(1L, 1L, "npc:1", "早", null); // id=2
        service.appendUser(1L, 1L, "在吗");          // id=3

        service.remove(1L, 1L, 2L);

        ArgumentCaptor<List<ActorDialogueMessage>> tailCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).deleteAll(tailCaptor.capture());
        List<ActorDialogueMessage> tail = tailCaptor.getValue();
        // 删除目标本身 + 其后（id=2/3）
        assertEquals(2, tail.size());
        assertEquals(2L, tail.get(0).getId());
        assertEquals(3L, tail.get(1).getId());
    }

    @Test
    void swipeRotatesToNextCandidate() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        service.appendAssistant(1L, 1L, "npc:1", "当前回复", "{\"swipes\":[\"备选一\",\"备选二\"]}");

        DialogueMessageVO vo = service.swipe(1L, 1L, 2L);

        // 轮换：content=下一候选，旧内容入队尾
        assertEquals("备选一", vo.content());
        assertTrue(vo.metaJson().contains("备选二"));
        assertTrue(vo.metaJson().contains("当前回复"));
        assertTrue(vo.metaJson().contains("swipes"));
    }

    @Test
    void swipeWithoutCandidatesRejected() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        service.appendAssistant(1L, 1L, "npc:1", "回复", null);
        BizException e = assertThrows(BizException.class, () -> service.swipe(1L, 1L, 2L));
        assertTrue(e.getMessage().contains("没有可切换的备选回复"));
    }

    @Test
    void swipeNonAssistantRejected() {
        session(1L, 1L);
        service.appendUser(1L, 1L, "你好");
        BizException e = assertThrows(BizException.class, () -> service.swipe(1L, 1L, 1L));
        assertTrue(e.getMessage().contains("只有角色回复可以换一条"));
    }
}
