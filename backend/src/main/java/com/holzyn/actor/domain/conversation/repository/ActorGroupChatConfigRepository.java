package com.holzyn.actor.domain.conversation.repository;

import com.holzyn.actor.domain.conversation.entity.ActorGroupChatConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 群聊配置仓库。
 * <p>职责：按用户查询/保存 actor_group_chat_config（每用户一条）。</p>
 * <p>所属模块：repository（数据访问层-对话子域）</p>
 */
public interface ActorGroupChatConfigRepository extends JpaRepository<ActorGroupChatConfig, Long> {

    /**
     * 按用户查询群聊配置（无记录时返回空）。
     *
     * @param userId 归属用户 ID
     * @return 配置（可空）
     */
    Optional<ActorGroupChatConfig> findByUserId(Long userId);
}