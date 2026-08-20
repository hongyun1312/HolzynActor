package com.holzyn.actor.domain.character.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色社会关系图实体，对应表 actor_character_relation。
 * <p>职责：记录角色之间 from→to 的社会关系（亲属/师徒/敌对等），
 * 用于角色卡生成时的「社会关系」输入与后续对话编排。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_character_relation")
public class ActorCharacterRelation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 关系发起方角色 ID */
    @Column(name = "from_character_id", nullable = false)
    private Long fromCharacterId;

    /** 关系目标角色 ID */
    @Column(name = "to_character_id", nullable = false)
    private Long toCharacterId;

    /** 关系发起方角色名（角色不存在时 id=0 仅存名称的「幽灵端点」兜底；存在时冗余存储便于展示/按名关联） */
    @Column(name = "from_name", length = 100)
    private String fromName;

    /** 关系目标角色名（同上，名称兜底） */
    @Column(name = "to_name", length = 100)
    private String toName;

    /** 关系类型（亲属/师徒/敌对等） */
    @Column(name = "relation_type", length = 50, nullable = false)
    private String relationType;

    /** 关系描述 */
    @Column(length = 255)
    private String description;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充创建时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}