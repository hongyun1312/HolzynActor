package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CharacterKnowledgeCardSyncService 单元测试（知识检索质量深化，2026-09-29_04）。
 * <p>职责：验证人物知识卡——①卡片组装（actor_profile 列 + persona_json 各维 + 角色级知识文档
 * 全字段映射 / 空字段跳过 / 全空返回空串）；②同步（新建+向量化 / 幂等零向量化 / 内容变化更新 /
 * 角色不存在零操作 / 空卡不建）。</p>
 */
class CharacterKnowledgeCardSyncServiceTest {

    private final ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
    private final ActorKnowledgeDocRepository docRepository = mock(ActorKnowledgeDocRepository.class);
    private final KnowledgeVectorizerService vectorizerService = mock(KnowledgeVectorizerService.class);

    private final CharacterKnowledgeCardSyncService svc = new CharacterKnowledgeCardSyncService(
            characterRepository, docRepository, vectorizerService);

    /** 新建文档 id 分配计数器（saveAndFlush mock 回填主键） */
    private int counter = 500;

    private ActorCharacter ch;

    /** 完整 persona_json（身份/经历/性格/技能/目标各维） */
    private static final String PERSONA_JSON = """
            {
              "dims": {
                "identity": {"aliases": ["砚秋公"]},
                "experience": {"origin": "青禾村土生土长", "keyEvents": [{"age": 30, "what": "继任族长"}]},
                "personality": {"temperamentType": "沉稳", "traitsSummary": "重诺守信"},
                "ability": {"skills": {"族务": 8.0, "书法": 6.0}, "talents": ["识人"]},
                "values": {"lifeGoals": ["光耀门楣"], "currentGoals": ["主持冬至祭祖"],
                           "coreDesires": ["村中安宁"], "coreFears": ["宗族分裂"]}
              }
            }
            """;

