package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界时钟实体，对应表 actor_world_clock（P4-2 世界持续模拟）。
 * <p>职责：承载每项目的游戏时钟状态——真实时间按速率映射为游戏时间：
 * 锚点（worldStartAt 真实时刻 + worldStartGameHour 对应游戏起始小时）与 rate（每真实小时推进的游戏小时数，
 * 默认 24=1 真实小时推进 1 游戏日）；lastSimTime/lastGameHour 记录最近一次模拟推进位置（惰性补算与幂等）；
 * paused 暂停开关；lastSummary 最近推进摘要（前端展示）。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_world_clock")
public class ActorWorldClock {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID（唯一，uk_world_clock_project） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 速率：每真实小时推进的游戏小时数（默认 24=1真实小时推进1游戏日） */
    @Column(nullable = false)
    private Integer rate = 24;

    /** 真实时刻锚点（默认=项目创建时刻） */
    @Column(name = "world_start_at")
    private LocalDateTime worldStartAt;

    /** 锚点对应的游戏起始时刻（小时数，自纪元起，默认 0） */
    @Column(name = "world_start_game_hour", nullable = false)
    private Long worldStartGameHour = 0L;

    /** 最近一次模拟推进的真实时刻（null=尚未首次推进） */
    @Column(name = "last_sim_time")
    private LocalDateTime lastSimTime;

    /** 最近推进到的游戏时刻（小时数，自纪元起） */
    @Column(name = "last_game_hour", nullable = false)
    private Long lastGameHour = 0L;

    /** 暂停开关：0推进/1暂停 */
    @Column(nullable = false)
    private Integer paused = 0;

    /** 暂停时刻（真实时刻）：暂停时记录用于冻结游戏时间；未暂停为 null */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /** 最近推进摘要（供前端展示） */
    @Column(name = "last_summary", length = 500)
    private String lastSummary;

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
        if (rate == null) rate = 24;
        if (worldStartGameHour == null) worldStartGameHour = 0L;
        if (lastGameHour == null) lastGameHour = 0L;
        if (paused == null) paused = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
