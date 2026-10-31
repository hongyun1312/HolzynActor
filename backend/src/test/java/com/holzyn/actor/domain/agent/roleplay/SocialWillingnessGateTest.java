package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.ai.decision.gate.DecisionGate;
import com.holzyn.actor.ai.decision.gate.DecisionGateProperties;
import com.holzyn.actor.ai.decision.gate.DecisionScenes;
import com.holzyn.actor.ai.decision.gate.DecisionEscalationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N3 判定类落地单测：社交意愿门控（SocialWillingnessGate）。
 *
 * <p>测试纪律（V1.3 第六层）：字段注入 + {@code ReflectionTestUtils.setField}，不改构造器；
 * 判定门/升级客户端全 mock（零真实 AI）。</p>
 *
 * <p>覆盖：未启用 fail-open 愿意、决策模型 采纳拒绝/接受、升级 → Qwen 重问、Qwen 失败 fail-open、
 * 批量判定（升级项升 Qwen）、拒绝文本。</p>
 *
 * <p>所属模块：domain/agent/roleplay（N 轨道 N3 社交意愿门控）</p>
 */
class SocialWillingnessGateTest {

    private SocialWillingnessGate gate;
    private DecisionGate decisionGate;
    private DecisionEscalationClient escalationClient;

    @BeforeEach
    void setUp() {
        gate = new SocialWillingnessGate();
        decisionGate = mock(DecisionGate.class);
        escalationClient = mock(DecisionEscalationClient.class);
        ReflectionTestUtils.setField(gate, "decisionGate", decisionGate);
        ReflectionTestUtils.setField(gate, "escalationClient", escalationClient);
    }

    @Test
    @DisplayName("未启用（判定门关/决策模型 不可用）→ fail-open 愿意（现状有求必应）")
    void evaluateDisabledFailOpen() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(false);
        SocialWillingnessGate.WillingnessResult r = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertNotNull(r);
        assertTrue(r.willing());
        assertEquals("fail_open", r.source());
        verify(decisionGate, never()).booleanDecision(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("决策模型 采纳愿意 → 放行")
    void evaluateAdoptedWilling() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(true);
        when(decisionGate.booleanDecision(eq(1L), eq(1L), eq(SocialWillingnessGate.SCENE), any(), any()))
                .thenReturn(new DecisionGate.BooleanOutcome(true, true,
                        DecisionGate.DISP_ADOPTED, 0.8, 0.8, 0.47, 100));
        SocialWillingnessGate.WillingnessResult r = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertTrue(r.willing());
        assertEquals("decision", r.source());
        assertEquals(0.8, r.top1Prob(), 0.001);
    }

    @Test
    @DisplayName("决策模型 采纳拒绝 → 拒绝（调用方给程序化拒绝文本）")
    void evaluateAdoptedDeny() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(true);
        when(decisionGate.booleanDecision(eq(1L), eq(1L), eq(SocialWillingnessGate.SCENE), any(), any()))
                .thenReturn(new DecisionGate.BooleanOutcome(false, true,
                        DecisionGate.DISP_ADOPTED, 0.2, 0.8, 0.47, 100));
        SocialWillingnessGate.WillingnessResult r = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertFalse(r.willing());
        assertEquals("decision", r.source());
        // 拒绝文本
        String denial = gate.denialText("孙二娘", "正忙，无暇应答");
        assertTrue(denial.contains("孙二娘"));
        assertTrue(denial.contains("正忙"));
    }

    @Test
    @DisplayName("升级（分布平坦）→ Qwen 重问决定；Qwen 失败 → fail-open 愿意")
    void evaluateEscalatedToQwen() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(true);
        // 决策模型 升级
        when(decisionGate.booleanDecision(eq(1L), eq(1L), eq(SocialWillingnessGate.SCENE), any(), any()))
                .thenReturn(new DecisionGate.BooleanOutcome(false, false,
                        DecisionGate.DISP_ESCALATED, 0.52, 0.52, 0.99, 100));
        // Qwen 判定不愿意
        when(escalationClient.ask(eq(1L), eq(1L), eq(SocialWillingnessGate.ESCALATION_SCENE),
                anyString(), anyString(), any())).thenReturn(new DecisionEscalationClient.ParsedAnswer(false));
        SocialWillingnessGate.WillingnessResult r = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertFalse(r.willing());
        assertEquals("qwen_escalation", r.source());
        // Qwen 失败 → fail-open 愿意
        when(escalationClient.ask(eq(1L), eq(1L), eq(SocialWillingnessGate.ESCALATION_SCENE),
                anyString(), anyString(), any())).thenReturn(null);
        SocialWillingnessGate.WillingnessResult r2 = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertTrue(r2.willing());
        assertEquals("fail_open", r2.source());
    }

    @Test
    @DisplayName("判定失败 → fail-open 愿意（门控是新增层，失败不降级体验）")
    void evaluateFailedFailOpen() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(true);
        when(decisionGate.booleanDecision(eq(1L), eq(1L), eq(SocialWillingnessGate.SCENE), any(), any()))
                .thenReturn(new DecisionGate.BooleanOutcome(false, false,
                        DecisionGate.DISP_FAILED, null, null, null, 0));
        SocialWillingnessGate.WillingnessResult r = gate.evaluate(1L, 1L, "孙二娘", "情境");
        assertTrue(r.willing());
        assertEquals("fail_open", r.source());
    }

    @Test
    @DisplayName("批量判定：逐项收敛（采纳/升级 Qwen/fail-open）")
    void evaluateBatch() {
        when(decisionGate.isEnabled(SocialWillingnessGate.SCENE)).thenReturn(true);
        Map<String, DecisionGate.BooleanOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("npc:1", new DecisionGate.BooleanOutcome(true, true,
                DecisionGate.DISP_ADOPTED, 0.9, 0.9, 0.3, 50));
        outcomes.put("npc:2", new DecisionGate.BooleanOutcome(false, true,
                DecisionGate.DISP_ADOPTED, 0.1, 0.9, 0.3, 50));
        outcomes.put("npc:3", new DecisionGate.BooleanOutcome(false, false,
                DecisionGate.DISP_ESCALATED, 0.52, 0.52, 0.99, 50));
        outcomes.put("npc:4", new DecisionGate.BooleanOutcome(false, false,
                DecisionGate.DISP_FAILED, null, null, null, 0));
        when(decisionGate.booleanDecisions(eq(1L), eq(SocialWillingnessGate.SCENE), any(), any()))
                .thenReturn(outcomes);
        when(escalationClient.ask(eq(1L), eq(3L), eq(SocialWillingnessGate.ESCALATION_SCENE),
                anyString(), anyString(), any())).thenReturn(new DecisionEscalationClient.ParsedAnswer(true));
        Map<String, SocialWillingnessGate.SocialContext> items = new LinkedHashMap<>();
        items.put("npc:1", new SocialWillingnessGate.SocialContext(1L, "甲", "情境"));
        items.put("npc:2", new SocialWillingnessGate.SocialContext(2L, "乙", "情境"));
        items.put("npc:3", new SocialWillingnessGate.SocialContext(3L, "丙", "情境"));
        items.put("npc:4", new SocialWillingnessGate.SocialContext(4L, "丁", "情境"));
        Map<String, Boolean> willing = gate.evaluateBatch(1L, items);
        assertEquals(4, willing.size());
        assertTrue(willing.get("npc:1"));
        assertFalse(willing.get("npc:2"));
        assertTrue(willing.get("npc:3")); // 升级 Qwen=true
        assertTrue(willing.get("npc:4")); // 失败 fail-open
    }
}
