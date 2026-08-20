package com.holzyn.actor.domain.conversation.vo;

import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话视图对象（ConversationVO）。
 * <p>职责：向前端返回会话信息（含参与角色 ID 列表，供前端渲染对话上下文）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record ConversationVO(
        Long id,
        Long projectId,
        Long userId,
        String mode,
        String title,
        String location,
        String gameTimeText,
        Integer worldEventEnabled,
        List<Long> characterIds,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体与成员 ID 列表构造 VO。
     *
     * @param c            会话实体
     * @param characterIds 参与角色 ID 列表
     * @return VO 对象
     */
    public static ConversationVO of(ActorConversation c, List<Long> characterIds) {
        return new ConversationVO(c.getId(), c.getProjectId(), c.getUserId(), c.getMode(), c.getTitle(),
                c.getLocation(), c.getGameTimeText(), c.getWorldEventEnabled(), characterIds,
                c.getLastMessageAt(), c.getCreatedAt(), c.getUpdatedAt());
    }
}