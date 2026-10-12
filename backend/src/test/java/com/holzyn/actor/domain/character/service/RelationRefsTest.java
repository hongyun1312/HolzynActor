package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 角色统一引用（characterRef）解析工具单元测试（D1 F6，domain/character/service）。
 *
 * <p><b>O6 G1a 重写（2026-09-21）</b>：统一身份改为单前缀 {@code npc:{actor_profile.id}}，特殊/普通
 * 合并到同一张 {@code actor_profile}——本测试相应从「special:{id} / crowd:{id} 双前缀 + 双仓库」改为
 * 单前缀 + 单仓库口径。验证 {@link RelationRefs#resolve}（{@code npc:{id}}/名称 → 正式 ref 或幽灵）、
 * {@link RelationRefs#endpointMatches}（正式端点 id 精确优先 + ref 兜底 + 幽灵名称只匹配幽灵行，修 C-13）、
 * {@link RelationRefs#storeOf}（端点 → 行存储三元组：正式端点恒有 id，不再有 crowd 的 id=null 特例）。</p>
 */
class RelationRefsTest {

    private ActorCharacterRepository characterRepository;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
    }

    private ActorCharacter npc(Long id, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        return c;
    }

    /** 普通型夹具（G1a：单主表下须显式 type=common；本工具类不按类型分叉，仅用于夹具真实性） */
    private ActorCharacter common(Long id, String name) {
        ActorCharacter n = npc(id, name);
        n.setType("common");
        return n;
    }

    /** {@code npc:{id}} → 正式 ref + id（恒为 actor_profile.id）+ 角色名快照 */
    @Test
    void resolvesNpcRef() {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(npc(3L, "林晚")));
        RelationRefs.Endpoint ep = RelationRefs.resolve(100L, "npc:3", characterRepository);
        assertEquals("npc:3", ep.ref());
        assertEquals(3L, ep.id());
        assertEquals("林晚", ep.name());
    }

    /**
     * {@code npc:{id}} 指向不存在的行：仍是正式端点（ref + id 保留），仅名称快照为 null。
     * <p>G1a 语义变化：单表后不再有「crowd 端点 id=null」的特例——id 恒为 {@code actor_profile.id}。</p>
     */
    @Test
    void resolvesNpcRefWithoutRowKeepsIdAndNullName() {
        when(characterRepository.findById(9L)).thenReturn(Optional.empty());
        RelationRefs.Endpoint ep = RelationRefs.resolve(100L, "npc:9", characterRepository);
        assertEquals("npc:9", ep.ref());
        assertEquals(9L, ep.id(), "单表后端点 id 恒为 actor_profile.id，不再有 id=null 特例");
        assertNull(ep.name());
    }

    /** 纯名称命中项目内角色（单表：特殊/普通同表）→ 转正式 ref（修 C-13：不落幽灵） */
    @Test
    void resolvesNameToFormalRef() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(npc(3L, "林晚")));
        RelationRefs.Endpoint ep = RelationRefs.resolve(100L, "林晚", characterRepository);
        assertEquals("npc:3", ep.ref());
        assertEquals(3L, ep.id());
        assertEquals("林晚", ep.name());
    }

    /** 纯名称命中普通型 NPC → 同样转正式 ref（单表后不再区分节点类型前缀，重名以 npc:{id} 区分） */
    @Test
    void resolvesNameToCommonNpcFormalRef() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenReturn(List.of(common(9L, "小二")));
        RelationRefs.Endpoint ep = RelationRefs.resolve(100L, "小二", characterRepository);
        assertEquals("npc:9", ep.ref());
        assertEquals(9L, ep.id());
        assertEquals("小二", ep.name());
    }

    /** 单表都不命中 → 幽灵端点（ref=null，仅名称兜底） */
    @Test
    void resolvesUnknownNameToGhost() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        RelationRefs.Endpoint ep = RelationRefs.resolve(100L, "阿明", characterRepository);
        assertNull(ep.ref());
        assertNull(ep.id());
        assertEquals("阿明", ep.name());
    }

    /**
     * 空输入 → null（不抛异常）；历史双前缀/非法引用不再是正式端点 → 落幽灵（G1a 单前缀严格口径）。
     */
    @Test
    void rejectsBlankAndTreatsLegacyPrefixAsGhost() {
        assertNull(RelationRefs.resolve(100L, "", characterRepository));
        assertNull(RelationRefs.resolve(100L, "  ", characterRepository));
        // G1a：special:/crowd: 前缀不再是合法身份引用（严格单前缀 npc:）→ 按名称兜底为幽灵端点
        for (String legacy : List.of("special:abc", "crowd:0", "special:3", "crowd:9")) {
            RelationRefs.Endpoint ep = RelationRefs.resolve(100L, legacy, characterRepository);
            assertNull(ep.ref(), "历史前缀不转正式 ref：" + legacy);
            assertNull(ep.id(), "历史前缀不解析 id：" + legacy);
            assertEquals(legacy, ep.name(), "落幽灵，名称原样保留：" + legacy);
        }
    }

    /**
     * 端点→行存储：正式端点一律存 id+名+ref；幽灵存名（id=0、ref=null）。
     * <p>G1a：不再有「crowd 端点 id=null → 只存名+ref」的特例——id 缺失即按幽灵口径处理。</p>
     */
    @Test
    void storeOfMapsEndpointToColumns() {
        RelationRefs.Store formal = RelationRefs.storeOf(new RelationRefs.Endpoint("npc:3", 3L, "林晚"));
        assertEquals(3L, formal.characterId());
        assertEquals("npc:3", formal.ref());
        assertEquals("林晚", formal.name());

        // 正式 ref 但在 actor_profile 中查不到 id（脏引用）→ 落幽灵口径（id=0、ref=null）
        RelationRefs.Store orphan = RelationRefs.storeOf(new RelationRefs.Endpoint("npc:9", null, "小二"));
        assertEquals(0L, orphan.characterId());
        assertNull(orphan.ref());

        RelationRefs.Store ghost = RelationRefs.storeOf(new RelationRefs.Endpoint(null, null, "阿明"));
        assertEquals(0L, ghost.characterId());
        assertNull(ghost.ref());
        assertEquals("阿明", ghost.name());
    }

    /** 端点匹配：正式端点 id 精确优先（ref 仅作存量行兜底）；幽灵名称只匹配幽灵行（防重名误匹配） */
    @Test
    void endpointMatchesPreferIdThenRefThenGhostName() {
        // id 精确命中（G1a：id 恒为 actor_profile.id）
        assertTrue(RelationRefs.endpointMatches(null, 3L, "林晚", new RelationRefs.Endpoint("npc:3", 3L, "林晚")));
        assertTrue(RelationRefs.endpointMatches("npc:3", 3L, "林晚", new RelationRefs.Endpoint("npc:3", 3L, "林晚")));
        // 存量行无 id（id=0）→ 回退 ref 相等匹配
        assertTrue(RelationRefs.endpointMatches("npc:9", 0L, "小二", new RelationRefs.Endpoint("npc:9", 9L, "小二")));
        // id 与 ref 都不相等 → 不命中
        assertFalse(RelationRefs.endpointMatches("npc:8", 8L, "小二", new RelationRefs.Endpoint("npc:9", 9L, "小二")));
        // 名称端点：只有幽灵行（ref=null 且 id=0）命中
        assertTrue(RelationRefs.endpointMatches(null, 0L, "阿明", new RelationRefs.Endpoint(null, null, "阿明")));
        // 名称端点 ≠ 已有正式 ref 行（防 C-13 重名误匹配）
        assertFalse(RelationRefs.endpointMatches("npc:9", 0L, "小二", new RelationRefs.Endpoint(null, null, "小二")));
        // 名称端点 ≠ 已有 id 行
        assertFalse(RelationRefs.endpointMatches(null, 3L, "林晚", new RelationRefs.Endpoint(null, null, "林晚")));
        // 目标为空 → false
        assertFalse(RelationRefs.endpointMatches(null, 0L, "阿明", null));
    }
}