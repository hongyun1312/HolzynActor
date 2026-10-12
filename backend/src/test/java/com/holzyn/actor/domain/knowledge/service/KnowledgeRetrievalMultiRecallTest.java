package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.langchain4j.RagSearchService;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRetrievalService 多路召回单元测试（知识检索质量深化，2026-09-29_04）。
 * <p>职责：验证「原 query 向量检索 + 话题词独立向量检索合并取 topK」——①话题词命中并入结果；
 * ②同 (docId,text) 去重取高分；③开关/上限关闭时退化为单路；④主路未命中走文本降级（不叠加多路）；
 * ⑤话题词路异常不阻断主路。</p>
 */
class KnowledgeRetrievalMultiRecallTest {

    private ActorKnowledgeDocRepository docRepository;
    private ModelApiService modelApiService;
    private RagSearchService ragSearchService;
    private KnowledgeRetrievalService svc;

    /** 项目 1 知识库内容（含高频话题词「势力」——正文出现 3 次入话题词索引） */
    private static final String DOC_CONTENT = "沈氏势力与雷家势力分布，势力之争不断。";
    private static final String MAIN_QUERY = "青禾村有哪些势力";
    private static final String TOPIC = "势力";

    @BeforeEach
    void setUp() {
        docRepository = mock(ActorKnowledgeDocRepository.class);
        modelApiService = mock(ModelApiService.class);
        ragSearchService = mock(RagSearchService.class);
        svc = new KnowledgeRetrievalService(docRepository, modelApiService, ragSearchService);
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(1L);
        d.setProjectId(1L);
        d.setTitle("世界设定·势力");
        d.setContent(DOC_CONTENT);
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(d));
    }

    private static KnowledgeHit hit(Long docId, String title, String text, double score) {
        return new KnowledgeHit(docId, title, text, score, null);
    }
    @Test
    void multiRecallMergesTopicHits() {
        KnowledgeHit main = hit(1L, "世界设定·势力", "主路片段", 0.9);
        KnowledgeHit extra = hit(2L, "世界设定·历史", "话题词片段", 0.85);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(TOPIC), anyInt()))
                .thenReturn(List.of(extra));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(2, result.size(), "主路 + 话题词路合并");
        assertTrue(result.contains(main) && result.contains(extra));
        // 主路 1 次 + 话题词 1 次 = 2 次向量检索
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 同 (docId,text) 去重取高分：话题词路同片段高分 → 结果只保留一条且为高分。 */
    @Test
    void multiRecallDedupKeepsHigherScore() {
        KnowledgeHit main = hit(1L, "世界设定·势力", "同一片段", 0.7);
        KnowledgeHit extra = hit(1L, "世界设定·势力", "同一片段", 0.95);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(TOPIC), anyInt()))
                .thenReturn(List.of(extra));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(1, result.size(), "同片段去重");
        assertEquals(0.95, result.get(0).score(), "取高分");
    }

    /** 多路召回开关关闭 → 仅主路（1 次向量检索）。 */
    @Test
    void multiRecallDisabledFallsBackToSinglePath() {
        ReflectionTestUtils.setField(svc, "multiRecallEnabled", false);
        KnowledgeHit main = hit(1L, "世界设定·势力", "主路片段", 0.9);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(List.of(main), result);
        verify(ragSearchService, times(1)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 话题词上限 = 0 → 仅主路。 */
    @Test
    void topicLimitZeroSkipsMultiRecall() {
        ReflectionTestUtils.setField(svc, "topicLimit", 0);
        KnowledgeHit main = hit(1L, "世界设定·势力", "主路片段", 0.9);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(List.of(main), result);
        verify(ragSearchService, times(1)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 主路未命中 + 无话题词索引 → 文本关键词检索降级（仅 1 次调用）。 */
    @Test
    void mainMissFallsBackToTextSearchWhenNoTopics() {
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of()); // 无文档 → 无话题词索引
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of());

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        verify(ragSearchService, times(1)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        assertTrue(result.isEmpty(), "无文档文本降级返回空");
    }

    /** 主路未命中 + 话题词路精确命中 → 话题词路兜底返回（冒烟实证驱动：短词/专有词主路语义检索常未命中）。 */
    @Test
    void mainMissTopicHitRescues() {
        KnowledgeHit topicHit = hit(1L, "世界设定·势力", "势力甲", 0.9);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of());
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(TOPIC), anyInt()))
                .thenReturn(List.of(topicHit));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(List.of(topicHit), result, "主路未命中时话题词路精确命中兜底");
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 主路未命中 + 话题词路也空 → 文本关键词检索降级（保留原语义兜底）。 */
    @Test
    void mainMissTopicPathEmptyFallsBackToText() {
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of());
        // docRepository 有含「势力」正文 → 话题词=[势力]；话题词路未打桩 → 空 → merged 空 → 文本降级

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        assertTrue(!result.isEmpty(), "话题词路空 → 文本降级命中正文片段");
    }

    /** 话题词路检索异常 → 不阻断主路结果。 */
    @Test
    void topicSearchFailureDoesNotBreakMain() {
        KnowledgeHit main = hit(1L, "世界设定·势力", "主路片段", 0.9);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(TOPIC), anyInt()))
                .thenThrow(new RuntimeException("话题词路失败"));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(List.of(main), result, "话题词路失败仅告警，主路结果保留");
    }

    /** 无话题词索引（知识库无内容）→ 退化为单路。 */
    @Test
    void noTopicIndexDegradesToSinglePath() {
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());
        KnowledgeHit main = hit(1L, "世界设定·势力", "主路片段", 0.9);
        when(ragSearchService.search(anyLong(), anyLong(), any(), eq(MAIN_QUERY), anyInt()))
                .thenReturn(List.of(main));

        List<KnowledgeHit> result = svc.search(1L, 1L, null, MAIN_QUERY, 3);

        assertEquals(List.of(main), result);
        verify(ragSearchService, times(1)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }
}
