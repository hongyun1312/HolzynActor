package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldEventBus（B1 F1 世界事件总线）单元测试（与实现同包，访问纯逻辑静态方法）。
 * <p>职责：验证发布（字段落全/幂等/游戏时刻解析/开关关闭跳过/滚动保护/广播订阅者）与
 * 查询（可见性过滤 global/location/private、游戏时间窗、类别/来源/地点过滤、limit 截断）。</p>
 * <p>开关字段（@Value 注入）用反射设置，脱离 Spring 上下文直测实现逻辑。</p>
 */
class WorldEventBusTest {

    private ActorEventRepository eventRepository;
    private WorldClockService worldClockService;
    private WorldEventSseHub sseHub;
    private WorldEventWsHub wsHub;
    private WorldEventBusImpl bus;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository = Mockito.mock(ActorEventRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        sseHub = Mockito.mock(WorldEventSseHub.class);
        wsHub = Mockito.mock(WorldEventWsHub.class);
        bus = new WorldEventBusImpl(eventRepository, worldClockService, sseHub, wsHub);
        setField(bus, "eventsEnabled", true);
        setField(bus, "eventsMaxPerProject", 5000);
        // 默认：save 返回入参（便于断言落库字段）；无幂等命中；滚动不超限
        when(eventRepository.save(any(ActorEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.findTopByProjectIdAndSourceKey(anyLong(), any())).thenReturn(Optional.empty());
        when(eventRepository.countByProjectId(anyLong())).thenReturn(0L);
    }

    // ==================== 发布 ====================

    /**
     * 发布落库字段完整：kind/source/角色 ref/location/visibility/payload/gameHour/sourceKey/importance
     * 全部正确写入。
     */
    @Test
    void publishPersistsAllFields() {
        when(worldClockService.currentGameHour(100L)).thenReturn(888L);
        EventSpec spec = EventSpec.builder()
                .kind("action")
                .source("action")
                .characterId(3L)
                .characterRefs(List.of("npc:3", "npc:12"))
                .locationId(7L)
                .locationName("城东市场")
                .visibility("global")
                .title("张三：前往市场采购")
                .content("前往市场采购药材（目标：城东市场）")
                .payloadJson("{\"type\":\"move\"}")
                .importance(4)
                .gameHour(888L)
                .sourceKey("plan:42")
                .build();
        ActorEvent saved = bus.publish(1L, 100L, spec);

        assertNotNull(saved);
        assertEquals(100L, saved.getProjectId());
        assertEquals("action", saved.getKind());
        assertEquals("action", saved.getSource());
        assertEquals(3L, saved.getCharacterId());
        // G1a：参与引用统一 npc:{id}（joinRefs 去重后按序拼接）
        assertEquals("npc:3,npc:12", saved.getCharacterIds());
        assertEquals(7L, saved.getLocationId());
        assertEquals("城东市场", saved.getLocationName());
        assertEquals("global", saved.getVisibility());
        assertEquals("张三：前往市场采购", saved.getTitle());
        assertTrue(saved.getContent().contains("采购药材"));
        assertEquals("{\"type\":\"move\"}", saved.getPayloadJson());
        assertEquals(Integer.valueOf(4), saved.getImportance(), "事件重要度应落库");
        assertEquals(888L, saved.getGameHour());
        assertEquals("plan:42", saved.getSourceKey());
        assertNotNull(saved.getCreatedAt());
    }

    /**
     * 事件重要度：未传回退 3（B4 记忆沉淀缺省）；越界钳制 1~5。
     */
    @Test
    void publishNormalizesImportance() {
        ActorEvent defaulted = bus.publish(1L, 100L,
                EventSpec.builder().kind("world").source("ai").title("缺省").build());
        assertEquals(Integer.valueOf(3), defaulted.getImportance(), "未传重要度回退 3");
        assertEquals(Integer.valueOf(3), WorldEventBusImpl.normalizeImportance(null));
        assertEquals(Integer.valueOf(5), WorldEventBusImpl.normalizeImportance(9), "越界钳制到 5");
        assertEquals(Integer.valueOf(1), WorldEventBusImpl.normalizeImportance(-2), "越界钳制到 1");
        assertEquals(Integer.valueOf(4), WorldEventBusImpl.normalizeImportance(4), "合法值原样");
    }

    /**
     * 幂等：同一 project_id+source_key 已存在 → 返回已有事件，不再落库（修 C-21）。
     */
    @Test
    void publishIdempotentBySourceKey() {
        ActorEvent existing = new ActorEvent();
        existing.setId(9L);
        existing.setProjectId(100L);
        existing.setSourceKey("plan:42");
        when(eventRepository.findTopByProjectIdAndSourceKey(100L, "plan:42"))
                .thenReturn(Optional.of(existing));

        EventSpec spec = EventSpec.builder().kind("action").source("action").sourceKey("plan:42").build();
        ActorEvent result = bus.publish(1L, 100L, spec);

        assertSame(existing, result, "幂等命中应返回已有事件");
        verify(eventRepository, never()).save(any(ActorEvent.class));
    }

    /**
     * 未传 gameHour 时由世界时钟解析（统一时间口径，R2）。
     * <p>P0-2（2026-09-11 状态循环修复）：缺省<b>优先引擎推进位置</b>（currentEngineGameHour）——
     * 流动时钟（currentGameHour）在引擎停止/慢速时远超模拟位置（24h 压测实证 87 游戏天），
     * 会把事件时刻虚快并经决策链流入 actor_state 造成状态冻结；引擎不可读时回退流动时钟。</p>
     */
    @Test
    void publishResolvesGameHourFromClock() {
        // 引擎位置不可读（存量时钟行无秒级/小时级位置）→ 回退流动时钟（最后手段）
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(null);
        when(worldClockService.currentGameHour(100L)).thenReturn(777L);
        EventSpec spec = EventSpec.builder().kind("world").source("ai").title("战争爆发").build();
        ActorEvent saved = bus.publish(1L, 100L, spec);
        assertEquals(777L, saved.getGameHour());
    }

    /**
     * P0-2：缺省时刻优先引擎推进位置（currentEngineGameHour=123），流动时钟 999 不得使用。
     */
    @Test
    void publishResolvesGameHourPrefersEnginePosition() {
        when(worldClockService.currentEngineGameHour(100L)).thenReturn(123L);
        when(worldClockService.currentGameHour(100L)).thenReturn(999L);
        EventSpec spec = EventSpec.builder().kind("world").source("ai").title("灵脉异动").build();
        ActorEvent saved = bus.publish(1L, 100L, spec);
        assertEquals(123L, saved.getGameHour(), "缺省时刻应取引擎推进位置而非流动时钟");
    }

    /**
     * 发布开关关闭（events-enabled=false）→ publish 返回 null，不落库不广播（回到 B1 前行为）。
     */
    @Test
    void publishDisabledReturnsNull() throws Exception {
        setField(bus, "eventsEnabled", false);
        EventSpec spec = EventSpec.builder().kind("action").source("action").title("行动").build();
        ActorEvent result = bus.publish(1L, 100L, spec);
        assertNull(result);
        verify(eventRepository, never()).save(any(ActorEvent.class));
        verify(sseHub, never()).broadcast(anyLong(), any());
    }

    /**
     * 滚动保护：项目事件数超限清理最旧（路线图风险表「每项目最近 5000 条」保护）。
     */
    @Test
    void retentionCleansOldestWhenOverCap() throws Exception {
        setField(bus, "eventsMaxPerProject", 3);
        when(eventRepository.countByProjectId(100L)).thenReturn(5L);
        ActorEvent e1 = event(1L);
        ActorEvent e2 = event(2L);
        ActorEvent e3 = event(3L);
        ActorEvent e4 = event(4L);
        ActorEvent e5 = event(5L);
        when(eventRepository.findByProjectIdOrderByIdAsc(100L))
                .thenReturn(List.of(e1, e2, e3, e4, e5));

        bus.publish(1L, 100L, EventSpec.builder().kind("world").source("ai").build());

        ArgumentCaptor<List<ActorEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).deleteAll(captor.capture());
        List<ActorEvent> deleted = captor.getValue();
        assertEquals(2, deleted.size(), "超 2 条应清理最旧 2 条");
        assertEquals(1L, deleted.get(0).getId());
        assertEquals(2L, deleted.get(1).getId());
    }

    /**
     * 未超限不触发滚动清理。
     */
    @Test
    void retentionNotTriggeredWhenUnderCap() {
        when(eventRepository.countByProjectId(100L)).thenReturn(2L);
        bus.publish(1L, 100L, EventSpec.builder().kind("world").source("ai").build());
        verify(eventRepository, never()).deleteAll(any());
    }

    /**
     * 发布后广播：SSE Hub 收到项目载荷 + 订阅者收到事件（调试事件流同源）。
     */
    @Test
    void publishBroadcastsToSseAndSubscribers() {
        AtomicReference<ActorEvent> received = new AtomicReference<>();
        WorldEventSubscriber subscriber = received::set;
        bus.subscribe(subscriber);
        bus.publish(1L, 100L, EventSpec.builder().kind("world").source("ai").title("事件").build());

        verify(sseHub).broadcast(anyLong(), any(Map.class));
        assertNotNull(received.get(), "订阅者应收到事件");
        assertEquals("world", received.get().getKind());
    }

    // ==================== 查询（静态纯逻辑 + 端到端） ====================

    /**
     * 角色视角可见性：global 全可见 / private 仅当事人 / location 对当事人或同地点在场者可见。
     */
    @Test
    void queryVisibilityFilter() {
        ActorEvent global = event(1L);
        global.setKind("world");
        global.setVisibility("global");
        ActorEvent privateEvent = event(2L);
        privateEvent.setVisibility("private");
        privateEvent.setCharacterIds("npc:5");
        ActorEvent locationEvent = event(3L);
        locationEvent.setVisibility("location");
        locationEvent.setLocationId(7L);
        locationEvent.setCharacterIds("npc:9");
        ActorEvent locationMine = event(4L);
        locationMine.setVisibility("location");
        locationMine.setLocationId(8L);
        locationMine.setCharacterIds("npc:5");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L))
                .thenReturn(List.of(global, privateEvent, locationEvent, locationMine));

