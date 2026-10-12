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

package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.spring.Http11OpenAiHttpClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Judge 聊天客户端。
 * <p>与生成链路完全隔离的专用客户端：</p>
 * <ul>
 * <li><b>独立供应商</b>：火山方舟 Ark coding v3（OpenAI 兼容），模型 deepseek-v4-flash——
 * <b>与生成模型（本地 qwen3.6）不同家族，规避 self-preference 偏见</b>（，
 * 联网调研：judge 与受评系统应使用不同模型家族）；</li>
 * <li><b>不落 actor_ai_call_log</b>：judge 是离线评估设施（非世界运行 AI 调用），
 * 调用详情只进评估批次样本/评分表（actor_eval_sample/actor_eval_score）；</li>
 * <li><b>超时兜底</b>：复用 {@link Http11OpenAiHttpClient}（强制 HTTP/1.1 + 看门狗，
 * 已知问题最终修复方案）——judge 调用永不悬挂；</li>
 * <li><b>温度 0.0</b>：评分必须稳定可复现（默认）。</li>
 * </ul>
 * <p>模型惰性构建（首次调用时；配置变更重启生效），构建失败抛 AiCallException（中文提示）。</p>
 */
@Slf4j
@Component
public class JudgeChatClient implements JudgeScorer {

    /**
     * 思考控制（2026-10-02 全局指示：所有文本模型默认不思考、直接输出）。
     * <p>judge 走独立 OpenAiChatModel（不经 AiSpringChatGateway），必须显式下发
     * {@code thinking:{type:disabled}}——否则 Ark deepseek-v4-flash 输出大量 reasoning
     * 占用输出预算（实测整次评分被推理撑慢/截断）。</p>
     */
    private static final Map<String, Object> THINKING_DISABLED = Map.of("thinking", Map.of("type", "disabled"));

    private final EvaluationProperties properties;

    /** 惰性构建的 judge 模型（配置驱动，volatile 双检锁） */
    private volatile OpenAiChatModel model;

    public JudgeChatClient(EvaluationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String call(String systemPrompt, String userPrompt) {
        OpenAiChatModel m = model();
        // ⚠️ 每调用 options 必须携带 model：Spring AI 2.0.1 中 per-call options 覆盖默认 options，
        // 缺 model 时请求体不带模型名 → Ark coding/v3 返回 404「model does not support the coding
        // plan feature」
        ChatResponse resp = m.call(new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                OpenAiChatOptions.builder()
                        .model(properties.getJudgeModel())
                        .temperature(properties.getJudgeTemperature())
                        .maxTokens(properties.getJudgeMaxOutputTokens())
                        // 全局默认不思考：请求体顶层下发 thinking={type:disabled}
                        .extraBody(THINKING_DISABLED)
                        .build()));
        String content = resp == null || resp.getResult() == null
                ? null : resp.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new AiCallException("Judge 模型返回空内容");
        }
        return content;
    }

    @Override
    public String modelName() {
        return properties.getJudgeModel();
    }

    /** 惰性构建模型（线程安全双检锁）。 */
    private OpenAiChatModel model() {
        OpenAiChatModel m = model;
        if (m == null) {
            synchronized (this) {
                m = model;
                if (m == null) {
                    m = build();
                    model = m;
                }
            }
        }
        return m;
    }

    /** 构建 judge 模型。 */
    private OpenAiChatModel build() {
        String baseUrl = properties.getJudgeBaseUrl();
        String apiKey = properties.getJudgeApiKey();
        String modelName = properties.getJudgeModel();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiCallException("Judge 供应商未配置：holzyn.actor.evaluation.judge.base-url 为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("Judge API Key 未配置：holzyn.actor.evaluation.judge.api-key 为空（经环境变量注入）");
        }
        long connect = Math.max(1, properties.getJudgeTimeoutConnectSeconds());
        long read = Math.max(1, properties.getJudgeTimeoutReadSeconds());
        long request = Math.max(1, properties.getJudgeTimeoutRequestSeconds());
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(modelName)
                    .build();
            Timeout timeout = Timeout.builder()
                    .connect(Duration.ofSeconds(connect))
                    .read(Duration.ofSeconds(read))
                    .write(Duration.ofSeconds(read))
                    .request(Duration.ofSeconds(request))
                    .build();
            Http11OpenAiHttpClient httpClient = new Http11OpenAiHttpClient(
                    Duration.ofSeconds(connect), Duration.ofSeconds(read),
                    Duration.ofSeconds(read), Duration.ofSeconds(request));
            ClientOptions clientOptions = ClientOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .httpClient(httpClient)
                    .maxRetries(0)
                    .timeout(timeout)
                    .build();
            return OpenAiChatModel.builder()
                    .options(options)
                    .openAiClient(new OpenAIClientImpl(clientOptions))
                    .build();
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            throw new AiCallException("Judge 模型构建失败：请检查 Base URL / Key / 模型名（" + e.getMessage() + "）");
        }
    }
}
