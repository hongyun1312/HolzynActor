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

package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 婚恋 AI 判定通用支撑：模板渲染 → AI 调用 → 用量入账 → 提取 JSON。
 * <p><b>架构不变量</b>：本类只做「调用与提取」，不含任何写库、不带 {@code @Transactional}
 * （AI 调用耗时秒级，事务内持连接会堵世界 tick）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDecisionSourceSupport {

    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;

    /**
     * 统一 AI 上下文注入器（婚恋/生育/婚宴叙事属「涉及该角色」的判定与生成——前置
     * 世界观简报 + 归属角色核心卡；字段注入保持构造器不变；null=未装配跳过注入，不影响原调用）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 调用婚恋判定 AI（通用）：渲染模板 → 网关调用 → 入账 → 返回正文。
     *
     * @param projectId 项目 ID
     *
     * @param npcProfileId 归属角色 ID（用量入账主体）
     *
     * @param scene 场景码（growth_romance_decision / growth_proposal_decision /
     * growth_fertility_decision / growth_newborn_persona / growth_wedding_narrative）
     *
     * @param placeholders 模板占位符
     *
     * @return AI 正文；失败返回 null（零阻断）
     */
    public String call(Long projectId, Long npcProfileId, String scene, Map<String, Object> placeholders) {
        if (scene == null || npcProfileId == null) {
            return null;
        }
        Long uid = currentUserProvider.currentUserId();
        String prompt = promptTemplateService.render(uid, projectId, scene, placeholders);
        // 上下文注入（2026-10-07）：婚恋/生育/婚宴叙事属「涉及该角色」的判定与生成——
        // 前置世界观简报 + 归属角色核心卡（未装配/失败零阻断；newborn_persona 建档时
        // 角色卡为空 → 仅世界观简报，无害）
        // 批5 世界与叙事（2026-10-10）：升级为 V2.0 段1A+1C+2D+2E（memoryChainContext：
        // compose + decisionStatePrefix）——判定者知道此刻时节天气与该角色当下状态，
        // 心动/求婚/生育意愿/新生儿/婚宴叙事更贴合真人处境
        String injected = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, "npc:" + npcProfileId, null);
        String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
        String system = promptTemplateService.systemMessage(uid, projectId, scene, null);
        if (system.isBlank()) {
            system = "你只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
        }
        try {
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    PromptSpecRegistry.temperatureOf(scene),
                    PromptSpecRegistry.maxOutputTokensOf(scene),
                    true);
            AiChatResult result = AiCallContext.run(scene, npcProfileId, 1,
                    () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
            try {
                usageLogService.record(uid, projectId, npcProfileId, result.providerId(), result.model(), scene,
                        result.promptTokens(), result.completionTokens(), result.cacheHitTokens(),
                        result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.warn("[婚育AI] 用量入账失败（已忽略）: scene={} : {}", scene, e.getMessage());
            }
            return result.content() == null ? null : result.content().trim();
        } catch (Exception e) {
            log.warn("[婚育AI] 判定调用失败（零阻断，调用方程序化兜底）: scene={} 原因={}", scene, e.getMessage());
            return null;
        }
    }

    /** 便捷构造占位符 Map。 */
    public static Map<String, Object> ph(Object... kvs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(String.valueOf(kvs[i]), kvs[i + 1]);
        }
        return m;
    }

    /**
     * 从 AI 输出中提取 JSON 对象文本（剥 Markdown 围栏 + 取首对花括号）。
     *
     * @param content AI 正文（可空）
     *
     * @return JSON 文本；无法提取返回 null
     */
    public static String extractJson(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
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
        return null;
    }
}
