package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观事件服务单元测试（宏观社会演化系统 M1/M2，domain/world/service，同包，mock 总线）。
 * <p>职责：验证严重度分级、阈值 AI 叙事判定（粮价&gt;20%/势力&gt;30%）、规则模板文本、
 * 幂等（同 sourceKey 跳过）、产出后发布 kind=macro + 回灌委托（信息包/帧板块）。</p>
 */
class MacroEventServiceTest {

    private MacroProperties props;
    private ActorWorldMacroEventRepository eventRepository;
    private MacroAiService macroAiService;
    private MacroFeedbackService macroFeedbackService;
    private MacroStateService macroStateService;
    private WorldEventBus worldEventBus;
    private MacroEventService service;

    @BeforeEach
    void setUp() {
        props = new MacroProperties();
        eventRepository = Mockito.mock(ActorWorldMacroEventRepository.class);
        macroAiService = Mockito.mock(MacroAiService.class);
        macroFeedbackService = Mockito.mock(MacroFeedbackService.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        service = new MacroEventService(props, eventRepository, macroAiService, macroFeedbackService,
                macroStateService, worldEventBus, currentUserProvider);
        when(eventRepository.findByProjectIdAndSourceKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(eventRepository.save(any(ActorWorldMacroEvent.class))).thenAnswer(inv -> {
            ActorWorldMacroEvent e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(99L);
            }
            return e;
        });
        when(macroAiService.narrateDetailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any())).thenReturn(null);
        when(worldEventBus.publish(anyLong(), anyLong(), any(EventSpec.class)))
                .thenReturn(new com.holzyn.actor.domain.world.entity.ActorEvent());
    }

    /**
     * 严重度分级：price_surge/plague=4；harvest_good=2；幅度&gt;0.5 再 +1。
     */
    @Test
    void severityGrading() {
        assertEquals(4, MacroEventService.severityOf("price_surge", 0.25));
        assertEquals(4, MacroEventService.severityOf("plague", 0.05));
        assertEquals(2, MacroEventService.severityOf("harvest_good", 0.1));
        assertEquals(5, MacroEventService.severityOf("price_surge", 0.6));
        assertEquals(1, MacroEventService.severityOf("harvest_good", 0.05));
    }

    /**
     * 阈值判定：粮价波动&gt;20% → AI 叙事；未超 → 规则模板。
     */
    @Test
    void aiNarrativeThreshold() {
        assertTrue(service.needsAiNarrative("price_surge", 0.25));
        assertFalse(service.needsAiNarrative("price_surge", 0.1));
        assertTrue(service.needsAiNarrative("power_shift", 0.35));
        assertTrue(service.needsAiNarrative("bank_run", null)); // 固定重大事件
        assertFalse(service.needsAiNarrative("harvest_good", 0.1));
    }

    /**
     * 规则模板文本（无 AI）：price_surge 带幅度、plague 带具名 NPC。
     */
    @Test
    void ruleTemplateText() {
        String t = service.ruleTemplate(100L, "price_surge", "grain_price", 100.0, 130.0, 0.3, null, 5L);
        assertTrue(t.contains("第6日"));
        assertTrue(t.contains("30%"));
        String p = service.ruleTemplate(100L, "plague", null, null, null, null, "铁匠", 5L);
        assertTrue(p.contains("铁匠"));
    }

    /**
     * 产出并发布：规则叙事落库（source=rule）+ 发布 kind=macro + 回灌委托。
     */
    @Test
    void emitRuleEventPublishesAndFeedsBack() {
        ActorWorldMacroEvent ev = service.emit(100L, 5L, 5L * 86400L, "economy", "harvest_good",
                "grain_production", null, 3.2, 0.18, null, 1);
        assertNotNull(ev);
        assertEquals("rule", ev.getSource());
        assertEquals(2, ev.getSeverity());
        assertTrue(ev.getNarrative().contains("风调雨顺"));
        verify(worldEventBus).publish(anyLong(), anyLong(), any(EventSpec.class));
        verify(macroFeedbackService).onMacroEvent(anyLong(), anyLong(), any(ActorWorldMacroEvent.class), any());
    }

    /**
     * 阈值触发 AI 叙事：AI 命中 → source=ai + 叙事替换规则草稿。
     */
    @Test
    void emitMajorEventUsesAiNarrative() {
        when(macroAiService.narrateDetailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any()))
                .thenReturn(new MacroAiService.Narrative("粮行告急", "本村集市的粮行前挤满了人，粮价飞涨。"));
        ActorWorldMacroEvent ev = service.emit(100L, 5L, 5L * 86400L, "economy", "price_surge",
                "grain_price", 100.0, 130.0, 0.3, null, 1);
        assertNotNull(ev);
        assertEquals("ai", ev.getSource());
        assertEquals("粮行告急", ev.getTitle());
        assertTrue(ev.getNarrative().contains("挤满了人"));
        assertEquals(4, ev.getSeverity());
    }

    /**
     * 幂等：同 sourceKey 已存在 → 跳过（不重复落库/发布）。
     */
    @Test
    void emitIdempotentBySourceKey() {
        when(eventRepository.findByProjectIdAndSourceKey(anyLong(), anyString()))
                .thenReturn(Optional.of(new ActorWorldMacroEvent()));
        ActorWorldMacroEvent ev = service.emit(100L, 5L, 5L * 86400L, "economy", "harvest_good",
                "grain_production", null, 3.2, 0.18, null, 1);
        assertNull(ev);
        verify(eventRepository, never()).save(any(ActorWorldMacroEvent.class));
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any(EventSpec.class));
    }
}
