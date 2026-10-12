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

package com.holzyn.actor.ai.nanojev.decision;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * NanoJev 判定层 · 升级重问客户端。
 * <p><b>升级语义</b>：NanoJev 分布不尖锐（top-1 概率低于
 * 场景阈值）→ 0.6B 判定不可信 → <b>升级 Qwen 深度处理</b>。本客户端把同一道判定题
 * （同情境/同判据/同候选/同档位）转成 Qwen 的 chat 判定任务（jsonMode 输出选中项），
 * 与 评估口径（QwenBaselineClient）一致，但<b>独立场景码记账</b>（调用方传入
 * escalationScene，如 nanojev_social_willingness_escalation），不污染评估设施。</p>
 * <p><b>架构不变量</b>：本类含 AI 调用（chat 网关），公开方法一律<b>不带
 * {@code @Transactional}</b>（AiCallOutsideTransactionTest 扩展守护）；调用经
 * {@link AiCallContext#run} 包裹（trace/用量记账完整）；userId=null 兜底当前用户
 * 。</p>
 */
@Slf4j
@Component
public class NanoJevEscalationClient {

    /** 解析结果（answer=Boolean / String(choice 键) / Integer(score 档位)；null=失败）。 */
    public record ParsedAnswer(Object answer) {
        public boolean isTrue() {
            return Boolean.TRUE.equals(answer);
        }

        public boolean isFalse() {
            return Boolean.FALSE.equals(answer);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private AiSpringChatGateway chatGateway;

    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * Qwen 升级重问：给定情境与判定题，返回选中项。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空；trace 归属）
     *
     * @param scene 升级场景码（如 nanojev_social_willingness_escalation，PromptSpecRegistry 登记）
     *
     * @param npcName 角色名（可空）
     *
     * @param stateText 情境文本
     *
     * @param question 判定题（NanoJev 三题型）
     *
     * @return 解析结果（失败/不可解析返回 null → 调用方 fail-open 兜底）
     */
    public ParsedAnswer ask(Long projectId, Long characterId, String scene,
                            String npcName, String stateText, NanoJevQuestion question) {
        if (chatGateway == null) {
            return null;
        }
        Long uid = currentUserProvider == null ? null : currentUserProvider.currentUserId();
        return AiCallContext.run(scene == null ? "nanojev_escalation" : scene, characterId, () -> {
            try {
                AiChatRequest req = buildRequest(npcName, stateText, question);
                AiChatResult result = chatGateway.chatCompletionJson(uid, projectId, null, req);
                if (usageLogService != null) {
                    try {
                        usageLogService.record(uid, projectId, characterId, result.providerId(), result.model(),
                                scene == null ? "nanojev_escalation" : scene,
                                result.promptTokens(), result.completionTokens(),
                                result.cacheHitTokens(), result.cacheMissTokens(), 0);
                    } catch (Exception e) {
                        log.warn("[NanoJev升级] 用量入账失败（忽略）: {}", e.getMessage());
                    }
                }
                return parse(result.content(), question);
            } catch (Exception e) {
                log.warn("[NanoJev升级] Qwen 重问失败（fail-open 兜底）: scene={} 原因={}", scene, e.getMessage());
                return null;
            }
        });
    }

    /** 构造 Qwen 判定提示词（同题型同判据；jsonMode 输出选中项）。 */
    private AiChatRequest buildRequest(String npcName, String stateText, NanoJevQuestion question) {
        String sys = "你是判定评估对照模型。给定一段角色情境与一道判定题，"
                + "按题目要求选出你的答案，只输出 JSON 对象，禁止输出其他任何文字。";
        StringBuilder sb = new StringBuilder();
        sb.append("—— 角色情境 ——\n");
        sb.append(npcName == null || npcName.isBlank() ? "（无名角色）" : npcName);
        sb.append("\n").append(stateText == null ? "（无情境）" : stateText).append("\n\n");
        sb.append("—— 判定题 ——\n");
        sb.append("题目：").append(question.instructions()).append("\n");
        switch (question.type()) {
            case NanoJevQuestion.TYPE_CHOICE -> {
                sb.append("候选（选一个）：\n");
                sb.append(question.criteria().entrySet().stream()
                        .map(e -> "[" + e.getKey() + "] " + e.getValue())
                        .collect(Collectors.joining("\n")));
                sb.append("\n\n—— 输出契约 ——\n只输出：{\"answer\": \"<候选键>\"}\n");
            }
            case NanoJevQuestion.TYPE_BOOLEAN -> {
                if (question.criteria() != null) {
                    sb.append("判据：false=").append(question.criteria().get("false"))
                            .append("；true=").append(question.criteria().get("true")).append("\n");
                }
                sb.append("\n—— 输出契约 ——\n只输出：{\"answer\": true} 或 {\"answer\": false}\n");
            }
            case NanoJevQuestion.TYPE_SCORE -> {
                sb.append("档位（选一个，档位索引从 0 起）：\n");
                for (int i = 0; i < question.scoreCriteria().size(); i++) {
                    sb.append("[").append(i).append("] ").append(question.scoreCriteria().get(i)).append("\n");
                }
                sb.append("\n—— 输出契约 ——\n只输出：{\"answer\": <档位索引>}\n");
            }
            default -> sb.append("（未知题型）");
        }
        return new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", sys),
                        new AiChatRequest.ChatMessage("user", sb.toString())),
                0.3, 512, true);
    }

    /** 解析选中项（JSON {"answer":...} + 文本兜底；失败 null）。 */
    private ParsedAnswer parse(String content, NanoJevQuestion question) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        Object answer = null;
        try {
            JsonNode node = MAPPER.readTree(extractJson(text));
            if (node != null && node.has("answer") && !node.get("answer").isNull()) {
                JsonNode a = node.get("answer");
                answer = a.isBoolean() ? a.asBoolean() : (a.isNumber() ? a.asInt() : a.asText());
            }
        } catch (Exception e) {
            // 文本兜底
            String t = text.toLowerCase();
            if (NanoJevQuestion.TYPE_BOOLEAN.equals(question.type())) {
                answer = t.contains("true") && !t.contains("false") ? Boolean.TRUE
                        : (t.contains("false") && !t.contains("true") ? Boolean.FALSE : null);
            }
        }
        if (answer == null) {
            return null;
        }
        if (NanoJevQuestion.TYPE_BOOLEAN.equals(question.type())) {
            return new ParsedAnswer(answer instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(answer)));
        }
        if (NanoJevQuestion.TYPE_SCORE.equals(question.type())) {
            try {
                return new ParsedAnswer(Integer.parseInt(String.valueOf(answer)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new ParsedAnswer(String.valueOf(answer));
    }

    /** 剥 Markdown 围栏 + 取首对花括号（宽容）。 */
    private static String extractJson(String text) {
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(nl + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
