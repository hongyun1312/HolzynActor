package com.holzyn.actor.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会话实体，对应表 actor_conversation。
 * <p>职责：承载一次对话会话（单聊 single / 群聊 group），记录归属用户、模式、
 * 标题与世界事件注入开关；lastMessageAt 用于会话列表排序。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_conversation")
public class ActorConversation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 模式：single 单聊 / group 群聊 */
    @Column(length = 10, nullable = false)
    private String mode = "single";

    /** 会话标题 */
    @Column(length = 100)
    private String title;

    /** 对话所在地（场景地点；空=通过手机等远程通讯软件对话） */
    @Column(length = 200)
    private String location;

    /** 对话发生时的世界时间快照（世界时钟 gameTimeText，如「世界历 0025年03月12日 14时30分」） */
    @Column(name = "game_time_text", length = 120)
    private String gameTimeText;

    /** 是否启用世界事件注入：0否/1是 */
    @Column(name = "world_event_enabled")
    private Integer worldEventEnabled = 0;

    /** 最后消息时间（列表排序用） */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (mode == null) mode = "single";
        if (worldEventEnabled == null) worldEventEnabled = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}