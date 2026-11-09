package com.holzyn.actor.domain.agent.perception;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPositionRepository;
import com.holzyn.actor.domain.mapnav.service.NpcSpatialView;
import com.holzyn.actor.domain.mapnav.vo.SpatialFix;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.entity.ActorMovement;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import com.holzyn.actor.domain.worldmap.service.MovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 空间段与空间约束段（实跑事故后补的护栏）。
 *
 * <p><b>守的是什么</b>：实测出现过「正走在去河神庙的路上（从铁匠铺出发）」——坐标来自权威行
 * （米制地图），在途描述却读沙盘移动行（0..500 的另一套坐标），于是方向与出发点分别来自两套
 * 数据，模型跟着这句话走了。另一个护栏是【空间约束】：环境进了 prompt 却没改变决策
 * （反事实实验 TVD 2.4%~9.6%），因为它只是叙述、不是规则。</p>
 */
class PerceptionSpatialSegmentTest {

    private ActorCharacterRepository characterRepository;
    private NpcSpatialView npcSpatialView;
    private ActorNpcPathRepository npcPathRepository;
    private MovementService movementService;
    private ActorMapFeatureRepository mapFeatureRepository;
    private ActorWorldMapRepository worldMapRepository;
    private ActorHomeMemberRepository homeMemberRepository;
    private ActorNpcPositionRepository npcPositionRepository;
    private com.holzyn.actor.domain.mapnav.service.NpcPlaceView placeView;
    private PerceptionService service;
    /** 当前被摆到的要素（地点读口从这里取，模拟权威行）。 */
    private String currentFeature;

