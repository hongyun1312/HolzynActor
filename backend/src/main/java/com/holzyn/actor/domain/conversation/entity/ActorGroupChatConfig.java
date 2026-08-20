package com.holzyn.actor.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 群聊配置实体，对应表 actor_group_chat_config。
 * <p>职责：承载用户级群聊参数（当前为每轮回复上限），供前端修改并持久化，
 * 群聊编排时读取该配置决定每轮最多连续回复数。</p>
 * <p>所属模块：model/entity（实体层-对话子域）</p>
 */
@Data
@Entity
@Table(name = "actor_group_chat_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_id", columnNames = "user_id"))
public class ActorGroupChatConfig {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 ID（每个用户一条，唯一） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 每轮最多连续回复的角色数（默认 5，可 1~20） */
    @Column(name = "max_replies", nullable = false)
    private Integer maxReplies = 5;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (maxReplies == null) maxReplies = 5;
    }

    /** 更新前回调：刷新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}