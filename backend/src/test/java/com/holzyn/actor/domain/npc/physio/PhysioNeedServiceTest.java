package com.holzyn.actor.domain.npc.physio;

import com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 生理需求→行动决策耦合服务单元测试（domain/npc/physio）。
 * <p>职责：验证——危险需求判定（四需/边界/最紧急优先/开关关）、低值候选组装
 * （四需/三餐窗口门控/地点匹配/权重缺口）、三餐窗口判定（跨零点窗口/未知时刻宽松）、
 * 轻行动抑制判定、危险区程序化决策 Map（新键+旧键双写/priority=5）、打断刺激文本。</p>
 * <p>设计依据：次对接文档_20260930_04 §6.1（生理需求→行动决策耦合，两轮共证头号真实性优化）。</p>
 */
class PhysioNeedServiceTest {

    private NpcStateProperties props;
    private PhysioNeedService service;

    @BeforeEach
    void setUp() {
        props = new NpcStateProperties();
        service = new PhysioNeedService();
        ReflectionTestUtils.setField(service, "stateProperties", props);
    }

    private ActorNpcState state() {
        ActorNpcState st = new ActorNpcState();
        st.setNpcRef("npc:3");
        st.setHunger(70);
        st.setThirst(70);
        st.setBladder(50);
        st.setHygiene(90);
        return st;
    }

    // ==================== 危险需求判定 ====================

    @Test
    void dangerNeedOf_noDangerReturnsNull() {
        ActorNpcState st = state();
        st.setHunger(30); st.setThirst(30); st.setBladder(25); st.setHygiene(35);
        assertNull(PhysioNeedService.dangerNeedOf(st, props.getPhysio()), "全部高于危险阈值 → 无危险");
    }

    @Test
    void dangerNeedOf_hungerDanger() {
        ActorNpcState st = state();
        st.setHunger(15); // < danger-hunger 20
        PhysioNeedService.DangerNeed d = PhysioNeedService.dangerNeedOf(st, props.getPhysio());
        assertNotNull(d);
        assertEquals("饥饿", d.need());
        assertEquals("eat", d.actionType());
        assertEquals(15, d.value());
    }

    @Test
    void dangerNeedOf_boundaryNotDanger() {
        ActorNpcState st = state();
        st.setHunger(20); // == danger-hunger 20（不低于）
        assertNull(PhysioNeedService.dangerNeedOf(st, props.getPhysio()), "等于阈值不算危险");
    }

    @Test
    void dangerNeedOf_mostUrgentWins() {
        ActorNpcState st = state();
        st.setHunger(18); st.setThirst(10); st.setBladder(5); // 膀胱最危急
        PhysioNeedService.DangerNeed d = PhysioNeedService.dangerNeedOf(st, props.getPhysio());
        assertNotNull(d);
        assertEquals("膀胱", d.need(), "多需求同时危险 → 值最低者优先");
        assertEquals("toilet", d.actionType());
    }

    @Test
    void dangerNeedOf_disabledReturnsNull() {
        props.getPhysio().setEnabled(false);
        ActorNpcState st = state();
        st.setHunger(5);
        assertNull(PhysioNeedService.dangerNeedOf(st, props.getPhysio()), "总开关关 → 不耦合");
    }

    // ==================== 低值候选组装 ====================

    @Test
    void lowCandidates_noLowReturnsEmpty() {
        assertTrue(PhysioNeedService.lowCandidates(state(), props.getPhysio(), 12,
                List.of("云来茶馆"), new Random(1)).isEmpty(), "全部高于低值阈值 → 无候选");
    }

    @Test
    void lowCandidates_hungerInMealWindowBuildsEatCandidate() {
        ActorNpcState st = state();
        st.setHunger(30); // < low-hunger 45
        List<SpaceDecisionCandidates.SpaceCandidate> c = PhysioNeedService.lowCandidates(
                st, props.getPhysio(), 12, List.of("云来茶馆", "青禾集市"), new Random(1));
        assertEquals(1, c.size());
        assertEquals("eat", PhysioNeedService.actionTypeOf("饥饿"), "候选行动类型与恢复关键词对齐");
        assertTrue(c.get(0).action().contains("用餐") || c.get(0).action().contains("充饥"));
        assertEquals("云来茶馆", c.get(0).target(), "地点关键词匹配（茶馆→eat）");
        assertTrue(c.get(0).questionText().contains("倾向"), "权重作「（倾向 x%）」情境信息");
        assertNotNull(c.get(0).weight());
        assertTrue(c.get(0).weight() > 0 && c.get(0).weight() <= 1, "权重=需求缺口比例");
    }

