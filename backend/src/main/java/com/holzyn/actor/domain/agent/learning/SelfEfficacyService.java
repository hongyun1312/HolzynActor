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

package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力自评卡（P1 ①-2，self-efficacy）。
 * <p><b>语义</b>：角色对自身各领域能力的主观评估（1-5）随经历/反思动态微调——MUSE 元认知框架
 * 「能力感知」的人类对应物：成功提升信心、失败降低信心，但整体稳定（不会因单次事件剧变）；
 * 自评允许与客观 {@code skills} 有偏差（自信/谦逊/高估/低估），偏差本身即性格。数据落点=
 * persona ⑥ ability 内扩 {@code self_efficacy}/{@code self_comment}（11 维结构不变，旧数据缺字段=空）。</p>
 * <p><b>触发口径（用户 Q1-Q7 确认）</b>：范围=只做能力自评卡本体；数据形态=ability 内扩
 * selfEfficacy 子段；更新钩子=<b>反思联动微调</b>（反思成功后由 AI 按近期经历微调，走
 * {@code AfterCommit} 在写阶段全部提交后执行——对齐 L3 自编辑先例，绝不持有行锁）；
 * 决策注入=【角色个性】段内追加【能力自评】小节（{@code PersonalityInjectionService}）；
 * 建档模板=同步（新建角色自动带初始自评）；AI 失败/超时/解析失败/写回失败 → <b>fail-open</b>
 * 零阻断（本次不调，下次反思再说）。</p>
 * <p><b>架构不变量</b>：含 AI 调用，公开方法<b>一律不带 {@code @Transactional}</b>（调用方
 * AfterCommit 保证在事务外）；依赖全部字段注入 {@code @Autowired(required=false)}+{@code @Lazy}
 * 破 Bean 环；null=未装配[单测]按能力缺失降级（跳过微调，零影响）。</p>
 */
@Slf4j
@Component
public class SelfEfficacyService {

    /** AI 判定场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_SELF_EFFICACY_UPDATE = "self_efficacy_update";

    /** 决策链路 trace 环节类型（stepType=self_efficacy，审计/复盘依据）。 */
    public static final String STEP_TYPE_SELF_EFFICACY = "self_efficacy";

    /** 自评写回落库来源标记（PersonaService.savePersona 的 source 入参；对齐 generated/manual/edited）。 */
    public static final String SOURCE_SELF_EFFICACY = "self_efficacy";

    /** 总开关。 */
    @Value("${holzyn.actor.self-efficacy.enabled:true}")
    private boolean enabled = true;

    /** 自评一句话截断上限（字符）。 */
    private static final int COMMENT_MAX = 40;

    /** 反思证据上下文截断上限（字符，防撑爆判定输入）。 */
    private static final int CONTEXT_MAX = 1200;

    /** Prompt 渲染（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（AI 判定；真实网关/测试 mock）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 判定记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /** 档案服务（读最新 persona_json + 写回；null=未装配跳过微调）。 */
    @Autowired(required = false)
    @Lazy
    private PersonaService personaService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（self_efficacy_update 属批4 记忆链·角色视角）：自评微调请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== 反思联动微调入口 ====================

