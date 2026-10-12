package com.holzyn.actor.domain.agent.runtime;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前瞻推演决策解析器单元测试（O2 决策与协调 P1-1，domain/agent/runtime 同包）。
 * <p>职责：验证——候选行动数组提取（action/consequence）、上限截断、缺失/非法回退空列表。</p>
 * <p>设计依据：现状优化轨道 §7.2 P1-1（candidates[{action, consequence}] 结构化输出）。</p>
 */
class ForesightDecisionParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 正常候选提取（action+consequence）。 */
    @Test
    void parseCandidatesHappyPath() throws Exception {
        var node = MAPPER.readTree("""
                {"action":"去集市摊位买药","candidates":[
                  {"action":"先到集市摊位买药","consequence":"药到手，能及时治病"},
                  {"action":"等一会再去","consequence":"可能错过药材"},
                  {"action":"改去药庐","consequence":"更贵但稳妥"}
                ]}
                """);
        var cands = ForesightDecisionParse.parseCandidates(node, 3);
        assertEquals(3, cands.size());
        assertEquals("先到集市摊位买药", cands.get(0).action());
        assertEquals("可能错过药材", cands.get(1).consequence());
        assertTrue(ForesightDecisionParse.hasCandidates(node));
    }

    /** 上限截断（maxCandidates=2 → 只取前 2）。 */
    @Test
    void parseCandidatesRespectsLimit() throws Exception {
        var node = MAPPER.readTree("""
                {"action":"x","candidates":[{"action":"a"},{"action":"b"},{"action":"c"}]}
                """);
        assertEquals(2, ForesightDecisionParse.parseCandidates(node, 2).size());
        assertEquals(3, ForesightDecisionParse.parseCandidates(node, 0).size());
    }

    /** 无候选/非法 → 空列表（success=false 判定依据）。 */
    @Test
    void parseCandidatesEmptyOnMissing() throws Exception {
        assertTrue(ForesightDecisionParse.parseCandidates(MAPPER.readTree("{\"action\":\"x\"}"), 0).isEmpty());
        assertTrue(ForesightDecisionParse.parseCandidates(null, 0).isEmpty());
        assertTrue(ForesightDecisionParse.parseCandidates(MAPPER.readTree("[]"), 0).isEmpty());
        assertFalse(ForesightDecisionParse.hasCandidates(MAPPER.readTree("{\"action\":\"x\"}")));
    }

    /** 无 action 的候选项跳过；consequence 缺失为空串。 */
    @Test
    void parseCandidatesSkipsBlankAction() throws Exception {
        var node = MAPPER.readTree("""
                {"candidates":[{"action":"","consequence":"x"},{"action":"   ","consequence":"y"},
                {"consequence":"z"},{"action":"有效候选"}]}
                """);
        var cands = ForesightDecisionParse.parseCandidates(node, 0);
        assertEquals(1, cands.size());
        assertEquals("有效候选", cands.get(0).action());
        assertEquals("", cands.get(0).consequence());
    }
}