    @Test
    void lowCandidates_hungerOutsideMealWindowSkipped() {
        ActorNpcState st = state();
        st.setHunger(30); // 低值但处于非餐点（22 点）
        List<SpaceDecisionCandidates.SpaceCandidate> c = PhysioNeedService.lowCandidates(
                st, props.getPhysio(), 22, List.of("云来茶馆"), new Random(1));
        assertTrue(c.isEmpty(), "非餐点不注入进食候选（真人不会半夜正经吃饭）");
    }

    @Test
    void lowCandidates_thirstBladderHygieneNotWindowGated() {
        ActorNpcState st = state();
        st.setThirst(20); st.setBladder(15); st.setHygiene(30);
        List<SpaceDecisionCandidates.SpaceCandidate> c = PhysioNeedService.lowCandidates(
                st, props.getPhysio(), 22, List.of("镇口水井", "村口茅房", "河边"), new Random(1));
        // 非餐点但饮水/如厕/清洗不受窗口限制
        assertEquals(3, c.size(), "饮水/如厕/清洗不受三餐窗口限制");
        assertTrue(c.stream().anyMatch(x -> x.action().contains("饮水")));
        assertTrue(c.stream().anyMatch(x -> x.action().contains("如厕")));
        assertTrue(c.stream().anyMatch(x -> x.action().contains("洗漱")));
    }

    @Test
    void lowCandidates_noMatchingLocationNoTarget() {
        ActorNpcState st = state();
        st.setHunger(30);
        List<SpaceDecisionCandidates.SpaceCandidate> c = PhysioNeedService.lowCandidates(
                st, props.getPhysio(), 12, List.of("村口石桥"), new Random(1));
        assertEquals(1, c.size());
        assertNull(c.get(0).target(), "无匹配地点 → 无 target（候选仍注入，行动文本回退觅食充饥）");
        assertTrue(c.get(0).action().contains("充饥"));
    }

    @Test
    void lowCandidates_hourUnknownLoosePass() {
        ActorNpcState st = state();
        st.setHunger(30);
        List<SpaceDecisionCandidates.SpaceCandidate> c = PhysioNeedService.lowCandidates(
                st, props.getPhysio(), null, List.of("云来茶馆"), new Random(1));
        assertEquals(1, c.size(), "时刻未知 → 进食不受窗口门控（宽松放行零阻断）");
    }

    // ==================== 三餐窗口 ====================

    @Test
    void isMealHour_windows() {
        assertTrue(PhysioNeedService.isMealHour(7, props.getPhysio()), "早 6~9");
        assertFalse(PhysioNeedService.isMealHour(10, props.getPhysio()), "10 点非餐点");
        assertTrue(PhysioNeedService.isMealHour(12, props.getPhysio()), "午 11~14");
        assertFalse(PhysioNeedService.isMealHour(15, props.getPhysio()));
        assertTrue(PhysioNeedService.isMealHour(18, props.getPhysio()), "晚 17~20");
        assertFalse(PhysioNeedService.isMealHour(21, props.getPhysio()));
        assertTrue(PhysioNeedService.isMealHour(null, props.getPhysio()), "时刻未知宽松放行");
    }

    @Test
    void isMealHour_crossMidnightWindow() {
        props.getPhysio().setMealWindows(List.of(new NpcStateProperties.Physio.MealWindow(22, 2)));
        assertTrue(PhysioNeedService.isMealHour(23, props.getPhysio()), "跨零点窗口 22~2 内");
        assertTrue(PhysioNeedService.isMealHour(1, props.getPhysio()), "跨零点窗口次日 1 点");
        assertFalse(PhysioNeedService.isMealHour(12, props.getPhysio()));
    }

    // ==================== 轻行动抑制判定 ====================

