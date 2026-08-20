package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 场景/地点实体，对应表 actor_scene（V2.1）。
 * <p>职责：存储项目内的场景/地点设定（如咖啡店、酒馆、城门口）——名称/描述/地点/
 * 背景设定（世界演化 AI 注入用）；世界演化可选择指定场景或全局（世界）进行。</p>
 * <p>所属模块：model/entity（实体层-场景子域）</p>
 */
@Data
@Entity
@Table(name = "actor_scene")
public class ActorScene {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 场景/地点名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 场景描述（一句话） */
    @Column(length = 500)
    private String description;

    /** 地点/位置 */
    @Column(length = 200)
    private String location;

    /** 场景背景设定（世界演化 AI 注入用，可含环境/氛围/在场人员默认） */
    @Column(columnDefinition = "TEXT")
    private String background;

    /** 来源说明（AI 自动生成时记录依据：基于世界观/角色哪些设定创建；手动创建为 null） */
    @Column(columnDefinition = "TEXT")
    private String source;

    /** 启用：0否/1是 */
    private Integer enabled = 1;

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
        if (enabled == null) enabled = 1;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
