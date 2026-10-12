package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * CharacterRelationService AI 关系输出解析逻辑单元测试。
 * <p>验证 {@link CharacterRelationService#parseDrafts}：顶层数组/对象包裹/Markdown 代码块、
 * 自环过滤、重复去重、类型缺省、字段截断、空输入边界。</p>
 */
class CharacterRelationServiceTest {

    /** 顶层 JSON 数组 */
    @Test
    void parseDraftsReadsTopLevelArray() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"传剑\"},"
                        + "{\"from\":\"雪球\",\"to\":\"奶糖\",\"relationType\":\"挚友\",\"description\":\"互相扶持\"}]");
        assertEquals(2, drafts.size());
        assertEquals("奶糖", drafts.get(0).from());
        assertEquals("暖阳", drafts.get(0).to());
        assertEquals("师徒", drafts.get(0).relationType());
        assertEquals("传剑", drafts.get(0).description());
    }

    /** 顶层对象含 relations 数组（兼容某些模型返回 {relations:[...]}） */
    @Test
    void parseDraftsReadsRelationsFieldObject() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "{\"relations\":[{\"from\":\"林晚\",\"to\":\"沈夜\",\"relationType\":\"师徒\",\"description\":\"传剑\"}]}");
        assertEquals(1, drafts.size());
        assertEquals("林晚", drafts.get(0).from());
    }

    /** Markdown 代码块包裹 + 首尾说明文字 */
    @Test
    void parseDraftsStripsCodeFenceAndSurroundingText() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "以下是识别到的关系：\n```json\n[{\"from\":\"奶糖\",\"to\":\"雪球\",\"relationType\":\"挚友\",\"description\":\"\"}]\n```\n完毕");
        assertEquals(1, drafts.size());
        assertEquals("挚友", drafts.get(0).relationType());
    }

    /** 自环（from==to）过滤；from/to 缺失过滤；同对同类型去重 */
    @Test
    void parseDraftsFiltersSelfLoopAndDeduplicates() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"奶糖\",\"relationType\":\"自我\",\"description\":\"自环\"},"
                        + "{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"a\"},"
                        + "{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"b\"},"
                        + "{\"from\":\"\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"缺from\"},"
                        + "{\"from\":\"雪球\",\"to\":\"暖阳\",\"relationType\":\"\",\"description\":\"缺类型\"}]");
        assertEquals(2, drafts.size());
        // 去重后保留第一条
        assertEquals("a", drafts.get(0).description());
        // 缺类型 → 默认「未知关系」
        assertEquals("未知关系", drafts.get(1).relationType());
    }

    /** 关系类型与描述超长截断（type≤50 / desc≤255） */
    @Test
    void parseDraftsTruncatesOverlongFields() {
        String longType = "非".repeat(80);
        String longDesc = "描".repeat(400);
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"" + longType + "\",\"description\":\"" + longDesc + "\"}]");
        assertEquals(1, drafts.size());
        assertEquals(50, drafts.get(0).relationType().length());
        assertEquals(255, drafts.get(0).description().length());
    }

    /** 空输入 / 非法 JSON / 空数组 → 空列表 */
    @Test
    void parseDraftsHandlesEmptyAndInvalidInput() {
        assertTrue(CharacterRelationService.parseDrafts(null).isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("   ").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("不是 JSON").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("[]").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("{\"relations\":[]}").isEmpty());
    }

    // ==================== D1 F6 关系 AI 精修输出解析（relation_refine） ====================

    /** 顶层 JSON 对象：affinity/trust/relationState/description 全部解析 */
    @Test
    void parseRefineResultReadsTopLevelObject() {
        tools.jackson.databind.JsonNode node = CharacterRelationService.parseRefineResult(
                "{\"affinity\":45,\"trust\":60,\"relationState\":\"friend\",\"description\":\"相互扶持的挚友\",\"reason\":\"多次共同行动\"}");
        assertEquals(45, node.path("affinity").asInt());
        assertEquals(60, node.path("trust").asInt());
        assertEquals("friend", node.path("relationState").asText());
        assertEquals("相互扶持的挚友", node.path("description").asText());
    }

    /** 非法 JSON / 非对象输出 → 空对象节点（不抛异常，调用方按缺省钳制） */
    @Test
    void parseRefineResultFallsBackToEmptyObject() {
        tools.jackson.databind.JsonNode empty = CharacterRelationService.parseRefineResult("不是 JSON");
        assertTrue(empty.isObject());
        assertTrue(empty.isEmpty());
        tools.jackson.databind.JsonNode array = CharacterRelationService.parseRefineResult("[{\"a\":1}]");
        assertTrue(array.isEmpty(), "顶层数组视为解析失败");
    }

    // ==================== 治理批次 0 · 卡5 亡偶守卫（G-REMARRY-02） ====================

    /**
     * G-REMARRY-02（卡5 亡偶守卫，起始未知→先跑再定）：AI 精修/draft 入库路径给亡偶设 ROMANTIC 前必须过滤。
     * <p>被测方法为 {@link CharacterRelationService#batchSave}（AI 草稿应用路径，此前无测试覆盖）。
     * 输入 draft 含「npc:1→npc:2 ROMANTIC（亡偶）」与「npc:1→npc:3 ROMANTIC（生者）」——
     * 硬断言：亡偶候选被拒绝（不写 ROMANTIC 行），生者候选正常写入。</p>
     */
    @Test
    void refineDraft_deadSpouseRejectedFromRomanticCandidate() {
        // ===== 手工构造真实服务（内存关系表 fake）=====
        com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository relationRepository =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository.class);
        List<com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation> store = new java.util.ArrayList<>();
        org.mockito.Mockito.lenient().when(relationRepository.save(any()))
                .thenAnswer(inv -> {
                    com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation r = inv.getArgument(0);
                    if (r.getId() == null) {
                        r.setId((long) (store.size() + 1));
                    }
                    store.removeIf(x -> x.getId().equals(r.getId()));
                    store.add(r);
                    return r;
                });
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            store.removeAll(inv.getArgument(0));
            return null;
        }).when(relationRepository).deleteAll(any());
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository characterRepository =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        com.holzyn.actor.domain.npc.profile.entity.ActorCharacter dead = new com.holzyn.actor.domain.npc.profile.entity.ActorCharacter();
        dead.setId(2L); dead.setProjectId(1L); dead.setName("白小满"); dead.setType("special");
        dead.setStage(com.holzyn.actor.domain.growth.stage.GrowthStage.DECEASED.name());
        com.holzyn.actor.domain.npc.profile.entity.ActorCharacter alive = new com.holzyn.actor.domain.npc.profile.entity.ActorCharacter();
        alive.setId(3L); alive.setProjectId(1L); alive.setName("李翠花"); alive.setType("common");
        alive.setStage(com.holzyn.actor.domain.growth.stage.GrowthStage.YOUTH.name());
        org.mockito.Mockito.lenient().when(characterRepository.findById(2L)).thenReturn(Optional.of(dead));
        org.mockito.Mockito.lenient().when(characterRepository.findById(3L)).thenReturn(Optional.of(alive));
        CharacterRelationService svc = new CharacterRelationService(
                org.mockito.Mockito.mock(CharacterService.class),
                relationRepository,
                characterRepository,
                org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.account.CurrentUserProvider.class),
                org.mockito.Mockito.mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.usage.service.UsageLogService.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.learning.RelationDynamicsService.class));

        // ===== 触发：AI 草稿入库（含亡偶 ROMANTIC 候选 + 生者候选）=====
        var dto = new com.holzyn.actor.domain.character.dto.RelationBatchDTO("supplement", null, null,
                List.of(
                        new com.holzyn.actor.domain.character.vo.RelationDraftVO("npc:1", "npc:2", "ROMANTIC", "旧情难忘"),
                        new com.holzyn.actor.domain.character.vo.RelationDraftVO("npc:1", "npc:3", "ROMANTIC", "新相识")));

        svc.batchSave(1L, dto);

        // 硬断言：亡偶候选被拒绝（不写 ROMANTIC 行），生者候选正常写入
        assertEquals(1, store.size(), "亡偶候选被拒绝，生者候选正常");
        assertEquals("npc:3", store.get(0).getToRef(), "仅生者候选落库");
        assertEquals("ROMANTIC", store.get(0).getRelationType());
    }

    // ==================== init 关系幂等（P2-3，2026-10-02：supplement 补充模式去重根治） ====================

    /** 手工构造真实服务 + 内存关系表（findByProjectId 返回实时 store，供去重比对）。 */
    private static class RelationHarness {
        final com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository relationRepository;
        final com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository characterRepository;
        final List<com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation> store =
                new java.util.ArrayList<>();
        final CharacterRelationService svc;

        RelationHarness() {
            relationRepository =
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository.class);
            characterRepository =
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
            org.mockito.Mockito.lenient().when(relationRepository.findByProjectId(any())).thenAnswer(inv -> store);
            org.mockito.Mockito.lenient().when(relationRepository.save(any()))
                    .thenAnswer(inv -> {
                        com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation r = inv.getArgument(0);
                        if (r.getId() == null) {
                            r.setId((long) (store.size() + 1));
                        }
                        store.removeIf(x -> x.getId() != null && x.getId().equals(r.getId()));
                        store.add(r);
                        return r;
                    });
            org.mockito.Mockito.lenient().doAnswer(inv -> {
                store.removeAll(inv.getArgument(0));
                return null;
            }).when(relationRepository).deleteAll(any());
            org.mockito.Mockito.lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any()))
                    .thenReturn(java.util.List.of());
            svc = new CharacterRelationService(
                    org.mockito.Mockito.mock(CharacterService.class),
                    relationRepository,
                    characterRepository,
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.account.CurrentUserProvider.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.usage.service.UsageLogService.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class),
                    org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.learning.RelationDynamicsService.class));
        }

        /** 幽灵端点关系行（id=0，仅名称）。 */
        com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation ghostRow(
                String from, String to, String type) {
            com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation r =
                    new com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation();
            r.setId((long) (store.size() + 1));
            r.setProjectId(1L);
            r.setFromCharacterId(0L);
            r.setToCharacterId(0L);
            r.setFromName(from);
            r.setToName(to);
            r.setRelationType(type);
            return r;
        }

        /** supplement 入库调用（草稿列表 → {added, skipped, total}）。 */
        Map<String, Object> batch(List<com.holzyn.actor.domain.character.vo.RelationDraftVO> drafts) {
            return svc.batchSave(1L, new com.holzyn.actor.domain.character.dto.RelationBatchDTO(
                    "supplement", null, null, drafts));
        }

        static com.holzyn.actor.domain.character.vo.RelationDraftVO draft(String from, String to, String type) {
            return new com.holzyn.actor.domain.character.vo.RelationDraftVO(from, to, type, "描述");
        }
    }

    /** 补充模式幂等：同端点对+同类型已存在 → 跳过（added=0，skipped=1，库不变）。 */
    @Test
    void batchSaveSupplement_dedupsExistingPairType() {
        RelationHarness h = new RelationHarness();
        h.store.add(h.ghostRow("沈砚秋", "李翠花", "师徒"));

        Map<String, Object> result = h.batch(List.of(RelationHarness.draft("沈砚秋", "李翠花", "师徒")));

        assertEquals(0, result.get("added"), "已存在同对同类型 → 不重复入库");
        assertEquals(1, result.get("skipped"), "去重跳过 1 条");
        assertEquals(1, h.store.size(), "库中仍 1 条（重跑 init 收敛到同一状态）");
    }

    /** 补充模式幂等：方向翻转容忍（A→B 与 B→A 同键）。 */
    @Test
    void batchSaveSupplement_directionFlipTolerated() {
        RelationHarness h = new RelationHarness();
        h.store.add(h.ghostRow("沈砚秋", "李翠花", "师徒"));

        Map<String, Object> result = h.batch(List.of(RelationHarness.draft("李翠花", "沈砚秋", "师徒")));

        assertEquals(0, result.get("added"), "方向翻转不产生重复行");
        assertEquals(1, result.get("skipped"));
    }

    /** 补充模式幂等：不同类型允许共存（师徒+邻居合法并存）。 */
    @Test
    void batchSaveSupplement_differentTypeKept() {
        RelationHarness h = new RelationHarness();
        h.store.add(h.ghostRow("沈砚秋", "李翠花", "师徒"));

        Map<String, Object> result = h.batch(List.of(RelationHarness.draft("沈砚秋", "李翠花", "邻居")));

        assertEquals(1, result.get("added"), "不同类型非重复 → 正常新增");
        assertEquals(0, result.get("skipped"));
        assertEquals(2, h.store.size());
    }

    /** 补充模式幂等：同批内去重（同一草稿出现两次 → 只写 1 条）。 */
    @Test
    void batchSaveSupplement_withinBatchDedup() {
        RelationHarness h = new RelationHarness();

        Map<String, Object> result = h.batch(List.of(
                RelationHarness.draft("沈砚秋", "李翠花", "师徒"),
                RelationHarness.draft("沈砚秋", "李翠花", "师徒")));

        assertEquals(1, result.get("added"));
        assertEquals(1, result.get("skipped"), "批内第二次视为重复");
        assertEquals(1, h.store.size());
    }

    /** 补充模式幂等：幽灵行（id=0）与角色建档后正式端点同名同类型 → 跨形态去重。 */
    @Test
    void batchSaveSupplement_ghostAndSpecialSameNameDedup() {
        RelationHarness h = new RelationHarness();
        h.store.add(h.ghostRow("张三", "李四", "邻居"));
        // 角色建档：张三=special id=5（同名）→ 草稿解析为正式端点
        com.holzyn.actor.domain.npc.profile.entity.ActorCharacter zhangSan =
                new com.holzyn.actor.domain.npc.profile.entity.ActorCharacter();
        zhangSan.setId(5L);
        zhangSan.setProjectId(1L);
        zhangSan.setName("张三");
        zhangSan.setType("special");
        org.mockito.Mockito.lenient().when(h.characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(java.util.List.of(zhangSan));

        Map<String, Object> result = h.batch(List.of(RelationHarness.draft("张三", "李四", "邻居")));

        assertEquals(0, result.get("added"), "幽灵与正式端点同名同类型 → 视为同一关系");
        assertEquals(1, result.get("skipped"));
        assertEquals(1, h.store.size(), "不产生重复行（角色建档后重跑补充收敛）");
    }

    /** rebuild 模式：先清空相关范围再写入（天然幂等，去重不影响）。 */
    @Test
    void batchSaveRebuild_clearsAndWritesAll() {
        RelationHarness h = new RelationHarness();
        h.store.add(h.ghostRow("旧关系", "旧目标", "师徒"));
        List<com.holzyn.actor.domain.character.vo.RelationDraftVO> drafts = List.of(
                RelationHarness.draft("沈砚秋", "李翠花", "师徒"),
                RelationHarness.draft("沈砚秋", "李翠花", "师徒"),
                RelationHarness.draft("林晚", "沈夜", "挚友"));

        Map<String, Object> result = h.svc.batchSave(1L,
                new com.holzyn.actor.domain.character.dto.RelationBatchDTO("rebuild", null, null, drafts));

        assertEquals(2, result.get("added"), "清空后写入（批内重复仍去重）");
        assertEquals(1, result.get("skipped"), "批内重复跳过");
        assertEquals(2, h.store.size(), "旧行已清空，只剩新写入");
        assertEquals(3, result.get("total"));
    }
}
