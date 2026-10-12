package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 世界状态图谱统一查询层单元测试（调研报告 ③-1，domain/world/service）。
 * <p>职责：验证六维单域 / 三种聚合视角 / 全局概览的投影与关联口径（角色↔事件 character_id ∪
 * character_ids、角色↔势力 membership+affiliation 兜底、关系端点匹配）、adhoc 排除、
 * 404 透传与异常隔离（单点数据源失败降级不阻断）。</p>
 */
class WorldGraphQueryServiceTest {

    private WorldLocationRepository locationRepository;
    private ActorCharacterRepository characterRepository;
    private ActorEventRepository eventRepository;
    private ActorCharacterRelationRepository relationRepository;
    private ActorMesoGroupRepository groupRepository;
    private ActorMesoMembershipRepository membershipRepository;
    private ActorWorldChronicleRepository chronicleRepository;
    private ActorWorldClockRepository clockRepository;
    private WorldClockService worldClockService;
    private MacroStateService macroStateService;
    private ActorWorldEntityRepository entityRepository;
    private WorldGraphQueryService service;

    @BeforeEach
    void setUp() {
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        eventRepository = Mockito.mock(ActorEventRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        groupRepository = Mockito.mock(ActorMesoGroupRepository.class);
        membershipRepository = Mockito.mock(ActorMesoMembershipRepository.class);
        chronicleRepository = Mockito.mock(ActorWorldChronicleRepository.class);
        clockRepository = Mockito.mock(ActorWorldClockRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        entityRepository = Mockito.mock(ActorWorldEntityRepository.class);
        service = new WorldGraphQueryService(locationRepository, characterRepository, eventRepository,
                relationRepository, groupRepository, membershipRepository, chronicleRepository,
                clockRepository);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(service, "macroStateService", macroStateService);
        ReflectionTestUtils.setField(service, "entityRepository", entityRepository);
    }

    // ==================== 六维单域 ====================

    /** 地点单域：sortOrder 投影。 */
    @Test
    void locationsProjection() {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(1L);
        loc.setName("村口牌坊");
        loc.setType("地标");
        loc.setIntro("村口大石牌坊");
        loc.setImportance(3);
        loc.setSortOrder(1);
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(100L))
                .thenReturn(List.of(loc));

        List<WorldGraphVO.LocationNode> out = service.locations(100L);
        assertEquals(1, out.size());
        assertEquals("村口牌坊", out.get(0).name());
        assertEquals(3, out.get(0).importance());
    }

    /** 时间单域：无时钟行返回 null。 */
    @Test
    void timeWithoutClockReturnsNull() {
        when(clockRepository.findByProjectId(100L)).thenReturn(Optional.empty());
        assertNull(service.time(100L));
    }

    /** 时间单域：引擎位置投影（秒→小时→日 + 历法文本）。 */
    @Test
    void timeProjectionFromEnginePosition() {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setProjectId(100L);
        clock.setLastGameSecond(2L * 86400L + 6L * 3600L);
        clock.setPaused(1);
        clock.setWorldRunning(0);
        when(clockRepository.findByProjectId(100L)).thenReturn(Optional.of(clock));
        when(worldClockService.calendarNameOf(100L)).thenReturn("青禾历");

        WorldGraphVO.TimeNode t = service.time(100L);
        assertNotNull(t);
        assertEquals(2L * 86400L + 6L * 3600L, t.gameSecond());
        assertEquals(2L * 24L + 6L, t.gameHour());
        assertEquals(2L, t.gameDay());
        assertEquals(1, t.paused());
        assertEquals(0, t.worldRunning());
        assertTrue(t.gameTimeText().contains("青禾历"));
    }

    /** 角色单域：按地点过滤（在场角色）。 */
    @Test
    void charactersByLocation() {
        ActorCharacter ch = character(1L, "沈砚秋", "special", "青禾村村长", "村口牌坊", 10L);
        when(characterRepository.findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(100L, 10L, 0))
                .thenReturn(List.of(ch));

        List<WorldGraphVO.CharacterNode> out = service.characters(100L, null, 10L);
        assertEquals(1, out.size());
        assertEquals("npc:1", out.get(0).ref());
        assertEquals("青禾村村长", out.get(0).title());
    }

    /** 角色单域：按类型过滤（special）。 */
    @Test
    void charactersByType() {
        when(characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(100L, "special", 0))
                .thenReturn(List.of(character(1L, "沈砚秋", "special", null, null, null)));
        List<WorldGraphVO.CharacterNode> out = service.characters(100L, "special", null);
        assertEquals(1, out.size());
        assertEquals("special", out.get(0).type());
    }

    /** 事件单域：characterId 参与过滤（走 character_id ∪ character_ids 口径仓库查询）。 */
    @Test
    void eventsByParticipant() {
        ActorEvent e1 = event(1L, "action", "沈砚秋去祠堂", "查看祠堂", 100L, null, null, null, 3);
        e1.setCharacterId(1L);
        when(eventRepository.findByProjectIdAndCharacterRef(100L, 1L, "npc:1"))
                .thenReturn(List.of(e1));
        when(characterRepository.findByIdIn(List.of(1L)))
                .thenReturn(List.of(character(1L, "沈砚秋", "special", null, null, null)));

        List<WorldGraphVO.EventNode> out = service.events(100L, null, 1L, null);
        assertEquals(1, out.size());
        assertEquals("action", out.get(0).kind());
        assertEquals(List.of("沈砚秋"), out.get(0).characterNames());
    }

    /** 事件单域：character_ids 逗号列表解析参与角色名（含 crowd: 旧前缀宽松解析）。 */
    @Test
    void eventsResolveCharacterNamesFromList() {
        ActorEvent e1 = event(2L, "event", "村口集会", "村民集会", 100L, null, null, "npc:1,crowd:2", 4);
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e1));
        when(characterRepository.findByIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(character(1L, "沈砚秋", "special", null, null, null),
                        character(2L, "王二", "common", null, null, null)));

