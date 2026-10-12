package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.agent.thinking.DecisionService;
import com.holzyn.actor.domain.npc.physio.PhysioNeedService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BrainStrategy 六策略单元测试（P2-3 可插拔决策策略）。
 * <p>验证：危险强制（双类型）/空间选择/快选择/AI 深思/概率化/程序化兜底的
 * 委托与空语义（不命中 → Optional.empty → 链继续）、applies 类型/档位过滤、
 * 人群行动 Map 编解码、兜底 postProcess=false。</p>
 */
class BrainStrategyStrategiesTest {

    private ActorCharacter character(String type) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setType(type);
        ch.setCurrentActivity("在茶馆喝茶");
        return ch;
    }

    private DecisionContext ctx(NpcKind kind, String brainLevel) {
        return new DecisionContext(100L, character("special"), 1L, kind, brainLevel,
                "情境", 100000L, null, false);
    }

    private CrowdDecisionParse.CrowdAction crowdAction() {
        return new CrowdDecisionParse.CrowdAction("前往云来茶馆用餐", "eat", "云来茶馆", "测试理由");
    }

    // ==================== PhysioDangerStrategy（双类型） ====================

    /** SPECIAL：危险命中 → 返回 dangerDecision 产物（priority=100 恒先尝试）。 */
    @Test
    void physioDangerSpecialReturnsDangerDecision() {
        PhysioNeedService physio = mock(PhysioNeedService.class);
        Map<String, Object> danger = Map.of("action", "前往云来茶馆用餐", "actionType", "move_to");
        when(physio.dangerDecision(anyLong(), any())).thenReturn(danger);
        PhysioDangerStrategy s = new PhysioDangerStrategy();
        ReflectionTestUtils.setField(s, "physioNeedService", physio);

        assertTrue(s.applies(ctx(NpcKind.SPECIAL, "full")));
        Optional<Map<String, Object>> out = s.decide(ctx(NpcKind.SPECIAL, "full"));
        assertTrue(out.isPresent());
        assertEquals("前往云来茶馆用餐", out.get().get("action"));
        assertEquals("physio_danger", s.name());
        assertEquals(100, s.priority());
    }

    /** SPECIAL：无危险 → 空（链继续）。 */
    @Test
    void physioDangerSpecialEmptyWhenNoDanger() {
        PhysioNeedService physio = mock(PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(null);
        PhysioDangerStrategy s = new PhysioDangerStrategy();
        ReflectionTestUtils.setField(s, "physioNeedService", physio);

        assertTrue(s.decide(ctx(NpcKind.SPECIAL, "full")).isEmpty());
    }

    /** COMMON：危险命中 → 人群行动 Map（actionText/actionType/target/reason）。 */
    @Test
    void physioDangerCommonReturnsCrowdActionMap() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.dangerDrive(anyLong(), any(), any())).thenReturn(crowdAction());
        PhysioDangerStrategy s = new PhysioDangerStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        DecisionContext ctx = ctx(NpcKind.COMMON, null);
        Optional<Map<String, Object>> out = s.decide(ctx);
        assertTrue(out.isPresent());
        assertEquals("前往云来茶馆用餐", out.get().get("actionText"));
        assertEquals("eat", out.get().get("actionType"));
        assertEquals("云来茶馆", out.get().get("target"));
        assertEquals("测试理由", out.get().get("reason"));
    }

    /** COMMON：无危险 → 空（链继续到快选择/概率化）。 */
    @Test
    void physioDangerCommonEmptyWhenNoDanger() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.dangerDrive(anyLong(), any(), any())).thenReturn(null);
        PhysioDangerStrategy s = new PhysioDangerStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        assertTrue(s.decide(ctx(NpcKind.COMMON, null)).isEmpty());
    }

    /** 未装配（单测）→ 空，不抛异常。 */
    @Test
    void physioDangerUnwiredReturnsEmpty() {
        PhysioDangerStrategy s = new PhysioDangerStrategy();
        assertTrue(s.decide(ctx(NpcKind.SPECIAL, "full")).isEmpty());
        assertTrue(s.decide(ctx(NpcKind.COMMON, null)).isEmpty());
    }

    // ==================== SpaceChoiceStrategy（SPECIAL 快通道） ====================

    /** SPECIAL：空间候选命中 → 返回 trySpaceDecision 产物。 */
    @Test
    void spaceChoiceDelegatesToTrySpaceDecision() {
        DecisionService decisionService = mock(DecisionService.class);
        Map<String, Object> decision = Map.of("action", "去茶馆坐坐", "actionType", "start_action");
        when(decisionService.trySpaceDecision(any(), anyString())).thenReturn(decision);
        SpaceChoiceStrategy s = new SpaceChoiceStrategy();
        ReflectionTestUtils.setField(s, "decisionService", decisionService);

        DecisionContext ctx = ctx(NpcKind.SPECIAL, "full");
        assertTrue(s.applies(ctx));
        Optional<Map<String, Object>> out = s.decide(ctx);
        assertTrue(out.isPresent());
        assertEquals("去茶馆坐坐", out.get().get("action"));
    }

    /** 空间未命中（判定失败/未启用）→ 空（链继续到 AI 深思）。 */
    @Test
    void spaceChoiceEmptyWhenMiss() {
        DecisionService decisionService = mock(DecisionService.class);
        when(decisionService.trySpaceDecision(any(), anyString())).thenReturn(null);
        SpaceChoiceStrategy s = new SpaceChoiceStrategy();
        ReflectionTestUtils.setField(s, "decisionService", decisionService);

        assertTrue(s.decide(ctx(NpcKind.SPECIAL, "full")).isEmpty());
    }

    /** 仅 SPECIAL；programmatic 档不适用（0 AI 语义）。 */
    @Test
    void spaceChoiceAppliesOnlySpecialNonProgrammatic() {
        SpaceChoiceStrategy s = new SpaceChoiceStrategy();
        assertFalse(s.applies(ctx(NpcKind.COMMON, null)), "普通 NPC 不经空间快通道");
        assertFalse(s.applies(ctx(NpcKind.SPECIAL, "programmatic")), "programmatic 档 0 AI");
        assertTrue(s.applies(ctx(NpcKind.SPECIAL, "full")));
        assertEquals(80, s.priority());
    }

    // ==================== CrowdChoiceStrategy（COMMON 快选择） ====================

    /** COMMON：快选择命中 → 人群行动 Map。 */
    @Test
    void crowdChoiceDelegatesToCrowdChoiceDrive() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.crowdChoiceDrive(anyLong(), any(), any(), any())).thenReturn(crowdAction());
        CrowdChoiceStrategy s = new CrowdChoiceStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        DecisionContext ctx = ctx(NpcKind.COMMON, null);
        assertTrue(s.applies(ctx));
        Optional<Map<String, Object>> out = s.decide(ctx);
        assertTrue(out.isPresent());
        assertEquals("前往云来茶馆用餐", out.get().get("actionText"));
        assertEquals("eat", out.get().get("actionType"));
    }

    /** 快选择全失败 → 空（链继续到概率化）。 */
    @Test
    void crowdChoiceEmptyWhenMiss() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.crowdChoiceDrive(anyLong(), any(), any(), any())).thenReturn(null);
        CrowdChoiceStrategy s = new CrowdChoiceStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        assertTrue(s.decide(ctx(NpcKind.COMMON, null)).isEmpty());
    }

    /** 仅 COMMON。 */
    @Test
    void crowdChoiceAppliesOnlyCommon() {
        CrowdChoiceStrategy s = new CrowdChoiceStrategy();
        assertFalse(s.applies(ctx(NpcKind.SPECIAL, "full")));
        assertTrue(s.applies(ctx(NpcKind.COMMON, null)));
    }

    // ==================== DeliberateStrategy（SPECIAL AI 深思） ====================

    /** SPECIAL：深思命中 → 返回 deliberateDecision 产物。 */
    @Test
    void deliberateDelegatesToDeliberateDecision() {
        DecisionService decisionService = mock(DecisionService.class);
        Map<String, Object> decision = Map.of("action", "前往城东市场采购药材", "actionType", "move_to");
        when(decisionService.deliberateDecision(any(), anyLong(), anyString())).thenReturn(decision);
        DeliberateStrategy s = new DeliberateStrategy();
        ReflectionTestUtils.setField(s, "decisionService", decisionService);

        DecisionContext ctx = ctx(NpcKind.SPECIAL, "full");
        assertTrue(s.applies(ctx));
        Optional<Map<String, Object>> out = s.decide(ctx);
        assertTrue(out.isPresent());
        assertEquals("前往城东市场采购药材", out.get().get("action"));
        assertEquals(60, s.priority());
    }

    /** 全部重试失败 → null → 空（链继续到程序化兜底）。 */
    @Test
    void deliberateEmptyOnAiFailure() {
        DecisionService decisionService = mock(DecisionService.class);
        when(decisionService.deliberateDecision(any(), anyLong(), anyString())).thenReturn(null);
        DeliberateStrategy s = new DeliberateStrategy();
        ReflectionTestUtils.setField(s, "decisionService", decisionService);

        assertTrue(s.decide(ctx(NpcKind.SPECIAL, "full")).isEmpty());
    }

    /** 仅 SPECIAL 且非 programmatic。 */
    @Test
    void deliberateAppliesOnlySpecialNonProgrammatic() {
        DeliberateStrategy s = new DeliberateStrategy();
        assertFalse(s.applies(ctx(NpcKind.COMMON, null)));
        assertFalse(s.applies(ctx(NpcKind.SPECIAL, "programmatic")), "programmatic 档 0 AI");
        assertTrue(s.applies(ctx(NpcKind.SPECIAL, "full")));
    }

    // ==================== ProbabilisticStrategy（COMMON 概率化） ====================

    /** COMMON + 概率化驱动启用 → 委托 probabilisticDrive。 */
    @Test
    void probabilisticDelegatesToProbabilisticDrive() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.isProbabilisticDriveEnabled()).thenReturn(true);
        when(npcStateService.probabilisticDrive(anyLong(), any(), any(), any())).thenReturn(crowdAction());
        ProbabilisticStrategy s = new ProbabilisticStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        DecisionContext ctx = ctx(NpcKind.COMMON, null);
        assertTrue(s.applies(ctx));
        Optional<Map<String, Object>> out = s.decide(ctx);
        assertTrue(out.isPresent());
        assertEquals("前往云来茶馆用餐", out.get().get("actionText"));
        assertEquals("eat", out.get().get("actionType"));
    }

    /** 概率化驱动未启用 → 不适用（链跳过，对齐调用方门控）。 */
    @Test
    void probabilisticNotAppliesWhenDriveDisabled() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.isProbabilisticDriveEnabled()).thenReturn(false);
        ProbabilisticStrategy s = new ProbabilisticStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        assertFalse(s.applies(ctx(NpcKind.COMMON, null)));
    }

    /** 无候选 → 空（链空 → 调用方作息状态机兜底）。 */
    @Test
    void probabilisticEmptyWhenNoCandidate() {
        NpcStateService npcStateService = mock(NpcStateService.class);
        when(npcStateService.isProbabilisticDriveEnabled()).thenReturn(true);
        when(npcStateService.probabilisticDrive(anyLong(), any(), any(), any())).thenReturn(null);
        ProbabilisticStrategy s = new ProbabilisticStrategy();
        ReflectionTestUtils.setField(s, "npcStateService", npcStateService);

        assertTrue(s.decide(ctx(NpcKind.COMMON, null)).isEmpty());
    }

    /** 仅 COMMON。 */
    @Test
    void probabilisticAppliesOnlyCommon() {
        ProbabilisticStrategy s = new ProbabilisticStrategy();
        assertFalse(s.applies(ctx(NpcKind.SPECIAL, "full")));
    }

    // ==================== ProgrammaticFallbackStrategy（SPECIAL 兜底） ====================

    /** 兜底恒命中：continue 当前行为（0 AI），不走调用方后处理。 */
    @Test
    void programmaticFallbackReturnsContinue() {
        ProgrammaticFallbackStrategy s = new ProgrammaticFallbackStrategy();

        assertTrue(s.applies(ctx(NpcKind.SPECIAL, "full")));
        Optional<Map<String, Object>> out = s.decide(ctx(NpcKind.SPECIAL, "full"));
        assertTrue(out.isPresent());
        assertEquals("continue", out.get().get("actionType"), "兜底=continue 当前行为");
        assertTrue(String.valueOf(out.get().get("action")).contains("继续"), "动作含「继续」");
        assertFalse(s.postProcess(), "程序化兜底不走护栏/前瞻/想法后处理");
        assertEquals(0, s.priority(), "链尾恒在");
    }

    /** 仅 SPECIAL（普通 NPC 兜底在调用方作息状态机）。 */
    @Test
    void programmaticFallbackAppliesOnlySpecial() {
        ProgrammaticFallbackStrategy s = new ProgrammaticFallbackStrategy();
        assertFalse(s.applies(ctx(NpcKind.COMMON, null)));
    }

    // ==================== CrowdActionCodec 编解码 ====================

    /** 人群行动 ↔ 策略链 Map 双向无损。 */
    @Test
    void crowdActionCodecRoundTrips() {
        CrowdDecisionParse.CrowdAction a = crowdAction();
        Map<String, Object> m = CrowdActionCodec.toMap(a);
        assertEquals("前往云来茶馆用餐", m.get("actionText"));
        CrowdDecisionParse.CrowdAction back = CrowdActionCodec.fromMap(m);
        assertNotNull(back);
        assertEquals("前往云来茶馆用餐", back.action());
        assertEquals("eat", back.actionType());
        assertEquals("云来茶馆", back.target());
        assertEquals("测试理由", back.reason());
    }

    /** 空/缺 actionText → null（调用方按链空处理）。 */
    @Test
    void crowdActionCodecHandlesEmpty() {
        assertNotNull(CrowdActionCodec.toMap(null), "null → 空 Map 不抛");
        assertEquals(0, CrowdActionCodec.toMap(null).size());
        assertTrue(CrowdActionCodec.fromMap(null) == null);
        assertTrue(CrowdActionCodec.fromMap(Map.of()) == null);
        assertTrue(CrowdActionCodec.fromMap(Map.of("actionText", "  ")) == null);
    }

    /** NpcKind 判定与角色类型同口径。 */
    @Test
    void npcKindOfMatchesCharacterType() {
        assertEquals(NpcKind.SPECIAL, NpcKind.of(character("special")));
        assertEquals(NpcKind.COMMON, NpcKind.of(character("common")));
        assertEquals(NpcKind.SPECIAL, NpcKind.of(null), "null 角色按 SPECIAL 宽松");
    }
}
