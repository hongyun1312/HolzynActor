package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentMemoryEventSubscriber（B4 F5 事件→经历记忆 + F18 项目级大事记）单元测试
 * （与实现同包，domain/agent/memory）。
 * <p>职责：验证事件总线订阅者的两条沉淀通道——参与者经历记忆（visibility=private/location 且带
 * characterIds，按事件重要度，跳过 action/evolution 避免与直写方重复）与项目级大事记
 * （kind=world/evolution 重要度≥阈值写 characterId=null）；开关关闭不沉淀；文本格式化。</p>
 * <p>测试要点对齐闭环评估 §7.5：事件记忆去重（由 AgentMemoryService 保证，此处验证触发条件）/
 * 触发阈值/跳过直写方/大事记写入。</p>
 */
class AgentMemoryEventSubscriberTest {

    private WorldEventBus worldEventBus;
    private AgentMemoryService agentMemoryService;
    private AgentMemoryEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        worldEventBus = Mockito.mock(WorldEventBus.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        subscriber = new AgentMemoryEventSubscriber(worldEventBus, agentMemoryService);
        ReflectionTestUtils.setField(subscriber, "recordEvents", true);
        ReflectionTestUtils.setField(subscriber, "eventImportanceThreshold", 2);
        ReflectionTestUtils.setField(subscriber, "projectEventImportanceThreshold", 4);
    }

    // ==================== 参与者经历记忆（F5） ====================

    /**
     * location 可见性 + 带参与角色 ref + 重要度达标 → 为每位当事人写 observation 经历。
     */
    @Test
    void locationEventWritesParticipantExperiences() {
        ActorEvent e = event(1L, "crowd", "crowd", "location", "special:3,crowd:12", 3);
        e.setTitle("集市争执");
        e.setContent("集市争执的起因是货物价格，围观者散去后议论纷纷。");

        subscriber.onEvent(e);

        verify(agentMemoryService, times(2)).recordObservation(eq(100L), anyString(), anyString(), eq(3));
        verify(agentMemoryService).recordObservation(100L, "special:3",
                "【事件经历】集市争执：集市争执的起因是货物价格，围观者散去后议论纷纷。", 3);
        verify(agentMemoryService).recordObservation(100L, "crowd:12",
                "【事件经历】集市争执：集市争执的起因是货物价格，围观者散去后议论纷纷。", 3);
    }

    /**
     * private 可见性 + 带参与角色 → 仅当事人写经历（感知不是全知，当事人私有经历）。
     */
    @Test
    void privateEventWritesOnlyParticipants() {
        ActorEvent e = event(1L, "event", "ai", "private", "special:7", 3);
        e.setTitle("密谈");
        e.setContent("深夜密谈达成了口头约定。");

        subscriber.onEvent(e);

        verify(agentMemoryService).recordObservation(100L, "special:7", "【事件经历】密谈：深夜密谈达成了口头约定。", 3);
    }

    /**
     * global 事件不逐人写经历（由项目级大事记覆盖）。
     */
    @Test
    void globalEventDoesNotWriteParticipantExperiences() {
        ActorEvent e = event(1L, "world", "engine", "global", "special:3", 4);
        e.setTitle("战争爆发");

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
        // global 重大世界事件仍写项目级大事记
        verify(agentMemoryService).recordProjectMemory(eq(100L), anyString(), eq(4));
    }

    /**
     * action 事件不写参与者经历（ActionEngine.execute 已直写，避免重复沉淀）。
     */
    @Test
    void actionEventSkippedForParticipantExperience() {
        ActorEvent e = event(1L, "action", "action", "location", "special:3", 4);
        e.setTitle("张三：前往市场");

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
        verify(agentMemoryService, never()).recordProjectMemory(anyLong(), anyString(), anyInt());
    }

    /**
     * evolution 事件不写参与者经历（WorldEvolutionService.finish 已直写当事人 fact），但写项目级大事记（F18）。
     */
    @Test
    void evolutionEventSkipsExperienceButWritesChronicle() {
        ActorEvent e = event(1L, "evolution", "evolution", "global", "special:3", 4);
        e.setTitle("深夜咖啡馆里的密谈");
        e.setContent("密谈后三人分道扬镳，各自踏上归途。");

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
        verify(agentMemoryService).recordProjectMemory(100L,
                "深夜咖啡馆里的密谈：密谈后三人分道扬镳，各自踏上归途。", 4);
    }