    @BeforeEach
    void setUp() {
        characterRepository = mock(ActorCharacterRepository.class);
        npcSpatialView = mock(NpcSpatialView.class);
        npcPathRepository = mock(ActorNpcPathRepository.class);
        movementService = mock(MovementService.class);
        mapFeatureRepository = mock(ActorMapFeatureRepository.class);
        worldMapRepository = mock(ActorWorldMapRepository.class);
        homeMemberRepository = mock(ActorHomeMemberRepository.class);
        npcPositionRepository = mock(ActorNpcPositionRepository.class);
        // 地点读口：与生产同一实现，只是把仓储换成 mock（避免「测试里的判定和线上不是一套」）
        placeView = new com.holzyn.actor.domain.mapnav.service.NpcPlaceView();
        ReflectionTestUtils.setField(placeView, "npcPositionRepository", npcPositionRepository);
        ReflectionTestUtils.setField(placeView, "npcPathRepository", npcPathRepository);
        ReflectionTestUtils.setField(placeView, "worldMapRepository", worldMapRepository);
        ReflectionTestUtils.setField(placeView, "mapFeatureRepository", mapFeatureRepository);
        ReflectionTestUtils.setField(placeView, "homeMemberRepository", homeMemberRepository);
        when(npcPositionRepository.findByProjectIdAndNpcRef(anyLong(), anyString())).thenAnswer(inv -> {
            com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition pos =
                    new com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition();
            pos.setFeatureCode(currentFeature);
            pos.setX(543.0);
            pos.setZ(636.0);
            pos.setIndoor(0);
            return Optional.of(pos);
        });
        service = new PerceptionService(characterRepository, characterRepository,
                mock(ActorCharacterRelationRepository.class), mock(WorldClockService.class),
                mock(WorldEventBus.class), new NpcTypeResolver(characterRepository));
        ReflectionTestUtils.setField(service, "npcSpatialView", npcSpatialView);
        ReflectionTestUtils.setField(service, "npcPathRepository", npcPathRepository);
        ReflectionTestUtils.setField(service, "movementService", movementService);
        ReflectionTestUtils.setField(service, "placeView", placeView);
        // 空间段的前置门禁：worldMapQueryService / movementService 任一为空即整段跳过
        ReflectionTestUtils.setField(service, "worldMapQueryService",
                mock(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.class));
        when(npcSpatialView.candidates(anyLong(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(npcSpatialView.nearbyAuthority(anyLong(), anyDouble(), anyDouble(),
                anyString(), anyDouble(), anyInt())).thenReturn(List.of());
    }

    private SpatialFix authorityFix(String featureCode) {
        currentFeature = featureCode;
        SpatialFix fix = new SpatialFix();
        fix.setNpcRef("npc:5");
        fix.setX(543.0);
        fix.setZ(636.0);
        fix.setFeatureCode(featureCode);
        fix.setGameSecond(1000L);
        fix.setSource(SpatialFix.SOURCE_AUTHORITY);
        return fix;
    }

    private ActorWorldMap map() {
        ActorWorldMap m = new ActorWorldMap();
        m.setId(1L);
        return m;
    }

    private ActorMapFeature feature(String code, String name, String category, String ownerHomeRef) {
        ActorMapFeature f = new ActorMapFeature();
        f.setCode(code);
        f.setName(name);
        f.setCategory(category);
        f.setOwnerHomeRef(ownerHomeRef);
        f.setKind(ActorMapFeature.KIND_BUILDING);
        return f;
    }

    // ==================== 在途描述必须与坐标同源 ====================

    /**
     * 坐标走权威行时，「正走去哪」只能读权威侧路径行——沙盘移动行的目标绝不能出现在句子里。
     */
    @Test
    void authorityInTransitReadsAuthorityPathNotSandboxMovement() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_blacksmith_19")));
        when(npcSpatialView.featureName(1L, "f_blacksmith_19")).thenReturn("铁匠铺");

        ActorNpcPath path = new ActorNpcPath();
        path.setToRef("loc:河神庙");
        path.setDepartGameSecond(1000L);
        path.setArriveGameSecond(1660L);
        when(npcPathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(1L, "npc:5", "walking"))
                .thenReturn(List.of(path));

        ActorMovement sandboxMove = new ActorMovement();
        sandboxMove.setTargetLocationName("阿珍家");
        when(movementService.inTransitMove(1L, "npc:5")).thenReturn(sandboxMove);

        String text = ReflectionTestUtils.invokeMethod(service, "spatialSegment", 1L, "npc:5", 1000L);

        assertThat(text).isNotNull();
        assertThat(text).contains("正走在去河神庙的路上");
        assertThat(text).contains("从铁匠铺出发");
        assertThat(text).doesNotContain("阿珍家");
    }

    /**
     * 权威行没有在途路径时，不能说「正走在路上」——人在原地，就老实说在哪儿。
     */
    @Test
    void authorityWithoutPathFallsBackToResidence() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_blacksmith_19")));
        when(npcSpatialView.featureName(1L, "f_blacksmith_19")).thenReturn("铁匠铺");
        when(npcPathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(1L, "npc:5", "walking"))
                .thenReturn(List.of());

        ActorMovement sandboxMove = new ActorMovement();
        sandboxMove.setTargetLocationName("阿珍家");
        when(movementService.inTransitMove(1L, "npc:5")).thenReturn(sandboxMove);

        String text = ReflectionTestUtils.invokeMethod(service, "spatialSegment", 1L, "npc:5", 1000L);

        assertThat(text).contains("在铁匠铺");
        assertThat(text).doesNotContain("正走在去");
        assertThat(text).doesNotContain("阿珍家");
    }

    /**
     * 路径行还挂着 walking，但人已经被摆到别处（几何上离路径 20 米开外）——不许再念「正走在路上」。
     */
    @Test
    void stalePathIgnoredWhenPositionOffRoute() {
        SpatialFix fix = authorityFix("f_home_62");
        fix.setX(900.0);
        fix.setZ(900.0);
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(fix));
        when(npcSpatialView.featureName(1L, "f_home_62")).thenReturn("阿珍家");

        ActorNpcPath path = new ActorNpcPath();
        path.setToRef("loc:河神庙");
        path.setDepartGameSecond(1000L);
        path.setArriveGameSecond(1660L);
        path.setPointsJson("[[532.11,638.3],[452.14,266.44]]");
        when(npcPathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(1L, "npc:5", "walking"))
                .thenReturn(List.of(path));

        String text = ReflectionTestUtils.invokeMethod(service, "spatialSegment", 1L, "npc:5", 1000L);

        assertThat(text).contains("在阿珍家");
        assertThat(text).doesNotContain("正走在去");
    }

    // ==================== 空间约束：环境从叙述变成规则 ====================

