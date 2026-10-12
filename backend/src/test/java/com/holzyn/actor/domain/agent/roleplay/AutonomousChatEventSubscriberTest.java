package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * AutonomousChatEventSubscriber（社会对话层修复 B 层）单元测试（与实现同包，domain/agent/roleplay）。
 *
 * <p>职责：验证「事件 → 无玩家群聊回应轮」统一接线的事件过滤与护栏——重要度阈值、
 * kind 自触发/反馈环排除（roleplay/relation）、地点必需、总开关、每日上限（跨游戏日重置）。</p>
 */
class AutonomousChatEventSubscriberTest {

    private WorldEventBus worldEventBus;
    private AutonomousChatService autonomousChatService;
    private AutonomousChatEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        worldEventBus = Mockito.mock(WorldEventBus.class);
        autonomousChatService = Mockito.mock(AutonomousChatService.class);
        subscriber = new AutonomousChatEventSubscriber(worldEventBus, autonomousChatService);
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(subscriber, "chatEnabled", true);
        ReflectionTestUtils.setField(subscriber, "eventResponseEnabled", true);
        ReflectionTestUtils.setField(subscriber, "eventImportanceThreshold", 2);
        ReflectionTestUtils.setField(subscriber, "eventResponseDailyCap", 12);
        ReflectionTestUtils.setField(subscriber, "eventResponseMinGapHours", 1);
        // B 层 2026-09-25「先查后记账」：前置检查默认放行（个别用例按需覆盖为 false）
        Mockito.when(autonomousChatService.canTriggerEventResponse(anyLong(), any(), anyString()))
                .thenReturn(true);
    }

    /** 事件构造（kind/重要度/地点/时刻/内容）。 */
    private ActorEvent event(String kind, Integer importance, Long locationId, String locationName,
                             Long gameHour, String content) {
        ActorEvent e = new ActorEvent();
        e.setId(1L);
        e.setProjectId(1L);
        e.setKind(kind);
        e.setImportance(importance);
        e.setLocationId(locationId);
        e.setLocationName(locationName);
        e.setGameHour(gameHour);
        e.setContent(content);
        return e;
    }

    @Test
    @DisplayName("imp≥阈值 + 带地点的世界事件 → 触发所在地点回应轮（userId 回退 1L）")
    void triggersForImportantEventWithLocation() {
        subscriber.onEvent(event("world", 4, 33L, "青禾茶馆", 130L, "茶馆走水，众人救火"));

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                1L, 1L, 33L, "青禾茶馆", 130L, "茶馆走水，众人救火");
    }

    @Test
    @DisplayName("重要度低于阈值 → 不触发（低重要度琐事不扰动村民议论）")
    void skipsLowImportance() {
        subscriber.onEvent(event("world", 1, 33L, "青禾茶馆", 130L, "无事发生"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("kind=roleplay 跳过（无玩家群聊自身输出，防「议论→事件→议论」自触发环）")
    void skipsRoleplayKind() {
        subscriber.onEvent(event("roleplay", 3, 33L, "青禾茶馆", 130L, "NPC 自发闲聊"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("kind=relation 跳过（关系事件是闲聊输出，防「闲聊→关系→再闲聊」反馈环）")
    void skipsRelationKind() {
        subscriber.onEvent(event("relation", 3, 33L, "青禾茶馆", 130L, "二人好感提升"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("无地点事件跳过（无法定位 NPC 内群）")
    void skipsWithoutLocation() {
        subscriber.onEvent(event("world", 4, null, null, 130L, "远方传闻"));
        subscriber.onEvent(event("world", 4, null, "   ", 130L, "远方传闻"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("仅有地点名（locationId 空）→ 仍触发（地点名快照口径）")
    void triggersWithLocationNameOnly() {
        subscriber.onEvent(event("action", 3, null, "青禾集市", 131L, "张满仓赶集摆摊"));

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                1L, 1L, null, "青禾集市", 131L, "张满仓赶集摆摊");
    }

    @Test
    @DisplayName("总开关关闭 → 不触发")
    void skipsWhenDisabled() {
        ReflectionTestUtils.setField(subscriber, "eventResponseEnabled", false);

        subscriber.onEvent(event("world", 4, 33L, "青禾茶馆", 130L, "事件"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("F12 总开关关闭 → 不触发（且不做每日额度记账）")
    void skipsWhenChatDisabled() {
        ReflectionTestUtils.setField(subscriber, "chatEnabled", false);

        subscriber.onEvent(event("world", 4, 33L, "青禾茶馆", 130L, "事件"));

        verify(autonomousChatService, never()).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("每日上限：同日超出上限的事件不再触发（软护栏，防高频事件打爆预算）")
    void respectsDailyCap() {
        ReflectionTestUtils.setField(subscriber, "eventResponseDailyCap", 2);

        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "事件1"));
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 131L, "事件2"));
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 132L, "事件3")); // 超上限

        verify(autonomousChatService, times(2)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("每日上限跨游戏日重置：第 5 日额度用尽后，第 6 日重新放行")
    void dailyCapResetsNextGameDay() {
        ReflectionTestUtils.setField(subscriber, "eventResponseDailyCap", 1);

        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "第5日事件"));   // 第 5 日
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 131L, "第5日第2件")); // 第 5 日超限
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 154L, "第6日事件"));   // 第 6 日重置

        verify(autonomousChatService, times(2)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("important 缺省（null）按 3 处理 → 达到阈值（默认 2）触发")
    void nullImportanceDefaultsToThree() {
        subscriber.onEvent(event("crowd", null, 33L, "青禾集市", 130L, "集市人流渐多"));

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                eq(1L), eq(1L), eq(33L), eq("青禾集市"), eq(130L), anyString());
    }

    @Test
    @DisplayName("同地点最小间隔：同一地点同一小时内多起事件只议论一场（把节奏摊平到全天）")
    void respectsSameLocationMinGap() {
        ReflectionTestUtils.setField(subscriber, "eventResponseMinGapHours", 1);

        // 同一地点（id=33）同一游戏小时连来 3 起事件 → 只开一场（B 层 2026-09-25 修复爆发式烧额度）
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "事件1"));
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "事件2"));
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "事件3"));

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("同地点最小间隔：间隔满足后放行；不同地点互不影响（间隔按地点计）")
    void gapIsPerLocationAndAllowsAfterInterval() {
        ReflectionTestUtils.setField(subscriber, "eventResponseMinGapHours", 1);

        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "茶馆事件"));   // 地点 33 首场
        subscriber.onEvent(event("action", 3, 44L, "青禾集市", 130L, "集市事件"));   // 不同地点 → 放行
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 131L, "茶馆再议"));   // 地点 33 满 1 小时 → 放行

        verify(autonomousChatService, times(3)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("同地点不足 2 名 NPC → 跳过且不占当日额度（先查后记账，B 层 2026-09-25 修复）")
    void insufficientNpcsSkipsWithoutConsumingQuota() {
        ReflectionTestUtils.setField(subscriber, "eventResponseDailyCap", 1);
        // 首起事件同地点仅 1 人（不可开轮）→ 不得扣额度；其后事件可开轮
        Mockito.when(autonomousChatService.canTriggerEventResponse(anyLong(), any(), anyString()))
                .thenReturn(false, true, true);

        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "无人可议")); // 前置不通过 → 不计额
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 131L, "有人可议")); // 用掉当日唯一额度
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 132L, "超额事件")); // 达上限 → 不再触发

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("每日上限达线仅播报一次（降噪：修复前每起事件各打一行 info）")
    void capBreachLoggedOnceOnly() {
        ReflectionTestUtils.setField(subscriber, "eventResponseDailyCap", 1);
        // 行为断言：达线后的事件一律不触发（日志降噪为可读性改进，此处锁定行为不变）
        subscriber.onEvent(event("action", 3, 33L, "青禾茶馆", 130L, "首场"));
        subscriber.onEvent(event("action", 3, 44L, "青禾集市", 130L, "第二场"));
        subscriber.onEvent(event("action", 3, 55L, "青禾村塾", 130L, "第三场"));

        verify(autonomousChatService, times(1)).triggerEventResponseAsync(
                anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }
}