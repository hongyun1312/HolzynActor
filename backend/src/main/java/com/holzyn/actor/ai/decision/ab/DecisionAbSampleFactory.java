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

package com.holzyn.actor.ai.decision.ab;

import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决策模型 A/B 判定样本工厂。
 * <p>职责：从青禾村真实运行记录（actor_ai_call_log）抽取<b>真实情境</b>，按三类目标
 * 场景构造 决策模型 三题型判定题（①：社交意愿无历史调用点→从对话历史截取
 * 发言前情境构造；决策⑦：短跑世界攒真实情境）：</p>
 * <ul>
 * <li><b>emotion_eval 情绪评估</b>（score）：真实来源=scene=npc_emotion_eval 的
 * 历史调用（user_prompt 情境文本即情绪评估输入）→ {@link DecisionAbTemplates#emotionQuestion()}；</li>
 * <li><b>crowd_decision 候选选择</b>（choice）：真实来源=scene=action 的历史调用
 * （user_prompt 情境 + response 决策 JSON）→ <b>按样本动态提取候选集</b>
 * （⑮：从 response 提炼动作摘要 + 通用项「继续当前行动/歇一歇/找人说话」，
 * <li><b>social_willingness 社交意愿</b>（boolean）：真实来源=scene=dialog /
 * group_reply / autonomous_chat_beat 的历史调用（user_prompt 发言前情境）→
 * </ul>
 * <p>抽样策略：按场景配额（⑧：情绪 75 / 候选选择 75 / 社交意愿 50，
 * 合计 ~200），从项目最近成功调用中取最新优先；每类场景真实样本不足配额时按可抽取数
 * 取样（报告如实呈现数量，不伪造样本）。</p>
 * <p>⚠️ 实测口径：actor_ai_call_log 中 scene=agent_decision 是决策链路级
 * 标记行（prompt/response 为空）——抽样必须过滤 status=success + user_prompt/response
 * 非空 + step_type=ai_call/空，否则样本全是空壳。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAbSampleFactory {

    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final DecisionAbProperties properties;

    /** Jackson 3（决策 JSON 候选集提取）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 情境文本摘录上限（判定题 state 共享上下文；决策模型 判据清晰优先，不宜过长） */
    private static final int STATE_MAX_CHARS = 3000;

    /** 候选集单条动作摘要上限（字符） */
    private static final int CANDIDATE_MAX_CHARS = 24;

    /** 决策 JSON 提取（ReAct 契约 {@code <decision>{...}</decision>} 或裸 JSON 对象）。 */
    private static final Pattern DECISION_TAG = Pattern.compile("<decision>(.*?)</decision>", Pattern.DOTALL);

    /**
     * 按场景范围构建样本。
     *
     * @param projectId 项目 ID
     *
     * @param sceneScope all（三类全跑）/ emotion_eval / crowd_decision / social_willingness
     *
     * @return 判定样本列表（每类按配额上限取样；无可用真实情境的场景为空）
     */
    public List<AbSample> buildSamples(Long projectId, String sceneScope) {
        List<AbSample> out = new ArrayList<>();
        String scope = sceneScope == null || sceneScope.isBlank() ? "all" : sceneScope.trim();
        if ("all".equals(scope) || AbSample.SCENE_EMOTION_EVAL.equals(scope)) {
            out.addAll(buildEmotionSamples(projectId, quotaOf(AbSample.SCENE_EMOTION_EVAL)));
        }
        if ("all".equals(scope) || AbSample.SCENE_CROWD_DECISION.equals(scope)) {
            out.addAll(buildCrowdSamples(projectId, quotaOf(AbSample.SCENE_CROWD_DECISION)));
        }
        if ("all".equals(scope) || AbSample.SCENE_SOCIAL_WILLINGNESS.equals(scope)) {
            out.addAll(buildSocialSamples(projectId, quotaOf(AbSample.SCENE_SOCIAL_WILLINGNESS)));
        }
        log.info("[决策模型 A/B] 样本构建：project={} scope={} 总数={}", projectId, scope, out.size());
        return out;
    }

    /** 场景配额（配置优先，兜底默认）。 */
    private int quotaOf(String scene) {
        Map<String, Integer> q = properties.getSampleQuotas();
        if (q != null && q.containsKey(scene)) {
            Integer v = q.get(scene);
            if (v != null && v > 0) {
                return v;
            }
        }
        return switch (scene) {
            case AbSample.SCENE_EMOTION_EVAL -> properties.getDefaultEmotionQuota();
            case AbSample.SCENE_CROWD_DECISION -> properties.getDefaultCrowdQuota();
            case AbSample.SCENE_SOCIAL_WILLINGNESS -> properties.getDefaultSocialQuota();
            default -> 50;
        };
    }

    // ==================== ① 情绪评估（score） ====================

    /** 从 scene=npc_emotion_eval 历史调用构造情绪档位题样本。 */
    private List<AbSample> buildEmotionSamples(Long projectId, int quota) {
        List<ActorAiCallLog> rows = queryLogs(projectId, List.of("npc_emotion_eval"), quota);
        List<AbSample> out = new ArrayList<>();
        for (ActorAiCallLog r : rows) {
            out.add(new AbSample(
                    AbSample.SCENE_EMOTION_EVAL, DecisionQuestion.TYPE_SCORE,
                    refOf(projectId, r.getCharacterId()), nameOf(projectId, r.getCharacterId()),
                    shorten(r.getUserPrompt(), STATE_MAX_CHARS),
                    DecisionAbTemplates.emotionQuestion(),
                    r.getScene(), r.getTraceId()));
        }
        return out;
    }

    // ==================== ② 候选选择（choice，动态候选集） ====================

    /** 从 scene=action 历史调用构造候选选择题样本（候选集=response 动作摘要动态提取 + 通用项）。 */
    private List<AbSample> buildCrowdSamples(Long projectId, int quota) {
        List<ActorAiCallLog> rows = queryLogs(projectId, List.of("action"), quota);
        List<AbSample> out = new ArrayList<>();
        for (ActorAiCallLog r : rows) {
            Map<String, String> candidates = dynamicCandidates(r.getResponse());
            out.add(new AbSample(
                    AbSample.SCENE_CROWD_DECISION, DecisionQuestion.TYPE_CHOICE,
                    refOf(projectId, r.getCharacterId()), nameOf(projectId, r.getCharacterId()),
                    shorten(r.getUserPrompt(), STATE_MAX_CHARS),
                    DecisionAbTemplates.crowdQuestion(candidates),
                    r.getScene(), r.getTraceId()));
        }
        return out;
    }

    /**
     * 按样本动态提取候选集：从决策 response 提炼动作摘要（去重后最多
     * 5 项）+ 通用项 3 项 = 5~8 项；response 不可解析时回退纯通用项（仍 ≥5 项合法）。
     *
     * @param response 决策 AI 输出（ReAct &lt;decision&gt; JSON 或裸 JSON）
     *
     * @return 候选表（键 → 自包含描述）
     */
    static Map<String, String> dynamicCandidates(String response) {
        Map<String, String> out = new LinkedHashMap<>();
        out.putAll(DecisionAbTemplates.commonChoiceCandidates());
        if (response == null || response.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        String actionText = extractActionText(response);
        if (actionText != null && !actionText.isBlank()) {
            String key = "act_" + (seen.size() + 1);
            String desc = shorten(actionText, CANDIDATE_MAX_CHARS);
            out.put(key, desc);
            seen.add(desc);
        }
        // 兜底：若决策 JSON 有候选列表（candidates[{action,consequence}]）也提取（前瞻推演产物）
        for (String cand : extractCandidateActions(response)) {
            String desc = shorten(cand, CANDIDATE_MAX_CHARS);
            if (desc.isBlank() || !seen.add(desc)) {
                continue;
            }
            out.put("act_" + (seen.size() + 1), desc);
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    /** 提取决策 response 中的主行动文本（action_detail / action 字段；ReAct 或裸 JSON）。 */
    private static String extractActionText(String response) {
        try {
            Matcher m = DECISION_TAG.matcher(response);
            String json = m.find() ? m.group(1) : response;
            JsonNode node = MAPPER.readTree(extractJsonObject(json));
            if (node == null || !node.isObject()) {
                return null;
            }
            for (String key : new String[]{"action_detail", "action", "detail", "target_action"}) {
                String v = node.path(key).asText("").trim();
                if (!v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // 解析失败回退 null（用纯通用项）
        }
        return null;
    }

    /** 提取决策 response 中的候选列表（candidates[{action,...}]，前瞻推演产物）。 */
    private static List<String> extractCandidateActions(String response) {
        List<String> out = new ArrayList<>();
        try {
            Matcher m = DECISION_TAG.matcher(response);
            String json = m.find() ? m.group(1) : response;
            JsonNode node = MAPPER.readTree(extractJsonObject(json));
            JsonNode cands = node == null ? null : node.get("candidates");
            if (cands != null && cands.isArray()) {
                for (JsonNode c : cands) {
                    String a = c.path("action").asText("").trim();
                    if (!a.isBlank()) {
                        out.add(a);
                    }
                }
            }
        } catch (Exception ignored) {
            // 无候选列表
        }
        return out;
    }

    /** 从文本提取 JSON 对象（宽容：截取首个 { 到最后一个 }；非法返回原文由 readTree 兜底）。 */
    private static String extractJsonObject(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s >= 0 && e > s) {
            return text.substring(s, e + 1);
        }
        return text;
    }

    // ==================== ③ 社交意愿（boolean，发言前情境） ====================

    /** 从对话类历史调用（dialog/group_reply/autonomous_chat_beat）构造社交意愿 boolean 题样本。 */
    private List<AbSample> buildSocialSamples(Long projectId, int quota) {
        List<ActorAiCallLog> rows = queryLogs(projectId,
                List.of("dialog", "group_reply", "autonomous_chat_beat"), quota);
        List<AbSample> out = new ArrayList<>();
        for (ActorAiCallLog r : rows) {
            out.add(new AbSample(
                    AbSample.SCENE_SOCIAL_WILLINGNESS, DecisionQuestion.TYPE_BOOLEAN,
                    refOf(projectId, r.getCharacterId()), nameOf(projectId, r.getCharacterId()),
                    shorten(r.getUserPrompt(), STATE_MAX_CHARS),
                    DecisionAbTemplates.willingnessQuestion(),
                    r.getScene(), r.getTraceId()));
        }
        return out;
    }

    // ==================== 通用抽取 ====================

    /**
     * 从 actor_ai_call_log 抽取指定场景的真实成功调用（最新优先，取 N 条）。
     * ⚠️ 实测口径：过滤 step_type=ai_call/空 + user_prompt/response 非空（防链路级标记空壳）。
     */
    private List<ActorAiCallLog> queryLogs(Long projectId, List<String> scenes, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Specification<ActorAiCallLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("projectId"), projectId));
            ps.add(root.get("scene").in(scenes));
            ps.add(cb.equal(root.get("status"), "success"));
            ps.add(cb.or(cb.isNull(root.get("stepType")), cb.equal(root.get("stepType"), "ai_call")));
            ps.add(cb.isNotNull(root.get("userPrompt")));
            ps.add(cb.notEqual(root.get("userPrompt"), ""));
            ps.add(cb.isNotNull(root.get("response")));
            ps.add(cb.notEqual(root.get("response"), ""));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<ActorAiCallLog> rows = aiCallLogRepository.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        log.info("[决策模型 A/B] 真实情境抽取：project={} scenes={} 命中={}/{}", projectId, scenes, rows.size(), limit);
        return rows;
    }

    // ==================== 角色解析（先特殊后普通） ====================

    /** characterId → ref（先特殊后普通；无则 null）。 */
    private String refOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        if (characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId())).isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        if (ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId())).isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        return null;
    }

    /** characterId → 角色名（先特殊后普通；无则 null）。 */
    private String nameOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        Optional<ActorCharacter> ch = characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()));
        if (ch.isPresent()) {
            return ch.get().getName();
        }
        Optional<ActorCharacter> npc = ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId()));
        return npc.map(ActorCharacter::getName).orElse(null);
    }

    /** 文本截断（含省略号标记）。 */
    static String shorten(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }
}
