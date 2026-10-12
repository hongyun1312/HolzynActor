package com.holzyn.actor.ai.nanojev.decision;

import com.holzyn.actor.domain.growth.mask.MaskBreachDecision;
import com.holzyn.actor.domain.growth.romance.RomanceDecisions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N3 判定类落地单测：G4 三插拔点 + G3 面具两点 + 仲裁 + 护栏的 NanoJev 判定类。
 *
 * <p>测试纪律（V1.3 第六层）：字段注入 + {@code ReflectionTestUtils.setField}；判定门全 mock
 * （脚本化响应，零真实 AI）；覆盖「采纳 → 结果」「未启用/升级/失败 → null 回退」两态。</p>
 *
 * <p>所属模块：ai/nanojev/decision（N 轨道 N3）</p>
 */
class NanoJevPluginsDecisionTest {

    private NanoJevDecisionGate gate;

    @BeforeEach
    void setUp() {
        gate = mock(NanoJevDecisionGate.class);
        when(gate.isEnabled(any())).thenReturn(true);
    }

    // ==================== G4 心动 ====================

    @Test
    @DisplayName("心动：采纳 → AttractionDecision；未启用/升级 → null 回退 Qwen")
    void romanceAttract() {
        NanoJevRomanceAttractionDecision d = new NanoJevRomanceAttractionDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.booleanDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.ROMANCE_ATTRACT), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(true, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.7, 0.7, 0.6, 50));
        RomanceDecisions.AttractionContext ctx = new RomanceDecisions.AttractionContext(
                1L, "npc:10", "孙二娘", "npc:11", "柴大山", "两人常一起干活", 70, 1000L);
        RomanceDecisions.AttractionDecision r = d.decide(ctx);
        assertNotNull(r);
        assertTrue(r.attracted());
        assertEquals("nanojev", r.source());
        // 升级 → null
        when(gate.booleanDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.ROMANCE_ATTRACT), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(false, false,
                        NanoJevDecisionGate.DISP_ESCALATED, 0.5, 0.5, 1.0, 50));
        assertNull(d.decide(ctx));
        // 未启用 → null
        when(gate.isEnabled(NanoJevDecisionScenes.ROMANCE_ATTRACT)).thenReturn(false);
        assertNull(d.decide(ctx));
    }

    // ==================== G4 求婚 ====================

    @Test
    @DisplayName("求婚：propose/wait 采纳映射；升级 → null 回退 Qwen")
    void proposal() {
        NanoJevProposalDecision d = new NanoJevProposalDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.PROPOSAL), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome("propose", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("propose", 0.7, "wait", 0.2, "give_up", 0.1),
                        0.7, 0.6, 50));
        RomanceDecisions.ProposalContext ctx = new RomanceDecisions.ProposalContext(
                1L, "npc:10", "孙二娘", List.of("npc:11"), "柴大山：好感 85", 40L, 1000L);
        RomanceDecisions.ProposalDecision r = d.decide(ctx);
        assertNotNull(r);
        assertTrue(r.propose());
        assertEquals("npc:11", r.targetRef());
        // wait → 不推进
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.PROPOSAL), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome("wait", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("propose", 0.2, "wait", 0.7, "give_up", 0.1),
                        0.7, 0.6, 50));
        RomanceDecisions.ProposalDecision r2 = d.decide(ctx);
        assertFalse(r2.propose());
        assertNull(r2.targetRef());
        // 升级 → null
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.PROPOSAL), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome(null, false,
                        NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 50));
        assertNull(d.decide(ctx));
    }

    // ==================== G4 生育 ====================

    @Test
    @DisplayName("生育：档位≥1 想要；档 0 不想要；升级 → null 回退 Qwen")
    void fertility() {
        NanoJevFertilityDecision d = new NanoJevFertilityDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.scoreDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.FERTILITY), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ScoreOutcome(2, 1.8, true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("0", 0.1, "1", 0.1, "2", 0.8), 0.8, 0.4, 50));
        RomanceDecisions.FertilityContext ctx = new RomanceDecisions.FertilityContext(
                1L, "npc:10", "孙二娘", "npc:11", "柴大山", 300L, 0, "三十出头", 45, 1000L);
        RomanceDecisions.FertilityDecision r = d.decide(ctx);
        assertNotNull(r);
        assertTrue(r.want());
        // 档 0 → 不想要
        when(gate.scoreDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.FERTILITY), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ScoreOutcome(0, 0.2, true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("0", 0.8, "1", 0.1, "2", 0.1), 0.8, 0.4, 50));
        assertFalse(d.decide(ctx).want());
        // 升级 → null
        when(gate.scoreDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.FERTILITY), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ScoreOutcome(-1, 0, false,
                        NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 50));
        assertNull(d.decide(ctx));
    }

    // ==================== G3 面具情境 ====================

    @Test
    @DisplayName("面具情境：采纳对象码；无对象/未启用 → null 回退程序化")
    void maskContext() {
        NanoJevMaskContextDecision d = new NanoJevMaskContextDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.MASK_CONTEXT), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome("before_authority", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("before_authority", 0.8), 0.8, 0.4, 50));
        assertEquals("before_authority", d.decide(1L, "npc:10", "孙二娘", "村长沈砚秋，身份村长"));
        // 无对方描述 → null（不调 NanoJev）
        assertNull(d.decide(1L, "npc:10", "孙二娘", ""));
        // 升级 → null
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.MASK_CONTEXT), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome(null, false,
                        NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 50));
        assertNull(d.decide(1L, "npc:10", "孙二娘", "村长沈砚秋"));
    }

    // ==================== G3 面具破绽 ====================

    @Test
    @DisplayName("面具破绽：采纳破绽/无事；升级 → null 回退程序化")
    void maskBreach() {
        NanoJevMaskBreachDecision d = new NanoJevMaskBreachDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.booleanDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.MASK_BREACH), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(true, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.8, 0.8, 0.4, 50));
        MaskBreachDecision.BreachResult r = d.detect(1L, "npc:10", "孙二娘", "提到次子", "次子");
        assertNotNull(r);
        assertTrue(r.triggered());
        assertEquals(NanoJevMaskBreachDecision.DEFAULT_DROP, r.suppressionDelta(), 0.001);
        // 无事
        when(gate.booleanDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.MASK_BREACH), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(false, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.2, 0.8, 0.4, 50));
        assertFalse(d.detect(1L, "npc:10", "孙二娘", "平常话", null).triggered());
        // 升级 → null
        when(gate.booleanDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.MASK_BREACH), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(false, false,
                        NanoJevDecisionGate.DISP_ESCALATED, 0.5, 0.5, 1.0, 50));
        assertNull(d.detect(1L, "npc:10", "孙二娘", "平常话", null));
    }

    // ==================== 打断仲裁 ====================

    @Test
    @DisplayName("仲裁：采纳四态之一；升级 → null 回退 Qwen/确定性")
    void negotiate() {
        NanoJevNegotiateDecision d = new NanoJevNegotiateDecision();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.NEGOTIATE), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome("wait", true,
                        NanoJevDecisionGate.DISP_ADOPTED,
                        Map.of("proceed", 0.2, "wait", 0.6, "yield", 0.1, "redirect", 0.1), 0.6, 0.8, 50));
        assertEquals("wait", d.decide(1L, 10L, "我（孙二娘）想去祠堂…"));
        // 升级 → null
        when(gate.choiceDecision(eq(1L), eq(10L), eq(NanoJevDecisionScenes.NEGOTIATE), any(), any()))
                .thenReturn(new NanoJevDecisionGate.ChoiceOutcome(null, false,
                        NanoJevDecisionGate.DISP_ESCALATED, null, null, null, 50));
        assertNull(d.decide(1L, 10L, "我（孙二娘）想去祠堂…"));
        // 空情境 → null
        assertNull(d.decide(1L, 10L, ""));
    }

    // ==================== 护栏语义 ====================

    @Test
    @DisplayName("护栏语义：越界/合规/回退三态")
    void guardSemantic() {
        NanoJevGuardSemanticCheck d = new NanoJevGuardSemanticCheck();
        ReflectionTestUtils.setField(d, "gate", gate);
        when(gate.booleanDecision(eq(1L), eq(null), eq(NanoJevDecisionScenes.GUARD_SEMANTIC), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(true, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.9, 0.9, 0.3, 50));
        assertEquals(Boolean.TRUE, d.check(1L, "action_gen", "好的，我会做的。"));
        when(gate.booleanDecision(eq(1L), eq(null), eq(NanoJevDecisionScenes.GUARD_SEMANTIC), any(), any()))
                .thenReturn(new NanoJevDecisionGate.BooleanOutcome(false, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.1, 0.9, 0.3, 50));
        assertEquals(Boolean.FALSE, d.check(1L, "action_gen", "前往集市卖菜。"));
        // 未启用 → null（不拦截）
        when(gate.isEnabled(NanoJevDecisionScenes.GUARD_SEMANTIC)).thenReturn(false);
        assertNull(d.check(1L, "action_gen", "前往集市卖菜。"));
    }
}
