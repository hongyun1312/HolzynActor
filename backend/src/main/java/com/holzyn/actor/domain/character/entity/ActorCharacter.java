package com.holzyn.actor.domain.character.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色主表实体，对应表 actor_character。
 * <p>职责：存储 NPC 角色基础档案——姓名/头衔/类型（特殊型 special / 普通型 common）、
 * 是否主角与重要度（重要度决定 AI 投入成本）；P2 起新增行动模拟状态字段
 * current_activity / location，用于行动引擎模拟执行后持久化角色状态（P4 世界状态基础）。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_character")
public class ActorCharacter {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 类型：special 特殊型 / common 普通型 */
    @Column(length = 10, nullable = false)
    private String type = "special";

    /** 角色姓名 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 头衔 */
    @Column(length = 50)
    private String title;

    /** 角色详细信息（用户自行输入的完整档案；角色卡生成的知识源） */
    @Column(columnDefinition = "LONGTEXT")
    private String detail;

    /** 头像 URL */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    /** 是否主角：0否/1是 */
    @Column(name = "is_protagonist")
    private Integer isProtagonist = 0;

    /** 重要度（1-5，决定 AI 投入） */
    private Integer importance = 1;

    /** 状态：0草稿/1正常 */
    private Integer status = 0;

    /** 软删除标记 */
    private Integer deleted = 0;

    /** 当前行动描述（行动引擎模拟执行后更新，如「前往城东市场采购药材」） */
    @Column(name = "current_activity", length = 255)
    private String currentActivity;

    /** 当前位置（行动引擎模拟执行后更新，如「城东市场」） */
    @Column(length = 255)
    private String location;

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
        if (type == null) type = "special";
        if (isProtagonist == null) isProtagonist = 0;
        if (importance == null) importance = 1;
        if (status == null) status = 0;
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