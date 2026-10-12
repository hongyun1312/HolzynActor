package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConversationContextService 单元测试（知识库 RAG 适配，2026-09-29_03：对话【知识参考】注入带来源标题）。
 * <p>职责：验证 RAG 命中片段注入格式——①命中 → 【知识参考】段逐条带 {@code [标题]} 前缀
 * （对齐感知路径「标题：文本」，AI 可识别知识类别，便于核对引用来源）；②标题为空回退「知识」；
 * ③无命中不注入知识段；④RAG 关闭不检索。</p>
 */
class ConversationContextServiceTest {

    private final KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository = mock(ActorCrowdRuntimeRepository.class);
    private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
    private final NpcTypeResolver npcTypeResolver = mock(NpcTypeResolver.class);

    private final ConversationContextService svc = new ConversationContextService(
            retrievalService, crowdRuntimeRepository, memoryService, npcTypeResolver);

    /** 记忆为空 + 无人群环境快照（隔离知识段断言）。 */
    private void stubEmptyMemoryAndEnv() {
        when(memoryService.memoryContext(anyLong(), anyLong(), any(), any(), any())).thenReturn("");
        when(crowdRuntimeRepository.findByProjectId(anyLong())).thenReturn(Optional.empty());
    }

    /**
     * 开启 RAG 开关（单测直接 new 时 @Value 字段 ragEnabled 保持 boolean 默认 false 不注入，
     * 与 Spring 环境的 @Value(true) 不一致——反射置 true，与真实装配对齐）。
     */
    private void enableRag() throws Exception {
        java.lang.reflect.Field f = ConversationContextService.class.getDeclaredField("ragEnabled");
        f.setAccessible(true);
        f.setBoolean(svc, true);
    }

    /** 命中 → 【知识参考】段逐条带 [标题] 前缀（世界设定·地理等来源类别可见）。 */
    @Test
    void ragHitsInjectWithTitlePrefix() throws Exception {
        enableRag();
        stubEmptyMemoryAndEnv();
        when(retrievalService.search(eq(7L), eq(1L), eq(3L), eq("青禾村地形如何"), eq(3)))
                .thenReturn(List.of(
                        new KnowledgeHit(10L, "世界设定·地理", "青禾村坐落在青岳山东麓、灵溪河畔。", 0.9, null),
                        new KnowledgeHit(11L, "世界地点志", "云来茶馆（茶馆）：临街二层小楼。", 0.8, null)));

        String ctx = svc.buildContext(7L, 1L, 3L, "青禾村地形如何");

        assertTrue(ctx.contains("【知识参考】"));
        assertTrue(ctx.contains("- [世界设定·地理] 青禾村坐落在青岳山东麓、灵溪河畔。"));
        assertTrue(ctx.contains("- [世界地点志] 云来茶馆（茶馆）：临街二层小楼。"));
    }

    /** 标题为空 → 回退「知识」。 */
    @Test
    void ragHitBlankTitleFallsBackToKnowledge() throws Exception {
        enableRag();
        stubEmptyMemoryAndEnv();
        when(retrievalService.search(eq(7L), eq(1L), eq(3L), eq("附近有什么"), eq(3)))
                .thenReturn(List.of(new KnowledgeHit(10L, "  ", "片段文本。", 0.9, null)));

        String ctx = svc.buildContext(7L, 1L, 3L, "附近有什么");

        assertTrue(ctx.contains("- [知识] 片段文本。"));
    }

    /** 无命中 → 不注入【知识参考】段。 */
    @Test
    void ragMissInjectsNoKnowledgeSection() throws Exception {
        enableRag();
        stubEmptyMemoryAndEnv();
        when(retrievalService.search(anyLong(), anyLong(), any(), anyString(), anyInt())).thenReturn(List.of());

        String ctx = svc.buildContext(7L, 1L, 3L, "随便聊聊");

        assertFalse(ctx.contains("【知识参考】"));
    }

    /** ragEnabled=false → 不检索知识（行为与 P2 一致）。 */
    @Test
    void ragDisabledSkipsRetrieval() throws Exception {
        stubEmptyMemoryAndEnv();
        // 反射关掉 ragEnabled（字段注入保持构造器不变，存量单测零改动）
        java.lang.reflect.Field f = ConversationContextService.class.getDeclaredField("ragEnabled");
        f.setAccessible(true);
        f.setBoolean(svc, false);

        String ctx = svc.buildContext(7L, 1L, 3L, "青禾村地形如何");

        assertFalse(ctx.contains("【知识参考】"));
        org.mockito.Mockito.verify(retrievalService, org.mockito.Mockito.never())
                .search(anyLong(), anyLong(), any(), anyString(), anyInt());
    }

    /**
     * 记忆注入 query=最近用户消息（tier 判定与 L2 语义检索的情境文本）——修复
     * 「对话注入空查询→memory_tier 空跑 + L2 相关性=0」缺陷（2026-09-30 长跑实测 72/72 空查询）。
     */
    @Test
    void buildContextThreadsLastUserTextAsMemoryQuery() {
        stubEmptyMemoryAndEnv();
        when(retrievalService.search(anyLong(), anyLong(), any(), anyString(), anyInt())).thenReturn(List.of());

        svc.buildContext(7L, 1L, 3L, "青禾村地形如何");

        org.mockito.Mockito.verify(memoryService)
                .memoryContext(eq(7L), eq(1L), eq(3L), eq("青禾村地形如何"), isNull());
    }
}
