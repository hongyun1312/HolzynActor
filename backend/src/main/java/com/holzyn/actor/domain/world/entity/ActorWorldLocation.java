package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界观地点表实体，对应表 actor_world_location。
 * <p>职责：存储项目世界观的地理位置——名称/类型/详细简介/重要度/排序。
 * 数据来源：① 新建项目「上传文件解析」流程的地理设定之后新增「地点提取」阶段（AI 从 geography
 * 文本识别并生成简介）；② 世界详情「地点详情」页手动增删改查与「AI 重新提取」。
 * 归属项目级（随 .holzyn 包导入导出）。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
@Data
@Entity
@Table(name = "actor_world_location")
public class ActorWorldLocation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 地点名称（必填，如「王都酒馆」「雾都」） */
    @Column(length = 100)
    private String name;

    /** 地点类型（城市/城镇/酒馆/森林/王国…） */
    @Column(length = 50)
    private String type;

    /** 详细简介（AI 生成或手动填写，≤2000 字） */
    @Column(columnDefinition = "TEXT")
    private String intro;

    /** 重要度（1-5，默认 3） */
    private Integer importance = 3;

    /** 排序（越小越靠前，默认 0） */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

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
        if (importance == null) importance = 3;
        if (sortOrder == null) sortOrder = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