        List<WorldGraphVO.EventNode> out = service.events(100L, null, null, 10);
        assertEquals(1, out.size());
        assertEquals(List.of("沈砚秋", "王二"), out.get(0).characterNames());
    }

    /** 事件单域：kind 过滤 + limit 截断。 */
    @Test
    void eventsFilterKindAndLimit() {
        ActorEvent e1 = event(1L, "action", "a", "a", 100L, null, null, null, 3);
        ActorEvent e2 = event(2L, "event", "b", "b", 100L, null, null, null, 3);
        ActorEvent e3 = event(3L, "action", "c", "c", 100L, null, null, null, 3);
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e1, e2, e3));

        List<WorldGraphVO.EventNode> out = service.events(100L, "action", null, 1);
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).title());
    }

    /** 关系单域：characterId 过滤（from/to 任一端点命中）。 */
    @Test
    void relationsFilterByParticipant() {
        ActorCharacterRelation r1 = relation(1L, "npc:1", "沈砚秋", "npc:2", "王二", "师徒", 50, 60, "friend");
        ActorCharacterRelation r2 = relation(2L, "npc:3", "张三", "npc:4", "李四", "敌对", -30, 10, "rival");
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of(r1, r2));

        List<WorldGraphVO.RelationEdge> out = service.relations(100L, 1L);
        assertEquals(1, out.size());
        assertEquals("师徒", out.get(0).relationType());
        assertEquals(50, out.get(0).affinity());
    }

    /** 势力单域：adhoc 排除 + 活跃成员数 + meta_json 组织间关系分。 */
    @Test
    void factionsExcludeAdhocAndParseRelations() {
        ActorMesoGroup force = group(1L, "沈氏宗族", "family", "active", 60.0, "npc:1",
                "{\"relations\":{\"王家\":-20}}");
        ActorMesoGroup adhoc = group(2L, "临时集会", "adhoc", "active", 5.0, null, null);
        when(groupRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of(force, adhoc));
        when(membershipRepository.countByGroupIdAndStatus(1L, "active")).thenReturn(12L);

        List<WorldGraphVO.FactionNode> out = service.factions(100L);
        assertEquals(1, out.size());
        assertEquals("沈氏宗族", out.get(0).name());
        assertEquals(12L, out.get(0).memberCount());
        assertEquals(-20, out.get(0).relations().get("王家"));
    }

    /** 宏观物资单域：快照投影（人口/粮价/民怨/势力 top/议题 top）。 */
    @Test
    void macroProjection() {
        MacroState st = new MacroState();
        st.getPopulation().setTotal(300L);
        st.getEconomy().setGrainPrice(120.0);
        st.getEconomy().setGrainStock(500.0);
        st.getEconomy().setSeason(SeasonService.AUTUMN);
        st.setGrievance(40.0);
        MacroState.Faction top = new MacroState.Faction();
        top.setName("沈氏宗族");
        top.setInfluence(80.0);
        st.getFactions().put("沈氏宗族", top);
        MacroState.Issue issue = new MacroState.Issue();
        issue.setTopic("时局");
        issue.setIntensity(0.7);
        st.getIssues().add(issue);
        when(macroStateService.snapshotOf(100L)).thenReturn(st);
        when(macroStateService.buildSummary(eq(st), eq("daily"))).thenReturn("第 1 游戏日宏观简报");

        WorldGraphVO.MacroNode m = service.macro(100L);
        assertNotNull(m);
        assertEquals(300L, m.population());
        assertEquals(120.0, m.grainPrice());
        assertEquals(40.0, m.grievance());
        assertEquals("沈氏宗族", m.topFaction());
        assertEquals("时局", m.topIssue());
        assertTrue(m.summary().contains("宏观简报"));
    }

    // ==================== 三种聚合视角 ====================

    /** 角色视角：角色+位置+关系+所属组织（membership）+参与事件。 */
    @Test
    void characterViewAggregatesAll() {
        ActorCharacter ch = character(1L, "沈砚秋", "special", "青禾村村长", "村口牌坊", 10L);
        when(characterRepository.findByIdAndProjectId(1L, 100L)).thenReturn(Optional.of(ch));
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(10L);
        loc.setName("村口牌坊");
        loc.setType("地标");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc));
        ActorCharacterRelation r1 = relation(1L, "npc:1", "沈砚秋", "npc:2", "王二", "师徒", 50, 60, "friend");
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of(r1));
        ActorMesoMembership ms = new ActorMesoMembership();
        ms.setGroupId(1L);
        ms.setActorId(1L);
        ms.setActorRef("npc:1");
        ms.setRole("leader");
        when(membershipRepository.findByProjectIdAndActorRefOrderByIdAsc(100L, "npc:1"))
                .thenReturn(List.of(ms));
        ActorMesoGroup g = group(1L, "沈氏宗族", "family", "active", 60.0, "npc:1", null);
        when(groupRepository.findAllById(List.of(1L))).thenReturn(List.of(g));
        ActorEvent e1 = event(1L, "action", "去祠堂", "查看祠堂", 100L, null, null, null, 3);
        when(eventRepository.findByProjectIdAndCharacterRef(100L, 1L, "npc:1")).thenReturn(List.of(e1));

        WorldGraphVO.CharacterView v = service.characterView(100L, 1L);
        assertEquals("沈砚秋", v.character().name());
        assertEquals("村口牌坊", v.location().name());
        assertEquals(1, v.relations().size());
        assertEquals(1, v.factions().size());
        assertEquals("沈氏宗族", v.factions().get(0).name());
        assertEquals(1, v.events().size());
    }

    /** 角色视角：无 membership 时按 affiliation 文本匹配势力兜底。 */
    @Test
    void characterViewFactionFallbackByAffiliation() {
        ActorCharacter ch = character(1L, "王二", "common", null, null, null);
        ch.setAffiliation("沈氏宗族");
        when(characterRepository.findByIdAndProjectId(1L, 100L)).thenReturn(Optional.of(ch));
        when(membershipRepository.findByProjectIdAndActorRefOrderByIdAsc(100L, "npc:1"))
                .thenReturn(List.of());
        ActorMesoGroup g = group(1L, "沈氏宗族", "family", "active", 60.0, null, null);
        when(groupRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of(g));

        WorldGraphVO.CharacterView v = service.characterView(100L, 1L);
        assertEquals(1, v.factions().size());
        assertEquals("沈氏宗族", v.factions().get(0).name());
    }

    /** 角色视角：角色不存在抛 404。 */
    @Test
    void characterViewNotFoundThrows404() {
        when(characterRepository.findByIdAndProjectId(999L, 100L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.characterView(100L, 999L));
        assertEquals(404, ex.getCode());
    }

    /** 地点视角：在场角色 + 近期事件 + 关联实体状态（名称匹配）。 */
    @Test
    void locationViewAggregatesPresentEventsEntities() {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(10L);
        loc.setProjectId(100L);
        loc.setName("村口牌坊");
        loc.setType("地标");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc));
        when(characterRepository.findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(100L, 10L, 0))
                .thenReturn(List.of(character(1L, "沈砚秋", "special", null, "村口牌坊", 10L)));
        ActorEvent e1 = event(1L, "event", "集会", "村民集会", 100L, 10L, "村口牌坊", null, 3);
        when(eventRepository.findByProjectIdAndLocationIdOrderByIdDesc(100L, 10L))
                .thenReturn(List.of(e1));
        ActorWorldEntity ent = new ActorWorldEntity();
        ent.setId(5L);
        ent.setEntityType("location");
        ent.setEntityKey("location:村口牌坊");
        ent.setName("村口牌坊");
        ent.setCurrentState("完好");
        ent.setValidFromGameDay(1L);
        when(entityRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(ent));

        WorldGraphVO.LocationView v = service.locationView(100L, 10L);
        assertEquals("村口牌坊", v.location().name());
        assertEquals(1, v.characters().size());
        assertEquals(1, v.events().size());
        assertEquals(1, v.entities().size());
        assertEquals("完好", v.entities().get(0).currentState());
    }

    /** 地点视角：地点不存在抛 404。 */
    @Test
    void locationViewNotFoundThrows404() {
        when(locationRepository.findById(999L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.locationView(100L, 999L));
        assertEquals(404, ex.getCode());
    }

    /** 势力视角：成员解析（membership → 角色名）+ 关系分。 */
    @Test
    void factionViewAggregatesMembers() {
        ActorMesoGroup g = group(1L, "沈氏宗族", "family", "active", 60.0, "npc:1",
                "{\"relations\":{\"王家\":-20}}");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(g));
        ActorMesoMembership ms = new ActorMesoMembership();
        ms.setGroupId(1L);
        ms.setActorId(1L);
        ms.setActorRef("npc:1");
        ms.setRole("leader");
        ms.setInfluence(55.0);
        ms.setStatus("active");
        when(membershipRepository.findByGroupIdOrderByIdAsc(1L)).thenReturn(List.of(ms));
        when(membershipRepository.countByGroupIdAndStatus(1L, "active")).thenReturn(1L);
        when(characterRepository.findByIdIn(List.of(1L)))
                .thenReturn(List.of(character(1L, "沈砚秋", "special", null, null, null)));

        WorldGraphVO.FactionView v = service.factionView(100L, 1L);
        assertEquals("沈氏宗族", v.faction().name());
        assertEquals(1, v.members().size());
        assertEquals("沈砚秋", v.members().get(0).actorName());
        assertEquals("leader", v.members().get(0).role());
        assertEquals(-20, v.relations().get("王家"));
    }

    // ==================== 全局概览 ====================

    /** 概览：时间+计数+宏观+最近事件+最近编年史。 */
    @Test
    void overviewAggregatesAll() {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setProjectId(100L);
        clock.setLastGameSecond(86400L);
        when(clockRepository.findByProjectId(100L)).thenReturn(Optional.of(clock));
        when(locationRepository.countByProjectId(100L)).thenReturn(5L);
        when(characterRepository.countByProjectIdAndDeleted(100L, 0)).thenReturn(20L);
        when(eventRepository.countByProjectId(100L)).thenReturn(300L);
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of(relation(
                1L, "npc:1", "沈砚秋", "npc:2", "王二", "师徒", 50, 60, "friend")));
        when(groupRepository.countByProjectIdAndTypeNot(100L, "adhoc")).thenReturn(3L);
        MacroState st = new MacroState();
        st.getPopulation().setTotal(300L);
        when(macroStateService.snapshotOf(100L)).thenReturn(st);
        when(macroStateService.buildSummary(eq(st), eq("daily"))).thenReturn("简报");
        when(eventRepository.findTop20ByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(
                event(1L, "event", "集会", "村民集会", 100L, null, null, null, 3)));
        ActorWorldChronicle c = new ActorWorldChronicle();
        c.setId(7L);
        c.setGameDay(3L);
        c.setTitle("村口集会");
        c.setContent("全村集会商议秋收");
        c.setImportance(4);
        c.setSeason(SeasonService.AUTUMN);
        c.setSource("daily");
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(100L))
                .thenReturn(List.of(c));

        WorldGraphVO.Overview o = service.overview(100L);
        assertNotNull(o);
        assertNotNull(o.time());
        assertEquals(5L, o.counts().locations());
        assertEquals(20L, o.counts().characters());
        assertEquals(300L, o.counts().events());
        assertEquals(1L, o.counts().relations());
        assertEquals(3L, o.counts().factions());
        assertEquals(300L, o.macro().population());
        assertEquals(1, o.recentEvents().size());
        assertEquals(1, o.recentChronicles().size());
        assertEquals("村口集会", o.recentChronicles().get(0).title());
    }

    // ==================== 异常隔离 ====================

    /** 单域数据源失败 → 降级空列表/空 Map（不抛异常）。 */
    @Test
    void singleDomainFailureDegradesGracefully() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(100L))
                .thenThrow(new RuntimeException("db down"));
        when(groupRepository.findByProjectIdOrderByIdAsc(100L))
                .thenThrow(new RuntimeException("db down"));
        when(macroStateService.snapshotOf(100L)).thenThrow(new RuntimeException("db down"));
        when(clockRepository.findByProjectId(100L)).thenThrow(new RuntimeException("db down"));
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenThrow(new RuntimeException("db down"));
        when(relationRepository.findByProjectId(100L)).thenThrow(new RuntimeException("db down"));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(List.of(), service.locations(100L));
        assertEquals(List.of(), service.characters(100L, null, null));
        assertEquals(List.of(), service.events(100L, null, null, null));
        assertEquals(List.of(), service.relations(100L, null));
        assertEquals(List.of(), service.factions(100L));
        assertNull(service.macro(100L));
        assertNull(service.time(100L));
        assertNull(service.overview(100L));
    }

    /** 视角内子段失败 → 仅该段降级空列表，主视图仍返回。 */
    @Test
    void viewSubsectionFailureDegradesOnlyThatSection() {
        ActorCharacter ch = character(1L, "沈砚秋", "special", null, null, null);
        when(characterRepository.findByIdAndProjectId(1L, 100L)).thenReturn(Optional.of(ch));
        when(eventRepository.findByProjectIdAndCharacterRef(100L, 1L, "npc:1"))
                .thenThrow(new RuntimeException("db down"));
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of());
        when(membershipRepository.findByProjectIdAndActorRefOrderByIdAsc(100L, "npc:1"))
                .thenReturn(List.of());

        WorldGraphVO.CharacterView v = service.characterView(100L, 1L);
        assertNotNull(v);
        assertEquals("沈砚秋", v.character().name());
        assertEquals(List.of(), v.events());
    }

    // ==================== 构造辅助 ====================

    private static ActorCharacter character(Long id, String name, String type, String title,
                                            String location, Long locationId) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setTitle(title);
        c.setLocation(location);
        c.setLocationId(locationId);
        return c;
    }

    private static ActorEvent event(Long id, String kind, String title, String content, Long gameHour,
                                    Long locationId, String locationName, String characterIds,
                                    Integer importance) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setProjectId(100L);
        e.setKind(kind);
        e.setTitle(title);
        e.setContent(content);
        e.setGameHour(gameHour);
        e.setLocationId(locationId);
        e.setLocationName(locationName);
        e.setCharacterIds(characterIds);
        e.setImportance(importance);
        return e;
    }

    private static ActorCharacterRelation relation(Long id, String fromRef, String fromName,
                                                   String toRef, String toName, String type,
                                                   Integer affinity, Integer trust, String state) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setId(id);
        r.setFromRef(fromRef);
        r.setFromName(fromName);
        r.setToRef(toRef);
        r.setToName(toName);
        r.setRelationType(type);
        r.setAffinity(affinity);
        r.setTrust(trust);
        r.setRelationState(state);
        return r;
    }

    private static ActorMesoGroup group(Long id, String name, String type, String status,
                                        Double influence, String leaderRef, String metaJson) {
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(id);
        g.setProjectId(100L);
        g.setName(name);
        g.setType(type);
        g.setStatus(status);
        g.setInfluence(influence);
        g.setLeaderRef(leaderRef);
        g.setMetaJson(metaJson);
        return g;
    }
}
