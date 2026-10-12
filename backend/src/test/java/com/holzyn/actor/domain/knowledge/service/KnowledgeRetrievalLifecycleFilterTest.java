package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.langchain4j.RagSearchService;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * 知识检索生命周期出口（P1 ②-2）单元测试。
 * <p>覆盖：archived 文档从检索结果排除（「遗忘」）；stale 文档命中标注（消费方注入
 * prompt 带「可能过时」）；hot/warm 正常不标注；命中即引用计数（recordAccess）；
 * 状态查询失败降级（返回原始命中，零阻断）。</p>
 * <p>装配：mock 仓库直接 new 构造（构造器=docRepository+modelApiService+ragSearchService）；
 * lifecycleService 字段注入 {@code ReflectionTestUtils.setField}；queryCacheEnabled=false
 * （直走未缓存路径）。</p>
 */
class KnowledgeRetrievalLifecycleFilterTest {

    private final ActorKnowledgeDocRepository docRepo = mock(ActorKnowledgeDocRepository.class);
    private final ModelApiService modelApiService = mock(ModelApiService.class);
    private final RagSearchService ragSearchService = mock(RagSearchService.class);
    private final KnowledgeLifecycleService lifecycleService = mock(KnowledgeLifecycleService.class);
    private final KnowledgeRetrievalService service =
            new KnowledgeRetrievalService(docRepo, modelApiService, ragSearchService);

    private void setUpFilter(boolean queryCacheEnabled) {
        ReflectionTestUtils.setField(service, "queryCacheEnabled", queryCacheEnabled);
        ReflectionTestUtils.setField(service, "lifecycleService", lifecycleService);
        // 话题词索引构建兜底（主路命中即返回，不触发额外检索）
        when(docRepo.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of());
    }

    private ActorKnowledgeDoc doc(Long id, String state) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setProjectId(100L);
        d.setLifecycleState(state);
        return d;
    }

    private List<KnowledgeHit> hits(KnowledgeHit... hs) {
        return List.of(hs);
    }

    private KnowledgeHit hit(Long docId, String text) {
        return new KnowledgeHit(docId, "知识-" + docId, text, 0.9, null);
    }

    private void stubRag(List<KnowledgeHit> returned) {
        when(ragSearchService.search(anyLong(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(returned);
    }

    /** archived 文档排除：检索命中被过滤（「遗忘」）。 */
    @Test
    void archivedExcludedFromRetrieval() {
        setUpFilter(false);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市"), hit(2L, "集市每逢初一休市")));
        when(docRepo.findAllById(Set.of(1L, 2L))).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_ARCHIVED),
                doc(2L, KnowledgeLifecycleService.STATE_HOT)));

        List<KnowledgeHit> out = service.search(1L, 100L, null, "粮市", 3);

        assertEquals(1, out.size(), "archived 命中被排除");
        assertEquals(2L, out.get(0).docId(), "仅剩 hot 命中");
    }

    /** stale 文档命中标注（消费方注入 prompt 带「可能过时」）。 */
    @Test
    void staleMarkedInHits() {
        setUpFilter(false);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市"), hit(2L, "集市每逢初一休市")));
        when(docRepo.findAllById(Set.of(1L, 2L))).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_STALE),
                doc(2L, KnowledgeLifecycleService.STATE_WARM)));

        List<KnowledgeHit> out = service.search(1L, 100L, null, "粮市", 3);

        assertEquals(2, out.size());
        KnowledgeHit stale = out.stream().filter(h -> h.docId() == 1L).findFirst().orElseThrow();
        KnowledgeHit warm = out.stream().filter(h -> h.docId() == 2L).findFirst().orElseThrow();
        assertTrue(stale.stale(), "stale 命中标注");
        assertFalse(warm.stale(), "warm 命中不标注");
    }

    /** 命中即引用计数：recordAccess 收到全部命中 docId。 */
    @Test
    void recordAccessOnHits() {
        setUpFilter(false);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市")));
        when(docRepo.findAllById(Set.of(1L))).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_HOT)));

        service.search(1L, 100L, null, "粮市", 3);

        verify(lifecycleService).recordAccess(eq(100L), eq(Set.of(1L)));
    }

    /** 缓存命中路径同样过滤/标注（缓存存原始命中，出口统一处理）。 */
    @Test
    void cachedHitsAlsoFiltered() {
        setUpFilter(true);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市")));
        when(docRepo.findAllById(Set.of(1L))).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_ARCHIVED)));

        // 第一次（未缓存）→ 过滤后为空；第二次（缓存命中）→ 同样过滤
        List<KnowledgeHit> first = service.search(1L, 100L, null, "粮市", 3);
        List<KnowledgeHit> second = service.search(1L, 100L, null, "粮市", 3);

        assertTrue(first.isEmpty(), "首次已过滤 archived");
        assertTrue(second.isEmpty(), "缓存命中同样过滤 archived");
    }

    /** 生命周期服务未装配：过滤/标注仍生效，计数跳过。 */
    @Test
    void unwiredLifecycleStillFilters() {
        setUpFilter(false);
        ReflectionTestUtils.setField(service, "lifecycleService", null);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市")));
        when(docRepo.findAllById(Set.of(1L))).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_ARCHIVED)));

        List<KnowledgeHit> out = service.search(1L, 100L, null, "粮市", 3);

        assertTrue(out.isEmpty(), "未装配也排除 archived");
    }

    /** 状态查询失败 → 返回原始命中（零阻断）。 */
    @Test
    void stateQueryFailureDegrades() {
        setUpFilter(false);
        stubRag(hits(hit(1L, "沈氏宗族控制粮市")));
        when(docRepo.findAllById(Set.of(1L))).thenThrow(new RuntimeException("DB 故障"));

        List<KnowledgeHit> out = service.search(1L, 100L, null, "粮市", 3);

        assertEquals(1, out.size(), "查询失败返回原始命中");
        assertFalse(out.get(0).stale());
        verify(lifecycleService, never()).recordAccess(anyLong(), any());
    }
}