    /**
     * 反思联动微调（ReflectionService AfterCommit 调用）：读当前自评 → AI 按近期经历/反思微调
     * → 有变化写回 persona（source=self_efficacy）→ trace 留痕。
     * <p><b>fail-open 全程零阻断</b>：开关关/参数缺失/档案缺失/AI 判定失败/解析失败/无变化/
     * 写回失败 → 静默返回（下次反思再说）。写回走 {@link PersonaService#savePersona}（幂等，
     * 附带版本快照留痕——自评微调可审计）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code special:{id}} 或 {@code npc:{id}}；反思本就只对特殊型）
     *
     * @param reflectionContext 反思证据/洞察摘要（AI 微调输入；空白=跳过）
     */
    public void maybeAdjustFromReflection(Long projectId, String characterRef, String reflectionContext) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || reflectionContext == null || reflectionContext.isBlank()) {
            return;
        }
        long startMs = System.currentTimeMillis();
        try {
            Long charId = NpcRefs.idOf(characterRef);
            if (charId == null || personaService == null) {
                return;
            }
            String personaJson = personaService.latestPersonaJson(charId).orElse(null);
            if (personaJson == null || personaJson.isBlank()) {
                return; // 无档案：无法微调
            }
            PersonaProfile profile = PersonaJson.readQuietly(personaJson);
            if (profile == null || profile.getDims() == null) {
                return;
            }
            PersonaDims.Ability ab = profile.getDims().getAbility();
            Map<String, Integer> current = ab == null || ab.getSelfEfficacy() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(ab.getSelfEfficacy());
            String currentComment = ab == null ? null : ab.getSelfComment();
            String skillsText = skillsText(ab);
            // AI 判定（scene=self_efficacy_update）：微调后的自评 + 是否变化 + 理由
            Verdict v = judge(projectId, characterRef, current, currentComment, skillsText, reflectionContext);
            if (v == null) {
                // fail-open：AI 判定失败/超时/解析失败 → 跳过（不写回）
                log.debug("[能力自评] AI 判定失败降级（跳过）: ref={}", characterRef);
                return;
            }
            if (!v.changed()) {
                recordTrace(projectId, characterRef, System.currentTimeMillis() - startMs,
                        "changed=false 维持不变（" + abbreviate(v.reason(), 40) + "）");
                return;
            }
            Map<String, Integer> updates = sanitize(v.selfEfficacy(), current, skillNamesOf(ab));
            String comment = abbreviate(v.selfComment(), COMMENT_MAX);
            if (updates.isEmpty() && (comment == null || comment.isBlank())) {
                // 声称变化但无实质内容：跳过（防 AI 空转写回）
                return;
            }
            // 写回：合并（只更新 AI 给出的领域，保留其它领域——不误删既有自评）+ selfComment
            PersonaDims.Ability target = ab != null ? ab : new PersonaDims.Ability();
            target.getSelfEfficacy().putAll(updates);
            if (comment != null && !comment.isBlank()) {
                target.setSelfComment(comment);
            }
            if (ab == null) {
                profile.getDims().setAbility(target);
            }
            int version = personaService.savePersona(charId, profile, SOURCE_SELF_EFFICACY);
            if (version <= 0) {
                log.warn("[能力自评] 写回未生效（savePersona 返回 {}，跳过）: ref={}", version, characterRef);
                return;
            }
            recordTrace(projectId, characterRef, System.currentTimeMillis() - startMs,
                    "已微调 version=" + version + "（" + summarize(updates, current) + "）");
            log.info("[能力自评] 反思后微调: ref={} version={} 领域={}", characterRef, version, updates.keySet());
        } catch (Exception e) {
            // 旁路能力异常隔离：绝不阻断反思/世界主流程
            log.warn("[能力自评] 异常（fail-open）: ref={} : {}", characterRef, e.getMessage());
        }
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 微调判定结果（内部规范）。
     *
     * @param selfEfficacy 微调后的自评（领域 → 1-5）
     *
     * @param selfComment 一句话整体自评（可空）
     *
     * @param changed true=确有调整
     *
     * @param reason 调整理由（AI 输出；可空）
     */
    public record Verdict(Map<String, Integer> selfEfficacy, String selfComment, boolean changed, String reason) {
    }

    /**
     * 解析判定 JSON（{"self_efficacy":{...},"self_comment":str,"changed":bool,"reason":str}）。
     * 失败/非法 → null。
     */
    static Verdict parseVerdict(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            Map<String, Integer> se = new LinkedHashMap<>();
            JsonNode arr = node.path("self_efficacy");
            if (arr.isObject()) {
                arr.properties().forEach(e -> {
                    JsonNode val = e.getValue();
                    int n = val.isIntegralNumber() ? val.asInt(3) : parseScore(val.asText("3"));
                    se.put(e.getKey().trim(), Math.max(1, Math.min(5, n)));
                });
            }
            String comment = node.path("self_comment").asText(null);
            boolean changed = node.path("changed").asBoolean(false);
            String reason = node.path("reason").asText(null);
            return new Verdict(se, comment, changed, reason);
        } catch (Exception e) {
            return null;
        }
    }

    /** 分数文本解析（"4" → 4；失败回退 3）。 */
    private static int parseScore(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 3;
        }
    }

    /**
     * 领域清洗（模板铁律：不得新造领域）：只保留「当前自评已有领域 ∪ skills 领域」内的键；
     * 值钳制 1-5。越界领域（AI 编造）直接丢弃。
     *
     * @param updates AI 给出的领域
     *
     * @param current 当前自评领域
     *
     * @param skillNames 客观 skills 领域（可空=无技能）
     *
     * @return 清洗后只含合法领域的更新（LinkedHashMap 保序）
     */
    static Map<String, Integer> sanitize(Map<String, Integer> updates, Map<String, Integer> current,
                                         java.util.Set<String> skillNames) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (updates == null) {
            return out;
        }
        for (Map.Entry<String, Integer> e : updates.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                continue;
            }
            boolean known = (current != null && current.containsKey(k))
                    || (skillNames != null && skillNames.contains(k));
            if (!known) {
                continue; // 越界领域丢弃
            }
            out.put(k, Math.max(1, Math.min(5, e.getValue() == null ? 3 : e.getValue())));
        }
        return out;
    }

    /** 字符串截断（摘要展示/输入收敛用）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    // ==================== 私有辅助 ====================

    /**
     * 客观技能文本（「技能名=熟练度」，供 AI 判定与自评锚定；无技能返回空串）。
     */
    private static String skillsText(PersonaDims.Ability ab) {
        if (ab == null || ab.getSkills() == null || ab.getSkills().isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        ab.getSkills().forEach((k, v) -> lines.add(k + "=" + (v == null ? 0 : v)));
        return String.join("、", lines);
    }

    /** 客观 skills 领域集合（供 sanitize 领域合法性判定；无技能返回空集）。 */
    private static java.util.Set<String> skillNamesOf(PersonaDims.Ability ab) {
        if (ab == null || ab.getSkills() == null || ab.getSkills().isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(ab.getSkills().keySet());
    }

    /**
     * 变更摘要（「领域 3→4、领域 5→3」；与当前对比，供 trace/审计）。
     */
    private static String summarize(Map<String, Integer> updates, Map<String, Integer> current) {
        if (updates.isEmpty()) {
            return "仅自评语句更新";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : updates.entrySet()) {
            Integer before = current.get(e.getKey());
            parts.add(e.getKey() + (before == null ? " 新增=" : " " + before + "→") + e.getValue());
        }
        return String.join("、", parts);
    }

    /**
     * AI 判定：scene=self_efficacy_update，输入=当前自评 + 客观技能 + 反思证据/洞察 → 输出
     * {"self_efficacy":{...},"self_comment":str,"changed":bool,"reason":str}。失败/超时/解析失败 → null（fail-open）。
     */
    private Verdict judge(Long projectId, String characterRef, Map<String, Integer> current, String currentComment,
                          String skillsText, String reflectionContext) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long userId = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            Map<String, Object> placeholders = new LinkedHashMap<>();
            placeholders.put("self_efficacy_current", currentText(current, currentComment));
            placeholders.put("skills", skillsText.isBlank() ? "（无记录）" : skillsText);
            placeholders.put("reflection_context", abbreviate(reflectionContext, CONTEXT_MAX));
            String prompt = promptTemplateService.render(userId, projectId, SCENE_SELF_EFFICACY_UPDATE, placeholders);
            // 批6 收口（self_efficacy_update 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
            // 「自评该不该微调」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
            if (aiContextInjector != null) {
                String cctx = aiContextInjector.memoryChainContext(projectId, characterRef, null);
                if (cctx != null && !cctx.isBlank()) {
                    prompt = cctx + "\n\n" + prompt;
                }
            }
            String system = promptTemplateService.systemMessage(userId, projectId, SCENE_SELF_EFFICACY_UPDATE, null);
            if (system == null || system.isBlank()) {
                system = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", prompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_SELF_EFFICACY_UPDATE),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_SELF_EFFICACY_UPDATE),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_SELF_EFFICACY_UPDATE, NpcRefs.idOf(characterRef), 1,
                    () -> aiChatGateway.chatCompletion(userId, projectId, null, req));
            try {
                usageLogService.record(userId, projectId, NpcRefs.idOf(characterRef), result.providerId(),
                        result.model(), SCENE_SELF_EFFICACY_UPDATE, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[能力自评] 用量入账失败（忽略）: {}", e.getMessage());
            }
            return parseVerdict(result.content());
        } catch (Exception e) {
            log.debug("[能力自评] AI 判定失败（fail-open）: {}", e.getMessage());
            return null;
        }
    }

    /** 当前自评文本（「领域=3/5、领域=4/5」+ 一句话；无自评返回「（尚无自评）」）。 */
    private static String currentText(Map<String, Integer> current, String currentComment) {
        StringBuilder sb = new StringBuilder();
        if (current == null || current.isEmpty()) {
            sb.append("（尚无自评）");
        } else {
            List<String> bits = new ArrayList<>();
            current.forEach((k, v) -> bits.add(k + "=" + v + "/5"));
            sb.append(String.join("、", bits));
        }
        if (currentComment != null && !currentComment.isBlank()) {
            sb.append("；整体：").append(currentComment.trim());
        }
        return sb.toString();
    }

    /** 决策链路 trace（content 前缀 ref=npc:{id}，对齐 SelfCheckStrategy 检索口径）。 */
    private void recordTrace(Long projectId, String characterRef, long durationMs, String detail) {
        try {
            AiCallContext.recordStep(projectId, SCENE_SELF_EFFICACY_UPDATE, STEP_TYPE_SELF_EFFICACY,
                    "ref=" + characterRef + " " + detail, durationMs);
        } catch (Exception e) {
            log.debug("[能力自评] trace 写入失败（忽略）: {}", e.getMessage());
        }
    }
}