        // 角色 special:5 在地点 7：global 可见、private(当事人) 可见、location(同地点 7 在场) 可见、
        // location(当事人在 8) 可见 → 4 条
        List<ActorEvent> seen = bus.query(100L, EventQuery.forCharacter("npc:5", 7L, null, 100));
        assertEquals(4, seen.size());
        assertTrue(seen.stream().anyMatch(e -> e.getId() == 2L), "当事人应看到 private 事件");
        assertTrue(seen.stream().anyMatch(e -> e.getId() == 3L), "同地点在场者应看到 location 事件");

        // 同地点在场者（非当事人）在 location 地点 7 可见；private 仍不可见
        List<ActorEvent> bystander = bus.query(100L, EventQuery.forCharacter("npc:99", 7L, null, 100));
        assertTrue(bystander.stream().anyMatch(e -> e.getId() == 3L), "同地点在场者应看到 location 事件");
        assertFalse(bystander.stream().anyMatch(e -> e.getId() == 2L), "非当事人不应看到 private 事件");

        // 不带 characterRef：全部可见
        assertEquals(4, bus.query(100L, EventQuery.recent(100)).size());
    }

    /**
     * 游戏时间窗过滤（统一 game_hour 口径，修 C-18）。
     */
    @Test
    void queryGameHourRange() {
        assertTrue(WorldEventBusImpl.matchGameHour(eventWithHour(100L), null, null));
        assertTrue(WorldEventBusImpl.matchGameHour(eventWithHour(100L), 90L, 110L));
        assertFalse(WorldEventBusImpl.matchGameHour(eventWithHour(100L), 120L, null));
        assertFalse(WorldEventBusImpl.matchGameHour(eventWithHour(100L), null, 99L));
    }

    /**
     * 类别/来源/地点过滤 + limit 截断。
     */
    @Test
    void queryKindSourceLocationAndLimit() {
        ActorEvent action = event(1L);
        action.setKind("action");
        action.setSource("action");
        action.setLocationId(7L);
        ActorEvent crowd = event(2L);
        crowd.setKind("crowd");
        crowd.setSource("crowd");
        ActorEvent world = event(3L);
        world.setKind("world");
        world.setSource("ai");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L))
                .thenReturn(List.of(action, crowd, world));

        assertEquals(1, bus.query(100L, new EventQuery("action", null, null, null, null, null, 100)).size());
        assertEquals(1, bus.query(100L, new EventQuery(null, "crowd", null, null, null, null, 100)).size());
        assertEquals(1, bus.query(100L, new EventQuery(null, null, 7L, null, null, null, 100)).size());
        assertEquals(2, bus.query(100L, new EventQuery(null, null, null, null, null, null, 2)).size());
    }

    /**
     * 归一化静态逻辑：kind/source/visibility 非法回退、角色 ref 拼接/包含。
     */
    @Test
    void normalizationHelpers() {
        assertEquals("event", WorldEventBusImpl.normalizeKind(null));
        assertEquals("event", WorldEventBusImpl.normalizeKind("  unknown  "));
        assertEquals("action", WorldEventBusImpl.normalizeKind("Action"));
        assertEquals("relation", WorldEventBusImpl.normalizeKind("relation"), "D1 F6 新增 relation kind");
        assertEquals("world_frame", WorldEventBusImpl.normalizeKind("world_frame"), "M4 新增 world_frame kind（每日世界帧宏观事件）");
        assertEquals("macro", WorldEventBusImpl.normalizeKind("macro"), "宏观社会演化系统 M2 新增 macro kind（宏观事件）");
        assertEquals("manual", WorldEventBusImpl.normalizeSource(null));
        assertEquals("manual", WorldEventBusImpl.normalizeSource("bogus"));
        assertEquals("crowd", WorldEventBusImpl.normalizeSource("crowd"));
        assertEquals("learning", WorldEventBusImpl.normalizeSource("learning"), "D1 F6 新增 learning source");
        assertEquals("engine", WorldEventBusImpl.normalizeSource("engine"), "M4 修复 engine source 归一化（引擎来源不再落 manual）");
        assertEquals("macro", WorldEventBusImpl.normalizeSource("macro"), "宏观社会演化系统 M2 新增 macro source（宏观事件来源）");
        assertEquals("location", WorldEventBusImpl.normalizeVisibility(null));
        assertEquals("location", WorldEventBusImpl.normalizeVisibility("evil"));
        assertEquals("global", WorldEventBusImpl.normalizeVisibility("GLOBAL"));
        assertEquals("npc:3,npc:12", WorldEventBusImpl.joinRefs(List.of("npc:3", "npc:12", "npc:3"), null));
        assertEquals("npc:3", WorldEventBusImpl.joinRefs(List.of(), 3L));
        assertNull(WorldEventBusImpl.joinRefs(null, null));
        assertTrue(WorldEventBusImpl.containsRef("npc:3,npc:12", "npc:12"));
        assertFalse(WorldEventBusImpl.containsRef("npc:3", "npc:12"));
    }

    // ==================== 辅助 ====================

    /** 反射设置 @Value 私有字段（脱离 Spring 上下文测试） */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 构造事件实体（仅 id/projectId） */
    private ActorEvent event(long id) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setProjectId(100L);
        return e;
    }

    /** 构造带游戏时刻的事件实体 */
    private ActorEvent eventWithHour(long gameHour) {
        ActorEvent e = new ActorEvent();
        e.setGameHour(gameHour);
        return e;
    }
}