    /**
     * 低重要度事件（<阈值 2）不写参与者经历。
     */
    @Test
    void lowImportanceEventSkippedForExperience() {
        ActorEvent e = event(1L, "event", "ai", "location", "special:3", 1);
        e.setTitle("寻常琐事");

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
    }

    // ==================== 项目级大事记（F18） ====================

    /**
     * 重大世界事件（kind=world，重要度≥4）→ 写项目级大事记（characterId=null，修 C-4）。
     */
    @Test
    void majorWorldEventWritesProjectChronicle() {
        ActorEvent e = event(1L, "world", "engine", "global", null, 4);
        e.setTitle("第3日：城东集市异动");
        e.setContent("第3日：城东集市异动——商旅传闻有异常动静，城防加强了巡查，街巷议论纷纷。");

        subscriber.onEvent(e);

        verify(agentMemoryService).recordProjectMemory(100L,
                "第3日：城东集市异动——商旅传闻有异常动静，城防加强了巡查，街巷议论纷纷。", 4);
    }

    /**
     * 世界事件重要度不足（<4）→ 不写项目级大事记。
     */
    @Test
    void lowImportanceWorldEventSkipsChronicle() {
        ActorEvent e = event(1L, "world", "engine", "global", null, 3);

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordProjectMemory(anyLong(), anyString(), anyInt());
    }

    /**
     * 非 world/evolution 类别（即使重要度达标）不写项目级大事记（大事记只记重大世界事件）。
     */
    @Test
    void nonWorldKindSkipsChronicle() {
        ActorEvent e = event(1L, "manual", "manual", "global", null, 5);

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordProjectMemory(anyLong(), anyString(), anyInt());
    }

    // ==================== 开关与容错 ====================

    /**
     * 开关 record-events=false → 事件不沉任何记忆（回到 B3 前行为）。
     */
    @Test
    void recordEventsDisabledSkipsEverything() {
        ReflectionTestUtils.setField(subscriber, "recordEvents", false);
        ActorEvent e = event(1L, "world", "engine", "global", null, 5);

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
        verify(agentMemoryService, never()).recordProjectMemory(anyLong(), anyString(), anyInt());
    }

    /**
     * null 事件 / 无 projectId 事件 → 安全跳过（订阅者异常隔离）。
     */
    @Test
    void nullEventSkippedSafely() {
        subscriber.onEvent(null);
        ActorEvent e = new ActorEvent();
        e.setProjectId(null);
        subscriber.onEvent(e);
        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
        verify(agentMemoryService, never()).recordProjectMemory(anyLong(), anyString(), anyInt());
    }

    /**
     * 无参与角色的 location 事件不写参与者经历（无当事人）。
     */
    @Test
    void eventWithoutParticipantsSkipsExperience() {
        ActorEvent e = event(1L, "event", "ai", "location", null, 3);
        e.setTitle("无主事件");

        subscriber.onEvent(e);

        verify(agentMemoryService, never()).recordObservation(anyLong(), anyString(), anyString(), anyInt());
    }

    // ==================== 文本格式化 ====================

    /**
     * 经历文本：『【事件经历】标题：内容首行』；大事记文本：『标题：内容』（内容已含标题则不重复）。
     */
    @Test
    void textFormatting() {
        ActorEvent e = event(1L, "world", "engine", "global", null, 4);
        e.setTitle("异动");
        e.setContent("异动——商旅传闻四起。\n第二行");

        assertEquals("【事件经历】异动：异动——商旅传闻四起。",
                AgentMemoryEventSubscriber.experienceText(e));
        assertEquals("异动——商旅传闻四起。\n第二行",
                AgentMemoryEventSubscriber.chronicleText(e), "内容已含标题不重复拼接");

        ActorEvent noTitle = event(2L, "world", "engine", "global", null, 4);
        noTitle.setTitle("");
        noTitle.setContent("只有内容");
        assertTrue(AgentMemoryEventSubscriber.experienceText(noTitle).startsWith("【事件经历】事件："));
        assertEquals("世界事件：只有内容", AgentMemoryEventSubscriber.chronicleText(noTitle),
                "无标题时回退「世界事件」作前缀");
    }

    /** 构造事件实体 */
    private ActorEvent event(long id, String kind, String source, String visibility, String characterIds, int importance) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setProjectId(100L);
        e.setKind(kind);
        e.setSource(source);
        e.setVisibility(visibility);
        e.setCharacterIds(characterIds);
        e.setImportance(importance);
        return e;
    }
}
