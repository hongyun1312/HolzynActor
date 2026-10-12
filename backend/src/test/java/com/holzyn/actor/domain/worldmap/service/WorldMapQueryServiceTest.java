package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import com.holzyn.actor.domain.worldmap.vo.WorldMapLocationVO;
import com.holzyn.actor.domain.worldmap.vo.WorldMapVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorldMapQueryService 单元测试（位置移动与地图生成系统 M1 + M3，V1.3 第六层：仓库全 mock，纯只读）。
 * <p>职责：worldMap 概览（layout v2 + 住所）/ locationDetail（区域/摆放/住所/在场角色）/ npcHome；
 * M3 感知联动纯函数（区域反查/八方位/路程口语化/距离分级/附近角色/地点名反查/目标路程远近）。</p>
 */
class WorldMapQueryServiceTest {

    private final ActorSandboxSceneRepository sceneRepository = mock(ActorSandboxSceneRepository.class);
    private final WorldLocationRepository locationRepository = mock(WorldLocationRepository.class);
    private final ActorNpcHomeRepository homeRepository = mock(ActorNpcHomeRepository.class);
    private final ActorHomeMemberRepository memberRepository = mock(ActorHomeMemberRepository.class);
    private final ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
    private final ActorSandboxStateRepository sandboxStateRepository = mock(ActorSandboxStateRepository.class);

    private final WorldMapQueryService service = new WorldMapQueryService(sceneRepository, locationRepository,
            homeRepository, memberRepository, characterRepository, new ObjectMapper());

