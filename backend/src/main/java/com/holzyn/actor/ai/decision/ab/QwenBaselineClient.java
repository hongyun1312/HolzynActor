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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Qwen 同题型重问客户端。
 * <p>对照口径：把 决策模型 判定题（choice/boolean/score）转成 Qwen 的
 * <b>同题型判定任务</b>——同一道题、同样的候选/档位/判据，Qwen 输出选中项 JSON，
 * 与 决策模型 top-1（argmax）比较一致率。同任务同格式，一致率定义干净。</p>
 * <p>提示词构造：</p>
 * <ul>
 * <li>system：判定评估对照模型——只输出选中项 JSON（jsonMode）；</li>
 * <li>user：情境 + 判定题（instructions + criteria 候选/档位/判据 + 输出契约）。</li>
 * </ul>
 * <p>输出契约（{@link QwenAnswerParse} 解析）：</p>
 * <ul>
 * <li>choice：{@code {"answer": "<候选键>"}}；</li>
 * <li>score：{@code {"answer": 档位索引}}。</li>
 * </ul>
 * <p><b>架构不变量</b>：本类含 AI 调用（chat 网关），公开方法一律<b>不带
 * {@code @Transactional}</b>（AiCallOutsideTransactionTest 扩展守护）；调用经
 * {@link AiCallContext#run} 包裹（场景=decision_ab_qwen，trace/用量记账完整）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QwenBaselineClient {

    private final AiSpringChatGateway chatGateway;
    private final DecisionAbProperties properties;

    /** 当前用户提供者（单用户 id=1；userId=null 时兜底取当前用户，与 DecisionCallClient 同口径） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * Qwen 同题型重问：给定情境与判定题，返回 Qwen 选中项。
     *
     * @param userId 归属用户 ID（单用户 id=1）
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空；trace 归属）
     *
     * @param npcRef 角色引用（可空；提示词上下文用）
     *
     * @param npcName 角色名（可空）
     *
     * @param stateText 情境文本
     *
     * @param question 判定题（决策模型 三题型）
     *
     * @return 解析结果（answer=字符串/布尔/整数；调用失败/解析失败返回 null——对照失败
     * 样本标 failed，不参与一致率分子）
     */
    public QwenAnswerParse.QwenAnswer ask(Long userId, Long projectId, Long characterId,
                                          String npcRef, String npcName, String stateText,
                                          DecisionQuestion question) {
        String scene = properties.getQwen() == null ? "decision_ab_qwen" : properties.getQwen().getScene();
        // userId=null → 兜底取当前用户（单用户 id=1）：resolveProviderConfig 需要非空 userId 才能
        // 解析项目级/用户级 API 配置
        Long uid = userId;
        if (uid == null && currentUserProvider != null) {
            uid = currentUserProvider.currentUserId();
        }
        Long effUserId = uid;
        return AiCallContext.run(scene, characterId, () -> {
            try {
                AiChatRequest req = buildRequest(npcRef, npcName, stateText, question);
                AiChatResult result = chatGateway.chatCompletionJson(effUserId, projectId, null, req);
                QwenAnswerParse.QwenAnswer parsed = QwenAnswerParse.parse(result.content());
                if (parsed == null) {
                    log.warn("[决策模型 A/B] Qwen 重问输出不可解析（样本标 failed）：content={}",
                            result.content() == null ? "" : result.content().substring(0,
                                    Math.min(200, result.content().length())));
                }
                return parsed;
            } catch (Exception e) {
                log.warn("[决策模型 A/B] Qwen 重问失败（样本标 failed，不参与一致率）：原因={}", e.getMessage());
                return null;
            }
        });
    }

    /** 构造 Qwen 判定提示词（同题型同判据；jsonMode 输出选中项）。 */
    private AiChatRequest buildRequest(String npcRef, String npcName, String stateText,
                                       DecisionQuestion question) {
        String sys = "你是判定评估对照模型。给定一段角色情境与一道判定题，"
                + "按题目要求选出你的答案，只输出 JSON 对象，禁止输出其他任何文字。";
        String user = buildUserPrompt(npcRef, npcName, stateText, question);
        return new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", sys),
                        new AiChatRequest.ChatMessage("user", user)),
                properties.getQwen() == null ? 0.3 : properties.getQwen().getTemperature(),
                properties.getQwen() == null ? 512 : properties.getQwen().getMaxTokens(),
                true);
    }

    /** 组装 user 提示词（情境 + 判据 + 候选 + 输出契约）。 */
    static String buildUserPrompt(String npcRef, String npcName, String stateText, DecisionQuestion question) {
        StringBuilder sb = new StringBuilder();
        sb.append("—— 角色情境 ——\n");
        sb.append(npcName == null || npcName.isBlank() ? "（无名角色）" : npcName);
        if (npcRef != null && !npcRef.isBlank()) {
            sb.append("（").append(npcRef).append("）");
        }
        sb.append("\n").append(stateText == null ? "（无情境）" : stateText).append("\n\n");

        sb.append("—— 判定题 ——\n");
        sb.append("题目：").append(question.instructions()).append("\n");
        switch (question.type()) {
            case DecisionQuestion.TYPE_CHOICE -> {
                sb.append("候选（选一个）：\n");
                sb.append(question.criteria().entrySet().stream()
                        .map(e -> "[" + e.getKey() + "] " + e.getValue())
                        .collect(Collectors.joining("\n")));
                sb.append("\n\n—— 输出契约 ——\n");
                sb.append("只输出：{\"answer\": \"<候选键>\"}\n");
            }
            case DecisionQuestion.TYPE_BOOLEAN -> {
                if (question.criteria() != null) {
                    sb.append("判据：false=").append(question.criteria().get("false"))
                            .append("；true=").append(question.criteria().get("true")).append("\n");
                }
                sb.append("\n—— 输出契约 ——\n");
                sb.append("只输出：{\"answer\": true} 或 {\"answer\": false}\n");
            }
            case DecisionQuestion.TYPE_SCORE -> {
                sb.append("档位（选一个，档位索引从 0 起）：\n");
                for (int i = 0; i < question.scoreCriteria().size(); i++) {
                    sb.append("[").append(i).append("] ").append(question.scoreCriteria().get(i)).append("\n");
                }
                sb.append("\n—— 输出契约 ——\n");
                sb.append("只输出：{\"answer\": <档位索引>}\n");
            }
            default -> sb.append("（未知题型）");
        }
        return sb.toString();
    }

    /** 候选键表（choice 场景的键序，供报告/调试展示）。 */
    static Map<String, String> criteriaOf(DecisionQuestion question) {
        return question.criteria();
    }
}
