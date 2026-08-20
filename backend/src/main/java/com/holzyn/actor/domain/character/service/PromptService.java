package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 渲染服务（P2 起模板表驱动）。
 * <p>职责：基于 actor_prompt_template 表（PromptTemplateService）渲染各类 Prompt：
 * ① 角色卡生成（character_card_gen）；② 对话系统（dialog_system，从角色卡 persona_json 渲染）；
 * ③ 群聊编排（group_orchestrator）；④ 世界事件（world_event）；⑤ 行动生成（action_gen）。
 * 渲染时传入 userId，遵循「用户覆盖 > 内置」的模板解析规则。</p>
 * <p>所属模块：service/character（角色/角色卡子域）</p>
 */
@Component
public class PromptService {

    /** Jackson 解析器（用于读取 persona_json 渲染对话 Prompt） */
    private final ObjectMapper objectMapper;

    /** Prompt 模板服务（内置种子 + 用户覆盖解析） */
    private final PromptTemplateService templateService;

    /**
     * 构造函数：注入 Jackson 解析器与模板服务。
     *
     * @param objectMapper    Spring 注入的 Jackson ObjectMapper
     * @param templateService Prompt 模板服务
     */
    public PromptService(ObjectMapper objectMapper, PromptTemplateService templateService) {
        this.objectMapper = objectMapper;
        this.templateService = templateService;
    }

