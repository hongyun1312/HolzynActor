package com.holzyn.actor.domain.sandbox.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.agent.event.ConversationLiveSseHub;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.sandbox.vo.SandboxOverviewVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxSceneVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxStateVO;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 世界沙盘 SSE 广播中心单元测试（世界沙盘 S2，domain/sandbox/service，同包）。
 * <p>职责：验证——订阅即推送 connected + sandbox:init 全量快照（场景 + 状态 + 时钟）、
 * 广播按项目隔离、10 事件载荷组装（moved/arrived/state/thought/join/leave/clock）、
 * 世界事件转发为 scene:event、sse-enabled 开关关闭时广播跳过、活跃订阅数统计。</p>
 * <p>设计依据：《HolzynActor_世界沙盘_主开发方案_V1.0》§6.2/§10.1 S2。</p>
 */
class WorldSandboxSseHubTest {

    private SseLifecycle sseLifecycle;
    private WorldSandboxStateService sandboxStateService;
    private WorldEventBus worldEventBus;
    private ConversationLiveSseHub conversationLiveSseHub;
    private ActorConversationRepository conversationRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private WorldClockService worldClockService;
    private WorldSandboxSseHub hub;

    /** 记录发送事件（emitter, name, data） */
    private final List<Map<String, Object>> sent = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        sseLifecycle = Mockito.mock(SseLifecycle.class);
        sandboxStateService = Mockito.mock(WorldSandboxStateService.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        conversationLiveSseHub = Mockito.mock(ConversationLiveSseHub.class);
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        hub = new WorldSandboxSseHub(sseLifecycle, worldEventBus,
                conversationLiveSseHub, conversationRepository, characterRepository,
                ordinaryNpcRepository);
        // 字段注入的两个服务（sandboxStateService / worldClockService）经反射设置（避免构造循环）
        setField(hub, "sandboxStateService", sandboxStateService);
        setField(hub, "worldClockService", worldClockService);

        when(sseLifecycle.create(anyLong(), anyLong(), any())).thenAnswer(inv -> new SseEmitter());
        when(sseLifecycle.send(any(), anyString(), any())).thenAnswer(inv -> {
            sent.add(Map.of("emitter", inv.getArgument(0), "name", inv.getArgument(1), "data", inv.getArgument(2)));
            return true;
        });
        // init 快照：1 场景 + 1 状态 + 1 时钟
        SandboxOverviewVO overview = new SandboxOverviewVO();
        overview.setProjectId(1L);
        overview.setSceneCount(1);
        SandboxSceneVO scene = new SandboxSceneVO();
        scene.setId(7L);
        scene.setName("青岩村");
        overview.setScenes(List.of(scene));
        overview.setMainScene(scene);
        SandboxStateVO st = new SandboxStateVO();
        st.setId(1L);
        st.setNpcRef("special:10");
        st.setNpcName("罗小黑");
        st.setMoveState("idle");
        st.setActionText("在青岩村巡逻");
        overview.setStates(List.of(st));
        overview.setStateCount(1);
        when(sandboxStateService.overview(1L)).thenReturn(overview);

        ActorWorldClock clock = new ActorWorldClock();
        clock.setProjectId(1L);
        clock.setPaused(0);
        when(worldClockService.requireClock(1L)).thenReturn(clock);
        WorldClockVO clockVO = new WorldClockVO();
        clockVO.setProjectId(1L);
        clockVO.setDay(1L);
        clockVO.setGameHour(8L);
        clockVO.setGameSecond(28800L);
        clockVO.setPaused(0);
        clockVO.setRate(24);
        clockVO.setWorldRunning(1);
        clockVO.setGameTimeText("青岩历 第1天 08:00");
        when(worldClockService.toVO(any(ActorWorldClock.class))).thenReturn(clockVO);
    }

    /** 反射设置字段（字段注入服务在单测中手动装配）。 */
    private static void setField(WorldSandboxSseHub hub, String name, Object value) throws Exception {
        Field f = WorldSandboxSseHub.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(hub, value);
    }

