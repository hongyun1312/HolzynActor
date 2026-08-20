package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
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
    private ActorOrdinaryNpc ordinary(Long id, String name, String affiliation, String occupation) {
        ActorOrdinaryNpc n = new ActorOrdinaryNpc();
        n.setId(id);
        n.setName(name);
        n.setAffiliation(affiliation);
        n.setOccupation(occupation);
        n.setState("idle");
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

    /** 普通型 NPC（单表扁平）全部入图 + 关系端点按名称解析到 crowd-key（普通人群节点为具体人名） */
    @Test
    void buildGraphIncludesOrdinaryNpcsAndResolvesCrowdKeys() {
        ActorCharacter a = npc(1L, "林晚", "special", 5, 1);
        ActorOrdinaryNpc m = ordinary(9L, "小二", "临江酒楼", "店小二");
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
}

