package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * C 层社交聚集服务单测：{@link SocialGatheringService}（社交中心识别 + 配置 + 零阻断）。
 *
 * <p>所属模块：domain/growth/space（N4 空间候选选择程序化件·C 层扩展）</p>
 */
class SocialGatheringServiceTest {

    private WorldLocationRepository locationRepository;
    private SocialGatheringService service;

    @BeforeEach
    void setUp() {
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        service = new SocialGatheringService(locationRepository);
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "centerKeywords",
                "茶馆,茶楼,茶铺,集市,市集,村口,客栈,酒楼,酒馆,饭馆,饭店,广场,戏台,祠堂,凉亭,亭子,庙");
        ReflectionTestUtils.setField(service, "deepNightStart", 23);
        ReflectionTestUtils.setField(service, "deepNightEnd", 6);
        ReflectionTestUtils.setField(service, "maxCandidates", 2);
        ReflectionTestUtils.setField(service, "weight", 0.3);
    }

    private ActorWorldLocation location(Long id, String name, String type) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setProjectId(1L);
        l.setName(name);
        l.setType(type);
        return l;
    }

    @Test
    @DisplayName("社交中心识别：地点名或类型命中关键词即入选，保序去重，空名跳过")
    void centerNamesMatchesNameOrType() {
        List<ActorWorldLocation> locations = List.of(
                location(1L, "青禾茶馆", "商铺"),
                location(2L, "村东农田", "农田"),
                location(3L, "后山竹林", "林地"),
                location(4L, "听风轩", "茶馆"),      // 类型命中
                location(5L, "青禾茶馆", "商铺"),    // 同名去重
                location(6L, "  ", "商铺"),          // 空名跳过
                location(7L, "村口石牌坊", null));

        List<String> centers = SocialGatheringService.centerNames(locations,
                SocialGatheringService.parseKeywords("茶馆,村口"));

        assertEquals(List.of("青禾茶馆", "听风轩", "村口石牌坊"), centers);
    }

    @Test
    @DisplayName("关键词解析：中英文逗号/顿号/分号分隔，去空白去重保序")
    void parseKeywordsHandlesSeparators() {
        assertEquals(List.of("茶馆", "集市", "村口"),
                SocialGatheringService.parseKeywords("茶馆, 集市，村口"));
        assertEquals(List.of("茶馆", "集市"),
                SocialGatheringService.parseKeywords("茶馆;集市；茶馆"));
        assertTrue(SocialGatheringService.parseKeywords(null).isEmpty());
        assertTrue(SocialGatheringService.parseKeywords("  ").isEmpty());
    }

    @Test
    @DisplayName("candidatesFor：独处白天 → 注入社交聚集候选（默认上限 2、权重 0.3）")
    void candidatesForLoneNpc() {
        when(locationRepository.findByProjectId(1L)).thenReturn(List.of(
                location(1L, "青禾茶馆", "商铺"),
                location(2L, "青禾集市", "集市"),
                location(3L, "后山竹林", "林地")));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                service.candidatesFor(1L, "村东民居", true, 10);

        assertEquals(2, c.size());
        assertEquals("青禾茶馆", c.get(0).target());
        assertEquals("青禾集市", c.get(1).target());
    }

    @Test
    @DisplayName("candidatesFor 零阻断：未启用 / 仓库异常 / 无中心 → 空表（行为回到修复前）")
    void candidatesForZeroBlocking() {
        // 未启用
        ReflectionTestUtils.setField(service, "enabled", false);
        assertTrue(service.candidatesFor(1L, "村东民居", true, 10).isEmpty());
        ReflectionTestUtils.setField(service, "enabled", true);
        // 仓库异常（零阻断）
        when(locationRepository.findByProjectId(1L)).thenThrow(new RuntimeException("db down"));
        assertTrue(service.candidatesFor(1L, "村东民居", true, 10).isEmpty());
        // 无社交中心（⚠️ thenThrow 后的重打桩必须走 doReturn——when(mock.method()) 会真的调用已桩方法而抛异常）
        Mockito.doReturn(List.of(location(3L, "后山竹林", "林地")))
                .when(locationRepository).findByProjectId(1L);
        assertTrue(service.candidatesFor(1L, "村东民居", true, 10).isEmpty());
    }
}