    // ==================== 订阅 + init 快照 ====================

    @Test
    void subscribe_sendsConnectedAndInitSnapshot() {
        hub.subscribe(1L);

        assertEquals(2, sent.size(), "订阅应推 connected + sandbox:init 两条");
        assertEquals(SseLifecycle.EVENT_CONNECTED, sent.get(0).get("name"));
        assertEquals("sandbox:init", sent.get(1).get("name"));
        Map<String, Object> init = (Map<String, Object>) sent.get(1).get("data");
        assertEquals(1L, ((Number) init.get("projectId")).longValue());
        assertEquals(1, ((Number) init.get("sceneCount")).intValue());
        assertEquals(1, ((Number) init.get("stateCount")).intValue());
        assertEquals(1, ((List<?>) init.get("states")).size(), "init 应回放全部 NPC 状态");
        assertNotNull(init.get("clock"), "init 应含时钟载荷");
        Map<String, Object> clock = (Map<String, Object>) init.get("clock");
        assertEquals("青岩历 第1天 08:00", clock.get("gameTimeText"));
    }

    @Test
    void subscribe_countsActiveSubscriber() {
        hub.subscribe(1L);
        hub.subscribe(1L);
        hub.subscribe(2L);
        assertEquals(3, hub.size(), "全局活跃订阅数=3");
        assertEquals(2, hub.size(1L), "项目 1 活跃订阅=2");
        assertEquals(1, hub.size(2L), "项目 2 活跃订阅=1");
    }

    // ==================== 广播按项目隔离 + 载荷组装 ====================

    @Test
    void publish_isIsolatedByProject() {
        hub.subscribe(1L);
        hub.subscribe(2L);
        sent.clear();

        hub.publish(1L, WorldSandboxSseHub.EVENT_NPC_MOVED, Map.of("npcRef", "special:10"));

        assertEquals(1, sent.size(), "只广播给项目 1 的订阅者");
        assertEquals("npc:moved", sent.get(0).get("name"));
    }

    @Test
    void publishNpcMoved_buildsPayload() {
        hub.subscribe(1L);
        sent.clear();

        hub.publishNpcMoved(1L, "special:10", new double[]{10d, 0d, 20d}, new double[]{30d, 0d, 40d}, 10L);

        assertEquals(1, sent.size());
        Map<String, Object> data = (Map<String, Object>) sent.get(0).get("data");
        assertEquals("special:10", data.get("npcRef"));
        assertEquals(List.of(10d, 0d, 20d), data.get("from"));
        assertEquals(List.of(30d, 0d, 40d), data.get("to"));
        assertEquals(10L, ((Number) data.get("durationGameSec")).longValue());
    }

    @Test
    void publishNpcState_thought_join_leave_buildPayloads() {
        hub.subscribe(1L);
        sent.clear();

        hub.publishNpcState(1L, "special:10", "walking", "前往酒馆", "心情不错", 100L);
        hub.publishNpcThought(1L, "crowd:5", "今天天气真好", "derived", 200L);
        hub.publishNpcJoin(1L, "crowd:6", "crowd", "阿福");
        hub.publishNpcLeave(1L, "special:10", "special", "罗小黑");

        assertEquals(4, sent.size());
        assertEquals("npc:state", sent.get(0).get("name"));
        assertEquals("npc:thought", sent.get(1).get("name"));
        assertEquals("npc:join", sent.get(2).get("name"));
        assertEquals("npc:leave", sent.get(3).get("name"));
        Map<String, Object> thought = (Map<String, Object>) sent.get(1).get("data");
        assertEquals("crowd:5", thought.get("npcRef"));
        assertEquals("derived", thought.get("source"));
    }

    @Test
    void publishClock_buildsPayload() {
        hub.subscribe(1L);
        sent.clear();

        hub.publishClock(1L, 1);

        assertEquals(1, sent.size());
        assertEquals("clock", sent.get(0).get("name"));
        Map<String, Object> data = (Map<String, Object>) sent.get(0).get("data");
        assertEquals(1, data.get("paused"), "paused 覆盖应生效");
    }

