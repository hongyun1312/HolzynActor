package com.holzyn.actor.domain.conversation.repository;

import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 消息仓库（actor_message）。
 * <p>职责：提供按会话查询消息历史与最新消息的能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorMessageRepository extends JpaRepository<ActorMessage, Long> {

    /**
     * 查询某会话全部消息（按创建顺序升序）。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    List<ActorMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /**
     * 查询某会话最新一条消息。
     *
     * @param conversationId 会话 ID
     * @return 最新消息（可能为空）
     */
    Optional<ActorMessage> findTopByConversationIdOrderByIdDesc(Long conversationId);

    /**
     * 删除某会话全部消息（会话删除时清理）。
     *
     * @param conversationId 会话 ID
     */
    void deleteByConversationId(Long conversationId);

    /**
     * 按类型 + 会话集合查询消息（项目级时间线聚合世界事件使用）。
     *
     * @param type  消息类型（event）
     * @param conversationIds 会话 ID 集合
     * @return 消息列表
     */
    List<ActorMessage> findByTypeAndConversationIdInOrderByIdAsc(String type, Collection<Long> conversationIds);
}