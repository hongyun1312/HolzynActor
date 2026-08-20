package com.holzyn.actor.domain.conversation.repository;

import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 群聊成员仓库（actor_conversation_member）。
 * <p>职责：提供按会话查询成员角色的能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorConversationMemberRepository extends JpaRepository<ActorConversationMember, Long> {

    /**
     * 查询某会话的全部成员。
     *
     * @param conversationId 会话 ID
     * @return 成员列表
     */
    List<ActorConversationMember> findByConversationId(Long conversationId);

    /**
     * 查询某角色参与的全部会话成员关系（记忆抽取/手动补抽定位会话用）。
     *
     * @param characterId 角色 ID
     * @return 成员关系列表
     */
    List<ActorConversationMember> findByCharacterId(Long characterId);

    /**
     * 删除某会话全部成员关系（会话删除时清理）。
     *
     * @param conversationId 会话 ID
     */
    void deleteByConversationId(Long conversationId);
}