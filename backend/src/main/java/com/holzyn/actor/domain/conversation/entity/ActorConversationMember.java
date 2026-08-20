package com.holzyn.actor.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 群聊成员实体，对应表 actor_conversation_member。
 * <p>职责：记录一次会话包含哪些角色（单聊 1 个，群聊多个），
 * 供对话编排按成员加载角色卡与上下文。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_conversation_member")
public class ActorConversationMember {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（关联 actor_conversation.id） */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 角色 ID（关联 actor_character.id） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 加入时间 */
    @Column(name = "join_time", updatable = false)
    private LocalDateTime joinTime;

    /**
     * 插入前回调：填充加入时间。
     */
    @PrePersist
    void prePersist() {
        if (joinTime == null) joinTime = LocalDateTime.now();
    }
}