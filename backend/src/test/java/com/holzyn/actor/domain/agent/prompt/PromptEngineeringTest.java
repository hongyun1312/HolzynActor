package com.holzyn.actor.domain.agent.prompt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O4 Prompt 工程框架单元测试（domain/agent/prompt）：
 * 群聊 JSON 契约解析（GroupReplyParse）/ 决策 ReAct 契约解析与适配（DecisionReActParse）/
 * 分层组装器（LayeredPromptBuilder）/ 渲染期规范化（PromptNormalizer）。
 */
class PromptEngineeringTest {

    // ==================== GroupReplyParse（群聊 DIALOG_JSON 契约） ====================

    @Test
    void groupReplyParsesFullContract() {
        String raw = "{\"dialogue\":\"王大哥说的是，\",\"action\":\"端起茶碗抿了一口\","
                + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":2},\"internal_thought\":\"他今天气色不错\"}";
        GroupReplyParse.GroupReply r = GroupReplyParse.parse(raw);
        assertNotNull(r);
        assertTrue(r.compliant());
        assertEquals("王大哥说的是，", r.dialogue());
        assertEquals("端起茶碗抿了一口", r.action());
        assertEquals("平淡", r.emotion());
        assertEquals(2, r.emotionIntensity());
        assertEquals("他今天气色不错", r.internalThought());
    }

    @Test
    void groupReplyToleratesMissingOptionalFields() {
        String raw = "{\"dialogue\":\"就一句话\"}";
        GroupReplyParse.GroupReply r = GroupReplyParse.parse(raw);
        assertNotNull(r);
        assertTrue(r.compliant());
        assertEquals("就一句话", r.dialogue());
        assertNull(r.emotion());
        assertNull(r.emotionIntensity());
    }

    @Test
    void groupReplyRejectsBlankDialogue() {
        GroupReplyParse.GroupReply r = GroupReplyParse.parse("{\"dialogue\":\"  \"}");
        assertNotNull(r);
        assertFalse(r.compliant(), "dialogue 空白 = 契约不合规（空回复防护）");
        assertNull(r.dialogue());
    }

    @Test
    void groupReplyRejectsNonJson() {
        assertNull(GroupReplyParse.parse("不是JSON"));
        assertNull(GroupReplyParse.parse(null));
        assertNull(GroupReplyParse.parse(""));
    }

    // ==================== DecisionReActParse（决策 DECISION_JSON 契约） ====================

    @Test
    void decisionParseAcceptsDecisionTag() {
        String raw = "<decision>{\"reasoning\":\"药材告急\",\"action_type\":\"move_to\","
                + "\"action_detail\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"priority\":4,"
                + "\"expected_duration_minutes\":30}</decision>";
        Map<String, Object> d = DecisionReActParse.parse(raw);
        assertNotNull(d);
        assertEquals("move_to", d.get("actionType"));
        assertEquals("move", d.get("type"), "move_to → 旧枚举 move（下游兼容）");
        assertEquals("前往城东市场采购药材", d.get("action"));
        assertEquals(4, d.get("urgency"), "priority → 旧键 urgency");
        assertEquals(30, d.get("duration"), "expected_duration_minutes → 旧键 duration");
        assertEquals("城东市场", d.get("target"));
        assertEquals(Map.of("move", Map.of("to", "城东市场")), d.get("params"));
    }

    @Test
    void decisionParseAcceptsBareJsonWithoutTag() {
        String raw = "{\"reasoning\":\"无事发生\",\"action_type\":\"continue\","
                + "\"action_detail\":\"继续在茶馆喝茶\",\"priority\":1,\"expected_duration_minutes\":30}";
        Map<String, Object> d = DecisionReActParse.parse(raw);
        assertNotNull(d);
        assertEquals("continue", d.get("actionType"));
        assertEquals("custom", d.get("type"));
    }

    @Test
    void decisionParseRejectsInvalidActionType() {
        assertNull(DecisionReActParse.parse("<decision>{\"reasoning\":\"x\",\"action_type\":\"teleport\","
                + "\"action_detail\":\"瞬移\"}</decision>"));
    }

    @Test
    void decisionParseRejectsBlankActionDetail() {
        assertNull(DecisionReActParse.parse("<decision>{\"reasoning\":\"x\",\"action_type\":\"start_action\","
                + "\"action_detail\":\"  \"}</decision>"));
    }

    @Test
    void decisionParseCapturesWaitAndCandidates() {
        String raw = "<decision>{\"reasoning\":\"等人\",\"action_type\":\"wait\","
                + "\"action_detail\":\"等集市散场再回河畔\",\"priority\":2,\"expected_duration_minutes\":30,"
                + "\"wait_minutes\":25,\"candidates\":[{\"action\":\"先等着\",\"consequence\":\"集市散场\"}]}</decision>";
        Map<String, Object> d = DecisionReActParse.parse(raw);
        assertNotNull(d);
        assertEquals(25, d.get("waitMinutes"));
        assertEquals("rest", d.get("type"), "wait → 旧枚举 rest（等待类计划）");
        assertNotNull(d.get("foresightCandidates"));
        assertEquals(1, ((List<?>) d.get("foresightCandidates")).size());
    }

