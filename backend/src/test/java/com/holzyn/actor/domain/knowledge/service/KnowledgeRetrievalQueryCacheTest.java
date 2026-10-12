package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.langchain4j.RagSearchService;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRetrievalService 检索结果缓存单元测试（2026-09-21 世界运行抓修）。
 *
 * <p>抓修背景：感知侧（D3 F8）每次组装感知文本都会以「所在地 + 当前活动」检索一次知识，而该 query 在
 * 角色没换地点/没换活动时逐游戏小时完全相同——实测 15 分钟内 240 次检索只有 53 条不同 query（4.5 倍重复），
 * 每次都重新向量化（打到向量模型）+ 重新检索同一份静态知识库。</p>
 *
 * <p>验证要点：①同 query 命中缓存不再回源；②容量/开关/过期边界行为；③知识库变更（写入/删除）
 * 必须让旧答案作废（invalidate）；④不同角色级/不同 topK 不共享缓存（检索语义不同）。</p>
 */
class KnowledgeRetrievalQueryCacheTest {

    private ActorKnowledgeDocRepository docRepository;
    private ModelApiService modelApiService;
    private RagSearchService ragSearchService;
    private KnowledgeRetrievalService svc;

    private static final List<KnowledgeHit> HITS =
            List.of(new KnowledgeHit(7L, "青禾村志", "青禾村位于灵溪河畔……", 0.82, null));

    @BeforeEach
    void setUp() {
        docRepository = mock(ActorKnowledgeDocRepository.class);
        modelApiService = mock(ModelApiService.class);
        ragSearchService = mock(RagSearchService.class);
        svc = new KnowledgeRetrievalService(docRepository, modelApiService, ragSearchService);
        when(ragSearchService.search(anyLong(), anyLong(), any(), anyString(), anyInt())).thenReturn(HITS);
    }

    /** 同项目/角色级/query/topK 第二次检索命中缓存：只回源一次。 */
    @Test
    void sameQueryServedFromCache() {
        List<KnowledgeHit> first = svc.search(1L, 2L, 9L, "竹林茅舍 rest（歇息就寝）", 3);
        List<KnowledgeHit> second = svc.search(1L, 2L, 9L, "竹林茅舍 rest（歇息就寝）", 3);

        assertEquals(HITS, first);
        assertEquals(HITS, second);
        verify(ragSearchService, times(1)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        Map<String, Object> stats = svc.cacheStats();
        assertEquals(1L, stats.get("hits"), "第二次应命中缓存");
        assertEquals(1L, stats.get("misses"), "只回源一次");
    }

    /** 知识库变更（文档写入/删除）→ 显式失效后必须回源（否则新知识在 TTL 内不可见）。 */
    @Test
    void invalidateForcesRefetch() {
        svc.search(1L, 2L, 9L, "灵溪河畔 浣衣", 3);
        svc.invalidate(2L);
        svc.search(1L, 2L, 9L, "灵溪河畔 浣衣", 3);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        assertEquals(1L, svc.cacheStats().get("invalidations"));
    }

    /** 失效按项目隔离：失效项目 A 不影响项目 B 的缓存。 */
    @Test
    void invalidateIsPerProject() {
        svc.search(1L, 2L, null, "青禾集市 叫卖", 3);
        svc.search(1L, 3L, null, "青禾集市 叫卖", 3);
        svc.invalidate(2L);
        svc.search(1L, 2L, null, "青禾集市 叫卖", 3);   // 回源
        svc.search(1L, 3L, null, "青禾集市 叫卖", 3);   // 仍命中
        // 项目 2 两次回源 + 项目 3 一次 = 3
        verify(ragSearchService, times(3)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 角色级不同（null=项目级 vs 具体角色）不共享缓存——检索语义不同（角色级知识并入）。 */
    @Test
    void differentCharacterLevelNotShared() {
        svc.search(1L, 2L, null, "云来茶馆 待客", 3);
        svc.search(1L, 2L, 9L, "云来茶馆 待客", 3);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** topK 不同不共享缓存（返回条数不同，缓存不可互换）。 */
    @Test
    void differentTopKNotShared() {
        svc.search(1L, 2L, 9L, "老井 打水", 3);
        svc.search(1L, 2L, 9L, "老井 打水", 5);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 开关关闭：每次检索都回源（退回抓修前行为）。 */
    @Test
    void disabledCacheAlwaysRefetches() {
        ReflectionTestUtils.setField(svc, "queryCacheEnabled", false);
        svc.search(1L, 2L, 9L, "后山竹林 采药", 3);
        svc.search(1L, 2L, 9L, "后山竹林 采药", 3);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 过期（TTL 负数 → 恒过期）→ 回源（兜底"漏失效"的场景）。 */
    @Test
    void expiredEntryRefetches() {
        ReflectionTestUtils.setField(svc, "queryCacheTtlMs", -1L);
        svc.search(1L, 2L, 9L, "雷家铁匠铺 打铁", 3);
        svc.search(1L, 2L, 9L, "雷家铁匠铺 打铁", 3);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /** 空 query / 空项目不参与缓存（键不可构造），但功能不降级（照常回源）。 */
    @Test
    void blankQueryNotCached() {
        svc.search(1L, 2L, 9L, "   ", 3);
        svc.search(1L, 2L, 9L, "   ", 3);
        verify(ragSearchService, times(2)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        assertEquals(0L, svc.cacheStats().get("hits"));
    }

    /** 容量超限整体清空（条目极小、重建便宜；防无界增长）。 */
    @Test
    void overCapacityClearsCache() {
        ReflectionTestUtils.setField(svc, "queryCacheMax", 1);
        svc.search(1L, 2L, 9L, "第一段查询", 3);
        svc.search(1L, 2L, 9L, "第二段查询", 3);
        // 容量 1：写入第二条时清空 → 第一条不再命中
        svc.search(1L, 2L, 9L, "第一段查询", 3);
        verify(ragSearchService, times(3)).search(anyLong(), anyLong(), any(), anyString(), anyInt());
        assertTrue((Integer) svc.cacheStats().get("size") >= 0);
    }
}