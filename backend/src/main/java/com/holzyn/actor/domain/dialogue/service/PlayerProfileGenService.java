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

package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 玩家 persona 六维 AI 补充生成（新用户引导「我的形象」页，AI 调用，<b>不得带 {@code @Transactional}</b>）。
 * <p>职责：用户写一段自我介绍 → 调用 AI 生成 persona 六维
 * （identity/background/personality/voice/goals/ability，各 1~2 句可感知细节）
 * → 返回给前端回填表单预览，由用户确认后再走 {@link PlayerProfileService#updateProfile} 落库。</p>
 * <p><b>场景码</b>：{@code player_profile_gen}（PromptSpecRegistry 登记 + PromptTemplateService
 * 内置模板版本留痕；本服务按内置模板文本内联组装，运行时零模板库依赖）。</p>
 * <p><b>失败语义</b>：AI 失败/输出不可解析 → 抛 {@link AiCallException}（全局处理器返回 400 中文提示，
 * 前端展示错误）；调用方无需预检查 API（网关在未配置时抛 {@link AiCallException}）。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用，不得带 {@code @Transactional}
 * （对齐 {@link DialogueGreetingService} 的反射断言守护模式）。</p>
 */
@Slf4j
@Service
public class PlayerProfileGenService {

    /** 玩家 persona 补充生成场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE_PLAYER_PROFILE_GEN = "player_profile_gen";

    /** persona 六维键（与 {@code PlayerProfileService.PERSONA_KEYS} 对齐）。 */
    private static final List<String> PERSONA_KEYS =
            List.of("identity", "background", "personality", "voice", "goals", "ability");

    /** 自我介绍最大长度（超出截断，防模板撑爆）。 */
    private static final int MAX_SELF_INTRO = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** AI 网关（非流式） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量日志（scene=player_profile_gen 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 生成玩家 persona 六维。
     *
     * @param userId    用户 ID
     * @param selfIntro 用户写下的自我介绍（非空）
     *
     * @return persona 六维 Map（identity/background/personality/voice/goals/ability）
     *
     * @throws AiCallException 自我介绍为空 / AI 调用失败 / 输出不可解析（消息面向用户）
     */
    public Map<String, String> generate(Long userId, String selfIntro) {
        if (selfIntro == null || selfIntro.isBlank()) {
            throw new AiCallException("请先写一段自我介绍，再点「AI 补充生成」");
        }
        String intro = selfIntro.trim();
        if (intro.length() > MAX_SELF_INTRO) {
            intro = intro.substring(0, MAX_SELF_INTRO);
        }
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_PLAYER_PROFILE_GEN);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", systemPrompt()),
                        new AiChatRequest.ChatMessage("user", intro)),
                spec.temperature(), spec.maxOutputTokens(), false);
        AiChatResult result;
        try {
            result = AiCallContext.run(SCENE_PLAYER_PROFILE_GEN, null, (Supplier<AiChatResult>) () ->
                    aiChatGateway.chatCompletion(userId, null, null, request));
        } catch (AiCallException e) {
            throw new AiCallException("AI 补充生成失败：" + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[玩家档案] AI 补充生成异常（{}）: {}", SCENE_PLAYER_PROFILE_GEN, e.getMessage());
            throw new AiCallException("AI 补充生成失败：" + e.getMessage(), e);
        }
        String text = result == null || result.content() == null ? "" : result.content().trim();
        Map<String, String> persona = parsePersona(text);
        if (persona.isEmpty()) {
            throw new AiCallException("AI 补充生成未返回有效结果，请重试");
        }
        recordUsage(userId, result, request, text);
        return persona;
    }

    /** 系统提示（与 PromptTemplateService.CODE_PLAYER_PROFILE_GEN 内置模板文本一致）。 */
    private String systemPrompt() {
        return """
                你是「角色设定师」：根据用户写下的一段自我介绍，帮他/她补全一份真实、有质感的玩家 persona。
                【自我介绍】{{self_intro}}
                请只输出一个 JSON 对象，键严格为 identity / background / personality / voice / goals / ability
                （各自为一段中文文本，1~2 句，具体到可感知的细节，不要泛泛而谈；不要输出 JSON 以外的任何内容）：
                {"identity":"身份（如：云游四方的旅行编辑）","background":"背景经历（一两句）","personality":"性格（如：温和慢热，重承诺）","voice":"说话风格（如：慢条斯理，爱引经据典）","goals":"目标（如：收集九州民间故事）","ability":"能力（如：善写、善辨音）"}
                """;
    }

    /**
     * 解析 AI 输出的六维 JSON；失败返回空 Map（调用方按「未返回有效结果」处理）。
     * 兼容模型偶发在 JSON 前后追加自然语言前缀/后缀（提取首个 {…} 片段重试一次）。
     */
    private static Map<String, String> parsePersona(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        JsonNode root = tryRead(text);
        if (root == null) {
            // 提取首个 JSON 对象片段（模型偶尔加前后缀）
            int s = text.indexOf('{');
            int e = text.lastIndexOf('}');
            if (s >= 0 && e > s) {
                root = tryRead(text.substring(s, e + 1));
            }
        }
        if (root == null || !root.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : PERSONA_KEYS) {
            JsonNode v = root.get(key);
            if (v != null && v.isTextual()) {
                String s = v.asText().trim();
                if (!s.isBlank()) {
                    out.put(key, s);
                }
            }
        }
        return out;
    }

    private static JsonNode tryRead(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 用量记账（scene=player_profile_gen；失败仅告警不阻断）。 */
    private void recordUsage(Long userId, AiChatResult result, AiChatRequest request, String text) {
        if (usageLogService == null) {
            return;
        }
        try {
            Integer in = result.promptTokens() != null ? result.promptTokens()
                    : TokenEstimator.estimatePromptTokens(request.messages());
            Integer out = result.completionTokens() != null ? result.completionTokens()
                    : TokenEstimator.estimateTokens(text);
            usageLogService.record(userId, null, null,
                    result.providerId(), result.model(), SCENE_PLAYER_PROFILE_GEN, in, out,
                    result.cacheHitTokens(), result.cacheMissTokens(), null);
        } catch (Exception e) {
            log.warn("[玩家档案] AI 补充生成用量记账失败（忽略）: {}", e.getMessage());
        }
    }
}
