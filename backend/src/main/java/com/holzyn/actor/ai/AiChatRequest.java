package com.holzyn.actor.ai;

import java.util.List;

/**
 * AI 对话补全请求。
 * <p>用途：承载一次 OpenAI 兼容 Chat Completions 调用的入参，供 AiProviderRouter 分发。
 * jsonMode 为 true 时请求体携带 response_format=json_object，引导模型输出合法 JSON
 * （部分供应商不支持时由业务层文本解析兜底）。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 *
 * @param model       模型名（为空时由 Provider 回退到配置默认模型）
 * @param messages    对话消息序列（role: system/user/assistant）
 * @param temperature 采样温度（可为 null，使用服务端默认）
 * @param maxTokens   最大输出 token（可为 null，使用服务端默认）
 * @param jsonMode    是否请求结构化 JSON 输出（默认 false）
 */
public record AiChatRequest(String model, List<ChatMessage> messages, Double temperature, Integer maxTokens, boolean jsonMode) {

    /**
     * 兼容旧签名：非结构化输出（jsonMode=false）。
     *
     * @param model       模型名
     * @param messages    对话消息序列
     * @param temperature 采样温度
     * @param maxTokens   最大输出 token
     */
    public AiChatRequest(String model, List<ChatMessage> messages, Double temperature, Integer maxTokens) {
        this(model, messages, temperature, maxTokens, false);
    }

    /**
     * 单条对话消息。
     *
     * @param role    消息角色（system/user/assistant）
     * @param content 消息内容
     */
    public record ChatMessage(String role, String content) {
    }
}