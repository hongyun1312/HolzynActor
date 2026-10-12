package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleArchiveRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 世界诞生编年史记录（M4 适配轮 2026-09-29_05：source=worldgen，不进角色感知）单元测试。
 * <p>职责：验证——recordWorldGenChronicle 直插（source=worldgen/day 取当前时钟）、
 * 幂等（已存在跳过）、recentChronicleSegment 注入过滤（worldgen 条目不进【世界近闻】）。</p>
 */
class WorldMemoryChronicleWorldGenTest {

    private ActorWorldChronicleRepository chronicleRepository;
    private WorldMemoryService service;

    @BeforeEach
    void setUp() {
        chronicleRepository = Mockito.mock(ActorWorldChronicleRepository.class);
        service = new WorldMemoryService(chronicleRepository,
                Mockito.mock(ActorWorldChronicleArchiveRepository.class),
                Mockito.mock(ActorWorldEntityRepository.class),
                Mockito.mock(ActorEventRepository.class),
                Mockito.mock(ActorWorldFrameRepository.class),
                Mockito.mock(WorldLocationRepository.class),
                Mockito.mock(ActorCharacterRepository.class),
                Mockito.mock(ActorCharacterRepository.class),
                Mockito.mock(ActorCharacterRelationRepository.class),
                Mockito.mock(WorldEventBus.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "injectRecentCount", 3);
        // 世界时钟：gs=348508 → day 4（第 5 日）
        WorldClockService clock = Mockito.mock(WorldClockService.class);
        when(clock.currentEngineGameSecond(anyLong())).thenReturn(348508L);
        ReflectionTestUtils.setField(service, "worldClockService", clock);
    }

    private ActorWorldChronicle entry(String source, String title, long day) {
        ActorWorldChronicle c = new ActorWorldChronicle();
        c.setProjectId(5L);
        c.setSource(source);
        c.setTitle(title);
        c.setContent("内容：" + title);
        c.setGameDay(day);
        c.setImportance(3);
        return c;
    }

    @Test
    @DisplayName("记录世界诞生：直插 source=worldgen + day 取当前时钟")
    void recordWorldGenChronicleInserts() {
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(anyLong())).thenReturn(List.of());
        boolean wrote = service.recordWorldGenChronicle(5L, "世界「青禾村」诞生", "世界已创建");

        assertTrue(wrote);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        ActorWorldChronicle saved = captor.getValue();
        assertEquals(ActorWorldChronicle.SOURCE_WORLDGEN, saved.getSource());
        assertEquals(4L, saved.getGameDay(), "day 应取当前世界时钟（348508s → 第 4 日）");
        assertEquals("世界「青禾村」诞生", saved.getTitle());
    }

    @Test
    @DisplayName("记录幂等：同项目已存在 worldgen 条目 → 跳过不重复写")
    void recordWorldGenChronicleIdempotent() {
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(anyLong()))
                .thenReturn(List.of(entry(ActorWorldChronicle.SOURCE_WORLDGEN, "旧的世界诞生", 4L)));
        boolean wrote = service.recordWorldGenChronicle(5L, "世界「青禾村」诞生", "世界已创建");
        assertFalse(wrote);
        verify(chronicleRepository, never()).save(any());
    }

    @Test
    @DisplayName("注入过滤：worldgen 条目不进【世界近闻】（防角色 OOC 感知世界被创建）")
    void recentChronicleSegmentFiltersWorldGen() {
        List<ActorWorldChronicle> mixed = List.of(
                entry(ActorWorldChronicle.SOURCE_WORLDGEN, "世界诞生（不可见）", 5L),
                entry(ActorWorldChronicle.SOURCE_DAILY, "集市开张", 4L),
                entry(ActorWorldChronicle.SOURCE_INSTANT, "山神庙修缮", 3L));
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(5L)).thenReturn(mixed);

        String segment = service.recentChronicleSegment(5L, 3);
        assertTrue(segment.contains("集市开张"), "正常编年史应注入");
        assertTrue(segment.contains("山神庙修缮"), "正常编年史应注入");
        assertFalse(segment.contains("世界诞生"), "worldgen 条目不应注入角色感知");
    }

    @Test
    @DisplayName("注入过滤：全部为 worldgen 条目 → 空段（零注入最真实）")
    void recentChronicleSegmentEmptyWhenOnlyWorldGen() {
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(5L))
                .thenReturn(List.of(entry(ActorWorldChronicle.SOURCE_WORLDGEN, "世界诞生", 5L)));
        assertEquals("", service.recentChronicleSegment(5L, 3));
    }
}
