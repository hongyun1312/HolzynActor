package com.holzyn.actor.ai.decision.gate;

import com.holzyn.actor.ai.decision.DecisionAnswer;
import com.holzyn.actor.ai.decision.DecisionCallClient;
import com.holzyn.actor.ai.decision.DecisionResponse;
import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.ai.decision.DecisionState;
import com.holzyn.actor.domain.account.CurrentUserProvider;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N3 判定类落地单测：统一判定门（DecisionGate）。
 *
 * <p>测试纪律（V1.3 第六层）：字段注入 + {@code ReflectionTestUtils.setField}，不改构造器；
 * 决策模型 client 全 mock（返回脚本化响应，零真实 AI）；分布级断言（采纳/升级/采样两态）。</p>
 *
 * <p>覆盖：三重门控（总开关/场景开关/client 可用）、boolean/choice/score 判定、升级策略
 * （top1Prob 阈值）、批量 boolean（多 state 一次前向）、fail-closed（未启用/失败回退语义）。</p>
 *
 * <p>所属模块：ai/decision/decision（N 轨道 N3）</p>
 */
class DecisionGateTest {

    private DecisionGate gate;
    private DecisionGateProperties properties;
    private DecisionCallClient decisionClient;
    private DecisionGateStats stats;

    /** 构造脚本化 boolean 响应（p_true）。 */
    private static DecisionResponse boolResponse(String stateId, String qid, double pTrue) {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("false", Math.round((1 - pTrue) * 1000) / 1000.0);
        probs.put("true", Math.round(pTrue * 1000) / 1000.0);
        DecisionAnswer answer = new DecisionAnswer("boolean", probs, null, pTrue, null, null, pTrue >= 0.5);
        Map<String, DecisionAnswer> byQid = new LinkedHashMap<>();
        byQid.put(qid, answer);
        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        answers.put(stateId, byQid);
        return new DecisionResponse(
                new DecisionResponse.Execution(1, 1, 1, 1, 0, 0, "cuda:0", 0.01),
                answers, "{}");
    }

    /** 构造脚本化 choice 响应。 */
    private static DecisionResponse choiceResponse(String stateId, String qid, Map<String, Double> probs) {
        DecisionAnswer answer = new DecisionAnswer("choice", probs, null, null, null, null, null);
        Map<String, DecisionAnswer> byQid = new LinkedHashMap<>();
        byQid.put(qid, answer);
        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        answers.put(stateId, byQid);
        return new DecisionResponse(
                new DecisionResponse.Execution(1, 1, probs.size(), 1, 0, 0, "cuda:0", 0.01),
                answers, "{}");
    }

    /** 构造脚本化 score 响应。 */
    private static DecisionResponse scoreResponse(String stateId, String qid, Map<String, Double> probs) {
        DecisionAnswer answer = new DecisionAnswer("score", probs, null, null, 1.2, 2, 2);
        Map<String, DecisionAnswer> byQid = new LinkedHashMap<>();
        byQid.put(qid, answer);
        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        answers.put(stateId, byQid);
        return new DecisionResponse(
                new DecisionResponse.Execution(1, 1, probs.size(), 1, 0, 0, "cuda:0", 0.01),
                answers, "{}");
    }

    private static DecisionState boolState(String stateId, String text, double pTrue) {
        Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> qs = DecisionState.newQuestions();
        qs.put("willing", DecisionTemplates.socialWillingnessQuestion());
        return DecisionState.text(stateId, text, qs);
    }

