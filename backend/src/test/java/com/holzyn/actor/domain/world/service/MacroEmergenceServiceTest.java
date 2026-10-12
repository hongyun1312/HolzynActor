package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.EmergenceProperties;
import com.holzyn.actor.domain.world.entity.ActorMacroEmergenceEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorMacroEmergenceEventRepository;
import com.holzyn.actor.domain.world.repository.ActorMacroEmergenceMemberRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 二阶涌现检测服务单元测试（宏观社会演化系统 M6.6，domain/world/service，同包）。
 * <p>职责：验证行为类分组、达标判定（minMembers/比例）、冷却、涌现落库+成员明细、
 * 叠加入账（source=emergence）、排除自引用（防自我强化）、规则叙事、AI 回退、读接口。</p>
 */
class MacroEmergenceServiceTest {

    private EmergenceProperties props;
    private ActorWorldMacroDeltaRepository deltaRepository;
    private ActorMacroEmergenceEventRepository eventRepository;
    private ActorMacroEmergenceMemberRepository memberRepository;
    private ActorCharacterRepository npcRepository;
    private MacroStateService macroStateService;
    private MacroFeedbackService macroFeedbackService;
    private MacroEmergenceService service;

    @BeforeEach
    void setUp() {
        props = new EmergenceProperties();
        deltaRepository = Mockito.mock(ActorWorldMacroDeltaRepository.class);
        eventRepository = Mockito.mock(ActorMacroEmergenceEventRepository.class);
        memberRepository = Mockito.mock(ActorMacroEmergenceMemberRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        macroFeedbackService = Mockito.mock(MacroFeedbackService.class);
        service = new MacroEmergenceService(props, deltaRepository, eventRepository, memberRepository,
                npcRepository, macroStateService, macroFeedbackService, new ObjectMapper());
        when(eventRepository.save(any(ActorMacroEmergenceEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.findTopByProjectIdAndBehaviorClassOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        // G1a：普通型行数口径显式写回 type=common（原 countByProjectId 数的是 actor_profile_ordinary 行数）
        when(npcRepository.countByProjectIdAndType(anyLong(), eq("common"))).thenReturn(0L);
        when(macroStateService.templateOf(anyLong())).thenReturn(
                com.holzyn.actor.domain.world.config.MacroTemplate.builtins()
                        .get(com.holzyn.actor.domain.world.config.MacroTemplate.DEFAULT_ID));
    }

    /** 流水行构造（行为类由 classify 判定）。 */
    private ActorWorldMacroDelta delta(String ref, String actionType, String text, long day) {
        ActorWorldMacroDelta d = new ActorWorldMacroDelta();
        d.setProjectId(100L);
        d.setGameDay(day);
        d.setNpcRef(ref);
        d.setActionType(actionType);
        d.setActionText(text);
        d.setTargetMacro("economy");
        d.setIndicator("market_activity");
        d.setDelta(0.5);
        d.setSource("rule");
        return d;
    }

    /** 3 名不同角色囤粮 → 涌现事件（成员=3、叠加入账 market_activity 负、回灌信息包）。 */
    @Test
    void scanDetectsHoardingEmergence() {
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("special:1", "custom", "把粮囤起来", 9L),
                        delta("special:2", "custom", "抢购存粮", 9L),
                        delta("special:3", "trade", "囤积货物", 9L)));
        service.scanDaily(100L, 10L, 10L * 86400L);
        ArgumentCaptor<ActorMacroEmergenceEvent> cap = ArgumentCaptor.forClass(ActorMacroEmergenceEvent.class);
        verify(eventRepository).save(cap.capture());
        ActorMacroEmergenceEvent ev = cap.getValue();
        assertEquals("hoarding", ev.getEmergenceType());
        assertEquals(3, ev.getMemberCount());
        assertEquals(3.0 * 2.0, ev.getWeight(), 0.001, "成员×囤积权重 2.0");
        assertTrue(ev.getNarrative().contains("囤积"), "规则叙事含行为类显示名");
        assertEquals(10L + props.getCooldownDays(), ev.getCooledUntilDay());
        // 叠加入账：economy/market_activity 负值 + source=emergence
        ArgumentCaptor<ActorWorldMacroDelta> dc = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(dc.capture());
        ActorWorldMacroDelta superposed = dc.getValue();
        assertEquals(MacroEmergenceService.SOURCE_EMERGENCE, superposed.getSource());
        assertEquals("market_activity", superposed.getIndicator());
        assertTrue(superposed.getDelta() < 0, "囤积抽干市面→活跃度下降");
        // 成员明细（每人一行）+ 回灌
        verify(memberRepository, org.mockito.Mockito.times(3))
                .save(any(com.holzyn.actor.domain.world.entity.ActorMacroEmergenceMember.class));
        verify(macroFeedbackService).onMacroEvent(anyLong(), anyLong(), any(), any());
    }

    /** 不足 minMembers（2 人）→ 不检出不落库。 */
    @Test
    void belowMinMembersSkips() {
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("special:1", "custom", "把粮囤起来", 9L),
                        delta("special:2", "custom", "抢购存粮", 9L)));
        service.scanDaily(100L, 10L, 10L * 86400L);
        verify(eventRepository, never()).save(any(ActorMacroEmergenceEvent.class));
    }

