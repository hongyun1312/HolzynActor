package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界演化轮次消息实体，对应表 actor_evolution_turn（V2.1）。
 * <p>职责：记录演化会话的消息流——角色对话（text/assistant）、角色行动（action）、
 * 系统环境变化（system）、事件卡片（event）；character_id NULL=系统/环境消息。
 * 与 .holzyn V2.0 events.jsonl 的 kind=system/event/action 对齐。</p>
 * <p>所属模块：model/entity（实体层-世界演化子域）</p>
 */
@Data
@Entity
@Table(name = "actor_evolution_turn")
public class ActorEvolutionTurn {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 演化会话 ID */
    @Column(name = "evolution_id", nullable = false)
    private Long evolutionId;

    /** 归属角色 ID（NULL=系统/环境） */
    @Column(name = "character_id")
    private Long characterId;

    /** 角色：system / assistant / user */
    @Column(nullable = false, length = 10)
    private String role = "assistant";

    /** 消息类型：text 对话 / action 行动 / system 系统环境变化 / event 事件 */
    @Column(nullable = false, length = 20)
    private String type = "text";

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 游戏时刻（小时数，可空） */
    @Column(name = "game_hour")
    private Long gameHour;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (role == null || role.isBlank()) role = "assistant";
        if (type == null || type.isBlank()) type = "text";
    }
}
