package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantMessage;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantSession;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantMessageRepository;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 助手会话服务单元测试（纯本地 DB，零 AI）。
 * <p>职责：验证——创建（默认叙事模式）、首条用户消息生成标题、模式更新、消息追加与读取、
 * 删除级联、项目隔离（跨项目取会话抛 404）。</p>
 */
class AssistantSessionServiceTest {

    private ActorAssistantSessionRepository sessionRepository;
    private ActorAssistantMessageRepository messageRepository;
    private AssistantSessionService service;

    private final List<ActorAssistantSession> sessions = new ArrayList<>();
    private final List<ActorAssistantMessage> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionRepository = Mockito.mock(ActorAssistantSessionRepository.class);
        messageRepository = Mockito.mock(ActorAssistantMessageRepository.class);
        when(sessionRepository.save(any(ActorAssistantSession.class))).thenAnswer(inv -> {
            ActorAssistantSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId((long) (sessions.size() + 1));
                sessions.add(s);
            }
            return s;
        });
        when(sessionRepository.findByIdAndProjectId(any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long pid = inv.getArgument(1);
            return sessions.stream().filter(s -> id.equals(s.getId()) && pid.equals(s.getProjectId())).findFirst();
        });
        when(sessionRepository.findByProjectIdOrderByUpdatedAtDescIdDesc(any())).thenAnswer(inv ->
                sessions.stream().filter(s -> inv.getArgument(0).equals(s.getProjectId())).toList());
        when(messageRepository.save(any(ActorAssistantMessage.class))).thenAnswer(inv -> {
            ActorAssistantMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (messages.size() + 1));
                messages.add(m);
            }
            return m;
        });
        when(messageRepository.findBySessionIdOrderByIdAsc(any())).thenAnswer(inv -> messages.stream()
                .filter(m -> inv.getArgument(0).equals(m.getSessionId())).toList());
        when(messageRepository.countBySessionId(any())).thenAnswer(inv -> messages.stream()
                .filter(m -> inv.getArgument(0).equals(m.getSessionId())).count());
        when(messageRepository.findBySessionIdOrderByIdDesc(any())).thenAnswer(inv -> {
            Long sid = inv.getArgument(0);
            List<ActorAssistantMessage> list = messages.stream()
                    .filter(m -> sid.equals(m.getSessionId())).toList();
            return list.reversed();
        });
        Mockito.doAnswer(inv -> {
            messages.removeIf(m -> inv.getArgument(0).equals(m.getSessionId()));
            return null;
        }).when(messageRepository).deleteBySessionId(any());
        Mockito.doAnswer(inv -> {
            ActorAssistantSession s = inv.getArgument(0);
            sessions.removeIf(x -> x.getId().equals(s.getId()));
            return null;
        }).when(sessionRepository).delete(any(ActorAssistantSession.class));
        service = new AssistantSessionService(sessionRepository, messageRepository);
    }

    @Test
    void createDefaultsToNarrative() {
        var vo = service.create(1L, 1L, null, null);
        assertEquals("narrative", vo.defaultMode());
        assertEquals(1L, vo.projectId());
        assertNotNull(vo.id());
    }

    @Test
    void createAcceptsFactMode() {
        var vo = service.create(1L, 1L, "测试会话", "fact");
        assertEquals("fact", vo.defaultMode());
        assertEquals("测试会话", vo.title());
    }

    @Test
    void firstUserMessageGeneratesTitle() {
        var session = service.create(1L, 1L, null, null);
        service.appendUserMessage(1L, session.id(), "沈砚秋现在在做什么呢？");
        var got = service.get(1L, session.id());
        assertTrue(got.title().contains("沈砚秋"));
        assertEquals(1L, got.messageCount());
    }

    @Test
    void appendAssistantMessagePersistsStructuredPayloads() {
        var session = service.create(1L, 1L, "t", "narrative");
        service.appendUserMessage(1L, session.id(), "q");
        service.appendAssistantMessage(1L, session.id(), "回答", "fact", "[card]",
                "[sources]", "[tools]", "[follows]");
        var list = service.messages(1L, session.id());
        assertEquals(2, list.size());
        assertEquals("fact", list.get(1).mode());
        assertEquals("[card]", list.get(1).cardJson());
        assertEquals("[follows]", list.get(1).quickFollowsJson());
    }

    @Test
    void updateDefaultMode() {
        var session = service.create(1L, 1L, "t", "narrative");
        service.updateDefaultMode(1L, session.id(), "fact");
        assertEquals("fact", service.get(1L, session.id()).defaultMode());
    }

    @Test
    void deleteCascadesMessages() {
        var session = service.create(1L, 1L, "t", "narrative");
        service.appendUserMessage(1L, session.id(), "q");
        service.delete(1L, session.id());
        assertEquals(0, messages.size());
        assertEquals(0, sessions.size());
    }

    @Test
    void projectIsolationThrows404() {
        var session = service.create(1L, 1L, "t", "narrative");
        assertThrows(BizException.class, () -> service.get(97L, session.id()));
    }

    @Test
    void recentMessagesWindow() {
        var session = service.create(1L, 1L, "t", "narrative");
        for (int i = 0; i < 5; i++) {
            service.appendUserMessage(1L, session.id(), "q" + i);
            service.appendAssistantMessage(1L, session.id(), "a" + i, "narrative", null, null, null, null);
        }
        var recent = service.recentMessages(session.id(), 4);
        assertEquals(4, recent.size());
        // 最近 N 条按时间正序：q3,a3,q4,a4
        assertEquals("q3", recent.get(0).getContent());
        assertEquals("a4", recent.get(3).getContent());
    }

    @Test
    void blankQuestionRejected() {
        var session = service.create(1L, 1L, "t", "narrative");
        assertThrows(BizException.class, () -> service.appendUserMessage(1L, session.id(), "  "));
    }

    @Test
    void confirmModeDefaultsToStandard() {
        var session = service.create(1L, 1L, "t", "narrative");
        assertEquals("standard", service.confirmModeOf(1L, session.id()));
        assertEquals("standard", service.get(1L, session.id()).confirmMode());
    }

    @Test
    void updateConfirmModePersists() {
        var session = service.create(1L, 1L, "t", "narrative");
        service.updateConfirmMode(1L, session.id(), "trust");
        assertEquals("trust", service.confirmModeOf(1L, session.id()));
        service.updateConfirmMode(1L, session.id(), "invalid");
        assertEquals("standard", service.confirmModeOf(1L, session.id()));
    }

    @Test
    void approvedOpsTrackLowRiskOperations() {
        var session = service.create(1L, 1L, "t", "narrative");
        assertTrue(!service.isApprovedOp(1L, session.id(), "talk"));
        service.markApprovedOp(1L, session.id(), "talk");
        service.markApprovedOp(1L, session.id(), "weather");
        assertTrue(service.isApprovedOp(1L, session.id(), "talk"));
        assertTrue(service.isApprovedOp(1L, session.id(), "weather"));
        assertTrue(!service.isApprovedOp(1L, session.id(), "event"));
        // 重复放行去重
        service.markApprovedOp(1L, session.id(), "talk");
        var got = service.get(1L, session.id());
        assertTrue(got.confirmMode() != null);
    }
}
