package com.holzyn.actor.domain.action.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 行动决策实体，对应表 actor_action_plan。
 * <p>职责：存储行动引擎生成的行动决策（action_decision JSON，设计文档 §十一 Schema），
 * 记录触发源（manual/after_dialog/scheduled/event）、状态（planned/executing/done/cancelled）
 * 与计划执行时间；scheduled 触发源由定时任务按 planned_time 到期执行。</p>
 * <p>所属模块：model/entity（实体层-行动子域）</p>
 */
@Data
@Entity
@Table(name = "actor_action_plan")
public class ActorActionPlan {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色 ID（关联 actor_character.id） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 触发会话 ID（对话/事件触发时关联 actor_conversation.id，可空） */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 行动决策 JSON（action_decision，设计文档 §11.1 Schema） */
    @Column(name = "action_json", columnDefinition = "JSON")
    private String actionJson;

    /** 触发源：after_dialog / scheduled / event / manual */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType = "manual";

    /** 状态：planned / executing / done / cancelled */
    @Column(nullable = false, length = 20)
    private String status = "planned";

    /** 计划执行时间（scheduled 触发源使用；空表示立即执行） */
    @Column(name = "planned_time")
    private LocalDateTime plannedTime;

    /** 实际执行时间 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

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
        if (triggerType == null) triggerType = "manual";
        if (status == null) status = "planned";
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}