package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CharacterService 角色关系拓扑图构建 + 幽灵关系名称回填逻辑单元测试。
 * <p>验证 {@link CharacterService#buildGraph}（NPC/普通型 NPC/幽灵三类节点 + 端点解析）
 * 与 {@link CharacterService#backfillRelation}（按名称回填 id）纯函数。</p>
 */
class CharacterServiceTest {

    /** 构造 NPC 角色实体 */
    private ActorCharacter npc(Long id, String name, String type, int importance, int isProtagonist) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setImportance(importance);
        c.setIsProtagonist(isProtagonist);
        return c;
    }

    /** 构造普通型 NPC 实体 */
    private ActorCharacter ordinary(Long id, String name, String affiliation, String occupation) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setName(name);
        n.setAffiliation(affiliation);
        n.setOccupation(occupation);
        // G1a：单主表下普通型显式 type=common；运行态 state 已迁 actor_state，实体上不再有该列
        n.setType("common");
        return n;
    }

    /** 构造关系实体 */
    private ActorCharacterRelation rel(Long id, Long from, Long to, String fromName, String toName, String type, String desc) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setId(id);
        r.setFromCharacterId(from);
        r.setToCharacterId(to);
        r.setFromName(fromName);
        r.setToName(toName);
        r.setRelationType(type);
        r.setDescription(desc);
        return r;
    }

    /** 全部 NPC 入图（含孤立）+ 关系端点解析为 npc-key */
    @Test
    void buildGraphIncludesAllNpcsAndResolvesNpcKeys() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        ActorCharacter b = npc(2L, "沈夜", "special", 4, 0);
        ActorCharacter c = npc(3L, "苏陌", "common", 3, 0);
        CharacterRelationGraphVO g = CharacterService.buildGraph(
                List.of(a, b, c), List.of(),
                List.of(rel(10L, 1L, 2L, "林晚", "沈夜", "师徒", "传剑")));

        assertEquals(3, g.nodes().size());
        assertTrue(g.nodes().stream().anyMatch(n -> n.id().equals("npc-1") && n.name().equals("林晚") && "npc".equals(n.kind())));
        assertEquals(1, g.relations().size());
        CharacterRelationGraphVO.EdgeVO e = g.relations().get(0);
        assertEquals("npc-1", e.fromKey());
        assertEquals("npc-2", e.toKey());
        assertEquals("师徒", e.relationType());
    }

    /** 普通型 NPC（单表扁平）全部入图 + 关系端点按名称解析到 crowd-key（普通人群节点为具体人名；节点 key 仍用 crowd- 前缀，统一"引用"是 npc:{id}） */
    @Test
    void buildGraphIncludesOrdinaryNpcsAndResolvesCrowdKeys() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        ActorCharacter m = ordinary(9L, "小二", "临江酒楼", "店小二");
        CharacterRelationGraphVO g = CharacterService.buildGraph(
                List.of(a), List.of(m),
                List.of(rel(10L, 1L, 0L, "林晚", "小二", "主顾", "常去酒楼")));

        // NPC + 普通型 NPC 均入图
        assertTrue(g.nodes().stream().anyMatch(n -> n.id().equals("crowd-9") && "crowd".equals(n.kind()) && "小二".equals(n.name())));
        CharacterRelationGraphVO.NodeVO crowdNode = g.nodes().stream().filter(n -> n.id().equals("crowd-9")).findFirst().orElseThrow();
        assertEquals("临江酒楼", crowdNode.crowdName());
        assertEquals("店小二", crowdNode.occupation());
        // 边端点解析：林晚→npc-1，小二→crowd-9
        CharacterRelationGraphVO.EdgeVO e = g.relations().get(0);
        assertEquals("npc-1", e.fromKey());
        assertEquals("crowd-9", e.toKey());
    }

    /** 关系引用「两表都不存在」的角色 → 幽灵节点（仅名称，id=0） + 边解析为 ghost-key；缺失端点过滤 */
    @Test
    void buildGraphCreatesGhostNodesForUnknownEndpointsAndSkipsUnresolvable() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        CharacterRelationGraphVO g = CharacterService.buildGraph(
                List.of(a), List.of(),
                List.of(
                        // 幽灵端点：两表都没有「阿明」
                        rel(10L, 1L, 0L, "林晚", "阿明", "故交", "旧识"),
                        // 完全无法解析（无 id 无名称）：丢弃
                        rel(11L, null, 0L, null, "阿明", "师徒", "无端点"),
                        // 自环（from==to 同名同 id）：丢弃
                        rel(12L, 1L, 1L, "林晚", "林晚", "自我", "分裂")));

        // NPC + 幽灵 阿明 两个节点
        assertEquals(2, g.nodes().size());
        CharacterRelationGraphVO.NodeVO ghost = g.nodes().stream().filter(n -> "ghost".equals(n.kind())).findFirst().orElseThrow();
        assertEquals("ghost-阿明", ghost.id());
        assertEquals("阿明", ghost.name());
        // 仅 1 条边（幽灵边），自环与无端点边被过滤
        assertEquals(1, g.relations().size());
        assertEquals("ghost-阿明", g.relations().get(0).toKey());
    }

    /** 幽灵按名称回填：from/to 名称匹配新角色名且 id=0 → 回填新角色 id（幂等、不覆盖已有关联） */
    @Test
    void backfillRelationFillsGhostEndpointsByName() {
        ActorCharacterRelation r = rel(10L, 0L, 1L, "阿明", "林晚", "故交", "旧识");
        assertTrue(CharacterService.backfillRelation(r, 99L, "阿明"));
        assertEquals(99L, r.getFromCharacterId());
        assertEquals(1L, r.getToCharacterId());

        // 幂等：再次调用不再修改
        assertFalse(CharacterService.backfillRelation(r, 99L, "阿明"));

        // 已有关联（id>0）不覆盖
        ActorCharacterRelation r2 = rel(11L, 2L, 0L, "沈夜", "阿明", "挚友", "旧识");
        assertTrue(CharacterService.backfillRelation(r2, 99L, "阿明"));
        assertEquals(2L, r2.getFromCharacterId());
        assertEquals(99L, r2.getToCharacterId());

        // 自环防护：新角色两端都命中（from==to 同名）时只回填一端，保证不会把两端都指向同一新角色
        ActorCharacterRelation r3 = rel(12L, 0L, 0L, "阿明", "阿明", "自我", "分裂");
        assertTrue(CharacterService.backfillRelation(r3, 99L, "阿明"));
        assertEquals(99L, r3.getFromCharacterId());
        assertEquals(0L, r3.getToCharacterId());
        assertFalse(r3.getFromCharacterId().equals(r3.getToCharacterId()));
    }

    /** 空数据边界：无角色/无关系/无普通型 NPC → 空节点与空关系（不抛异常） */
    @Test
    void buildGraphHandlesEmptyData() {
        CharacterRelationGraphVO g = CharacterService.buildGraph(List.of(), List.of(), List.of());
        assertEquals(0, g.nodes().size());
        assertEquals(0, g.relations().size());

        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        CharacterRelationGraphVO g2 = CharacterService.buildGraph(List.of(a), List.of(), List.of());
        assertEquals(1, g2.nodes().size());
        assertTrue(g2.relations().isEmpty());
    }

    // ==================== C-10 默认重要度（2026-08-21 阶段 A3） ====================

    /** 未填重要度 + 主角 → 默认 4（主角≥4，配得上 AI 投入） */
    @Test
    void defaultImportanceProtagonistIsFour() {
        assertEquals(4, CharacterService.resolveDefaultImportance(null, 1));
    }

    /** 未填重要度 + 非主角 → 默认 3（≥3 走 AI 行动决策，避免默认 1 永远程序化） */
    @Test
    void defaultImportanceNonProtagonistIsThree() {
        assertEquals(3, CharacterService.resolveDefaultImportance(null, 0));
        // isProtagonist 未指定（null）同样按非主角处理
        assertEquals(3, CharacterService.resolveDefaultImportance(null, null));
    }

    /** 显式提供的重要度原样保留（尊重用户选择，不覆盖） */
    @Test
    void explicitImportanceIsPreserved() {
        assertEquals(2, CharacterService.resolveDefaultImportance(2, 1));
        assertEquals(5, CharacterService.resolveDefaultImportance(5, 0));
        assertEquals(1, CharacterService.resolveDefaultImportance(1, null));
    }

    /** 实体内联初始值守卫（C-10 冒烟发现）：ActorCharacter 新建即 importance=1 / isProtagonist=0（非 null）——
     *  create() 必须用 DTO 原始值判断「是否显式提供」，读实体永远非 null 会误判导致默认逻辑失效 */
    @Test
    void entityFieldInitializersAreNonNull() {
        ActorCharacter c = new ActorCharacter();
        assertEquals(1, c.getImportance());
        assertEquals(0, c.getIsProtagonist());
    }

    // ==================== D1 F6 正式 characterRef 端点解析（修 C-13） ====================

    /** 关系端点存正式 ref：统一 {@code npc:{id}} → 按 id 归属定节点 key（npc-/crowd-；重名普通 NPC 精确区分） */
    @Test
    void buildGraphResolvesFormalRefs() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        ActorCharacter m = ordinary(9L, "小二", "临江酒楼", "店小二");
        // 正式 ref 行：from_ref=npc:9（与普通 NPC 同名重名场景），to 为 npc:1
        ActorCharacterRelation r = rel(10L, 0L, 1L, "小二", "林晚", "主顾", "常去酒楼");
        r.setFromRef("npc:9");
        r.setToRef("npc:1");
        r.setAffinity(20);
        r.setTrust(30);
        r.setRelationState("acquaintance");

        CharacterRelationGraphVO g = CharacterService.buildGraph(List.of(a), List.of(m), List.of(r));

        CharacterRelationGraphVO.EdgeVO e = g.relations().get(0);
        // 正式 ref 优先：即使 from_name=小二（与 npc:9 同名的「另一普通 NPC」），也精确解析到 crowd-9
        assertEquals("crowd-9", e.fromKey());
        assertEquals("npc-1", e.toKey());
        assertEquals("npc:9", e.fromRef());
        assertEquals("npc:1", e.toRef());
        // D1 F6 动态字段随边返回
        assertEquals(Integer.valueOf(20), e.affinity());
        assertEquals(Integer.valueOf(30), e.trust());
        assertEquals("acquaintance", e.relationState());
        assertEquals("相识", e.relationStateLabel());
    }

    /** 正式 ref 指向已删除/不存在的角色 → 回退名称路径（幽灵或现存角色），不抛异常 */
    @Test
    void buildGraphFallsBackWhenFormalRefMissing() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        ActorCharacterRelation r = rel(10L, 1L, 0L, "林晚", "阿明", "故交", "旧识");
        r.setToRef("npc:999"); // 指向不存在的角色
        CharacterRelationGraphVO g = CharacterService.buildGraph(List.of(a), List.of(), List.of(r));
        // 回退：to 按名称「阿明」建幽灵节点
        assertEquals("ghost-阿明", g.relations().get(0).toKey());
    }
}

