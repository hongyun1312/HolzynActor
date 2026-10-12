/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 渲染服务。
 * <p>职责：基于 actor_prompt_template 表（PromptTemplateService）渲染各类 Prompt：
 * ① 角色卡生成（character_card_gen）；② 对话系统（dialog_system，从角色卡 persona_json 渲染）；
 * ③ 群聊编排（group_orchestrator）；④ 世界事件（world_event）；⑤ 行动生成（action_gen）。
 * 渲染时传入 userId，遵循「用户覆盖 > 内置」的模板解析规则。</p>
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
     * @param objectMapper Spring 注入的 Jackson ObjectMapper
     *
     * @param templateService Prompt 模板服务
     */
    public PromptService(ObjectMapper objectMapper, PromptTemplateService templateService) {
        this.objectMapper = objectMapper;
        this.templateService = templateService;
    }

    /** ToM 推断深度注入开关（realtime-decision.coordination.tom-inject-enabled；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.realtime-decision.coordination.tom-inject-enabled:true}")
    private boolean tomInjectEnabled = true;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口：action_gen 前置 段1A【世界观】；world_event / group_orchestrator 前置
     * 段1A【世界观】+ 段2D【当前世界动态】（`worldContextPrefix`，世界视角）。
     * null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private AiContextInjector aiContextInjector;

    /**
     * 渲染角色卡生成 Prompt（character_card_gen 模板）。
     *
     * @param userId 归属用户 ID（用于解析模板覆盖）
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param worldSettingText 世界观自由文本（可空）
     *
     * @param characterInput 角色档案输入（姓名/头衔/类型/简介/背景/经历/社会关系）
     *
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
     * 从档案 persona_json 渲染对话系统 Prompt（{@code dialog_system} 模板）。
     * <p>决策与协调：按角色 OCEAN 的宜人性×神经质计算 ToM 推断深度档位，
     * 以 {{tom_section}} 占位符注入【推断深度】段。</p>
     * <p><b>⚠️ 现状：本方法目前在应用内没有调用方。</b>它原先只被
     * {@code CharacterCardService} 用来生成"角色卡里存下来的 system_prompt"，而 已把
     * "建档时定稿一段 system_prompt"这个概念**物理废弃**：对话系统 Prompt 改为运行时由
     * {@code renderShardsForScene(..., "dialog_system")} 的档案分片（S1 S2 S3 S7 S8 S10）动态组装。
     * 保留本方法是为了：(1) 它仍是 {@code PromptTemplateService.CODE_DIALOG} 模板的唯一渲染入口
     * （模板本身仍在设置页可见/可改）；(2) 外部集成若只想拿一段"静态对话系统提示词"仍可用它。
     * <b>后续若确认无外部消费者，应连同 {@code v2text/v2join/joinRelations} 一并删除</b>，
     * 避免出现"两个各读一份档案的对话 Prompt 组装器"这种会互相漂移的双份实现。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param worldName 世界观名称（可空）
     *
     * @param personaJson 结构化档案 JSON 文本（v2：{@code dims.*}）
     *
     * @return 渲染后的系统 Prompt；personaJson 无法解析时返回降级提示
     */
    public String renderDialogSystemPrompt(Long userId, Long projectId, String worldName, String personaJson) {
        try {
            JsonNode p = objectMapper.readTree(personaJson);
            Map<String, Object> ph = new LinkedHashMap<>();
            // ====================：占位符取值全部改到 v2 档案结构（dims.*） ====================
            // 变更要点： 后 persona_json 是 11 维 v2（dims.identity/personality/...），旧 v1 平铺键
            // （identity.name / personality.traits / background.goals / behaviorPatterns / speechStyle.* /
            // knowledge.*）已不存在——继续按旧键取值会得到**全空占位符且不报错**（静默退化）。
            // 故此处逐项映射到 v2：
            // ①身份与社会 → dims.identity.*（姓名/种族/职业/归属是 actor_profile 结构列口径，档案内不重复存储，
            // 故这四个仅当 JSON 顶层带出时取值，否则保留原兜底文案）
            // ④人格 → dims.personality.traits_summary
            // ⑤价值观 → dims.values.value_weights（权重键）/ life_goals / current_goals
            // ⑦语言 → dims.voice.register / vocabulary_keys / catchphrases / taboo_topics
            // ⑥能力知识 → dims.ability.knowledge_domains / knowledge_blind_spots
            // ⑧习惯 → dims.habits.quirks / micro_movements / ritual_behaviors
            // ⑨经历 → dims.experience.origin / key_events / traumas / unresolved_knots
            ph.put("name", firstNonBlank(v2text(p, "name"), v2text(p, "dims", "identity", "aliases"), "角色"));
            ph.put("title", firstNonBlank(v2text(p, "dims", "identity", "identity_title"), "无头衔"));
            ph.put("world_name", worldName == null || worldName.isBlank() ? "未知" : worldName);
            ph.put("species", firstNonBlank(v2text(p, "race"), "未知种族"));
            ph.put("occupation", firstNonBlank(v2text(p, "occupation"), "未知职业"));
            ph.put("affiliation", firstNonBlank(v2text(p, "affiliation"), "未知势力"));
            ph.put("personality", blank(v2text(p, "dims", "personality", "traits_summary")));
            ph.put("values", blank(v2join(p, "dims", "values", "value_weights")));
            ph.put("quirks", blank(v2join(p, "dims", "habits", "quirks")));
            ph.put("history", blank(v2text(p, "dims", "experience", "origin")));
            ph.put("goals", blank(firstNonBlank(v2join(p, "dims", "values", "life_goals"),
                    v2join(p, "dims", "values", "current_goals"))));
            ph.put("wounds", blank(firstNonBlank(v2join(p, "dims", "experience", "traumas"),
                    v2join(p, "dims", "experience", "unresolved_knots"))));
            ph.put("relations", blank(joinRelations(p)));
            ph.put("tone", firstNonBlank(v2text(p, "dims", "voice", "register"), "自然"));
            ph.put("vocabulary", blank(v2join(p, "dims", "voice", "vocabulary_keys")));
            ph.put("catchphrases", blank(v2join(p, "dims", "voice", "catchphrases")));
            ph.put("taboos", blank(v2join(p, "dims", "voice", "taboo_topics")));
            ph.put("knows", blank(v2join(p, "dims", "ability", "knowledge_domains")));
            ph.put("notKnows", blank(v2join(p, "dims", "ability", "knowledge_blind_spots")));
            ph.put("behaviors", blank(firstNonBlank(v2join(p, "dims", "habits", "micro_movements"),
                    v2join(p, "dims", "habits", "ritual_behaviors"))));
            // ：ToM 推断深度段（按 OCEAN 宜人性×神经质；门控由模板占位符空值天然降级）
            ph.put("tom_section", tomInjectEnabled ? com.holzyn.actor.domain.agent.runtime.TomInference.segmentOf(personaJson) : "");
            return templateService.render(userId, projectId, PromptTemplateService.CODE_DIALOG, ph);
        } catch (Exception e) {
            // 角色卡解析失败时返回降级系统提示（不应阻断对话）
            return "你是这个世界中的一位角色。请基于上下文保持角色身份进行对话。";
        }
    }

    /**
     * 读 v2 档案中的文本字段（路径逐级下钻；缺失/空白返回空串）。
     *
     * @param p v2 档案 JSON 根
     *
     * @param path 字段路径（如 {@code "dims","personality","traits_summary"}）
     *
     * @return 文本值；缺失返回空串
     */
    private static String v2text(JsonNode p, String... path) {
        JsonNode n = p;
        for (String seg : path) {
            if (n == null) {
                return "";
            }
            n = n.path(seg);
        }
        return n == null || n.isMissingNode() || n.isNull() ? "" : n.asText("");
    }

    /**
     * 读 v2 档案中的数组字段并连接为文本（也接受"对象/文本"形态）。
     * <p>数组 → 元素文本用「、」连接；对象（如 {@code value_weights} 权重表）→ 取键名连接；
     * 文本 → 原样返回。缺失返回空串。</p>
     *
     * @param p v2 档案 JSON 根
     *
     * @param path 字段路径
     *
     * @return 连接后的文本；缺失返回空串
     */
    private static String v2join(JsonNode p, String... path) {
        JsonNode n = p;
        for (String seg : path) {
            if (n == null) {
                return "";
            }
            n = n.path(seg);
        }
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(e -> {
                String t = e.isObject() ? e.path("what").asText("") : e.asText("");
                if (!t.isBlank()) {
                    out.add(t);
                }
            });
        } else if (n.isObject()) {
            // Jackson 3：对象键迭代统一用 properties()（与 PersonaBits 同口径）
            n.properties().forEach(e -> out.add(e.getKey()));
        } else {
            String t = n.asText("");
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return String.join("、", out);
    }

    /** 取第一个非空白字符串（全部为空时返回 fallback）。 */
    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return fallback;
    }

    /** 取第一个非空白字符串（无 fallback 时返回空串）。 */
    private static String firstNonBlank(String a, String b) {
        return firstNonBlank(a, b, "");
    }

    /**
     * 渲染群聊编排 Prompt（group_orchestrator 模板）：成员摘要 + 最近上下文 + 输出发言人 JSON。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param membersSummary 全体成员角色摘要（姓名/人设/关系/重要性）
     *
     * @param context 最近对话上下文
     *
     * @return 编排 Prompt
     */
    public String buildGroupOrchestratorPrompt(Long userId, Long projectId, String membersSummary, String context) {
        return buildGroupOrchestratorPrompt(userId, projectId, membersSummary, context, null);
    }

    /**
     * 渲染群聊编排 Prompt（group_orchestrator 模板， 说话意愿修复：新增「本场背景」段）：
     * 成员摘要 + <b>本场背景（本次交谈的缘由）</b>+ 最近上下文 + 输出发言人 JSON。
     * <p>背景 = 触发本次交谈的缘由（世界事件文本 / 日常自发闲聊说明）；此前该信息只进「退场收尾编排」，
     * 发言人选评估完全看不到——开场轮（聊天记录为空）时模型无据可依，只能给出 desire=1「无人想接话」，
     * 导致剧集 1 节拍即收尾。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param membersSummary 全体成员角色摘要（姓名/人设/关系/重要性）
     *
     * @param context 最近对话上下文
     *
     * @param background 本场背景（可空=「日常自发交谈，无特定缘由」兜底文案）
     *
     * @return 编排 Prompt
     */
    public String buildGroupOrchestratorPrompt(Long userId, Long projectId, String membersSummary, String context,
                                               String background) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("members_summary", membersSummary == null ? "" : membersSummary);
        ph.put("context", context == null ? "" : context);
        ph.put("background", background == null || background.isBlank()
                ? "（本场为日常自发交谈，无特定缘由）" : background);
        String rendered = templateService.render(userId, projectId, PromptTemplateService.CODE_GROUP, ph);
        // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】——群聊编排前置
        // 段1A【世界观】+ 段2D【当前世界动态】（成员身份由 members_summary 承担，无单一角色卡）；
        // 未装配/无数据 → 原样返回（fail-open 零阻断）
        String prefix = aiContextInjector == null ? "" : aiContextInjector.worldContextPrefix(projectId, null);
        return prefix.isBlank() ? rendered : prefix + "\n\n" + rendered;
    }

    /**
     * 渲染世界事件生成 Prompt（world_event 模板）：世界观 + 情境 + 输出事件 JSON。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param worldSetting 世界观自由文本（可空）
     *
     * @param context 当前情境（项目/会话上下文）
     *
     * @return 事件生成 Prompt
     */
    public String buildWorldEventPrompt(Long userId, Long projectId, String worldSetting, String context) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", worldSetting == null || worldSetting.isBlank() ? "（未提供）" : worldSetting);
        ph.put("context", context == null ? "" : context);
        String rendered = templateService.render(userId, projectId, PromptTemplateService.CODE_WORLD_EVENT, ph);
        // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】——世界事件生成前置
        // 段1A【世界观】+ 段2D【当前世界动态】；未装配/无数据 → 原样返回（fail-open 零阻断）
        String prefix = aiContextInjector == null ? "" : aiContextInjector.worldContextPrefix(projectId, null);
        return prefix.isBlank() ? rendered : prefix + "\n\n" + rendered;
    }

    /**
     * 渲染跨日世界事件「AI 润色」Prompt（world_event_polish 模板，B3 T-4 双通道第二通道）：
     * 世界观 + 机制生成草稿 + 情境 → 输出成文事件 JSON。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param worldSetting 世界观自由文本（可空）
     *
     * @param draft 机制生成的事件草稿（必填，润色以此为基础）
     *
     * @param context 当前情境（引擎推进游戏时刻/第几天等）
     *
     * @return 润色 Prompt
     */
    public String buildWorldEventPolishPrompt(Long userId, Long projectId, String worldSetting,
                                              String draft, String context) {
        return buildWorldEventPolishPrompt(userId, projectId, worldSetting, draft, context, "");
    }

    /**
     * 渲染跨日世界事件「AI 润色」Prompt：
     * 在 5 参基础上追加世界氛围片段注入（story-plan 工作流产出，事件叙述语气贴合世界气质）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param worldSetting 世界观自由文本（可空）
     *
     * @param draft 机制生成的事件草稿（必填，润色以此为基础）
     *
     * @param context 当前情境（引擎推进游戏时刻/第几天等）
     *
     * @param atmosphere 世界氛围片段（可空；模板 {{atmosphere}} 占位符，空=不注入）
     *
     * @return 润色 Prompt
     */
    public String buildWorldEventPolishPrompt(Long userId, Long projectId, String worldSetting,
                                              String draft, String context, String atmosphere) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", worldSetting == null || worldSetting.isBlank() ? "（未提供）" : worldSetting);
        ph.put("draft", draft == null || draft.isBlank() ? "（草稿缺失）" : draft);
        ph.put("context", context == null ? "" : context);
        ph.put("atmosphere", atmosphere == null ? "" : atmosphere);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_WORLD_EVENT_POLISH, ph);
    }

    /**
     * 渲染行动生成 Prompt（action_gen 模板）：角色人设摘要 + 当前情境 + 输出行动决策 JSON。
     * <p>决策与协调：新增 tomSection 参数——按角色 OCEAN 宜人性×神经质
     * 生成的【推断深度】段（{{tom_section}} 占位符；空=不注入）；前瞻推演段由调用方拼入
     * situation（同调用插段，零新增 AI 调用）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param personaSummary 角色人设摘要（行为模式/重要性/目标）
     *
     * @param situation 当前情境（时间/地点/事件/对话上下文）
     *
     * @param tomSection ToM 推断深度段（可空=不注入）
     *
     * @return 行动生成 Prompt
     */
    public String buildActionGenPrompt(Long userId, Long projectId, String personaSummary, String situation,
                                       String tomSection) {
        return buildActionGenPrompt(userId, projectId, personaSummary, situation, tomSection, "");
    }

    /**
     * 渲染行动生成 Prompt（action_gen 模板）：角色人设摘要 + 当前情境 + 输出行动决策 JSON。
     * <p>决策与协调：6 参版本新增 proactiveMemory 参数——主动记忆检索命中的
     * 【主动想起】段（{{proactive_memory}} 占位符；空=不注入）；旧 5 参签名委托本方法
     * 传空串（行为不变）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param personaSummary 角色人设摘要（行为模式/重要性/目标）
     *
     * @param situation 当前情境（时间/地点/事件/对话上下文）
     *
     * @param tomSection ToM 推断深度段（可空=不注入）
     *
     * @param proactiveMemory 主动记忆检索【主动想起】段（可空=不注入）
     *
     * @return 行动生成 Prompt
     */
    public String buildActionGenPrompt(Long userId, Long projectId, String personaSummary, String situation,
                                       String tomSection, String proactiveMemory) {
        return buildActionGenPrompt(userId, projectId, personaSummary, situation, tomSection, proactiveMemory, "");
    }

    /**
     * 渲染行动生成 Prompt（action_gen 模板）：角色人设摘要 + 当前情境 + 输出行动决策 JSON。
     * <p>决策与协调：7 参版本新增 strategyPreference 参数——MUSE 策略切换（P1 ①-3）命中的
     * 【策略偏好】段（{{strategy_preference}} 占位符；空=不注入）；旧 6 参签名委托本方法
     * 传空串（行为不变）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param personaSummary 角色人设摘要（行为模式/重要性/目标）
     *
     * @param situation 当前情境（时间/地点/事件/对话上下文）
     *
     * @param tomSection ToM 推断深度段（可空=不注入）
     *
     * @param proactiveMemory 主动记忆检索【主动想起】段（可空=不注入）
     *
     * @param strategyPreference MUSE 策略切换【策略偏好】段（可空=不注入）
     *
     * @return 行动生成 Prompt
     */
    public String buildActionGenPrompt(Long userId, Long projectId, String personaSummary, String situation,
                                       String tomSection, String proactiveMemory, String strategyPreference) {
        return buildActionGenPrompt(userId, projectId, personaSummary, situation, tomSection,
                proactiveMemory, strategyPreference, "");
    }

    /**
     * 渲染行动生成 Prompt（action_gen 模板）：角色人设摘要 + 当前情境 + 输出行动决策 JSON。
     * <p>决策与协调：8 参版本新增 worldOutlook 参数——潜在世界预测注入（P1 ③-2）的
     * 【未来一天世界走向】段（{{world_outlook}} 占位符；空=不注入）；旧 7 参签名委托本方法
     * 传空串（行为不变）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param personaSummary 角色人设摘要（行为模式/重要性/目标）
     *
     * @param situation 当前情境（时间/地点/事件/对话上下文）
     *
     * @param tomSection ToM 推断深度段（可空=不注入）
     *
     * @param proactiveMemory 主动记忆检索【主动想起】段（可空=不注入）
     *
     * @param strategyPreference MUSE 策略切换【策略偏好】段（可空=不注入）
     *
     * @param worldOutlook 潜在世界预测【未来一天世界走向】段（可空=不注入）
     *
     * @return 行动生成 Prompt
     */
    public String buildActionGenPrompt(Long userId, Long projectId, String personaSummary, String situation,
                                       String tomSection, String proactiveMemory, String strategyPreference,
                                       String worldOutlook) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("persona_summary", personaSummary == null ? "" : personaSummary);
        ph.put("situation", situation == null ? "" : situation);
        ph.put("tom_section", tomSection == null ? "" : tomSection);
        ph.put("proactive_memory", proactiveMemory == null || proactiveMemory.isBlank() ? "" : proactiveMemory);
        ph.put("strategy_preference", strategyPreference == null || strategyPreference.isBlank() ? "" : strategyPreference);
        // 决策与协调：潜在世界预测注入（P1 ③-2）——【未来一天世界走向】段（{{world_outlook}}；
        // 空=不注入；渲染期残留占位符自动抹除）
        ph.put("world_outlook", worldOutlook == null || worldOutlook.isBlank() ? "" : worldOutlook);
        // Prompt 工程：action_gen 模板 {{foresight_requirement}} 占位符——
        // 默认不注入（渲染期残留占位符自动抹除）；前瞻推演要求由 ForesightService 阈值触发时
        // 通过 situation【前瞻推演】段注入
        ph.put("foresight_requirement", "");
        String rendered = templateService.render(userId, projectId, PromptTemplateService.CODE_ACTION, ph);
        // 批6 收口（action_gen 属于批3 决策与感知·角色视角）：前置 段1A【世界观】稳定简报
        // （世界常识摘要 300-500 字，缓存友好）——决策请求骨架补齐 段1A+1C+2D+2E：
        // 段1C 角色核心卡=模板 {{persona_summary}}、段2D/2E=情境（STIMULUS 前置/periodic 感知漏斗），
        // 唯独 段1A 世界观此前缺失；未装配/无数据 → 原样返回（fail-open 零阻断）
        String worldBrief = aiContextInjector == null ? "" : aiContextInjector.worldBrief(projectId);
        if (worldBrief == null || worldBrief.isBlank()) {
            return rendered;
        }
        return "【世界观】\n" + worldBrief + "\n\n" + rendered;
    }

    /**
     * 渲染恢复决策 Prompt：被打断任务 + 打断刺激 +
     * 人物信息（性格/价值观/经历）+ 当前状态 → 输出 resume/adjust/abandon 三态恢复决策 JSON。
     * <p>决策与协调：新增 tomSection 参数——【推断深度】段（{{tom_section}}；
     * 空=不注入）。</p>
     *
     * @param userId 归属用户 ID（用于解析模板覆盖）
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param characterInfo 人物信息（姓名/头衔/性格/价值观/经历摘要，可空）
     *
     * @param stimulus 打断刺激文本（可空）
     *
     * @param interruptedTask 被打断的任务描述（含地点与已持续时长，可空）
     *
     * @param currentState 当前状态（角色状态摘要，可空）
     *
     * @param tomSection ToM 推断深度段（可空=不注入）
     *
     * @return 恢复决策 Prompt
     */
    public String buildInterruptResumePrompt(Long userId, Long projectId, String characterInfo,
                                             String stimulus, String interruptedTask, String currentState,
                                             String tomSection) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("character_info", characterInfo == null || characterInfo.isBlank()
                ? "（未提供，请按一般合理人物推断）" : characterInfo);
        ph.put("stimulus", stimulus == null || stimulus.isBlank() ? "（一次突发事件）" : stimulus);
        ph.put("interrupted_task", interruptedTask == null || interruptedTask.isBlank()
                ? "（未记录明确任务）" : interruptedTask);
        ph.put("current_state", currentState == null || currentState.isBlank()
                ? "（当前无特别状态）" : currentState);
        ph.put("tom_section", tomSection == null ? "" : tomSection);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_INTERRUPT_RESUME, ph);
    }

    /**
     * 渲染打断前探针 Prompt：人物信息（全量个性段）+
     * 当前活动 + 打断刺激 → 输出 interrupt true/false 探针决策 JSON。
     *
     * @param userId 归属用户 ID（用于解析模板覆盖）
     *
     * @param projectId 项目 ID（用于解析项目级模板覆盖，可空）
     *
     * @param characterInfo 人物信息（【角色个性】段：persona+L3+brainProfile+关系+时间感，可空）
     *
     * @param currentActivity 当前活动文本（可空）
     *
     * @param stimulus 打断刺激文本（可空）
     *
     * @return 探针决策 Prompt
     */
    public String buildInterruptProbePrompt(Long userId, Long projectId, String characterInfo,
                                            String currentActivity, String stimulus) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("character_info", characterInfo == null || characterInfo.isBlank()
                ? "（未提供，请按一般合理人物推断）" : characterInfo);
        ph.put("current_activity", currentActivity == null || currentActivity.isBlank()
                ? "（手头无事）" : currentActivity);
        ph.put("stimulus", stimulus == null || stimulus.isBlank() ? "（一次突发事件）" : stimulus);
        return templateService.render(userId, projectId, PromptTemplateService.CODE_INTERRUPT_PROBE, ph);
    }

    /**
     * 拼接 JSON 数组节点为逗号分隔文本。
     *
     * @param root JSON 根节点
     *
     * @param first 第一级字段名
     *
     * @param rest 后续字段名（支持一至二级）
     *
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
     *
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
     *
     * @return 空或空白时返回占位「无」
     */
    private String blank(String s) {
        return (s == null || s.isBlank()) ? "无" : s;
    }
}