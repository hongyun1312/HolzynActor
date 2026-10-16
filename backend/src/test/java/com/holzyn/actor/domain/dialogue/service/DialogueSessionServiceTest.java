package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueMessageRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈会话服务单元测试（纯本地 DB + 冻结服务 mock，零 AI）。
 * <p>职责：验证——创建（单聊校验 + 冻结快照落库 + 冻结服务缺失降级）、列表/详情/重命名/关闭、
 * 项目隔离（跨项目取会话抛 404）、角色归属校验（非本项目角色 404）。</p>
 */
class DialogueSessionServiceTest {

    private ActorDialogueSessionRepository sessionRepository;
    private ActorDialogueMessageRepository messageRepository;
    private ActorCharacterRepository characterRepository;
    private DialogueFreezeService freezeService;
    private DialogueSessionService service;

    private final List<ActorDialogueSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ActorDialogueSessionRepository.class);
        messageRepository = mock(ActorDialogueMessageRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        freezeService = mock(DialogueFreezeService.class);

        when(sessionRepository.save(any(ActorDialogueSession.class))).thenAnswer(inv -> {
            ActorDialogueSession s = inv.getArgument(0);
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
        when(messageRepository.countBySessionId(any())).thenAnswer(inv ->
                sessions.stream().filter(s -> inv.getArgument(0).equals(s.getId())).count() * 0L);
        service = new DialogueSessionService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "freezeService", freezeService);
    }

    private void stubCharacter(Long id, String name) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setName(name);
        ch.setProjectId(1L);
        when(characterRepository.findByIdAndProjectId(id, 1L)).thenReturn(Optional.of(ch));
        when(characterRepository.findById(id)).thenReturn(Optional.of(ch));
    }

    private DialogueFreezeService.FreezeSnapshot snapshot() {
        return new DialogueFreezeService.FreezeSnapshot(13044175200L, "青禾村历 0420-05-15 06时00分 · 初夏季 · 天气晴",
                "沈守拙", "村东水田", "平静", List.of("npc:2"), true);
    }

    @Test
    void createSinglePersistsFrozenSnapshot() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", null);
        assertEquals("single", vo.type());
        assertEquals("npc:1", vo.characterRef());
        assertEquals(13044175200L, vo.frozenGameSecond());
        assertNotNull(vo.frozenTimeText());
        assertTrue(vo.frozenTimeText().contains("青禾村历"));
        assertEquals(1, sessions.size());
    }

    @Test
    void createSingleRequiresCharacterRef() {
        assertThrows(BizException.class, () -> service.create(1L, 1L, "single", null, null));
        assertThrows(BizException.class, () -> service.create(1L, 1L, "single", "  ", null));
    }

    @Test
    void createInvalidTypeRejected() {
        stubCharacter(1L, "沈守拙");
        assertThrows(BizException.class, () -> service.create(1L, 1L, "chat", "npc:1", null));
    }

    @Test
    void createCharacterNotInProject404() {
        when(characterRepository.findByIdAndProjectId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.create(1L, 1L, "single", "npc:99", null));
        verify(freezeService, never()).freeze(anyLong(), anyLong(), any());
    }

    @Test
    void createWithNullFreezeServiceStillWorks() {
        stubCharacter(1L, "沈守拙");
        ReflectionTestUtils.setField(service, "freezeService", null);
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", "标题");
        assertNull(vo.frozenGameSecond());
        assertEquals("标题", vo.title());
        assertEquals(1, sessions.size());
    }

    @Test
    void listReturnsOrderedWithCharacterName() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        service.create(1L, 1L, "single", "npc:1", "会话一");
        service.create(1L, 1L, "single", "npc:1", "会话二");
        List<DialogueSessionVO> list = service.list(1L);
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(v -> "沈守拙".equals(v.characterName())));
    }

    @Test
    void getThrows404CrossProject() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", null);
        assertThrows(BizException.class, () -> service.get(97L, vo.id()));
    }

    @Test
    void renameUpdatesTitle() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", "旧标题");
        service.rename(1L, vo.id(), "新标题");
        assertEquals("新标题", service.get(1L, vo.id()).title());
        assertThrows(BizException.class, () -> service.rename(1L, vo.id(), "  "));
    }

    @Test
    void closeSetsStatus() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", null);
        service.close(1L, vo.id());
        assertEquals("closed", service.get(1L, vo.id()).status());
    }

    // ==================== M6 打磨：会话搜索 + 沉淀版本留痕 ====================

    @Test
    void searchByTitleMatchesOnlyKeyword() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        service.create(1L, 1L, "single", "npc:1", "河边散步");
        service.create(1L, 1L, "single", "npc:1", "茶馆闲聊");
        List<DialogueSessionVO> hit = service.search(1L, "茶馆");
        assertEquals(1, hit.size());
        assertEquals("茶馆闲聊", hit.get(0).title());
    }

    @Test
    void searchByCharacterNameMatches() {
        stubCharacter(1L, "沈守拙");
        stubCharacter(2L, "林听竹");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:2"))).thenReturn(snapshot());
        service.create(1L, 1L, "single", "npc:1", "会话一");
        service.create(1L, 1L, "single", "npc:2", "会话二");
        // 按角色名搜索（characterRepository.findById 解析）
        List<DialogueSessionVO> hit = service.search(1L, "林听竹");
        assertEquals(1, hit.size());
        assertEquals("npc:2", hit.get(0).characterRef());
    }

    @Test
    void searchBlankKeywordReturnsAll() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        service.create(1L, 1L, "single", "npc:1", "会话一");
        service.create(1L, 1L, "single", "npc:1", "会话二");
        assertEquals(2, service.search(1L, "  ").size());
        assertEquals(2, service.search(1L, null).size());
    }

    @Test
    void searchNoMatchReturnsEmpty() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        service.create(1L, 1L, "single", "npc:1", "河边散步");
        assertTrue(service.search(1L, "不存在的关键字").isEmpty());
    }

    @Test
    void markConsolidatedSetsSessionVersion() {
        stubCharacter(1L, "沈守拙");
        when(freezeService.freeze(eq(1L), eq(1L), eq("npc:1"))).thenReturn(snapshot());
        DialogueSessionVO vo = service.create(1L, 1L, "single", "npc:1", null);
        service.markConsolidated(1L, vo.id(), 5);
        assertEquals(5, sessions.get(0).getPlayerRelationVersion());
        service.markConsolidated(1L, vo.id(), null);
        assertEquals(0, sessions.get(0).getPlayerRelationVersion());
    }
}
