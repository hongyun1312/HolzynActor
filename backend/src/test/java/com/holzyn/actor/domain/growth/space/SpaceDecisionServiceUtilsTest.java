package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N4 空间候选选择单测：判定编排工具（SpaceDecisionServiceUtils 纯静态 + mock 判定门）。
 *
 * <p>测试纪律（V1.3 第六层）：判定门 mock（脚本化 mixedDecisions 响应，零真实 AI）——
 * precondition 程序化过滤 → 语义条件 boolean 批量 + 行动 choice 一次前向 → 选中复核/重采样
 * → null 回退语义逐条断言。</p>
 *
 * <p>所属模块：domain/growth/space（N 轨道 N4 程序化件）</p>
 */
class SpaceDecisionServiceUtilsTest {

    private NanoJevDecisionGate gate;

    @BeforeEach
    void setUp() {
        gate = mock(NanoJevDecisionGate.class);
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_DECISION)).thenReturn(true);
        when(gate.samplingMode(NanoJevDecisionScenes.SPACE_DECISION)).thenReturn("sample");
    }

    private static SpaceDecisionCandidates.SpaceCandidate cand(String key, String action, String precondition) {
        return new SpaceDecisionCandidates.SpaceCandidate(key, action,
                action == null ? "" : action, null, precondition, null, false);
    }

    private static SpacePreconditionEvaluator.Context ctx() {
        return new SpacePreconditionEvaluator.Context(10, new ActorNpcState(),
                List.of(), List.of(), "晴", "村口");
    }

    private static Map<String, Object> mixed(List<String> pcTrue, String chosenKey,
                                             Map<String, Double> distribution) {
        Map<String, Object> results = new LinkedHashMap<>();
        int i = 0;
        for (String key : pcTrue) {
            results.put(SpaceDecisionServiceUtils.PRECOND_QID_PREFIX + (i++),
                    new NanoJevDecisionGate.BooleanOutcome(true, true,
                            NanoJevDecisionGate.DISP_ADOPTED, 0.9, 0.9, 0.3, 50));
        }
        results.put(SpaceDecisionServiceUtils.ACTION_QID,
                new NanoJevDecisionGate.ChoiceOutcome(chosenKey, true,
                        NanoJevDecisionGate.DISP_ADOPTED, distribution, 0.8, 0.7, 50));
        return results;
    }

    @Test
    @DisplayName("gate 不可用/候选不足/场景未启用 → null（零阻断回退）")
    void nullFallbacks() {
        assertNull(SpaceDecisionServiceUtils.decide(null, 1L, 1L,
                List.of(cand("a", "甲", null), cand("b", "乙", null)), ctx(), "情境"));
        assertNull(SpaceDecisionServiceUtils.decide(gate, 1L, 1L,
                List.of(cand("a", "甲", null)), ctx(), "情境"));
        when(gate.isEnabled(NanoJevDecisionScenes.SPACE_DECISION)).thenReturn(false);
        assertNull(SpaceDecisionServiceUtils.decide(gate, 1L, 1L,
                List.of(cand("a", "甲", null), cand("b", "乙", null)), ctx(), "情境"));
    }

    @Test
    @DisplayName("全部程序化满足（无 precondition）→ 一次前向仅 choice，选中即采纳")
    void noSemanticPrecondition() {
        List<SpaceDecisionCandidates.SpaceCandidate> cands = List.of(
                cand("a", "去河边挑水", null), cand("b", "坐下发呆", null));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(SpaceDecisionServiceUtils.ACTION_QID,
                new NanoJevDecisionGate.ChoiceOutcome("b", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("a", 0.2, "b", 0.8), 0.8, 0.7, 50));
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_DECISION),
                any(), any())).thenReturn(results);
        SpaceDecisionCandidates.SpaceCandidate chosen =
                SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境");
        assertNotNull(chosen);
        assertEquals("b", chosen.key());
    }

    @Test
    @DisplayName("precondition 程序化不满足 → 候选出局（不进判定）")
    void programmaticFilter() {
        // 「下雨」在晴天下程序化出局；只剩一项 → 不足 2 → null（零阻断）
        List<SpaceDecisionCandidates.SpaceCandidate> cands = List.of(
                cand("a", "去河边挑水", "下雨"), cand("b", "坐下发呆", "下雨"));
        assertNull(SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境"));
    }

    @Test
    @DisplayName("语义条件 boolean 批量 + choice 一次前向；条件为真才进可接受集")
    void semanticPreconditionAccepted() {
        List<SpaceDecisionCandidates.SpaceCandidate> cands = List.of(
                cand("a", "去河边挑水", "若有人同去"), cand("b", "坐下发呆", null));
        // 语义条件（pc0）判定为真 + choice 选中 a
        Map<String, Object> results = mixed(List.of("a"), "a", Map.of("a", 0.6, "b", 0.4));
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_DECISION),
                any(), any())).thenReturn(results);
        SpaceDecisionCandidates.SpaceCandidate chosen =
                SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境");
        assertNotNull(chosen);
        assertEquals("a", chosen.key());
    }

    @Test
    @DisplayName("选中项语义条件为假 → 按 choice 分布∩可接受集归一化重采样（以程序化为准）")
    void resampleOnConflict() {
        List<SpaceDecisionCandidates.SpaceCandidate> cands = List.of(
                cand("a", "去河边挑水", "若有人同去"), cand("b", "坐下发呆", null));
        // pc0=false：a 出可接受集；choice 仍选 a（模型误判）→ 重采样到 b（唯一可接受）
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(SpaceDecisionServiceUtils.PRECOND_QID_PREFIX + "0",
                new NanoJevDecisionGate.BooleanOutcome(false, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.1, 0.9, 0.3, 50));
        results.put(SpaceDecisionServiceUtils.ACTION_QID,
                new NanoJevDecisionGate.ChoiceOutcome("a", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("a", 0.9, "b", 0.1), 0.9, 0.5, 50));
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_DECISION),
                any(), any())).thenReturn(results);
        SpaceDecisionCandidates.SpaceCandidate chosen =
                SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境");
        assertNotNull(chosen);
        assertEquals("b", chosen.key());
    }

    @Test
    @DisplayName("判定失败/可接受集为空/选中键未知 → null")
    void failuresToNull() {
        List<SpaceDecisionCandidates.SpaceCandidate> cands = List.of(
                cand("a", "去河边挑水", "若有人同去"), cand("b", "坐下发呆", null));
        // 语义条件判定 failed（无 BooleanOutcome）→ 可接受集仅 b；choice 选未知键 → null
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(SpaceDecisionServiceUtils.ACTION_QID,
                new NanoJevDecisionGate.ChoiceOutcome("bogus", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("bogus", 1.0), 1.0, 0.0, 50));
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_DECISION),
                any(), any())).thenReturn(results);
        assertNull(SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境"));
        // 语义条件为假 + choice 选 a → 重采样失败（b 不在分布）→ 均匀回退仍可返回 b
        Map<String, Object> results2 = new LinkedHashMap<>();
        results2.put(SpaceDecisionServiceUtils.PRECOND_QID_PREFIX + "0",
                new NanoJevDecisionGate.BooleanOutcome(false, true,
                        NanoJevDecisionGate.DISP_ADOPTED, 0.1, 0.9, 0.3, 50));
        results2.put(SpaceDecisionServiceUtils.ACTION_QID,
                new NanoJevDecisionGate.ChoiceOutcome("a", true,
                        NanoJevDecisionGate.DISP_ADOPTED, Map.of("a", 0.9), 0.9, 0.5, 50));
        when(gate.mixedDecisions(eq(1L), eq(1L), eq(NanoJevDecisionScenes.SPACE_DECISION),
                any(), any())).thenReturn(results2);
        SpaceDecisionCandidates.SpaceCandidate chosen =
                SpaceDecisionServiceUtils.decide(gate, 1L, 1L, cands, ctx(), "情境");
        assertNotNull(chosen);
        assertEquals("b", chosen.key());
    }
}
