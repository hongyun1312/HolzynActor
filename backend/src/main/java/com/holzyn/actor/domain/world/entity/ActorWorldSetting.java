package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界观设定实体，对应表 actor_world_setting。
 * <p>职责：存储项目的世界观设定——结构化字段（题材/时代/地理/势力/规则/文化/历史）
 * 与自由长文本（freeText，作为后续知识库注入源），支持按版本迭代。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_world_setting")
public class ActorWorldSetting {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 版本号（同一项目可多版本，保存时自增） */
    private Integer version = 1;

    /** 世界观名称 */
    @Column(length = 100)
    private String name;

    /** 题材（奇幻/科幻/都市/历史等） */
    @Column(length = 50)
    private String genre;

    /** 时代背景 */
    @Column(length = 50)
    private String era;

    /** 地理/地图设定 */
    @Column(columnDefinition = "TEXT")
    private String geography;

    /** 势力/阵营 */
    @Column(columnDefinition = "TEXT")
    private String factions;

    /** 规则体系（魔法/科技/规则） */
    @Column(name = "magic_system", columnDefinition = "TEXT")
    private String magicSystem;

    /** 文化/风俗 */
    @Column(columnDefinition = "TEXT")
    private String culture;

    /** 历史背景 */
    @Column(columnDefinition = "TEXT")
    private String history;

    /** 完整世界观自由文本（知识库注入源） */
    @Column(name = "free_text", columnDefinition = "LONGTEXT")
    private String freeText;

    /** 状态：0草稿/1生效 */
    private Integer status = 0;

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
        if (version == null) version = 1;
        if (status == null) status = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}