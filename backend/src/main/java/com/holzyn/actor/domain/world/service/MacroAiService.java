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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宏观社会演化 · AI 服务。
 * <p>职责：宏观层两处 AI 决策点，共用「独立每日 AI 预算」（/：阈值触发 AI 叙事
 * + 重大行动 AI 判定；预算耗尽/失败一律规则兜底零阻断）+ <b>AI 强度四档闸门</b>
 * （AiIntensityGate：programmatic 零 AI / light 预算减半 / full 标准 / max 全 AI 必调无预算）：</p>
 * <ul>
 * <li><b>重大行动 AI 判定</b>（scene=macro_impact）：NPC 重大行动（urgency≥4）异步判定本次
 * 行动对宏观的影响——规则映射表的补充叠加；世界观背景按模板语义注入
 * ；</li>
 * <li><b>阈值触发 AI 叙事</b>（scene=macro_event）：重大宏观变化（粮价波动&gt;20%、势力位移&gt;30%、
 * 极端季节事件/舆论转向/科技突破/信仰危机/民变风险/新习俗兴起等）→ LLM 生成叙事化
 * 宏观事件；<b>因果链上下文</b>（季节/天气/收成/民怨/入账摘要）注入提示词——AI 叙事
 * 体现 全链联动的因果。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroAiService {

    /** 重大行动 AI 判定场景（trace/用量 scene 记账） */
    public static final String SCENE_MACRO_IMPACT = "macro_impact";
    /** 阈值触发 AI 叙事场景（trace/用量 scene 记账） */
    public static final String SCENE_MACRO_EVENT = "macro_event";
    /** 中观指令 AI 生成场景 */
    public static final String SCENE_MESO_DIRECTIVE = "meso_directive";
    /** 二阶涌现 AI 叙事场景 */
    public static final String SCENE_MACRO_EMERGENCE = "macro_emergence";

    private final MacroProperties props;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final AiIntensityGate aiIntensityGate;
    private final MacroStateService macroStateService;
    private final ObjectMapper objectMapper;

    /**
     * 世界氛围片段读取服务（story-plan 工作流 _06 字段注入——保持构造器签名
     * 不变；直接 new 时为空 → 宏观 AI 叙事不注入世界氛围，零破坏）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StoryAtmosphereService storyAtmosphereService;

    /**
     * 统一 AI 上下文注入器（宏观属「世界视角」生成——非角色视角，无角色核心卡；
     * 仅补 段2D 当前世界动态 = worldDynamicsLine（世界历时刻/季节/天气），
     * AI 叙事/判定知道此刻世界处于什么时节天气；字段注入 + @Lazy 破环；
     * null=未装配跳过注入，不影响原调用）。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 独立每日 AI 预算计数：projectId → {gameDay, used}（进程内存态；重启丢失可接受） */
    private final Map<Long, long[]> dailyBudget = new ConcurrentHashMap<>();

    /** 重大行动 AI 判定结果 */
    public record ImpactJudgment(boolean hasImpact, String targetMacro, String indicator,
                                 double delta, String reason) {
    }

    /** AI 叙事结果 */
    public record Narrative(String title, String content) {
    }

    /** 中观指令 AI 生成结果 */
    public record DirectiveGen(String tendencyBias, Double tendencyStrength, List<String> forbidden,
                               Boolean soft, String constraintReason, String resourceHint, String note) {
    }

    // ==================== 中观指令 AI 生成 ====================

    /**
     * 组织差异化指令 AI 生成（档位联动：max 全 AI 必调不设预算 / light·full 重要事件 AI 消耗
     * 共享每日预算；失败/耗尽/纯规则档返回 null=调用方规则模板兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @param eventType 宏观事件类型（大旱/粮价暴涨/民变风险…）
     *
     * @param severity 事件严重度
     *
     * @param groupName 组织名
     *
     * @param groupType 组织类型
     *
     * @param groupTags 组织标签
     *
     * @param communityName 社区名（模板语义）
     *
     * @param coreResource 核心资源名（模板语义）
     *
     * @return 生成结果（null=兜底规则模板）
     */
    public DirectiveGen generateDirective(Long projectId, Long gameDay, String eventType, Integer severity,
                                          String groupName, String groupType, List<String> groupTags,
                                          String communityName, String coreResource) {
        boolean max = aiIntensityGate != null && aiIntensityGate.isMax(projectId);
        if (!aiAllowed(projectId)) {
            return null;
        }
        // max 档预算不设上限；light/full 消耗共享每日预算（耗尽转规则兜底）
        if (!max && !tryConsumeDailyAi(projectId, gameDay)) {
            return null;
        }
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("community_name", communityName == null ? "" : communityName);
        ph.put("core_resource", coreResource == null ? "" : coreResource);
        ph.put("event_type", eventType == null ? "" : eventType);
        ph.put("severity", severity == null ? 3 : severity);
        ph.put("group_name", groupName == null ? "" : groupName);
        ph.put("group_type", groupType == null ? "" : groupType);
        ph.put("group_tags", groupTags == null || groupTags.isEmpty() ? "（无）" : String.join("、", groupTags));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return AiCallContext.run(SCENE_MESO_DIRECTIVE, null, attempt, () -> {
                    String prompt = promptTemplateService.render(1L, projectId,
                            PromptTemplateService.CODE_MESO_DIRECTIVE, ph);
                    // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】——中观指令生成
                    // 前置 段1A【世界观】+ 段2D 当前世界动态（无单一角色，仅世界口径）
                    String ctxPrefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = ctxPrefix.isBlank() ? prompt : ctxPrefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(1L, projectId,
                            PromptTemplateService.CODE_MESO_DIRECTIVE, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getTemperature(), 384, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(1L, projectId, null, req);
                    usageLogService.record(1L, projectId, null, result.providerId(), result.model(),
                            SCENE_MESO_DIRECTIVE, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new IllegalStateException("AI 未返回有效 JSON");
                    }
                    return parseDirective(json);
                });
            } catch (Exception e) {
                log.warn("[宏观] 中观指令 AI 生成第 {} 次失败（回退规则模板）: project={} : {}",
                        attempt, projectId, e.getMessage());
            }
        }
        return null;
    }

    /** 指令生成 JSON 解析（容错缺省；核心字段空值回退规则模板语义）。 */
    private DirectiveGen parseDirective(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tendency = root.path("tendency");
            String bias = tendency.path("bias").asText("");
            Double strength = tendency.path("strength").isNumber()
                    ? clampDouble(tendency.path("strength").asDouble(0.5), 0.0, 1.0) : null;
            List<String> forbidden = new java.util.ArrayList<>();
            JsonNode constraint = root.path("constraint");
            if (constraint.path("forbidden").isArray()) {
                constraint.path("forbidden").forEach(n -> forbidden.add(n.asText()));
            }
            Boolean soft = constraint.has("soft") ? constraint.path("soft").asBoolean(true) : null;
            String cReason = constraint.path("reason").asText("");
            String resourceHint = root.path("resourceDelta").path("hint").asText("");
            String note = root.path("note").asText("");
            return new DirectiveGen(bias, strength, forbidden, soft, cReason, resourceHint, note);
        } catch (Exception e) {
            log.warn("[宏观] 中观指令 AI 生成解析失败（回退规则模板）: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 二阶涌现 AI 叙事 ====================

    /**
     * 二阶涌现事件 AI 叙事（检测出同类行为聚集后；预算门控（max 不设上限）失败回退规则文本）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @param emergenceType 涌现类型（囤积/迁徙/抗议…）
     *
     * @param behaviorClass 行为类别
     *
     * @param memberCount 参与人数
     *
     * @param ruleText 规则文本草稿（参考素材）
     *
     * @param communityName 社区名
     *
     * @return 叙事（null=兜底规则文本）
     */
    public Narrative narrateEmergence(Long projectId, Long gameDay, String emergenceType,
                                      String behaviorClass, Integer memberCount, String ruleText,
                                      String communityName) {
        boolean max = aiIntensityGate != null && aiIntensityGate.isMax(projectId);
        if (!aiAllowed(projectId)) {
            return null;
        }
        if (!max && !tryConsumeDailyAi(projectId, gameDay)) {
            return null;
        }
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("community_name", communityName == null ? "" : communityName);
        ph.put("emergence_type", emergenceType == null ? "" : emergenceType);
        ph.put("behavior_class", behaviorClass == null ? "" : behaviorClass);
        ph.put("member_count", memberCount == null ? 0 : memberCount);
        ph.put("rule_draft", ruleText == null ? "" : ruleText);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return AiCallContext.run(SCENE_MACRO_EMERGENCE, null, attempt, () -> {
                    String prompt = promptTemplateService.render(1L, projectId,
                            PromptTemplateService.CODE_MACRO_EMERGENCE, ph);
                    // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】——二阶涌现生成
                    // 前置 段1A【世界观】+ 段2D 当前世界动态（无单一角色，仅世界口径）
                    String ctxPrefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = ctxPrefix.isBlank() ? prompt : ctxPrefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(1L, projectId,
                            PromptTemplateService.CODE_MACRO_EMERGENCE, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getTemperature(), 384, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(1L, projectId, null, req);
                    usageLogService.record(1L, projectId, null, result.providerId(), result.model(),
                            SCENE_MACRO_EMERGENCE, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new IllegalStateException("AI 未返回有效 JSON");
                    }
                    JsonNode root = objectMapper.readTree(json);
                    String title = root.path("title").asText(emergenceType);
                    String content = root.path("narrative").asText(ruleText);
                    if (content.isBlank()) {
                        content = ruleText;
                    }
                    return new Narrative(title, content);
                });
            } catch (Exception e) {
                log.warn("[宏观] 涌现 AI 叙事第 {} 次失败（回退规则文本）: project={} : {}",
                        attempt, projectId, e.getMessage());
            }
        }
        return null;
    }

    /** 数值钳制。 */
    private static double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==================== 独立每日 AI 预算 ====================

    /**
     * 消耗 1 次宏观独立每日预算（跨游戏日自动重置；上限按 AI 强度档：light=原值×0.5、
     * max=不限、programmatic=0；超上限 false=转规则兜底）。
     */
    synchronized boolean tryConsumeDailyAi(Long projectId, Long gameDay) {
        int cap = aiIntensityGate == null ? props.getDailyAiCap()
                : aiIntensityGate.effectiveCap(projectId, props.getDailyAiCap());
        if (cap <= 0) {
            return false;
        }
        long[] cur = dailyBudget.computeIfAbsent(projectId, k -> new long[]{gameDay, 0});
        if (cur[0] != gameDay) {
            cur[0] = gameDay;
            cur[1] = 0;
        }
        if (cur[1] >= cap) {
            log.debug("[宏观] 独立每日 AI 预算耗尽，转规则兜底: project={} day={} used={}",
                    projectId, gameDay, cur[1]);
            return false;
        }
        cur[1]++;
        return true;
    }

    /** AI 强度闸门（允许调用判定：programmatic 档零 AI）。 */
    private boolean aiAllowed(Long projectId) {
        return aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
    }

    /** 安全取模板（服务缺失/解析失败回退默认仙侠模板——测试 mock 场景零阻断）。 */
    private MacroTemplate safeTemplate(Long projectId) {
        if (macroStateService != null) {
            try {
                MacroTemplate t = macroStateService.templateOf(projectId);
                if (t != null) {
                    return t;
                }
            } catch (Exception ignored) {
                // 回退默认
            }
        }
        return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
    }

    // ==================== 重大行动 AI 判定（scene=macro_impact） ====================

    /**
     * 重大行动宏观影响判定（异步调用方；预算内 AI，失败/耗尽返回 null=仅规则映射入账）。
     *
     * @param userId 用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @param npcRef 行动者引用
     *
     * @param npcName 行动者名称
     *
     * @param actionText 行动文本
     *
     * @param actionType 行动类型
     *
     * @param target 行动目标
     *
     * @param urgency 决策紧急度（≥4 才调用）
     *
     * @param gameSecond 游戏秒
     *
     * @return 判定结果（null=不叠加）
     */
    public ImpactJudgment judgeImpact(Long userId, Long projectId, Long gameDay, String npcRef, String npcName,
                                      String actionText, String actionType, String target, int urgency,
                                      Long gameSecond) {
        if (!aiAllowed(projectId) || !tryConsumeDailyAi(projectId, gameDay)) {
            return null;
        }
        // 重构：世界观背景按模板语义注入（不再硬编码测试世界文本）
        MacroTemplate tpl = safeTemplate(projectId);
        StringBuilder factions = new StringBuilder();
        if (macroStateService != null) {
            try {
                MacroState st = macroStateService.stateOf(projectId);
                int n = 0;
                for (MacroState.Faction f : st.getFactions().values()) {
                    if (n++ >= 5) {
                        break;
                    }
                    factions.append(f.getName()).append("、");
                }
            } catch (Exception ignored) {
                // 状态不可用不阻断
            }
        }
        if (factions.length() > 0) {
            factions.setLength(factions.length() - 1);
        }
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("npc_name", npcName == null ? "" : npcName);
        ph.put("action_text", actionText == null ? "" : actionText);
        ph.put("action_type", actionType == null ? "" : actionType);
        ph.put("target", target == null ? "" : target);
        ph.put("urgency", urgency);
        // 通用化：世界背景按模板语义渲染，不再硬编码「人口三百余」等测试世界数值
        ph.put("world_setting", tpl.communityName() + "——" + tpl.name() + "，"
                + tpl.coreResource() + "价约百" + tpl.currencyUnit() + "一" + tpl.priceUnit().substring(
                        Math.max(0, tpl.priceUnit().indexOf("/") + 1)) + "，主要势力："
                + (factions.length() == 0 ? "（按世界观）" : factions));
        ph.put("community_name", tpl.communityName());
        ph.put("core_resource", tpl.coreResource());
        ph.put("price_unit", tpl.priceUnit());
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return AiCallContext.run(SCENE_MACRO_IMPACT, null, attempt, () -> {
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_MACRO_IMPACT, ph);
                    // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】与角色身份——
                    // 宏观影响判定前置 段1A【世界观】+ 段1C 行动者身份（characterCard）+ 段2D
                    // 当前世界动态；AI 不知道行动者是谁/世界背景，无法判定影响
                    String ctxPrefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, npcRef);
                    String finalPrompt = ctxPrefix.isBlank() ? prompt : ctxPrefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_MACRO_IMPACT, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getTemperature(), 256, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_MACRO_IMPACT, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new IllegalStateException("AI 未返回有效 JSON");
                    }
                    return parseImpact(json);
                });
            } catch (Exception e) {
                log.warn("[宏观] 重大行动 AI 判定第 {} 次失败（回退规则映射）: project={} : {}",
                        attempt, projectId, e.getMessage());
            }
        }
        return null;
    }

    /** 判定 JSON 解析（容错：hasImpact 缺失按 true 处理带出的字段）。 */
    private ImpactJudgment parseImpact(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            boolean hasImpact = root.path("hasImpact").asBoolean(true);
            String macro = root.path("targetMacro").asText("economy");
            String indicator = root.path("indicator").asText("market_activity");
            double delta = root.path("delta").asDouble(0.0);
            String reason = root.path("reason").asText("");
            if (!hasImpact || delta == 0.0) {
                return new ImpactJudgment(false, macro, indicator, 0.0, reason);
            }
            if (!isValidMacro(macro)) {
                return new ImpactJudgment(false, macro, indicator, 0.0, reason);
            }
            // delta 钳制（防 AI 输出离谱值；经济 ±5/势力 ±10/文化 ±0.5/科技 ±5）
            double cap = switch (macro) {
                case "faction" -> 10.0;
                case "culture" -> 0.5;
                case "population" -> 5.0;
                default -> 5.0;
            };
            return new ImpactJudgment(true, macro, indicator,
                    Math.max(-cap, Math.min(cap, delta)), reason);
        } catch (Exception e) {
            log.warn("[宏观] 重大行动 AI 判定解析失败（回退规则映射）: {}", e.getMessage());
            return null;
        }
    }

    /** 合法目标子系统集合。 */
    static boolean isValidMacro(String macro) {
        return "economy".equals(macro) || "population".equals(macro) || "faction".equals(macro)
                || "culture".equals(macro) || "tech".equals(macro);
    }

    // ==================== 阈值触发 AI 叙事（scene=macro_event） ====================

    /**
     * 重大宏观事件 AI 叙事（无因果上下文版本=narrateDetailed 的简化委托；测试兼容入口）。
     */
    public Narrative narrate(Long userId, Long projectId, Long gameDay, String macroType, String eventType,
                             String indicator, Double oldValue, Double newValue, String ruleText) {
        return narrateDetailed(userId, projectId, gameDay, macroType, eventType, indicator,
                oldValue, newValue, ruleText, null);
    }

    /**
     * 重大宏观事件 AI 叙事（阈值已由 MacroEventService 判定；AI 强度闸门 + 独立预算内 AI，
     * 失败/耗尽/纯规则档返回 null =调用方规则模板兜底）。
     *
     * @param userId 用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @param macroType 子系统（economy/faction/population/culture/tech…）
     *
     * @param eventType 事件类型（粮价暴涨/大旱/舆论转向/技艺突破…）
     *
     * @param indicator 指标名
     *
     * @param oldValue 旧值
     *
     * @param newValue 新值
     *
     * @param ruleText 规则模板草稿（AI 参考素材）
     *
     * @param context 因果链上下文（：season/weather/harvestFactor/grievance/入账摘要等；
     * 渲染为「季节：秋 · 天气：晴 · 收成系数：1.2 …」注入提示词；可空）
     *
     * @return 叙事（null=兜底）
     */
    public Narrative narrateDetailed(Long userId, Long projectId, Long gameDay, String macroType,
                                     String eventType, String indicator, Double oldValue, Double newValue,
                                     String ruleText, Map<String, Object> context) {
        if (!aiAllowed(projectId) || !tryConsumeDailyAi(projectId, gameDay)) {
            return null;
        }
        MacroTemplate tpl = safeTemplate(projectId);
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("community_name", tpl.communityName());
        ph.put("core_resource", tpl.coreResource());
        ph.put("price_unit", tpl.priceUnit());
        ph.put("macro_type", macroType);
        // 事件码 → 通用中文显示名
        ph.put("event_type", MacroTemplate.displayName(eventType));
        ph.put("indicator", indicator);
        ph.put("old_value", oldValue);
        ph.put("new_value", newValue);
        ph.put("rule_draft", ruleText == null ? "" : ruleText);
        ph.put("context_extra", renderContext(context));
        // story-plan（_06）：世界氛围片段注入（无档案/未装配 → 空串，模板残留占位符自动抹除）
        ph.put("atmosphere", storyAtmosphereService == null ? "" : storyAtmosphereService.atmosphereOf(projectId));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return AiCallContext.run(SCENE_MACRO_EVENT, null, attempt, () -> {
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_MACRO_EVENT, ph);
                    // 2026-10-10 用户指示：世界视角 prompt 至少注入【世界观】——宏观事件叙事
                    // 前置 段1A【世界观】+ 段2D 当前世界动态（无单一角色，仅世界口径）
                    String ctxPrefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = ctxPrefix.isBlank() ? prompt : ctxPrefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_MACRO_EVENT, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getTemperature(), 512, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_MACRO_EVENT, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new IllegalStateException("AI 未返回有效 JSON");
                    }
                    JsonNode root = objectMapper.readTree(json);
                    String title = root.path("title").asText(eventType);
                    String content = root.path("narrative").asText(ruleText);
                    if (content.isBlank()) {
                        content = ruleText;
                    }
                    return new Narrative(title, content);
                });
            } catch (Exception e) {
                log.warn("[宏观] 宏观事件 AI 叙事第 {} 次失败（回退规则模板）: project={} : {}",
                        attempt, projectId, e.getMessage());
            }
        }
        return null;
    }

    /** 因果链上下文 → 提示词文本（「季节：秋 · 天气：晴 · 收成系数：1.2 …」；空=「（无）」）。 */
    private static String renderContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : context.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String v = String.valueOf(e.getValue());
            if (v.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(e.getKey()).append("：").append(v);
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }
}
