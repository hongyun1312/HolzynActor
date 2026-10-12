package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中观组引擎单元测试（宏观社会演化系统 M6.5a，domain/world/service，同包）。
 * <p>职责：验证世界观文本通用组织解析（段切分/候选名/后缀类型/去重）、模板骨架兜底、
 * 首领三级解析（全名/姓氏/关键词邻近）、势力派生与写回、自发成组（幂等建组/扩组）、
 * 成员装配工具。</p>
 */
class MesoGroupEngineTest {

    private ActorMesoGroupRepository groupRepository;
    private ActorMesoMembershipRepository membershipRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private ActorWorldMacroDeltaRepository deltaRepository;
    private WorldClockService worldClockService;
    private MesoGroupEngine engine;

    @BeforeEach
    void setUp() {
        groupRepository = Mockito.mock(ActorMesoGroupRepository.class);
        membershipRepository = Mockito.mock(ActorMesoMembershipRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        deltaRepository = Mockito.mock(ActorWorldMacroDeltaRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        engine = new MesoGroupEngine(groupRepository, membershipRepository, worldSettingRepository,
                characterRepository, ordinaryNpcRepository, deltaRepository, worldClockService,
                new ObjectMapper());
        when(worldClockService.currentEngineGameSecond(anyLong())).thenReturn(86400L * 30L);
    }

    /** 段切分：按 ①②③ 序号标记分段。 */
    @Test
    void splitSegmentsByMarkers() {
        List<String> segs = MesoGroupEngine.splitSegments("① 王氏宗族：耕读传家 ② 庙宇祭司一系：香火 ③ 往来商帮：贩货");
        assertEquals(3, segs.size());
        assertTrue(segs.get(0).contains("王氏宗族"));
        assertTrue(segs.get(1).contains("庙宇祭司"));
        assertTrue(segs.get(2).contains("商帮"));
    }

    /** 通用组织解析：序号段 → 名称/类型/标签/影响力；无组织文本返回空。 */
    @Test
    void parseLoreExtractsGroups() {
        List<MesoGroupEngine.GroupSeed> seeds = engine.parseLore(
                "① 王氏宗族：耕读传家 ② 庙宇祭司一系：香火旺盛 ③ 往来商帮：贩货四方");
        assertEquals(3, seeds.size());
        assertEquals("王氏宗族", seeds.get(0).name());
        assertEquals("family", seeds.get(0).type());
        assertEquals("庙宇祭司一系", seeds.get(1).name());
        assertEquals("往来商帮", seeds.get(2).name());
        assertEquals("guild", seeds.get(2).type());
        assertTrue(engine.parseLore("一片普通的土地，没有组织。").isEmpty());
        assertTrue(engine.parseLore(null).isEmpty());
        assertTrue(engine.parseLore("").isEmpty());
    }

    /** 候选名：去序号/标点/空白，后缀保留；无名称成分返回 null。 */
    @Test
    void candidateNameTrimsPrefix() {
        assertEquals("王氏宗族", MesoGroupEngine.candidateName("① 王氏宗族：耕读传家", 4));
        assertEquals("王氏宗族", MesoGroupEngine.candidateName("王氏宗族：掌一方族务", 2));
        assertNull(MesoGroupEngine.candidateName("：宗族", 1), "后缀前无名称成分");
        assertNull(MesoGroupEngine.candidateName("宗族：掌一方族务", 0), "段首即后缀无前缀名");
    }

    /** 后缀 → 类型映射（含散人/游侠回退 community）。 */
    @Test
    void suffixTypeMapping() {
        assertEquals("family", MesoGroupEngine.suffixTypeOf("王氏宗族", ""));
        assertEquals("guild", MesoGroupEngine.suffixTypeOf("往来商帮", ""));
        assertEquals("sect", MesoGroupEngine.suffixTypeOf("青山门派", ""));
        assertEquals("cult", MesoGroupEngine.suffixTypeOf("白莲教派", ""));
        assertEquals("community", MesoGroupEngine.suffixTypeOf("游侠散人", ""));
    }

    /** 模板骨架兜底：groupTemplates → 种子（名称/类型/标签/影响力）。 */
    @Test
    void templateSeedsFromTemplate() {
        MacroTemplate tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        List<MesoGroupEngine.GroupSeed> seeds = engine.templateSeeds(tpl);
        assertEquals(tpl.groupTemplates().size(), seeds.size());
        assertEquals(tpl.groupTemplates().get(0).name(), seeds.get(0).name());
        assertEquals("family", seeds.get(0).type());
        assertTrue(engine.templateSeeds(null).isEmpty());
    }

    /** 首领①全名命中：描述段直接点名 → 该角色。 */
    @Test
    void resolveLeaderByFullName() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(11L);
        ch.setName("王青");
        MesoGroupEngine.GroupSeed seed = new MesoGroupEngine.GroupSeed("王氏宗族", "family",
                List.of("宗族"), 55.0, "王氏宗族：族长王青主事");
        assertEquals("npc:11", engine.resolveLeader(100L, "王氏宗族：族长王青主事", seed,
                List.of(ch)));
    }

    /** 首领②姓氏匹配：组名「X氏」→ 同姓角色。 */
    @Test
    void resolveLeaderBySurname() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(12L);
        ch.setName("王大山");
        MesoGroupEngine.GroupSeed seed = new MesoGroupEngine.GroupSeed("王氏宗族", "family",
                List.of("宗族"), 55.0, "王氏宗族：耕读传家");
        assertEquals("npc:12", engine.resolveLeader(100L, "王氏宗族：耕读传家", seed,
                List.of(ch)));
    }