    @BeforeEach
    void setUp() {
        ch = new ActorCharacter();
        ch.setId(7L);
        ch.setProjectId(1L);
        ch.setName("沈砚秋");
        ch.setTitle("沈氏族长");
        ch.setGender("男");
        ch.setRace("人族");
        ch.setAffiliation("沈氏宗族");
        ch.setOccupation("族长");
        ch.setAge(45);
        ch.setLocation("青禾村沈氏祠堂");
        ch.setPersonaJson(PERSONA_JSON);
        ch.setDeleted(0);
        when(characterRepository.findById(7L)).thenReturn(Optional.of(ch));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(ch));
        when(docRepository.saveAndFlush(any(ActorKnowledgeDoc.class))).thenAnswer(inv -> {
            ActorKnowledgeDoc d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId((long) ++counter);
            }
            return d;
        });
    }

    private static ActorKnowledgeDoc doc(Long id, Long charId, String title, String content) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setProjectId(1L);
        d.setCharacterId(charId);
        d.setTitle(title);
        d.setContent(content);
        d.setStatus(1);
        return d;
    }

    // ==================== 纯逻辑：卡片组装 ====================

    /** 全字段组装：actor_profile 结构列 + persona_json 各维 + 角色级知识文档全部映射。 */
    @Test
    void buildCardAssemblesAllSections() {
        List<ActorKnowledgeDoc> docs = List.of(
                doc(1L, 7L, "知识领域", "草药、农事"),
                doc(2L, 7L, "知识盲区", "机关术"),
                doc(3L, 8L, "知识领域", "他人领域")); // 其他角色文档不串扰
        String card = CharacterKnowledgeCardSyncService.buildCard(ch, docs);

        assertTrue(card.contains("【身份】沈氏族长沈砚秋（男·人族·沈氏宗族·族长）"), card);
        assertTrue(card.contains("【别名】砚秋公"), card);
        assertTrue(card.contains("【年龄】45岁"), card);
        assertTrue(card.contains("【出身】青禾村土生土长"), card);
        assertTrue(card.contains("【背景】30岁：继任族长"), card);
        assertTrue(card.contains("【性格】沉稳、重诺守信"), card);
        assertTrue(card.contains("【常去地点】青禾村沈氏祠堂"), card);
        assertTrue(card.contains("【技能】族务、书法、识人"), card);
        assertTrue(card.contains("【目标】光耀门楣、主持冬至祭祖、村中安宁、宗族分裂"), card);
        assertTrue(card.contains("【知识领域】草药、农事"), card);
        assertTrue(card.contains("【知识盲区】机关术"), card);
        assertFalse(card.contains("他人领域"), "其他角色的知识文档不得进入本卡");
    }

    /** persona_json 为空 → 回退 actor_profile 基础字段（身份/年龄/背景=detail/常去地点），persona 段缺失。 */
    @Test
    void buildCardFallsBackToProfileWhenNoPersona() {
        ch.setPersonaJson(null);
        ch.setDetail("早年随父经商，后归乡主持族务。");
        String card = CharacterKnowledgeCardSyncService.buildCard(ch, List.of());

        assertTrue(card.contains("【身份】沈氏族长沈砚秋（男·人族·沈氏宗族·族长）"), card);
        assertTrue(card.contains("【年龄】45岁"), card);
        assertTrue(card.contains("【背景】早年随父经商，后归乡主持族务。"), card);
        assertTrue(card.contains("【常去地点】青禾村沈氏祠堂"), card);
        assertFalse(card.contains("【性格】"), "无 persona 性格维 → 段缺失");
        assertFalse(card.contains("【技能】"), "无 persona 技能维 → 段缺失");
    }

    /** 角色全空 → 空串（不建空卡）。 */
    @Test
    void buildCardAllBlankReturnsEmpty() {
        ActorCharacter empty = new ActorCharacter();
        empty.setId(9L);
        empty.setProjectId(1L);
        assertEquals("", CharacterKnowledgeCardSyncService.buildCard(empty, List.of()));
    }

    /** persona_json 非法 JSON → 零阻断：仅基础字段段。 */
    @Test
    void buildCardToleratesInvalidPersonaJson() {
        ch.setPersonaJson("{broken json");
        String card = CharacterKnowledgeCardSyncService.buildCard(ch, List.of());
        assertTrue(card.contains("【身份】沈氏族长沈砚秋"), card);
        assertFalse(card.contains("【性格】"), "非法 persona 段缺失且不抛异常");
    }

    // ==================== 同步（新建/幂等/更新） ====================

    /** 无既有卡 → 新建 + 向量化（角色级 characterId 传入向量库元数据）。 */
    @Test
    void syncCreatesCardAndVectorizes() {
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());

        int synced = svc.syncCharacterKnowledgeCard(1L, 1L, 7L);

        assertEquals(1, synced);
        verify(docRepository, times(1)).saveAndFlush(any(ActorKnowledgeDoc.class));
        verify(vectorizerService, times(1)).vectorize(eq(1L), eq(1L), eq((long) counter),
                eq(CharacterKnowledgeCardSyncService.TITLE_CARD), eq(7L), anyString());
    }

    /** 内容未变 → 幂等跳过（零向量化、零保存）。 */
    @Test
    void syncIdempotentSkipsUnchangedContent() {
        String expected = CharacterKnowledgeCardSyncService.buildCard(ch, List.of());
        when(docRepository.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(List.of(doc(10L, 7L, CharacterKnowledgeCardSyncService.TITLE_CARD, expected)));

        int synced = svc.syncCharacterKnowledgeCard(1L, 1L, 7L);

        assertEquals(0, synced);
        verifyNoInteractions(vectorizerService);
    }

    /** 内容变化 → 更新正文 + 重新向量化。 */
    @Test
    void syncUpdatesChangedCard() {
        when(docRepository.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(List.of(doc(10L, 7L, CharacterKnowledgeCardSyncService.TITLE_CARD, "旧卡内容")));

        int synced = svc.syncCharacterKnowledgeCard(1L, 1L, 7L);

        assertEquals(1, synced);
        verify(docRepository, times(1)).saveAndFlush(any(ActorKnowledgeDoc.class));
        verify(vectorizerService, times(1)).vectorize(eq(1L), eq(1L), eq(10L),
                eq(CharacterKnowledgeCardSyncService.TITLE_CARD), eq(7L), anyString());
    }

    /** 角色不存在 / 项目不匹配 / 已删除 → 零操作。 */
    @Test
    void syncSkipsMissingOrForeignCharacter() {
        when(characterRepository.findById(7L)).thenReturn(Optional.empty());
        assertEquals(0, svc.syncCharacterKnowledgeCard(1L, 1L, 7L));

        ActorCharacter foreign = new ActorCharacter();
        foreign.setId(7L);
        foreign.setProjectId(99L);
        when(characterRepository.findById(7L)).thenReturn(Optional.of(foreign));
        assertEquals(0, svc.syncCharacterKnowledgeCard(1L, 1L, 7L));

        ch.setDeleted(1);
        when(characterRepository.findById(7L)).thenReturn(Optional.of(ch));
        assertEquals(0, svc.syncCharacterKnowledgeCard(1L, 1L, 7L));
        verifyNoInteractions(vectorizerService);
    }

    /** 空卡（角色无任何可卡内容）→ 不建空文档。 */
    @Test
    void syncBlankCardSkips() {
        ActorCharacter empty = new ActorCharacter();
        empty.setId(9L);
        empty.setProjectId(1L);
        when(characterRepository.findById(9L)).thenReturn(Optional.of(empty));

        int synced = svc.syncCharacterKnowledgeCard(1L, 1L, 9L);

        assertEquals(0, synced);
        verify(docRepository, times(0)).saveAndFlush(any());
        verifyNoInteractions(vectorizerService);
    }

    /** syncAllCards：全角色同步，单角色失败不阻断其余。 */
    @Test
    void syncAllCardsIteratesAllCharacters() {
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(ch, ch));
        int synced = svc.syncAllCards(1L, 1L);
        assertEquals(2, synced);
        verify(vectorizerService, times(2)).vectorize(anyLong(), anyLong(), any(),
                anyString(), any(), anyString());
    }
}
