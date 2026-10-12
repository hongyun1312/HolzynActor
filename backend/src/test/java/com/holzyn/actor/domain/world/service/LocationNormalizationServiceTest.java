package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LocationNormalizationService（D3 F11 统一位置模型）单元测试。
 * <p>职责：验证位置归一核心——精确→包含→未命中兜底匹配；normalize/normalizedName/normalizedId；
 * 空入参/空地点表/异常降级（保留自由文本）。</p>
 */
class LocationNormalizationServiceTest {

    private WorldLocationRepository locationRepository;
    private LocationNormalizationService service;

    @BeforeEach
    void setUp() {
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        service = new LocationNormalizationService(locationRepository);
    }

    private ActorWorldLocation loc(Long id, String name) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        return l;
    }

    /** 精确匹配：trim 全等命中 */
    @Test
    void matchExact() {
        ActorWorldLocation hit = LocationNormalizationService.matchLocation("城东市场",
                List.of(loc(1L, "妖灵会馆"), loc(2L, "城东市场"), loc(3L, "城西酒馆")));
        assertNotNull(hit);
        assertEquals(2L, hit.getId());
        assertEquals("城东市场", hit.getName());
    }

    /** 精确匹配忽略首尾空白 */
    @Test
    void matchExactTrimsWhitespace() {
        ActorWorldLocation hit = LocationNormalizationService.matchLocation("  城东市场 ",
                List.of(loc(2L, "城东市场")));
        assertNotNull(hit);
        assertEquals(2L, hit.getId());
    }

    /** 包含匹配：名称含于串（「城东」∈「城东市场」）或串含于名称；取最长名称更具体 */
    @Test
    void matchContainsPrefersLongest() {
        ActorWorldLocation hit = LocationNormalizationService.matchLocation("城东市场北门",
                List.of(loc(1L, "城东"), loc(2L, "城东市场")));
        assertNotNull(hit);
        assertEquals(2L, hit.getId(), "包含匹配应取最长名称（更具体的子地点）");
    }

    /** 串含于名称：自由文本「市场」命中「城东市场」 */
    @Test
    void matchContainsTextInsideName() {
        ActorWorldLocation hit = LocationNormalizationService.matchLocation("市场",
                List.of(loc(2L, "城东市场")));
        assertNotNull(hit);
        assertEquals(2L, hit.getId());
    }

    /** 未命中：无任何名称重叠 → null（自由文本兜底） */
    @Test
    void matchNone() {
        assertNull(LocationNormalizationService.matchLocation("深山老林",
                List.of(loc(1L, "城东市场"), loc(2L, "妖灵会馆"))));
        assertNull(LocationNormalizationService.matchLocation("城东市场", List.of()));
        assertNull(LocationNormalizationService.matchLocation("城东市场", null));
        assertNull(LocationNormalizationService.matchLocation(null, List.of(loc(1L, "城东市场"))));
        assertNull(LocationNormalizationService.matchLocation("  ", List.of(loc(1L, "城东市场"))));
    }

    /** normalize：命中返回 {locationId, 规范名}；未命中返回 {locationId=null, name=自由文本}；空入参 null */
    @Test
    void normalizeResolvesAndFallsBack() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(loc(1L, "城东市场"), loc(2L, "妖灵会馆")));

        LocationNormalizationService.LocationRef hit = service.normalize(1L, "城东市场");
        assertNotNull(hit);
        assertTrue(hit.matched());
        assertEquals(1L, hit.locationId());
        assertEquals("城东市场", hit.name());

        LocationNormalizationService.LocationRef miss = service.normalize(1L, "无名山洞");
        assertNotNull(miss);
        assertFalse(miss.matched());
        assertNull(miss.locationId());
        assertEquals("无名山洞", miss.name());

        assertNull(service.normalize(1L, null));
        assertNull(service.normalize(1L, "  "));
        assertNull(service.normalize(null, "城东市场"));
    }

    /** normalizedId / normalizedName 便捷方法 */
    @Test
    void convenienceMethods() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(loc(1L, "城东市场")));
        assertEquals(1L, service.normalizedId(1L, "城东市场"));
        assertEquals("城东市场", service.normalizedName(1L, "城东市场"));
        assertNull(service.normalizedId(1L, "无名山洞"));
        assertEquals("无名山洞", service.normalizedName(1L, "无名山洞"));
        assertNull(service.normalizedName(1L, null));
    }

    /** 仓库异常 → 降级保留自由文本（不阻断写入） */
    @Test
    void repositoryFailureFallsBack() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(1L))
                .thenThrow(new RuntimeException("db down"));
        LocationNormalizationService.LocationRef ref = service.normalize(1L, "城东市场");
        assertNotNull(ref);
        assertFalse(ref.matched());
        assertEquals("城东市场", ref.name());
    }
}
