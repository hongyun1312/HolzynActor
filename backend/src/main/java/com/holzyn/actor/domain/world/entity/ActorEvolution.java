package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界演化会话实体，对应表 actor_evolution（V2.1）。
 * <p>职责：一次「世界演化」的会话主表——可选择全局（scene_id NULL）或指定场景（scene_id 非空）、
 * 背景（background）与参与角色（participants）；模式分手动选择 / AI 自动选择；
 * 状态 running/finished；结束后 AI 生成收尾摘要（ai_summary）并归档为事件（event_id）。</p>
 * <p>所属模块：model/entity（实体层-世界演化子域）</p>
 */
@Data
@Entity
@Table(name = "actor_evolution")
public class ActorEvolution {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 场景 ID（NULL=全局/世界演化） */
    @Column(name = "scene_id")
    private Long sceneId;

    /** 演化标题 */
    @Column(length = 200)
    private String title;

    /** 演化背景设定（用户/AI 提供，覆盖/补充场景背景） */
    @Column(columnDefinition = "TEXT")
    private String background;

    /** 创建方式：manual 手动选择 / ai AI自动选择 */
    @Column(nullable = false, length = 10)
    private String mode = "manual";

    /** 状态：running 进行中 / finished 已结束归档 */
    @Column(nullable = false, length = 10)
    private String status = "running";

    /** 已推进轮次数 */
    @Column(name = "turn_count")
    private Integer turnCount = 0;

    /** 归档事件 ID（结束后非空） */
    @Column(name = "event_id")
    private Long eventId;

    /** AI 生成的收尾摘要 */
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    /** 结束时间 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

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
        if (mode == null || mode.isBlank()) mode = "manual";
        if (status == null || status.isBlank()) status = "running";
        if (turnCount == null) turnCount = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
