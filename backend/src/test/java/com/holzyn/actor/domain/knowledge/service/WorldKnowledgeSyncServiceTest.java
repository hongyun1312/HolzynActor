package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WorldKnowledgeSyncService 单元测试（知识库 RAG 适配，2026-09-29_03）。
 * <p>职责：验证世界设定 → 项目级知识文档同步——①草稿组装（分段映射/总览格式/地点志拼接/空分段跳过）；
 * ②新建路径（saveAndFlush + vectorize）；③幂等（内容未变不重复向量化）；
 * ④内容变化（更新 + 重新向量化）；⑤无设定/无地点零操作；⑥角色级既有文档不受影响。</p>
 */
class WorldKnowledgeSyncServiceTest {

    private final ActorWorldSettingRepository worldSettingRepository = mock(ActorWorldSettingRepository.class);
    private final WorldLocationRepository locationRepository = mock(WorldLocationRepository.class);
    private final ActorKnowledgeDocRepository docRepository = mock(ActorKnowledgeDocRepository.class);
    private final KnowledgeVectorizerService vectorizerService = mock(KnowledgeVectorizerService.class);

    private final WorldKnowledgeSyncService svc = new WorldKnowledgeSyncService(
            worldSettingRepository, locationRepository, docRepository, vectorizerService);

    /** 新建文档 id 分配计数器（saveAndFlush mock 回填主键） */
    private int counter = 1000;

    private ActorWorldSetting setting() {
        ActorWorldSetting ws = new ActorWorldSetting();
        ws.setId(1L);
        ws.setProjectId(1L);
        ws.setName("青禾村 · 灵溪河畔的仙凡村落");
        ws.setGenre("仙侠 / 东方玄幻");
        ws.setEra("大雍朝");
        ws.setGeography("青禾村坐落在青岳山东麓、灵溪河畔。");
        ws.setFactions("① 沈氏宗族：村长一脉。");
        ws.setMagicSystem("灵气、灵脉、吐纳。");
        ws.setCulture("四季农时与节气物候。");
        ws.setHistory("百年建村史。");
        ws.setFreeText("完整世界观自由文本。");
        return ws;
    }

