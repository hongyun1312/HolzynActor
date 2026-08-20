package com.holzyn.actor.domain.action.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 行动执行日志实体，对应表 actor_action_log。
 * <p>职责：记录行动模拟执行的每个时间线节点（摘要+详情），
 * 与行动决策一起构成「行动时间线」数据源；log_time 为行动发生时间。</p>
 * <p>所属模块：model/entity（实体层-行动子域）</p>
 */
@Data
@Entity
@Table(name = "actor_action_log")
public class ActorActionLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色 ID（关联 actor_character.id） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 关联行动决策 ID（关联 actor_action_plan.id，可空） */
    @Column(name = "plan_id")
    private Long planId;

    /** 行动摘要（时间线节点标题，如「前往城东市场采购药材」） */
    @Column(nullable = false, length = 255)
    private String summary;

    /** 行动详情（补充信息/结果） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 行动发生时间 */
    @Column(name = "log_time", nullable = false)
    private LocalDateTime logTime;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (logTime == null) logTime = now;
    }
}