    /** 首领③关键词邻近姓匹配：描述「祭司李氏一脉」→ 李姓角色；无匹配 → null。 */
    @Test
    void resolveLeaderByNeighborSurname() {
        ActorCharacter li = new ActorCharacter();
        li.setId(13L);
        li.setName("李清");
        ActorCharacter zhao = new ActorCharacter();
        zhao.setId(14L);
        zhao.setName("赵四");
        String lore = "庙宇祭司一系：祭司李氏一脉迁入，主持香火";
        MesoGroupEngine.GroupSeed seed = new MesoGroupEngine.GroupSeed("庙宇祭司一系", "family",
                List.of("香火"), 50.0, "庙宇祭司一系：祭司李氏一脉迁入");
        assertEquals("npc:13", engine.resolveLeader(100L, lore, seed, List.of(li, zhao)));
        // 无邻近姓 → null（背景组织）
        assertEquals("npc:13", engine.resolveLeader(100L, lore, seed, List.of(zhao, li)));
        assertNull(engine.resolveLeader(100L, "与李无关", seed, List.of(zhao)),
                "无姓命中返回 null=背景组织");
    }

    /** 姓氏/actorRef 工具。 */
    @Test
    void surnameAndActorIdUtilities() {
        assertEquals("王", MesoGroupEngine.surname("王大山"));
        assertNull(MesoGroupEngine.surname(null));
        assertNull(MesoGroupEngine.surname(""));
        assertEquals(110L, MesoGroupEngine.actorIdOf("npc:110"));
        assertEquals(41L, MesoGroupEngine.actorIdOf("npc:41"));
        assertEquals(0L, MesoGroupEngine.actorIdOf(null));
        assertEquals(0L, MesoGroupEngine.actorIdOf("npc:abc"));
    }

    /** 势力派生：type≠adhoc 的 active 组 → factions（影响力/成员数/关系分）；adhoc 排除。 */
    @Test
    void deriveFactionsExcludesAdhoc() {
        ActorMesoGroup family = new ActorMesoGroup();
        family.setId(5L);
        family.setName("王氏宗族");
        family.setType("family");
        family.setStatus("active");
        family.setInfluence(60.0);
        family.setLeaderRef("npc:12");
        family.setMetaJson("{\"relations\":{\"庙宇祭司一系\":-20}}");
        ActorMesoGroup adhoc = new ActorMesoGroup();
        adhoc.setId(6L);
        adhoc.setName("囤积群体");
        adhoc.setType("adhoc");
        adhoc.setStatus("active");
        when(groupRepository.findByProjectIdAndStatusOrderByIdAsc(100L, "active"))
                .thenReturn(List.of(family, adhoc));
        when(membershipRepository.countByGroupIdAndStatus(5L, "active")).thenReturn(8L);
        Map<String, MacroState.Faction> factions = engine.deriveFactions(100L);
        assertEquals(1, factions.size(), "adhoc 不派生势力");
        MacroState.Faction f = factions.get("王氏宗族");
        assertNotNull(f);
        assertEquals(60.0, f.getInfluence(), 0.001);
        assertEquals(8L, f.getMemberCount());
        assertEquals(-20, f.getRelations().get("庙宇祭司一系"));
        assertTrue(engine.deriveFactions(null).isEmpty());
    }

