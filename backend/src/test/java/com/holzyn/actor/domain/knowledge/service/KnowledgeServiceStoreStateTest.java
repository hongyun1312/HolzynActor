package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.vo.KnowledgeDocVO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeService 知识页四态 VO 单元测试（AI 接入层重构 M4，2026-09-06，用户决策：判定改走向量库 + 改 VO/前端展示结构）。
 * <p>职责：验证 toVO 四态判定——①内容为空 → EMPTY；②未配置 embedding 供应商 → NOT_CONFIGURED；
 * ③向量库含该 docId chunk → VECTORIZED（chunkCount&gt;0，embeddingModel 展示）；
 * ④供应商已配置但向量库无该文档 → FAILED（向量化失败/未入库）。
 * 四态均以 {@link VectorStoreService#docChunkCount} 为判定源（不再读旧 embedding 列）。</p>
 */
class KnowledgeServiceStoreStateTest {

    private final ActorKnowledgeDocRepository docRepository = mock(ActorKnowledgeDocRepository.class);
    private final ActorProjectRepository projectRepository = mock(ActorProjectRepository.class);
    private final ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
    private final KnowledgeVectorizerService vectorizerService = mock(KnowledgeVectorizerService.class);
    private final WorldKnowledgeSyncService worldKnowledgeSyncService = mock(WorldKnowledgeSyncService.class);
    private final CharacterKnowledgeCardSyncService characterKnowledgeCardSyncService =
            mock(CharacterKnowledgeCardSyncService.class);
    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);
    private final ModelApiService modelApiService = mock(ModelApiService.class);

    private KnowledgeService newService() {
        return new KnowledgeService(docRepository, projectRepository, characterRepository,
                currentUserProvider, retrievalService, vectorizerService, worldKnowledgeSyncService,
                characterKnowledgeCardSyncService, vectorStoreService, modelApiService);
    }

    /** 基础 mock：当前用户 + 项目归属 + embedding 模型名（项目级优先/用户级回退） */
    private void baseMocks(boolean providerConfigured) {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        ActorProject p = new ActorProject();
        p.setId(2L);
        when(projectRepository.findByIdAndUserIdAndDeleted(eq(2L), eq(1L), eq(0))).thenReturn(Optional.of(p));
        if (providerConfigured) {
            when(modelApiService.resolveEmbeddingProvider(1L, 2L))
                    .thenReturn(new ProviderConfig(5L, "https://api.example.com/v1", "sk", "emb", false));
        } else {
            when(modelApiService.resolveEmbeddingProvider(1L, 2L)).thenReturn(null);
        }
    }

    /** 构造文档实体 */
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
     * ① 内容为空 → EMPTY（无内容可分块向量化）。
     */
    @Test
    void emptyContentYieldsEmptyState() {
        baseMocks(true);
        ActorKnowledgeDoc d = doc(10L, "空文档", "   ");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        KnowledgeService svc = newService();
        KnowledgeDocVO vo = svc.list(2L).get(0);
        assertEquals(KnowledgeDocVO.STATE_EMPTY, vo.storeState());
        assertFalse(vo.vectorized());
        assertEquals(0, vo.chunkCount());
    }

    /**
     * ② 未配置 embedding 供应商 → NOT_CONFIGURED（前端顶部降级提示），不触发向量库查询。
     */
    @Test
    void noProviderYieldsNotConfigured() {
        baseMocks(false);
        ActorKnowledgeDoc d = doc(10L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        KnowledgeService svc = newService();
        KnowledgeDocVO vo = svc.list(2L).get(0);
        assertEquals(KnowledgeDocVO.STATE_NOT_CONFIGURED, vo.storeState());
        assertFalse(vo.vectorized());
        assertNull(vo.embeddingModel());
    }

    /**
     * ③ 向量库含该 docId chunk → VECTORIZED（chunkCount 取向量库计数，embeddingModel 展示）。
     */
    @Test
    void storeHasDocYieldsVectorized() {
        baseMocks(true);
        ActorKnowledgeDoc d = doc(10L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        when(vectorStoreService.docChunkCount(2L, 10L)).thenReturn(3);
        KnowledgeService svc = newService();
        KnowledgeDocVO vo = svc.list(2L).get(0);
        assertEquals(KnowledgeDocVO.STATE_VECTORIZED, vo.storeState());
        assertTrue(vo.vectorized());
        assertEquals(3, vo.chunkCount());
        assertEquals("emb", vo.embeddingModel());
    }

    /**
     * ④ 供应商已配置但向量库无该文档 → FAILED（向量化失败/未入库）。
     */
    @Test
    void providerConfiguredButStoreMissingYieldsFailed() {
        baseMocks(true);
        ActorKnowledgeDoc d = doc(10L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        when(vectorStoreService.docChunkCount(2L, 10L)).thenReturn(0);
        KnowledgeService svc = newService();
        KnowledgeDocVO vo = svc.list(2L).get(0);
        assertEquals(KnowledgeDocVO.STATE_FAILED, vo.storeState());
        assertFalse(vo.vectorized());
        assertEquals(0, vo.chunkCount());
        assertNull(vo.embeddingModel());
    }

    /**
     * 角色级文档：characterName 正常解析（不影响四态判定）。
     */
    @Test
    void characterDocResolvesName() {
        baseMocks(true);
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(11L);
        d.setProjectId(2L);
        d.setCharacterId(3L);
        d.setTitle("角色设定");
        d.setContent("角色私密设定内容。");
        when(docRepository.findByProjectIdOrderByIdAsc(2L)).thenReturn(List.of(d));
        when(vectorStoreService.docChunkCount(2L, 11L)).thenReturn(2);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        KnowledgeService svc = newService();
        KnowledgeDocVO vo = svc.list(2L).get(0);
        assertEquals(KnowledgeDocVO.STATE_VECTORIZED, vo.storeState());
        assertEquals("罗小黑", vo.characterName());
        assertEquals(3L, vo.characterId());
    }
}