    // ==================== scene:event 转发 ====================

    @Test
    void onEvent_forwardsAsSceneEvent() {
        hub.subscribe(1L);
        sent.clear();

        ActorEvent event = new ActorEvent();
        event.setId(99L);
        event.setProjectId(1L);
        event.setKind("world_event");
        event.setTitle("山洪暴发");
        event.setContent("青岩村上游山洪暴发，村民紧急撤离。");
        hub.onEvent(event);

        assertEquals(1, sent.size());
        assertEquals("scene:event", sent.get(0).get("name"));
        Map<String, Object> data = (Map<String, Object>) sent.get(0).get("data");
        assertEquals(99L, ((Number) data.get("id")).longValue());
        assertEquals("山洪暴发", data.get("title"));
    }

    @Test
    void onEvent_ignoresNullEvent() {
        hub.subscribe(1L);
        sent.clear();
        hub.onEvent(null);
        assertEquals(0, sent.size(), "空事件应忽略");
    }

    // ==================== sse-enabled 开关 ====================

    @Test
    void publish_skipsWhenDisabled() throws Exception {
        hub.subscribe(1L);
        sent.clear();
        Field f = WorldSandboxSseHub.class.getDeclaredField("sseEnabled");
        f.setAccessible(true);
        f.setBoolean(hub, false);

        hub.publish(1L, WorldSandboxSseHub.EVENT_NPC_MOVED, Map.of("npcRef", "special:10"));
        assertEquals(0, sent.size(), "开关关闭时不应广播");
        assertEquals(1, hub.size(1L), "开关不影响订阅者计数");
    }

    // ==================== U1 旁路监听器（Unity 下行数据源） ====================

    @Test
    void addListener_forwardsPublishEvents() {
        List<WorldSandboxSseHub.SandboxEvent> received = new ArrayList<>();
        hub.addListener(received::add);

        hub.publish(1L, WorldSandboxSseHub.EVENT_NPC_MOVED, Map.of("npcRef", "special:10"));
        hub.publishNpcState(1L, "special:10", "idle", "站在村口", "心情不错", 100L);
        hub.publishClock(1L, 0);

        assertEquals(3, received.size(), "旁路监听器收到全部 publish 事件（含类型化发布入口）");
        assertEquals(WorldSandboxSseHub.EVENT_NPC_MOVED, received.get(0).name());
        assertEquals(1L, received.get(0).projectId());
        assertEquals("special:10", received.get(0).data().get("npcRef"));
        assertEquals(WorldSandboxSseHub.EVENT_NPC_STATE, received.get(1).name());
        assertEquals(WorldSandboxSseHub.EVENT_CLOCK, received.get(2).name());
    }

    @Test
    void addListener_isIsolatedByProjectAndIdempotent() {
        List<WorldSandboxSseHub.SandboxEvent> received = new ArrayList<>();
        hub.addListener(received::add);
        hub.addListener(received::add); // 重复注册也独立计数
        assertEquals(2, hub.listenerCount());

        hub.publish(1L, WorldSandboxSseHub.EVENT_WEATHER, Map.of("condition", "晴"));
        assertEquals(2, received.size(), "两个监听器各收一条");
        assertEquals("weather", received.get(0).name());
        assertEquals("晴", received.get(0).data().get("condition"));
    }

    @Test
    void addListener_exceptionIsolatedFromPublish() {
        List<WorldSandboxSseHub.SandboxEvent> received = new ArrayList<>();
        hub.addListener(e -> {
            throw new RuntimeException("监听器异常不应影响发布方");
        });
        hub.addListener(received::add);

        // 异常监听器抛错 → 隔离继续，不影响 SSE 广播与其它监听器
        hub.subscribe(1L);
        sent.clear();
        hub.publish(1L, WorldSandboxSseHub.EVENT_NPC_MOVED, Map.of("npcRef", "special:10"));
        assertEquals(1, sent.size(), "SSE 广播不受监听器异常影响");
        assertEquals(1, received.size(), "第二个监听器仍收到");
    }
}
