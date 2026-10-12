package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.langchain4j.RagSearchService;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRetrievalService 检索委托单元测试（AI 接入层重构 M5，2026-09-XX）。
 * <p>职责：验证 M5 简化壳（A/B 开关与旧余弦路径已随 EmbeddingService/旧 embedding 列物理删除）——
 * ①直接委托 {@link RagSearchService}（唯一向量检索路径），命中直接返回（不查库）；
 * ②新路径命中为空 → 文本关键词检索降级；③新路径异常 → 文本关键词检索降级（不阻断）；
 * ④无知识文档 → 文本降级返回空。</p>
 */
class KnowledgeRetrievalServiceDelegationTest {

    private final ActorKnowledgeDocRepository docRepository = mock(ActorKnowledgeDocRepository.class);
    private final ModelApiService modelApiService = mock(ModelApiService.class);
    private final RagSearchService ragSearchService = mock(RagSearchService.class);

    private final KnowledgeRetrievalService svc =
            new KnowledgeRetrievalService(docRepository, modelApiService, ragSearchService);

    /** 构造某文档实体（项目级） */
    private static ActorKnowledgeDoc doc(long id, String title, String content) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setProjectId(2L);
        d.setCharacterId(null);
        d.setTitle(title);
        d.setContent(content);
        return d;
    }

    /**
     * 直接委托 RagSearchService，命中直接返回（不查库/不 resolve embedding）。
     * <p>2026-09-29_04 适配：多路召回开启时命中路径会查库构建话题词索引（知识检索质量深化），
     * 本用例验证的是「纯委托」语义 → 显式关闭多路召回后再断言不查库。</p>
     */
    @Test
    void delegatesToRagAndReturnsHits() {
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "multiRecallEnabled", false);
        when(ragSearchService.search(1L, 2L, 3L, "查询", 3))
                .thenReturn(List.of(new KnowledgeHit(10L, "世界规则", "禁灵咒：本世界禁止对人类使用。", 0.9, null)));
        List<KnowledgeHit> hits = svc.search(1L, 2L, 3L, "查询", 3);
        assertEquals(1, hits.size());
        assertEquals(10L, hits.get(0).docId());
        // 命中不查库（文本降级才查）
        verify(docRepository, never()).findByProjectIdOrderByIdAsc(any());
    }

    /**
     * 新路径未命中 → 文本关键词降级（保留现状语义，返回文本命中）。
     */
    @Test
    void fallsBackToTextWhenRagEmpty() {
        when(ragSearchService.search(1L, 2L, null, "禁灵咒", 3)).thenReturn(List.of());
        ActorKnowledgeDoc d = doc(10L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。违反者将受到严惩。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        List<KnowledgeHit> hits = svc.search(1L, 2L, null, "禁灵咒", 3);
        assertEquals(1, hits.size(), "新路径空命中 → 文本降级应返回文本命中");
        assertEquals(10L, hits.get(0).docId());
    }

    /**
     * 新路径异常 → 文本关键词降级（不阻断）。
     */
    @Test
    void fallsBackToTextWhenRagThrows() {
        when(ragSearchService.search(1L, 2L, null, "禁灵咒", 3))
                .thenThrow(new RuntimeException("langchain4j down"));
        ActorKnowledgeDoc d = doc(10L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。违反者将受到严惩。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        List<KnowledgeHit> hits = svc.search(1L, 2L, null, "禁灵咒", 3);
        assertEquals(1, hits.size(), "新路径异常 → 文本降级应返回文本命中");
        assertEquals(10L, hits.get(0).docId());
    }

    /**
     * 无知识文档：文本降级返回空（Rag 空命中 + 库空）。
     */
    @Test
    void noDocsReturnsEmpty() {
        when(ragSearchService.search(1L, 2L, null, "查询", 3)).thenReturn(List.of());
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of());
        assertTrue(svc.search(1L, 2L, null, "查询", 3).isEmpty());
    }

    /**
     * 文本降级按双粒度过滤：角色级文档仅当前角色可见（非当前角色剔除）。
     */
    @Test
    void textFallbackFiltersByCharacter() {
        when(ragSearchService.search(1L, 2L, 7L, "禁灵咒", 3)).thenReturn(List.of());
        ActorKnowledgeDoc other = doc(11L, "角色甲的笔记", "禁灵咒：仅角色甲可见的禁灵咒记录。");
        other.setCharacterId(7L);
        ActorKnowledgeDoc mine = doc(12L, "角色乙的笔记", "禁灵咒：仅角色乙可见的禁灵咒记录。");
        mine.setCharacterId(8L);
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(other, mine));
        List<KnowledgeHit> hits = svc.search(1L, 2L, 7L, "禁灵咒", 3);
        // 角色 7 只能看到自己的文档（11），看不到角色 8 的文档（12）
        assertEquals(1, hits.size());
        assertEquals(11L, hits.get(0).docId());
    }
}