    @Test
    void hasLowNeed_flags() {
        assertFalse(PhysioNeedService.hasLowNeed(state(), props.getPhysio(), 12));
        ActorNpcState st = state();
        st.setHunger(30);
        assertTrue(PhysioNeedService.hasLowNeed(st, props.getPhysio(), 12), "餐点内低值饥饿 → 抑制");
        assertFalse(PhysioNeedService.hasLowNeed(st, props.getPhysio(), 22), "非餐点低值饥饿不抑制");
        st.setThirst(20);
        assertTrue(PhysioNeedService.hasLowNeed(st, props.getPhysio(), 22), "口渴不受窗口限制 → 抑制");
    }

    // ==================== 实例方法（危险决策/候选/抑制/刺激文本） ====================

    @Test
    void dangerDecision_forcesPhysioAction() {
        ActorNpcState st = state();
        st.setHunger(10); // 危险区
        NpcStateService npcStateService = Mockito.mock(NpcStateService.class);
        when(npcStateService.stateFor(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(st);
        when(npcStateService.locationNames(anyLong())).thenReturn(List.of("云来茶馆"));
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);

        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setCurrentActivity("在茶馆喝茶");
        Map<String, Object> d = service.dangerDecision(100L, ch);

        assertNotNull(d);
        assertEquals("move_to", d.get("actionType"), "含目标地点 → move_to");
        assertEquals(5, d.get("priority"), "危险区 priority 抬到 5（健康告急优先）");
        assertEquals(5, d.get("urgency"));
        assertTrue(String.valueOf(d.get("action")).contains("用餐") || String.valueOf(d.get("action")).contains("充饥"));
        assertEquals("云来茶馆", d.get("target"));
    }

    @Test
    void dangerDecision_noDangerReturnsNull() {
        ActorNpcState st = state();
        NpcStateService npcStateService = Mockito.mock(NpcStateService.class);
        when(npcStateService.stateFor(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(st);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);

        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        assertNull(service.dangerDecision(100L, ch), "无危险 → null（调用方走原决策链）");
    }

    @Test
    void dangerStimulusTextOf_mapsNeeds() {
        assertEquals("饿得发慌了，必须马上找东西吃",
                PhysioNeedService.dangerStimulusTextOf(new PhysioNeedService.DangerNeed("饥饿", "eat", 10)));
        assertEquals("渴得嗓子冒烟，必须马上找水喝",
                PhysioNeedService.dangerStimulusTextOf(new PhysioNeedService.DangerNeed("口渴", "drink", 10)));
        assertNull(PhysioNeedService.dangerStimulusTextOf(null));
    }

    @Test
    void suppressLight_gatedBySwitchAndNeeds() {
        assertFalse(service.suppressLight(100L, null), "角色 null → 不抑制");
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ActorNpcState st = state();
        st.setHunger(30);
        NpcStateService npcStateService = Mockito.mock(NpcStateService.class);
        when(npcStateService.stateFor(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(st);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
        com.holzyn.actor.domain.world.service.WorldClockService wcs =
                Mockito.mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        when(wcs.currentEngineGameHour(anyLong())).thenReturn(12L);
        ReflectionTestUtils.setField(service, "worldClockService", wcs);

        assertTrue(service.suppressLight(100L, ch), "餐点内低值饥饿 → 抑制轻行动");
        props.getPhysio().setLightActionSuppressEnabled(false);
        assertFalse(service.suppressLight(100L, ch), "抑制开关关 → 不抑制");
    }

    @Test
    void actionTextOf_keywordAlignment() {
        assertEquals("前往云来茶馆用餐", PhysioNeedService.actionTextOf("饥饿", "云来茶馆"));
        assertEquals("觅食充饥", PhysioNeedService.actionTextOf("饥饿", null));
        assertEquals("前往镇口水井饮水", PhysioNeedService.actionTextOf("口渴", "镇口水井"));
        assertEquals("前往村口茅房如厕", PhysioNeedService.actionTextOf("膀胱", "村口茅房"));
        assertEquals("前往河边洗漱", PhysioNeedService.actionTextOf("清洁", "河边"));
        assertEquals("寻水洗漱", PhysioNeedService.actionTextOf("清洁", null));
    }
}
