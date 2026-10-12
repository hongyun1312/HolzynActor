package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorStoryPlan;
import com.holzyn.actor.domain.world.repository.ActorStoryPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoryAtmosphereService（M4 story-plan 工作流，2026-09-29_06）单元测试。
 * <p>职责：验证——最新氛围片段读取、TTL 缓存（TTL 内不重查库）、invalidate 失效、
 * 无档案/异常零阻断降级空串。</p>
 */
class StoryAtmosphereServiceTest {

    private ActorStoryPlanRepository repository;
    private StoryAtmosphereService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorStoryPlanRepository.class);
        service = new StoryAtmosphereService(repository);
    }

    @Test
    @DisplayName("读取最新氛围片段；TTL 内命中缓存不重查库")
    void atmosphereOf_cached() {
        ActorStoryPlan row = new ActorStoryPlan();
        row.setVersion(2);
        row.setAtmosphereSegment("药香与山雨，雾隐仙村");
        when(repository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.of(row));

        assertEquals("药香与山雨，雾隐仙村", service.atmosphereOf(5L));
        assertEquals("药香与山雨，雾隐仙村", service.atmosphereOf(5L));
        // 缓存命中：仅查库一次
        verify(repository, times(1)).findTopByProjectIdOrderByVersionDesc(anyLong());
    }

    @Test
    @DisplayName("invalidate 后重新查库取新氛围")
    void atmosphereOf_invalidateReloads() {
        ActorStoryPlan v1 = new ActorStoryPlan();
        v1.setVersion(1);
        v1.setAtmosphereSegment("旧氛围");
        ActorStoryPlan v2 = new ActorStoryPlan();
        v2.setVersion(2);
        v2.setAtmosphereSegment("新氛围");
        when(repository.findTopByProjectIdOrderByVersionDesc(5L))
                .thenReturn(Optional.of(v1), Optional.of(v2));

        assertEquals("旧氛围", service.atmosphereOf(5L));
        service.invalidate(5L);
        assertEquals("新氛围", service.atmosphereOf(5L));
        service.invalidate(5L);
        service.invalidate(null); // null 忽略不抛
    }

    @Test
    @DisplayName("无档案/无片段/异常 → 空串零阻断")
    void atmosphereOf_degrade() {
        when(repository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.empty());
        assertEquals("", service.atmosphereOf(5L));
        assertEquals("", service.atmosphereOf(null));
        ActorStoryPlan empty = new ActorStoryPlan();
        empty.setVersion(1);
        when(repository.findTopByProjectIdOrderByVersionDesc(6L)).thenReturn(Optional.of(empty));
        assertEquals("", service.atmosphereOf(6L));
    }
}
