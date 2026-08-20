package com.holzyn.actor.domain.conversation.vo;

import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import java.time.LocalDateTime;

/**
 * 消息视图对象（MessageVO）。
 * <p>职责：向前端返回对话消息（含 SSE 状态与 token 用量）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record MessageVO(
        Long id,
        Long conversationId,
        Long characterId,
        String role,
        String type,
        String content,
        String status,
        Integer tokenIn,
        Integer tokenOut,
        LocalDateTime createdAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param m 消息实体
     * @return VO 对象
     */
    public static MessageVO of(ActorMessage m) {
        return new MessageVO(m.getId(), m.getConversationId(), m.getCharacterId(), m.getRole(),
                m.getType(), m.getContent(), m.getStatus(), m.getTokenIn(), m.getTokenOut(), m.getCreatedAt());
    }
}