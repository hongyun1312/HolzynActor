package com.holzyn.actor.domain.memory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 长期记忆实体，对应表 actor_memory（P4 长期记忆 A-C7 P2）。
 * <p>职责：存储对话后 AI 自动抽取的关键事实与会话摘要——双粒度归属：
 * 角色级（character_id 非空，仅该角色注入）+ 项目级（character_id=NULL，世界大事记，所有角色可见）；
 * kind 区分 fact 关键事实 / summary 会话摘要；importance 1~5 决定注入优先级与滚动淘汰顺序；
 * deleted 软删（管理删除，不物理删除保留审计）。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_memory")
public class ActorMemory {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级记忆归属（关联 actor_project.id；NULL=历史旧数据未归属，P4 起必填） */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色 ID（NULL=项目级记忆；非空=该角色记忆） */
    @Column(name = "character_id")
    private Long characterId;

    /** 类型：fact 关键事实 / summary 会话摘要 */
    @Column(length = 20, nullable = false)
    private String kind = "fact";

    /** 记忆内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 重要度（1-5，低重要度滚动淘汰） */
    private Integer importance = 1;

    /** 软删除标记：0正常/1已删 */
    private Integer deleted = 0;

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
        if (kind == null) kind = "fact";
        if (importance == null) importance = 1;
        if (deleted == null) deleted = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