    {
        ReflectionTestUtils.setField(service, "sandboxStateRepository", sandboxStateRepository);
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(anyLong()))
                .thenReturn(Optional.of(scene(500)));
    }

    private String layoutJson() {
        return """
                {"scene":{"size":500,"style":"village"},
                "regions":[{"locationId":1,"name":"村口石牌坊","centerX":250,"centerZ":430,"radius":12,"regionDesc":"村西口"}],
                "roads":[{"name":"主道","path":[[250,40],[250,470]],"width":5}],
                "placements":[{"id":11,"name":"村碑","category":"landmark","posX":240,"posZ":435,"locationId":1}]}
                """;
    }

    @Test
    void worldMapOverview() {
        ActorSandboxScene scene = new ActorSandboxScene();
        scene.setId(33L);
        scene.setSceneSize(500);
        scene.setStyleCode("village");
        scene.setSource("ai_generated");
        scene.setLayoutJson(layoutJson());
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(1L)).thenReturn(Optional.of(scene));

        ActorNpcHome home = new ActorNpcHome();
        home.setId(1L);
        home.setProjectId(1L);
        home.setName("沈家大宅");
        home.setLocationId(1L);
        home.setPosX(240d);
        home.setPosZ(420d);
        when(homeRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(home));
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location(1L, "村口石牌坊")));
        ActorHomeMember member = new ActorHomeMember();
        member.setId(1L);
        member.setProjectId(1L);
        member.setHomeId(1L);
        member.setCharacterRef("npc:1");
        member.setRole("户主");
        member.setIsOwner(1);
        when(memberRepository.findByHomeIdOrderByIdAsc(1L)).thenReturn(List.of(member));
        when(characterRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(character(1L, "沈砚秋")));

        WorldMapVO vo = service.worldMap(1L);

        assertEquals(33L, vo.getSceneId());
        assertEquals(1, vo.getRegions().size());
        assertEquals("村口石牌坊", vo.getRegions().get(0).getName());
        assertEquals(1, vo.getRoads().size());
        assertEquals(1, vo.getPlacements().size());
        assertEquals(1, vo.getHomes().size());
        assertEquals("沈家大宅", vo.getHomes().get(0).getName());
        assertEquals("村口石牌坊", vo.getHomes().get(0).getLocationName());
        assertEquals(1, vo.getHomes().get(0).getMembers().size());
        assertEquals("沈砚秋", vo.getHomes().get(0).getMembers().get(0).getCharacterName());
    }

    @Test
    void worldMapNoSceneReturnsEmpty() {
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(2L)).thenReturn(Optional.empty());
        WorldMapVO vo = service.worldMap(2L);
        assertEquals(2L, vo.getProjectId());
        assertEquals(0, vo.getRegions().size());
        assertEquals(0, vo.getHomes().size());
    }

    @Test
    void locationDetail() {
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location(1L, "村口石牌坊", "地标")));
        ActorSandboxScene scene = new ActorSandboxScene();
        scene.setId(33L);
        scene.setLayoutJson(layoutJson());
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(1L)).thenReturn(Optional.of(scene));
        when(homeRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(1L, 1L, 0))
                .thenReturn(List.of(character(1L, "沈砚秋")));

        WorldMapLocationVO vo = service.locationDetail(1L, 1L);

        assertEquals("村口石牌坊", vo.getName());
        assertEquals(1, vo.getPlacements().size());
        assertEquals("村碑", vo.getPlacements().get(0).getName());
        assertEquals(List.of("沈砚秋"), vo.getCharacters());
    }

    @Test
    void locationDetailNotFound() {
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.locationDetail(1L, 99L));
    }

    @Test
    void npcHome() {
        ActorHomeMember member = new ActorHomeMember();
        member.setId(1L);
        member.setProjectId(1L);
        member.setHomeId(5L);
        member.setCharacterRef("npc:1");
        when(memberRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.of(member));
        ActorNpcHome home = new ActorNpcHome();
        home.setId(5L);
        home.setName("沈家大宅");
        home.setLocationId(1L);
        when(homeRepository.findById(5L)).thenReturn(Optional.of(home));
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(location(1L, "村口石牌坊")));
        when(characterRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(character(1L, "沈砚秋")));
        when(memberRepository.findByHomeIdOrderByIdAsc(5L)).thenReturn(List.of(member));

        WorldMapVO.HomeVO vo = service.npcHome(1L, "npc:1");

        assertNotNull(vo);
        assertEquals("沈家大宅", vo.getName());
        assertEquals("村口石牌坊", vo.getLocationName());
        assertEquals("沈砚秋", vo.getMembers().get(0).getCharacterName());
    }

    @Test
    void npcHomeNotFound() {
        when(memberRepository.findByProjectIdAndCharacterRef(1L, "npc:99")).thenReturn(Optional.empty());
        assertNull(service.npcHome(1L, "npc:99"));
    }

    private ActorWorldLocation location(long id, String name) {
        return location(id, name, "地标");
    }

    private ActorWorldLocation location(long id, String name, String type) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        l.setType(type);
        return l;
    }

    private ActorCharacter character(long id, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setType("special");
        return c;
    }

    private ActorSandboxScene scene(int size) {
        ActorSandboxScene s = new ActorSandboxScene();
        s.setId(33L);
        s.setSceneSize(size);
        return s;
    }

    private ActorWorldLocation located(long id, String name, double cx, double cz, double radius) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        l.setCenterX(cx);
        l.setCenterZ(cz);
        l.setRadius(radius);
        l.setRegionDesc("村西口");
        return l;
    }

    // ==================== M3 感知联动纯函数 ====================

    @Test
    void bearing_eightDirections() {
        // 以 (0,0) 为起点：东=+x，南=+z（atan2(dz,dx) 逆时针，x 轴 0°）
        assertEquals("东", WorldMapQueryService.bearing(0, 0, 10, 0));
        assertEquals("西", WorldMapQueryService.bearing(0, 0, -10, 0));
        assertEquals("南", WorldMapQueryService.bearing(0, 0, 0, 10));
        assertEquals("北", WorldMapQueryService.bearing(0, 0, 0, -10));
        assertEquals("东南", WorldMapQueryService.bearing(0, 0, 10, 10));
        assertEquals("西北", WorldMapQueryService.bearing(0, 0, -10, -10));
        assertEquals("前方", WorldMapQueryService.bearing(0, 0, 0, 0), "同点回退前方");
    }

    @Test
    void distanceText_stepsAndLi() {
        assertEquals("几步路", WorldMapQueryService.distanceText(0));
        assertEquals("几步路", WorldMapQueryService.distanceText(-5));
        // 30 米 ÷ 0.6 = 50 步
        assertEquals("约50步", WorldMapQueryService.distanceText(30));
        // 60 米 ÷ 0.6 = 100 步 → 约百步
        assertEquals("约百步", WorldMapQueryService.distanceText(60));
        // 120 米 ÷ 0.6 = 200 步 → 约2百步
        assertEquals("约2百步", WorldMapQueryService.distanceText(120));
        // 600 米 ÷ 0.6 = 1000 步 → 约1里
        assertEquals("约1里", WorldMapQueryService.distanceText(600));
    }

    @Test
    void distanceGrade_nearMidFar() {
        assertEquals("近", WorldMapQueryService.distanceGrade(50));
        assertEquals("近", WorldMapQueryService.distanceGrade(10));
        assertEquals("中", WorldMapQueryService.distanceGrade(200));
        assertEquals("中", WorldMapQueryService.distanceGrade(100));
        assertEquals("远", WorldMapQueryService.distanceGrade(201));
        assertEquals("远", WorldMapQueryService.distanceGrade(5000));
    }

    @Test
    void regionAt_returnsRegionDescOrNull() {
        // 场景 500：沙盘 (10,10) → 地图 (260,260)，区域 医馆(260,260, r=20) 命中
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(
                located(5L, "医馆", 260d, 260d, 20d)));
        assertEquals("村西口", service.regionAt(1L, 10d, 10d));
        // 远离区域 → null
        assertNull(service.regionAt(1L, -200d, -200d));
    }

    @Test
    void nearbyRefs_returnsSortedRefsWithinRadius() {
        ActorSandboxState me = state("npc:1", 0d, 0d);
        ActorSandboxState near = state("npc:2", 5d, 0d);
        ActorSandboxState far = state("npc:3", 200d, 0d);
        when(sandboxStateRepository.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(List.of(me, near, far));

        List<String> refs = service.nearbyRefs(1L, 0d, 0d, "npc:1", 30);
        assertEquals(List.of("npc:2"), refs, "排除自身且只留半径内角色");
        // 无排除 → 自身也出现（但自身距离 0 排最前）
        List<String> all = service.nearbyRefs(1L, 0d, 0d, null, 30);
        assertEquals(List.of("npc:1", "npc:2"), all, "半径内全部按距离升序");
    }

    @Test
    void locationIdByName_matchesExactly() {
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(
                located(5L, "医馆", 260d, 260d, 20d)));
        assertEquals(5L, service.locationIdByName(1L, "医馆"));
        assertNull(service.locationIdByName(1L, "药铺"), "不存在的点名返回 null");
        assertNull(service.locationIdByName(1L, null));
    }

    @Test
    void distanceToTarget_gradesByLocationCenter() {
        ActorSandboxState st = state("npc:1", 0d, 0d);
        when(locationRepository.findById(5L)).thenReturn(Optional.of(
                located(5L, "医馆", 260d, 260d, 20d)));
        // 角色沙盘 (0,0) → 医馆中心沙盘 (10,10) → 距离 ~14.1 米 → 近
        String d = service.distanceToTarget(1L, 0d, 0d, 5L);
        assertNotNull(d);
        assertTrue(d.startsWith("近"), "14 米应判近: " + d);
        // 未知地点 → null
        assertNull(service.distanceToTarget(1L, 0d, 0d, 99L));
    }

    private ActorSandboxState state(String ref, double x, double z) {
        ActorSandboxState s = new ActorSandboxState();
        s.setProjectId(1L);
        s.setNpcRef(ref);
        s.setPosX(x);
        s.setPosZ(z);
        return s;
    }
}