    /** 别人的住处：必须给出「不是你家、进门先敲门」这类可执行约束。 */
    @Test
    void spatialConstraintWarnsWhenAtOthersHome() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_home_62")));
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(1L)).thenReturn(Optional.of(map()));
        when(mapFeatureRepository.findByMapIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(feature("f_home_62", "阿珍家", "民居", "62")));
        ActorHomeMember member = new ActorHomeMember();
        member.setCharacterRef("npc:6");
        when(homeMemberRepository.findByHomeIdOrderByIdAsc(62L)).thenReturn(List.of(member));

        String text = service.spatialConstraint(1L, "npc:5", 1000L);

        assertThat(text).startsWith("【空间约束】你在阿珍家");
        assertThat(text).contains("不是你家");
        assertThat(text).contains("敲门");
        // 约束之外还要给「可做/不可做」的判据（打分模型只吃后者）
        assertThat(text).contains("【空间选项】");
        assertThat(text).contains("不可做：自己推门进去");
    }

    /** 自己家：同一句「进屋拿凿子」在这里是正当的，约束必须说「可以自由进出」。 */
    @Test
    void spatialConstraintAllowsOwnHome() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_home_5")));
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(1L)).thenReturn(Optional.of(map()));
        when(mapFeatureRepository.findByMapIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(feature("f_home_5", "老吴家", "民居", "5")));
        ActorHomeMember member = new ActorHomeMember();
        member.setCharacterRef("npc:5");
        when(homeMemberRepository.findByHomeIdOrderByIdAsc(5L)).thenReturn(List.of(member));

        String text = service.spatialConstraint(1L, "npc:5", 1000L);

        assertThat(text).contains("自己的住处");
        assertThat(text).contains("自由进出");
        assertThat(text).contains("【空间选项】");
        assertThat(text).contains("可做：自由进出");
        assertThat(text).doesNotContain("自己推门进去");
    }

    /** 陌生地方（要素缺失）：降级为一句「先认清这里是谁的地界」，不许返回半截话。 */
    @Test
    void spatialConstraintDegradesWhenFeatureMissing() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_unknown")));
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(1L)).thenReturn(Optional.of(map()));
        when(mapFeatureRepository.findByMapIdOrderBySortOrderAsc(1L)).thenReturn(List.of());

        String text = service.spatialConstraint(1L, "npc:5", 1000L);

        assertThat(text).startsWith("【空间约束】");
        assertThat(text).contains("再动手");
    }

    /** 没有坐标就没有约束段（不猜、不编）。 */
    @Test
    void spatialConstraintEmptyWhenNoPosition() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.empty());
        assertThat(service.spatialConstraint(1L, "npc:5", 1000L)).isEmpty();
    }

    /**
     * 集市：可做清单里必须出现「买卖」。
     *
     * <p>整世界长跑实测：站在集市上的角色读到的可做清单是「停留、说话、观望、歇脚」，
     * 432 条决策里只有 1 条含「买」、0 笔成交。选项句是模型唯一能对照的判据，
     * 缺了它市场在真实世界里等于不存在。</p>
     */
    @Test
    void spatialConstraintOffersTradeAtShop() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_market")));
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(1L)).thenReturn(Optional.of(map()));
        when(mapFeatureRepository.findByMapIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(feature("f_market", "集市", "公共", null)));

        com.holzyn.actor.domain.world.entity.ActorWorldLocation loc =
                new com.holzyn.actor.domain.world.entity.ActorWorldLocation();
        loc.setId(8L);
        loc.setName("集市");
        com.holzyn.actor.domain.world.repository.WorldLocationRepository locationRepository =
                mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class);
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(loc));
        ReflectionTestUtils.setField(service, "locationRepository", locationRepository);

        com.holzyn.actor.domain.item.port.InventoryPort port =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        when(port.list(1L, "loc:8")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("面包", "面包", "food", 10, 100, null, null)));
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        com.holzyn.actor.domain.item.config.ItemProperties itemProps =
                new com.holzyn.actor.domain.item.config.ItemProperties();
        itemProps.setEnabled(true);
        ReflectionTestUtils.setField(service, "itemProperties", itemProps);

        String text = service.spatialConstraint(1L, "npc:5", 1000L);

        assertThat(text).contains("可做：问价、买东西");
        assertThat(text).contains("柜台上有货");
        assertThat(text).contains("不可做：自己进后堂取货");
    }

    /** 自家院子：可做清单要提一句「缺什么去集市买」（角色在家也得想起去买东西）。 */
    @Test
    void spatialConstraintPointsToMarketFromHome() {
        when(npcSpatialView.resolve(eq(1L), eq("npc:5"), any())).thenReturn(Optional.of(authorityFix("f_home_5")));
        when(worldMapRepository.findFirstByProjectIdOrderByIdDesc(1L)).thenReturn(Optional.of(map()));
        when(mapFeatureRepository.findByMapIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(feature("f_home_5", "老吴家", "民居", "5")));
        ActorHomeMember member = new ActorHomeMember();
        member.setCharacterRef("npc:5");
        when(homeMemberRepository.findByHomeIdOrderByIdAsc(5L)).thenReturn(List.of(member));

        com.holzyn.actor.domain.world.entity.ActorWorldLocation shop =
                new com.holzyn.actor.domain.world.entity.ActorWorldLocation();
        shop.setId(8L);
        shop.setName("集市");
        com.holzyn.actor.domain.world.repository.WorldLocationRepository locationRepository =
                mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class);
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(shop));
        ReflectionTestUtils.setField(service, "locationRepository", locationRepository);

        String text = service.spatialConstraint(1L, "npc:5", 1000L);

        assertThat(text).contains("可做：自由进出");
        assertThat(text).contains("去「集市」买");
    }
}