    /**
     * 渲染角色卡生成 Prompt（character_card_gen 模板）。
     *
     * @param userId            归属用户 ID（用于解析模板覆盖）
     * @param projectId         项目 ID（用于解析项目级模板覆盖，可空）
     * @param worldSettingText  世界观自由文本（可空）
     * @param characterInput    角色档案输入（姓名/头衔/类型/简介/背景/经历/社会关系）
     * @return 完整生成 Prompt
     */
    public String buildCharacterCardGenPrompt(Long userId, Long projectId, String worldSettingText, String characterInput) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", worldSettingText == null || worldSettingText.isBlank()
                ? "（未提供，请合理泛化设定）" : worldSettingText);
        ph.put("character_input", characterInput == null || characterInput.isBlank()
                ? "（未提供详细档案，请给出基础合理的设定）" : characterInput);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_CARD_GEN, ph);
    }

    /**
     * 从角色卡 persona_json 渲染对话系统 Prompt（dialog_system 模板）。
     *
     * @param userId      归属用户 ID
     * @param projectId   项目 ID（用于解析项目级模板覆盖，可空）
     * @param worldName   世界观名称（可空）
     * @param personaJson 结构化角色卡 JSON 文本
     * @return 渲染后的系统 Prompt；personaJson 无法解析时返回降级提示
     */
    public String renderDialogSystemPrompt(Long userId, Long projectId, String worldName, String personaJson) {
        try {
            JsonNode p = objectMapper.readTree(personaJson);
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("name", p.path("identity").path("name").asText("角色"));
            ph.put("title", p.path("identity").path("title").asText("无头衔"));
            ph.put("world_name", worldName == null || worldName.isBlank() ? "未知" : worldName);
            ph.put("species", p.path("identity").path("species").asText("未知种族"));
            ph.put("occupation", p.path("identity").path("occupation").asText("未知职业"));
            ph.put("affiliation", p.path("identity").path("affiliation").asText("未知势力"));
            ph.put("personality", blank(join(p, "personality", "traits")));
            ph.put("values", blank(join(p, "personality", "values")));
            ph.put("quirks", blank(join(p, "personality", "quirks")));
            ph.put("history", blank(p.path("background").path("history").asText("")));
            ph.put("goals", blank(join(p, "background", "goals")));
            ph.put("wounds", blank(join(p, "background", "wounds")));
            ph.put("relations", blank(joinRelations(p)));
            ph.put("tone", p.path("speechStyle").path("tone").asText("自然"));
            ph.put("vocabulary", blank(p.path("speechStyle").path("vocabulary").asText("")));
            ph.put("catchphrases", blank(join(p, "speechStyle", "catchphrases")));
            ph.put("taboos", blank(join(p, "speechStyle", "taboos")));
            ph.put("knows", blank(join(p, "knowledge", "knows")));
            ph.put("notKnows", blank(join(p, "knowledge", "notKnows")));
            ph.put("behaviors", blank(join(p, "behaviorPatterns")));
            return templateService.render(userId, projectId, PromptTemplateService.CODE_DIALOG, ph);
        } catch (Exception e) {
            // 角色卡解析失败时返回降级系统提示（不应阻断对话）
            return "你是这个世界中的一位角色。请基于上下文保持角色身份进行对话。";
        }
    }

    /**
     * 渲染群聊编排 Prompt（group_orchestrator 模板）：成员摘要 + 最近上下文 + 输出发言人 JSON。
     *
     * @param userId         归属用户 ID
     * @param projectId      项目 ID（用于解析项目级模板覆盖，可空）
     * @param membersSummary 全体成员角色摘要（姓名/人设/关系/重要性）
     * @param context        最近对话上下文
     * @return 编排 Prompt
     */
    public String buildGroupOrchestratorPrompt(Long userId, Long projectId, String membersSummary, String context) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("members_summary", membersSummary == null ? "" : membersSummary);
        ph.put("context", context == null ? "" : context);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_GROUP, ph);
    }

    /**
     * 渲染世界事件生成 Prompt（world_event 模板）：世界观 + 情境 + 输出事件 JSON。
     *
     * @param userId        归属用户 ID
     * @param projectId     项目 ID（用于解析项目级模板覆盖，可空）
     * @param worldSetting  世界观自由文本（可空）
     * @param context       当前情境（项目/会话上下文）
     * @return 事件生成 Prompt
     */
    public String buildWorldEventPrompt(Long userId, Long projectId, String worldSetting, String context) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", worldSetting == null || worldSetting.isBlank() ? "（未提供）" : worldSetting);
        ph.put("context", context == null ? "" : context);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_WORLD_EVENT, ph);
    }

    /**
     * 渲染行动生成 Prompt（action_gen 模板）：角色人设摘要 + 当前情境 + 输出行动决策 JSON。
     *
     * @param userId         归属用户 ID
     * @param projectId      项目 ID（用于解析项目级模板覆盖，可空）
     * @param personaSummary 角色人设摘要（行为模式/重要性/目标）
     * @param situation      当前情境（时间/地点/事件/对话上下文）
     * @return 行动生成 Prompt
     */
    public String buildActionGenPrompt(Long userId, Long projectId, String personaSummary, String situation) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("persona_summary", personaSummary == null ? "" : personaSummary);
        ph.put("situation", situation == null ? "" : situation);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_ACTION, ph);
    }

    /**
     * 拼接 JSON 数组节点为逗号分隔文本。
     *
     * @param root  JSON 根节点
     * @param first 第一级字段名
     * @param rest  后续字段名（支持一至二级）
     * @return 逗号分隔字符串（空时返回空串）
     */
    private String join(JsonNode root, String first, String... rest) {
        JsonNode node = root.path(first);
        for (String key : rest) {
            if (node == null || node.isMissingNode() || (!node.isArray() && !node.isObject())) return "";
            node = node.path(key);
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            node.forEach(n -> {
                if (sb.length() > 0) sb.append("、");
                sb.append(n.asText());
            });
            return sb.toString();
        }
        return node.isMissingNode() ? "" : node.asText("");
    }

    /**
     * 拼接社会关系数组（with：type：attitude）。
     *
     * @param root JSON 根节点
     * @return 关系摘要文本
     */
    private String joinRelations(JsonNode root) {
        JsonNode arr = root.path("relations");
        if (!arr.isArray() || arr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        arr.forEach(r -> {
            if (sb.length() > 0) sb.append("；");
            sb.append(r.path("with").asText("?")).append("（").append(r.path("type").asText("未知关系"))
                    .append("，态度：").append(r.path("attitude").asText("一般")).append("）");
        });
        return sb.toString();
    }

    /**
     * 空串归一化。
     *
     * @param s 原始字符串
     * @return 空或空白时返回占位「无」
     */
    private String blank(String s) {
        return (s == null || s.isBlank()) ? "无" : s;
    }
}