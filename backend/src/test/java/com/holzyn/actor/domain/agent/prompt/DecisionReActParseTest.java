package com.holzyn.actor.domain.agent.prompt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ReAct 决策契约解析单元测试（与实现同包）。
 *
 * <p>职责：锁定「action_detail 必须是可读的动作文本」——模型偶尔把内层契约再套一层字符串
 * （{@code "action_detail": "{\"action\": \"…\"}"}），解析层必须先解包再落库；
 * 解不出人话就返回 null 走程序化兜底，绝不把原始 JSON 当动作传下去。</p>
 */
class DecisionReActParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 正常契约：action_detail 为纯文本，映射旧键 action 同值。 */
    @Test
    void parseMapsActionDetailToAction() throws Exception {
        JsonNode node = MAPPER.readTree("{\"reasoning\":\"天热，水缸见底\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"去河边挑两桶水\",\"target\":\"河边\",\"priority\":3,"
                + "\"expected_duration_minutes\":30}");
        Map<String, Object> decision = DecisionReActParse.parse(node);
        assertNotNull(decision);
        assertEquals("去河边挑两桶水", decision.get("actionDetail"));
        assertEquals("去河边挑两桶水", decision.get("action"), "旧键 action 必须是人话");
        assertEquals("move", decision.get("type"));
    }

    /** 核心：action_detail 的值本身是 {"action": "…"} 字符串 → 解包成人话。 */
    @Test
    void parseUnwrapsJsonActionDetail() throws Exception {
        JsonNode node = MAPPER.readTree("{\"reasoning\":\"天色已晚\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"{\\\"action\\\": \\\"傍晚，老支书从桥头慢慢走回家\\\"}\"}");
        Map<String, Object> decision = DecisionReActParse.parse(node);
        assertNotNull(decision, "解包后应通过校验");
        assertEquals("傍晚，老支书从桥头慢慢走回家", decision.get("action"));
        assertEquals("傍晚，老支书从桥头慢慢走回家", decision.get("actionDetail"));
    }

    /** 解包后仍无人话（结构体里没有可识别的键）→ 判解析失败（回退程序化兜底，不落 JSON）。 */
    @Test
    void parseRejectsUnparseableActionDetail() throws Exception {
        JsonNode node = MAPPER.readTree("{\"reasoning\":\"随便\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"{\\\"foo\\\": 1}\"}");
        assertNull(DecisionReActParse.parse(node), "抽不出人话必须判解析失败");
    }

    /** action_detail 是对象（而非字符串）→ asText 为空 → 判解析失败。 */
    @Test
    void parseRejectsObjectActionDetail() throws Exception {
        JsonNode node = MAPPER.readTree("{\"reasoning\":\"随便\",\"action_type\":\"start_action\","
                + "\"action_detail\":{\"action\":\"去做点什么\"}}");
        assertNull(DecisionReActParse.parse(node));
    }

    /** 契约外的 action_type 仍判失败（解包不放宽枚举校验）。 */
    @Test
    void parseRejectsUnknownActionType() throws Exception {
        JsonNode node = MAPPER.readTree("{\"action_type\":\"teleport\",\"action_detail\":\"瞬移\"}");
        assertNull(DecisionReActParse.parse(node));
    }

    /** 程序化兜底的 continue 决策仍然自洽（action/actionDetail 同值）。 */
    @Test
    void continueDecisionIsSelfConsistent() {
        Map<String, Object> d = DecisionReActParse.continueDecision("择菜");
        assertEquals("continue", d.get("actionType"));
        assertEquals("继续择菜", d.get("action"));
        assertEquals("继续择菜", d.get("actionDetail"));
    }
}
