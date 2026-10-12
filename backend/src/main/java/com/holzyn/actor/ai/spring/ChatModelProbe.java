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

package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 供应商连通性探测。
 * <p>职责：替代旧 {@code OpenAiCompatibleProvider.testConnection} 的手写 HTTP 探测——用
 * {@link DynamicChatModelFactory} 构建的 {@link ChatModel} 做一次<b>最小对话校验</b>
 * （maxTokens=1 的 "ping"，消耗极低），返回与旧实现同形的结果 Map：
 * {@code connected / method / status / message / latencyMs}（设置页前端零改动）。</p>
 * <p><b>未保存配置（id=null）</b>：设置页 DTO 测试连接场景——直接 {@code factory.build(config)}
 * 构建不缓存（动态工厂 {@code get()} 要求 providerId 作缓存键）；已保存配置走缓存复用。</p>
 */
@Slf4j
@Component
public class ChatModelProbe {

    /** 动态模型工厂（缓存复用/直接构建） */
    private final DynamicChatModelFactory chatModelFactory;

    /**
     * 构造（Spring 注入）。
     *
     * @param chatModelFactory 动态模型工厂
     */
    public ChatModelProbe(DynamicChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * 连通性探测（最小对话校验，maxTokens=1）。
     *
     * @param config 供应商运行时配置（含解密 Key；id 可空=未保存配置直接构建）
     *
     * @return 结果 Map：connected(boolean) / method("chat") / status / message / latencyMs
     */
    public Map<String, Object> testConnection(ProviderConfig config) {
        Map<String, Object> result = new HashMap<>();
        long start = System.currentTimeMillis();
        try {
            // 未保存配置（id=null，设置页 DTO 测试）：直接构建不缓存；已保存走缓存复用
            ChatModel model = config.id() == null ? chatModelFactory.build(config) : chatModelFactory.get(config);
            String modelName = (config.model() == null || config.model().isBlank()) ? "default" : config.model();
            ChatResponse response = model.call(new Prompt("ping",
                    OpenAiChatOptions.builder().model(modelName).maxTokens(1).build()));
            long latency = System.currentTimeMillis() - start;
            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null ? "" : response.getResult().getOutput().getText();
            result.put("connected", true);
            result.put("method", "chat");
            result.put("status", 200);
            result.put("latencyMs", latency);
            result.put("message", "连接成功（已通过最小对话校验）");
            log.info("[AI连接测试] 成功: baseUrl={} model={} latency={}ms", config.baseUrl(), modelName, latency);
            return result;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.put("connected", false);
            result.put("method", "chat");
            result.put("status", 0);
            result.put("latencyMs", latency);
            result.put("message", "连接失败：" + detail);
            log.warn("[AI连接测试] 失败: baseUrl={} model={}：{}", config.baseUrl(), config.model(), detail);
            return result;
        }
    }
}