    /** 比例模式：具名角色总数 10、比例 0.5 → 需 5 人，3 人未达标跳过。 */
    @Test
    void ratioModeBlocksWhenBelowProportion() {
        when(npcRepository.countByProjectIdAndType(anyLong(), eq("common"))).thenReturn(10L);
        props.setRatio(0.5);
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("special:1", "custom", "把粮囤起来", 9L),
                        delta("special:2", "custom", "抢购存粮", 9L),
                        delta("special:3", "trade", "囤积货物", 9L)));
        service.scanDaily(100L, 10L, 10L * 86400L);
        verify(eventRepository, never()).save(any(ActorMacroEmergenceEvent.class));
    }

    /** 冷却：同行为类最近事件冷却至 ≥ 当前日 → 跳过（防重复误报）。 */
    @Test
    void cooldownSkipsRepeatedType() {
        ActorMacroEmergenceEvent last = new ActorMacroEmergenceEvent();
        last.setBehaviorClass("hoarding");
        last.setCooledUntilDay(12L);
        when(eventRepository.findTopByProjectIdAndBehaviorClassOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.of(last));
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("special:1", "custom", "把粮囤起来", 9L),
                        delta("special:2", "custom", "抢购存粮", 9L),
                        delta("special:3", "trade", "囤积货物", 9L)));
        service.scanDaily(100L, 10L, 10L * 86400L);
        verify(eventRepository, never()).save(any(ActorMacroEmergenceEvent.class));
    }

    /** 排除自引用：窗口内全为 source=emergence 叠加行 → 不检出不自我强化。 */
    @Test
    void emergenceSourceRowsExcludedFromScan() {
        ActorWorldMacroDelta self = delta("system:emergence", "hoarding", "囤积成风", 9L);
        self.setSource(MacroEmergenceService.SOURCE_EMERGENCE);
        self.setNpcRef("system:emergence");
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(self));
        service.scanDaily(100L, 10L, 10L * 86400L);
        verify(eventRepository, never()).save(any(ActorMacroEmergenceEvent.class));
    }

    /** 大涌现（≥6 人）→ severity 4（重大集体现象）。 */
    @Test
    void largeEmergenceGetsSeverity4() {
        when(deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(
                        delta("special:1", "custom", "把粮囤起来", 9L),
                        delta("special:2", "custom", "抢购存粮", 9L),
                        delta("special:3", "trade", "囤积货物", 9L),
                        delta("special:4", "custom", "存粮备荒", 9L),
                        delta("special:5", "custom", "买粮囤着", 9L),
                        delta("special:6", "trade", "囤货", 9L)));
        service.scanDaily(100L, 10L, 10L * 86400L);
        ArgumentCaptor<ActorMacroEmergenceEvent> cap = ArgumentCaptor.forClass(ActorMacroEmergenceEvent.class);
        verify(eventRepository).save(cap.capture());
        assertEquals(4, cap.getValue().getSeverity());
    }

    /** 规则叙事：含行为类显示名 + 参与人数 + 模板核心资源语义（通用，无硬编码物资名）。 */
    @Test
    void ruleNarrativeUsesTemplateSemantics() {
        String n = service.ruleNarrative(100L, 9L, "hoarding", 4, 5);
        assertTrue(n.contains("囤积"), "含行为类显示名");
        assertTrue(n.contains("4 人"), "含参与人数");
        assertTrue(n.contains("粮"), "含模板核心资源（默认仙侠=粮）");
        assertFalse(n.contains("青禾村"), "不含测试世界名");
    }

    /** 行为类分组：可归一入组、不可归一跳过、按类分组。 */
    @Test
    void groupByBehaviorGroupsAndSkips() {
        Map<String, List<ActorWorldMacroDelta>> g = MacroEmergenceService.groupByBehavior(List.of(
                delta("special:1", "custom", "把粮囤起来", 9L),
                delta("special:2", "custom", "抢购存粮", 9L),
                delta("special:3", "custom", "在厨房做饭", 9L)));
        assertEquals(1, g.size(), "只有囤积入组（做饭不参与）");
        assertEquals(2, g.get("hoarding").size());
        assertNull(MacroEmergenceService.groupByBehavior(null).get("hoarding"));
        assertNotNull(g.get("hoarding"));
    }
}