    /** 势力写回：影响力/关系分 → actor_meso_group（meta_json.relations 合并）。 */
    @Test
    void writeBackFactionsPersists() {
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(5L);
        g.setName("王氏宗族");
        g.setMetaJson("{\"relations\":{}}");
        when(groupRepository.findByProjectIdAndName(100L, "王氏宗族")).thenReturn(Optional.of(g));
        MacroState.Faction f = new MacroState.Faction();
        f.setName("王氏宗族");
        f.setInfluence(70.5);
        f.getRelations().put("庙宇祭司一系", -25);
        engine.writeBackFactions(100L, Map.of("王氏宗族", f));
        verify(groupRepository).save(g);
        assertEquals(70.5, g.getInfluence(), 0.001);
        assertTrue(g.getMetaJson().contains("-25"), "关系分写回 meta_json");
    }

    /** 自发成组：同行为 ≥3 角色 → 建 adhoc 组并装配成员；已存在 → 扩组不重建。 */
    @Test
    void scanEmergentGroupsCreatesAdhoc() {
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("npc:1", "把粮囤起来"), delta("npc:2", "抢购存粮"),
                        delta("npc:3", "囤积货物")));
        when(groupRepository.findByProjectIdAndName(100L, "囤积群体")).thenReturn(Optional.empty());
        when(membershipRepository.findByGroupIdAndActorRef(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(groupRepository.save(any(ActorMesoGroup.class))).thenAnswer(inv -> {
            ActorMesoGroup g = inv.getArgument(0);
            g.setId(99L);
            return g;
        });
        engine.scanEmergentGroups(100L, 10L, 1, 10L * 86400L);
        var captor = org.mockito.ArgumentCaptor.forClass(ActorMesoGroup.class);
        verify(groupRepository).save(captor.capture());
        assertEquals("囤积群体", captor.getValue().getName());
        assertEquals("adhoc", captor.getValue().getType());
        verify(membershipRepository, org.mockito.Mockito.times(3))
                .save(any(ActorMesoMembership.class));
    }

    /** 自发成组幂等：同名 adhoc 组已存在 → 不重建（仅扩组补成员）。 */
    @Test
    void ensureAdhocGroupReusesExisting() {
        ActorMesoGroup existing = new ActorMesoGroup();
        existing.setId(88L);
        existing.setName("囤积群体");
        existing.setType("adhoc");
        existing.setStatus("active");
        when(groupRepository.findByProjectIdAndName(100L, "囤积群体")).thenReturn(Optional.of(existing));
        when(membershipRepository.findByGroupIdAndActorRef(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        engine.ensureAdhocGroup(100L, "hoarding", Map.of("npc:1", new ActorWorldMacroDelta(),
                "npc:2", new ActorWorldMacroDelta()), 100L);
        verify(groupRepository, never()).save(any(ActorMesoGroup.class));
        verify(membershipRepository, org.mockito.Mockito.times(2))
                .save(any(ActorMesoMembership.class));
    }

    /** 普通 NPC 确定性轮派：id % groupCount == groupIndex 入组。 */
    @Test
    void assignCrowdMembersRoundRobin() {
        ActorCharacter n1 = new ActorCharacter();
        n1.setId(1L);
        ActorCharacter n2 = new ActorCharacter();
        n2.setId(2L);
        ActorCharacter n3 = new ActorCharacter();
        n3.setId(3L);
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of(n1, n2, n3));
        when(membershipRepository.findByGroupIdAndActorRef(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        engine.assignCrowdMembers(100L, 5L, 0, 2, 100L);
        // 1%2=1≠0、3%2=1≠0 → 仅 id=2 入组（轮派确定性）
        verify(membershipRepository).save(org.mockito.ArgumentMatchers.argThat(m ->
                m instanceof ActorMesoMembership mm && "npc:2".equals(mm.getActorRef())
                        && "member".equals(mm.getRole())));
        verify(membershipRepository, org.mockito.Mockito.times(1)).save(any(ActorMesoMembership.class));
    }

    private static ActorWorldMacroDelta delta(String ref, String text) {
        ActorWorldMacroDelta d = new ActorWorldMacroDelta();
        d.setNpcRef(ref);
        d.setActionText(text);
        d.setActionType("custom");
        d.setGameDay(9L);
        d.setTargetMacro("economy");
        d.setSource("rule");
        return d;
    }
}