    @Test
    void continueDecisionIsProgrammaticFallback() {
        Map<String, Object> d = DecisionReActParse.continueDecision("在茶馆喝茶");
        assertEquals("continue", d.get("actionType"));
        assertTrue(String.valueOf(d.get("action")).contains("继续"));
        assertTrue(String.valueOf(d.get("action")).contains("在茶馆喝茶"));
        assertEquals(1, d.get("urgency"));
        assertNotNull(d.get("reason"));
    }

    @Test
    void legacyTypeMapping() {
        assertEquals("move", DecisionReActParse.legacyType("move_to", "前往市场"));
        assertEquals("speak", DecisionReActParse.legacyType("speak_to", "与掌柜商量"));
        assertEquals("rest", DecisionReActParse.legacyType("wait", "等一等"));
        assertEquals("custom", DecisionReActParse.legacyType("continue", "继续手头的事"));
        assertEquals("move", DecisionReActParse.legacyType("start_action", "去村口集市"));
        assertEquals("speak", DecisionReActParse.legacyType("start_action", "找郎中问诊"));
        assertEquals("custom", DecisionReActParse.legacyType("start_action", "练剑"));
    }

    // ==================== LayeredPromptBuilder（分层组装器） ====================

    @Test
    void layeredBuilderOrdersByStablePrefix() {
        String prompt = LayeredPromptBuilder.builder()
                .section(PromptLayers.TASK, "任务内容")
                .section(PromptLayers.ROLE_PROFILE, "角色档案")
                .section(PromptLayers.SYSTEM, "系统锚定")
                .section(PromptLayers.OUTPUT_FORMAT, "输出格式")
                .build();
        assertNotNull(prompt);
        // 固定层序：SYSTEM → ROLE_PROFILE → … → TASK → OUTPUT_FORMAT（与添加顺序无关）
        int sys = prompt.indexOf("—— SYSTEM ——");
        int role = prompt.indexOf("—— ROLE_PROFILE ——");
        int task = prompt.indexOf("—— TASK ——");
        int out = prompt.indexOf("—— OUTPUT_FORMAT ——");
        assertTrue(sys >= 0 && role > sys && task > role && out > task, "按变化频率从低到高排列");
        assertTrue(prompt.contains("系统锚定") && prompt.contains("角色档案")
                && prompt.contains("任务内容") && prompt.contains("输出格式"));
    }

    @Test
    void layeredBuilderSkipsBlankAndDuplicatesBlocks() {
        String prompt = LayeredPromptBuilder.builder()
                .section(PromptLayers.SYSTEM, "   ")
                .section(PromptLayers.SYSTEM, null)
                .section(PromptLayers.SYSTEM, "世界规则")
                .section(PromptLayers.SYSTEM, "角色锚定")
                .build();
        assertTrue(prompt.contains("世界规则"));
        assertTrue(prompt.contains("角色锚定"));
        assertFalse(prompt.contains("—— SYSTEM ——\n\n—— SYSTEM ——"), "同层多块不应重复段标题");
    }

    // ==================== PromptNormalizer（渲染期规范化） ====================

    @Test
    void normalizerUnifiesOutputRuleWording() {
        String out = PromptNormalizer.normalize("请严格输出一个 JSON 对象，禁止输出任何其他文字。");
        assertTrue(out.contains("请严格输出 JSON 对象，禁止输出任何其他文字。"));
        assertTrue(out.contains("DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
    }

    @Test
    void normalizerUnifiesSectionTitles() {
        String out = PromptNormalizer.normalize("── 身份 ──\n你好\n【性格】\n特质\n-- 目标 --\nx");
        assertTrue(out.contains("—— 身份 ——"));
        assertTrue(out.contains("—— 性格 ——"));
        assertTrue(out.contains("—— 目标 ——"));
    }

    @Test
    void normalizerLeavesPlainTextUntouched() {
        String text = "你是沈砚秋，青禾村村长。";
        assertEquals(text, PromptNormalizer.normalize(text));
    }

    @Test
    void specRegistryProvidesDefaultsAndRegistered() {
        assertEquals(PromptContractType.PLAIN_TEXT, PromptSpecRegistry.contractOf("dialog_system"));
        assertEquals(300, PromptSpecRegistry.maxOutputTokensOf("dialog_system"));
        assertEquals(PromptContractType.DECISION_JSON, PromptSpecRegistry.contractOf("action_gen"));
        assertEquals(250, PromptSpecRegistry.maxOutputTokensOf("action_gen"));
        assertEquals(0.4, PromptSpecRegistry.temperatureOf("action_gen"));
        assertEquals(PromptContractType.SCHEMA_JSON, PromptSpecRegistry.contractOf("unknown_scene"));
        assertTrue(PromptSpecRegistry.versionOf("dialog_system") >= 1);
        assertFalse(PromptSpecRegistry.all().isEmpty());
    }
}
