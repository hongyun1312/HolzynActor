package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目事件实体，对应表 actor_event（时间线节点，V2.1）。
 * <p>职责：存储项目级事件（时间线统一节点）——手动新增 / AI 从世界观识别生成 /
 * 世界演化归档 / 世界模拟系统事件；kind 区分事件性质、source 区分来源；
 * 关联场景（scene_id）与演化会话（evolution_id）支持「仅场景当事人记忆」的归档追溯。</p>
 * <p>所属模块：model/entity（实体层-时间线/事件子域）</p>
 */
@Data
@Entity
@Table(name = "actor_event")
public class ActorEvent {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 事件类型：event 世界事件 / manual 手动 / evolution 演化归档 / system 系统 */
    @Column(nullable = false, length = 20)
    private String kind = "event";

    /** 事件标题 */
    @Column(length = 200)
    private String title;

    /** 事件内容（含时间地点经过影响） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 关联角色 ID（可空） */
    @Column(name = "character_id")
    private Long characterId;

    /** 关联场景 ID（演化场景，可空） */
    @Column(name = "scene_id")
    private Long sceneId;

    /** 关联演化会话 ID（演化归档时非空） */
    @Column(name = "evolution_id")
    private Long evolutionId;

    /** 来源：manual 手动 / ai AI识别 / simulation 世界模拟 / evolution 演化归档 / dialog 对话抽取 */
    @Column(nullable = false, length = 20)
    private String source = "manual";

    /** 游戏时刻（小时数，自纪元起，可空） */
    @Column(name = "game_hour")
    private Long gameHour;

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
        if (kind == null || kind.isBlank()) kind = "event";
        if (source == null || source.isBlank()) source = "manual";
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
