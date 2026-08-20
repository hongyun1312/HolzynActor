package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界演化参与者实体，对应表 actor_evolution_participant（V2.1）。
 * <p>职责：记录一次演化会话的参与角色及在场状态——加入（join_at）/ 退场（leave_at，status=left）；
 * 演化结束后，仅参与者（含已退场者）会获得归档事件的「角色级记忆」（记忆隔离：非当事人不知道）。</p>
 * <p>所属模块：model/entity（实体层-世界演化子域）</p>
 */
@Data
@Entity
@Table(name = "actor_evolution_participant",
        uniqueConstraints = @UniqueConstraint(name = "uk_ev_participant", columnNames = {"evolution_id", "character_id"}))
public class ActorEvolutionParticipant {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 演化会话 ID */
    @Column(name = "evolution_id", nullable = false)
    private Long evolutionId;

    /** 参与角色 ID */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 状态：active 在场 / left 已退场 */
    @Column(nullable = false, length = 10)
    private String status = "active";

    /** 加入时间 */
    @Column(name = "join_at")
    private LocalDateTime joinAt;

    /** 退场时间 */
    @Column(name = "leave_at")
    private LocalDateTime leaveAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (joinAt == null) joinAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = "active";
    }
}