    private ActorWorldLocation location(Long id, String name, String type, String intro) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        l.setType(type);
        l.setIntro(intro);
        return l;
    }

    // ==================== 纯逻辑：草稿组装 ====================

    /** 全量组装：6 个设定分段 + 地点志 = 7 份草稿，标题与来源分段一一对应。 */
    @Test
    void buildDraftsFullMapping() {
        ActorWorldSetting ws = setting();
        ws.setFreeText(""); // 总览回退：freeText 为空不影响（有名称+题材+时代）
        List<ActorWorldLocation> locs = List.of(
                location(1L, "云来茶馆", "茶馆", "临街二层小楼，茶香四溢。"),
                location(2L, "济世医馆", "医馆", "悬壶济世。"));
        Map<String, String> drafts = WorldKnowledgeSyncService.buildDrafts(ws, locs);
        assertEquals(7, drafts.size());
        // 每份正文首行=标题锚词引导句 + 原文（2026-09-29_07 锚词观察项）
        assertStartsWithAnchor(drafts.get(WorldKnowledgeSyncService.TITLE_GEOGRAPHY),
                WorldKnowledgeSyncService.ANCHOR_GEOGRAPHY, "青禾村坐落在青岳山东麓、灵溪河畔。");
        assertStartsWithAnchor(drafts.get(WorldKnowledgeSyncService.TITLE_FACTIONS),
                WorldKnowledgeSyncService.ANCHOR_FACTIONS, "① 沈氏宗族：村长一脉。");
        assertStartsWithAnchor(drafts.get(WorldKnowledgeSyncService.TITLE_RULES),
                WorldKnowledgeSyncService.ANCHOR_RULES, "灵气、灵脉、吐纳。");
        assertStartsWithAnchor(drafts.get(WorldKnowledgeSyncService.TITLE_CULTURE),
                WorldKnowledgeSyncService.ANCHOR_CULTURE, "四季农时与节气物候。");
        assertStartsWithAnchor(drafts.get(WorldKnowledgeSyncService.TITLE_HISTORY),
                WorldKnowledgeSyncService.ANCHOR_HISTORY, "百年建村史。");
        // 总览：锚词引导句 + 名称 + 题材/时代 + freeText（空则无 freeText 段）
        String overview = drafts.get(WorldKnowledgeSyncService.TITLE_OVERVIEW);
        assertTrue(overview.startsWith(WorldKnowledgeSyncService.ANCHOR_OVERVIEW));
        assertTrue(overview.contains("青禾村 · 灵溪河畔的仙凡村落"));
        assertTrue(overview.contains("题材：仙侠 / 东方玄幻"));
        assertTrue(overview.contains("时代：大雍朝"));
        assertFalse(overview.contains("完整世界观自由文本"));
    }

    /** 锚词观察项：7 份文档正文首行均含标题锚词引导句（正文含标题锚词 → 向量/话题词两路召回改善）。 */
    @Test
    void buildDraftsAllDocsContainTitleAnchor() {
        ActorWorldSetting ws = setting();
        List<ActorWorldLocation> locs = List.of(location(1L, "云来茶馆", "茶馆", "临街二层小楼。"));
        Map<String, String> drafts = WorldKnowledgeSyncService.buildDrafts(ws, locs);
        assertEquals(7, drafts.size());
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_OVERVIEW).startsWith(WorldKnowledgeSyncService.ANCHOR_OVERVIEW));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_GEOGRAPHY).startsWith(WorldKnowledgeSyncService.ANCHOR_GEOGRAPHY));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_FACTIONS).startsWith(WorldKnowledgeSyncService.ANCHOR_FACTIONS));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_RULES).startsWith(WorldKnowledgeSyncService.ANCHOR_RULES));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_CULTURE).startsWith(WorldKnowledgeSyncService.ANCHOR_CULTURE));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_HISTORY).startsWith(WorldKnowledgeSyncService.ANCHOR_HISTORY));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_LOCATIONS).startsWith(WorldKnowledgeSyncService.ANCHOR_LOCATIONS));
        // 锚词本体出现（势力/地理/地点志…——正文含标题锚词的直接证据）
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_FACTIONS).contains("势力"));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_GEOGRAPHY).contains("地理"));
        assertTrue(drafts.get(WorldKnowledgeSyncService.TITLE_LOCATIONS).contains("地点志"));
        // 锚词行后换行 + 原文完整保留
        String factions = drafts.get(WorldKnowledgeSyncService.TITLE_FACTIONS);
        assertTrue(factions.startsWith(WorldKnowledgeSyncService.ANCHOR_FACTIONS + "\n① 沈氏宗族：村长一脉。"));
    }

    private void assertStartsWithAnchor(String content, String anchor, String raw) {
        assertTrue(content.startsWith(anchor), "正文应以锚词引导句开头");
        assertTrue(content.indexOf(raw) > anchor.length(), "原文应保留在锚词行之后");
    }

    /** 总览含 freeText（知识库注入源字段）时拼接在尾部。 */
    @Test
    void buildDraftsOverviewIncludesFreeText() {
        Map<String, String> drafts = WorldKnowledgeSyncService.buildDrafts(setting(), List.of());
        String overview = drafts.get(WorldKnowledgeSyncService.TITLE_OVERVIEW);
        assertTrue(overview.contains("完整世界观自由文本"));
        assertTrue(overview.indexOf("完整世界观自由文本") > overview.indexOf("时代："));
    }

    /** 地点志拼接：「地点名（类型）：简介」逐条换行，顺序保持。 */
    @Test
    void buildDraftsLocationsTextFormat() {
        List<ActorWorldLocation> locs = List.of(
                location(1L, "云来茶馆", "茶馆", "临街二层小楼。"),
                location(2L, "村口石牌坊", null, "百年石牌坊。"));
        String text = WorldKnowledgeSyncService.locationsText(locs);
        assertEquals("云来茶馆（茶馆）：临街二层小楼。\n村口石牌坊：百年石牌坊。", text);
    }

    /** 空分段跳过：某分段 blank → 不建该文档；全空 → 零草稿。 */
    @Test
    void buildDraftsSkipsBlankSegments() {
        ActorWorldSetting ws = setting();
        ws.setGeography("  ");
        ws.setFactions(null);
        ws.setMagicSystem("");
        Map<String, String> drafts = WorldKnowledgeSyncService.buildDrafts(ws, List.of());
        assertFalse(drafts.containsKey(WorldKnowledgeSyncService.TITLE_GEOGRAPHY));
        assertFalse(drafts.containsKey(WorldKnowledgeSyncService.TITLE_FACTIONS));
        assertFalse(drafts.containsKey(WorldKnowledgeSyncService.TITLE_RULES));
        assertTrue(drafts.containsKey(WorldKnowledgeSyncService.TITLE_OVERVIEW));
        assertTrue(drafts.containsKey(WorldKnowledgeSyncService.TITLE_CULTURE));

        ActorWorldSetting empty = new ActorWorldSetting();
        empty.setProjectId(1L);
        assertTrue(WorldKnowledgeSyncService.buildDrafts(empty, List.of()).isEmpty());
    }

    // ==================== 编排：同步 ====================

    /** 无世界设定且无地点：零操作（不查文档、不向量化）。 */
    @Test
    void syncWithoutSettingAndLocationsIsNoop() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        assertEquals(0, svc.syncWorldSetting(1L, 1L));
        verify(docRepository, never()).findByProjectIdOrderByIdAsc(anyLong());
        verifyNoInteractions(vectorizerService);
    }

    /** 新建路径：全量 7 份创建（saveAndFlush 拿主键）+ 各自向量化（角色级=null）+ 返回 7。 */
    @Test
    void syncCreatesAllDocsAndVectorizes() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(setting()));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                location(1L, "云来茶馆", "茶馆", "临街二层小楼。")));
        // 既有文档：只有角色级（characterId 非空）——项目级全新
        ActorKnowledgeDoc charDoc = new ActorKnowledgeDoc();
        charDoc.setId(100L);
        charDoc.setProjectId(1L);
        charDoc.setCharacterId(5L);
        charDoc.setTitle("知识领域");
        charDoc.setContent("农业、草药");
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(charDoc));
        when(docRepository.saveAndFlush(any(ActorKnowledgeDoc.class))).thenAnswer(inv -> {
            ActorKnowledgeDoc d = inv.getArgument(0);
            if (d.getId() == null) d.setId(200L + counter++);
            return d;
        });

        int synced = svc.syncWorldSetting(7L, 1L);

        assertEquals(7, synced);
        verify(docRepository, times(7)).saveAndFlush(any(ActorKnowledgeDoc.class));
        // 每次向量化：项目级（characterId=null）
        verify(vectorizerService, times(7))
                .vectorize(eq(7L), eq(1L), anyLong(), anyString(), isNull(), anyString());
        // 角色级既有文档不受影响（未保存/未向量化）
        verify(docRepository, never()).save(charDoc);
    }

    /** 幂等：全部文档内容未变 → 不重复向量化（返回 0，embedding 零消耗）。 */
    @Test
    void syncIdempotentSkipsUnchangedContent() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(setting()));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                location(1L, "云来茶馆", "茶馆", "临街二层小楼。")));
        // 既有项目级文档 = 与设定源完全一致（content 相同，含锚词引导句）
        ActorKnowledgeDoc geo = doc(10L, WorldKnowledgeSyncService.TITLE_GEOGRAPHY,
                WorldKnowledgeSyncService.ANCHOR_GEOGRAPHY + "\n青禾村坐落在青岳山东麓、灵溪河畔。");
        ActorKnowledgeDoc fac = doc(11L, WorldKnowledgeSyncService.TITLE_FACTIONS,
                WorldKnowledgeSyncService.ANCHOR_FACTIONS + "\n① 沈氏宗族：村长一脉。");
        ActorKnowledgeDoc rules = doc(12L, WorldKnowledgeSyncService.TITLE_RULES,
                WorldKnowledgeSyncService.ANCHOR_RULES + "\n灵气、灵脉、吐纳。");
        ActorKnowledgeDoc cul = doc(13L, WorldKnowledgeSyncService.TITLE_CULTURE,
                WorldKnowledgeSyncService.ANCHOR_CULTURE + "\n四季农时与节气物候。");
        ActorKnowledgeDoc his = doc(14L, WorldKnowledgeSyncService.TITLE_HISTORY,
                WorldKnowledgeSyncService.ANCHOR_HISTORY + "\n百年建村史。");
        ActorKnowledgeDoc overview = doc(15L, WorldKnowledgeSyncService.TITLE_OVERVIEW,
                WorldKnowledgeSyncService.ANCHOR_OVERVIEW
                        + "\n【青禾村 · 灵溪河畔的仙凡村落】题材：仙侠 / 东方玄幻；时代：大雍朝\n完整世界观自由文本。");
        ActorKnowledgeDoc locs = doc(16L, WorldKnowledgeSyncService.TITLE_LOCATIONS,
                WorldKnowledgeSyncService.ANCHOR_LOCATIONS + "\n云来茶馆（茶馆）：临街二层小楼。");
        when(docRepository.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(List.of(geo, fac, rules, cul, his, overview, locs));

        int synced = svc.syncWorldSetting(7L, 1L);

        assertEquals(0, synced);
        verify(docRepository, never()).saveAndFlush(any(ActorKnowledgeDoc.class));
        verifyNoInteractions(vectorizerService);
    }

    /** 内容变化：仅变化文档更新 + 重新向量化；未变化文档跳过。 */
    @Test
    void syncUpdatesOnlyChangedDocs() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(setting()));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        // 既有：地理内容旧了（设定源已更新），文化内容一致（含锚词引导句）
        ActorKnowledgeDoc geo = doc(10L, WorldKnowledgeSyncService.TITLE_GEOGRAPHY, "旧地理设定。");
        ActorKnowledgeDoc cul = doc(13L, WorldKnowledgeSyncService.TITLE_CULTURE,
                WorldKnowledgeSyncService.ANCHOR_CULTURE + "\n四季农时与节气物候。");
        ActorKnowledgeDoc overview = doc(15L, WorldKnowledgeSyncService.TITLE_OVERVIEW,
                WorldKnowledgeSyncService.ANCHOR_OVERVIEW
                        + "\n【青禾村 · 灵溪河畔的仙凡村落】题材：仙侠 / 东方玄幻；时代：大雍朝\n完整世界观自由文本。");
        ActorKnowledgeDoc fac = doc(11L, WorldKnowledgeSyncService.TITLE_FACTIONS,
                WorldKnowledgeSyncService.ANCHOR_FACTIONS + "\n① 沈氏宗族：村长一脉。");
        ActorKnowledgeDoc rules = doc(12L, WorldKnowledgeSyncService.TITLE_RULES,
                WorldKnowledgeSyncService.ANCHOR_RULES + "\n灵气、灵脉、吐纳。");
        ActorKnowledgeDoc his = doc(14L, WorldKnowledgeSyncService.TITLE_HISTORY,
                WorldKnowledgeSyncService.ANCHOR_HISTORY + "\n百年建村史。");
        when(docRepository.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(List.of(geo, fac, rules, cul, his, overview));
        when(docRepository.saveAndFlush(any(ActorKnowledgeDoc.class))).thenAnswer(inv -> inv.getArgument(0));

        int synced = svc.syncWorldSetting(7L, 1L);

        // 仅地理更新（1 份同步）；其余 5 份内容一致跳过
        assertEquals(1, synced);
        verify(docRepository, times(1)).saveAndFlush(any(ActorKnowledgeDoc.class));
        verify(vectorizerService, times(1))
                .vectorize(eq(7L), eq(1L), eq(10L), eq(WorldKnowledgeSyncService.TITLE_GEOGRAPHY), isNull(),
                        eq(WorldKnowledgeSyncService.ANCHOR_GEOGRAPHY + "\n青禾村坐落在青岳山东麓、灵溪河畔。"));
    }

    /** 向量化失败不阻断同步（文档已落库，走文本降级），其余文档继续。 */
    @Test
    void syncContinuesWhenVectorizeThrows() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(setting()));
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        when(docRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(docRepository.saveAndFlush(any(ActorKnowledgeDoc.class))).thenAnswer(inv -> {
            ActorKnowledgeDoc d = inv.getArgument(0);
            if (d.getId() == null) d.setId(500L + counter++);
            return d;
        });
        org.mockito.Mockito.doThrow(new RuntimeException("embedding 供应商不可用"))
                .when(vectorizerService).vectorize(eq(7L), eq(1L), anyLong(), anyString(), isNull(), anyString());

        int synced = svc.syncWorldSetting(7L, 1L);

        // 6 份设定分段全部落库成功（向量化失败降级不阻断）
        assertEquals(6, synced);
        verify(docRepository, times(6)).saveAndFlush(any(ActorKnowledgeDoc.class));
    }

    private ActorKnowledgeDoc doc(Long id, String title, String content) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setProjectId(1L);
        d.setTitle(title);
        d.setContent(content);
        d.setStatus(1);
        return d;
    }
}
