package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.action.service.ActionEngine;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 世界事件服务（P2 阶段一，P4 世界模拟自动事件扩展点）。
 * <p>职责：承载世界事件注入——手填文本或按 world_event 模板 AI 生成事件，
 * 落库为 type=event 消息（角色 system 角色）后返回，前端随之打开 SSE 触发在场角色回应。
 * 事件消息对所有在场角色可见，作为对话/行动的情境上下文。</p>
 * <p>所属模块：service/conversation（对话子域-世界事件）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEventService {

    /** 事件生成输出最大 token */
    private static final int EVENT_MAX_TOKENS = 1024;

    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final AiProviderRouter aiProviderRouter;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    private final ActionEngine actionEngine;
    private final ObjectMapper objectMapper;

    /**
     * 注入世界事件：手填文本或 AI 生成 → 落 type=event 消息 → 返回供前端触发角色回应。
     *
     * @param conversationId 会话主键
     * @param userId         归属用户 ID
     * @param body           入参：{text? 手填事件文本, generate? 是否 AI 生成}
     * @return Map：eventMessageId / mode / reaction（true 表示前端应打开 SSE 触发回应）
     */
    @Transactional
    public Map<String, Object> inject(Long conversationId, Long userId, Map<String, Object> body) {
        // 归属校验用显式 userId：本方法会被世界模拟定时线程调用（无 SecurityContext），
        // 默认 requireOwned(id) 会回退演示用户导致真实用户会话误判无权访问
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        String text = body == null ? null : str(body.get("text"));
        boolean generate = body != null && Boolean.TRUE.equals(body.get("generate"));

        String eventContent;
        if (text != null && !text.isBlank()) {
            eventContent = text.trim();
        } else if (generate) {
            eventContent = generateByAi(userId, conv);
        } else {
            throw new BizException(400, "请提供事件文本或开启 AI 生成（generate=true）");
        }

        // 事件消息：role=system、type=event、状态 done（非对话消息，不参与流式占位）
        ActorMessage eventMsg = new ActorMessage();
        eventMsg.setConversationId(conversationId);
        eventMsg.setRole("system");
        eventMsg.setType("event");
        eventMsg.setContent(eventContent);
        eventMsg.setStatus("done");
        messageRepository.save(eventMsg);

        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        // 世界事件后自动触发在场重要角色的行动（event 触发源，异步）
        actionEngine.triggerForEventAsync(conversationId, userId);

        return Map.of("eventMessageId", eventMsg.getId(), "mode", conv.getMode(),
                "reaction", true, "content", eventContent);
    }

    /**
     * 「无玩家轮次」预留：自主推进一轮群聊（P4 世界模拟使用，P2 验证接口）。
     *
     * @param conversationId 会话主键
     * @param userId         归属用户 ID
     * @return Map：mode / autonomous
     */
    @Transactional(readOnly = true)
    public Map<String, Object> advance(Long conversationId, Long userId) {
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        if (!"group".equals(conv.getMode())) {
            throw new BizException(400, "「无玩家轮次」仅支持群聊会话");
        }
        return Map.of("mode", "group", "autonomous", true,
                "message", "自主推进已就绪，请打开 SSE 流式通道触发本轮对话");
    }

    /**
     * AI 生成世界事件（world_event 模板 + json_object 输出）。
     *
     * @param userId 归属用户 ID
     * @param conv   会话实体
     * @return 事件文本（标题 + 内容）
     */
    private String generateByAi(Long userId, ActorConversation conv) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(conv.getProjectId())
                .map(s -> s.getFreeText()).orElse("");
        String context = "会话模式：" + ("group".equals(conv.getMode()) ? "群聊" : "单聊")
                + "；会话ID：" + conv.getId();
        String prompt = promptService.buildWorldEventPrompt(userId, conv.getProjectId(), worldSetting, context);
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), 0.8, EVENT_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        AiChatResult result = aiProviderRouter.chatCompletion(userId, conv.getProjectId(), null, req);
        usageLogService.record(userId, conv.getProjectId(), null, result.providerId(), result.model(), "dialog",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        String json = JsonUtil.extractJson(result.content());
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("世界事件");
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                throw new BizException(400, "AI 生成的世界事件内容为空");
            }
            return title + "\n" + content;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "世界事件生成失败：AI 输出无法解析，请重试或手填事件");
        }
    }

    /**
     * 取值辅助：Object 转字符串。
     *
     * @param v 原始对象
     * @return 字符串（null 时返回 null）
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}