package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 事件→关系好感订阅者（D1 F6，domain/agent/learning）单元测试。
 * <p>验证：参与角色两两双向增减好感、跳过 action/evolution/relation（防双算/自触发）、
 * 重要度阈值过滤、角色 ref 解析。</p>
 */
class LearningRelationEventSubscriberTest {

    private WorldEventBus worldEventBus;
    private RelationDynamicsService relationDynamicsService;
    private LearningRelationEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        worldEventBus = Mockito.mock(WorldEventBus.class);
        relationDynamicsService = Mockito.mock(RelationDynamicsService.class);
        subscriber = new LearningRelationEventSubscriber(worldEventBus, relationDynamicsService);
        ReflectionTestUtils.setField(subscriber, "relationsEnabled", true);
        ReflectionTestUtils.setField(subscriber, "eventDelta", 1);
        ReflectionTestUtils.setField(subscriber, "eventImportanceThreshold", 2);
    }

    private ActorEvent event(String kind, String characterIds, Integer importance) {
        ActorEvent e = new ActorEvent();
        e.setId(1L);
        e.setProjectId(100L);
        e.setKind(kind);
        e.setCharacterIds(characterIds);
        e.setImportance(importance);
        e.setTitle("城东庙会");
        return e;
    }

    /** 带 ≥2 参与角色的事件：两两双向增减好感（3 个角色 → 3 对） */
    @Test
    void onEventAppliesPairDeltasBothDirections() {
        subscriber.onEvent(event("world", "special:1,special:2,special:3", 3));
        // 对 (1,2)(1,3)(2,3) 各双向 → 6 次 applyDialogPairDelta
        verify(relationDynamicsService).applyDialogPairDelta(eq(100L), eq("special:1"), eq("special:2"), eq(1), anyString());
        verify(relationDynamicsService).applyDialogPairDelta(eq(100L), eq("special:1"), eq("special:3"), eq(1), anyString());
        verify(relationDynamicsService).applyDialogPairDelta(eq(100L), eq("special:2"), eq("special:3"), eq(1), anyString());
        verify(relationDynamicsService, Mockito.times(3)).applyDialogPairDelta(anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    /** 跳过 action（行动已由 AgentActionService 挂钩，防双算）/ relation（F6 自身输出，防自触发） */
    @Test
    void onEventSkipsActionAndRelationKinds() {
        subscriber.onEvent(event("action", "special:1,special:2", 3));
        subscriber.onEvent(event("relation", "special:1,special:2", 3));
        verify(relationDynamicsService, never()).applyDialogPairDelta(anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    /** 重要度低于阈值 → 不增减好感（低重要度琐事不扰动关系） */
    @Test
    void onEventSkipsLowImportance() {
        subscriber.onEvent(event("world", "special:1,special:2", 1));
        verify(relationDynamicsService, never()).applyDialogPairDelta(anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    /** 单参与角色/无角色 → 无配对 */
    @Test
    void onEventSkipsSingleParticipant() {
        subscriber.onEvent(event("world", "special:1", 3));
        verify(relationDynamicsService, never()).applyDialogPairDelta(anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    /** 角色 ref 解析（纯静态）：逗号分隔去空白 */
    @Test
    void parseRefsSplitsAndTrims() {
        assertEquals(List.of("special:1", "crowd:2"), LearningRelationEventSubscriber.parseRefs("special:1, crowd:2"));
        assertEquals(List.of(), LearningRelationEventSubscriber.parseRefs(null));
        assertEquals(List.of(), LearningRelationEventSubscriber.parseRefs(""));
        assertEquals(List.of(), LearningRelationEventSubscriber.parseRefs(" , "));
    }
}