    @BeforeEach
    void setUp() {
        gate = new DecisionGate();
        properties = new DecisionGateProperties();
        decisionClient = mock(DecisionCallClient.class);
        stats = new DecisionGateStats();
        CurrentUserProvider cu = mock(CurrentUserProvider.class);
        when(cu.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(gate, "properties", properties);
        ReflectionTestUtils.setField(gate, "decisionClient", decisionClient);
        ReflectionTestUtils.setField(gate, "currentUserProvider", cu);
        ReflectionTestUtils.setField(gate, "stats", stats);
        // 默认：client 可用 + 场景开关开
        when(decisionClient.isEnabled()).thenReturn(true);
        properties.getScenes().put(DecisionScenes.SOCIAL_WILLINGNESS, new DecisionGateProperties.SceneConfig());
        properties.getScenes().get(DecisionScenes.SOCIAL_WILLINGNESS).setEnabled(true);
    }

    @Test
    @DisplayName("三重门控：总开关/场景开关/client 任一不满足 → 未启用")
    void isEnabledTripleGate() {
        // 全开
        assertTrue(gate.isEnabled(DecisionScenes.SOCIAL_WILLINGNESS));
        // 总开关关
        properties.setEnabled(false);
        assertFalse(gate.isEnabled(DecisionScenes.SOCIAL_WILLINGNESS));
        properties.setEnabled(true);
        // 场景开关关
        properties.getScenes().get(DecisionScenes.SOCIAL_WILLINGNESS).setEnabled(false);
        assertFalse(gate.isEnabled(DecisionScenes.SOCIAL_WILLINGNESS));
        // client 不可用
        when(decisionClient.isEnabled()).thenReturn(false);
        assertFalse(gate.isEnabled(DecisionScenes.SOCIAL_WILLINGNESS));
        // 未配置场景（默认关）
        assertFalse(gate.isEnabled(DecisionScenes.MASK_CONTEXT));
    }

    @Test
    @DisplayName("boolean 判定：未启用 → disabled（fail-open 语义由调用方处理）")
    void booleanDecisionDisabled() {
        when(decisionClient.isEnabled()).thenReturn(false);
        DecisionGate.BooleanOutcome out = gate.booleanDecision(1L, 1L,
                DecisionScenes.SOCIAL_WILLINGNESS, boolState("s1", "情境", 0.8), "willing");
        assertNotNull(out);
        assertFalse(out.adopted());
        assertEquals(DecisionGate.DISP_DISABLED, out.disposition());
    }

    @Test
    @DisplayName("boolean 判定：采纳（sample 分布内）且统计记录")
    void booleanDecisionAdopted() {
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.8));
        DecisionGate.BooleanOutcome out = gate.booleanDecision(1L, 1L,
                DecisionScenes.SOCIAL_WILLINGNESS, boolState("s1", "情境", 0.8), "willing");
        assertNotNull(out);
        assertTrue(out.adopted());
        assertEquals(DecisionGate.DISP_ADOPTED, out.disposition());
        assertEquals(0.8, out.pTrue(), 0.001);
        assertEquals(0.8, out.top1Prob(), 0.001);
        // 统计已记录
        assertFalse(stats.recent(10).isEmpty());
        assertEquals(1L, stats.sceneStats().get(0).get("count"));
    }

    @Test
    @DisplayName("boolean 判定：升级策略按 N2 标定 top1Prob=0.5（boolean 恒 ≥0.5 → 默认几乎不升级）")
    void booleanDecisionEscalated() {
        // 配置升级阈值 0.5（N2 实测标定接线：social_willingness 建议 top1Prob=0.5）
        DecisionGateProperties.SceneConfig cfg = properties.getScenes().get(DecisionScenes.SOCIAL_WILLINGNESS);
        cfg.getEscalation().setEnabled(true);
        cfg.getEscalation().setTop1Prob(0.5);
        // 语义：boolean top1 = max(p, 1-p) 恒 ≥0.5 → 默认阈值 0.5 下从不升级（采纳标准=整体一致率
        // 92.3% 已达标）；升级是理论兜底（p 恰为 0.5 时 top1=0.5 仍 ≥，故仅当阈值 >0.5 才可能触发）。
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.55));
        DecisionGate.BooleanOutcome out = gate.booleanDecision(1L, 1L,
                DecisionScenes.SOCIAL_WILLINGNESS, boolState("s1", "情境", 0.55), "willing");
        assertNotNull(out);
        assertTrue(out.adopted()); // top1=0.55 ≥0.5 → 采纳
        // 阈值调高到 0.9（对照实验）：平坦分布 p=0.55 → top1=0.55 <0.9 → 升级（top1Prob 维度对
        // boolean 也可工作，只是阈值须 >0.5）
        cfg.getEscalation().setTop1Prob(0.9);
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.55));
        DecisionGate.BooleanOutcome out2 = gate.booleanDecision(1L, 1L,
                DecisionScenes.SOCIAL_WILLINGNESS, boolState("s1", "情境", 0.55), "willing");
        assertFalse(out2.adopted());
        assertEquals(DecisionGate.DISP_ESCALATED, out2.disposition());
        // 熵维度（可选配置）：尖锐分布（p=0.9，熵≈0.47）低于熵上限 → 采纳；平坦（p=0.55，熵≈0.99）
        // 高于上限 → 升级（设计 §6「分布不尖锐升 Qwen」的熵口径）
        cfg.getEscalation().setTop1Prob(null);
        cfg.getEscalation().setEntropy(0.9);
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.9));
        assertTrue(gate.booleanDecision(1L, 1L, DecisionScenes.SOCIAL_WILLINGNESS,
                boolState("s1", "情境", 0.9), "willing").adopted());
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.55));
        assertFalse(gate.booleanDecision(1L, 1L, DecisionScenes.SOCIAL_WILLINGNESS,
                boolState("s1", "情境", 0.55), "willing").adopted());
    }

    @Test
    @DisplayName("choice 判定：采纳采样 + 升级策略（top1Prob 阈值）")
    void choiceDecisionAdoptedAndEscalated() {
        DecisionGateProperties.SceneConfig crowdCfg = properties.getScenes()
                .computeIfAbsent(DecisionScenes.CROWD_DECISION,
                        k -> new DecisionGateProperties.SceneConfig());
        crowdCfg.setEnabled(true);
        // 采纳：work 0.479 尖锐
        Map<String, Double> sharp = new LinkedHashMap<>();
        sharp.put("rest", 0.1);
        sharp.put("chat", 0.2);
        sharp.put("work", 0.7);
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.CROWD_DECISION),
                any(List.class))).thenReturn(choiceResponse("s1", "action", sharp));
        Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> qs = DecisionState.newQuestions();
        qs.put("action", DecisionTemplates.crowdQuestion(
                DecisionTemplates.commonCrowdCandidates()));
        DecisionState state = DecisionState.text("s1", "情境", qs);
        DecisionGate.ChoiceOutcome out = gate.choiceDecision(1L, 1L,
                DecisionScenes.CROWD_DECISION, state, "action");
        assertTrue(out.adopted());
        assertNotNull(out.chosen());
        assertEquals(0.7, out.top1Prob(), 0.001);
        // 升级：crowd 场景配置 top1Prob=0.7 → 平坦分布（top1 0.4）升级
        crowdCfg.getEscalation().setEnabled(true);
        crowdCfg.getEscalation().setTop1Prob(0.7);
        Map<String, Double> flat = new LinkedHashMap<>();
        flat.put("rest", 0.3);
        flat.put("chat", 0.3);
        flat.put("work", 0.4);
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.CROWD_DECISION),
                any(List.class))).thenReturn(choiceResponse("s1", "action", flat));
        DecisionGate.ChoiceOutcome out2 = gate.choiceDecision(1L, 1L,
                DecisionScenes.CROWD_DECISION, state, "action");
        assertFalse(out2.adopted());
        assertEquals(DecisionGate.DISP_ESCALATED, out2.disposition());
    }

    @Test
    @DisplayName("score 判定：采纳档位采样 + 期望值")
    void scoreDecisionAdopted() {
        DecisionGateProperties.SceneConfig fertCfg = properties.getScenes()
                .computeIfAbsent(DecisionScenes.FERTILITY,
                        k -> new DecisionGateProperties.SceneConfig());
        fertCfg.setEnabled(true);
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("0", 0.2);
        probs.put("1", 0.3);
        probs.put("2", 0.5);
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.FERTILITY),
                any(List.class))).thenReturn(scoreResponse("s1", "fertility", probs));
        Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> qs = DecisionState.newQuestions();
        qs.put("fertility", DecisionTemplates.fertilityQuestion());
        DecisionState state = DecisionState.text("s1", "情境", qs);
        DecisionGate.ScoreOutcome out = gate.scoreDecision(1L, 1L,
                DecisionScenes.FERTILITY, state, "fertility");
        assertTrue(out.adopted());
        assertTrue(out.level() >= 0);
        assertEquals(1.3, out.expected(), 0.001); // 0×0.2+1×0.3+2×0.5
    }

    @Test
    @DisplayName("批量 boolean：多 state 一次前向逐项收敛")
    void booleanDecisionsBatch() {
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(List.of(
                boolResponse("b0", "willing", 0.9),
                boolResponse("b1", "willing", 0.1),
                boolResponse("b2", "willing", 0.95)));
        // 注意：evaluate 返回单响应含多 state 才符合真实形状；此处分别响应仅覆盖解析路径——
        // 真实批量返回一个响应含全部 state，测试用单响应多 state 形状
        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        answers.put("b0", boolAnswer(0.9));
        answers.put("b1", boolAnswer(0.1));
        answers.put("b2", boolAnswer(0.95));
        DecisionResponse multi = new DecisionResponse(
                new DecisionResponse.Execution(3, 3, 3, 1, 0, 0, "cuda:0", 0.01), answers, "{}");
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(List.of(multi));
        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("k0", boolState("b0", "情境0", 0.9));
        states.put("k1", boolState("b1", "情境1", 0.1));
        states.put("k2", boolState("b2", "情境2", 0.95));
        Map<String, DecisionGate.BooleanOutcome> out = gate.booleanDecisions(
                1L, DecisionScenes.SOCIAL_WILLINGNESS, states, "willing");
        assertEquals(3, out.size());
        assertTrue(out.get("k0").adopted());
        assertTrue(out.get("k2").adopted());
        // 批量统计 3 条
        assertEquals(3L, stats.sceneStats().get(0).get("count"));
    }

    @Test
    @DisplayName("批量 boolean：client 失败 → 全部 failed（调用方 fail-open）")
    void booleanDecisionsBatchFailed() {
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenThrow(new RuntimeException("网络故障"));
        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("k0", boolState("b0", "情境", 0.9));
        Map<String, DecisionGate.BooleanOutcome> out = gate.booleanDecisions(
                1L, DecisionScenes.SOCIAL_WILLINGNESS, states, "willing");
        assertFalse(out.get("k0").adopted());
        assertEquals(DecisionGate.DISP_FAILED, out.get("k0").disposition());
    }

    @Test
    @DisplayName("判定门方法无 @Transactional（AiCallOutsideTransactionTest 反射守护的配套行为断言）")
    void gateCallsClientOncePerDecision() {
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(1L), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(boolResponse("s1", "willing", 0.8));
        gate.booleanDecision(1L, 1L, DecisionScenes.SOCIAL_WILLINGNESS,
                boolState("s1", "情境", 0.8), "willing");
        verify(decisionClient).evaluateOnce(eq(1L), eq(1L), eq(1L),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), any(List.class));
    }

    @Test
    @DisplayName("N5 批量混合判定：多 state × 共享 qid 一次前向（score + choice 同请求）")
    void mixedBatchDecisionsMultiState() {
        // 装配 attention 场景（默认开）
        DecisionGateProperties.SceneConfig att = properties.getScenes()
                .computeIfAbsent(DecisionScenes.ATTENTION,
                        k -> new DecisionGateProperties.SceneConfig());
        att.setEnabled(true);
        // 单响应含两个 state，每个 state 两个 qid（score attention_state + choice micro_intent）
        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        Map<String, Double> scoreProbs = new LinkedHashMap<>();
        scoreProbs.put("0", 0.05);
        scoreProbs.put("1", 0.05);
        scoreProbs.put("2", 0.10);
        scoreProbs.put("3", 0.80); // 尖锐：高
        Map<String, DecisionAnswer> a0 = new LinkedHashMap<>();
        a0.put("attention_state", new DecisionAnswer("score", scoreProbs, null, null, 2.65, 3, 3));
        a0.put("micro_intent", new DecisionAnswer("choice",
                Map.of("continue_current", 0.85, "drift", 0.05, "look_around", 0.03,
                        "fidget", 0.03, "chat", 0.02, "move_about", 0.02), null, null, null, null, null));
        Map<String, Double> scoreProbs2 = new LinkedHashMap<>();
        scoreProbs2.put("0", 0.7);
        scoreProbs2.put("1", 0.1);
        scoreProbs2.put("2", 0.1);
        scoreProbs2.put("3", 0.1); // 尖锐：无
        Map<String, DecisionAnswer> a1 = new LinkedHashMap<>();
        a1.put("attention_state", new DecisionAnswer("score", scoreProbs2, null, null, 0.6, 0, 0));
        a1.put("micro_intent", new DecisionAnswer("choice",
                Map.of("continue_current", 0.4, "drift", 0.3, "look_around", 0.1,
                        "fidget", 0.1, "chat", 0.05, "move_about", 0.05), null, null, null, null, null));
        answers.put("s0", a0);
        answers.put("s1", a1);
        DecisionResponse multi = new DecisionResponse(
                new DecisionResponse.Execution(2, 2, 10, 1, 0, 0, "cuda:0", 0.01), answers, "{}");
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.ATTENTION),
                any(List.class))).thenReturn(List.of(multi));

        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("k0", mixedState("s0", "角色0情境"));
        states.put("k1", mixedState("s1", "角色1情境"));
        Map<String, Map<String, Object>> out = gate.mixedBatchDecisions(1L,
                DecisionScenes.ATTENTION, states,
                Map.of("attention_state", DecisionQuestion.TYPE_SCORE,
                        "micro_intent", DecisionQuestion.TYPE_CHOICE));

        assertEquals(2, out.size());
        Map<String, Object> r0 = out.get("k0");
        assertTrue(r0.get("attention_state") instanceof DecisionGate.ScoreOutcome so0 && so0.adopted());
        assertTrue(r0.get("micro_intent") instanceof DecisionGate.ChoiceOutcome co0 && co0.adopted());
        Map<String, Object> r1 = out.get("k1");
        assertTrue(r1.get("attention_state") instanceof DecisionGate.ScoreOutcome so1 && so1.adopted());
        assertTrue(r1.get("micro_intent") instanceof DecisionGate.ChoiceOutcome co1 && co1.adopted());
        // 逐题采纳语义与单题版一致（score 期望值 2.65 / choice 采样）
        assertEquals(2.65, ((DecisionGate.ScoreOutcome) r0.get("attention_state")).expected(), 0.001);
        // 批量统计按题记录
        assertEquals(4L, stats.recent(10).size());
    }

    @Test
    @DisplayName("N5 批量混合判定：场景未启用 → 全部 disabled（调用方程序化兜底）")
    void mixedBatchDecisionsDisabled() {
        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("k0", mixedState("s0", "情境"));
        Map<String, Map<String, Object>> out = gate.mixedBatchDecisions(1L,
                DecisionScenes.ACCIDENT, states,
                Map.of("accident", DecisionQuestion.TYPE_BOOLEAN));
        assertEquals(DecisionGate.DISP_DISABLED,
                ((DecisionGate.BooleanOutcome) out.get("k0").get("accident")).disposition());
    }

    @Test
    @DisplayName("N5 批量混合判定：client 失败 → 全部 failed（fail-closed 回退）")
    void mixedBatchDecisionsFailed() {
        DecisionGateProperties.SceneConfig att = properties.getScenes()
                .computeIfAbsent(DecisionScenes.ATTENTION,
                        k -> new DecisionGateProperties.SceneConfig());
        att.setEnabled(true);
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.ATTENTION),
                any(List.class))).thenThrow(new RuntimeException("网络故障"));
        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("k0", mixedState("s0", "情境"));
        Map<String, Map<String, Object>> out = gate.mixedBatchDecisions(1L,
                DecisionScenes.ATTENTION, states,
                Map.of("attention_state", DecisionQuestion.TYPE_SCORE));
        assertEquals(DecisionGate.DISP_FAILED,
                ((DecisionGate.ScoreOutcome) out.get("k0").get("attention_state")).disposition());
    }

    @Test
    @DisplayName("上下文注入：单 state 判定自动装配 V2.0 段 1+2+3 判定输入（2026-10-08 规范化重构批1）")
    void booleanDecisionInjectsContextIntoState() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        ReflectionTestUtils.setField(gate, "aiContextInjector", injector);
        when(injector.decisionInput(eq(1L), eq("npc:7"), any())).thenAnswer(inv -> {
            String text = inv.getArgument(2);
            return "【世界观】\n青禾村四面环山。\n\n【系统指令】\n你是一个快速判断引擎。"
                    + "\n\n【角色身份】\n姓名：沈砚秋（青禾村村长）\n\n【本次情境】\n" + text
                    + "\n\n【行为护栏】\n只依据情境中明确给出的事实判断。"
                    + "\n\n【输出契约】\n严格按每个问题的判据作答。";
        });
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(7L),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), any(List.class)))
                .thenReturn(boolResponse("s1", "willing", 0.8));

        gate.booleanDecision(1L, 7L, DecisionScenes.SOCIAL_WILLINGNESS,
                boolState("s1", "npc:7 与 npc:6 的关系从「stranger」变为「acquaintance」", 0.8), "willing");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<DecisionState>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(decisionClient).evaluateOnce(eq(1L), eq(1L), eq(7L),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), captor.capture());
        String sent = captor.getValue().get(0).state().toString();
        assertTrue(sent.contains("【世界观】"));
        assertTrue(sent.contains("【系统指令】"));
        assertTrue(sent.contains("【角色身份】"));
        assertTrue(sent.contains("【本次情境】"));
        assertTrue(sent.contains("【行为护栏】"));
        assertTrue(sent.contains("【输出契约】"));
        assertTrue(sent.contains("沈砚秋"));
        assertTrue(sent.contains("关系从「stranger」变为「acquaintance」"));
        // 段 1 前缀在原始判定文本之前、段 3 契约在原始判定文本之后（AI 先读身份与世界观再读变化，契约在末尾高权重位）
        assertTrue(sent.indexOf("【世界观】") < sent.indexOf("关系从"));
        assertTrue(sent.indexOf("关系从") < sent.indexOf("【行为护栏】"));
    }

    @Test
    @DisplayName("上下文注入：未装配注入器 → state 原样发送（零阻断保既有行为）")
    void booleanDecisionWithoutInjectorKeepsOriginalState() {
        when(decisionClient.evaluateOnce(eq(1L), eq(1L), eq(7L),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), any(List.class)))
                .thenReturn(boolResponse("s1", "willing", 0.8));
        gate.booleanDecision(1L, 7L, DecisionScenes.SOCIAL_WILLINGNESS,
                boolState("s1", "原始判定文本", 0.8), "willing");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<DecisionState>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(decisionClient).evaluateOnce(eq(1L), eq(1L), eq(7L),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), captor.capture());
        assertEquals("原始判定文本", captor.getValue().get(0).state());
    }

    @Test
    @DisplayName("上下文注入：批量判定按键（npc:{id}）逐角色注入，无法解析的键原样保留")
    void booleanDecisionsBatchInjectsPerCharacterRef() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        ReflectionTestUtils.setField(gate, "aiContextInjector", injector);
        when(injector.decisionInput(eq(1L), eq("npc:7"), any())).thenAnswer(inv -> {
            String text = inv.getArgument(2);
            return "【世界观】\n青禾村四面环山。\n\n【角色身份】\n姓名：沈砚秋（青禾村村长）\n\n【本次情境】\n" + text;
        });
        // 无法解析的键（非 npc:{id}）→ 不调用注入器（enrichBatch 原样保留）
        when(injector.decisionInput(eq(1L), eq("npc:8"), any())).thenAnswer(inv -> inv.getArgument(2));

        Map<String, Map<String, DecisionAnswer>> answers = new LinkedHashMap<>();
        answers.put("b0", boolAnswer(0.9));
        answers.put("b1", boolAnswer(0.1));
        DecisionResponse multi = new DecisionResponse(
                new DecisionResponse.Execution(2, 2, 2, 1, 0, 0, "cuda:0", 0.01), answers, "{}");
        when(decisionClient.evaluate(eq(1L), eq(1L), eq(null), eq(DecisionScenes.SOCIAL_WILLINGNESS),
                any(List.class))).thenReturn(List.of(multi));

        Map<String, DecisionState> states = new LinkedHashMap<>();
        states.put("npc:7", boolState("b0", "角色7情境", 0.9));
        states.put("npc:8", boolState("b1", "角色8情境", 0.1));
        Map<String, DecisionGate.BooleanOutcome> out = gate.booleanDecisions(
                1L, DecisionScenes.SOCIAL_WILLINGNESS, states, "willing");
        assertEquals(2, out.size());
        assertTrue(out.get("npc:7").adopted());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<DecisionState>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(decisionClient).evaluate(eq(1L), eq(1L), eq(null),
                eq(DecisionScenes.SOCIAL_WILLINGNESS), captor.capture());
        java.util.List<DecisionState> sent = captor.getValue();
        // npc:7 已注入，npc:8 原样
        String s7 = sent.stream().filter(s -> "b0".equals(s.id())).findFirst().orElseThrow().state().toString();
        String s8 = sent.stream().filter(s -> "b1".equals(s.id())).findFirst().orElseThrow().state().toString();
        assertTrue(s7.contains("【世界观】"));
        assertTrue(s7.contains("沈砚秋"));
        assertEquals("角色8情境", s8);
    }

    /** N5 共享 qid 混合 state 构造（score attention_state + choice micro_intent）。 */
    private static DecisionState mixedState(String stateId, String text) {
        Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> qs = DecisionState.newQuestions();
        qs.put("attention_state", DecisionTemplates.attentionStateQuestion());
        qs.put("micro_intent", DecisionTemplates.microIntentQuestion());
        return DecisionState.text(stateId, text, qs);
    }

    private static Map<String, DecisionAnswer> boolAnswer(double pTrue) {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("false", Math.round((1 - pTrue) * 1000) / 1000.0);
        probs.put("true", Math.round(pTrue * 1000) / 1000.0);
        Map<String, DecisionAnswer> byQid = new LinkedHashMap<>();
        byQid.put("willing", new DecisionAnswer("boolean", probs, null, pTrue, null, null, pTrue >= 0.5));
        return byQid;
    }
}
