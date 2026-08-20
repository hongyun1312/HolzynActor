package com.holzyn.actor.ai;

import com.holzyn.actor.domain.settings.service.ModelApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * AI Provider 路由（设计文档 §8，后端项目化改造 V2.0）。
 * <p>职责：根据用户、项目与指定的 API 配置（providerId，可空=取项目级/用户级默认），
 * 从 ModelApiService 解析出运行时调用凭据（含解密 Key），路由到具体 Provider
 * （当前仅 OpenAI 兼容协议）执行对话补全或连通性测试，供对话 / 角色卡等业务层直接选用。</p>
 * <p>项目化（V2.0）：所有解析按 userId + projectId 维度，「项目级优先、用户级回退」；
 * 归属隔离：均按 userId + projectId + providerId 双重校验，防止跨用户/跨项目越权调用。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 */
@Component
@RequiredArgsConstructor
public class AiProviderRouter {

    /** 用户/项目 AI API 配置服务（负责凭据解析与解密） */
    private final ModelApiService modelApiService;

    /** OpenAI 兼容协议 Provider（当前唯一实现） */
    private final OpenAiCompatibleProvider openAiCompatibleProvider;

    /**
     * 执行一次非流式对话补全（业务层入口）。
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID（NULL=仅用户级配置）
     * @param providerId API 配置主键（可空=取项目级/用户级默认配置）
     * @param request    对话请求
     * @return 对话补全结果（含用量统计）
     */
    public AiChatResult chatCompletion(Long userId, Long projectId, Long providerId, AiChatRequest request) {
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, providerId);
        return openAiCompatibleProvider.chatCompletion(config, request);
    }

    /**
     * 对指定配置执行连通性测试（业务层入口，供 API 管理页调用）。
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID（NULL=仅用户级配置）
     * @param providerId API 配置主键（已入库）
     * @return 测试结果 Map（connected/method/status/message/latencyMs）
     */
    public Map<String, Object> testProvider(Long userId, Long projectId, Long providerId) {
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, providerId);
        return openAiCompatibleProvider.testConnection(config.baseUrl(), config.apiKey(), config.model());
    }


    /**
     * 执行一次流式对话补全（业务层入口，P1-4 单聊 SSE 使用）。
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID（NULL=仅用户级配置）
     * @param providerId API 配置主键（可空=取项目级/用户级默认配置）
     * @param request    对话请求
     * @param onToken    每个增量 token 的回调（供 SSE 转发）
     * @param onUsage    结束时的用量回调（AiUsage：prompt/completion/cacheHit/cacheMiss token）
     */
    public void chatCompletionStream(Long userId, Long projectId, Long providerId, AiChatRequest request,
                                     Consumer<String> onToken, Consumer<AiUsage> onUsage) {
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, providerId);
        openAiCompatibleProvider.chatCompletionStream(config, request, onToken, onUsage);
    }
}
