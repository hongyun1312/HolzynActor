package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorTalkWorldbook;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorTalkWorldbookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对谈世界书服务单元测试（零 AI）。
 * <p>职责：验证——upsert 新建/更新（同会话同关键词唯一）、list 按 priority 升序、
 * 关键词命中扫描（最近消息命中/停用跳过/priority 排序/上限/去重）、世界书片段渲染、
 * 归属校验（会话/条目 404）。</p>
 */
class DialogueWorldbookServiceTest {

    private ActorDialogueSessionRepository sessionRepository;
    private ActorTalkWorldbookRepository worldbookRepository;
    private DialogueWorldbookService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ActorDialogueSessionRepository.class);
        worldbookRepository = mock(ActorTalkWorldbookRepository.class);
        service = new DialogueWorldbookService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "worldbookRepository", worldbookRepository);

        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        when(sessionRepository.findByIdAndProjectId(7L, 1L)).thenReturn(Optional.of(s));
    }

    private ActorTalkWorldbook entry(Long id, String keyword, String content, boolean enabled, int priority) {
        ActorTalkWorldbook e = new ActorTalkWorldbook();
        e.setId(id);
        e.setSessionId(7L);
        e.setProjectId(1L);
        e.setKeyword(keyword);
        e.setContent(content);
        e.setEnabled(enabled);
        e.setPriority(priority);
        return e;
    }

    @Test
    void upsertCreatesNewEntry() {
        when(worldbookRepository.findBySessionIdAndKeyword(7L, "灵脉")).thenReturn(Optional.empty());
        when(worldbookRepository.save(any())).thenAnswer(inv -> {
            ActorTalkWorldbook e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        var vo = service.upsert(1L, 7L, " 灵脉 ", "灵脉是青禾村的灵气源头", null, 2);
        assertEquals("灵脉", vo.keyword());
        assertEquals("灵脉是青禾村的灵气源头", vo.content());
        assertEquals(Boolean.TRUE, vo.enabled());
        assertEquals(2, vo.priority());
        assertEquals(7L, vo.sessionId());
        assertEquals(1L, vo.projectId());
    }

    @Test
    void upsertUpdatesExistingEntry() {
        ActorTalkWorldbook existing = entry(5L, "灵脉", "旧内容", false, 1);
        when(worldbookRepository.findBySessionIdAndKeyword(7L, "灵脉")).thenReturn(Optional.of(existing));
        when(worldbookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var vo = service.upsert(1L, 7L, "灵脉", "新内容", true, 3);
        assertEquals(5L, vo.id());
        assertEquals("新内容", vo.content());
        assertEquals(Boolean.TRUE, vo.enabled());
        assertEquals(3, vo.priority());
    }

    @Test
    void upsertRejectsBlankKeyword() {
        assertThrows(BizException.class, () -> service.upsert(1L, 7L, "  ", "内容", null, 0));
        verify(worldbookRepository, never()).save(any());
    }

    @Test
    void listSortedByPriority() {
        // 仓库 ORDER BY priority ASC,id ASC（A 优先 0，B 优先 1）
        when(worldbookRepository.findBySessionIdOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "A", "a", false, 0), entry(2L, "B", "b", true, 1)));
        var list = service.list(1L, 7L);
        assertEquals(2, list.size());
        assertEquals("A", list.get(0).keyword());
        assertEquals("B", list.get(1).keyword());
        assertFalse(list.get(0).enabled()); // 管理视图含停用条目
    }

    @Test
    void keywordHitsMatchesEnabledEntriesInOrderAndCaps() {
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "灵脉", "灵脉知识", true, 0),
                        entry(2L, "茶馆", "茶馆知识", true, 1),
                        entry(3L, "灵石", "灵石知识", true, 2)));
        List<String> recent = List.of("灵脉在哪？", "去茶馆坐坐");
        var hits = service.keywordHits(7L, recent, 3);
        assertEquals(2, hits.size());
        assertEquals("灵脉", hits.get(0).getKeyword());
        assertEquals("茶馆", hits.get(1).getKeyword());
    }

    @Test
    void keywordHitsSkipsDisabledAndAppliesLimit() {
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "灵脉", "灵脉知识", true, 0),
                        entry(2L, "茶馆", "茶馆知识", true, 1)));
        List<String> recent = List.of("灵脉和茶馆都提到了");
        var hits = service.keywordHits(7L, recent, 1);
        assertEquals(1, hits.size());
        assertEquals("灵脉", hits.get(0).getKeyword());
    }

    @Test
    void keywordHitsNoMatchReturnsEmpty() {
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "灵脉", "灵脉知识", true, 0)));
        assertTrue(service.keywordHits(7L, List.of("今天天气不错"), 3).isEmpty());
        assertTrue(service.keywordHits(7L, List.of(), 3).isEmpty());
        assertTrue(service.keywordHits(7L, null, 3).isEmpty());
    }

    @Test
    void keywordHitsSkipsBlankKeywordsAndDeduplicates() {
        ActorTalkWorldbook blank = entry(1L, "  ", "空白关键词", true, 0);
        ActorTalkWorldbook dup = entry(2L, "灵脉", "灵脉知识", true, 0);
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(blank, dup, dup));
        var hits = service.keywordHits(7L, List.of("灵脉"), 3);
        assertEquals(1, hits.size());
        assertEquals("灵脉", hits.get(0).getKeyword());
    }

    @Test
    void worldbookContextRendersSourceAnnotatedSegments() {
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "灵脉", "灵脉是青禾村的灵气源头", true, 0)));
        String ctx = service.worldbookContext(7L, List.of("灵脉在哪里"), null);
        assertTrue(ctx.contains("【世界书片段】"));
        assertTrue(ctx.contains("〔关键词「灵脉」〕"));
        assertTrue(ctx.contains("灵脉是青禾村的灵气源头"));
    }

    @Test
    void worldbookContextEmptyWhenNoHits() {
        when(worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(7L))
                .thenReturn(List.of(entry(1L, "灵脉", "灵脉知识", true, 0)));
        assertEquals("", service.worldbookContext(7L, List.of("天气"), null));
    }

    @Test
    void removeRequiresSessionOwnership() {
        when(worldbookRepository.findById(1L)).thenReturn(Optional.of(entry(1L, "灵脉", "x", true, 0)));
        service.remove(1L, 7L, 1L);
        verify(worldbookRepository).delete(any());
        // 条目属于其他会话 → 404
        ActorTalkWorldbook other = entry(99L, "别家", "x", true, 0);
        other.setSessionId(999L);
        other.setProjectId(2L);
        when(worldbookRepository.findById(99L)).thenReturn(Optional.of(other));
        assertThrows(BizException.class, () -> service.remove(1L, 7L, 99L));
        verify(worldbookRepository, never()).delete(other);
    }

    @Test
    void crudRequiresSessionExistence() {
        when(sessionRepository.findByIdAndProjectId(7L, 1L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.list(1L, 7L));
        assertThrows(BizException.class, () -> service.upsert(1L, 7L, "k", "v", null, 0));
    }
}
